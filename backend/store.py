from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import sqlite3
from difflib import SequenceMatcher
from uuid import uuid4

from .ai import has_correlation_context, now_ms
from .signals import verification_signals

STRUCTURED_FIELDS = ("emergency_type", "location_text", "floor", "room", "people_affected", "vulnerability")
STAGES = ("intake", "correlation", "verification", "triage")
GATEWAY_LEASE_MS = 120_000


def initial_stages(moment: int | None = None) -> dict:
    return {name: {"status": "queued", "updated_at": moment or now_ms(), "error": None} for name in STAGES}


class StoreError(Exception):
    def __init__(self, status: int, detail: str):
        self.status, self.detail = status, detail
        super().__init__(detail)


def dump(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class Store:
    def __init__(self, path: str):
        self.path = str(Path(path).resolve())
        self.media_root = Path(self.path + ".media")
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS incidents (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, building TEXT, zone TEXT,
                    status TEXT NOT NULL DEFAULT 'new', category TEXT, team TEXT,
                    acknowledged_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    merged_into TEXT REFERENCES incidents(id), revision INTEGER NOT NULL DEFAULT 1,
                    triage_status TEXT NOT NULL DEFAULT 'queued', triage_error TEXT, triage_json TEXT
                );
                CREATE TABLE IF NOT EXISTS reports (
                    id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, packet_json TEXT NOT NULL,
                    incident_id TEXT NOT NULL REFERENCES incidents(id), received_at INTEGER NOT NULL,
                    ai_status TEXT NOT NULL DEFAULT 'queued', ai_error TEXT, intake_json TEXT
                );
                CREATE INDEX IF NOT EXISTS reports_incident ON reports(incident_id);
                CREATE TABLE IF NOT EXISTS correlations (
                    id TEXT PRIMARY KEY, incident_a_id TEXT NOT NULL REFERENCES incidents(id),
                    incident_b_id TEXT NOT NULL REFERENCES incidents(id), confidence REAL NOT NULL,
                    reason TEXT NOT NULL, evidence_json TEXT NOT NULL, model TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL,
                    decided_at INTEGER, survivor_id TEXT REFERENCES incidents(id)
                );
                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, target_id TEXT NOT NULL,
                    stage TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0,
                    available_at INTEGER NOT NULL, last_error TEXT, rerun_requested INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(kind,target_id)
                );
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL,
                    action TEXT NOT NULL, entity_id TEXT NOT NULL, details_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS media_analysis (
                    report_id TEXT NOT NULL REFERENCES reports(id), attachment_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0,
                    available_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    error TEXT, result_json TEXT, PRIMARY KEY(report_id,attachment_id)
                );
            """)
            # Upgrade the initial prototype schema without losing suggestion history. A pair
            # can be proposed again after reanalysis supersedes its previous generation.
            correlation_schema = db.execute("SELECT sql FROM sqlite_master WHERE name='correlations'").fetchone()[0]
            if "UNIQUE(incident_a_id, incident_b_id)" in correlation_schema:
                db.execute("BEGIN IMMEDIATE")
                db.execute("""CREATE TABLE correlations_new (
                    id TEXT PRIMARY KEY, incident_a_id TEXT NOT NULL REFERENCES incidents(id),
                    incident_b_id TEXT NOT NULL REFERENCES incidents(id), confidence REAL NOT NULL,
                    reason TEXT NOT NULL, evidence_json TEXT NOT NULL, model TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL,
                    decided_at INTEGER, survivor_id TEXT REFERENCES incidents(id))""")
                db.execute("INSERT INTO correlations_new SELECT * FROM correlations")
                db.execute("DROP TABLE correlations")
                db.execute("ALTER TABLE correlations_new RENAME TO correlations")
            db.execute("CREATE INDEX IF NOT EXISTS correlations_pair ON correlations(incident_a_id,incident_b_id)")
            columns = {row[1] for row in db.execute("PRAGMA table_info(incidents)")}
            for name, declaration in {
                "processing_json": "TEXT", "verification_json": "TEXT", "verification_signals_json": "TEXT",
                "verification_status": "TEXT NOT NULL DEFAULT 'unverified'", "verification_notes": "TEXT",
                "verification_updated_at": "INTEGER",
                "human_verification_json": "TEXT",
            }.items():
                if name not in columns:
                    db.execute(f"ALTER TABLE incidents ADD COLUMN {name} {declaration}")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS gateways (
                    node_id TEXT NOT NULL, simulation INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, PRIMARY KEY(node_id,simulation)
                );
                CREATE TABLE IF NOT EXISTS receipts (
                    id TEXT PRIMARY KEY, report_id TEXT NOT NULL REFERENCES reports(id),
                    type TEXT NOT NULL, receipt_json TEXT NOT NULL, timestamp INTEGER NOT NULL,
                    UNIQUE(report_id,type)
                );
                CREATE TABLE IF NOT EXISTS delivery_observations (
                    report_id TEXT NOT NULL REFERENCES reports(id), gateway_id TEXT NOT NULL,
                    relay_path_json TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(report_id,gateway_id,relay_path_json)
                );
            """)
            db.execute("BEGIN IMMEDIATE")
            for report in db.execute("SELECT * FROM reports").fetchall():
                packet = json.loads(report["packet_json"])
                self.ensure_receipt(db, report, "backend_received", report["received_at"], packet["relay_path"][-1])
            for incident in db.execute("SELECT * FROM incidents WHERE processing_json IS NULL").fetchall():
                stages = initial_stages(incident["updated_at"])
                reports = db.execute("SELECT intake_json FROM reports WHERE incident_id=?", (incident["id"],)).fetchall()
                if reports and all(r[0] for r in reports):
                    stages["intake"]["status"] = "complete"
                if incident["triage_json"]:
                    stages["triage"]["status"] = "complete"
                if db.execute("SELECT 1 FROM audit WHERE action='correlation.completed' AND entity_id=?", (incident["id"],)).fetchone():
                    stages["correlation"]["status"] = "complete"
                db.execute("UPDATE incidents SET processing_json=? WHERE id=?", (dump(stages), incident["id"]))
                if not incident["merged_into"]:
                    self.enqueue(db, "incident", incident["id"])
            self.supersede_obsolete_failures(db)

    @staticmethod
    def fingerprint(packet: dict) -> str:
        immutable = {key: value for key, value in packet.items() if key not in ("hop_count", "relay_path")
                     and not (key in STRUCTURED_FIELDS and value is None)}
        return hashlib.sha256(json.dumps(immutable, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    @staticmethod
    def ensure_receipt(db, report, kind: str, timestamp: int, gateway_id: str | None) -> dict:
        existing = db.execute("SELECT receipt_json FROM receipts WHERE report_id=? AND type=?", (report["id"], kind)).fetchone()
        if existing:
            return json.loads(existing[0])
        packet = json.loads(report["packet_json"])
        receipt = {"id": str(uuid4()), "report_id": report["id"], "incident_id": report["incident_id"],
                   "type": kind, "timestamp": timestamp, "gateway_id": gateway_id,
                   "simulation": packet["simulation"], "issuer": "resqmesh_backend",
                   "trust": "backend_issued_unattested", "relay_path": packet["relay_path"]}
        db.execute("INSERT INTO receipts(id,report_id,type,receipt_json,timestamp) VALUES(?,?,?,?,?)",
                   (receipt["id"], report["id"], kind, dump(receipt), timestamp))
        return receipt

    @staticmethod
    def record_gateway(db, node_id: str, simulation: bool, moment: int) -> dict:
        db.execute("""INSERT INTO gateways(node_id,simulation,last_seen,expires_at) VALUES(?,?,?,?)
            ON CONFLICT(node_id,simulation) DO UPDATE SET last_seen=excluded.last_seen,expires_at=excluded.expires_at""",
            (node_id, int(simulation), moment, moment + GATEWAY_LEASE_MS))
        return {"node_id": node_id, "simulation": simulation, "last_seen": moment,
                "expires_at": moment + GATEWAY_LEASE_MS, "online": True}

    def heartbeat(self, node_id: str, simulation: bool) -> dict:
        with self.connection(transaction=True) as db:
            return self.record_gateway(db, node_id, simulation, now_ms())

    def record_delivery(self, db, packet: dict, moment: int):
        gateway_id = packet["relay_path"][-1]
        self.record_gateway(db, gateway_id, packet["simulation"], moment)
        db.execute("""INSERT INTO delivery_observations(report_id,gateway_id,relay_path_json,first_seen,last_seen)
            VALUES(?,?,?,?,?) ON CONFLICT(report_id,gateway_id,relay_path_json) DO UPDATE SET
            last_seen=excluded.last_seen,attempts=delivery_observations.attempts+1""",
            (packet["id"], gateway_id, dump(packet["relay_path"]), moment, moment))

    def receipts(self, report_id: str) -> dict:
        with self.connection() as db:
            return {"receipts": [json.loads(row[0]) for row in db.execute("SELECT receipt_json FROM receipts WHERE report_id=? ORDER BY timestamp,id", (report_id,))], "server_time": now_ms()}

    @staticmethod
    def write_stage(db, incident_id: str, stage: str, status: str, error: str | None = None):
        row = db.execute("SELECT processing_json FROM incidents WHERE id=?", (incident_id,)).fetchone()
        stages = json.loads(row[0]) if row and row[0] else initial_stages()
        stages[stage] = {"status": status, "updated_at": now_ms(), "error": error}
        db.execute("UPDATE incidents SET processing_json=?,updated_at=? WHERE id=?", (dump(stages), now_ms(), incident_id))

    def stage_status(self, context: dict, stage: str, status: str, error: str | None = None) -> bool:
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, context["id"], active=False)
            if row["merged_into"] or row["revision"] != context["revision"]:
                return False
            self.write_stage(db, context["id"], stage, status, error)
            return True

    def invalidate_analysis(self, db, incident_id: str):
        for stage in ("correlation", "verification", "triage"):
            self.write_stage(db, incident_id, stage, "queued")
        db.execute("UPDATE incidents SET verification_json=NULL,verification_signals_json=NULL,triage_json=NULL,triage_status='queued',triage_error=NULL WHERE id=?", (incident_id,))

    def verification_context(self, source: dict) -> list[dict]:
        reports = source["reports"]
        if not reports:
            return []
        mode = reports[0]["simulation"]
        source_ids = {r["id"] for r in reports}
        source_buildings = {r["building"].strip().casefold() for r in reports if r.get("building")}
        source_texts = [" ".join(r["text"].casefold().split()) for r in reports if r.get("message_source") != "preset"]
        with self.connection() as db:
            recent = [self.report_dict(row) for row in db.execute("SELECT * FROM reports WHERE received_at>=? ORDER BY received_at DESC LIMIT 48", (now_ms() - 900_000,))]
        chosen = []
        budget = len(dump(reports))
        for report in recent:
            if report["id"] in source_ids or report["simulation"] != mode:
                continue
            same_building = bool(report.get("building") and report["building"].strip().casefold() in source_buildings)
            normalized = " ".join(report["text"].casefold().split())
            similar_text = report.get("message_source") != "preset" and any(SequenceMatcher(None, text, normalized).ratio() >= .85 for text in source_texts)
            if not same_building and not similar_text:
                continue
            size = len(dump(report))
            if budget + size > 12_000:
                continue
            chosen.append(report)
            budget += size
            if len(chosen) == 8:
                break
        return chosen

    def save_verification(self, context: dict, signals: list[dict], result: dict) -> bool:
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, context["id"], active=False)
            if row["merged_into"] or row["revision"] != context["revision"]:
                self.enqueue(db, "incident", row["merged_into"] or row["id"])
                return False
            db.execute("UPDATE incidents SET verification_json=?,verification_signals_json=?,triage_json=NULL,triage_status='queued',triage_error=NULL,updated_at=? WHERE id=?",
                       (dump(result), dump(signals), now_ms(), context["id"]))
            self.write_stage(db, context["id"], "verification", "complete")
            self.write_stage(db, context["id"], "triage", "queued")
            self.audit(db, "ai", "verification.completed", context["id"], {"model": result["model"], "suggested_state": result["suggested_state"], "operator_decision": False, "source_independence": "unknown", "signal_ids": [s["id"] for s in signals]})
            return True

    def verification_action(self, incident_id: str, action: str, notes: str | None, *,
                            reviewer_label: str | None = None, check_method: str | None = None,
                            evidence_reference: str | None = None, checked_at: int | None = None) -> dict:
        mapping = {"request_verification": "verification_requested", "corroborated": "corroborated",
                   "responder_verified": "responder_verified", "false_closed": "false_closed"}
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, incident_id)
            metadata = {"reviewer_label": reviewer_label, "check_method": check_method,
                        "evidence_reference": evidence_reference, "checked_at": checked_at}
            previous_review = json.loads(row["human_verification_json"]) if row["human_verification_json"] else None
            same_metadata = metadata == {key: (previous_review or {}).get(key) for key in metadata}
            # A legacy notes-only replay must not erase metadata from a richer decision.
            no_metadata = all(value is None for value in metadata.values())
            if row["verification_status"] == mapping[action] and row["verification_notes"] == notes and (same_metadata or no_metadata):
                return self.incident_dict(db, row)
            moment = now_ms()
            from .security import current_actor
            authenticated_account = current_actor.get()
            review = {"action": action, "notes": notes, **metadata, "recorded_at": moment,
                      "authenticated_account": authenticated_account,
                      "identity_assurance": "authenticated_account_self_reported_reviewer" if authenticated_account else "self_reported_not_authenticated",
                      "evidence_validation": "not_independently_validated"}
            status = "resolved" if action == "false_closed" else row["status"]
            db.execute("UPDATE incidents SET verification_status=?,verification_notes=?,verification_updated_at=?,human_verification_json=?,status=?,updated_at=? WHERE id=?",
                       (mapping[action], notes, moment, dump(review), status, moment, incident_id))
            self.audit(db, "operator", "verification." + action, incident_id,
                       {"notes": notes, "before": row["verification_status"], "after": mapping[action],
                        "human_decision": True, "human_verification": review, "previous_human_verification": previous_review})
            return self.incident_dict(db, self.require_incident(db, incident_id))

    @contextmanager
    def connection(self, transaction: bool = False):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=10000")
        try:
            if transaction:
                db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def audit(db, actor: str, action: str, entity_id: str, details: dict):
        if actor == "operator":
            from .security import current_actor
            actor = current_actor.get() or actor
        db.execute("INSERT INTO audit(at,actor,action,entity_id,details_json) VALUES(?,?,?,?,?)",
                   (now_ms(), actor, action, entity_id, dump(details)))

    @staticmethod
    def enqueue(db, kind: str, target: str):
        moment = now_ms()
        stage = "intake" if kind in ("report", "incident") else "triage"
        existing = db.execute("SELECT * FROM jobs WHERE kind=? AND target_id=?", (kind, target)).fetchone()
        if existing and existing["status"] == "running":
            db.execute("UPDATE jobs SET rerun_requested=1,updated_at=? WHERE id=?", (moment, existing["id"]))
        else:
            db.execute("""INSERT INTO jobs(kind,target_id,stage,status,available_at,created_at,updated_at)
                VALUES(?,?,?,'queued',?,?,?) ON CONFLICT(kind,target_id) DO UPDATE SET
                stage=excluded.stage,status='queued',attempts=0,available_at=excluded.available_at,
                last_error=NULL,rerun_requested=0,updated_at=excluded.updated_at""",
                (kind, target, stage, moment, moment, moment))

    def media_path(self, report_id: str, attachment_id: str) -> Path:
        # All identifiers originate from validated report manifests, never filenames.
        from uuid import UUID
        return self.media_root / str(UUID(report_id)) / str(UUID(attachment_id))

    def media_status(self, packet: dict) -> list[dict]:
        result = []
        for attachment in packet.get("attachments", []):
            path = self.media_path(packet["id"], attachment["id"])
            available = path.is_file() and path.stat().st_size == attachment["byte_size"]
            result.append({**attachment, "status": "available" if available else "pending"})
        return result

    def attachment(self, report_id: str, attachment_id: str) -> dict:
        report = self.report(report_id)
        for attachment in report.get("attachments", []):
            if attachment["id"] == attachment_id:
                return attachment
        raise StoreError(404, "Attachment is not declared in this report")

    def record_attachment(self, report_id: str, attachment: dict):
        with self.connection(transaction=True) as db:
            # Also called after a duplicate publication: recover a crash between the
            # atomic file link and this transaction without duplicate events or jobs.
            exists = db.execute("SELECT 1 FROM audit WHERE action='attachment.received' AND entity_id=? AND json_extract(details_json,'$.attachment_id')=?",
                                (report_id, attachment["id"])).fetchone()
            if not exists:
                self.audit(db, "gateway", "attachment.received", report_id, {
                    "attachment_id": attachment["id"], "kind": attachment["kind"],
                    "byte_size": attachment["byte_size"], "sha256": attachment["sha256"],
                    "content_verified": False, "ai_analyzed": False,
                })
            self.enqueue_media(db, report_id, attachment["id"])

    @staticmethod
    def enqueue_media(db, report_id: str, attachment_id: str):
        moment = now_ms()
        db.execute("""INSERT OR IGNORE INTO media_analysis
            (report_id,attachment_id,available_at,created_at,updated_at) VALUES(?,?,?,?,?)""",
            (report_id, attachment_id, moment, moment, moment))

    def media_analysis_for(self, packet: dict) -> list[dict]:
        if not packet.get("attachments"):
            return []
        with self.connection() as db:
            rows = {r["attachment_id"]: dict(r) for r in db.execute("SELECT * FROM media_analysis WHERE report_id=?", (packet["id"],))}
        result = []
        for attachment in packet["attachments"]:
            row = rows.get(attachment["id"])
            if row:
                raw = row.pop("result_json")
                row["result"] = json.loads(raw) if raw else None
                result.append(row)
            else:
                result.append({"report_id": packet["id"], "attachment_id": attachment["id"],
                    "status": "waiting_upload", "attempts": 0, "error": None, "result": None})
        return result

    def recover_media_jobs(self):
        with self.connection(transaction=True) as db:
            db.execute("UPDATE media_analysis SET status='queued',attempts=MAX(attempts-1,0),available_at=?,updated_at=? WHERE status='running'", (now_ms(), now_ms()))
            # Covers upgrades and a crash after file publication but before enqueue.
            for row in db.execute("SELECT packet_json FROM reports").fetchall():
                packet = json.loads(row[0])
                for media in self.media_status(packet):
                    if media["status"] == "available":
                        self.enqueue_media(db, packet["id"], media["id"])

    def claim_media_job(self):
        with self.connection(transaction=True) as db:
            row = db.execute("SELECT * FROM media_analysis WHERE status='queued' AND available_at<=? ORDER BY available_at,report_id,attachment_id LIMIT 1", (now_ms(),)).fetchone()
            if row is None:
                return None
            db.execute("UPDATE media_analysis SET status='running',attempts=attempts+1,updated_at=? WHERE report_id=? AND attachment_id=?", (now_ms(), row["report_id"], row["attachment_id"]))
            return {**dict(row), "attempts": row["attempts"] + 1}

    def save_media_analysis(self, job: dict, result: dict):
        with self.connection(transaction=True) as db:
            db.execute("UPDATE media_analysis SET status='complete',error=NULL,result_json=?,updated_at=? WHERE report_id=? AND attachment_id=?", (dump(result), now_ms(), job["report_id"], job["attachment_id"]))
            self.audit(db, "ai", "media.analysis_completed", job["report_id"], {
                "attachment_id": job["attachment_id"], "model": result["model"],
                "source_sha256": result["source_sha256"], "pipeline_version": result["pipeline_version"],
                "suggested_urgency": result["suggested_urgency"], "human_review_required": True,
                "notification_scope": "dashboard_review_only", "dispatch_performed": False})

    def fail_media_analysis(self, job: dict, error: str, delay_seconds: float):
        retry = job["attempts"] < 3
        with self.connection(transaction=True) as db:
            db.execute("UPDATE media_analysis SET status=?,error=?,available_at=?,updated_at=? WHERE report_id=? AND attachment_id=?", ("queued" if retry else "failed", error, now_ms() + int(delay_seconds * 1000), now_ms(), job["report_id"], job["attachment_id"]))
            self.audit(db, "system", "media.analysis_failed", job["report_id"], {
                "attachment_id": job["attachment_id"], "error": error,
                "attempt": job["attempts"], "will_retry": retry})

    def retry_media_analysis(self, report_id: str, attachment_id: str) -> dict:
        manifest = self.attachment(report_id, attachment_id)
        path = self.media_path(report_id, attachment_id)
        if not path.is_file() or path.stat().st_size != manifest["byte_size"]:
            raise StoreError(409, "Attachment must finish uploading before analysis")
        with self.connection(transaction=True) as db:
            row = db.execute("SELECT status FROM media_analysis WHERE report_id=? AND attachment_id=?", (report_id, attachment_id)).fetchone()
            already = bool(row and row["status"] in ("queued", "running"))
            if not already:
                self.enqueue_media(db, report_id, attachment_id)
                db.execute("UPDATE media_analysis SET status='queued',attempts=0,error=NULL,available_at=?,updated_at=? WHERE report_id=? AND attachment_id=?", (now_ms(), now_ms(), report_id, attachment_id))
                self.audit(db, "operator", "media.reanalysis_requested", report_id, {"attachment_id": attachment_id})
        return {"status": "queued", "report_id": report_id, "attachment_id": attachment_id, "idempotent": already}

    def media_queue_counts(self) -> dict:
        with self.connection() as db:
            counts = {r["status"]: r["n"] for r in db.execute("SELECT status,COUNT(*) n FROM media_analysis GROUP BY status")}
        return {status: counts.get(status, 0) for status in ("queued", "running", "failed", "complete")}

    def report_dict(self, row) -> dict:
        result = json.loads(row["packet_json"])
        for key in STRUCTURED_FIELDS:
            result.setdefault(key, None)
        result.update(incident_id=row["incident_id"], received_at=row["received_at"],
                      ai_status=row["ai_status"], ai_error=row["ai_error"],
                      intake=json.loads(row["intake_json"]) if row["intake_json"] else None)
        result["media"] = self.media_status(result)
        result["media_analysis"] = self.media_analysis_for(result)
        return result

    def incident_dict(self, db, row) -> dict:
        reports = [self.report_dict(r) for r in db.execute("SELECT * FROM reports WHERE incident_id=? ORDER BY received_at,id", (row["id"],))]
        result = {k: row[k] for k in ("id", "title", "building", "zone", "status", "category", "team",
                   "acknowledged_at", "created_at", "updated_at", "merged_into", "triage_status", "triage_error",
                   "verification_status", "verification_notes", "verification_updated_at")}
        counts = []
        for report in reports:
            seen = set()
            if report.get("people_affected") is not None:
                value = report["people_affected"]
                counts.append({"report_id": report["id"], "value": value, "quote": str(value), "source": "user_provided"})
                seen.add(str(value))
            for fact in (report["intake"] or {}).get("facts", []):
                if fact["field"] == "people_affected" and fact["value"].isdigit() and fact["value"] not in seen:
                    counts.append({"report_id": report["id"], "value": int(fact["value"]), "quote": fact["quote"], "source": "ai_extracted"})
                    seen.add(fact["value"])
        result.update(report_ids=[r["id"] for r in reports], triage=json.loads(row["triage_json"]) if row["triage_json"] else None,
                      reported_people_counts=counts, people_total=None,
                      people_total_note="Counts may overlap; a unique total requires human confirmation.",
                      processing_stages=json.loads(row["processing_json"]) if row["processing_json"] else initial_stages(),
                      verification=json.loads(row["verification_json"]) if row["verification_json"] else None,
                      human_verification=json.loads(row["human_verification_json"]) if row["human_verification_json"] else None,
                      verification_signals=json.loads(row["verification_signals_json"]) if row["verification_signals_json"] else verification_signals(reports),
                      acknowledged_report_ids=[r[0] for r in db.execute("SELECT receipts.report_id FROM receipts JOIN reports ON reports.id=receipts.report_id WHERE reports.incident_id=? AND receipts.type='responder_acknowledged'", (row["id"],))])
        emergency_types = sorted({r["emergency_type"] for r in reports if r.get("emergency_type")})
        result["emergency_types"] = emergency_types
        result["emergency_type"] = emergency_types[0] if len(emergency_types) == 1 else None
        for key in ("location_text", "floor", "room"):
            values = {r[key] for r in reports if r.get(key)}
            result[key] = next(iter(values)) if len(values) == 1 else None
        return result

    @staticmethod
    def correlation_dict(row) -> dict:
        result = {k: row[k] for k in ("id", "incident_a_id", "incident_b_id", "confidence", "reason", "status", "created_at", "decided_at", "model")}
        result["evidence"] = json.loads(row["evidence_json"])
        return result

    @staticmethod
    def require_incident(db, incident_id: str, active: bool = True):
        row = db.execute("SELECT * FROM incidents WHERE id=?", (incident_id,)).fetchone()
        if row is None:
            raise StoreError(404, "Incident not found")
        if active and row["merged_into"]:
            raise StoreError(409, f"Incident was merged into {row['merged_into']}")
        return row

    def accept(self, packet: dict) -> dict:
        fingerprint = self.fingerprint(packet)
        moment = now_ms()
        with self.connection(transaction=True) as db:
            old = db.execute("SELECT * FROM reports WHERE id=?", (packet["id"],)).fetchone()
            if old:
                if self.fingerprint(json.loads(old["packet_json"])) != fingerprint:
                    raise StoreError(409, "This message ID already exists with a different immutable payload")
                self.record_delivery(db, packet, moment)
                receipt = self.ensure_receipt(db, old, "backend_received", old["received_at"], json.loads(old["packet_json"])["relay_path"][-1])
                return {"status": "accepted", "report_id": packet["id"], "incident_id": old["incident_id"], "duplicate": True, "receipt": receipt}
            if packet["expires_at"] <= moment:
                raise StoreError(410, "SOS expired before reaching the backend; it was not accepted")
            if packet["created_at"] > moment + 300_000:
                raise StoreError(422, "created_at is more than five minutes in the future")
            incident_id = str(uuid4())
            title = " · ".join(p for p in (packet["building"], packet.get("floor") or packet["zone"], packet.get("room")) if p) or packet.get("location_text") or "Unclassified incident"
            db.execute("INSERT INTO incidents(id,title,building,zone,created_at,updated_at,processing_json) VALUES(?,?,?,?,?,?,?)",
                       (incident_id, title, packet["building"], packet["zone"], moment, moment, dump(initial_stages(moment))))
            db.execute("INSERT INTO reports(id,fingerprint,packet_json,incident_id,received_at) VALUES(?,?,?,?,?)",
                       (packet["id"], fingerprint, dump(packet), incident_id, moment))
            self.enqueue(db, "report", packet["id"])
            self.record_delivery(db, packet, moment)
            row = db.execute("SELECT * FROM reports WHERE id=?", (packet["id"],)).fetchone()
            receipt = self.ensure_receipt(db, row, "backend_received", moment, packet["relay_path"][-1])
            self.audit(db, "gateway", "report.accepted", packet["id"], {"incident_id": incident_id, "simulation": packet["simulation"], "relay_path": packet["relay_path"]})
        return {"status": "accepted", "report_id": packet["id"], "incident_id": incident_id, "duplicate": False, "receipt": receipt}

    def report(self, report_id: str) -> dict:
        with self.connection() as db:
            row = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
            if row is None:
                raise StoreError(404, "Report not found")
            return self.report_dict(row)

    def incident_context(self, incident_id: str) -> dict:
        with self.connection() as db:
            row = self.require_incident(db, incident_id, active=False)
            while row["merged_into"]:
                row = self.require_incident(db, row["merged_into"], active=False)
            reports = [self.report_dict(r) for r in db.execute("SELECT * FROM reports WHERE incident_id=? ORDER BY received_at,id", (row["id"],))]
            return {"id": row["id"], "revision": row["revision"], "triage_status": row["triage_status"], "reports": reports,
                    "processing_stages": json.loads(row["processing_json"]) if row["processing_json"] else initial_stages(),
                    "verification": json.loads(row["verification_json"]) if row["verification_json"] else None}

    def candidates(self, source_id: str) -> list[dict]:
        source = self.incident_context(source_id)
        if not has_correlation_context(source["reports"]):
            return []
        source_flags = {r["simulation"] for r in source["reports"]}
        with self.connection() as db:
            rows = db.execute("""SELECT id FROM incidents WHERE merged_into IS NULL AND id<>?
                AND status<>'resolved' ORDER BY updated_at DESC LIMIT 12""", (source_id,)).fetchall()
        candidates = []
        for row in rows:
            context = self.incident_context(row["id"])
            # An unprocessed candidate will be compared when its own report job reaches this stage.
            candidate_flags = {r["simulation"] for r in context["reports"]}
            if candidate_flags == source_flags and has_correlation_context(context["reports"]) and any(report["intake"] is not None for report in context["reports"]):
                candidates.append(context)
        return candidates

    def update_incident(self, incident_id: str, changes: dict) -> dict:
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, incident_id)
            if changes.get("status") == "acknowledged" and row["acknowledged_at"] is None:
                changes = {**changes, "acknowledged_at": now_ms()}
            if "status" in changes and changes["status"] != "resolved" and row["verification_status"] == "false_closed":
                changes = {**changes, "verification_status": "unverified", "verification_notes": None,
                           "human_verification_json": None, "verification_updated_at": now_ms()}
            fields = {**changes, "updated_at": now_ms()}
            db.execute(f"UPDATE incidents SET {','.join(key+'=?' for key in fields)} WHERE id=?", (*fields.values(), incident_id))
            if changes.get("status") == "acknowledged":
                for report in db.execute("SELECT * FROM reports WHERE incident_id=?", (incident_id,)).fetchall():
                    self.ensure_receipt(db, report, "responder_acknowledged", fields["updated_at"], None)
            self.audit(db, "operator", "incident.updated", incident_id, {"before": {k: row[k] for k in changes}, "changes": changes})
            return self.incident_dict(db, self.require_incident(db, incident_id))

    def acknowledge(self, incident_id: str) -> dict:
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, incident_id)
            moment = now_ms()
            emitted = []
            for report in db.execute("SELECT * FROM reports WHERE incident_id=?", (incident_id,)).fetchall():
                if not db.execute("SELECT 1 FROM receipts WHERE report_id=? AND type='responder_acknowledged'", (report["id"],)).fetchone():
                    emitted.append(self.ensure_receipt(db, report, "responder_acknowledged", moment, None)["id"])
            if row["acknowledged_at"] is None:
                status = "acknowledged" if row["status"] == "new" else row["status"]
                db.execute("UPDATE incidents SET acknowledged_at=?,status=?,updated_at=? WHERE id=?", (moment, status, moment, incident_id))
            if emitted:
                self.audit(db, "operator", "incident.acknowledged", incident_id, {"delivery": "receipt_outbox", "receipt_ids": emitted, "origin_delivery_confirmed": False})
            return self.incident_dict(db, self.require_incident(db, incident_id))

    def decide(self, correlation_id: str, decision: str) -> dict:
        final_status = "confirmed" if decision == "confirm" else "rejected"
        with self.connection(transaction=True) as db:
            correlation = db.execute("SELECT * FROM correlations WHERE id=?", (correlation_id,)).fetchone()
            if correlation is None:
                raise StoreError(404, "Correlation not found")
            if correlation["status"] != "pending":
                if correlation["status"] == final_status:
                    return {"correlation": self.correlation_dict(correlation), "incident_id": correlation["survivor_id"], "idempotent": True}
                raise StoreError(409, "This suggestion has already been decided or superseded")
            moment, survivor_id = now_ms(), None
            if decision == "confirm":
                left = self.require_incident(db, correlation["incident_a_id"])
                right = self.require_incident(db, correlation["incident_b_id"])
                left_flags = {json.loads(r[0])["simulation"] for r in db.execute("SELECT packet_json FROM reports WHERE incident_id=?", (left["id"],))}
                right_flags = {json.loads(r[0])["simulation"] for r in db.execute("SELECT packet_json FROM reports WHERE incident_id=?", (right["id"],))}
                if len(left_flags) != 1 or left_flags != right_flags:
                    raise StoreError(409, "Simulation reports and physical-device reports cannot be merged")
                # Preserve an open case rather than silently burying it in a resolved case.
                survivor, merged = sorted([left, right], key=lambda r: (r["status"] == "resolved", r["created_at"], r["id"]))
                survivor_id = survivor["id"]
                previous = [{**{k: r[k] for k in ("id", "status", "category", "team", "acknowledged_at", "verification_status", "verification_notes")},
                             "human_verification": json.loads(r["human_verification_json"]) if r["human_verification_json"] else None}
                            for r in (survivor, merged)]
                db.execute("UPDATE reports SET incident_id=? WHERE incident_id=?", (survivor_id, merged["id"]))
                db.execute("UPDATE incidents SET merged_into=?,updated_at=?,triage_json=NULL,triage_status='queued',triage_error=NULL WHERE id=?", (survivor_id, moment, merged["id"]))
                db.execute("UPDATE incidents SET revision=revision+1,updated_at=?,triage_json=NULL,triage_status='queued',triage_error=NULL WHERE id=?", (moment, survivor_id))
                self.invalidate_analysis(db, survivor_id)
                db.execute("UPDATE incidents SET verification_status='verification_requested',verification_notes=NULL,human_verification_json=NULL,verification_updated_at=? WHERE id=?", (moment, survivor_id))
                intake_pending = db.execute("SELECT 1 FROM reports WHERE incident_id=? AND intake_json IS NULL", (survivor_id,)).fetchone()
                self.write_stage(db, survivor_id, "intake", "queued" if intake_pending else "complete")
                # A conflicting location is explicitly unknown; original values remain in reports.
                if survivor["building"] != merged["building"]:
                    db.execute("UPDATE incidents SET building=NULL WHERE id=?", (survivor_id,))
                if survivor["zone"] != merged["zone"]:
                    db.execute("UPDATE incidents SET zone=NULL WHERE id=?", (survivor_id,))
                merged_row = self.require_incident(db, survivor_id)
                title = " · ".join(p for p in (merged_row["building"], merged_row["zone"]) if p) or "Merged incident — review locations"
                db.execute("UPDATE incidents SET title=? WHERE id=?", (title, survivor_id))
                db.execute("""UPDATE correlations SET status='superseded',decided_at=? WHERE status='pending'
                    AND id<>? AND (incident_a_id IN (?,?) OR incident_b_id IN (?,?))""",
                    (moment, correlation_id, survivor_id, merged["id"], survivor_id, merged["id"]))
                self.enqueue(db, "incident", survivor_id)
                self.audit(db, "operator", "incidents.merged", survivor_id, {"correlation_id": correlation_id, "merged_id": merged["id"], "previous_operator_assignments": previous, "victim_counts_summed": False})
            db.execute("UPDATE correlations SET status=?,decided_at=?,survivor_id=? WHERE id=?", (final_status, moment, survivor_id, correlation_id))
            self.audit(db, "operator", "correlation." + final_status, correlation_id, {"incident_id": survivor_id})
            return {"correlation": self.correlation_dict(db.execute("SELECT * FROM correlations WHERE id=?", (correlation_id,)).fetchone()), "incident_id": survivor_id, "idempotent": False}

    def reanalyze(self, report_id: str) -> dict:
        with self.connection(transaction=True) as db:
            report = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
            if report is None:
                raise StoreError(404, "Report not found")
            job = db.execute("SELECT * FROM jobs WHERE kind='report' AND target_id=?", (report_id,)).fetchone()
            already = bool(job and job["status"] in ("queued", "running"))
            if not already:
                db.execute("UPDATE reports SET intake_json=NULL,ai_status='queued',ai_error=NULL WHERE id=?", (report_id,))
                db.execute("UPDATE incidents SET revision=revision+1,triage_json=NULL,triage_status='queued',triage_error=NULL,updated_at=? WHERE id=?", (now_ms(), report["incident_id"]))
                self.invalidate_analysis(db, report["incident_id"])
                self.write_stage(db, report["incident_id"], "intake", "queued")
                db.execute("UPDATE correlations SET status='superseded',decided_at=? WHERE status='pending' AND (incident_a_id=? OR incident_b_id=?)", (now_ms(), report["incident_id"], report["incident_id"]))
                self.enqueue(db, "report", report_id)
                self.audit(db, "operator", "report.reanalysis_requested", report_id, {})
            return {"status": "queued", "report_id": report_id, "idempotent": already}

    def recover_jobs(self):
        with self.connection(transaction=True) as db:
            db.execute("UPDATE jobs SET status='queued',attempts=MAX(attempts-1,0),available_at=?,updated_at=? WHERE status='running'", (now_ms(), now_ms()))
            db.execute("UPDATE reports SET ai_status='queued' WHERE ai_status='running'")
            db.execute("UPDATE incidents SET triage_status='queued' WHERE triage_status='running'")
            for row in db.execute("SELECT id,processing_json FROM incidents WHERE processing_json IS NOT NULL").fetchall():
                stages = json.loads(row["processing_json"])
                if any(stage["status"] == "running" for stage in stages.values()):
                    for name, stage in stages.items():
                        if stage["status"] == "running":
                            self.write_stage(db, row["id"], name, "queued")

    def claim_job(self):
        with self.connection(transaction=True) as db:
            row = db.execute("SELECT * FROM jobs WHERE status='queued' AND available_at<=? ORDER BY available_at,id LIMIT 1", (now_ms(),)).fetchone()
            if not row:
                return None
            db.execute("UPDATE jobs SET status='running',attempts=attempts+1,updated_at=? WHERE id=?", (now_ms(), row["id"]))
            result = dict(row)
            result["attempts"] += 1
            return result

    def stage(self, job_id: int, stage: str):
        with self.connection() as db:
            db.execute("UPDATE jobs SET stage=?,updated_at=? WHERE id=?", (stage, now_ms(), job_id))

    def report_running(self, report_id: str):
        with self.connection() as db:
            db.execute("UPDATE reports SET ai_status='running',ai_error=NULL WHERE id=?", (report_id,))

    def save_intake(self, report_id: str, result: dict):
        with self.connection(transaction=True) as db:
            db.execute("UPDATE reports SET intake_json=?,ai_error=NULL WHERE id=?", (dump(result), report_id))
            incident_id = db.execute("SELECT incident_id FROM reports WHERE id=?", (report_id,)).fetchone()[0]
            db.execute("UPDATE incidents SET revision=revision+1,triage_json=NULL,triage_status='queued',triage_error=NULL WHERE id=?", (incident_id,))
            self.invalidate_analysis(db, incident_id)
            pending = db.execute("SELECT 1 FROM reports WHERE incident_id=? AND intake_json IS NULL", (incident_id,)).fetchone()
            self.write_stage(db, incident_id, "intake", "queued" if pending else "complete")
            self.audit(db, "ai", "intake.completed", report_id, {"model": result["model"], "evidence_checked": True})

    def triage_running(self, incident_id: str):
        with self.connection() as db:
            db.execute("UPDATE incidents SET triage_status='running',triage_error=NULL WHERE id=? AND merged_into IS NULL", (incident_id,))

    def save_triage(self, context: dict, result: dict) -> bool:
        with self.connection(transaction=True) as db:
            row = self.require_incident(db, context["id"], active=False)
            if row["merged_into"] or row["revision"] != context["revision"]:
                self.enqueue(db, "incident", row["merged_into"] or row["id"])
                return False
            db.execute("UPDATE incidents SET triage_json=?,triage_status='complete',triage_error=NULL,updated_at=? WHERE id=?", (dump(result), now_ms(), context["id"]))
            self.write_stage(db, context["id"], "triage", "complete")
            self.audit(db, "ai", "triage.completed", context["id"], {"model": result["model"], "evidence_checked": True, "operator_decision": False})
            return True

    def save_correlations(self, source: dict, candidates: list[dict], proposals: list[dict], model: str, omitted: int = 0):
        candidates_by_id = {c["id"]: c for c in candidates}
        with self.connection(transaction=True) as db:
            current = self.require_incident(db, source["id"], active=False)
            if current["merged_into"] or current["revision"] != source["revision"]:
                return
            for proposal in proposals:
                candidate = candidates_by_id[proposal["candidate_incident_id"]]
                row = self.require_incident(db, candidate["id"], active=False)
                if row["merged_into"] or row["revision"] != candidate["revision"]:
                    continue
                left, right = sorted((source["id"], candidate["id"]))
                if db.execute("SELECT 1 FROM correlations WHERE incident_a_id=? AND incident_b_id=? AND status<>'superseded'", (left, right)).fetchone():
                    continue
                correlation_id = str(uuid4())
                cursor = db.execute("""INSERT OR IGNORE INTO correlations(id,incident_a_id,incident_b_id,confidence,reason,evidence_json,model,created_at)
                    VALUES(?,?,?,?,?,?,?,?)""", (correlation_id, left, right, proposal["confidence"], proposal["reason"], dump(proposal["evidence"]), model, now_ms()))
                if cursor.rowcount:
                    self.audit(db, "ai", "correlation.suggested", correlation_id, {"model": model, "requires_human_confirmation": True})
            self.audit(db, "ai", "correlation.completed", source["id"], {"model": model, "candidates_compared": len(candidates), "candidates_omitted_for_context_budget": omitted, "suggestions": len(proposals), "inference_called": bool(candidates)})
            self.write_stage(db, source["id"], "correlation", "complete")

    def finish_job(self, job: dict, complete: bool = True, incident_id: str | None = None):
        with self.connection(transaction=True) as db:
            if not complete and incident_id:
                self.enqueue(db, "incident", incident_id)
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
            if row["rerun_requested"]:
                db.execute("UPDATE jobs SET status='queued',attempts=0,rerun_requested=0,available_at=?,updated_at=? WHERE id=?", (now_ms(), now_ms(), job["id"]))
            else:
                db.execute("UPDATE jobs SET status='complete',last_error=NULL,updated_at=? WHERE id=?", (now_ms(), job["id"]))
            if job["kind"] == "report":
                db.execute("UPDATE reports SET ai_status=?,ai_error=NULL WHERE id=?", ("complete" if complete else "queued", job["target_id"]))
            else:
                row = self.require_incident(db, job["target_id"], active=False)
                if not row["merged_into"]:
                    db.execute("UPDATE reports SET ai_status=?,ai_error=NULL WHERE incident_id=?", ("complete" if complete else "queued", row["id"]))
            self.supersede_obsolete_failures(db)

    @classmethod
    def supersede_obsolete_failures(cls, db):
        """Retain failed-job history without counting work a later job has completed.

        Completion requires both current stage evidence and a distinct successful job
        at least as recent as the failure. An unrelated or still-current failure stays failed.
        """
        failed = db.execute("""SELECT j.*, COALESCE(r.incident_id,j.target_id) AS incident_id
            FROM jobs j LEFT JOIN reports r ON j.kind='report' AND r.id=j.target_id
            WHERE j.status='failed'""").fetchall()
        for job in failed:
            incident = db.execute("SELECT * FROM incidents WHERE id=?", (job["incident_id"],)).fetchone()
            visited = set()
            while incident and incident["merged_into"] and incident["id"] not in visited:
                visited.add(incident["id"])
                incident = db.execute("SELECT * FROM incidents WHERE id=?", (incident["merged_into"],)).fetchone()
            if not incident or incident["merged_into"] or not incident["processing_json"]:
                continue
            stages = json.loads(incident["processing_json"])
            if any(stages.get(name, {}).get("status") not in ("complete", "not_applicable") for name in STAGES):
                continue
            replacement = db.execute("""SELECT j.id FROM jobs j
                LEFT JOIN reports r ON j.kind='report' AND r.id=j.target_id
                WHERE j.status='complete' AND j.id<>? AND j.updated_at>=?
                  AND ((j.kind IN ('incident','triage') AND j.target_id=?)
                    OR (j.kind='report' AND r.incident_id=?))
                ORDER BY j.updated_at DESC,j.id DESC LIMIT 1""",
                (job["id"], job["updated_at"], incident["id"], incident["id"])).fetchone()
            if not replacement:
                continue
            db.execute("UPDATE jobs SET status='superseded',updated_at=? WHERE id=?", (now_ms(), job["id"]))
            cls.audit(db, "system", "ai.job_superseded", job["target_id"], {
                "job_id": job["id"], "kind": job["kind"], "previous_status": "failed",
                "previous_error": job["last_error"], "previous_updated_at": job["updated_at"],
                "replacement_job_id": replacement["id"], "incident_id": incident["id"],
                "reason": "A later successful job completed all current incident stages",
            })

    def fail_job(self, job: dict, error: str, unavailable: bool, delay_seconds: float):
        status = "unavailable" if unavailable else "failed"
        retry = job["attempts"] < 3
        with self.connection(transaction=True) as db:
            db.execute("UPDATE jobs SET status=?,last_error=?,available_at=?,updated_at=? WHERE id=?",
                       ("queued" if retry else "failed", error, now_ms() + int(delay_seconds * 1000), now_ms(), job["id"]))
            incident_id = job["target_id"]
            if job["kind"] == "report":
                db.execute("UPDATE reports SET ai_status=?,ai_error=? WHERE id=?", (status, error, job["target_id"]))
                incident_id = db.execute("SELECT incident_id FROM reports WHERE id=?", (job["target_id"],)).fetchone()[0]
            else:
                db.execute("UPDATE reports SET ai_status=?,ai_error=? WHERE incident_id=?", (status, error, incident_id))
            db.execute("UPDATE incidents SET triage_status=?,triage_error=? WHERE id=? AND triage_status<>'complete' AND merged_into IS NULL", (status, error, incident_id))
            self.audit(db, "system", "ai.job_failed", job["target_id"], {"stage": job.get("stage"), "error": error, "attempt": job["attempts"], "will_retry": retry})

    def queue_counts(self) -> dict:
        with self.connection() as db:
            counts = {row["status"]: row["n"] for row in db.execute("SELECT status,COUNT(*) n FROM jobs GROUP BY status")}
            return {status: counts.get(status, 0) for status in ("queued", "running", "failed")}

    def snapshot(self) -> dict:
        with self.connection() as db:
            # A read transaction makes the reports and their incident memberships consistent.
            db.execute("BEGIN")
            reports = [self.report_dict(r) for r in db.execute("SELECT * FROM reports ORDER BY received_at DESC,id LIMIT 500")]
            for report in reports:
                report["receipts"] = [json.loads(r[0]) for r in db.execute("SELECT receipt_json FROM receipts WHERE report_id=? ORDER BY timestamp,id", (report["id"],))]
                report["deliveries"] = [{"gateway_id": r["gateway_id"], "relay_path": json.loads(r["relay_path_json"]),
                    "first_seen": r["first_seen"], "last_seen": r["last_seen"], "attempts": r["attempts"]}
                    for r in db.execute("SELECT * FROM delivery_observations WHERE report_id=? ORDER BY first_seen", (report["id"],))]
            incidents = [self.incident_dict(db, r) for r in db.execute("SELECT * FROM incidents ORDER BY updated_at DESC,id LIMIT 500")]
            correlations = [self.correlation_dict(r) for r in db.execute("SELECT * FROM correlations ORDER BY created_at DESC,id LIMIT 500")]
            audit = [{"id": r["id"], "at": r["at"], "actor": r["actor"], "action": r["action"], "entity_id": r["entity_id"], "details": json.loads(r["details_json"])} for r in db.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 200")]
            totals = {table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in ("reports", "incidents", "correlations", "audit")}
            stats = {"reports": totals["reports"], "incidents": db.execute("SELECT COUNT(*) FROM incidents WHERE merged_into IS NULL").fetchone()[0],
                     "open_incidents": db.execute("SELECT COUNT(*) FROM incidents WHERE merged_into IS NULL AND status<>'resolved'").fetchone()[0],
                     "pending_correlations": db.execute("SELECT COUNT(*) FROM correlations WHERE status='pending'").fetchone()[0],
                     "critical_incidents": db.execute("""SELECT COUNT(*) FROM incidents i
                         WHERE i.merged_into IS NULL AND i.status<>'resolved' AND
                         (json_extract(i.triage_json,'$.suggested_urgency')='critical' OR EXISTS (
                             SELECT 1 FROM reports r JOIN media_analysis m ON m.report_id=r.id
                             WHERE r.incident_id=i.id AND m.status='complete'
                               AND json_extract(m.result_json,'$.suggested_urgency')='critical'))""").fetchone()[0],
                     "awaiting_verification": db.execute("SELECT COUNT(*) FROM incidents WHERE merged_into IS NULL AND status<>'resolved' AND verification_status IN ('unverified','verification_requested')").fetchone()[0],
                     "gateways_online": db.execute("SELECT COUNT(*) FROM gateways WHERE expires_at>?", (now_ms(),)).fetchone()[0]}
            gateways = [{"node_id": r["node_id"], "simulation": bool(r["simulation"]), "last_seen": r["last_seen"],
                         "expires_at": r["expires_at"], "online": r["expires_at"] > now_ms()}
                        for r in db.execute("SELECT * FROM gateways ORDER BY last_seen DESC LIMIT 200")]
            return {"server_time": now_ms(), "reports": reports, "incidents": incidents, "correlations": correlations, "audit": audit,
                    "stats": stats, "gateways": gateways, "truncated": {table: totals[table] > (200 if table == "audit" else 500) for table in totals}}
