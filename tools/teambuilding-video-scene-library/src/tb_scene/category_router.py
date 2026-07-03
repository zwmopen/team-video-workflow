from __future__ import annotations

from pathlib import Path
import re

from .models import Classification


def load_sidecar_text(video_path: Path) -> str:
    candidates = [video_path.with_suffix(".txt")]
    if " - 副本" in video_path.stem:
        candidates.append(video_path.with_name(video_path.stem.replace(" - 副本", "") + ".txt"))
    for candidate in candidates:
        if candidate.exists():
            try:
                return candidate.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                return ""
    return ""


def classify_from_metadata(video_path: Path) -> Classification:
    text = f"{video_path.stem}\n{load_sidecar_text(video_path)}".lower()

    rules: list[tuple[str, str, str, float, list[str]]] = [
        (r"皮划艇|划艇|kayak", "05_项目活动", "皮划艇", 0.74, ["水上项目"]),
        (r"游艇|游船|游湖|坐船", "05_项目活动", "游艇游湖", 0.72, ["水上项目"]),
        (r"桨板|浆板|paddle", "05_项目活动", "桨板", 0.72, ["水上项目"]),
        (r"漂流", "05_项目活动", "漂流", 0.74, ["水上项目", "高潮镜头"]),
        (r"玩水|水上|水花", "05_项目活动", "玩水互动", 0.66, ["水上项目"]),
        (r"骑行|环湖骑", "05_项目活动", "湖边骑行", 0.7, ["户外项目"]),
        (r"真人cs|镭战|水弹", "05_项目活动", "真人CS", 0.72, ["团建项目"]),
        (r"烧烤|篝火|烟花|露营|天幕", "07_烧烤露营夜场", "", 0.66, ["夜场氛围"]),
        (r"民宿|酒店|住宿|别墅|房间|湖景房", "03_住宿空间", "", 0.7, ["住宿"]),
        (r"鱼头|鱼宴|吃|菜品|聚餐|碰杯|餐饮|美食", "04_餐饮美食", "", 0.68, ["餐饮"]),
        (r"大巴|上车|下车|出发|抵达|集合", "02_出发抵达", "", 0.68, ["交通"]),
        (r"飞盘|拓展|挑战|分组|草坪游戏|团建游戏", "06_团队互动", "", 0.66, ["团队互动"]),
        (r"航拍|湖景|湖面|山水|风景|日出|日落|岛屿", "01_环境空镜", "", 0.64, ["转场素材"]),
        (r"合照|欢呼|笑脸|击掌|比耶|挥手", "08_人物反应", "", 0.62, ["人物氛围"]),
        (r"返程|告别|结束", "10_收尾返程", "", 0.62, ["收尾"]),
    ]

    for pattern, category, subcategory, confidence, tags in rules:
        if re.search(pattern, text):
            return Classification(
                primary_category=category,
                subcategory=subcategory,
                confidence=confidence,
                semantic_tags=tags,
                review_status="auto_keyword",
            )

    return Classification(
        primary_category="90_待人工分类",
        subcategory="",
        confidence=0.0,
        semantic_tags=[],
        review_status="needs_review",
    )
