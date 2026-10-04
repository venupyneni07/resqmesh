"""Local account/session store. Production refuses anonymous or plaintext API access."""
from __future__ import annotations

from collections import OrderedDict, deque
from contextvars import ContextVar
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time

current_actor: ContextVar[str | None] = ContextVar("resqmesh_actor", default=None)
COOKIE = "resqmesh_session"
SESSION_SECONDS = 8 * 60 * 60
ROLES = {"viewer", "responder", "admin"}


@dataclass(frozen=True)
class Principal:
    name: str
    role: str
    node_id: str | None = None


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password: str, salt: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 600_000).hex()


class SecurityStore:
    def __init__(self, path: str):
        self.path = str(Path(path).resolve())
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        # Restrict a newly created file before SQLite writes password hashes to it.
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(fd)
        os.chmod(self.path, 0o600)
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    name TEXT PRIMARY KEY, role TEXT NOT NULL, salt TEXT NOT NULL,
                    password_hash TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    hash TEXT PRIMARY KEY, name TEXT NOT NULL REFERENCES users(name), expires REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS gateways (
                    node_id TEXT PRIMARY KEY, key_hash TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS security_audit (
                    id INTEGER PRIMARY KEY, at REAL NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL
                );
            """)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def set_user(self, name: str, password: str, role: str):
        if not re.fullmatch(r"[A-Za-z0-9_.@-]{1,80}", name) or role not in ROLES:
            raise ValueError("Invalid account name or role")
        if not 14 <= len(password) <= 256:
            raise ValueError("Passwords must be between 14 and 256 characters")
        salt = secrets.token_hex(16)
        hashed = password_hash(password, salt)
        with self.connection() as db:
            db.execute("INSERT INTO users VALUES(?,?,?,?,1) ON CONFLICT(name) DO UPDATE SET role=excluded.role,salt=excluded.salt,password_hash=excluded.password_hash,enabled=1", (name, role, salt, hashed))
            db.execute("DELETE FROM sessions WHERE name=?", (name,))
            db.execute("INSERT INTO security_audit(at,actor,action) VALUES(?,?,?)", (time.time(), name, "account.provisioned"))

    def disable_user(self, name: str):
        with self.connection() as db:
            db.execute("UPDATE users SET enabled=0 WHERE name=?", (name,))
            db.execute("DELETE FROM sessions WHERE name=?", (name,))

    def register_gateway(self, node_id: str, key: str):
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", node_id) or len(key) < 32:
            raise ValueError("Gateway needs a valid node ID and a random key of at least 32 characters")
        with self.connection() as db:
            db.execute("INSERT INTO gateways VALUES(?,?,1) ON CONFLICT(node_id) DO UPDATE SET key_hash=excluded.key_hash,enabled=1", (node_id, digest(key)))

    def login(self, name: str, password: str) -> tuple[str, Principal] | None:
        with self.connection() as db:
            row = db.execute("SELECT * FROM users WHERE name=?", (name,)).fetchone()
            # Unknown users incur the same password derivation cost.
            hashed = password_hash(password, row["salt"] if row else "00" * 16)
            valid = hmac.compare_digest(hashed, row["password_hash"] if row else "00" * 32)
            if not valid or not row or not row["enabled"]:
                return None
            token = secrets.token_urlsafe(32)
            db.execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO sessions VALUES(?,?,?)", (digest(token), name, time.time() + SESSION_SECONDS))
            db.execute("INSERT INTO security_audit(at,actor,action) VALUES(?,?,?)", (time.time(), name, "session.created"))
            return token, Principal(name, row["role"])

    def authenticate(self, session: str | None, api_key: str | None) -> Principal | None:
        with self.connection() as db:
            if session:
                row = db.execute("SELECT u.name,u.role FROM sessions s JOIN users u ON u.name=s.name WHERE s.hash=? AND s.expires>? AND u.enabled=1", (digest(session), time.time())).fetchone()
                if row:
                    return Principal(row["name"], row["role"])
            if api_key:
                row = db.execute("SELECT node_id FROM gateways WHERE key_hash=? AND enabled=1", (digest(api_key),)).fetchone()
                if row:
                    return Principal("gateway:" + row[0], "gateway", row[0])
        return None

    def logout(self, session: str | None):
        if session:
            with self.connection() as db:
                db.execute("DELETE FROM sessions WHERE hash=?", (digest(session),))


class RateLimiter:
    """Bounded per-process rolling windows; production runs one durable queue worker."""
    def __init__(self):
        self.windows = OrderedDict()

    def allow(self, key: str, count: int, seconds: int = 60) -> bool:
        now = time.monotonic()
        window = self.windows.setdefault(key, deque())
        self.windows.move_to_end(key)
        while window and window[0] <= now - seconds:
            window.popleft()
        if len(self.windows) > 4096:
            self.windows.popitem(last=False)
        if len(window) >= count:
            return False
        window.append(now)
        return True


def allowed(principal: Principal, method: str, path: str, node_header: str | None) -> bool:
    if path in {"/api/auth/me", "/api/auth/logout"}:
        return principal.role != "gateway"
    if principal.role == "gateway":
        if node_header != principal.node_id:
            return False
        if method == "POST" and path == "/api/reports":
            return True
        if method == "POST" and path == f"/api/gateways/{principal.node_id}/heartbeat":
            return True
        if method == "GET" and path == "/api/receipts":
            return True
        return bool(re.fullmatch(r"/api/reports/[0-9a-f-]{36}/attachments(?:/[0-9a-f-]{36})?", path)) and method in {"GET", "PUT"}
    if principal.role == "viewer":
        return method in {"GET", "HEAD"}
    if principal.role == "admin":
        return True
    if principal.role == "responder":
        return method in {"GET", "HEAD"} or bool(re.fullmatch(r"/api/(?:incidents/[0-9a-f-]{36}(?:/(?:acknowledge|verification))?|correlations/[0-9a-f-]{36}/decision|reports/[0-9a-f-]{36}/(?:reanalyze|attachments/[0-9a-f-]{36}/analyze))", path))
    return False
