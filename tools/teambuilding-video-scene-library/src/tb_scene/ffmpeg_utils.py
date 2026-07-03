from __future__ import annotations

from pathlib import Path
import json
import re
import shutil
import subprocess


def find_executable(name: str, extra_candidates: list[Path] | None = None) -> Path | None:
    found = shutil.which(name)
    if found:
        return Path(found)
    for candidate in extra_candidates or []:
        if candidate.exists():
            return candidate
    return None


def find_ffmpeg() -> Path:
    candidates = [
        Path(r"C:\ffmpeg\bin\ffmpeg.exe"),
        Path(r"D:\ffmpeg\bin\ffmpeg.exe"),
        Path(r"D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffmpeg.exe"),
    ]
    ffmpeg = find_executable("ffmpeg", candidates)
    if not ffmpeg:
        raise FileNotFoundError("FFmpeg was not found. Install it or add ffmpeg.exe to PATH.")
    return ffmpeg


def find_ffprobe() -> Path | None:
    candidates = [
        Path(r"C:\ffmpeg\bin\ffprobe.exe"),
        Path(r"D:\ffmpeg\bin\ffprobe.exe"),
        Path(r"D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffprobe.exe"),
    ]
    return find_executable("ffprobe", candidates)


def run_command(args: list[str | Path], timeout: int | None = None) -> subprocess.CompletedProcess[str]:
    str_args = [str(arg) for arg in args]
    return subprocess.run(
        str_args,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def probe_video(path: Path, ffmpeg: Path, ffprobe: Path | None = None) -> dict[str, float | int | str]:
    if ffprobe:
        result = run_command(
            [
                ffprobe,
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=codec_name,width,height,r_frame_rate,duration",
                "-show_entries",
                "format=duration",
                "-of",
                "json",
                path,
            ],
            timeout=60,
        )
        if result.returncode == 0:
            data = json.loads(result.stdout or "{}")
            streams = data.get("streams") or []
            stream = streams[0] if streams else {}
            fmt = data.get("format") or {}
            duration = float(stream.get("duration") or fmt.get("duration") or 0.0)
            fps = parse_fps(str(stream.get("r_frame_rate") or "0/1"))
            return {
                "duration": duration,
                "width": int(stream.get("width") or 0),
                "height": int(stream.get("height") or 0),
                "fps": fps,
                "codec": str(stream.get("codec_name") or ""),
            }

    result = run_command([ffmpeg, "-hide_banner", "-i", path], timeout=60)
    text = f"{result.stdout}\n{result.stderr}"
    return parse_ffmpeg_probe_text(text)


def parse_fps(value: str) -> float:
    if "/" in value:
        left, right = value.split("/", 1)
        try:
            denominator = float(right)
            return float(left) / denominator if denominator else 0.0
        except ValueError:
            return 0.0
    try:
        return float(value)
    except ValueError:
        return 0.0


def parse_duration(value: str) -> float:
    hour, minute, second = value.split(":")
    return int(hour) * 3600 + int(minute) * 60 + float(second)


def parse_ffmpeg_probe_text(text: str) -> dict[str, float | int | str]:
    duration = 0.0
    duration_match = re.search(r"Duration:\s*(\d+:\d+:\d+(?:\.\d+)?)", text)
    if duration_match:
        duration = parse_duration(duration_match.group(1))

    video_line = ""
    for line in text.splitlines():
        if " Video: " in line:
            video_line = line
            break

    codec = ""
    codec_match = re.search(r"Video:\s*([^,\s]+)", video_line)
    if codec_match:
        codec = codec_match.group(1)

    width = 0
    height = 0
    size_match = re.search(r"(?<![x\d])(\d{3,5})x(\d{3,5})(?![x\d])", video_line)
    if size_match:
        width = int(size_match.group(1))
        height = int(size_match.group(2))

    fps = 0.0
    fps_match = re.search(r"(\d+(?:\.\d+)?)\s*fps", video_line)
    if fps_match:
        fps = float(fps_match.group(1))

    return {
        "duration": duration,
        "width": width,
        "height": height,
        "fps": fps,
        "codec": codec,
    }
