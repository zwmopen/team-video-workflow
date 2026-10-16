from __future__ import annotations

from pathlib import Path
from typing import Any
import hashlib
import json
import os
import sqlite3
import time


def default_data_dir() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    return base / "ZwmWechatDraftPublisher"


class SettingsStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (default_data_dir() / "settings.json")

    def load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {
                "library_root": "",
                "default_account": "main",
                "title_trigger": 24,
                "title_target": 20,
                "body_soft_limit": 1000,
                "max_images": 10,
                "accounts": {},
            }
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        payload.setdefault("library_root", "")
        payload.setdefault("default_account", "main")
        payload.setdefault("title_trigger", 24)
        payload.setdefault("title_target", 20)
        payload.setdefault("body_soft_limit", 1000)
        payload.setdefault("max_images", 10)
        payload.setdefault("accounts", {})
        return payload

    def save(self, payload: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, self.path)


class DraftStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (default_data_dir() / "draft-history.db")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        return conn

    def _initialize(self) -> None:
        with self.connect() as conn:
            conn.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS draft_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_hash TEXT NOT NULL,
                    post_id TEXT NOT NULL,
                    folder_path TEXT NOT NULL,
                    account_id TEXT NOT NULL,
                    original_title TEXT NOT NULL,
                    final_title TEXT NOT NULL,
                    body_length INTEGER NOT NULL,
                    image_count INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    warning_message TEXT NOT NULL DEFAULT '',
                    error_code TEXT NOT NULL DEFAULT '',
                    error_message TEXT NOT NULL DEFAULT '',
                    draft_media_id TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER
                );
                CREATE INDEX IF NOT EXISTS idx_draft_tasks_post ON draft_tasks(post_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_draft_tasks_hash ON draft_tasks(task_hash, created_at DESC);

                CREATE TABLE IF NOT EXISTS uploaded_media (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER NOT NULL,
                    image_index INTEGER NOT NULL,
                    local_path TEXT NOT NULL,
                    file_sha256 TEXT NOT NULL,
                    wechat_media_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES draft_tasks(id)
                );
                CREATE INDEX IF NOT EXISTS idx_uploaded_media_hash ON uploaded_media(file_sha256, created_at DESC);
                """
            )

    @staticmethod
    def file_sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def task_hash(
        self,
        account_id: str,
        title: str,
        body: str,
        image_paths: list[Path],
    ) -> tuple[str, list[str]]:
        image_hashes = [self.file_sha256(path) for path in image_paths]
        digest = hashlib.sha256()
        for value in [account_id, title, body, *image_hashes]:
            digest.update(value.encode("utf-8", errors="ignore"))
            digest.update(b"\0")
        return digest.hexdigest(), image_hashes

    def find_success(self, task_hash: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT * FROM draft_tasks
                WHERE task_hash = ? AND status IN ('DRAFTED', 'DRAFTED_WITH_WARNING')
                ORDER BY created_at DESC LIMIT 1
                """,
                (task_hash,),
            ).fetchone()
        return dict(row) if row else None

    def begin_task(
        self,
        *,
        task_hash: str,
        post_id: str,
        folder_path: str,
        account_id: str,
        original_title: str,
        final_title: str,
        body_length: int,
        image_count: int,
        warning_message: str,
    ) -> int:
        now = int(time.time())
        with self.connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO draft_tasks (
                    task_hash, post_id, folder_path, account_id, original_title,
                    final_title, body_length, image_count, status, warning_message, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADING_IMAGES', ?, ?)
                """,
                (
                    task_hash,
                    post_id,
                    folder_path,
                    account_id,
                    original_title,
                    final_title,
                    body_length,
                    image_count,
                    warning_message,
                    now,
                ),
            )
            return int(cursor.lastrowid)

    def record_media(
        self,
        task_id: int,
        image_index: int,
        local_path: str,
        file_sha256: str,
        media_id: str,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO uploaded_media (
                    task_id, image_index, local_path, file_sha256, wechat_media_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (task_id, image_index, local_path, file_sha256, media_id, int(time.time())),
            )

    def complete_task(self, task_id: int, media_id: str, warning: bool) -> None:
        status = "DRAFTED_WITH_WARNING" if warning else "DRAFTED"
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE draft_tasks
                SET status = ?, draft_media_id = ?, completed_at = ?
                WHERE id = ?
                """,
                (status, media_id, int(time.time()), task_id),
            )

    def fail_task(self, task_id: int, code: str, message: str) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE draft_tasks
                SET status = 'FAILED_FINAL', error_code = ?, error_message = ?, completed_at = ?
                WHERE id = ?
                """,
                (code, message, int(time.time()), task_id),
            )

    def history(self, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 500))
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM draft_tasks ORDER BY created_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]
