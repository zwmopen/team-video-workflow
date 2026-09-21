import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

const androidMain = read("android/app/src/main/java/com/zwm/gallery/MainActivity.java");
const androidDetail = read("android/app/src/main/java/com/zwm/gallery/WorkDetailActivity.java");
const iosContent = read("ios/Album/ContentView.swift");
const iosDetail = read("ios/Album/WorkDetailView.swift");

assert.match(androidMain, /FlowLayout platformRow = new FlowLayout/);
assert.doesNotMatch(androidMain, /Button preview = compactButton\("预览"/);
assert.match(androidMain, /delete\.setText\("删除"\)/);
assert.match(androidMain, /openPreview\(work, imageIndex\)/);
assert.match(androidMain, /EXTRA_IMAGE_INDEX/);
assert.match(androidDetail, /EXTRA_IMAGE_INDEX = "imageIndex"/);
assert.match(androidDetail, /Theme_DeviceDefault_NoActionBar_Fullscreen/);

assert.doesNotMatch(iosContent, /previewButton/);
assert.match(iosContent, /platformContainer\.addArrangedSubview\(platformRow1\)/);
assert.match(iosContent, /platformRow1\.distribution = \.fillEqually/);
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

console.log("work-card-ui: 15 checks passed");
