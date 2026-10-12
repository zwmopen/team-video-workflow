"""静态契约：主页对比卡保持 iOS 级可读尺寸，素材/成品标签叠在图片内。"""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "MainActivity.java").read_text(encoding="utf-8-sig")


def check(name: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(name)
    print(f"PASS {name}")


def main() -> None:
    start = SOURCE.index("private View onlineComparePreviewStrip(")
    end = SOURCE.index("/** 全屏同框对比弹窗", start)
    preview = SOURCE[start:end]

    check("compare cards adapt to the visible strip width like iOS", "getResources().getConfiguration().screenWidthDp - 64" in preview and "Math.min(Math.max(" in preview and ", 256), 360" in preview)
    check("paired thumbnails keep the 3:4 image ratio", "int imageHeightDp = Math.round(imageWidthDp * 4f / 3f);" in preview and "new LinearLayout.LayoutParams(dp(imageWidthDp), dp(imageHeightDp))" in preview)
    check("card height reserves the image and compact header only", "int cardHeightDp = imageHeightDp + 27;" in preview and "new LinearLayout.LayoutParams(dp(cardWidthDp), dp(cardHeightDp))" in preview)
    check("source and output badges overlay the bottom-center of each image", preview.count("Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL") == 2 and "leftBox.addView(leftBadge, leftBadgeParams);" in preview and "rightBox.addView(rightBadge, rightBadgeParams);" in preview)
    check("labels no longer consume a separate row below the images", "badgeRow" not in preview and "card.addView(badgeRow" not in preview)
    print("PASS Android compare-preview strip contract")


if __name__ == "__main__":
    main()
