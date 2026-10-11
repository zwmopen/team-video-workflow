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
    print("PASS iOS compare/status UI contract")


if __name__ == "__main__":
    main()
