"""Isolated media transport tests. Container fixtures are not emergency evidence."""
import hashlib
import json
import struct
from uuid import uuid4

from fastapi.testclient import TestClient
from pydantic import ValidationError
import pytest

from backend.app import create_app
from backend.schemas import ReportPacket
from backend.store import Store
from backend.tests.test_backend import TestOnlyAgents, packet


JPEG = b"\xff\xd8\xff\xe0" + b"synthetic container fixture" + b"\xff\xd9"


def box(kind, payload):
    return struct.pack(">I4s", len(payload) + 8, kind) + payload


def mp4(kind):
    return box(b"ftyp", b"isom\0\0\0\0isommp42") + box(b"mdat", b"fixture bytes") + box(b"moov", box(b"trak", box(b"mdia", box(b"hdlr", b"\0" * 8 + (b"soun" if kind == "audio" else b"vide") + b"\0" * 12))))


def manifest(content=JPEG, kind="image", **changes):
    return {"id": str(uuid4()), "kind": kind, "mime_type": {"image": "image/jpeg", "audio": "audio/mp4", "video": "video/mp4"}[kind],
            "byte_size": len(content), "sha256": hashlib.sha256(content).hexdigest(),
            "duration_ms": None if kind == "image" else 1000, **changes}


def media_packet(*items, **changes):
    return packet(schema_version=4, text="", attachments=list(items or [manifest()]), **changes)


def url(report, item=None):
    return f"/api/reports/{report['id']}/attachments" + (f"/{item['id']}" if item else "")


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(db_path=str(tmp_path / "media.sqlite3"), agents=TestOnlyAgents(), start_worker=False)) as client:
        yield client


def test_media_is_independent_durable_and_manifest_immutable(client):
    original = media_packet()
    item = original["attachments"][0]
    accepted = client.post("/api/reports", json=original)
    assert accepted.status_code == 202
    assert client.get(url(original)).json()["attachments"] == [{**item, "status": "pending"}]
    report, = client.get("/api/state").json()["reports"]
    assert report["media"][0]["status"] == "pending"
    assert report["receipts"][0]["type"] == "backend_received"
    assert client.get(url(original, item)).status_code == 404
    store = client.app.state.store
    with store.connection() as db:
        before = dict(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone())
    uploaded = client.put(url(original, item), content=JPEG, headers={"Content-Type": item["mime_type"]})
    assert uploaded.status_code == 200
    assert uploaded.json() == {**item, "report_id": original["id"], "status": "available", "duplicate": False}
    assert client.put(url(original, item), content=JPEG, headers={"Content-Type": item["mime_type"]}).json()["duplicate"]
    assert client.get(url(original)).json()["attachments"][0]["status"] == "available"
    download = client.get(url(original, item))
    assert download.content == JPEG and download.headers["content-type"] == "image/jpeg"
    assert download.headers["x-content-type-options"] == "nosniff"
    assert download.headers["cache-control"] == "no-store"
    assert "sandbox" in download.headers["content-security-policy"]
    assert download.headers["content-disposition"].startswith("inline;")
    partial = client.get(url(original, item), headers={"Range": "bytes=1-3"})
    assert partial.status_code == 206 and partial.content == JPEG[1:4]
    assert partial.headers["content-range"] == f"bytes 1-3/{len(JPEG)}"
    assert client.get(url(original, item), headers={"Range": "bytes=999999-"}).status_code == 416
    with store.connection() as db:
        assert dict(db.execute("SELECT packet_json,fingerprint FROM reports").fetchone()) == before
        assert db.execute("SELECT COUNT(*) FROM audit WHERE action='attachment.received'").fetchone()[0] == 1
    assert Store(store.path).report(original["id"])["media"][0]["status"] == "available"
    altered = {**original, "attachments": [{**item, "sha256": "a" * 64}]}
    assert client.post("/api/reports", json=altered).status_code == 409
    assert client.post("/api/reports", json={**original, "relay_path": ["A", "GATEWAY"], "hop_count": 1}).json()["duplicate"]
    assert not list(store.media_root.rglob(".upload-*"))


@pytest.mark.parametrize("kind", ["audio", "video"])
def test_matching_mp4_recording_and_streamed_playback(client, kind):
    content = mp4(kind)
    item = manifest(content, kind)
    original = media_packet(item)
    assert client.post("/api/reports", json=original).status_code == 202
    assert client.put(url(original, item), content=content, headers={"Content-Type": item["mime_type"]}).status_code == 200
    assert client.get(url(original, item)).content == content


@pytest.mark.parametrize("changes", [
    {"id": "../escape"}, {"id": "A712D6E6-115E-409E-B29B-7853F1EDCF26"}, {"byte_size": True},
    {"byte_size": 0}, {"byte_size": 1_048_577}, {"kind": "unknown"}, {"mime_type": "text/html"},
    {"mime_type": "video/mp4"}, {"sha256": "A" * 64}, {"sha256": "a" * 63}, {"duration_ms": 1000},
    {"kind": "video", "mime_type": "video/mp4", "duration_ms": 15_001},
    {"kind": "audio", "mime_type": "audio/mp4", "duration_ms": 30_001},
    {"kind": "audio", "mime_type": "audio/mp4", "duration_ms": 0},
])
def test_invalid_manifest_is_rejected(changes):
    with pytest.raises(ValidationError):
        ReportPacket.model_validate(media_packet({**manifest(), **changes}))


def test_manifest_count_total_duplicates_and_legacy_contract():
    for items in ([manifest()] * 2, [manifest() for _ in range(4)],
                  [manifest(mp4("video"), "video", byte_size=8_388_608) for _ in range(2)]):
        with pytest.raises(ValidationError):
            ReportPacket.model_validate(media_packet(*items))
    for version in (1, 2, 3):
        old = packet(schema_version=version)
        assert "attachments" not in ReportPacket.model_validate(old).model_dump(mode="json")
        with pytest.raises(ValidationError):
            ReportPacket.model_validate({**old, "attachments": []})
    with pytest.raises(ValidationError):
        ReportPacket.model_validate({**media_packet(), "schema_version": 4.0})


def test_unknown_report_unbound_attachment_mime_size_hash_and_chunk_limits(client):
    original = media_packet()
    item = original["attachments"][0]
    headers = {"Content-Type": "image/jpeg"}
    assert client.put(url(original, item), content=JPEG, headers=headers).status_code == 404
    client.post("/api/reports", json=original)
    assert client.put(url(original, manifest()), content=JPEG, headers=headers).status_code == 404
    assert client.put(url(original, item), content=JPEG, headers={"Content-Type": "text/html"}).status_code == 415
    assert client.put(url(original, item), content=JPEG + b"x", headers=headers).status_code == 413
    assert client.put(url(original, item), content=JPEG[:-1], headers=headers).status_code == 422
    assert client.put(url(original, item), content=b"x" * len(JPEG), headers=headers).status_code == 422
    assert client.put(url(original, item), content=iter([JPEG, b"overflow"]), headers=headers).status_code == 413
    assert client.get(url(original)).json()["attachments"][0]["status"] == "pending"
    assert not list(client.app.state.store.media_root.rglob(".upload-*"))
    assert client.post("/api/reports", content=b" " * 32_769, headers={"Content-Type": "application/json"}).status_code == 413
    assert client.put(url(original, item), content=b"", headers={**headers, "Content-Length": str(8_388_609)}).status_code == 413


@pytest.mark.parametrize("content,kind", [(b"<script>alert(1)</script>", "image"), (b"\xff\xd8\xffbad", "image"),
    (mp4("audio"), "video"), (mp4("video"), "audio"), (b"not mp4", "audio"),
    (box(b"ftyp", b"isom\0\0\0\0") + b"\0\0\0\x01oops", "audio")])
def test_signature_mismatch_rejected_even_with_matching_hash(client, content, kind):
    item = manifest(content, kind)
    original = media_packet(item)
    client.post("/api/reports", json=original)
    response = client.put(url(original, item), content=content, headers={"Content-Type": item["mime_type"]})
    assert response.status_code == 422
    assert client.get(url(original)).json()["attachments"][0]["status"] == "pending"


def test_protected_media_requires_auth_and_origin_guard(tmp_path):
    auth = {"X-API-Key": "test-only-secret"}
    with TestClient(create_app(db_path=str(tmp_path / "auth.db"), agents=TestOnlyAgents(), start_worker=False, api_token=auth["X-API-Key"])) as client:
        original = media_packet()
        item = original["attachments"][0]
        client.post("/api/reports", json=original, headers=auth)
        for endpoint in (url(original), url(original, item)):
            assert client.get(endpoint).status_code == 401
        assert client.put(url(original, item), content=JPEG, headers={"Content-Type": "image/jpeg"}).status_code == 401
        assert client.put(url(original, item), content=JPEG, headers={**auth, "Content-Type": "image/jpeg", "Origin": "https://other.test"}).status_code == 403
        assert client.put(url(original, item), content=JPEG, headers={**auth, "Content-Type": "image/jpeg"}).status_code == 200
        assert client.get(url(original, item), headers={"Range": "bytes=0-3"}).status_code == 401
        assert client.get(url(original, item), headers={**auth, "Range": "bytes=0-3"}).status_code == 206


def test_conflicting_existing_file_is_not_overwritten(client):
    original = media_packet()
    item = original["attachments"][0]
    client.post("/api/reports", json=original)
    path = client.app.state.store.media_path(original["id"], item["id"])
    path.parent.mkdir(parents=True)
    path.write_bytes(b"conflicting stored bytes")
    response = client.put(url(original, item), content=JPEG, headers={"Content-Type": "image/jpeg"})
    assert response.status_code == 409
    assert path.read_bytes() == b"conflicting stored bytes"
