"""Opt-in real local model smoke test. Uses a new isolated SQLite file, no live SOS.

Run from the repository with PYTHONPATH=. .venv/bin/python -m backend.tests.run_live_media_validation
Committed synthetic fixtures live in backend/tests/fixtures/media and contain no microphone capture.
"""
import hashlib
import json
from pathlib import Path
import sqlite3
import time
from uuid import uuid4

from fastapi.testclient import TestClient

from backend.app import create_app
from backend.ai import now_ms


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "artifacts" / "media-intelligence"
FIXTURES = ROOT / "backend" / "tests" / "fixtures" / "media"


def immutable_snapshot():
    db_path = ROOT / "backend" / "data" / "resqmesh.sqlite3"
    if not db_path.exists():
        return {}
    with sqlite3.connect(f"file:{db_path}?mode=ro", uri=True) as db:
        rows = db.execute("SELECT id,packet_json,fingerprint FROM reports ORDER BY id").fetchall()
    return {row[0]: hashlib.sha256((row[1] + row[2]).encode()).hexdigest() for row in rows}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    before = immutable_snapshot()
    db_path = OUTPUT / f"live-pipeline-{uuid4()}.sqlite3"
    evidence = {"synthetic_only": True, "db_path": str(db_path), "model": "gemma4:e2b", "reports": []}
    with TestClient(create_app(db_path=str(db_path), retry_seconds=0, api_token="")) as client:
        originals = {}
        for kind, filename in [("audio", "synthetic-voice.m4a"), ("image", "synthetic-image.jpg"), ("video", "synthetic-video.mp4")]:
            path = FIXTURES / filename
            content = path.read_bytes()
            item = {"id": str(uuid4()), "kind": kind, "mime_type": {"audio": "audio/mp4", "image": "image/jpeg", "video": "video/mp4"}[kind],
                    "byte_size": len(content), "sha256": hashlib.sha256(content).hexdigest(), "duration_ms": None if kind == "image" else 5000}
            packet = {"schema_version": 4, "id": str(uuid4()), "origin_id": "SYNTHETIC-VALIDATION", "created_at": now_ms(),
                "expires_at": now_ms() + 3600000, "text": "Synthetic validation only. This is not a live emergency.",
                "building": "Synthetic validation", "zone": None, "hop_count": 0, "max_hops": 8,
                "relay_path": ["SYNTHETIC-VALIDATION"], "simulation": True, "attachments": [item]}
            accepted = client.post("/api/reports", json=packet)
            assert accepted.status_code == 202, accepted.text
            assert client.app.state.store.report(packet["id"])["media_analysis"][0]["status"] == "waiting_upload"
            with client.app.state.store.connection() as db:
                originals[packet["id"]] = tuple(db.execute("SELECT packet_json,fingerprint FROM reports WHERE id=?", (packet["id"],)).fetchone())
            started = time.monotonic()
            uploaded = client.put(f"/api/reports/{packet['id']}/attachments/{item['id']}", content=content,
                                  headers={"Content-Type": item["mime_type"]})
            assert uploaded.status_code == 200, uploaded.text
            evidence["reports"].append({"id": packet["id"], "attachment_id": item["id"], "kind": kind,
                "fixture": filename, "upload_seconds": round(time.monotonic() - started, 3),
                "state_after_upload": client.app.state.store.report(packet["id"])["media_analysis"][0]["status"]})
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            jobs = [client.app.state.store.report(item["id"])["media_analysis"][0] for item in evidence["reports"]]
            if all(job["status"] in ("complete", "failed") for job in jobs):
                break
            time.sleep(.2)
        for item, job in zip(evidence["reports"], jobs):
            item["analysis"] = job
            receipts = client.app.state.store.receipts(item["id"])["receipts"]
            assert [r["type"] for r in receipts] == ["backend_received"]
            with client.app.state.store.connection() as db:
                assert tuple(db.execute("SELECT packet_json,fingerprint FROM reports WHERE id=?", (item["id"],)).fetchone()) == originals[item["id"]]
                item["review_events"] = db.execute("SELECT COUNT(*) FROM audit WHERE action='media.analysis_completed' AND entity_id=?", (item["id"],)).fetchone()[0]
            item["original_packet_unchanged"] = True
            item["automatic_acknowledgement_or_dispatch"] = False
        evidence["all_media_complete"] = all(job["status"] == "complete" for job in jobs)
    after = immutable_snapshot()
    evidence["live_data_preservation"] = {"before_reports": len(before), "after_reports": len(after),
        "all_original_packets_unchanged": all(after.get(key) == value for key, value in before.items())}
    evidence["quality_note"] = "Inference completion is not accuracy validation. Plain-image hallucination and missed generated tone were observed in component tests. Human review is mandatory."
    (OUTPUT / "live-pipeline-validation.json").write_text(json.dumps(evidence, indent=2))
    print(json.dumps({key: value for key, value in evidence.items() if key != "reports"}, indent=2))
    assert evidence["all_media_complete"], "Inspect saved evidence for media failures"
    assert evidence["live_data_preservation"]["all_original_packets_unchanged"]


if __name__ == "__main__":
    main()
