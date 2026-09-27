from __future__ import annotations

from pathlib import Path
from typing import Any
import os

from .core import parse_post_folder, visible_length
from .store import DraftStore
from .wechat_api import AccountConfig, WechatApiError, WechatNewspicClient


class PublishConflict(RuntimeError):
    def __init__(self, existing: dict[str, Any]) -> None:
        super().__init__("该内容已经创建过草稿")
        self.existing = existing


class ConfigurationError(RuntimeError):
    pass


def account_from_settings(settings: dict[str, Any], account_id: str, *, allow_missing_secret: bool = False) -> AccountConfig:
    accounts = settings.get("accounts") or {}
    raw = accounts.get(account_id)
    if not isinstance(raw, dict):
        if allow_missing_secret:
            return AccountConfig(id=account_id or "dry-run", name="测试模式", app_id="dry-run-app", app_secret="dry-run-secret")
        raise ConfigurationError(f"未找到公众号账号配置：{account_id}")
    app_id = str(raw.get("app_id", "")).strip()
    secret_env = str(raw.get("app_secret_env", "")).strip()
    inline_secret = str(raw.get("app_secret", "")).strip()
    app_secret = os.environ.get(secret_env, "").strip() if secret_env else inline_secret
    if not app_id:
        raise ConfigurationError(f"账号 {account_id} 缺少 app_id")
    if not app_secret:
        if allow_missing_secret:
            app_secret = "dry-run-secret"
        elif secret_env:
            raise ConfigurationError(f"环境变量 {secret_env} 未设置")
        else:
            raise ConfigurationError(f"账号 {account_id} 缺少 app_secret 或 app_secret_env")
    return AccountConfig(
        id=account_id,
        name=str(raw.get("name", account_id)),
        app_id=app_id,
        app_secret=app_secret,
        author=str(raw.get("author", "")),
    )


def publish_folder(
    *,
    folder: Path,
    settings: dict[str, Any],
    store: DraftStore,
    account_id: str,
    title_override: str | None = None,
    body_override: str | None = None,
    force: bool = False,
    dry_run: bool = False,
) -> dict[str, Any]:
    post = parse_post_folder(
        folder,
        title_trigger=int(settings.get("title_trigger", 24)),
        title_target=int(settings.get("title_target", 20)),
        body_soft_limit=int(settings.get("body_soft_limit", 1000)),
        max_images=int(settings.get("max_images", 10)),
    )
    if not post.valid:
        raise ValueError("；".join(post.errors))

    title = (title_override or post.suggested_title or post.original_title).strip()
    body = post.body if body_override is None else body_override.strip()
    if not title:
        raise ValueError("标题不能为空")
    if not body:
        raise ValueError("正文不能为空")

    image_paths = [Path(path) for path in post.images]
    task_hash, image_hashes = store.task_hash(account_id, title, body, image_paths)
    existing = store.find_success(task_hash)
    if existing and not force:
        raise PublishConflict(existing)

    warnings = [item for item in post.warnings if not item.startswith("正文约 ")]
    final_body_length = visible_length(body)
    body_soft_limit = int(settings.get("body_soft_limit", 1000))
    if final_body_length > body_soft_limit:
        warnings.append(f"正文约 {final_body_length} 字，超过 {body_soft_limit} 字软限制；系统不会截断，将尝试保存草稿")
    if dry_run:
        warnings.append("当前为测试模式，未调用微信接口")
    task_id = store.begin_task(
        task_hash=task_hash,
        post_id=post.id,
        folder_path=post.folder,
        account_id=account_id,
        original_title=post.original_title,
        final_title=title,
        body_length=final_body_length,
        image_count=len(image_paths),
        warning_message="；".join(warnings),
    )

    try:
        account = account_from_settings(settings, account_id, allow_missing_secret=dry_run)
        client = WechatNewspicClient(account, dry_run=dry_run)
        media_ids: list[str] = []
        for index, (image_path, image_hash) in enumerate(zip(image_paths, image_hashes), start=1):
            media_id = client.upload_image(image_path)
            media_ids.append(media_id)
            store.record_media(task_id, index, str(image_path), image_hash, media_id)
        draft_media_id = client.create_draft(
            title=title,
            content=body,
            image_media_ids=media_ids,
        )
        store.complete_task(task_id, draft_media_id, warning=bool(warnings))
        return {
            "ok": True,
            "task_id": task_id,
            "status": "DRAFTED_WITH_WARNING" if warnings else "DRAFTED",
            "draft_media_id": draft_media_id,
            "image_media_ids": media_ids,
            "warnings": warnings,
            "title": title,
            "body_length": final_body_length,
            "dry_run": dry_run,
        }
    except WechatApiError as exc:
        store.fail_task(task_id, exc.code, str(exc))
        raise
    except Exception as exc:
        store.fail_task(task_id, type(exc).__name__, str(exc))
        raise
