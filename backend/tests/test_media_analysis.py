"""Tests use explicitly synthetic media/model responses, never emergency evidence."""
import asyncio
import json
from pathlib import Path

import httpx
import pytest

from backend.ai import InferenceUnavailable, InvalidAIOutput, OllamaAgents
from backend.media_analysis import MediaAnalyzer, prepare_media
from backend.store import Store, StoreError
from backend.tests.test_backend import TestOnlyAgents
from backend.tests.test_media import JPEG, manifest, media_packet
from backend.worker import Worker


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
    ("audio", True, [], interpretation(transcript="Test words"), None),
    ("audio", True, [], interpretation(summary="Speech could not be understood",
        uncertainties=["The recording is unclear; review the original"]), None),
    ("image", False, [0], interpretation(visual_observations=["Blue image"]), None),
    ("video", True, [0, 1, 2], interpretation(transcript="Test words", visual_observations=["Blue frames"]), None),
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
        assert payload["response_format"]["type"] == "json_schema"
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
