from __future__ import annotations

from pathlib import Path
import hashlib

from .ffmpeg_utils import probe_video
from .models import VIDEO_EXTENSIONS, VideoInfo


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def orientation_for(width: int, height: int) -> str:
    if height > width:
        return "vertical"
    if width > height:
        return "horizontal"
    return "square"


def scan_videos(input_dir: Path, ffmpeg: Path, ffprobe: Path | None) -> list[VideoInfo]:
    videos: list[VideoInfo] = []
    files = sorted(
        [path for path in input_dir.rglob("*") if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS],
        key=lambda item: str(item).lower(),
    )
    for index, path in enumerate(files, start=1):
        stat = path.stat()
        digest = sha256_file(path)
        probe = probe_video(path, ffmpeg, ffprobe)
        width = int(probe["width"])
        height = int(probe["height"])
        videos.append(
            VideoInfo(
                path=path,
                video_id=f"V{index:03d}",
                sha256=digest,
                size=stat.st_size,
                mtime_ns=stat.st_mtime_ns,
                duration=float(probe["duration"]),
                width=width,
                height=height,
                fps=float(probe["fps"]),
                codec=str(probe["codec"]),
                orientation=orientation_for(width, height),
            )
        )
    return videos
