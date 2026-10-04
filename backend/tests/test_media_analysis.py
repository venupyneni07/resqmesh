"""Tests use explicitly synthetic media/model responses, never emergency evidence."""
import asyncio
import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from backend.ai import InferenceUnavailable, InvalidAIOutput, OllamaAgents
from backend.app import create_app
from backend.media_analysis import PIPELINE_VERSION, MediaAnalyzer, prepare_media
from backend.store import Store, StoreError
from backend.tests.test_backend import TestOnlyAgents
from backend.tests.test_media import JPEG, manifest, media_packet
from backend.worker import Worker


# These are synthetic model outputs for contract tests, not observations of JPEG.
DETAILED_PHOTO_SUMMARY = (
    "The image appears to show a person seated beside a blue gate, with a bag on the ground. "
    "The person's hand rests on the gate, and a sign behind them reads 'North entrance'. "
    "A second figure is partly visible at the edge of the frame, but their activity is unclear. "
    "The image alone does not establish whether anyone needs assistance or when it was taken."
)
DETAILED_AUDIO_SUMMARY = (
    "The speaker says they are near a blue gate and cannot move, and asks for help. "
    "They mention that another person is waiting with them, but give no name or precise address. "
    "A low continuous sound is audible behind the speech; its source cannot be identified from this recording. "
    "These are statements heard in the recording, and the circumstances and current location still require confirmation."
)


def queued_store(tmp_path):
    store = Store(str(tmp_path / "analysis.db"))
    attachment = manifest()
    report = media_packet(attachment)
    store.accept(report)
    path = store.media_path(report["id"], attachment["id"])
    path.parent.mkdir(parents=True)
    path.write_bytes(JPEG)
    store.record_attachment(report["id"], attachment)
    return store, report, attachment


def interpretation(**changes):
    return {"transcript": "", "language": "unknown", "summary": "Synthetic test observation",
        "visual_observations": [], "audible_observations": [], "uncertainties": ["Synthetic test only"],
        "suggested_urgency": "unknown", "urgency_reason": "No real emergency in fixture",
        "requested_human_checks": ["Review original"], **changes}


def test_queue_is_durable_idempotent_and_packet_immutable(tmp_path):
    store, report, attachment = queued_store(tmp_path)
    with store.connection() as db:
        original = dict(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone())
    store.record_attachment(report["id"], attachment)
    assert store.media_queue_counts()["queued"] == 1
    job = store.claim_media_job()
    assert job["attempts"] == 1 and store.claim_media_job() is None
    restarted = Store(store.path)
    restarted.recover_media_jobs()
    job = restarted.claim_media_job()
    assert job["attempts"] == 1
    result = {**interpretation(), "model": "TEST-ONLY", "source_sha256": attachment["sha256"], "pipeline_version": "test"}
    restarted.save_media_analysis(job, result)
    restarted.record_attachment(report["id"], attachment)
    assert restarted.media_queue_counts() == {"queued": 0, "running": 0, "failed": 0, "complete": 1}
    state = restarted.report(report["id"])["media_analysis"][0]
    assert state["result"] == result and state["status"] == "complete"
    with restarted.connection() as db:
        assert dict(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone()) == original
        assert db.execute("SELECT COUNT(*) FROM audit WHERE action='attachment.received'").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0] == 1


def test_recovery_enqueues_published_file_but_not_missing_attachment(tmp_path):
    store = Store(str(tmp_path / "recovery.db"))
    items = [manifest(), manifest()]
    report = media_packet(*items)
    store.accept(report)
    path = store.media_path(report["id"], items[0]["id"])
    path.parent.mkdir(parents=True)
    path.write_bytes(JPEG)
    store.recover_media_jobs()
    statuses = [item["status"] for item in store.report(report["id"])["media_analysis"]]
    assert statuses == ["queued", "waiting_upload"]
    with pytest.raises(StoreError, match="finish uploading"):
        store.retry_media_analysis(report["id"], items[1]["id"])


@pytest.mark.parametrize("summary,observations", [
    ("The image appears uniformly white.", ["No distinct objects can be identified."]),
    (DETAILED_PHOTO_SUMMARY, ["Synthetic person beside a blue gate", "Synthetic sign reading North entrance"]),
])
def test_media_description_survives_worker_and_admin_api_without_invented_speech(
        monkeypatch, tmp_path, summary, observations):
    """Synthetic inference: both rich descriptions and brief blank-image results stay visible."""
    monkeypatch.setattr("backend.media_analysis.prepare_media", lambda *args: {
        "content": [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,c3ludGhldGlj"}}],
        "kind": "image", "duration_seconds": 0, "sampled_frame_seconds": [0], "audio_included": False})
    review = interpretation(summary=summary,
        visual_observations=observations,
        uncertainties=["This image alone cannot establish what happened."],
        urgency_reason="The attachment supplies no reliable urgency evidence.")
    def handler(request):
        if request.url.path == "/api/show":
            return httpx.Response(200, json={"capabilities": ["vision"]})
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {
            "content": json.dumps(review)}}]})
    with TestClient(create_app(db_path=str(tmp_path / "blank-media.db"), agents=TestOnlyAgents(),
                               start_worker=False, api_token="", production=False)) as client:
        attachment = manifest()
        packet = media_packet(attachment)
        assert client.post("/api/reports", json=packet).status_code == 202
        report, = client.get("/api/state").json()["reports"]
        assert report["media_analysis"][0]["status"] == "waiting_upload"
        store = client.app.state.store
        with store.connection() as db:
            original = tuple(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone())
        uploaded = client.put(f"/api/reports/{packet['id']}/attachments/{attachment['id']}",
            content=JPEG, headers={"Content-Type": "image/jpeg"})
        assert uploaded.status_code == 200
        report, = client.get("/api/state").json()["reports"]
        assert report["media_analysis"][0]["status"] == "queued"
        async def process():
            agents = OllamaAgents("http://test.local", "TEST-ONLY")
            await agents.client.aclose()
            agents.client = httpx.AsyncClient(base_url="http://test.local", transport=httpx.MockTransport(handler))
            try:
                await Worker(store, agents).process_media(store.claim_media_job())
            finally:
                await agents.close()
        asyncio.run(process())
        report, = client.get("/api/state").json()["reports"]
        item, = report["media_analysis"]
        assert item["status"] == "complete" and item["error"] is None
        assert item["result"]["summary"] == review["summary"]
        assert item["result"]["pipeline_version"] == "media-review-v3" == PIPELINE_VERSION
        assert item["result"]["visual_observations"] == observations
        assert item["result"]["uncertainties"] == review["uncertainties"]
        assert item["result"]["transcript"] == ""
        assert item["result"]["suggested_urgency"] == "unknown"
        assert item["result"]["dispatch_performed"] is False
        assert [r["type"] for r in report["receipts"]] == ["backend_received"]
        with store.connection() as db:
            assert tuple(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone()) == original
        assert store.media_path(packet["id"], attachment["id"]).read_bytes() == JPEG


def test_failure_retry_is_bounded_manual_retry_preserves_original(tmp_path):
    store, report, attachment = queued_store(tmp_path)
    worker = Worker(store, TestOnlyAgents(), retry_seconds=0)
    for attempt in range(3):
        asyncio.run(worker.process_media(store.claim_media_job()))
    assert store.claim_media_job() is None
    item, = store.report(report["id"])["media_analysis"]
    assert item["status"] == "failed" and item["attempts"] == 3
    assert "No real media inference" in item["error"]
    assert store.retry_media_analysis(report["id"], attachment["id"])["idempotent"] is False
    assert store.retry_media_analysis(report["id"], attachment["id"])["idempotent"] is True
    assert store.claim_media_job()["attempts"] == 1


def test_integrity_failure_prevents_decode(tmp_path):
    path = tmp_path / "modified"
    path.write_bytes(b"x" * len(JPEG))
    with pytest.raises(InvalidAIOutput, match="integrity"):
        prepare_media(path, manifest())


def test_critical_media_counts_active_incidents_once_and_requires_completed_analysis(tmp_path):
    store, report, attachment = queued_store(tmp_path)
    result = {**interpretation(suggested_urgency="critical"), "model": "TEST-ONLY",
              "source_sha256": attachment["sha256"], "pipeline_version": "test"}
    store.save_media_analysis(store.claim_media_job(), result)
    assert store.snapshot()["stats"]["critical_incidents"] == 1
    incident_id = store.report(report["id"])["incident_id"]
    with store.connection() as db:
        db.execute("UPDATE incidents SET triage_json=? WHERE id=?", (json.dumps({"suggested_urgency": "critical"}), incident_id))
    assert store.snapshot()["stats"]["critical_incidents"] == 1
    with store.connection() as db:
        db.execute("UPDATE incidents SET triage_json=NULL WHERE id=?", (incident_id,))
    store.retry_media_analysis(report["id"], attachment["id"])
    assert store.snapshot()["stats"]["critical_incidents"] == 0
    store.save_media_analysis(store.claim_media_job(), result)
    store.update_incident(incident_id, {"status": "resolved"})
    assert store.snapshot()["stats"]["critical_incidents"] == 0


def test_decoder_duration_limit(monkeypatch, tmp_path):
    path = tmp_path / "long"
    path.write_bytes(JPEG)
    monkeypatch.setattr("backend.media_analysis.shutil.which", lambda binary: binary)
    monkeypatch.setattr("backend.media_analysis._run", lambda *args: json.dumps({"streams": [{"codec_type": "audio"}], "format": {"duration": "3600"}}).encode())
    with pytest.raises(InvalidAIOutput, match="duration"):
        prepare_media(path, manifest(kind="audio"))


@pytest.mark.parametrize("kind,audio,frames,result,expected", [
    ("audio", True, [], interpretation(transcript="Test words", summary=DETAILED_AUDIO_SUMMARY), None),
    ("audio", True, [], interpretation(summary="Speech could not be understood",
        uncertainties=["The recording is unclear; review the original"]), None),
    ("image", False, [0], interpretation(visual_observations=["Blue image"]), None),
    ("video", True, [0, 1, 2], interpretation(transcript="Test words", visual_observations=["Blue frames"]), None),
    ("video", False, [0, 1, 2], interpretation(visual_observations=["Blue frames"]), None),
    ("image", False, [0], interpretation(transcript="Invented speech"), "claimed audio"),
    ("audio", True, [], interpretation(visual_observations=["Invented room"]), "claimed visual"),
])
def test_supported_multimodal_api_and_modality_grounding(monkeypatch, kind, audio, frames, result, expected):
    content = ([{"type": "input_audio", "input_audio": {"data": "c3ludGhldGlj", "format": "wav"}}] if audio else [])
    if frames:
        content += [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,c3ludGhldGlj"}}]
    monkeypatch.setattr("backend.media_analysis.prepare_media", lambda *args: {"content": content, "kind": kind,
        "duration_seconds": 3, "sampled_frame_seconds": frames, "audio_included": audio})
    requests = []
    def handler(request):
        requests.append(request)
        if request.url.path == "/api/show":
            return httpx.Response(200, json={"capabilities": ["vision", "audio"]})
        payload = json.loads(request.content)
        assert request.url.path == "/v1/chat/completions"
        assert payload["messages"][1]["content"][:-1] == content
        assert payload["reasoning_effort"] == "none"
        assert payload["frequency_penalty"] == (0.5 if audio else 0)
        assert payload["max_tokens"] == 1800
        assert "When speech is unintelligible, transcript must be empty" in payload["messages"][0]["content"]
        assert "An absent input modality is not negative evidence" in payload["messages"][0]["content"]
        assert "Do not guess a person's age, gender or emotional state" in payload["messages"][0]["content"]
        assert "absence of visible hazards alone MUST use unknown" in payload["messages"][0]["content"]
        assert payload["response_format"]["type"] == "json_schema"
        schema = payload["response_format"]["json_schema"]["schema"]["properties"]
        assert schema["summary"]["minLength"] == 1  # Blank/unclear media must not be padded.
        assert schema["summary"]["maxLength"] == 1200
        assert "3–5 useful sentences" in schema["summary"]["description"]
        assert "1–2 sentences" in schema["summary"]["description"]
        assert "Do not guess age, gender or emotional state" in schema["summary"]["description"]
        assert "Normal requires affirmative evidence of a non-urgent request" in schema["suggested_urgency"]["description"]
        assert "Use unknown for an ordinary portrait, blank scene" in schema["suggested_urgency"]["description"]
        assert "Missing audio or images must not be used" in schema["urgency_reason"]["description"]
        guidance = payload["messages"][1]["content"][-1]["text"]
        if kind == "image":
            assert "visible subject or scene" in guidance
            assert "clearly readable text" in guidance
        elif kind == "audio":
            assert "location clues, people mentioned and requested help only when actually heard" in guidance
            assert "non-speech sounds" in guidance
            assert schema["visual_observations"]["maxItems"] == 0
        else:
            assert "sampled frames" in guidance and "do not invent movement" in guidance
        if not audio:
            assert "No audio was supplied; do not make audible findings" in guidance
            assert schema["audible_observations"]["maxItems"] == 0
            assert schema["transcript"]["const"] == ""
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})
    async def run():
        agents = OllamaAgents("http://test.local", "TEST-ONLY")
        await agents.client.aclose()
        agents.client = httpx.AsyncClient(base_url="http://test.local", transport=httpx.MockTransport(handler))
        try:
            if expected:
                with pytest.raises(InvalidAIOutput, match=expected):
                    await MediaAnalyzer(agents).analyze(Path("test-only"), manifest(kind=kind))
            else:
                output = await MediaAnalyzer(agents).analyze(Path("test-only"), manifest(kind=kind))
                assert output["human_review_required"] and output["dispatch_performed"] is False
                assert output["authenticity"] == "unverified"
                assert output["transcript"] == result["transcript"]
                assert output["summary"] == result["summary"]
                assert output["uncertainties"] == result["uncertainties"]
                assert output["coverage"]["sampled_frame_seconds"] == frames
                assert len(requests) == 2
        finally:
            await agents.close()
    asyncio.run(run())


@pytest.mark.parametrize("finish_reason,expected", [
    ("length", "output limit after 1800 output tokens"),
    (None, "incomplete"),
])
def test_incomplete_media_output_is_rejected_even_when_json_is_valid(monkeypatch, finish_reason, expected):
    monkeypatch.setattr("backend.media_analysis.prepare_media", lambda *args: {"content": [],
        "kind": "audio", "sampled_frame_seconds": [], "audio_included": True})
    def handler(request):
        if request.url.path == "/api/show":
            return httpx.Response(200, json={"capabilities": ["audio"]})
        return httpx.Response(200, json={"usage": {"completion_tokens": 1800}, "choices": [
            {"finish_reason": finish_reason, "message": {"content": json.dumps(interpretation())}}]})
    async def run():
        agents = OllamaAgents("http://test.local", "TEST-ONLY")
        await agents.client.aclose()
        agents.client = httpx.AsyncClient(base_url="http://test.local", transport=httpx.MockTransport(handler))
        try:
            with pytest.raises(InvalidAIOutput, match=expected):
                await MediaAnalyzer(agents).analyze(Path("test-only"), manifest(kind="audio"))
        finally:
            await agents.close()
    asyncio.run(run())


def test_missing_model_capability_fails_before_inference(monkeypatch):
    monkeypatch.setattr("backend.media_analysis.prepare_media", lambda *args: {"content": [],
        "kind": "audio", "sampled_frame_seconds": [], "audio_included": True})
    async def run():
        agents = OllamaAgents("http://test.local", "TEST-ONLY")
        await agents.client.aclose()
        agents.client = httpx.AsyncClient(base_url="http://test.local", transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json={"capabilities": ["vision"]})))
        try:
            with pytest.raises(InferenceUnavailable, match="lacks required"):
                await MediaAnalyzer(agents).analyze(Path("test-only"), manifest())
        finally:
            await agents.close()
    asyncio.run(run())
