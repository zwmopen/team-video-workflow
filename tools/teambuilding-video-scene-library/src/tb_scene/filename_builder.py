from __future__ import annotations

from .models import Classification, SceneCut, VideoInfo
from .path_utils import sanitize_name


def build_clip_filename(
    location: str,
    quality_level: str,
    classification: Classification,
    source: VideoInfo,
    scene: SceneCut,
    serial: int,
) -> str:
    category = classification.primary_category.split("_", 1)[-1]
    subcategory = classification.subcategory or "未细分"
    parts = [
        quality_level,
        location,
        category,
        subcategory,
        f"{serial:04d}",
    ]
    readable = "_".join(sanitize_name(part) for part in parts)
    return f"{readable}__{source.video_id}_{scene.scene_id}.mp4"
