from __future__ import annotations

from pathlib import Path

from .ffmpeg_utils import run_command
from .models import SceneCut


def extract_keyframes(ffmpeg: Path, clip_path: Path, scene: SceneCut, keyframe_dir: Path) -> list[Path]:
    keyframe_dir.mkdir(parents=True, exist_ok=True)
    outputs: list[Path] = []
    for label, ratio in [("20", 0.2), ("50", 0.5), ("80", 0.8)]:
        timestamp = max(0.0, scene.duration * ratio)
        output = keyframe_dir / f"{clip_path.stem}_{label}.jpg"
        result = run_command(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                f"{timestamp:.3f}",
                "-i",
                clip_path,
                "-frames:v",
                "1",
                "-q:v",
                "2",
                output,
            ],
            timeout=120,
        )
        if result.returncode == 0 and output.exists():
            outputs.append(output)
    return outputs
