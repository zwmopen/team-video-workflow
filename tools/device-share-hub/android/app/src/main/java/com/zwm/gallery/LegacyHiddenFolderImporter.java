package com.zwm.gallery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Android 10 fallback for dot-prefixed folders omitted by some Huawei document providers. */
final class LegacyHiddenFolderImporter {
    private static final int MAX_DEPTH = 8;
    private static final long MAX_TEXT_BYTES = 2L * 1024L * 1024L;

    private LegacyHiddenFolderImporter() {
    }

    static Result importFrom(File root, WorkLibrary library) throws Exception {
        return importFrom(root, library, true);
    }

    static Result importAllFrom(File root, WorkLibrary library) throws Exception {
        return importFrom(root, library, false);
    }

    private static Result importFrom(File root, WorkLibrary library, boolean hiddenOnly) throws Exception {
        Result result = new Result();
        if (root == null || !root.isDirectory()) return result;
        scan(root.getCanonicalFile(), root.getCanonicalFile(), 0, library, result, hiddenOnly);
        return result;
    }

    private static void scan(File root, File directory, int depth, WorkLibrary library, Result result)
            throws Exception {
        scan(root, directory, depth, library, result, true);
    }

    private static void scan(File root, File directory, int depth, WorkLibrary library, Result result,
                             boolean hiddenOnly) throws Exception {
        if (depth > MAX_DEPTH) return;
        if (!directory.equals(root) && ExternalTrashManager.TRASH_NAME.equals(directory.getName())) return;
        result.scannedFolders++;
        File[] children = directory.listFiles();
        if (children == null) return;

        ArrayList<File> images = new ArrayList<>();
        ArrayList<File> texts = new ArrayList<>();
        ArrayList<String> textNames = new ArrayList<>();
        ArrayList<File> folders = new ArrayList<>();
        File marker = null;
        for (File child : children) {
            if (child.isDirectory()) {
                folders.add(child);
            } else if (child.isFile()) {
                if (".zwm-work-id".equals(child.getName())) marker = child;
                else if (WorkRules.isSupportedImage(child.getName())) images.add(child);
                else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    texts.add(child);
                    textNames.add(child.getName());
                }
            }
        }

        for (File folder : folders) scan(root, folder, depth + 1, library, result, hiddenOnly);

        // The normal SAF importer handles visible folders. This fallback only fills Huawei's hidden gap.
        if ((hiddenOnly && !directory.getName().startsWith("."))
                || images.isEmpty() || texts.isEmpty()) return;
        String captionName = WorkRules.chooseCaption(textNames);
        File caption = null;
        for (File text : texts) if (captionName.equals(text.getName())) caption = text;
        if (caption == null) return;

        result.detected++;
        String sourceRelativePath = relativePath(root, directory);
        String category = WorkCategory.fromPath(sourceRelativePath);
        result.detectedRelativePaths.add(sourceRelativePath);
        WorkLibrary.WorkEntry existingSource = library.findBySourceRelativePath(sourceRelativePath);
        if (existingSource != null) {
            library.updateSourceReference(existingSource.id, "", "", sourceRelativePath);
            library.updateCategory(existingSource.id, category);
            result.skipped++;
            return;
        }
        String id = marker == null ? "huawei-hidden-" + Integer.toHexString(relativePath(root, directory).hashCode())
                : readText(marker).trim();
        if (!id.matches("[A-Za-z0-9._-]{1,120}")) {
            id = "huawei-hidden-" + Integer.toHexString(relativePath(root, directory).hashCode());
        }
        String captionText = readText(caption);
        WorkLibrary.WorkEntry matchingContent = library.findByContent(captionText, images);
        if (library.contains(id) || matchingContent != null) {
            String existingId = matchingContent == null ? id : matchingContent.id;
            library.updateSourceReference(existingId, "", "", sourceRelativePath);
            library.updateCategory(existingId, category);
            result.skipped++;
            return;
        }
        String name = directory.getName().replaceFirst("^\\.+", "");
        String warning = texts.size() > 1 ? "检测到多个 TXT，已使用“" + captionName + "”" : "";
        library.importWork(id, name.isEmpty() ? directory.getName() : name,
                captionText, images, warning, "", "", sourceRelativePath, category);
        result.imported++;
    }

    private static String relativePath(File root, File directory) throws IOException {
        return root.toPath().relativize(directory.getCanonicalFile().toPath()).toString();
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_TEXT_BYTES) throw new IOException("TXT 文档过大");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF
                    && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        }
    }

    static final class Result {
        int scannedFolders;
        int detected;
        int imported;
        int skipped;
        final Set<String> detectedRelativePaths = new HashSet<>();
    }
}
