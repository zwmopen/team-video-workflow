from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import csv
import json
import shutil

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command
from .models import VIDEO_EXTENSIONS
from .path_utils import ensure_unique_path, sanitize_name, seconds_to_timestamp


@dataclass(slots=True)
class AudioLibraryOptions:
    source_dir: Path
    output: Path | None = None
    transcribe: bool = False
    model: str = "tiny"
    language: str = "zh"
    max_videos: int | None = None
    force: bool = False


def build_audio_library(options: AudioLibraryOptions) -> dict[str, object]:
    source_dir = options.source_dir.expanduser().resolve()
    if not source_dir.exists():
        raise FileNotFoundError(f"Source video folder does not exist: {source_dir}")
    output = options.output or default_audio_library_output(source_dir)
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    videos = [path for path in sorted(source_dir.rglob("*")) if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS]
    if options.max_videos:
        videos = videos[: options.max_videos]

    model = None
    if options.transcribe:
        import whisper

        model = whisper.load_model(options.model)

    rows: list[dict[str, object]] = []
    extracted = 0
    skipped = 0
    failed: list[dict[str, str]] = []
    for index, video in enumerate(videos, start=1):
        safe_stem = sanitize_name(video.stem, fallback=f"audio_{index:03d}")
        audio_path = output / f"{index:03d}_{safe_stem}.m4a"
        transcript_path = output / f"{index:03d}_{safe_stem}.txt"
        try:
            if audio_path.exists() and transcript_path.exists() and not options.force:
                skipped += 1
            else:
                extract_audio(video, audio_path, ffmpeg, force=options.force)
                write_or_copy_transcript(video, transcript_path, model, options.language)
                extracted += 1
            info = probe_video(video, ffmpeg, ffprobe)
            rows.append(
                {
                    "index": index,
                    "source_video": str(video),
                    "audio_path": str(audio_path),
                    "transcript_path": str(transcript_path),
                    "duration": round(float(info.get("duration") or 0.0), 3),
                    "width": int(info.get("width") or 0),
                    "height": int(info.get("height") or 0),
                    "status": "ready" if audio_path.exists() and transcript_path.exists() else "failed",
                }
            )
        except Exception as exc:
            failed.append({"source_video": str(video), "error": str(exc)})

    manifest_csv = output / "音频素材库清单.csv"
    manifest_json = output / "音频素材库清单.json"
    write_audio_manifest_csv(manifest_csv, rows)
    manifest_json.write_text(
        json.dumps(
            {
                "source_dir": str(source_dir),
                "output": str(output),
                "items": rows,
                "failed": failed,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return {
        "source_dir": str(source_dir),
        "output": str(output),
        "videos": len(videos),
        "extracted": extracted,
        "skipped": skipped,
        "failed": failed,
        "manifest_csv": str(manifest_csv),
        "manifest_json": str(manifest_json),
    }


def default_audio_library_output(source_dir: Path) -> Path:
    name = source_dir.name
    for suffix in ("-原视频素材", "原视频素材"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    name = name.strip("-_ ") or source_dir.name
    return source_dir.parent / f"{name}音频素材库"


def extract_audio(video: Path, audio_path: Path, ffmpeg: Path, force: bool = False) -> None:
    audio_path.parent.mkdir(parents=True, exist_ok=True)
    if audio_path.exists() and not force:
        return
    temp = ensure_unique_path(audio_path.with_suffix(".tmp.m4a"))
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            video,
            "-vn",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            temp,
        ],
        timeout=300,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        raise RuntimeError(f"Failed to extract audio: {result.stderr[-800:]}")
    if audio_path.exists():
        audio_path.unlink()
    temp.replace(audio_path)


def write_or_copy_transcript(video: Path, transcript_path: Path, model: object | None, language: str) -> None:
    transcript_path.parent.mkdir(parents=True, exist_ok=True)
    sidecar = video.with_suffix(".txt")
    if sidecar.exists():
        shutil.copy2(sidecar, transcript_path)
        return
    if model is not None:
        result = model.transcribe(str(video), language=language, fp16=False, verbose=False)  # type: ignore[attr-defined]
        transcript_path.write_text(format_whisper_segments(result), encoding="utf-8")
        return
    transcript_path.write_text(
        "\n".join(
            [
                "# 待转写",
                f"# source_video: {video}",
                "# 后续用 whisper/语音转文字生成带时间戳台词，格式建议：",
                "# 00:00.000 --> 00:03.000 台词内容",
            ]
        ),
        encoding="utf-8",
    )


def format_whisper_segments(result: dict[str, object]) -> str:
    lines: list[str] = []
    for segment in result.get("segments", []):  # type: ignore[union-attr]
        if not isinstance(segment, dict):
            continue
        start = float(segment.get("start") or 0.0)
        end = float(segment.get("end") or start)
        text = str(segment.get("text") or "").strip()
        if text:
            lines.append(f"{seconds_to_timestamp(start)} --> {seconds_to_timestamp(end)} {text}")
    if lines:
        return "\n".join(lines)
    return str(result.get("text") or "").strip()  # type: ignore[union-attr]


def write_audio_manifest_csv(path: Path, rows: list[dict[str, object]]) -> None:
    fields = ["index", "source_video", "audio_path", "transcript_path", "duration", "width", "height", "status"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
