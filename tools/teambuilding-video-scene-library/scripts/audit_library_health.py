from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}
AUDIO_EXTENSIONS = {".m4a", ".mp3", ".wav", ".aac", ".flac"}
GENERATED_MARKERS = {
    "裁切废料",
    "裁去字幕",
    "字幕之上",
    "手动处理",
    "裁切废料测试",
    "_check",
    "废料",
    "归档",
}
SCENE_KEY_PATTERN = re.compile(r"(?:^|_)([A-Za-z]*V\d+_S\d+)(?:_|\.)", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit the team-building video material library health.")
    parser.add_argument(
        "--library-root",
        default=r"D:\Download\素材下载\团建视频",
        help="Canonical material library root.",
    )
    parser.add_argument("--report-date", default=datetime.now().strftime("%Y%m%d"))
    return parser.parse_args()


def is_generated_or_system(path: Path) -> bool:
    return any(part in GENERATED_MARKERS or part.startswith("._") for part in path.parts)


def location_from_source_dir(path: Path) -> str:
    name = path.name
    if "-" in name:
        return name.split("-", 1)[0]
    return name


def list_files(root: Path, extensions: set[str]) -> list[Path]:
    if not root.exists():
        return []
    return [
        path
        for path in sorted(root.rglob("*"), key=lambda item: str(item).lower())
        if path.is_file() and path.suffix.lower() in extensions
    ]


def read_manifests(scene_root: Path) -> dict[str, list[dict[str, str]]]:
    by_source: dict[str, list[dict[str, str]]] = defaultdict(list)
    for manifest in scene_root.rglob("*manifest.csv"):
        try:
            with manifest.open("r", encoding="utf-8-sig", newline="") as handle:
                for row in csv.DictReader(handle):
                    source = row.get("source")
                    if source:
                        row["_manifest"] = str(manifest)
                        by_source[str(Path(source))].append(row)
        except Exception as exc:
            by_source[f"__manifest_read_error__:{manifest}"].append({"error": str(exc)})
    return by_source


def source_inventory(source_root: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for source_dir in sorted([p for p in source_root.iterdir() if p.is_dir()], key=lambda item: item.name) if source_root.exists() else []:
        if "深度修复横屏" in source_dir.name:
            continue
        location = location_from_source_dir(source_dir)
        for video in list_files(source_dir, VIDEO_EXTENSIONS):
            rows.append(
                {
                    "location": location,
                    "name": video.name,
                    "path": str(video),
                    "is_generated_or_manual": is_generated_or_system(video),
                    "size_mb": round(video.stat().st_size / 1024 / 1024, 2),
                }
            )
    return rows


def scene_inventory(scene_root: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for video in list_files(scene_root, VIDEO_EXTENSIONS):
        rel = video.relative_to(scene_root)
        parts = rel.parts
        location = parts[0].replace("智能镜头分类", "") if parts else "未知"
        category = parts[1] if len(parts) > 2 else "未分层"
        keyword = parts[2] if len(parts) > 3 else (parts[1] if len(parts) > 1 else "未分组")
        rows.append(
            {
                "location": location,
                "category": category,
                "keyword": keyword,
                "name": video.name,
                "path": str(video),
                "size_mb": round(video.stat().st_size / 1024 / 1024, 2),
                "is_crop_split": "裁剪分割" in video.name,
            }
        )
    return rows


def scene_key_from_path(path: str | Path) -> str:
    match = SCENE_KEY_PATTERN.search(Path(path).name)
    return match.group(1).upper() if match else ""


def scene_keys_by_location(scenes: list[dict[str, object]]) -> dict[str, set[str]]:
    keys: dict[str, set[str]] = defaultdict(set)
    for scene in scenes:
        key = scene_key_from_path(str(scene["path"]))
        if key:
            keys[str(scene["location"])].add(key)
    return keys


def audio_inventory(audio_root: Path) -> list[dict[str, object]]:
    rows = []
    for audio in list_files(audio_root, AUDIO_EXTENSIONS):
        if is_generated_or_system(audio):
            continue
        rel = audio.relative_to(audio_root)
        location = rel.parts[0] if len(rel.parts) > 1 else "未分组"
        transcript_path = find_audio_transcript(audio)
        rows.append(
            {
                "location": location,
                "name": audio.name,
                "path": str(audio),
                "size_mb": round(audio.stat().st_size / 1024 / 1024, 2),
                "has_txt": transcript_path is not None,
                "transcript_path": str(transcript_path) if transcript_path else "",
            }
        )
    return rows


def find_audio_transcript(audio: Path) -> Path | None:
    candidates = [
        audio.with_suffix(".transcript.txt"),
        audio.with_suffix(".txt"),
        audio.with_suffix(".plain.txt"),
    ]
    for candidate in candidates:
        if candidate.exists() and candidate.stat().st_size > 0:
            return candidate
    return None


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = sorted({key for row in rows for key in row.keys()}) if rows else ["empty"]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    args = parse_args()
    library_root = Path(args.library_root)
    source_root = library_root / "01_原片素材库"
    scene_root = library_root / "02_分镜素材库"
    audio_root = library_root / "03_音频文案库"
    report_root = library_root / "90_待整理与记录" / "._采集记录" / f"AI素材库健康审计_{args.report_date}"
    report_root.mkdir(parents=True, exist_ok=True)

    sources = source_inventory(source_root)
    scenes = scene_inventory(scene_root)
    scene_keys = scene_keys_by_location(scenes)
    audios = audio_inventory(audio_root)
    manifests = read_manifests(scene_root)

    real_sources = [row for row in sources if not row["is_generated_or_manual"]]
    source_audit: list[dict[str, object]] = []
    for source in sources:
        rows = manifests.get(str(Path(str(source["path"]))), [])
        written = []
        relocated = []
        for row in rows:
            if row.get("status") != "written" or not row.get("output"):
                continue
            output = str(row["output"])
            if Path(output).exists():
                written.append(output)
                continue
            if scene_key_from_path(output) in scene_keys.get(str(source["location"]), set()):
                written.append(output)
                relocated.append(output)
        status_counts = Counter(row.get("status", "") for row in rows)
        source_audit.append(
            {
                **source,
                "record_count": len(rows),
                "written_existing_count": len(written),
                "written_relocated_count": len(relocated),
                "complete": bool(written) if not source["is_generated_or_manual"] else "",
                "status_counts": json.dumps(dict(status_counts), ensure_ascii=False),
            }
        )

    real_source_count = len(real_sources)
    complete_source_count = sum(
        1
        for row in source_audit
        if not row["is_generated_or_manual"] and int(row["written_existing_count"]) > 0
    )
    scene_by_location = Counter(str(row["location"]) for row in scenes)
    scene_by_group = Counter((str(row["location"]), str(row["category"]), str(row["keyword"])) for row in scenes)
    audio_by_location = Counter(str(row["location"]) for row in audios)

    weak_groups = [
        {"location": loc, "category": cat, "keyword": kw, "count": count}
        for (loc, cat, kw), count in scene_by_group.items()
        if count <= 2
    ]
    huge_groups = [
        {"location": loc, "category": cat, "keyword": kw, "count": count}
        for (loc, cat, kw), count in scene_by_group.items()
        if count >= 120
    ]
    missing_audio_text = [row for row in audios if not row["has_txt"]]
    next_actions: list[dict[str, object]] = []
    for row in missing_audio_text:
        next_actions.append(
            {
                "priority": "P0",
                "action": "补音频文案/时间戳",
                "location": row["location"],
                "target": row["name"],
                "reason": "智能剪辑必须依赖台词时间戳，否则只能做弱关键词匹配。",
                "path": row["path"],
            }
        )
    for row in huge_groups:
        next_actions.append(
            {
                "priority": "P1",
                "action": "拆分过大素材组",
                "location": row["location"],
                "target": f"{row['category']} / {row['keyword']}",
                "reason": f"当前 {row['count']} 条，容易混杂，智能配镜会选错；需要细分子关键词或二次视觉复核。",
                "path": "",
            }
        )
    for row in weak_groups:
        next_actions.append(
            {
                "priority": "P2",
                "action": "补采/补分类弱素材组",
                "location": row["location"],
                "target": f"{row['category']} / {row['keyword']}",
                "reason": f"当前只有 {row['count']} 条，剪辑时容易循环同一画面。",
                "path": "",
            }
        )

    summary = {
        "library_root": str(library_root),
        "real_source_videos": real_source_count,
        "complete_source_videos": complete_source_count,
        "source_coverage_pct": round(complete_source_count / real_source_count * 100, 2) if real_source_count else 0,
        "generated_or_manual_source_derivatives": sum(1 for row in sources if row["is_generated_or_manual"]),
        "scene_clip_count": len(scenes),
        "scene_by_location": dict(scene_by_location),
        "audio_count": len(audios),
        "audio_by_location": dict(audio_by_location),
        "audio_missing_txt": len(missing_audio_text),
        "weak_keyword_groups_count": len(weak_groups),
        "huge_keyword_groups_count": len(huge_groups),
        "next_action_count": len(next_actions),
        "recommendations": [
            "Keep source coverage as a hard gate: true source videos must have at least one written scene output.",
            "Use weak keyword groups to drive collection: groups with <=2 clips are not enough for smooth editing.",
            "Review huge groups: groups with >=120 clips likely need sub-keyword splitting or deduplication.",
            "Audio assets without TXT should be transcribed before smart editing.",
            "Treat local matching as candidate generation only; semantic final selection should remain an AI-assisted skill step.",
        ],
    }

    write_csv(report_root / "source_coverage.csv", source_audit)
    write_csv(report_root / "scene_inventory.csv", scenes)
    write_csv(report_root / "audio_inventory.csv", audios)
    write_csv(report_root / "weak_keyword_groups.csv", weak_groups)
    write_csv(report_root / "huge_keyword_groups.csv", huge_groups)
    write_csv(report_root / "audio_missing_txt.csv", missing_audio_text)
    write_csv(report_root / "next_actions.csv", next_actions)
    (report_root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    report = [
        f"# AI素材库健康审计 {args.report_date}",
        "",
        "## 顶层结论",
        "",
        f"- 真原片覆盖率：{summary['complete_source_videos']} / {summary['real_source_videos']} = {summary['source_coverage_pct']}%",
        f"- 分镜素材总数：{summary['scene_clip_count']}",
        f"- 原片音频素材：{summary['audio_count']}，其中缺 TXT：{summary['audio_missing_txt']}",
        f"- 弱素材组（<=2条）：{summary['weak_keyword_groups_count']}",
        f"- 过大素材组（>=120条）：{summary['huge_keyword_groups_count']}",
        f"- 下一步动作：{summary['next_action_count']} 项",
        "",
        "## AI判断",
        "",
        "当前最重要的不是继续盲目生成更多分镜，而是进入“可剪辑质量治理”：覆盖率已经达标，下一步要减少脏素材、弱素材组和过大混杂组。",
        "",
        "优先级：",
        "",
        "1. 先补齐弱素材组，避免智能剪辑时一句台词只能反复循环同一个画面。",
        "2. 再拆分过大素材组，避免“风景/玩水/住宿”这种大桶里混进不相关画面。",
        "3. 给缺 TXT 的音频补转写，否则智能剪辑无法稳定按台词配镜。",
        "4. 本地工具只做候选生成和可视化；真正的语义配镜、疑难水印定位、质量复核必须由 AI Skill 参与。",
        "",
        "## 文件",
        "",
        f"- `source_coverage.csv`：原片覆盖表",
        f"- `scene_inventory.csv`：分镜全量索引",
        f"- `weak_keyword_groups.csv`：缺素材小组",
        f"- `huge_keyword_groups.csv`：过大混杂小组",
        f"- `audio_missing_txt.csv`：缺文案音频",
        f"- `next_actions.csv`：按优先级整理的下一步动作",
        f"- `summary.json`：机器可读汇总",
    ]
    (report_root / "AI素材库健康审计报告.md").write_text("\n".join(report), encoding="utf-8")
    print(json.dumps({"ok": True, "report_dir": str(report_root), **summary}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
