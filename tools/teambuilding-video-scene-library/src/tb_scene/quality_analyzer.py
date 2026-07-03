from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from .models import QualityResult, SceneCut, VideoInfo


def analyze_quality(source: VideoInfo, scene: SceneCut, keyframes: list[Path]) -> QualityResult:
    sharpness_values: list[float] = []
    brightness_values: list[float] = []
    black_values: list[float] = []
    over_values: list[float] = []

    for frame in keyframes:
        image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            continue
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        sharpness_values.append(float(cv2.Laplacian(gray, cv2.CV_64F).var()))
        brightness_values.append(float(gray.mean()))
        black_values.append(float((gray < 10).mean()))
        over_values.append(float((gray > 245).mean()))

    sharpness = float(np.mean(sharpness_values)) if sharpness_values else 0.0
    brightness = float(np.mean(brightness_values)) if brightness_values else 0.0
    black_ratio = float(np.mean(black_values)) if black_values else 1.0
    overexposure = float(np.mean(over_values)) if over_values else 0.0

    reasons: list[str] = []
    if scene.duration < 0.35:
        reasons.append("镜头过短")
    if source.width < 480 or source.height < 480:
        reasons.append("分辨率过低")
    if black_ratio > 0.7:
        reasons.append("黑屏比例过高")
    if overexposure > 0.7:
        reasons.append("过曝比例过高")
    if sharpness < 20:
        reasons.append("画面明显模糊")

    if reasons:
        level = "C"
    elif sharpness >= 160 and 35 <= brightness <= 235:
        level = "S"
    elif sharpness >= 80 and 25 <= brightness <= 245:
        level = "A"
    else:
        level = "B"

    return QualityResult(
        quality_level=level,
        quality_reasons=reasons,
        sharpness_average=round(sharpness, 3),
        brightness_average=round(brightness, 3),
        black_ratio=round(black_ratio, 5),
        overexposure_ratio=round(overexposure, 5),
    )
