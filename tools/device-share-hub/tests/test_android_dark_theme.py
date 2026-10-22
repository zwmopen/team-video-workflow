# -*- coding: utf-8 -*-
"""DSH-193 回归：深色模式下「点进某个界面主题不符合」

真机取证：手机 `ui_night_mode=2`（深色模式开启），主界面采样正常 (18,18,18)，
但打开作品详情后上下两条系统栏是 **(248,249,248)** —— 浅色。原因有两类：

1. **内容层**：6 个界面把页底写死成奶油白 `Color.rgb(246, 244, 240)`、卡片 `Color.WHITE`、
   辅助文字 `Color.GRAY`，深色模式下就是「白底 + 灰字」。
2. **系统栏层**：`ThemePalette.applySystemBarAppearance()` 只有 MainActivity 和
   SettingsActivity 调过，其余 Activity 的状态栏/导航栏沿用 `Theme.Material.Light`，
   于是出现「内容深色、上下两条浅色」的白边。

本测试把这两条不变量钉死。注意：**文件类型图标色是语义色，深色浅色都要能看见，不许改**。
"""

import os
import re

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SRC = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery")
GRADLE = os.path.join(REPO_ROOT, "android", "app", "build.gradle.kts")


def _read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def _java(name):
    return _read(os.path.join(SRC, name))


# 不需要内容主题的 Activity：UpdateInstallActivity 是纯中转（弹完就 finish），没有内容视图
NO_CONTENT_VIEW = {"UpdateInstallActivity.java"}


def test_no_cream_page_background():
    print("=== DSH-193: 不许再有写死的奶油白页底 ===")
    cream = "Color.rgb(246, 244, 240)"
    offenders = []
    for name in sorted(os.listdir(SRC)):
        if not name.endswith(".java"):
            continue
        if cream in _java(name):
            offenders.append(name)
    assert not offenders, f"以下文件仍写死奶油白页底 {cream}：{offenders}"
    print("  PASS 1. 全量扫描无写死奶油白页底")


def test_system_bar_applied_everywhere():
    print("=== DSH-193: 每个有内容视图的 Activity 都要应用系统栏主题 ===")
    missing = []
    checked = 0
    for name in sorted(os.listdir(SRC)):
        if not name.endswith("Activity.java") or name in NO_CONTENT_VIEW:
            continue
        code = _java(name)
        if "setContentView(" not in code:
            continue
        checked += 1
        if "ThemePalette.applySystemBarAppearance(this)" not in code:
            missing.append(name)
    assert checked >= 6, f"覆盖的 Activity 数量异常：{checked}"
    assert not missing, f"以下 Activity 有内容视图却没应用系统栏主题：{missing}"
    print(f"  PASS 2. {checked} 个 Activity 全部应用系统栏主题")

    # 必须在 setContentView 之后调用（onCreate 里 setContentView 之前调会 NPE，见 ThemePalette 注释）
    for name in ("WorkDetailActivity.java", "FileBrowserActivity.java",
                 "ShareImportActivity.java", "TransferActivity.java"):
        code = _java(name)
        i_set = code.index("setContentView(")
        i_bar = code.index("ThemePalette.applySystemBarAppearance(this)")
        assert i_bar > i_set, f"{name}: 系统栏主题必须在 setContentView 之后调用"
    print("  PASS 3. 调用时机在 setContentView 之后（不会 NPE）")


def test_palette_has_new_getters():
    print("=== DSH-193: 调色板新增动态色 ===")
    tp = _java("ThemePalette.java")
    for getter in ("getAccentText", "getDangerColor", "getDangerSoftBg", "getDangerSoftStroke",
                   "getLinkColor", "getInfoBadgeBg", "getInfoBadgeText", "getInfoBadgeStroke",
                   "getWarnBadgeBg", "getWarnBadgeText", "getBrandSoftStroke"):
        assert f"public static int {getter}(" in tp, f"ThemePalette 缺少 {getter}"
    print("  PASS 4. 11 个动态色齐全")

    # 每个新 getter 都必须是「深色/浅色」二选一，不能是常量
    for getter in ("getAccentText", "getInfoBadgeBg", "getWarnBadgeText", "getBrandSoftStroke"):
        m = re.search(r"public static int " + getter + r"\(Context c\) \{(.*?)\n    \}", tp, re.S)
        assert m, f"无法解析 {getter} 实现"
        assert "isDarkMode(c)" in m.group(1), f"{getter} 必须是动态色（按 isDarkMode 取两套值）"
    print("  PASS 5. 新动态色确实是深浅两套值")


def test_semantic_icon_colors_preserved():
    print("=== DSH-193: 语义图标色不许被误改 ===")
    main = _java("MainActivity.java")
    fb = _java("FileBrowserActivity.java")
    for color in ("Color.rgb(222, 164, 64)",   # 文件夹
                  "Color.rgb(65, 145, 99)",    # 图片
                  "Color.rgb(124, 96, 164)",   # 视频
                  "Color.rgb(190, 76, 72)"):   # PDF
        assert color in main or color in fb, f"文件类型语义色被误删：{color}"
    # 图片查看器/对比视图故意用深色遮罩，不能被动
    assert "Color.BLACK" in _java("WorkDetailActivity.java"), "图片查看器的纯黑底必须保留"
    print("  PASS 6. 语义图标色与查看器黑底未被误改")


def test_version_bumped():
    print("=== DSH-193: 改了客户端代码就必须升版本 ===")
    g = _read(GRADLE)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', g).group(1)
    code = int(re.search(r"versionCode\s*=\s*(\d+)", g).group(1))
    assert code >= 232, f"versionCode 必须 >= 232，当前 {code}"
    assert name != "0.8.120", f"versionName 必须已递增，当前仍是 {name}"
    print(f"  PASS 7. Android 版本真源 = {name} ({code})")


if __name__ == "__main__":
    test_no_cream_page_background()
    test_system_bar_applied_everywhere()
    test_palette_has_new_getters()
    test_semantic_icon_colors_preserved()
    test_version_bumped()
    print("\nALL ANDROID DARK THEME TESTS PASSED")
