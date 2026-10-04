"""Optional-text SOS acceptance, provenance, grounding and legacy replay contracts."""
import asyncio
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest
from pydantic import TypeAdapter, ValidationError

from backend.ai import InvalidAIOutput, OllamaAgents, grounded_text_evidence, validated_intake
from backend.app import create_app
from backend.schemas import IntakeOutput, QUICK_SOS_TEXT, ReportPacket
from backend.signals import verification_signals
from backend.store import Store
from backend.tests.test_backend import TestOnlyAgents, packet, triage_for


def quick_packet(**changes):
    return packet(schema_version=3, text="", building=None, zone=None, **changes)


@pytest.mark.parametrize("message", ["", " \n\t ", "!", "help"])
def test_no_typing_or_short_message_is_durable_and_unverified(tmp_path, message):
    p = {**quick_packet(), "text": message}
    path = str(tmp_path / "quick.sqlite3")
    with TestClient(create_app(db_path=path, agents=TestOnlyAgents(), start_worker=False)) as client:
        result = client.post("/api/reports", json=p)
        assert result.status_code == 202
        report, = client.get("/api/state").json()["reports"]
        assert report["text"] == (message if message.strip() else QUICK_SOS_TEXT)
        assert report["message_source"] == ("user" if message.strip() else "preset")
        assert report["quick_needs"] == []
        assert report["people_affected"] is None
        assert report["location_context"] == {"source": "unknown", "observed_at": None,
                                               "latitude": None, "longitude": None, "accuracy_m": None}
        incident, = client.get("/api/state").json()["incidents"]
        assert incident["verification_status"] == "unverified" and incident["status"] == "new"
        assert not incident["reported_people_counts"] and incident["people_total"] is None
        assert [receipt["type"] for receipt in report["receipts"]] == ["backend_received"]
    reopened = Store(path).snapshot()
    assert reopened["reports"][0]["text"] == report["text"]
    assert reopened["incidents"][0]["verification_status"] == "unverified"


def test_blank_and_normalized_replays_keep_one_report_and_preserve_metadata(tmp_path):
    p = quick_packet(quick_needs=["cannot_move", "cannot_speak", "people_injured"], people_affected=None)
    p["location_context"] = {"source": "saved", "observed_at": p["created_at"] - 86_400_000,
                             "latitude": 12.9716, "longitude": 77.5946, "accuracy_m": 200}
    with TestClient(create_app(db_path=str(tmp_path / "db"), agents=TestOnlyAgents(), start_worker=False)) as client:
        first = client.post("/api/reports", json=p).json()
        relayed = {**p, "text": QUICK_SOS_TEXT, "message_source": "preset", "hop_count": 2,
                   "relay_path": [p["origin_id"], "RQM-RELAY", "RQM-GATEWAY"]}
        again = client.post("/api/reports", json=relayed)
        assert again.status_code == 202 and again.json()["duplicate"]
        assert again.json()["receipt"] == first["receipt"]
        report, = client.get("/api/state").json()["reports"]
        assert report["quick_needs"] == p["quick_needs"]
        assert report["location_context"] == p["location_context"]
        assert len(report["deliveries"]) == 2
        for changes in ({"quick_needs": []}, {"location_context": {"source": "unknown"}}, {"message_source": "user"}):
            assert client.post("/api/reports", json={**relayed, **changes}).status_code == 409


@pytest.mark.parametrize("version", [1, 2])
def test_old_schema_payload_fingerprint_and_replay_remain_unchanged(tmp_path, version):
    p = packet(schema_version=version)
    store = Store(str(tmp_path / "db"))
    first = store.accept(p)
    parsed = ReportPacket.model_validate(p).model_dump(mode="json")
    assert not {"message_source", "quick_needs", "location_context"} & parsed.keys()
    assert Store.fingerprint(parsed) == Store.fingerprint(p)
    replay = store.accept({**parsed, "hop_count": 1, "relay_path": ["A", "RQM-GATEWAY"]})
    assert replay["duplicate"] and replay["receipt"] == first["receipt"]
    with store.connection() as db:
        assert json.loads(db.execute("SELECT packet_json FROM reports").fetchone()[0]) == p
    with pytest.raises(ValidationError):
        ReportPacket.model_validate({**p, "text": " "})
    with pytest.raises(ValidationError):
        ReportPacket.model_validate({**p, "quick_needs": ["cannot_move"]})


@pytest.mark.parametrize("changes", [
    {"schema_version": 3.0}, {"message_source": None}, {"message_source": "invented"},
    {"quick_needs": None}, {"location_context": None},
    {"message_source": "preset", "text": "A user-specific emergency"},
    {"quick_needs": ["cannot_move", "cannot_move"]}, {"quick_needs": ["invented_need"]},
    {"location_context": {"source": "unknown", "observed_at": 1}},
    {"location_context": {"source": "manual"}},
    {"location_context": {"source": "device", "observed_at": 1}},
    {"location_context": {"source": "device", "observed_at": 1, "latitude": 20}},
    {"location_context": {"source": "device", "observed_at": 1, "latitude": 91, "longitude": 20}},
    {"location_context": {"source": "device", "observed_at": 1, "latitude": 20, "longitude": -181}},
    {"location_context": {"source": "saved", "observed_at": 1, "accuracy_m": 20}},
    {"location_context": {"source": "device", "observed_at": 1, "latitude": 20, "longitude": 30, "accuracy_m": -1}},
    {"location_context": {"source": "device", "observed_at": 1, "latitude": float("nan"), "longitude": 30}},
    {"location_context": {"source": "device", "observed_at": True, "latitude": 20, "longitude": 30}},
])
def test_quick_sos_rejects_fabricated_or_inconsistent_metadata(changes):
    with pytest.raises(ValidationError):
        ReportPacket.model_validate({**quick_packet(), **changes})


def test_location_time_is_preserved_and_unknown_is_not_invented():
    p = quick_packet()
    for source in ("manual", "saved", "device"):
        old = {"source": source, "observed_at": p["created_at"] - 86_400_000,
               "latitude": 0, "longitude": 0, "accuracy_m": 0}
        value = ReportPacket.model_validate({**p, "location_context": old})
        assert value.location_context.model_dump() == old
        with pytest.raises(ValidationError):
            ReportPacket.model_validate({**p, "location_context": {**old, "observed_at": p["created_at"] + 300_001}})


def test_presets_are_not_copying_evidence_or_unrelated_location_matches(tmp_path):
    store = Store(str(tmp_path / "db"))
    reports = []
    accepted = []
    for origin in ("RQM-1", "RQM-2"):
        p = ReportPacket.model_validate(quick_packet(origin_id=origin, relay_path=[origin])).model_dump(mode="json")
        accepted.append(store.accept(p))
        reports.append(p)
    signals = {signal["code"]: signal for signal in verification_signals(reports)}
    assert "repeated_text" not in signals and "similar_wording" not in signals
    assert signals["preset_sos"]["severity"] == "info"
    assert store.verification_context(store.incident_context(accepted[0]["incident_id"])) == []
    reports[0]["location_context"] = {"source": "device", "observed_at": reports[0]["created_at"], "latitude": 0, "longitude": 0}
    signals = {signal["code"]: signal for signal in verification_signals(reports)}
    assert signals["missing_structured_location"]["report_ids"] == [reports[1]["id"]]


def test_selected_needs_ground_intake_without_inventing_count_or_condition():
    p = ReportPacket.model_validate(quick_packet(quick_needs=["cannot_move"])).model_dump(mode="json")
    fact = {"field": "situation", "value": "Cannot move", "quote": "cannot_move", "source": "quick_needs"}
    def output(changes):
        return IntakeOutput(facts=[{**fact, **changes}], missing_information=[], uncertain_interpretations=[])
    assert validated_intake(output({}), p, "TEST-ONLY")["facts"][0]["quote"] == "cannot_move"
    for changes in ({"quote": "move"}, {"value": "Paralyzed"}, {"quote": "cannot_speak"},
                    {"field": "people_affected", "value": "1"},
                    {"field": "emergency_type", "source": "text", "quote": "Help", "value": "medical"}):
        with pytest.raises(InvalidAIOutput):
            validated_intake(output(changes), p, "TEST-ONLY")


def test_actual_ai_request_includes_selection_and_location_provenance():
    p = ReportPacket.model_validate(quick_packet(quick_needs=["cannot_speak"])).model_dump(mode="json")
    async def run():
        agents = OllamaAgents("http://unused.invalid", "TEST-ONLY-CAPTURE")
        async def capture(_prompt, data, schema):
            assert data["message_source"] == "preset"
            assert data["quick_needs"] == ["cannot_speak"]
            assert data["location_context"]["source"] == "unknown"
            selected = {"field": "situation", "value": "Cannot speak", "quote": "cannot_speak", "source": "quick_needs"}
            value = {"facts": [selected], "missing_information": ["Current location"], "uncertain_interpretations": []}
            with pytest.raises(ValidationError):
                schema.model_validate({**value, "facts": [{**selected, "quote": "people_injured", "value": "People injured"}]})
            return schema.model_validate(value)
        agents._generate = capture
        try:
            assert (await agents.intake(p))["facts"][0]["value"] == "Cannot speak"
            context = agents.report_context(p)
            assert context["quick_needs"] == ["cannot_speak"] and context["location_context"]["source"] == "unknown"
        finally:
            await agents.close()
    asyncio.run(run())


def test_generic_presets_cannot_be_correlated_by_the_model_or_candidate_queue(tmp_path):
    store = Store(str(tmp_path / "db"))
    contexts = []
    for changes in ({}, {"emergency_type": "medical", "quick_needs": ["cannot_move"]},
                    {"location_text": "Synthetic east entrance"}):
        p = ReportPacket.model_validate(quick_packet(**changes)).model_dump(mode="json")
        accepted = store.accept(p)
        store.save_intake(p["id"], {"facts": [], "uncertain_interpretations": [], "missing_information": [], "model": "TEST-ONLY", "generated_at": p["created_at"]})
        contexts.append(store.incident_context(accepted["incident_id"]))
    assert store.candidates(contexts[0]["id"]) == []
    assert store.candidates(contexts[1]["id"]) == []
    assert store.candidates(contexts[2]["id"]) == []  # Generic reports cannot be candidates either.
    async def run():
        agents = OllamaAgents("http://unused.invalid", "TEST-ONLY")
        async def should_not_infer(*_args):
            raise AssertionError("No model call is allowed when either side has only generic preset context")
        agents._generate = should_not_infer
        try:
            assert await agents.correlate(contexts[0], contexts[1:]) == []
            assert await agents.correlate(contexts[2], contexts[:2]) == []
        finally:
            await agents.close()
    asyncio.run(run())


def test_evidence_generation_uses_bounded_original_excerpts_with_exact_report_pairs():
    left = packet(text="First original sentence. Another original sentence.")
    right = packet(text="Long original source " * 90)
    evidence = TypeAdapter(grounded_text_evidence([left, right]))
    assert evidence.validate_python({"report_id": left["id"], "quote": "First original sentence."}).quote == "First original sentence."
    for offset in range(0, len(right["text"]), 400):
        excerpt = right["text"][offset:offset + 400].strip()
        assert len(excerpt) <= 400
        assert evidence.validate_python({"report_id": right["id"], "quote": excerpt}).quote in right["text"]
    for item in ({"report_id": right["id"], "quote": left["text"]},
                 {"report_id": str(uuid4()), "quote": left["text"]},
                 {"report_id": left["id"], "quote": "Translated original sentence"}):
        with pytest.raises(ValidationError):
            evidence.validate_python(item)


@pytest.mark.parametrize("stage", ["triage", "verification", "correlation"])
def test_generated_evidence_cannot_quote_metadata_or_another_reports_text(stage):
    p = ReportPacket.model_validate(quick_packet(quick_needs=["cannot_speak"], location_text="Synthetic saved entrance")).model_dump(mode="json")
    q = packet(text="A distinct original written report.")
    signals = verification_signals([p], [q])
    candidate_id = str(uuid4())

    async def run():
        agents = OllamaAgents("http://unused.invalid", "TEST-ONLY-CAPTURE")
        calls = []
        async def capture(_prompt, _data, schema):
            calls.append(stage)
            if stage == "triage":
                output = {key: value for key, value in triage_for([p]).items() if key not in ("model", "generated_at")}
            elif stage == "verification":
                output = {key: value for key, value in (await TestOnlyAgents().verify([p], signals)).items() if key not in ("model", "generated_at")}
            else:
                output = {"suggestions": [{"candidate_incident_id": candidate_id, "confidence": .2,
                                          "reason": "Synthetic test only", "evidence": []}]}
            target = output["suggestions"][0] if stage == "correlation" else output
            target["evidence"] = [{"report_id": p["id"], "quote": p["text"]}, {"report_id": q["id"], "quote": q["text"]}]
            valid = schema.model_validate(output)
            for invalid in ({"report_id": p["id"], "quote": "Cannot speak"},
                            {"report_id": p["id"], "quote": p["location_text"]},
                            {"report_id": p["id"], "quote": q["text"]}):
                target["evidence"][0] = invalid
                with pytest.raises(ValidationError):
                    schema.model_validate(output)
            return valid
        agents._generate = capture
        try:
            if stage == "triage":
                result = await agents.triage([p, q])
            elif stage == "verification":
                result = await agents.verify([p], signals, [q])
            else:
                result = (await agents.correlate({"id": str(uuid4()), "reports": [p]}, [{"id": candidate_id, "reports": [q]}]))[0]
            assert result["evidence"] == [{"report_id": p["id"], "quote": p["text"]}, {"report_id": q["id"], "quote": q["text"]}]
            assert calls == [stage]
        finally:
            await agents.close()
    asyncio.run(run())
