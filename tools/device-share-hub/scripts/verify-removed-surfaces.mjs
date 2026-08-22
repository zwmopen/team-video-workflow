import { readdir, readFile } from "node:fs/promises";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = fileURLToPath(new URL(".", import.meta.url));
const projectRoot = join(scriptDirectory, "..");

async function filesUnder(directory) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) await visit(path);
      else result.push(path);
    }
  }
  await visit(directory);
  return result;
}

const rules = [
  {
    directory: join(projectRoot, "android", "app", "src", "main"),
    forbidden: [
      "SYSTEM_ALERT_WINDOW",
      "MediaProjection",
      "ScreenshotSendReceiver",
      "ClipboardActivity",
      "getPrimaryClip",
      "addPrimaryClipChangedListener",
      "OnPrimaryClipChangedListener",
      "READ_MEDIA_IMAGES",
      "READ_MEDIA_VIDEO",
      "READ_MEDIA_VISUAL_USER_SELECTED",
    ],
  },
  {
    directory: join(projectRoot, "ios", "Album"),
    forbidden: ["ScreenshotMonitor", "ClipboardBridge"],
  },
  {
    directory: join(projectRoot, "ios", "project.yml"),
    forbidden: [
      "NSPhotoLibraryUsageDescription",
      "NSScreenCaptureUsageDescription",
    ],
  },
];

const violations = [];
for (const rule of rules) {
  const candidates = rule.directory.endsWith("project.yml")
    ? [rule.directory]
    : await filesUnder(rule.directory);
  for (const path of candidates) {
    const text = await readFile(path, "utf8");
    for (const forbidden of rule.forbidden) {
      if (!text.includes(forbidden)) continue;
      violations.push(`${relative(projectRoot, path)} contains removed surface: ${forbidden}`);
    }
  }
}

if (violations.length > 0) {
  console.error(violations.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Removed screenshot/automatic-clipboard surfaces check passed.");
}
