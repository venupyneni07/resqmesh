from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import hmac
import os
import re
from dataclasses import asdict
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .ai import OllamaAgents
from .media import receive_attachment
from .schemas import CorrelationDecision, GatewayHeartbeat, IncidentPatch, ReportPacket, VerificationAction
from .store import Store, StoreError
from .worker import Worker
from .security import COOKIE, SESSION_SECONDS, Principal, RateLimiter, SecurityStore, allowed, current_actor

ROOT = Path(__file__).resolve().parent


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=1, max_length=256)


def create_app(*, db_path: str | None = None, agents=None, start_worker: bool = True,
               retry_seconds: float = 15, api_token: str | None = None,
               auth_db_path: str | None = None, production: bool | None = None) -> FastAPI:
    """Dependency injection exists for tests; production always constructs real OllamaAgents."""
    token = api_token if api_token is not None else os.getenv("RESQMESH_API_TOKEN", "")
    production = production if production is not None else os.getenv("RESQMESH_ENV") == "production"
    auth_path = auth_db_path or os.getenv("RESQMESH_AUTH_DB_PATH")
    if production and not auth_path:
        raise RuntimeError("Production requires RESQMESH_AUTH_DB_PATH; anonymous access is disabled")
    security = SecurityStore(auth_path) if auth_path else None
    limiter = RateLimiter()
    public_origin = os.getenv("RESQMESH_PUBLIC_ORIGIN", "").rstrip("/")
    if production and not public_origin.startswith("https://"):
        raise RuntimeError("Production requires an HTTPS RESQMESH_PUBLIC_ORIGIN")

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        store = Store(db_path or os.getenv("RESQMESH_DB_PATH", str(ROOT / "data" / "resqmesh.sqlite3")))
        ai = agents or OllamaAgents(os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434"),
                                  os.getenv("OLLAMA_MODEL", "gemma4:e2b"),
                                  float(os.getenv("OLLAMA_TIMEOUT_SECONDS", "180")))
        worker = Worker(store, ai, retry_seconds)
        application.state.store, application.state.worker = store, worker
        task = asyncio.create_task(worker.run()) if start_worker else None
        try:
            yield
        finally:
            if task:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task
            await ai.close()

    application = FastAPI(title="ResQMesh", version="1.0.0", lifespan=lifespan)

    @application.middleware("http")
    async def api_guard(request: Request, call_next):
        if request.url.path.startswith("/api/"):
            path = request.url.path
            public = path in {"/api/health", "/api/auth/login", "/api/auth/config"}
            if production and request.url.scheme != "https" and path != "/api/health":
                return JSONResponse({"detail": "HTTPS is required"}, status_code=400)
            principal = security.authenticate(request.cookies.get(COOKIE), request.headers.get("x-api-key")) if security else None
            if security and not public:
                if not principal:
                    return JSONResponse({"detail": "Sign in to the responder workspace"}, status_code=401)
                if not allowed(principal, request.method, path, request.headers.get("x-resqmesh-node-id")):
                    return JSONResponse({"detail": "This account cannot perform that action"}, status_code=403)
                if principal.role == "gateway" and request.method != "POST":
                    report_match = re.match(r"/api/reports/([0-9a-f-]{36})/", path)
                    report_id = report_match[1] if report_match else request.query_params.get("report_id")
                    with request.app.state.store.connection() as db:
                        observed = db.execute("SELECT 1 FROM delivery_observations WHERE report_id=? AND gateway_id=?", (report_id, principal.node_id)).fetchone()
                    if not observed:
                        return JSONResponse({"detail": "Gateway has not uploaded this report"}, status_code=403)
            elif not security and not public and token:
                supplied = request.headers.get("x-api-key", "")
                if not hmac.compare_digest(supplied.encode(), token.encode()):
                    return JSONResponse({"detail": "A valid X-API-Key is required"}, status_code=401)
            request.state.principal = principal
            if security:
                client = request.client.host if request.client else "unknown"
                bucket = "login:" + client if path == "/api/auth/login" else "api:" + (principal.name if principal else client)
                if not limiter.allow(bucket, 8 if path == "/api/auth/login" else 240):
                    return JSONResponse({"detail": "Too many requests; retry shortly"}, status_code=429, headers={"Retry-After": "60"})
            if request.method not in ("GET", "HEAD", "OPTIONS"):
                origin = request.headers.get("origin")
                expected = public_origin or f"{request.url.scheme}://{request.url.netloc}"
                if origin and origin != expected:
                    return JSONResponse({"detail": "Cross-origin mutations are not permitted"}, status_code=403)
                if request.headers.get("sec-fetch-site") == "cross-site":
                    return JSONResponse({"detail": "Cross-site mutations are not permitted"}, status_code=403)
            length = request.headers.get("content-length", "0")
            media_upload = request.method == "PUT" and re.fullmatch(r"/api/reports/[0-9a-f-]{36}/attachments/[0-9a-f-]{36}", request.url.path)
            body_limit = 8_388_608 if media_upload else 32_768
            if length.isdigit() and int(length) > body_limit:
                return JSONResponse({"detail": "Request body is too large"}, status_code=413)
            if request.method in {"POST", "PUT", "PATCH"} and not media_upload:
                data = bytearray()
                async for chunk in request.stream():
                    data.extend(chunk)
                    if len(data) > body_limit:
                        return JSONResponse({"detail": "Request body is too large"}, status_code=413)
                request._body = bytes(data)
        actor_token = current_actor.set(getattr(request.state, "principal", None).name if getattr(request.state, "principal", None) else None)
        try:
            response = await call_next(request)
        finally:
            current_actor.reset(actor_token)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        if "Content-Security-Policy" not in response.headers:
            response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        if production:
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
        response.headers["Cache-Control"] = "no-store" if request.url.path.startswith("/api/") else "no-cache"
        return response

    @application.get("/api/auth/config")
    async def auth_config():
        return {"mode": "accounts" if security else "legacy_key" if token else "local_demo", "production": production}

    @application.post("/api/auth/login")
    async def login(body: Login):
        if not security:
            raise StoreError(409, "Individual accounts are not configured for this local demo")
        result = await asyncio.to_thread(security.login, body.username, body.password)
        if not result:
            raise StoreError(401, "Invalid username or password")
        session, principal = result
        response = JSONResponse(asdict(principal))
        response.set_cookie(COOKIE, session, max_age=SESSION_SECONDS, secure=production,
                            httponly=True, samesite="strict", path="/api")
        return response

    @application.get("/api/auth/me")
    async def me(request: Request):
        return asdict(request.state.principal) if security else {"name": "Local demo", "role": "demo"}

    @application.post("/api/auth/logout")
    async def logout(request: Request):
        if security:
            security.logout(request.cookies.get(COOKIE))
        response = JSONResponse({"signed_out": True})
        response.delete_cookie(COOKIE, path="/api", secure=production, httponly=True, samesite="strict")
        return response

    @application.exception_handler(StoreError)
    async def store_error(_request: Request, exc: StoreError):
        return JSONResponse({"detail": exc.detail}, status_code=exc.status)

    @application.get("/api/health")
    async def health(request: Request):
        with request.app.state.store.connection() as db:
            db.execute("SELECT 1").fetchone()
        return {"status": "ok", "service": "resqmesh", "database": "ok", **({} if security else {"ai": request.app.state.worker.ai_state()})}

    @application.get("/api/state")
    async def state(request: Request):
        return {**request.app.state.store.snapshot(), "ai": request.app.state.worker.ai_state(),
                "account": asdict(request.state.principal) if security else {"name": "Local demo", "role": "demo"}}

    @application.post("/api/reports", status_code=202)
    async def accept_report(packet: ReportPacket, request: Request):
        claimed_gateway = request.headers.get("x-resqmesh-node-id")
        if claimed_gateway is not None and claimed_gateway != packet.relay_path[-1]:
            raise StoreError(422, "X-ResQMesh-Node-ID must match the uploading node at the end of relay_path")
        result = request.app.state.store.accept(packet.model_dump(mode="json"))
        request.app.state.worker.wake.set()
        return result

    @application.post("/api/gateways/{node_id}/heartbeat")
    async def heartbeat(node_id: str, body: GatewayHeartbeat, request: Request):
        if re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", node_id) is None:
            raise StoreError(422, "Invalid node ID")
        return request.app.state.store.heartbeat(node_id, body.simulation)

    @application.get("/api/reports/{report_id}/attachments")
    async def attachment_status(report_id: UUID, request: Request):
        report = request.app.state.store.report(str(report_id))
        return {"attachments": report["media"]}

    @application.put("/api/reports/{report_id}/attachments/{attachment_id}")
    async def upload_attachment(report_id: UUID, attachment_id: UUID, request: Request):
        return await receive_attachment(request, request.app.state.store, str(report_id), str(attachment_id))

    @application.get("/api/reports/{report_id}/attachments/{attachment_id}")
    async def download_attachment(report_id: UUID, attachment_id: UUID, request: Request):
        store = request.app.state.store
        manifest = store.attachment(str(report_id), str(attachment_id))
        path = store.media_path(str(report_id), str(attachment_id))
        if not path.is_file():
            raise StoreError(404, "SOS received; attachment is still pending")
        suffix = {"audio": "m4a", "image": "jpg", "video": "mp4"}[manifest["kind"]]
        return FileResponse(path, media_type=manifest["mime_type"], filename=f"{attachment_id}.{suffix}",
                            content_disposition_type="inline", headers={
                                "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store",
                                "Content-Security-Policy": "default-src 'none'; sandbox",
                            })

    @application.post("/api/reports/{report_id}/attachments/{attachment_id}/analyze", status_code=202)
    async def retry_media(report_id: UUID, attachment_id: UUID, request: Request):
        result = request.app.state.store.retry_media_analysis(str(report_id), str(attachment_id))
        request.app.state.worker.wake.set()
        return result

    @application.get("/api/receipts")
    async def receipts(report_id: UUID, request: Request):
        return request.app.state.store.receipts(str(report_id))

    @application.post("/api/incidents/{incident_id}/verification")
    async def verify_incident(incident_id: UUID, action: VerificationAction, request: Request):
        return request.app.state.store.verification_action(str(incident_id), **action.model_dump())

    @application.patch("/api/incidents/{incident_id}")
    async def patch_incident(incident_id: UUID, patch: IncidentPatch, request: Request):
        return request.app.state.store.update_incident(str(incident_id), patch.model_dump(exclude_unset=True))

    @application.post("/api/incidents/{incident_id}/acknowledge")
    async def acknowledge(incident_id: UUID, request: Request):
        return request.app.state.store.acknowledge(str(incident_id))

    @application.post("/api/correlations/{correlation_id}/decision")
    async def decide(correlation_id: UUID, decision: CorrelationDecision, request: Request):
        result = request.app.state.store.decide(str(correlation_id), decision.decision)
        request.app.state.worker.wake.set()
        return result

    @application.post("/api/reports/{report_id}/reanalyze")
    async def reanalyze(report_id: UUID, request: Request):
        result = request.app.state.store.reanalyze(str(report_id))
        request.app.state.worker.wake.set()
        return result

    @application.get("/")
    async def dashboard():
        index = ROOT / "static" / "index.html"
        if not index.exists():
            return JSONResponse({"detail": "Dashboard files are not installed; API is available"}, status_code=503)
        return FileResponse(index)

    application.mount("/static", StaticFiles(directory=str(ROOT / "static"), check_dir=False), name="static")
    return application


app = create_app()
