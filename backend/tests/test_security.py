"""Account roles, revoked sessions, gateway isolation and production fail-closed checks."""
import pytest
from fastapi.testclient import TestClient

from backend.app import create_app
from backend.security import COOKIE, SecurityStore
from backend.tests.test_backend import TestOnlyAgents, packet

PASSWORD = "test-only-fixture-passphrase"


@pytest.fixture
def setup(tmp_path):
    auth = SecurityStore(str(tmp_path / "auth.sqlite"))
    for role in ("viewer", "responder", "admin"):
        auth.set_user(role, PASSWORD, role)
    auth.register_gateway("A", "a" * 40)
    auth.register_gateway("B", "b" * 40)
    app = create_app(db_path=str(tmp_path / "reports.sqlite"), auth_db_path=auth.path,
                     agents=TestOnlyAgents(), start_worker=False)
    with TestClient(app) as client:
        yield client, auth


def signin(client, name):
    response = client.post("/api/auth/login", json={"username": name, "password": PASSWORD})
    assert response.status_code == 200
    assert "httponly" in response.headers["set-cookie"].lower()
    assert "samesite=strict" in response.headers["set-cookie"].lower()
    return response


def test_accounts_roles_identity_and_session_revocation(setup):
    client, auth = setup
    assert client.get("/api/state").status_code == 401
    p = packet()
    accepted = client.post("/api/reports", json=p, headers={"X-API-Key": "a" * 40, "X-ResQMesh-Node-ID": "A"})
    assert accepted.status_code == 202
    incident = accepted.json()["incident_id"]
    signin(client, "viewer")
    assert client.get("/api/auth/me").json()["role"] == "viewer"
    assert client.get("/api/state").status_code == 200
    assert client.post(f"/api/incidents/{incident}/acknowledge").status_code == 403
    client.post("/api/auth/logout")
    assert client.get("/api/state").status_code == 401
    signin(client, "responder")
    assert client.post(f"/api/incidents/{incident}/acknowledge").status_code == 200
    assert client.post("/api/reports", json=packet()).status_code == 403
    auth.disable_user("responder")
    assert client.get("/api/state").status_code == 401


def test_gateway_cannot_read_dashboard_or_impersonate_other_gateway(setup):
    client, _ = setup
    a = {"X-API-Key": "a" * 40, "X-ResQMesh-Node-ID": "A"}
    b = {"X-API-Key": "b" * 40, "X-ResQMesh-Node-ID": "B"}
    assert client.get("/api/state", headers=a).status_code == 403
    assert client.post("/api/gateways/B/heartbeat", json={"simulation": True}, headers=a).status_code == 403
    assert client.post("/api/reports", json=packet(), headers={**a, "X-ResQMesh-Node-ID": "B"}).status_code == 403
    p = packet()
    assert client.post("/api/reports", json=p, headers=a).status_code == 202
    assert client.get("/api/receipts", params={"report_id": p["id"]}, headers=a).status_code == 200
    assert client.get("/api/receipts", params={"report_id": p["id"]}, headers=b).status_code == 403
    assert client.get(f"/api/reports/{p['id']}/attachments", headers=b).status_code == 403


def test_csrf_invalid_login_and_password_change_revoke_session(setup):
    client, auth = setup
    assert client.post("/api/auth/login", json={"username": "admin", "password": "wrong"}).status_code == 401
    assert client.post("/api/auth/login", json={"username": "admin", "password": PASSWORD}, headers={"Origin": "https://evil.invalid"}).status_code == 403
    signin(client, "admin")
    assert client.post("/api/auth/logout", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
    auth.set_user("admin", PASSWORD + "-changed", "admin")
    assert client.get("/api/state").status_code == 401


def test_login_rate_limit_and_chunked_limit(setup):
    client, _ = setup
    for _ in range(8):
        assert client.post("/api/auth/login", json={"username": "missing", "password": "invalid"}).status_code == 401
    assert client.post("/api/auth/login", json={"username": "missing", "password": "invalid"}).status_code == 429
    assert client.post("/api/reports", content=iter([b"x" * 20_000, b"x" * 20_000]), headers={"X-API-Key": "a" * 40, "X-ResQMesh-Node-ID": "A"}).status_code == 413


def test_production_fails_closed_and_secure_cookie(tmp_path, monkeypatch):
    monkeypatch.delenv("RESQMESH_AUTH_DB_PATH", raising=False)
    with pytest.raises(RuntimeError, match="AUTH_DB_PATH"):
        create_app(production=True)
    auth = SecurityStore(str(tmp_path / "secure.sqlite"))
    with pytest.raises(RuntimeError, match="HTTPS"):
        create_app(production=True, auth_db_path=auth.path)
    monkeypatch.setenv("RESQMESH_PUBLIC_ORIGIN", "https://testserver")
    auth.set_user("admin", PASSWORD, "admin")
    app = create_app(production=True, auth_db_path=auth.path, db_path=str(tmp_path / "reports"), agents=TestOnlyAgents(), start_worker=False)
    with TestClient(app, base_url="https://testserver") as client:
        response = signin(client, "admin")
        assert "secure" in response.headers["set-cookie"].lower()
        assert client.get("/api/state").headers["strict-transport-security"] == "max-age=31536000"
    with TestClient(app, base_url="http://testserver") as client:
        assert client.get("/api/state").status_code == 400
