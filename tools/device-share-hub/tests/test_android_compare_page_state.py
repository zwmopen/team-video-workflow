"""Source guard for online comparison page changes (DSH-168)."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "MainActivity.java").read_text(encoding="utf-8")


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def main() -> None:
    setup_start = MAIN.index("FrameLayout leftFrame = new FrameLayout(this);")
    setup_end = MAIN.index("// 底部控制栏", setup_start)
    setup = MAIN[setup_start:setup_end]
    layout_start = MAIN.index("Runnable updateLayoutStructure = () -> {")
    layout_end = MAIN.index("toggleLayoutBtn.setOnClickListener", layout_start)
    layout = MAIN[layout_start:layout_end]
    start = MAIN.index("Runnable renderPage = () -> {")
    end = MAIN.index("prevBtn.setOnClickListener(v -> {", start)
    render_page = MAIN[start:end]
    setup = render_page[:render_page.index("String outImg = work.images.get(idx);")]
    title_start = MAIN.index('TextView titleView = text("对比视图",')
    title_end = MAIN.index("boolean hasSimilarity", title_start)
    title_module = MAIN[title_start:title_end]
    toggle_start = MAIN.index('Button toggleLayoutBtn = new Button(this);', title_end)
    toggle_end = MAIN.index("topControls.addView(toggleLayoutBtn, toggleParams);", toggle_start)
    toggle_module = MAIN[toggle_start:toggle_end]
    close_start = MAIN.index('Button closeBtn = new Button(this);', toggle_end)
    close_end = MAIN.index("root.addView(topBar", close_start)
    close_module = MAIN[close_start:close_end]

    check("images use the full centered pane without a reserved label rail", "leftImageParams.rightMargin" not in MAIN and "rightImageParams.rightMargin" not in MAIN)
    check("loading percentage is positioned inside the actual FIT_CENTER image bounds",
          "drawable.getIntrinsicWidth()" in MAIN and
          "float imageRight = imageLeft + displayedWidth;" in MAIN and
          "float imageBottom = imageTop + displayedHeight;" in MAIN and
          "left = Math.round(imageRight) - overlayWidth - dp(8);" in MAIN and
          "top = Math.round(imageBottom) - overlayHeight - dp(8);" in MAIN)
    check("side-by-side labels sit directly below the displayed images",
          "leftImage.setPadding(0, 0, 0, dp(24));" in layout and
          "rightImage.setPadding(0, 0, 0, dp(24));" in layout and
          "positionCompareOverlayOnImage(leftFrame, leftImage, leftBadge, true);" in MAIN and
          "positionCompareOverlayOnImage(rightFrame, rightImage, rightBadge, true);" in MAIN and
          "top = Math.round(imageBottom) + dp(2);" in MAIN)
    check("stacked labels stay at the right edge", "lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;" in layout and "rp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;" in layout)
    check("labels stay hidden while each original is loading", "leftBadge.setVisibility(View.GONE);" in setup and "rightBadge.setVisibility(View.GONE);" in setup and "正在加载原图 0%" in render_page)
    check("successful original loads replace progress with the corresponding label", "rightBadge.setVisibility(View.VISIBLE);" in render_page and "leftBadge.setVisibility(View.VISIBLE);" in render_page and render_page.count("Progress.setVisibility(View.GONE);") >= 2)
    check("failed original loads retain a visible failure status", 'rightProgress.setText("原图加载失败");' in render_page and 'leftProgress.setText("素材原图加载失败");' in render_page)
    check("clear both images before loading the next page", "leftImage.setImageDrawable(null);" in setup and "rightImage.setImageDrawable(null);" in setup)
    check("clear previous progress and empty-source state", "leftEmpty.setVisibility(View.GONE);" in setup and "leftProgress.setVisibility(View.GONE);" in setup and "rightProgress.setVisibility(View.GONE);" in setup)
    check("page number and controls update in the same render", 'pageText.setText("P" + (idx + 1) + " / P" + total);' in render_page)
    check("late full-image callbacks are tied to current page generation", "pageGeneration[0] == requestGeneration" in render_page)
    check("minimal arrow labels remain accessible", 'prevBtn.setContentDescription("上一页");' in MAIN and 'nextBtn.setContentDescription("下一页");' in MAIN)
    check("compare title is fixed text in a centered 88x32dp module",
          'text("对比视图", 12, true)' in title_module and
          "titleView.setGravity(Gravity.CENTER);" in title_module and
          "new LinearLayout.LayoutParams(dp(88), dp(32))" in title_module)
    check("compare title and layout toggle share dimensions, font size, and centered text",
          'text("对比视图", 12, true)' in title_module and
          "titleView.setGravity(Gravity.CENTER);" in title_module and
          "new LinearLayout.LayoutParams(dp(88), dp(32))" in title_module and
          "toggleLayoutBtn.setTextSize(12);" in toggle_module and
          "toggleLayoutBtn.setGravity(Gravity.CENTER);" in toggle_module and
          "new LinearLayout.LayoutParams(dp(88), dp(32))" in toggle_module)
    check("close cross is pinned to the top-right in its visible 36x32dp control",
          'closeBtn.setText("×");' in close_module and
          "closeBtn.setGravity(Gravity.CENTER);" in close_module and
          "new FrameLayout.LayoutParams(dp(36), dp(32)," in close_module and
          "Gravity.TOP | Gravity.END" in close_module and
          "topControlsParams.rightMargin = dp(42);" in MAIN)


if __name__ == "__main__":
    main()
