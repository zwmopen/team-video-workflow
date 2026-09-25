from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen
import json
import mimetypes
import socket
import threading
import time
import uuid

API_BASE = "https://api.weixin.qq.com/cgi-bin"
TOKEN_SAFETY_SECONDS = 300


class WechatApiError(RuntimeError):
    def __init__(self, code: str | int, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = str(code)
        self.retryable = retryable


@dataclass(slots=True)
class AccountConfig:
    id: str
    name: str
    app_id: str
    app_secret: str
    author: str = ""


class WechatNewspicClient:
    def __init__(
        self,
        account: AccountConfig,
        *,
        timeout: float = 30,
        retries: int = 2,
        dry_run: bool = False,
    ) -> None:
        self.account = account
        self.timeout = timeout
        self.retries = max(0, retries)
        self.dry_run = dry_run
        self._token = ""
        self._token_expires_at = 0.0
        self._token_lock = threading.Lock()
        self._dry_counter = 0

    def get_access_token(self, force: bool = False) -> str:
        if self.dry_run:
            return "dry-run-token"
        now = time.time()
        if not force and self._token and now < self._token_expires_at:
            return self._token
        with self._token_lock:
            now = time.time()
            if not force and self._token and now < self._token_expires_at:
                return self._token
            data = self._request_json(
                "GET",
                f"{API_BASE}/token",
                params={
                    "grant_type": "client_credential",
                    "appid": self.account.app_id,
                    "secret": self.account.app_secret,
                },
            )
            token = str(data.get("access_token", ""))
            if not token:
                self._raise_api_error(data, "获取 access_token 失败")
            expires_in = int(data.get("expires_in", 7200) or 7200)
            self._token = token
            self._token_expires_at = time.time() + max(60, expires_in - TOKEN_SAFETY_SECONDS)
            return token

    def upload_image(self, image_path: Path) -> str:
        image_path = image_path.resolve(strict=True)
        if self.dry_run:
            self._dry_counter += 1
            return f"dry-run-image-{self._dry_counter}"
        token = self.get_access_token()
        mime = mimetypes.guess_type(str(image_path))[0] or "application/octet-stream"
        data = self._request_json(
            "POST",
            f"{API_BASE}/material/add_material",
            params={"access_token": token, "type": "image"},
            multipart=("media", image_path.name, mime, image_path.read_bytes()),
        )
        media_id = str(data.get("media_id", ""))
        if not media_id:
            self._raise_api_error(data, f"上传图片失败：{image_path.name}")
        return media_id

    @staticmethod
    def build_draft_payload(
        *,
        title: str,
        content: str,
        image_media_ids: list[str],
        author: str = "",
    ) -> dict[str, Any]:
        article: dict[str, Any] = {
            "article_type": "newspic",
            "title": title,
            "content": content,
            "need_open_comment": 0,
            "only_fans_can_comment": 0,
            "image_info": {
                "image_list": [
                    {"image_media_id": media_id}
                    for media_id in image_media_ids
                ]
            },
        }
        if author:
            article["author"] = author
        return {"articles": [article]}

    def create_draft(
        self,
        *,
        title: str,
        content: str,
        image_media_ids: list[str],
    ) -> str:
        if not image_media_ids:
            raise WechatApiError("NO_IMAGES", "至少需要一张图片")
        payload = self.build_draft_payload(
            title=title,
            content=content,
            image_media_ids=image_media_ids,
            author=self.account.author,
        )
        if self.dry_run:
            return "dry-run-draft-media-id"
        token = self.get_access_token()
        data = self._request_json(
            "POST",
            f"{API_BASE}/draft/add",
            params={"access_token": token},
            json_payload=payload,
        )
        media_id = str(data.get("media_id", ""))
        if not media_id:
            self._raise_api_error(data, "创建公众号贴图草稿失败")
        return media_id

    def _request_json(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, str] | None = None,
        json_payload: dict[str, Any] | None = None,
        multipart: tuple[str, str, str, bytes] | None = None,
    ) -> dict[str, Any]:
        if params:
            url = f"{url}?{urlencode(params)}"
        headers: dict[str, str] = {"Accept": "application/json"}
        body: bytes | None = None
        if json_payload is not None:
            body = json.dumps(json_payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        elif multipart is not None:
            field_name, filename, mime, file_bytes = multipart
            boundary = f"----ZwmWechat{uuid.uuid4().hex}"
            fallback = "upload" + (Path(filename).suffix or ".jpg")
            disposition = (
                f'Content-Disposition: form-data; name="{field_name}"; '
                f'filename="{fallback}"; filename*=UTF-8\'\'{quote(filename)}\r\n'
            )
            body = (
                f"--{boundary}\r\n".encode("ascii")
                + disposition.encode("ascii")
                + f"Content-Type: {mime}\r\n\r\n".encode("ascii")
                + file_bytes
                + f"\r\n--{boundary}--\r\n".encode("ascii")
            )
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        request = Request(url, data=body, headers=headers, method=method)

        last_error: BaseException | None = None
        for attempt in range(self.retries + 1):
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    raw = response.read()
                    status = int(getattr(response, "status", 200))
                if status >= 500 and attempt < self.retries:
                    time.sleep(1.5**attempt)
                    continue
                return self._decode_json(raw)
            except HTTPError as exc:
                last_error = exc
                raw = exc.read()
                if exc.code >= 500 and attempt < self.retries:
                    time.sleep(1.5**attempt)
                    continue
                try:
                    data = self._decode_json(raw)
                except WechatApiError:
                    raise WechatApiError(exc.code, f"微信接口 HTTP {exc.code}", retryable=exc.code >= 500) from exc
                self._raise_api_error(data, f"微信接口 HTTP {exc.code}")
            except (URLError, TimeoutError, socket.timeout) as exc:
                last_error = exc
                if attempt < self.retries:
                    time.sleep(1.5**attempt)
                    continue
                raise WechatApiError("NETWORK_ERROR", str(exc), retryable=True) from exc
        raise WechatApiError("NETWORK_ERROR", str(last_error or "请求失败"), retryable=True)

    @staticmethod
    def _decode_json(raw: bytes) -> dict[str, Any]:
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise WechatApiError("INVALID_JSON", "微信接口返回了无法解析的内容") from exc
        if not isinstance(data, dict):
            raise WechatApiError("INVALID_RESPONSE", "微信接口返回结构异常")
        return data

    @staticmethod
    def _raise_api_error(data: dict[str, Any], fallback: str) -> None:
        code = data.get("errcode", "WECHAT_ERROR")
        message = str(data.get("errmsg", fallback) or fallback)
        retryable = str(code) in {"-1", "45009"}
        raise WechatApiError(code, f"{fallback} [{code}]：{message}", retryable=retryable)
