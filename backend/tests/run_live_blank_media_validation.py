"""Opt-in real loopback HTTP + media Worker + Ollama check with synthetic inputs.

Run: .venv/bin/python -m backend.tests.run_live_blank_media_validation
Creates an isolated database and local server; never uploads to the live workspace.
The four text-agent jobs are intentionally outside this focused media-worker check.
"""
import asyncio
from contextlib import asynccontextmanager, suppress
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
from uuid import uuid4

import httpx
import uvicorn

from backend.ai import now_ms
from backend.app import create_app


ROOT = Path(__file__).resolve().parents[2]
LIVE_DB = ROOT / "backend/data/resqmesh.sqlite3"


def live_snapshot():
    if not LIVE_DB.exists():
        return {"reports": {}, "attachments": {}}
    with sqlite3.connect(f"file:{LIVE_DB}?mode=ro", uri=True) as db:
        rows = db.execute("SELECT id,packet_json,fingerprint FROM reports ORDER BY id").fetchall()
    result = {"reports": {}, "attachments": {}}
    for report_id, raw, fingerprint in rows:
        result["reports"][report_id] = hashlib.sha256((raw + fingerprint).encode()).hexdigest()
        for attachment in json.loads(raw).get("attachments", []):
            path = Path(str(LIVE_DB) + ".media") / report_id / attachment["id"]
            result["attachments"][report_id + ":" + attachment["id"]] = (
                hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None)
    return result


def main():
    before = live_snapshot()
    if LIVE_DB.exists():
        with sqlite3.connect(f"file:{LIVE_DB}?mode=ro", uri=True) as db:
            busy = db.execute("SELECT (SELECT COUNT(*) FROM media_analysis WHERE status IN ('queued','running')) + "
                              "(SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running'))").fetchone()[0]
        if busy:
            raise RuntimeError("Live model jobs are pending; run this isolated check after they finish")
    output = ROOT / "artifacts/blank-media-validation" / str(uuid4())
    output.mkdir(parents=True)
    photo = output / "synthetic-white.jpg"
    ffmpeg = os.getenv("RESQMESH_FFMPEG") or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("Local FFmpeg is required")
    subprocess.run([ffmpeg, "-v", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=white:s=320x240", "-frames:v", "1", str(photo)], check=True, timeout=30)
    # Scope environment changes to this opt-in script process, not running services.
    os.environ.pop("RESQMESH_AUTH_DB_PATH", None)
    app = create_app(db_path=str(output / "isolated.sqlite3"), start_worker=False,
                     retry_seconds=0, api_token="", production=False)
    ordinary_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def media_lifespan(application):
        async with ordinary_lifespan(application):
            application.state.store.recover_media_jobs()
            task = asyncio.create_task(application.state.worker.run_media())
            try:
                yield
            finally:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task

    app.router.lifespan_context = media_lifespan
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    address = f"http://127.0.0.1:{listener.getsockname()[1]}"
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning"))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    evidence = {"synthetic_only": True, "real_loopback_http": True,
                "scope": "Media worker; text-agent jobs intentionally not processed", "reports": []}
    try:
        deadline = time.monotonic() + 20
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.05)
        assert server.started, "Isolated server failed to start"
        with httpx.Client(base_url=address, timeout=15, trust_env=False) as client:
            originals = {}
            for kind, path in [("image", photo), ("audio", ROOT / "backend/tests/fixtures/media/synthetic-voice.m4a")]:
                content = path.read_bytes()
                media = {"id": str(uuid4()), "kind": kind,
                         "mime_type": "image/jpeg" if kind == "image" else "audio/mp4",
                         "byte_size": len(content), "sha256": hashlib.sha256(content).hexdigest(),
                         "duration_ms": None if kind == "image" else 5000}
                packet = {"schema_version": 4, "id": str(uuid4()), "origin_id": "SYNTHETIC-VALIDATION",
                          "created_at": now_ms(), "expires_at": now_ms() + 3600000,
                          "text": "Synthetic validation only. This is not a live emergency.",
                          "building": "Synthetic validation", "zone": None, "hop_count": 0, "max_hops": 8,
                          "relay_path": ["SYNTHETIC-VALIDATION"], "simulation": True, "attachments": [media]}
                accepted = client.post("/api/reports", json=packet)
                assert accepted.status_code == 202, accepted.text
                state = client.get("/api/state").json()
                report = next(r for r in state["reports"] if r["id"] == packet["id"])
                assert report["media_analysis"][0]["status"] == "waiting_upload"
                with app.state.store.connection() as db:
                    originals[packet["id"]] = tuple(db.execute(
                        "SELECT packet_json,fingerprint FROM reports WHERE id=?", (packet["id"],)).fetchone())
                route = f"/api/reports/{packet['id']}/attachments/{media['id']}"
                uploaded = client.put(route, content=content, headers={"Content-Type": media["mime_type"]})
                assert uploaded.status_code == 200, uploaded.text
                assert client.get(route).content == content
                evidence["reports"].append({"id": packet["id"], "fixture": path.name, "kind": kind})
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                reports = client.get("/api/state").json()["reports"]
                if all(r["media_analysis"][0]["status"] in ("complete", "failed") for r in reports):
                    break
                time.sleep(.25)
            for item in evidence["reports"]:
                report = next(r for r in reports if r["id"] == item["id"])
                item["analysis"] = report["media_analysis"][0]
                assert [r["type"] for r in report["receipts"]] == ["backend_received"]
                with app.state.store.connection() as db:
                    item["original_packet_unchanged"] = tuple(db.execute(
                        "SELECT packet_json,fingerprint FROM reports WHERE id=?", (item["id"],)).fetchone()) == originals[item["id"]]
                    item["review_event_count"] = db.execute(
                        "SELECT COUNT(*) FROM audit WHERE action='media.analysis_completed' AND entity_id=?", (item["id"],)).fetchone()[0]
                result = item["analysis"].get("result") or {}
                if item["kind"] == "audio":
                    item["known_speech_matches_ignoring_case"] = result.get("transcript", "").casefold() == (
                        "This is a test recording. I need help near the blue gate. I cannot move.".casefold())
                print(json.dumps({"fixture": item["fixture"], "status": item["analysis"]["status"],
                                  "summary": result.get("summary"), "urgency": result.get("suggested_urgency")}), flush=True)
    finally:
        server.should_exit = True
        thread.join(timeout=15)
        listener.close()
    after = live_snapshot()
    evidence["live_data_preservation"] = {name: {
        "before": len(before[name]), "after": len(after[name]),
        "originals_unchanged": all(after[name].get(key) == value for key, value in before[name].items())}
        for name in before}
    evidence["quality_note"] = "Completion is not general inference accuracy. Results remain AI interpretations for human review."
    destination = output / "evidence.json"
    destination.write_text(json.dumps(evidence, indent=2))
    print("Evidence:", destination)
    print(json.dumps(evidence["live_data_preservation"]))
    assert all(item["analysis"]["status"] == "complete" for item in evidence["reports"])
    assert all(item["original_packet_unchanged"] and item["review_event_count"] == 1 for item in evidence["reports"])
    assert all(item["originals_unchanged"] for item in evidence["live_data_preservation"].values())
    assert all(item.get("known_speech_matches_ignoring_case", True) for item in evidence["reports"])


if __name__ == "__main__":
    main()
