#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-200：从 CHANGELOG 顶部区块抽取「更新内容」，供发布清单 latest.json 的 notes 字段。

背景（两次翻车）：
    1) 早期 CI 把 notes 写死成「相册 Android x.y.z（versionCode N）/ iOS a.b.c（build M）；
       源码提交 SHA」——用户只看得到版本号，看不到改了什么。
    2) DSH-198 改成从 CHANGELOG 现抽，结果把**内部工程语言**直接推给了手机：
       `OnlineWorkLifecycle.AUTO_ARCHIVE_DISABLED`、`tests/test_dsh195_server_directory_fallback.py
       → 28 项全过`、`Android 0.8.130/241 · iOS 0.8.124/235`。
       用户原话：「手机端收到更新日志，我靠一大串，一大堆编号，听不懂的描述」。

契约（本文件唯一的判断标准）：
    **手机弹窗里只允许出现「用户能感觉到的变化」，一句话一条，不带编号、不带类名、不带路径。**
    优先抽 CHANGELOG 顶部区块里的 `## 手机上看得到的变化` 段（作者手写的人话，3~5 条）；
    没有该段才走 [回退模式]：清洗裸要点行，并尽量剔除代码符号/编号/版本号；
    清洗后不足 1 条就返回空，由调用方回落版本模板（宁可少说，不说黑话）。

用法：
    python3 scripts/extract_changelog_notes.py [CHANGELOG 路径] [--max-items N] [--max-chars N] [--debug]
"""

import os
import re
import sys

DEFAULT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "CHANGELOG.md"
)

# 人话段落的小标题别名——写法不统一时别让抽取静默失效。
_USER_SECTION = re.compile(
    r"^##+\s*(?:手机上看得到的变化|手机上能感觉到的变化|你能感觉到的变化"
    r"|用户能感知的变化|用户能感觉到的变化|说人话)"
)

# 章节标题里常见的序号前缀，展示时去掉。
_ORDINAL = re.compile(r"^(?:[一二三四五六七八九十]+、|\d+[、.）)]\s*|\([一二三四五六七八九十]+\)\s*)")
# 「用户口径」段落整体跳过（内部对话原话，不适合出现在安装弹窗里）。
_SKIP_SECTION = re.compile(r"^##\s*用户口径")

# —— 黑话清洗规则（顺序敏感）——
_RE_URL = re.compile(r"https?://\S+")
_RE_DSH = re.compile(r"DSH-\d+")
_RE_DATE = re.compile(r"\b20\d{2}-\d{2}-\d{2}\b")
_RE_VERSION_TAIL = re.compile(r"[（(][^（）()]*(?:Android|iOS|Windows|服务端|versionCode|build)[^（）()]*[）)]")
_RE_CODE_SPAN = re.compile(r"`([^`]+)`")
_RE_CODEISH = re.compile(r"[A-Za-z0-9_]*[_./][A-Za-z0-9_./]*\.?(?:java|swift|py|kt|json|yml|yaml|md|ps1|sh)\b")
# 这些词一出现，基本就是内部验收口径，不是给用户看的。
_RE_INTERNAL = re.compile(
    r"(?:单测|单元测试|测试用例|回归|断言|全过|项全过|契约测试|CI|闸门|构建|编译|"
    r"fail|assert|pytest|parity_|test_)",
    re.IGNORECASE,
)
# 手写人话段也可能手滑写进黑话——出口统一再过一道闸，命中就整条丢掉。
_RE_BANNED_LINE = re.compile(
    r"(?:DSH-\d|`|versionCode|versionName|\.java\b|\.swift\b|\.py\b|\.kt\b|"
    r"https?://|__|\b[a-z]+_[a-z_]+\b|[A-Za-z]+\.[A-Za-z]+\()"
)


def _clean(s):
    s = re.sub(r"\*\*", "", s).strip()
    s = re.sub(r"\s+", " ", s)
    return s.strip(" ：:")


def _clip(s, max_chars):
    if len(s) <= max_chars:
        return s
    return s[: max_chars - 1].rstrip() + "…"


def _is_codeish(span):
    """反引号里的内容是不是代码符号（类名/字段/路径/命令）。"""
    if _RE_CODEISH.search(span):
        return True
    return bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", span))


def _humanize(s):
    """把一条工程要点尽量翻成用户能读的话。

    关键取舍：**看不懂就整条丢掉，绝不吐半句话**。
    上一版把 ``AUTO_ARCHIVE_DISABLED`` 从反引号里抠掉，输出成了
    「在 被从 改成 ，等于重新启用…」——比原文更像乱码。
    """
    s = _clean(s)
    if not s:
        return ""

    spans = _RE_CODE_SPAN.findall(s)
    # 反引号里只要有一个是类名/字段/路径/命令，整句离开它就是残句 → 丢弃。
    if any(_is_codeish(x) for x in spans):
        return ""
    if spans:
        s = _RE_CODE_SPAN.sub(lambda m: m.group(1), s)

    # 没加反引号的标识符（下划线变量 / 点号路径）同样会毁掉一句话。
    if re.search(r"[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+", s):
        return ""
    if re.search(r"\b[A-Za-z][A-Za-z0-9]+\.[A-Za-z][A-Za-z0-9]+\b", s):
        return ""

    s = _RE_URL.sub("", s)
    s = _RE_DATE.sub("", s)
    s = _RE_VERSION_TAIL.sub("", s)
    s = _RE_DSH.sub("", s)
    s = re.sub(r"[（(]\s*[）)]", "", s)
    s = re.sub(r"\s+", " ", s).strip(" ：:，,、·-—")

    # 含内部验收词（单测/断言/全过/CI…）→ 不是人话，丢掉。
    if _RE_INTERNAL.search(s):
        return ""
    if len(re.sub(r"[^\w]", "", s, flags=re.UNICODE)) < 8:
        return ""
    return s


def _sanitize_title(t):
    """标题行去掉日期、DSH 编号、版本尾巴——那些对用户没有信息量。"""
    t = _clean(t)
    t = _RE_DATE.sub("", t)
    t = _RE_DSH.sub("", t)
    t = _RE_VERSION_TAIL.sub("", t)
    t = re.sub(r"^[\s：:·\-—]+", "", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip(" ：:，,、-—")


def extract(path=DEFAULT_PATH, max_items=5, max_chars=46, max_total=600, info=None):
    """返回给手机弹窗看的 notes；抽不出人话则返回空串。info 回填来源，供 --debug 用。"""
    if info is None:
        info = {}
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as fp:
            lines = fp.read().splitlines()
    except OSError:
        return ""

    title = ""
    user_items = []   # 「## 手机上看得到的变化」段，最高优先级
    heads = []        # 二级标题 / 加粗要点（回退用）
    bullets = []      # 普通要点（回退用）
    in_fence = False
    skipping = False
    in_user = False
    started = False
    in_bullet = False

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

        if stripped.startswith("##"):
            in_bullet = False
            if _USER_SECTION.match(stripped):
                in_user = True
                skipping = False
                continue
            in_user = False
            if stripped.startswith("## "):
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

        if in_user:
            # 人话段只认「- 」要点行；遇到正文/引用/表格就说明该段结束了
            # （曾因「用户口径：」这行裸文本被当成第 5 条，弹窗里冒出「· 用户口径」）。
            if stripped.startswith("- "):
                text = _clean(stripped[2:])
                text = _RE_DSH.sub("", text).strip(" ：:，,、-—")
                if text:
                    user_items.append(_clip(text, max_chars))
                continue
            if not stripped:
                continue
            in_user = False
            # 不 continue：交给下面的常规逻辑判断它是不是新章节/加粗要点

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
                joined = re.sub(r"([，。；：、）】」』！？…])\s+", r"\g<1>", joined)
                bullets[-1] = joined
            continue
        if not stripped:
            in_bullet = False

    # 出口统一过闸：手写段也可能手滑写进黑话，命中就整条丢掉。
    user_items = [x for x in user_items if not _RE_BANNED_LINE.search(x)]

    # 路径 1：人话段（作者手写，最可信）
    if user_items:
        info["source"] = "手机上看得到的变化"
        info["count"] = len(user_items)
        picked = user_items[:max_items]
        text = "\n".join("· " + p for p in picked)
        return text[: max_total - 1].rstrip() + "…" if len(text) > max_total else text

    # 路径 2：回退——清洗工程语言，仍抽不出人话就交回调用方
    raw = bullets if not heads else (heads + bullets)
    human = []
    for item in raw:
        h = _humanize(item)
        if h and h not in human and not _RE_BANNED_LINE.search(h):
            human.append(h)
        if len(human) >= max_items:
            break
    if not human:
        info["source"] = "空"
        return ""
    info["source"] = "回退清洗"

    head = _sanitize_title(title)
    out = ([head] if head else []) + ["· " + _clip(h, max_chars) for h in human]
    text = "\n".join(out)
    return text[: max_total - 1].rstrip() + "…" if len(text) > max_total else text


def main(argv):
    path = DEFAULT_PATH
    max_items, max_chars = 5, 46
    debug = False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--max-items" and i + 1 < len(argv):
            max_items = int(argv[i + 1])
            i += 2
        elif a == "--max-chars" and i + 1 < len(argv):
            max_chars = int(argv[i + 1])
            i += 2
        elif a == "--debug":
            debug = True
            i += 1
        elif not a.startswith("-"):
            path = a
            i += 1
        else:
            i += 1

    info = {}
    text = extract(path, max_items, max_chars, info=info)
    if debug:
        sys.stderr.write(
            "[notes] 来源=%s 原始条数=%s 输出=%s 行 / %s 字\n"
            % (
                info.get("source", "?"),
                info.get("count", "-"),
                len(text.splitlines()) if text else 0,
                len(text),
            )
        )
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
