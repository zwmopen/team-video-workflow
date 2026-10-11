"""静态契约：iOS 在线状态与对比视图必须保持可见、可读、可回归。

主机没有 Xcode 时也要守住几个曾经在真机暴露的布局根因：状态条被旧文案盖住、
对比卡片尺寸固定过小、横向 UIStackView 子项没有高度、缺素材页退回普通预览。
"""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTENT = (ROOT / "ios" / "Album" / "ContentView.swift").read_text(encoding="utf-8-sig")
CLIENT = (ROOT / "ios" / "Album" / "OnlineGalleryClient.swift").read_text(encoding="utf-8-sig")


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def main() -> None:
    preview_start = CONTENT.index("final class OnlineComparePreviewController")
    preview = CONTENT[preview_start:]
    container_start = preview.index("private func setupImageViewContainer(")
    container_end = preview.index("private func setupGestures()", container_start)
    container = preview[container_start:container_end]
    load_start = preview.index("private func loadPage(_ pageIndex: Int)")
    load_end = preview.index("private func finishVisiblePageLoad", load_start)
    load_page = preview[load_start:load_end]
    toggle_start = preview.index("@objc private func toggleLayoutMode()")
    toggle_end = preview.index("@objc private func prevPage()", toggle_start)
    toggle = preview[toggle_start:toggle_end]

    check("iOS status has a dedicated online-status-label identifier", 'accessibilityIdentifier = "online-status-label"' in CONTENT)
    check("iOS status exposes synced total", "已同步电脑在线作品 · 共 \\(onlineWorks.count) 套" in CONTENT)
    check("iOS status shows in-flight sync state", 'onlineStatusOverride = "正在同步电脑在线作品…"' in CONTENT)
    check("compare card width adapts to the phone", "static var cardWidth: CGFloat" in CONTENT)
    check("compare card image keeps a 3:4 ratio", "static var imageHeight: CGFloat { imageWidth * 4.0 / 3.0 }" in CONTENT)
    check("compare preview height follows the card", "previewScrollHeightConstraint?.constant = ComparePairThumbView.cardHeight" in CONTENT)
    check("horizontal compare containers get an explicit height", "horizontalContainerHeightConstraints" in CONTENT and "multiplier: 1.18" in CONTENT)
    check("compare mode opens even when a page has no source", "if currentViewMode == .compare {" in CONTENT)
    check("iOS source arrays stay aligned to output pages", "var sourceImages = images.indices.map" in CLIENT and "var sourceNames = images.indices.map" in CLIENT)
    check("old cache can rebuild a source token from sourceNames", 'sourceImages[index] = "__src__:\\(outputName)"' in CLIENT)
    check("comparison images fill the pane and remain aspect-fit centered", "imageView.trailingAnchor.constraint(equalTo: container.trailingAnchor)" in container and "imageView.contentMode = .scaleAspectFit" in container and "badge.leadingAnchor" not in container)
    check("original-load percentage stays at bottom-right", "progressLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -8)" in container and "progressLabel.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8)" in container)
    check("stacked mode places labels in the right-side rail", "badge.centerYAnchor.constraint(equalTo: container.centerYAnchor)" in container and "leftBadgeCenterYConstraint?.isActive = !isHorizontalLayout" in toggle)
    check("side-by-side mode moves labels to the top", "badge.topAnchor.constraint(equalTo: container.topAnchor, constant: 8)" in container and "leftBadgeTopConstraint?.isActive = isHorizontalLayout" in toggle and "rightBadgeTopConstraint?.isActive = isHorizontalLayout" in toggle)
    check("original-load state starts with visible percentage text", 'rightProgressLabel.text = "正在加载原图 0%"' in load_page and 'leftProgressLabel.text = sourcePath.isEmpty ? nil : "正在加载原图 0%"' in load_page)
    check("successful original loads show labels and hide progress", "self.rightBadge.isHidden = false" in load_page and "self.leftBadge.isHidden = false" in load_page and load_page.count("ProgressLabel.isHidden = true") >= 2)
    check("failed original loads remain visibly distinct from ready labels", 'self.rightProgressLabel.text = "原图加载失败"' in load_page and 'self.leftProgressLabel.text = "素材原图加载失败"' in load_page)
    print("PASS iOS compare/status UI contract")


if __name__ == "__main__":
    main()
