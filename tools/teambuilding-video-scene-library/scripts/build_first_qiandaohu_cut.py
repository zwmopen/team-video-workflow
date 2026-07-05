from __future__ import annotations

import csv
import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


ROOT = Path(r"D:\Download\素材下载\团建视频")
AUDIO = ROOT / "已整理原片音频" / "千岛湖" / "赞0评0千岛湖团建两天一夜行程安排来啦！杭州周边露营烧烤、骑行团建，很适合江浙沪团建哦.m4a"
LIBRARY = ROOT / "千岛湖智能镜头分类"
OUT_ROOT = ROOT / "智能剪辑初剪库"
TITLE = "千岛湖团建两天一夜行程安排来啦_Codex语义配镜_v3"


@dataclass
class Beat:
    index: int
    start: float
    end: float
    line: str
    visual_need: str
    folders: tuple[str, ...]
    keywords: tuple[str, ...]
    reason: str
    max_clips: int = 2


BEATS = [
    Beat(1, 0.20, 3.00, "千岛湖团建两天一夜，秋季行程安排来啦！", "千岛湖开场风景，直接建立地点和路线感。", ("01_环境空镜",), ("千岛湖风景", "风景俯拍", "天屿山"), "开场先用地点空镜，避免一上来就进入具体项目。", 1),
    Beat(2, 3.00, 6.00, "推荐喜欢接触大自然的团队参考。", "湖光山色、自然感、山水空镜。", ("01_环境空镜", "03_住宿空间"), ("千岛湖风景", "湖景露台", "民宿外观"), "这句关键词是自然和松弛，优先湖景/露台/山水。", 2),
    Beat(3, 6.00, 8.80, "第一天，乘船到湖心小岛森屿湖营地。", "游艇、上岛、湖面交通、营地抵达。", ("05_项目活动", "01_环境空镜"), ("游艇游湖", "千岛湖风景"), "乘船上岛必须用游艇或湖面交通；没有足够游艇时用湖面风景补充，不用烧烤冒充。", 2),
    Beat(4, 8.80, 11.20, "在湖光山色中玩拓展团建。", "团队拓展、团队游戏或集体互动。", ("06_团队互动", "05_项目活动"), ("团队游戏挑战", "团队合照", "玩水互动"), "拓展团建要有人和团队动作，不能只放纯风景。", 1),
    Beat(5, 11.20, 14.84, "晚上安排湖畔烧烤加篝火晚会，沉浸在大自然的怀抱中。", "烧烤、篝火、露营夜场。", ("07_烧烤露营夜场",), ("烧烤", "篝火", "露营吃东西", "露营"), "这句有明确烧烤篝火，必须选夜场和吃烤类素材。", 2),
    Beat(6, 15.07, 18.60, "上午集合领护具和单车，开展环岛骑行。", "集合、骑行、单车、路上出发。", ("05_项目活动",), ("湖边骑行",), "骑行是明确项目，只有骑行素材可用；没有第二条就不硬补水上乐园。", 1),
    Beat(7, 18.60, 22.20, "下午游艇上岛，体会千岛湖的大好风光。", "游艇上岛加千岛湖风景。", ("05_项目活动", "01_环境空镜"), ("游艇游湖", "千岛湖风景", "风景俯拍"), "游艇和风景结合，避免只用单一湖景。", 2),
    Beat(8, 22.20, 25.07, "小聚为您的团建提供交通、食宿、策划加执行一站式服务。", "收尾用团队合照、住宿/餐饮/环境组合，表达完整服务。", ("06_团队互动", "03_住宿空间", "04_餐饮美食"), ("团队合照", "民宿外观", "菜品餐桌", "千岛湖鱼宴"), "服务收尾适合用团队合照和吃住场景，不再引入新项目。", 1),
]


def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def ffprobe_duration(path: Path) -> float:
    proc = run(["ffmpeg", "-i", str(path), "-f", "null", "-"])
    text = proc.stderr + proc.stdout
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", text)
    if not match:
        return 0.0
    hours, minutes, seconds = match.groups()
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def ffprobe_size(path: Path) -> tuple[int, int]:
    proc = run(["ffmpeg", "-i", str(path)])
    text = proc.stderr + proc.stdout
    match = re.search(r"Video:.*? (\d{2,5})x(\d{2,5})", text)
    if not match:
        return 0, 0
    return int(match.group(1)), int(match.group(2))


def clean_score(path: Path) -> int:
    text = str(path)
    score = 0
    if "裁剪分割" in text or "裁切废料" in text or "手动处理" in text:
        score += 5
    if "深度修复" in text:
        score += 2
    if "._系统记录" in text or "keyframes" in text:
        score -= 100
    if "待人工分类" in text:
        score -= 3
    if "横屏" in text:
        score -= 8
    return score


def collect_clips() -> list[Path]:
    clips = []
    for path in LIBRARY.rglob("*.mp4"):
        if "._系统记录" in str(path):
            continue
        clips.append(path)
    return clips


def choose_for_beat(beat: Beat, clips: list[Path], used: set[Path]) -> list[Path]:
    scored: list[tuple[int, Path]] = []
    concrete_terms = {"游艇游湖", "湖边骑行", "烧烤", "篝火", "皮划艇", "摩托艇", "水上乐园"}
    required_terms = tuple(term for term in beat.keywords if term in concrete_terms)
    wrong_terms = concrete_terms.difference(required_terms)
    for path in clips:
        text = str(path)
        score = clean_score(path)
        for folder in beat.folders:
            if folder in text:
                score += 8
        for kw in beat.keywords:
            if kw in text:
                score += 12
        if required_terms and not any(term in text for term in required_terms):
            if not ("千岛湖风景" in beat.keywords and "千岛湖风景" in text):
                score -= 30
        for term in wrong_terms:
            if term in text:
                score -= 18
        if "千岛湖" in text:
            score += 2
        if path in used:
            score -= 25
        if score > 8:
            scored.append((score, path))
    scored.sort(key=lambda item: (-item[0], str(item[1])))

    checked: list[tuple[int, Path]] = []
    for score, path in scored[:40]:
        w, h = ffprobe_size(path)
        dur = ffprobe_duration(path)
        if w and h and h < w:
            score -= 10
        if dur < 0.5:
            score -= 10
        elif dur >= 1.2:
            score += 2
        if dur > 8:
            score -= 1
        checked.append((score, path))
    checked.sort(key=lambda item: (-item[0], str(item[1])))

    target_count = min(beat.max_clips, 2 if beat.end - beat.start >= 3.0 else 1)
    selected = []
    for _, path in checked:
        if path in used:
            continue
        selected.append(path)
        used.add(path)
        if len(selected) >= target_count:
            break
    if not selected and checked:
        selected.append(checked[0][1])
        used.add(checked[0][1])
    return selected


def safe_name(text: str, max_len: int = 48) -> str:
    bad = '<>:"/\\|?*'
    for ch in bad:
        text = text.replace(ch, "_")
    return text.strip()[:max_len].strip("_") or "clip"


def render_preview(audio: Path, pack_files: list[Path], output: Path) -> None:
    target_audio = ffprobe_duration(audio)
    if not pack_files or target_audio <= 0:
        return
    per_clip = max(1.2, min(3.0, target_audio / len(pack_files)))
    inputs: list[str] = []
    for path in pack_files:
        inputs.extend(["-i", str(path)])
    inputs.extend(["-i", str(audio)])
    filters = []
    labels = []
    for i, path in enumerate(pack_files):
        dur = ffprobe_duration(path)
        take = min(max(0.8, dur), per_clip + 0.4)
        filters.append(
            f"[{i}:v]scale=1080:1920:force_original_aspect_ratio=increase,"
            f"crop=1080:1920,setsar=1,fps=30,trim=duration={take:.3f},"
            f"setpts=PTS-STARTPTS[v{i}]"
        )
        labels.append(f"[v{i}]")
    filters.append("".join(labels) + f"concat=n={len(pack_files)}:v=1:a=0[vout]")
    cmd = [
        "ffmpeg",
        "-y",
        *inputs,
        "-filter_complex",
        ";".join(filters),
        "-map",
        "[vout]",
        "-map",
        f"{len(pack_files)}:a:0",
        "-t",
        f"{target_audio:.3f}",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-c:a",
        "aac",
        "-b:a",
        "128k",
        "-movflags",
        "+faststart",
        str(output),
    ]
    proc = run(cmd)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr[-2000:])


def main() -> None:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = OUT_ROOT / f"{stamp}_{TITLE}"
    out_dir.mkdir(parents=True, exist_ok=True)
    transcript = AUDIO.with_suffix(".transcript.txt")
    plain = AUDIO.with_suffix(".plain.txt")
    shutil.copy2(AUDIO, out_dir / AUDIO.name)
    if transcript.exists():
        shutil.copy2(transcript, out_dir / "文案_带时间戳.txt")
    if plain.exists():
        shutil.copy2(plain, out_dir / "文案.txt")
    elif transcript.exists():
        (out_dir / "文案.txt").write_text(transcript.read_text(encoding="utf-8"), encoding="utf-8")

    clips = collect_clips()
    used: set[Path] = set()
    rows = []
    pack_files: list[Path] = []
    seq = 1
    for beat in BEATS:
        selected = choose_for_beat(beat, clips, used)
        for clip_index, src in enumerate(selected, start=1):
            name = f"{seq:03d}_台词{beat.index:02d}_{safe_name(beat.visual_need, 18)}__{safe_name(src.stem, 42)}{src.suffix}"
            dst = out_dir / name
            shutil.copy2(src, dst)
            pack_files.append(dst)
            rows.append({
                "seq": seq,
                "beat": beat.index,
                "start": f"{beat.start:.2f}",
                "end": f"{beat.end:.2f}",
                "line": beat.line,
                "visual_need": beat.visual_need,
                "selected_clip": str(dst),
                "source_clip": str(src),
                "reason": beat.reason,
                "match_note": "Codex semantic + folder keyword match",
            })
            seq += 1

    with (out_dir / "配镜表.csv").open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    explanation_lines = [
        "# 配镜说明",
        "",
        f"- 音频：{AUDIO}",
        f"- 输出：{out_dir}",
        "- 策略：先按台词语义拆段，再按具体活动/场景匹配素材。明确项目不互相顶替。",
        "- 注意：这是第一版初剪包，目标是能按编号拖入剪映继续细剪。",
        "",
        "## 台词与画面",
    ]
    for beat in BEATS:
        explanation_lines.append(f"- 台词 {beat.index}: {beat.line}")
        explanation_lines.append(f"  - 画面需求：{beat.visual_need}")
        explanation_lines.append(f"  - 理由：{beat.reason}")
    (out_dir / "配镜说明.md").write_text("\n".join(explanation_lines), encoding="utf-8")

    report = [
        "# 质检报告",
        "",
        f"- 音频时长：{ffprobe_duration(AUDIO):.2f}s",
        f"- 已复制镜头数：{len(pack_files)}",
        f"- 覆盖台词段：{len(BEATS)}",
        "- 重复检查：已避免同一条素材连续重复；每个明确项目优先选对应分类。",
        "- 弱点：当前按现有分类和文件名做第一轮语义筛选，最终仍建议在界面里用合并播放快速复核。",
        "- 交付建议：把编号 mp4 按顺序拖进剪映，音频使用同目录复制的 m4a。",
    ]
    (out_dir / "质检报告.md").write_text("\n".join(report), encoding="utf-8")

    preview = out_dir / "rough_cut_preview.mp4"
    render_preview(AUDIO, pack_files, preview)

    summary = {
        "output_dir": str(out_dir),
        "audio": str(AUDIO),
        "transcript": str(transcript),
        "clips": len(pack_files),
        "preview": str(preview),
        "rows": rows,
    }
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
