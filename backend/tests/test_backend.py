"""Tests use an explicitly named model stub; runtime has no stub or fallback mode."""
import asyncio
import json
import time
from uuid import uuid4

from fastapi.testclient import TestClient
import httpx
import pytest

from backend.ai import InferenceUnavailable, InvalidAIOutput, OllamaAgents, now_ms, validated_intake
from backend.app import create_app
from backend.schemas import IntakeOutput
from backend.store import Store, StoreError, dump
from backend.worker import Worker


def packet(**changes):
    return {"schema_version": 1, "id": str(uuid4()), "origin_id": "A", "created_at": now_ms(),
            "expires_at": now_ms() + 3_600_000, "text": "Three people trapped. One elderly person.",
            "building": "Test Hall", "zone": "Ground floor", "hop_count": 0, "max_hops": 8,
            "relay_path": ["A"], "simulation": True, **changes}


def intake_for(report):
    facts = [{"field": "people_affected", "value": "3", "quote": "Three people trapped.", "source": "text"}]
    facts += [{"field": "location", "value": report[k], "quote": report[k], "source": k} for k in ("building", "zone") if report[k]]
    return validated_intake(IntakeOutput(facts=facts, uncertain_interpretations=[], missing_information=["Exact room"]), report, "TEST-ONLY-STUB")


def triage_for(reports):
    return {"summary": "Test fixture summary; reported totals may overlap.", "suggested_urgency": "high",
            "urgency_reason": "Test fixture reason", "response_category": "rescue assessment",
            "questions": ["Exact room?"], "acknowledgement_draft": "Received for human review.",
            "missing_information": ["Exact room"], "evidence": [{"report_id": reports[0]["id"], "quote": "Three people trapped."}],
            "model": "TEST-ONLY-STUB", "generated_at": now_ms()}


class TestOnlyAgents:
    __test__ = False
    model = "TEST-ONLY-STUB"

    def __init__(self, unavailable=False):
        self.calls = []
        self.unavailable = unavailable

    async def check(self):
        if self.unavailable:
            raise InferenceUnavailable("Test model deliberately offline")

    async def close(self):
        pass

    async def intake(self, report):
        self.calls.append("intake")
        await self.check()
        return intake_for(report)

    async def triage(self, reports, verification=None):
        self.calls.append("triage")
        await self.check()
        return triage_for(reports)

    async def verify(self, reports, signals, related_reports=None):
        self.calls.append("verification")
        await self.check()
        return {"suggested_state": "unverified", "summary": "Test fixture: independent verification is unavailable.",
                "signal_assessments": [{"signal_id": signals[0]["id"], "assessment": "Test observation", "why_not_conclusive": "Unknown identity"}],
                "evidence": [], "limitations": ["Source independence unknown"], "questions": ["Can a responder check?"],
                "source_independence": "unknown", "model": self.model, "generated_at": now_ms()}

    async def correlate(self, source, candidates):
        self.calls.append("correlation")
        await self.check()
        return [{"candidate_incident_id": c["id"], "confidence": .8, "reason": "Test fixture candidate",
                 "evidence": [{"report_id": source["reports"][0]["id"], "quote": "Three people trapped."},
                              {"report_id": c["reports"][0]["id"], "quote": "Three people trapped."}]} for c in candidates]


def ready_store(tmp_path):
    store = Store(str(tmp_path / "test.sqlite3"))
    accepted = []
    for node in ("A", "B"):
        p = packet(origin_id=node, relay_path=[node])
        result = store.accept(p)
        store.save_intake(p["id"], intake_for(p))
        accepted.append(result)
    source = store.incident_context(accepted[0]["incident_id"])
    candidate = store.incident_context(accepted[1]["incident_id"])
    proposal = {"candidate_incident_id": candidate["id"], "confidence": .8, "reason": "Test fixture",
                "evidence": [{"report_id": r["report_id"], "quote": "Three people trapped."} for r in accepted]}
    store.save_correlations(source, [candidate], [proposal], "TEST-ONLY-STUB")
    return store, accepted, store.snapshot()["correlations"][0]


def wait_state(client, predicate, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = client.get("/api/state").json()
        if predicate(state):
            return state
        time.sleep(.02)
    raise AssertionError(f"State did not satisfy expectation: {state}")


def test_ingest_is_durable_idempotent_and_conflicts_rejected(tmp_path):
    path = str(tmp_path / "durable.sqlite3")
    p = packet(text="Three people trapped. <script>alert('untrusted')</script>")
    with TestClient(create_app(db_path=path, agents=TestOnlyAgents(), start_worker=False)) as client:
        first = client.post("/api/reports", json=p)
        assert first.status_code == 202
        assert not first.json()["duplicate"]
        replay = client.post("/api/reports", json={**p, "hop_count": 1, "relay_path": ["A", "D"]})
        assert replay.status_code == 202 and replay.json()["duplicate"]
        assert client.post("/api/reports", json={**p, "text": "Different content"}).status_code == 409
        assert len(client.get("/api/state").json()["reports"]) == 1
    with TestClient(create_app(db_path=path, agents=TestOnlyAgents(), start_worker=False)) as client:
        state = client.get("/api/state").json()
        assert state["reports"][0]["text"] == p["text"]
        assert state["reports"][0]["ai_status"] == "queued"
        assert state["reports"][0]["intake"] is None


@pytest.mark.parametrize("changes,code", [
    ({"id": "invalid"}, 422), ({"hop_count": 2}, 422), ({"origin_id": "node<script>"}, 422),
    ({"hop_count": 1, "relay_path": ["A", "A"]}, 422), ({"relay_path": ["B"]}, 422),
    ({"max_hops": 9}, 422), ({"text": "   "}, 422), ({"simulation": "true"}, 422),
    ({"created_at": 1000, "expires_at": 2000}, 410),
    ({"created_at": 2000, "expires_at": 1000}, 422),
    ({"created_at": now_ms() + 3_600_000, "expires_at": now_ms() + 7_200_000}, 422),
])
def test_packet_validation(tmp_path, changes, code):
    with TestClient(create_app(db_path=str(tmp_path / "db"), agents=TestOnlyAgents(), start_worker=False)) as client:
        assert client.post("/api/reports", json=packet(**changes)).status_code == code
        assert client.get("/api/state").json()["stats"]["reports"] == 0


def test_all_four_roles_run_and_operator_decides_merge(tmp_path):
    agents = TestOnlyAgents()
    with TestClient(create_app(db_path=str(tmp_path / "db"), agents=agents, retry_seconds=.001)) as client:
        a = client.post("/api/reports", json=packet()).json()
        b = client.post("/api/reports", json=packet(origin_id="B", relay_path=["B"])).json()
        state = wait_state(client, lambda s: len(s["correlations"]) == 1 and all(r["ai_status"] == "complete" for r in s["reports"]))
        assert state["stats"]["incidents"] == 2
        assert set(agents.calls) == {"intake", "triage", "correlation", "verification"}
        correlation = state["correlations"][0]
        assert correlation["status"] == "pending"
        result = client.post(f"/api/correlations/{correlation['id']}/decision", json={"decision": "confirm"})
        assert result.status_code == 200
        survivor = result.json()["incident_id"]
        assert client.post(f"/api/correlations/{correlation['id']}/decision", json={"decision": "confirm"}).json()["idempotent"]
        assert client.post(f"/api/correlations/{correlation['id']}/decision", json={"decision": "reject"}).status_code == 409
        state = wait_state(client, lambda s: any(i["id"] == survivor and i["triage_status"] == "complete" for i in s["incidents"]))
        incident = next(i for i in state["incidents"] if i["id"] == survivor)
        assert set(incident["report_ids"]) == {a["report_id"], b["report_id"]}
        assert incident["people_total"] is None
        assert [c["value"] for c in incident["reported_people_counts"]] == [3, 3]
        assert incident["category"] is None and incident["team"] is None and incident["status"] == "new"
        assert client.patch(f"/api/incidents/{survivor}", json={"status": "in_progress", "category": "rescue", "team": "Test team"}).json()["team"] == "Test team"
        assert client.post(f"/api/incidents/{survivor}/acknowledge").json()["status"] == "in_progress"


def test_unavailable_model_has_no_fabricated_output_and_bounded_retries(tmp_path):
    agents = TestOnlyAgents(unavailable=True)
    with TestClient(create_app(db_path=str(tmp_path / "db"), agents=agents, retry_seconds=.001)) as client:
        report = client.post("/api/reports", json=packet()).json()
        state = wait_state(client, lambda s: s["ai"]["queue"]["failed"] == 1)
        assert agents.calls == ["intake"] * 3
        assert state["reports"][0]["intake"] is None and state["incidents"][0]["triage"] is None
        assert state["reports"][0]["ai_status"] == "unavailable"
        assert state["ai"]["status"] == "unavailable"
        assert client.get("/api/health").status_code == 200
        agents.unavailable = False
        assert not client.post(f"/api/reports/{report['report_id']}/reanalyze").json()["idempotent"]
        wait_state(client, lambda s: s["reports"][0]["ai_status"] == "complete")


def test_restart_recovers_claimed_job(tmp_path):
    path = str(tmp_path / "db")
    store = Store(path)
    result = store.accept(packet())
    claimed = store.claim_job()
    assert claimed and store.queue_counts()["running"] == 1
    with TestClient(create_app(db_path=path, agents=TestOnlyAgents())) as client:
        state = wait_state(client, lambda s: s["reports"][0]["ai_status"] == "complete")
        assert state["reports"][0]["id"] == result["report_id"]
        assert state["ai"]["queue"]["running"] == 0


def test_stale_triage_cannot_overwrite_human_merge(tmp_path):
    store, accepted, correlation = ready_store(tmp_path)
    stale = store.incident_context(accepted[0]["incident_id"])
    result = store.decide(correlation["id"], "confirm")
    assert not store.save_triage(stale, triage_for(stale["reports"]))
    state = store.snapshot()
    survivor = next(i for i in state["incidents"] if i["id"] == result["incident_id"])
    assert survivor["triage"] is None and survivor["triage_status"] == "queued"
    assert store.queue_counts()["queued"] >= 1


def test_reanalysis_invalidates_pending_correlation_and_stale_triage(tmp_path):
    store, accepted, correlation = ready_store(tmp_path)
    stale = store.incident_context(accepted[0]["incident_id"])
    with store.connection() as db:
        db.execute("UPDATE jobs SET status='complete'")
    result = store.reanalyze(accepted[0]["report_id"])
    assert not result["idempotent"]
    assert store.snapshot()["correlations"][0]["status"] == "superseded"
    assert not store.save_triage(stale, triage_for(stale["reports"]))
    assert store.reanalyze(accepted[0]["report_id"])["idempotent"]
    store.save_intake(accepted[0]["report_id"], intake_for(store.report(accepted[0]["report_id"])))
    fresh = store.incident_context(accepted[0]["incident_id"])
    candidate = store.incident_context(accepted[1]["incident_id"])
    proposal = {"candidate_incident_id": candidate["id"], "confidence": .7, "reason": "Fresh test analysis", "evidence": correlation["evidence"]}
    store.save_correlations(fresh, [candidate], [proposal], "TEST-ONLY-STUB")
    generations = store.snapshot()["correlations"]
    assert len(generations) == 2
    assert {g["status"] for g in generations} == {"superseded", "pending"}
    assert len({g["id"] for g in generations}) == 2


def test_stale_correlation_results_are_discarded_after_merge(tmp_path):
    store, accepted, correlation = ready_store(tmp_path)
    third = packet()
    third_result = store.accept(third)
    store.save_intake(third["id"], intake_for(third))
    old_source = store.incident_context(accepted[0]["incident_id"])
    candidate = store.incident_context(third_result["incident_id"])
    store.decide(correlation["id"], "confirm")
    proposal = {"candidate_incident_id": candidate["id"], "confidence": .8, "reason": "Stale test analysis", "evidence": []}
    store.save_correlations(old_source, [candidate], [proposal], "TEST-ONLY-STUB")
    assert len(store.snapshot()["correlations"]) == 1


def test_simulation_and_physical_incidents_cannot_mix(tmp_path):
    store = Store(str(tmp_path / "db"))
    accepted = []
    for simulation in (True, False):
        p = packet(simulation=simulation)
        accepted.append(store.accept(p))
        store.save_intake(p["id"], intake_for(p))
    left, right = [r["incident_id"] for r in accepted]
    assert store.candidates(left) == [] and store.candidates(right) == []
    correlation_id = str(uuid4())
    with store.connection() as db:
        db.execute("INSERT INTO correlations(id,incident_a_id,incident_b_id,confidence,reason,evidence_json,model,created_at) VALUES(?,?,?,?,?,?,?,?)",
                   (correlation_id, left, right, .9, "Deliberately invalid test pair", "[]", "TEST-ONLY-STUB", now_ms()))
    with pytest.raises(StoreError, match="cannot be merged"):
        store.decide(correlation_id, "confirm")
    assert store.snapshot()["stats"]["incidents"] == 2


def test_intake_rejects_invented_quotes_numbers_and_numeric_only_vulnerability():
    p = packet(building=None, zone=None)
    for fact in [
        {"field": "situation", "value": "trapped", "quote": "Fire in room 900", "source": "text"},
        {"field": "people_affected", "value": "9", "quote": "Three people trapped.", "source": "text"},
        {"field": "vulnerable_person", "value": "1", "quote": "One elderly person.", "source": "text"},
    ]:
        with pytest.raises(InvalidAIOutput):
            validated_intake(IntakeOutput(facts=[fact], uncertain_interpretations=[], missing_information=[]), p, "TEST-ONLY-STUB")


def test_auth_and_cross_origin_mutation_guard(tmp_path):
    with TestClient(create_app(db_path=str(tmp_path / "db"), agents=TestOnlyAgents(), start_worker=False, api_token="test-secret")) as client:
        assert client.get("/api/health").status_code == 200
        assert client.get("/api/state").status_code == 401
        headers = {"X-API-Key": "test-secret"}
        assert client.get("/api/state", headers=headers).status_code == 200
        assert client.post("/api/reports", json=packet(), headers={**headers, "Origin": "https://untrusted.example"}).status_code == 403
        assert client.post("/api/reports", json=packet(), headers=headers).status_code == 202


def test_real_client_sends_strict_schema_and_disables_thinking():
    captured = []

    async def scenario():
        async def handler(request):
            body = json.loads(request.content)
            captured.append((request.url.path, body))
            if request.url.path == "/api/show":
                return httpx.Response(200, json={"thinking": {"values": [False], "default": False}})
            return httpx.Response(200, json={"done": True, "message": {"content": json.dumps({"facts": [], "uncertain_interpretations": [], "missing_information": ["Location"]})}})
        agents = OllamaAgents("http://test-only", "TEST-ONLY-STUB")
        await agents.client.aclose()
        agents.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://test-only")
        result = await agents.intake(packet(building=None, zone=None))
        await agents.close()
        return result

    result = asyncio.run(scenario())
    assert result["model"] == "TEST-ONLY-STUB"
    request = captured[-1][1]
    assert request["think"] is False and request["stream"] is False
    assert request["format"]["additionalProperties"] is False
    assert "UNTRUSTED_EMERGENCY_DATA_START" in request["messages"][1]["content"]


def test_explicit_metadata_does_not_require_redundant_model_extraction():
    p = packet()
    out = IntakeOutput(facts=[{"field": "people_affected", "value": "3", "quote": "Three people trapped.", "source": "text"}],
                       uncertain_interpretations=[], missing_information=["Exact room"])
    result = validated_intake(out, p, "TEST-ONLY-STUB")
    assert len(result["facts"]) == 1
    assert p["building"] == "Test Hall"  # Supplied metadata remains an independent input field.


def test_intake_uses_one_real_request_for_validation_repair():
    requests = []

    async def scenario():
        async def handler(request):
            body = json.loads(request.content)
            if request.url.path == "/api/show":
                return httpx.Response(200, json={"thinking": {"values": [False]}})
            requests.append(body)
            quote = "Invented evidence" if len(requests) == 1 else "Three people trapped."
            output = {"facts": [{"field": "people_affected", "value": "3", "quote": quote, "source": "text"}],
                      "uncertain_interpretations": [], "missing_information": ["Exact room"]}
            return httpx.Response(200, json={"done": True, "message": {"content": json.dumps(output)}})
        agents = OllamaAgents("http://test-only", "TEST-ONLY-STUB")
        await agents.client.aclose()
        agents.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://test-only")
        try:
            return await agents.intake(packet())
        finally:
            await agents.close()
    result = asyncio.run(scenario())
    assert len(requests) == 2
    assert "VALIDATION REPAIR" in requests[1]["messages"][0]["content"]
    assert result["facts"][0]["quote"] == "Three people trapped."


def test_legacy_correlation_uniqueness_migration_preserves_rows(tmp_path):
    store, _, correlation = ready_store(tmp_path)
    with store.connection(transaction=True) as db:
        definition = db.execute("SELECT sql FROM sqlite_master WHERE name='correlations'").fetchone()[0]
        rows = [tuple(r) for r in db.execute("SELECT * FROM correlations")]
        db.execute("DROP TABLE correlations")
        db.execute(definition.rstrip()[:-1] + ", UNIQUE(incident_a_id, incident_b_id))")
        for row in rows:
            db.execute("INSERT INTO correlations VALUES(" + ",".join("?" for _ in row) + ")", row)
    reopened = Store(store.path)
    assert reopened.snapshot()["correlations"][0]["id"] == correlation["id"]
    with reopened.connection() as db:
        definition = db.execute("SELECT sql FROM sqlite_master WHERE name='correlations'").fetchone()[0]
        assert "UNIQUE(incident_a_id, incident_b_id)" not in definition
