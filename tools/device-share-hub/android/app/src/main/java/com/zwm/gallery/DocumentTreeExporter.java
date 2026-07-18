package com.zwm.gallery;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Writes an incoming ZIP's recognized works into a user-authorized visible folder. */
final class DocumentTreeExporter {
    private DocumentTreeExporter() {
    }

    static int exportZip(ContentResolver resolver, Uri tree, File archive, String batchId) throws Exception {
        List<ZipWorkScanner.WorkPlan> plans;
        try (InputStream input = new FileInputStream(archive)) {
            plans = ZipWorkScanner.scan(input);
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        int exported = 0;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
                ZipWorkScanner.WorkPlan plan = plans.get(planIndex);
                Uri folder = DocumentsContract.createDocument(
                        resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, safeDisplayName(plan.name));
                if (folder == null) throw new IOException("无法在作品文件夹中创建“" + plan.name + "”");
                writeMarker(resolver, folder, batchId + "-" + (planIndex + 1));
                copyEntry(resolver, folder, zip, plan.captionEntry, "text/plain", baseName(plan.captionEntry));
                for (String image : plan.imageEntries) {
                    copyEntry(resolver, folder, zip, image, mimeFor(baseName(image)), baseName(image));
                }
                exported++;
            }
        }
        return exported;
    }

    private static void writeMarker(ContentResolver resolver, Uri parent, String workId) throws IOException {
        Uri marker = DocumentsContract.createDocument(resolver, parent, "text/plain", ".zwm-work-id");
        if (marker == null) throw new IOException("无法写入作品去重标记");
        try (OutputStream output = resolver.openOutputStream(marker, "w")) {
            if (output == null) throw new IOException("无法写入作品去重标记");
            output.write(workId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyEntry(ContentResolver resolver, Uri parent, ZipFile zip, String entryName,
                                  String mime, String displayName) throws IOException {
        ZipEntry entry = findEntry(zip, entryName);
        Uri target = DocumentsContract.createDocument(resolver, parent, mime, safeDisplayName(displayName));
        if (target == null) throw new IOException("无法创建文件：" + displayName);
        try (InputStream input = zip.getInputStream(entry); OutputStream output = resolver.openOutputStream(target, "w")) {
            if (output == null) throw new IOException("无法写入文件：" + displayName);
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String normalizedName) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().replace('\\', '/').equals(normalizedName)) return entry;
        }
        throw new IOException("压缩包缺少文件：" + normalizedName);
    }

    private static String safeDisplayName(String value) {
        String safe = value.replace('/', '_').replace('\\', '_').trim();
        return safe.isEmpty() ? "未命名作品" : safe;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        return "application/octet-stream";
    }
}
