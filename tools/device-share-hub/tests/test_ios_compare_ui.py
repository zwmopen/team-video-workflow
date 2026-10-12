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
    thumb_start = CONTENT.index("private final class ComparePairThumbView")
    thumb_end = CONTENT.index("final class OnlineComparePreviewController", thumb_start)
    thumb = CONTENT[thumb_start:thumb_end]
    preview_start = CONTENT.index("final class OnlineComparePreviewController")
    preview = CONTENT[preview_start:]
    container_start = preview.index("private func setupImageViewContainer(")
    container_end = preview.index("private func setupGestures()", container_start)
    container = preview[container_start:container_end]
    setup_start = preview.index("private func setupUI()")
    setup_end = preview.index("private func setupImageViewContainer(", setup_start)
    setup = preview[setup_start:setup_end]
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
    check("home comparison thumbnails show the complete source and final", "iv.contentMode = .scaleAspectFit" in thumb and ".scaleAspectFill" not in thumb)
    check("compare card leaves only a compact header and bottom inset", "static var cardHeight: CGFloat { imageHeight + 27 }" in thumb and "leftImageView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6)" in thumb)
    check("home compare labels overlay each image bottom-center", "leftImageView.addSubview(leftBadge)" in thumb and "rightImageView.addSubview(rightBadge)" in thumb and "leftBadge.centerXAnchor.constraint(equalTo: leftImageView.centerXAnchor)" in thumb and "leftBadge.bottomAnchor.constraint(equalTo: leftImageView.bottomAnchor, constant: -8)" in thumb and "rightBadge.centerXAnchor.constraint(equalTo: rightImageView.centerXAnchor)" in thumb and "rightBadge.bottomAnchor.constraint(equalTo: rightImageView.bottomAnchor, constant: -8)" in thumb)
    check("compare preview height follows the card", "previewScrollHeightConstraint?.constant = ComparePairThumbView.cardHeight" in CONTENT)
    check("horizontal compare containers get an explicit height", "horizontalContainerHeightConstraints" in CONTENT and "multiplier: 1.18" in CONTENT)
    check("compare mode opens even when a page has no source", "if currentViewMode == .compare {" in CONTENT)
    check("fullscreen title uses the fixed short compare label", 'titleLabel.text = "对比视图"' in setup and 'titleLabel.text = "对比视图 · \\(entry.title)"' not in setup)
    check("title and layout selector share centered typography and styling", "let moduleBackground = UIColor(white: 0.22, alpha: 0.8)" in setup and "let moduleCornerRadius: CGFloat = 8" in setup and "let moduleFont = UIFont.systemFont(ofSize: 12, weight: .semibold)" in setup and "titleLabel.font = moduleFont" in setup and "titleLabel.textAlignment = .center" in setup and "titleLabel.backgroundColor = moduleBackground" in setup and "titleLabel.layer.cornerRadius = moduleCornerRadius" in setup and "layoutModeButton.titleLabel?.font = moduleFont" in setup and "layoutModeButton.backgroundColor = moduleBackground" in setup and "layoutModeButton.layer.cornerRadius = moduleCornerRadius" in setup and "layoutModeButton.contentHorizontalAlignment = .center" in setup and "layoutModeButton.contentVerticalAlignment = .center" in setup)
    check("title and layout selector share an adaptive width and 32 point height", "let moduleWidth = min(92, max(minimumModuleWidth, (topBarWidth - fixedTopBarWidth - similarityWidth) / 2))" in setup and "titleLabel.widthAnchor.constraint(equalToConstant: moduleWidth)" in setup and "layoutModeButton.widthAnchor.constraint(equalToConstant: moduleWidth)" in setup and "titleLabel.heightAnchor.constraint(equalToConstant: 32)" in setup and "layoutModeButton.heightAnchor.constraint(equalToConstant: 32)" in setup)
    check("close control keeps a centered title in its fixed bounds", "closeButton.contentHorizontalAlignment = .center" in setup and "closeButton.contentVerticalAlignment = .center" in setup and "closeButton.titleLabel?.textAlignment = .center" in setup and "closeButton.widthAnchor.constraint(equalToConstant: 32)" in setup and "closeButton.heightAnchor.constraint(equalToConstant: 32)" in setup)
    check("iOS source arrays stay aligned to output pages", "var sourceImages = images.indices.map" in CLIENT and "var sourceNames = images.indices.map" in CLIENT)
    check("old cache can rebuild a source token from sourceNames", 'sourceImages[index] = "__src__:\\(outputName)"' in CLIENT)
    check("comparison images fill the pane and remain aspect-fit centered", "imageView.trailingAnchor.constraint(equalTo: container.trailingAnchor)" in container and "imageView.contentMode = .scaleAspectFit" in container and "badge.leadingAnchor" not in container)
    check("comparison panes match the black fullscreen canvas without a card edge", "container.backgroundColor = .black" in container and "container.layer.cornerRadius = 0" in container and "container.layer.borderWidth = 0" in container)
    check("original-load percentage follows the visible aspect-fit image", "override func viewDidLayoutSubviews()" in preview and "updateProgressLabelPositions()" in preview and "imageAspect > paneAspect" in preview and "visibleImageRect.offsetBy(dx: imageView.frame.minX, dy: imageView.frame.minY)" in preview and "progressLabel.translatesAutoresizingMaskIntoConstraints = true" in container and "progressLabel.trailingAnchor.constraint" not in container and "progressLabel.bottomAnchor.constraint" not in container)
    check("loading label shrinks to stay fully inside a narrow visible image", "progressLabel.adjustsFontSizeToFitWidth = true" in container and "progressLabel.minimumScaleFactor = 0.65" in container and "progressLabel.numberOfLines = 1" in container and "let inset: CGFloat = 4" in preview and "minimumWidth: 58, height: 20, horizontalPadding: 12" in preview and "let labelWidth = min(max(minimumWidth, fittingSize.width + horizontalPadding), availableWidth)" in preview and "x: imageRectInContainer.maxX - labelWidth - inset" in preview and "y: imageRectInContainer.maxY - labelHeight - inset" in preview)
    check("loaded labels share the actual fitted image bottom-right with the progress state",
          "positionBadgeLabel(leftBadge, over: leftImageView)" in preview and
          "positionBadgeLabel(rightBadge, over: rightImageView)" in preview and
          "private func positionBadgeLabel(" in preview and
          "imageRectInContainer.maxX - labelWidth - inset" in preview and
          "imageRectInContainer.maxY - labelHeight - inset" in preview and
          "badge.translatesAutoresizingMaskIntoConstraints = true" in container and
          "badge.trailingAnchor.constraint" not in container)
    check("original-load state starts with the same high-resolution status on both panes", 'rightProgressLabel.text = "高清原图加载中 0%"' in load_page and 'leftProgressLabel.text = sourcePath.isEmpty ? nil : "高清原图加载中 0%"' in load_page and 'self.rightProgressLabel.text = "高清原图加载中 \\(pct)%"' in load_page and 'self.leftProgressLabel.text = "高清原图加载中 \\(pct)%"' in load_page)
    check("standalone original preview uses the same loading status", 'badgeLabel.text = "⏳ 高清原图加载中..."' in CONTENT)
    view_did_load_start = CONTENT.index("override func viewDidLoad()")
    view_did_load_end = CONTENT.index("@objc private func openTransfer()", view_did_load_start)
    view_did_load = CONTENT[view_did_load_start:view_did_load_end]
    check("online first appearance does not duplicate the initial list request", "primeOnlineDataFromSnapshot()" in view_did_load and "loadOnlineData()" not in view_did_load and "loadOnlineData(silent: true)" in CONTENT)
    model_parse_start = CLIENT.index("public static func from(dict: [String: Any])")
    model_parse_end = CLIENT.index("// 在线回收站", model_parse_start)
    model_parse = CLIENT[model_parse_start:model_parse_end]
    check("client sorting time comes from stable createdAtMs and missing time stays deterministic", 'dict["createdAtMs"]' in model_parse and "updatedAt = 0" in model_parse and "Date().timeIntervalSince1970 * 1000" not in model_parse)
    check("successful original loads show labels and hide progress", "self.rightBadge.isHidden = false" in load_page and "self.leftBadge.isHidden = false" in load_page and load_page.count("ProgressLabel.isHidden = true") >= 2)
    check("failed original loads remain visibly distinct from ready labels", 'self.rightProgressLabel.text = "原图加载失败"' in load_page and 'self.leftProgressLabel.text = "素材原图加载失败"' in load_page)
    print("PASS iOS compare/status UI contract")


if __name__ == "__main__":
    main()
