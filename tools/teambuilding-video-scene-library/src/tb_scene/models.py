from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}


MAIN_CATEGORIES = [
    "01_环境空镜",
    "02_出发抵达",
    "03_住宿空间",
    "04_餐饮美食",
    "05_项目活动",
    "06_团队互动",
    "07_烧烤露营夜场",
    "08_人物反应",
    "09_细节特写",
    "10_收尾返程",
    "90_待人工分类",
]


@dataclass(slots=True)
class VideoInfo:
    path: Path
    video_id: str
    sha256: str
    size: int
    mtime_ns: int
    duration: float
    width: int
    height: int
    fps: float
    codec: str
    orientation: str
    status: str = "pending"
    skip_reason: str = ""


@dataclass(slots=True)
class SceneCut:
    scene_id: str
    start_time: float
    end_time: float
    start_frame: int = 0
    end_frame: int = 0

    @property
    def duration(self) -> float:
        return max(0.0, self.end_time - self.start_time)


@dataclass(slots=True)
class Classification:
    primary_category: str
    subcategory: str = ""
    confidence: float = 0.0
    method: str = "metadata_keyword"
    semantic_tags: list[str] = field(default_factory=list)
    usage_tags: list[str] = field(default_factory=list)
    review_status: str = "auto"


@dataclass(slots=True)
class QualityResult:
    quality_level: str
    quality_reasons: list[str]
    sharpness_average: float
    brightness_average: float
    black_ratio: float
    overexposure_ratio: float


@dataclass(slots=True)
class ClipRecord:
    location: str
    source: VideoInfo
    scene: SceneCut
    classification: Classification
    quality: QualityResult
    output_path: Path | None
    keyframes: list[Path]
    processing_status: str
    skip_reason: str = ""
    extra: dict[str, Any] = field(default_factory=dict)
