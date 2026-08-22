import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sourcePath = path.resolve(here, "../windows-native/src/main.cpp");
const source = fs.readFileSync(sourcePath, "utf8");

const uploadStart = source.indexOf("bool UploadToDevice(");
const uploadEnd = source.indexOf("// Shell SendTo is a background entry point", uploadStart);
if (uploadStart < 0 || uploadEnd < 0) throw new Error("UploadToDevice function boundary missing");
const upload = source.slice(uploadStart, uploadEnd);
if (!upload.includes("return transferSucceeded;")) {
  throw new Error("UploadToDevice must return an explicit success result");
}

const updateStart = source.indexOf("void MaybeAutoMobileUpdate()");
const updateEnd = source.indexOf("void RefreshDeviceList()", updateStart);
if (updateStart < 0 || updateEnd < 0) throw new Error("MaybeAutoMobileUpdate function boundary missing");
const update = source.slice(updateStart, updateEnd);
const successBranch = update.indexOf("if (succeeded)");
const deliveredWrite = update.indexOf("MobileUpdateDeliveredSettingKey(selected.id)");
if (successBranch < 0 || deliveredWrite < 0 || successBranch > deliveredWrite) {
  throw new Error("durable mobile-update delivery state must be written only after success");
}
if (!update.includes("auto_mobile_update_failed_retryable")) {
  throw new Error("failed mobile updates must emit a retryable diagnostic");
}
if (update.includes("MobileUpdateSentSettingKey")) {
  throw new Error("legacy pre-transfer sent marker must not suppress retries");
}

const beaconStart = source.indexOf('if (parts.size() >= 15)');
if (beaconStart < 0 || !source.slice(beaconStart, beaconStart + 700).includes("conversionCount")) {
  throw new Error("Windows discovery must parse optional precise inventory beacon fields");
}

console.log("Automatic mobile update retry and inventory beacon checks passed.");
