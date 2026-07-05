from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError


AUDIO_EXTENSIONS = {".m4a", ".mp3", ".wav", ".ogg"}
SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Transcribe local audio files with Volcengine SeedASR AUC.")
    parser.add_argument("audio_root", type=Path)
    parser.add_argument("--cloudflared", type=Path, required=True)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--api-key-env", default="VOLC_ASR_API_KEY")
    parser.add_argument("--app-id-env", default="VOLC_ASR_APP_ID")
    parser.add_argument("--access-token-env", default="VOLC_ASR_ACCESS_TOKEN")
    parser.add_argument("--resource-id", default="volc.seedasr.auc")
    parser.add_argument("--language", default="zh-CN")
    parser.add_argument("--max-files", type=int)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--rewrite-existing-json", action="store_true")
    parser.add_argument("--port", type=int, default=8789)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--poll-interval", type=float, default=2.5)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    audio_root = args.audio_root.expanduser().resolve()
    if not audio_root.exists():
        raise SystemExit(f"Audio root not found: {audio_root}")

    if args.rewrite_existing_json:
        summary = rewrite_existing_asr_json(audio_root)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    api_key = os.environ.get(args.api_key_env, "").strip()
    app_id = os.environ.get(args.app_id_env, "").strip()
    access_token = os.environ.get(args.access_token_env, "").strip()
    if not api_key and not (app_id and access_token):
        raise SystemExit(f"Missing {args.api_key_env}, or both {args.app_id_env} and {args.access_token_env}")

    cache_root = audio_root.parent / "._volc_asr_upload_cache"
    cache_root.mkdir(parents=True, exist_ok=True)
    audios = [p for p in sorted(audio_root.rglob("*")) if p.is_file() and p.suffix.lower() in AUDIO_EXTENSIONS]
    if args.max_files:
        audios = audios[: args.max_files]

    httpd, http_thread = start_http_server(cache_root, args.port)
    cloudflared = start_cloudflared(args.cloudflared, args.port)
    try:
        public_url = wait_for_tunnel_url(cloudflared)
        summary = run_batch(args, audios, cache_root, public_url, api_key, app_id, access_token)
    finally:
        cloudflared.terminate()
        try:
            cloudflared.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cloudflared.kill()
        httpd.shutdown()
        http_thread.join(timeout=5)

    summary_path = audio_root / "volc_asr_summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def rewrite_existing_asr_json(audio_root: Path) -> dict[str, object]:
    rows: list[dict[str, object]] = []
    done = failed = 0
    for json_path in sorted(audio_root.rglob("*.asr.json")):
        base = json_path.name[: -len(".asr.json")]
        transcript_path = json_path.with_name(base + ".transcript.txt")
        plain_path = json_path.with_name(base + ".plain.txt")
        try:
            payload = json.loads(json_path.read_text(encoding="utf-8", errors="replace"))
            write_sidecars(payload, transcript_path, plain_path, json_path)
            done += 1
            rows.append({"json": str(json_path), "status": "rewritten"})
        except Exception as exc:
            failed += 1
            rows.append({"json": str(json_path), "status": "failed", "error": str(exc)})
    return {"audio_root": str(audio_root), "done": done, "failed": failed, "items": rows}


def start_http_server(root: Path, port: int) -> tuple[socketserver.TCPServer, threading.Thread]:
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(root), **kwargs)

        def log_message(self, format: str, *args: object) -> None:
            return

    socketserver.TCPServer.allow_reuse_address = True
    httpd = socketserver.TCPServer(("127.0.0.1", port), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd, thread


def start_cloudflared(exe: Path, port: int) -> subprocess.Popen[str]:
    if not exe.exists():
        raise FileNotFoundError(f"cloudflared not found: {exe}")
    return subprocess.Popen(
        [str(exe), "tunnel", "--url", f"http://127.0.0.1:{port}", "--no-autoupdate"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def wait_for_tunnel_url(proc: subprocess.Popen[str], timeout: int = 45) -> str:
    deadline = time.time() + timeout
    pattern = re.compile(r"https://[a-zA-Z0-9.-]+\.trycloudflare\.com")
    lines: list[str] = []
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("cloudflared exited before tunnel URL was ready: " + "\n".join(lines[-20:]))
        line = proc.stdout.readline() if proc.stdout else ""
        if not line:
            time.sleep(0.2)
            continue
        lines.append(line.rstrip())
        match = pattern.search(line)
        if match:
            return match.group(0).rstrip("/")
    raise TimeoutError("Timed out waiting for cloudflared tunnel URL: " + "\n".join(lines[-20:]))


def run_batch(
    args: argparse.Namespace,
    audios: list[Path],
    cache_root: Path,
    public_url: str,
    api_key: str,
    app_id: str,
    access_token: str,
) -> dict[str, object]:
    rows: list[dict[str, object]] = []
    done = skipped = failed = 0
    for audio in audios:
        transcript_path = audio.with_suffix(".transcript.txt")
        plain_path = audio.with_suffix(".plain.txt")
        json_path = audio.with_suffix(".asr.json")
        if transcript_path.exists() and plain_path.exists() and not args.force:
            skipped += 1
            rows.append({"audio": str(audio), "status": "skipped", "transcript": str(transcript_path)})
            continue
        try:
            upload_file = prepare_upload_file(audio, cache_root, args.ffmpeg)
            audio_url = f"{public_url}/{upload_file.name}"
            result = transcribe_one(
                audio_url,
                api_key,
                app_id,
                access_token,
                args.resource_id,
                args.language,
                args.timeout,
                args.poll_interval,
            )
            write_sidecars(result, transcript_path, plain_path, json_path)
            done += 1
            rows.append({"audio": str(audio), "status": "done", "transcript": str(transcript_path), "plain": str(plain_path)})
        except Exception as exc:
            failed += 1
            rows.append({"audio": str(audio), "status": "failed", "error": str(exc)})
    return {"audio_root": str(args.audio_root), "total": len(audios), "done": done, "skipped": skipped, "failed": failed, "items": rows}


def prepare_upload_file(audio: Path, cache_root: Path, ffmpeg: str) -> Path:
    digest = hashlib.sha1(str(audio.resolve()).encode("utf-8")).hexdigest()[:12]
    output = cache_root / f"{digest}.mp3"
    if output.exists() and output.stat().st_size > 0:
        return output
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-i",
            str(audio),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-b:a",
            "64k",
            str(output),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=True,
        timeout=180,
    )
    if not output.exists() or output.stat().st_size <= 0:
        raise RuntimeError(f"ffmpeg did not create upload audio: {audio}")
    return output


def transcribe_one(
    audio_url: str,
    api_key: str,
    app_id: str,
    access_token: str,
    resource_id: str,
    language: str,
    timeout: int,
    poll_interval: float,
) -> dict[str, object]:
    task_id = str(uuid.uuid4())
    headers = {
        "Content-Type": "application/json",
        "X-Api-Resource-Id": resource_id,
        "X-Api-Request-Id": task_id,
        "X-Api-Sequence": "-1",
    }
    if api_key:
        headers["X-Api-Key"] = api_key
    else:
        headers["X-Api-App-Key"] = app_id
        headers["X-Api-Access-Key"] = access_token
    payload = {
        "user": {"uid": "codex-local-video-workflow"},
        "audio": {"url": audio_url, "format": "mp3", "language": language},
        "request": {
            "model_name": "bigmodel",
            "enable_itn": True,
            "enable_punc": True,
            "enable_ddc": True,
            "show_utterances": True,
            "vad_segment": True,
        },
    }
    submit_body, submit_headers = post_json(SUBMIT_URL, headers, payload)
    submit_code = submit_headers.get("X-Api-Status-Code", "")
    if submit_code not in {"20000000", "20000001", "20000002", ""}:
        raise RuntimeError(f"submit failed: {submit_code} {submit_headers.get('X-Api-Message', '')} {submit_body}")

    query_headers = dict(headers)
    query_headers.pop("X-Api-Sequence", None)
    deadline = time.time() + timeout
    while time.time() < deadline:
        body, result_headers = post_json(QUERY_URL, query_headers, {})
        status_code = result_headers.get("X-Api-Status-Code", "")
        if status_code == "20000000" and body.get("result"):
            body["_task_id"] = task_id
            body["_status_code"] = status_code
            return body
        if status_code not in {"20000001", "20000002"}:
            raise RuntimeError(f"query failed: {status_code} {result_headers.get('X-Api-Message', '')} {body}")
        time.sleep(poll_interval)
    raise TimeoutError(f"ASR query timed out for task {task_id}")


def post_json(url: str, headers: dict[str, str], payload: dict[str, object]) -> tuple[dict[str, object], dict[str, str]]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urlrequest.Request(url, data=data, headers=headers, method="POST")
    try:
        with urlrequest.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            response_headers = {k: v for k, v in resp.headers.items()}
    except HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        response_headers = {k: v for k, v in exc.headers.items()}
    except URLError as exc:
        raise RuntimeError(f"network error: {exc}") from exc
    try:
        body = json.loads(text) if text.strip() else {}
    except json.JSONDecodeError:
        body = {"raw": text}
    return body, response_headers


def write_sidecars(result: dict[str, object], transcript_path: Path, plain_path: Path, json_path: Path) -> None:
    transcript_path.parent.mkdir(parents=True, exist_ok=True)
    payload = result.get("result") or {}
    if isinstance(payload, list):
        payload = payload[0] if payload else {}
    if not isinstance(payload, dict):
        payload = {}
    text = fix_mojibake(str(payload.get("text") or "").strip())
    utterances = payload.get("utterances") or []
    lines: list[str] = []
    if isinstance(utterances, list):
        for item in utterances:
            if not isinstance(item, dict):
                continue
            start = int(item.get("start_time") or 0) / 1000.0
            end = int(item.get("end_time") or 0) / 1000.0
            utterance_text = fix_mojibake(str(item.get("text") or "").strip())
            if utterance_text:
                lines.append(f"{format_time(start)} --> {format_time(end)} {utterance_text}")
    if not lines and text:
        lines.append(f"00:00.000 --> 00:00.000 {text}")
    transcript_path.write_text("\n".join(lines), encoding="utf-8")
    plain_path.write_text(text or "\n".join(line.split(" ", 3)[-1] for line in lines), encoding="utf-8")
    json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def fix_mojibake(text: str) -> str:
    if not text:
        return text
    suspicious_markers = ("鍗", "婀", "滐", "紝", "銆", "槸", "涓", "栧")
    if sum(text.count(marker) for marker in suspicious_markers) < 2:
        return text
    for encoding in ("gbk", "cp936"):
        try:
            repaired = text.encode(encoding).decode("utf-8")
        except UnicodeError:
            continue
        if repaired and repaired != text and score_mojibake(repaired) < score_mojibake(text):
            return repaired
    return text


def score_mojibake(text: str) -> int:
    markers = ("鍗", "婀", "滐", "紝", "銆", "槸", "涓", "栧", "鐨", "绋", "寤")
    return sum(text.count(marker) for marker in markers)


def format_time(seconds: float) -> str:
    millis = int(round(seconds * 1000))
    h, rem = divmod(millis, 3600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


if __name__ == "__main__":
    raise SystemExit(main())
