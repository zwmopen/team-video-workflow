import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

const androidMain = read("android/app/src/main/java/com/zwm/gallery/MainActivity.java");
const androidDetail = read("android/app/src/main/java/com/zwm/gallery/WorkDetailActivity.java");
const androidParser = read("android/app/src/main/java/com/zwm/gallery/PlatformCopyParser.java");
const iosContent = read("ios/Album/ContentView.swift");
const iosDetail = read("ios/Album/WorkDetailView.swift");
const iosParser = read("ios/Album/PlatformCopyParser.swift");

assert.match(androidMain, /FlowLayout platformRow = new FlowLayout/);
assert.doesNotMatch(androidMain, /Button preview = compactButton\("预览"/);
assert.match(androidMain, /delete\.setText\("删除"\)/);
assert.match(androidMain, /openPreview\(work, imageIndex\)/);
assert.match(androidMain, /EXTRA_IMAGE_INDEX/);
assert.match(androidDetail, /EXTRA_IMAGE_INDEX = "imageIndex"/);
assert.match(androidDetail, /Theme_DeviceDefault_NoActionBar_Fullscreen/);

assert.doesNotMatch(iosContent, /previewButton/);
// 【2026-09-23 修正】原先这里断言 `platformContainer.addArrangedSubview(platformRow1)` 与
// `platformRow1.distribution = .fillEqually` —— 那是 DSH-091 C3 之前「单行横向滚动 + 等分」的旧结构。
// C3 把平台区换成 `PlatformFlowView`（横向排满自动折行）后，这两条与本守卫同名的实现彻底对不上，
// 自那次重构起长期为红 —— 一条长期红的守卫 ＝ 一道永不触发的闸门，危害与「没有守卫」完全相同。
// 现在只锁定当前真正的不变量：平台行由 `PlatformFlowView` 承载，且行高/间距与
// `sizeForItemAt` 的卡片高度预估同源（两边必须来自 `WorkCell` 的同一批常量）。
assert.match(iosContent, /platformContainer\.addArrangedSubview\(platformRow\)/);
assert.match(iosContent, /platformRow\.rowHeight = WorkCell\.platformRowHeight/);
assert.match(iosContent, /static let platformRowHeight: CGFloat = 36/);
// 【2026-09-21 修正】原先这里硬编码 `thumbnailTapped\(_ sender: UIButton\)`，
// 而 iOS 卡片早就改用 `ThumbnailButton` 子类（`ThumbnailButton(type: .custom)`）承载缩略图，
// 于是断言与本守卫同名的实现彻底对不上，自 2026-09-14 起长期为红
// —— 一条长期红的守卫 ＝ 一道永不触发的闸门，危害与「没有守卫」完全相同。
// 现在不绑定具体控件类型（控件类型本来就允许演进），只锁定真正的不变量：
// 「缩略图点击 → 用被点那张图的下标回调 onPreview」。
assert.match(iosContent, /thumbnailTapped\(_ sender: \w+\)\s*\{\s*onPreview\?\(sender\.tag\)/);
assert.match(iosContent, /onPreview: \(\(Int\) -> Void\)\?/);
assert.match(iosContent, /onDelete: \(\(\) -> Void\)\?/);
assert.match(iosDetail, /initialIndex: Int/);
assert.match(iosDetail, /ImagePreviewController\(workName: work\.name, urls: work\.imageURLs, initialIndex: initialIndex\)/);

// 【2026-09-23 用户口径】11 个版本**平级**，按钮顺序 = `文案.txt` 里版本块出现的先后顺序。
// Android 多版本时必须**原样返回**解析结果：不能把「抖音」那一版摘出来追加到末尾，也不能按
// `getButtonRank` 重排（这两处都会把已排在文案最前的抖音版甩到整行最后）；
// 与 iOS `if !multiItems.isEmpty { return multiItems }` 行为对齐。
assert.match(androidMain, /return new ArrayList<>\(rawPlatforms\);/);
assert.doesNotMatch(androidMain, /result\.add\(douyinItem\)/);
// 版本名**不截断到 4 字**（按钮改由容器 `FlowLayout` / `PlatformFlowView` 折行承载），两端一致。
assert.doesNotMatch(androidMain, /button\.setMaxLines\(1\)/);
assert.doesNotMatch(androidParser, /substring\(0, 4\)/);
assert.doesNotMatch(iosParser, /prefix\(4\)/);

console.log("work-card-ui: 21 checks passed");
