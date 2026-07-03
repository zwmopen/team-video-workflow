from __future__ import annotations

from pathlib import Path

from .models import SceneCut


def detect_scenes(
    path: Path,
    duration: float,
    detector: str,
    threshold: float | None,
    min_scene_len: int,
) -> list[SceneCut]:
    try:
        from scenedetect import SceneManager, open_video
        from scenedetect.detectors import AdaptiveDetector, ContentDetector

        video = open_video(str(path))
        manager = SceneManager()
        if detector == "content":
            manager.add_detector(ContentDetector(threshold=threshold or 27.0, min_scene_len=min_scene_len))
        elif detector == "adaptive":
            manager.add_detector(AdaptiveDetector(adaptive_threshold=threshold or 3.0, min_scene_len=min_scene_len))
        else:
            raise ValueError("transnet detector is reserved for a later version")
        manager.detect_scenes(video=video, show_progress=False)
        scenes = manager.get_scene_list()
        cuts = [
            SceneCut(
                scene_id=f"S{index:03d}",
                start_time=start.get_seconds(),
                end_time=end.get_seconds(),
                start_frame=start.get_frames(),
                end_frame=end.get_frames(),
            )
            for index, (start, end) in enumerate(scenes, start=1)
            if end.get_seconds() > start.get_seconds()
        ]
        return cuts or [SceneCut(scene_id="S001", start_time=0.0, end_time=duration)]
    except Exception:
        return [SceneCut(scene_id="S001", start_time=0.0, end_time=duration)]
