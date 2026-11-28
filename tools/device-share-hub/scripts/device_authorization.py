"""Device authorization registry for the staged secure gallery transport.

No trust is inferred from a network address or a caller-supplied device ID.
Tokens are generated on clients, transmitted only over authenticated TLS, and
stored on the PC as SHA-256 digests. Local desktop approval is out of band.
"""
import hashlib
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

ROLES = ("read", "operate", "manage")
ROLE_LEVEL = {role: i for i, role in enumerate(ROLES)}
PAIR_TTL = 600
MAX_PENDING = 32
TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{43}\Z")

# Explicit allowlist: newly introduced routes are denied until reviewed.
ROUTE_ROLES = {
    ("GET", "/api/online/categories"): "read",
    ("GET", "/api/online/filter-options"): "read",
    ("GET", "/api/online/works"): "read",
    ("GET", "/api/online/image"): "read",
    ("GET", "/api/online/video"): "read",
    ("GET", "/api/online/live-video"): "read",
    ("POST", "/api/online/use-work"): "operate",
    ("POST", "/api/online/star-work"): "operate",
    ("POST", "/api/online/update-copy"): "manage",
    ("GET", "/api/online/recycle"): "manage",
    ("POST", "/api/online/restore"): "manage",
}
# Destructive operations, clipboard, cloud enrollment, admin and arbitrary
# new routes remain blocked until their separate end-to-end review.

def default_db():
    return Path.home() / ".device-share-hub" / "device-authorization.sqlite3"

def new_token():
    return secrets.token_urlsafe(32)

def digest(token):
    if not isinstance(token, str) or not TOKEN_RE.fullmatch(token):
        raise ValueError("invalid device credential")
    return hashlib.sha256(token.encode("ascii")).hexdigest()

def fingerprint(device_id):
    return " ".join(device_id[i:i+4].upper() for i in range(0, 16, 4))

class Registry:
    def __init__(self, path=None):
        self.path = Path(path or default_db())
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, name TEXT NOT NULL,
                state TEXT NOT NULL, role TEXT,
                created INTEGER NOT NULL, updated INTEGER NOT NULL
            )""")
            db.execute("""CREATE TABLE IF NOT EXISTS audit (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
                device_id TEXT NOT NULL, action TEXT NOT NULL, outcome TEXT NOT NULL
            )""")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(str(self.path), timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _record(db, device_id, action, outcome):
        db.execute("INSERT INTO audit(at,device_id,action,outcome) VALUES(?,?,?,?)",
                   (int(time.time()), device_id, action, outcome))
        db.execute("DELETE FROM audit WHERE seq <= (SELECT MAX(seq)-10000 FROM audit)")

    def request(self, token, name):
        device_id = digest(token)
        if not isinstance(name, str) or not name.strip() or len(name) > 80:
            raise ValueError("invalid device name")
        now = int(time.time())
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM devices WHERE state='pending' AND updated < ?", (now-PAIR_TTL,))
            row = db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
            if row is None:
                count = db.execute("SELECT COUNT(*) FROM devices WHERE state='pending'").fetchone()[0]
                if count >= MAX_PENDING:
                    raise ValueError("pairing queue full")
                db.execute("INSERT INTO devices VALUES(?,?, 'pending', NULL,?,?)",
                           (device_id, name.strip(), now, now))
                self._record(db, device_id, "request", "pending")
        return self.status(token)

    def status(self, token):
        device_id = digest(token)
        with self._db() as db:
            row = db.execute("SELECT state,role,updated FROM devices WHERE id=?", (device_id,)).fetchone()
        if row is None or (row["state"] == "pending" and row["updated"] < time.time()-PAIR_TTL):
            return {"state": "unknown", "fingerprint": fingerprint(device_id)}
        return {"state": row["state"], "role": row["role"], "fingerprint": fingerprint(device_id)}

    def list(self):
        with self._db() as db:
            rows = db.execute("SELECT * FROM devices WHERE state!='pending' OR updated>=? ORDER BY updated DESC",
                              (int(time.time())-PAIR_TTL,)).fetchall()
        return [dict(row) for row in rows]

    def decide(self, device_id, state, role=None):
        if state not in ("approved", "revoked") or (state == "approved" and role not in ROLES):
            raise ValueError("invalid decision")
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state,updated FROM devices WHERE id=?", (device_id,)).fetchone()
            if row is None or row["state"] == "revoked":
                return False
            if row["state"] == "pending" and row["updated"] < time.time()-PAIR_TTL:
                return False
            if state == "approved" and row["state"] not in ("pending", "approved"):
                return False
            db.execute("UPDATE devices SET state=?,role=?,updated=? WHERE id=?",
                       (state, role if state == "approved" else None, int(time.time()), device_id))
            self._record(db, device_id, "decision", role if state=="approved" else state)
        return True

    def authorize(self, token, method, path):
        required = ROUTE_ROLES.get((method, path))
        if required is None:
            return False
        try:
            status = self.status(token)
        except ValueError:
            return False
        role = status.get("role")
        return (status.get("state") == "approved" and role in ROLE_LEVEL
                and ROLE_LEVEL[role] >= ROLE_LEVEL[required])
