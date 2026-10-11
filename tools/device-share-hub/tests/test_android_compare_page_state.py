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

    check("images use the full centered pane without a reserved label rail", "leftImageParams.rightMargin" not in MAIN and "rightImageParams.rightMargin" not in MAIN)
    check("loading percentage stays at bottom-right in both panes", MAIN.count("new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END)") >= 2)
    check("side-by-side labels move to the top", "lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;" in layout and "rp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;" in layout)
    check("stacked labels stay at the right edge", "lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;" in layout and "rp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;" in layout)
    check("labels stay hidden while each original is loading", "leftBadge.setVisibility(View.GONE);" in setup and "rightBadge.setVisibility(View.GONE);" in setup and "正在加载原图 0%" in render_page)
    check("successful original loads replace progress with the corresponding label", "rightBadge.setVisibility(View.VISIBLE);" in render_page and "leftBadge.setVisibility(View.VISIBLE);" in render_page and render_page.count("Progress.setVisibility(View.GONE);") >= 2)
    check("failed original loads retain a visible failure status", 'rightProgress.setText("原图加载失败");' in render_page and 'leftProgress.setText("素材原图加载失败");' in render_page)
    check("clear both images before loading the next page", "leftImage.setImageDrawable(null);" in setup and "rightImage.setImageDrawable(null);" in setup)
    check("clear previous progress and empty-source state", "leftEmpty.setVisibility(View.GONE);" in setup and "leftProgress.setVisibility(View.GONE);" in setup and "rightProgress.setVisibility(View.GONE);" in setup)
    check("page number and controls update in the same render", 'pageText.setText("P" + (idx + 1) + " / P" + total);' in render_page)
    check("late full-image callbacks are tied to current page generation", "pageGeneration[0] == requestGeneration" in render_page)
    check("minimal arrow labels remain accessible", 'prevBtn.setContentDescription("上一页");' in MAIN and 'nextBtn.setContentDescription("下一页");' in MAIN)


if __name__ == "__main__":
    main()
