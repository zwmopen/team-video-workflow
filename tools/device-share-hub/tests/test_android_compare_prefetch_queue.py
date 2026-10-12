"""Source guard for current-page-first Android comparison image loading."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "MainActivity.java").read_text(encoding="utf-8")


def section(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def main() -> None:
    compare = section(MAIN, "private void showOnlineCompareDialog(", "    /** 整页渲染开始时重置首发预算")
    render = section(compare, "Runnable renderPage = () -> {", "prevBtn.setOnClickListener(v -> {")
    prefetch_list = section(render, "final List<String> comparePrefetchImages", "final Runnable[] startNextComparePrefetch")
    next_prefetch = section(render, "startNextComparePrefetch[0] = () -> {", "final Runnable maybeStartComparePrefetch")
    start_gate = section(render, "final Runnable maybeStartComparePrefetch", "final Runnable visibleOriginalFinished")

    check("prefetch considers only the next two pages in forward order",
          "for (int p = idx + 1; p <= idx + 2 && p < total; p++)" in prefetch_list and
          "idx - 1" not in prefetch_list)
    check("each next page queues output and any associated source image",
          "comparePrefetchImages.add(nextOutput)" in prefetch_list and
          "comparePrefetchImages.add(nextSource)" in prefetch_list)
    check("prefetch does not start until all visible originals finish",
          "visibleOriginalsRemaining[0] > 0" in start_gate and
          "comparePrefetchStarted[0] = true" in start_gate and
          "startNextComparePrefetch[0].run()" in start_gate)
    check("visible success and failure callbacks both release the priority gate",
          render.count("visibleOriginalFinished.run();") == 4)
    check("only one prefetch image is submitted per queue step",
          next_prefetch.count("onlineClient.loadFullImage(") == 1 and
          "startNextComparePrefetch[0].run();" in next_prefetch)
    check("page changes and dialog dismissal invalidate stale prefetch callbacks",
          "++comparePrefetchGeneration[0]" in render and
          "comparePrefetchGeneration[0] != requestPrefetchGeneration" in next_prefetch and
          "comparePrefetchGeneration[0]++" in compare)
    check("stale page callbacks remain guarded and loading progress remains wired",
          render.count("pageGeneration[0] == requestGeneration") >= 4 and
          "rightProgress.setText(\"高清原图加载中 \" + percent + \"%\")" in render and
          "leftProgress.setText(\"高清原图加载中 \" + percent + \"%\")" in render)
    print("PASS Android comparison current-page-first prefetch contract")


if __name__ == "__main__":
    main()
