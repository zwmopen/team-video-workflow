from __future__ import annotations

from pathlib import Path

from .ffmpeg_utils import run_command
from .models import SceneCut, VideoInfo


def split_scene(
    ffmpeg: Path,
    source: VideoInfo,
    scene: SceneCut,
    output_path: Path,
    split_mode: str,
    crf: int,
    preset: str,
) -> tuple[bool, str]:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    duration = max(0.01, scene.duration)
    if split_mode == "copy":
        args = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{scene.start_time:.3f}",
            "-i",
            source.path,
            "-t",
            f"{duration:.3f}",
            "-an",
            "-c:v",
            "copy",
            "-avoid_negative_ts",
            "make_zero",
            output_path,
        ]
    else:
        args = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{scene.start_time:.3f}",
            "-i",
            source.path,
            "-t",
            f"{duration:.3f}",
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            preset,
            "-crf",
            str(crf),
            "-pix_fmt",
            "yuv420p",
            output_path,
        ]
    result = run_command(args, timeout=300)
    if result.returncode != 0:
        return False, (result.stderr or result.stdout or "ffmpeg split failed").strip()
    return True, ""
