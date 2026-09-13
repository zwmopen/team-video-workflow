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
assert.match(iosContent, /thumbnailTapped\(_ sender: UIButton\)/);
assert.match(iosContent, /onPreview: \(\(Int\) -> Void\)\?/);
assert.match(iosContent, /onDelete: \(\(\) -> Void\)\?/);
assert.match(iosDetail, /initialIndex: Int/);
assert.match(iosDetail, /ImagePreviewController\(workName: work\.name, urls: work\.imageURLs, initialIndex: initialIndex\)/);

console.log("work-card-ui: 14 checks passed");
