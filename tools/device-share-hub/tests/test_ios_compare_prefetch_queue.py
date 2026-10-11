"""Static regression gates for the iOS comparison preview image queue."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTENT = (ROOT / "ios" / "Album" / "ContentView.swift").read_text(encoding="utf-8-sig")
CLIENT = (ROOT / "ios" / "Album" / "OnlineGalleryClient.swift").read_text(encoding="utf-8-sig")
COMPARE = CONTENT.split("final class OnlineComparePreviewController", 1)[-1]


def section(source: str, start: str, end: str) -> str:
    if start not in source:
        return ""
    start_index = source.index(start)
    end_index = source.find(end, start_index)
    return source[start_index : end_index] if end_index >= 0 else ""


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def main() -> None:
    load_page = section(COMPARE, "private func loadPage(_ pageIndex: Int) {", "private func finishVisiblePageLoad")
    finish_visible_load = section(COMPARE, "private func finishVisiblePageLoad", "private func prepareComparePrefetch")
    prepare_prefetch = section(COMPARE, "private func prepareComparePrefetch", "private func startNextComparePrefetch")
    start_prefetch = section(COMPARE, "private func startNextComparePrefetch", "    @objc private func toggleLayoutMode()")
    remote_load = section(CLIENT, "private func loadRemoteImageAttempt(", "    /// 同步快速获取缓存图片")

    check("page changes cancel stale visible and prefetch requests",
          "activePageRequests.forEach { $0.cancel() }" in load_page and "cancelComparePrefetch()" in load_page)
    check("new page clears old images before reading cached thumbnail placeholders",
          load_page.index("rightImageView.image = nil") < load_page.index("getFastCachedImage") and
          load_page.index("leftImageView.image = nil") < load_page.index("getFastCachedImage"))
    check("cold visible pages request only full originals without thumbnail network competition",
          ".loadImage(" not in load_page and load_page.count(".loadImageWithProgress(") == 2)
    check("source original failure always ends its progress state while retaining a cached thumbnail",
          'self.leftProgressLabel.text = "加载失败"' in load_page and
          load_page.index('self.leftProgressLabel.text = "加载失败"') < load_page.index("if self.leftImageView.image == nil"))
    check("prefetch waits until all visible full images finish",
          "pageLoadsRemaining == 0" in finish_visible_load and "prepareComparePrefetch" in finish_visible_load)
    check("prefetch order contains only the next two pages",
          "currentIndex + 1" in prepare_prefetch and "currentIndex + 2" in prepare_prefetch and
          "pageIndex - 1" not in prepare_prefetch)
    check("prefetch queue starts one image and advances from completion",
          start_prefetch.count(".loadImage(") == 1 and
          "startNextComparePrefetch(generation: generation)" in start_prefetch)
    check("zero-similarity entries hide the duplicate comparison badge",
          'similarityBadge.text = "对比视图"' not in CONTENT and
          "similarityBadge.isHidden = !hasSimilarity" in CONTENT)
    check("image loader exposes a cancellable URLSession task handle",
          "public final class ImageLoadRequest" in CLIENT and
          "public func cancel()" in CLIENT and "request.attach(task)" in CLIENT and
          "-> ImageLoadRequest" in CLIENT)
    check("cancellation stops active and late-created URLSession tasks",
          "activeTask?.cancel()" in CLIENT and "if shouldCancel" in CLIENT and "task.cancel()" in CLIENT)
    check("cancelled requests suppress callbacks and delayed retries",
          remote_load.count("guard !request.isCancelled else { return }") >= 4 and
          "asyncAfter(deadline: .now() + self.thumbnailRetryDelay)" in remote_load and
          "guard let self = self, !request.isCancelled else { return }" in remote_load)
    print("PASS iOS comparison prefetch queue contract")


if __name__ == "__main__":
    main()
