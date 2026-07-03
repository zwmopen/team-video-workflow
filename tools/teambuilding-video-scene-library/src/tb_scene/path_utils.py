from __future__ import annotations

from pathlib import Path
import re


WINDOWS_BAD_CHARS = r'\/:*?"<>|'


def sanitize_name(value: str, fallback: str = "clip") -> str:
    cleaned = "".join("_" if ch in WINDOWS_BAD_CHARS else ch for ch in value)
    cleaned = re.sub(r"\s+", "_", cleaned).strip(" ._")
    cleaned = re.sub(r"_+", "_", cleaned)
    return cleaned or fallback


def output_root_for(input_dir: Path, explicit_output: str | None = None) -> Path:
    if explicit_output:
        return Path(explicit_output).expanduser().resolve()
    return input_dir.parent / f"{input_dir.name}智能镜头分类"


def ensure_unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    stem = path.stem
    suffix = path.suffix
    parent = path.parent
    for index in range(2, 10000):
        candidate = parent / f"{stem}_{index:02d}{suffix}"
        if not candidate.exists():
            return candidate
    raise RuntimeError(f"Cannot allocate a unique file name under {parent}")


def seconds_to_timestamp(seconds: float) -> str:
    total_ms = int(round(seconds * 1000))
    ms = total_ms % 1000
    total_seconds = total_ms // 1000
    sec = total_seconds % 60
    total_minutes = total_seconds // 60
    minute = total_minutes % 60
    hour = total_minutes // 60
    return f"{hour:02d}:{minute:02d}:{sec:02d}.{ms:03d}"
