"""Isolated, explicitly synthetic HTTP fixture for account/media browser tests."""
import hashlib
import os
from pathlib import Path
import tempfile
from uuid import uuid4

from backend.app import create_app
from backend.security import SecurityStore
from backend.store import Store
from backend.tests.test_backend import TestOnlyAgents
from backend.tests.test_media import manifest, media_packet
from backend.tests.test_media_analysis import interpretation

root = Path(tempfile.mkdtemp(prefix="resqmesh-browser-fixture-"))
security = SecurityStore(str(root / "accounts.sqlite"))
security.set_user("test-responder", "synthetic-browser-password", "responder")
security.set_user("test-viewer", "synthetic-browser-password", "viewer")
store = Store(str(root / "reports.sqlite"))
content = Path("android/app/src/androidTest/assets/media/synthetic-photo.jpg").read_bytes()
attachment = manifest(content)
report = {**media_packet(attachment, building="Test fixture room"), "text": "SYNTHETIC BROWSER TEST. No real emergency."}
store.accept(report)
path = store.media_path(report["id"], attachment["id"])
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(content)
store.record_attachment(report["id"], attachment)
store.save_media_analysis(store.claim_media_job(), {
    **interpretation(summary="SYNTHETIC TEST ONLY: sample media review", suggested_urgency="high",
                     transcript="<script>test text remains inert</script>", language="test fixture"),
    "model": "TEST-ONLY-STUB", "source_sha256": attachment["sha256"], "pipeline_version": "fixture",
    "generated_at": 1791105000000, "human_review_required": True,
    "coverage": {"test_fixture": True},
})
app = create_app(db_path=store.path, auth_db_path=security.path, agents=TestOnlyAgents(), start_worker=False)
