#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-198：从 CHANGELOG 顶部区块抽取「本次更新内容」，供发布清单 latest.json 的 notes 字段。

背景：
    手机端（Android `UpdateChecker` / iOS 更新提示）在「发现新版本」弹窗里会**全量展示**
    `latest.json` 的 `notes`。此前 CI 把 notes 写死成
    「相册 Android x.y.z（versionCode N）/ iOS a.b.c（build M）；源码提交 SHA」，
    等于用户点更新时只看得到版本号，看不到到底修了什么 —— 功能形同虚设。

契约：
    只取文件**第一个** `# ` 区块（本仓 CHANGELOG 约定最新条目置顶）；
    标题行原样作为第一行，正文优先抽 `## 章节标题` 与 `**加粗要点**`（作者已经归纳过，
    比裸 bullet 更短、不会断在逗号上），凑不够才回落到 `- ` 要点行。
    跳过「用户口径」段（那是内部对话原话，不适合出现在安装弹窗里）、
    表格行（`|`）、代码块、引用块。
    输出为空时由调用方回落版本模板，本脚本不负责兜底文案。

用法：
    python3 scripts/extract_changelog_notes.py [CHANGELOG 路径] [--max-items N] [--max-chars N]
"""

import os
import re
import sys

DEFAULT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "CHANGELOG.md"
)

# 章节标题里常见的序号前缀，展示时去掉，避免弹窗里满屏「一、」「1）」。
_ORDINAL = re.compile(r"^(?:[一二三四五六七八九十]+、|\d+[、.）)]\s*|\([一二三四五六七八九十]+\)\s*)")
# 「用户口径」段落整体跳过。
_SKIP_SECTION = re.compile(r"^##\s*用户口径")


def _clean(s):
    s = re.sub(r"\*\*", "", s).strip()
    s = re.sub(r"`+", "", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip(" ：:")


def _clip(s, max_chars):
    if len(s) <= max_chars:
        return s
    return s[: max_chars - 1].rstrip() + "…"


def extract(path=DEFAULT_PATH, max_items=9, max_chars=100, max_total=1100):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as fp:
            lines = fp.read().splitlines()
    except OSError:
        return ""

    title = ""
    heads = []   # 二级标题 / 加粗要点
    bullets = []  # 普通要点（兜底）；先存原文，最后统一截断
    in_fence = False
    skipping = False
    started = False
    in_bullet = False  # 上一行是要点 ⇒ 后续缩进的裸文本视为它的软换行续行

    for ln in lines:
        stripped = ln.strip()

        if stripped.startswith("```"):
            in_fence = not in_fence
            continue

        if ln.startswith("# "):
            if started:
                break
            started = True
            title = ln[2:].strip()
            continue
        if not started:
            continue

        if stripped.startswith("## "):
            in_bullet = False
            if _SKIP_SECTION.match(stripped):
                skipping = True
                continue
            skipping = False
            text = _clean(_ORDINAL.sub("", stripped[3:].strip()))
            if text:
                heads.append(_clip(text, max_chars))
            continue

        if in_fence or skipping:
            continue

        if stripped.startswith(("#", "**", ">", "|")):
            in_bullet = False

        m = re.fullmatch(r"\*\*(.+?)\*\*", stripped)
        if m:
            text = _clean(_ORDINAL.sub("", m.group(1)))
            if text:
                heads.append(_clip(text, max_chars))
            continue

        if stripped.startswith("- ") and "|" not in stripped:
            text = _clean(stripped[2:])
            if text:
                bullets.append(text)
                in_bullet = True
            continue

        # 软换行续行：markdown 里一条要点常被折成多行，直接取首行会断在逗号上。
        if in_bullet and stripped:
            if bullets:
                joined = (bullets[-1] + " " + _clean(stripped)).strip()
                # 中文标点后不需要空格（源文件折行处会留下多余空白）
                joined = re.sub(r"([，。；：、）】」』！？…])\s+", r"\g<1>", joined)
                bullets[-1] = joined
            continue
        if not stripped:
            in_bullet = False

    picked = heads if len(heads) >= 2 else bullets
    # 「## 问题 / ## 修复 / ## 验证」这类纯章节名没有信息量，弹窗里等于三条废话。
    # 判据：heads 里够长的不足 2 条，就改用正文要点行。
    if picked is heads and len([h for h in heads if len(h) >= 10]) < 2:
        picked = bullets
    if not title or not picked:
        return ""
    out = [title] + ["· " + _clip(p, max_chars) for p in picked[:max_items]]
    text = "\n".join(out)
    if len(text) > max_total:
        text = text[: max_total - 1].rstrip() + "…"
    return text


def main(argv):
    path = DEFAULT_PATH
    max_items, max_chars = 9, 100
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--max-items" and i + 1 < len(argv):
            max_items = int(argv[i + 1])
            i += 2
        elif a == "--max-chars" and i + 1 < len(argv):
            max_chars = int(argv[i + 1])
            i += 2
        elif not a.startswith("-"):
            path = a
            i += 1
        else:
            i += 1
    sys.stdout.write(extract(path, max_items, max_chars))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
