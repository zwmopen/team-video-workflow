package com.zwm.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Preserves general files and complete ZIP/folder trees in the user-authorized receive folder. */
final class DocumentTreeExporter {
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_ENTRY_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L * 1024L;

    private DocumentTreeExporter() {
    }

    static ExportResult exportZip(ContentResolver resolver, Uri tree, File archive, String batchId)
            throws Exception {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Map<String, Uri> directories = new HashMap<>();
        directories.put("", root);
        int fileCount = 0;
        long totalBytes = 0;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ARCHIVE_ENTRIES) throw new IOException("压缩包文件数量超过 10000 个");
                String path = validateArchivePath(entry.getName());
                if (path.isEmpty()) continue;
                String normalized = trimTrailingSlash(path);
                if (normalized.isEmpty()) continue;
                if (entry.isDirectory()) {
                    ensureDirectory(resolver, tree, directories, normalized);
                    continue;
                }
                long declared = entry.getSize();
                if (declared > MAX_ENTRY_BYTES) throw new IOException("压缩包中单个文件超过 4GB：" + baseName(path));
                String parentPath = parentPath(normalized);
                Uri parent = ensureDirectory(resolver, tree, directories, parentPath);
                Uri target = DocumentsContract.createDocument(
                        resolver, parent, mimeFor(baseName(normalized)), safeDisplayName(baseName(normalized)));
                if (target == null) throw new IOException("无法创建文件：" + baseName(normalized));
                long copied = copyBounded(resolver, target, zip.getInputStream(entry), MAX_ENTRY_BYTES);
                totalBytes += copied;
                if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("压缩包解压后总大小超过 20GB");
                fileCount++;
            }
        }
        return new ExportResult(fileCount, Math.max(0, directories.size() - 1));
    }

    static void exportFile(ContentResolver resolver, Uri tree, File source, String displayName, String mime)
            throws IOException {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri target = DocumentsContract.createDocument(resolver, root,
                mime == null || mime.isEmpty() ? mimeFor(displayName) : mime, safeDisplayName(displayName));
        if (target == null) throw new IOException("无法创建文件：" + displayName);
        try (InputStream input = new FileInputStream(source)) {
            copyBounded(resolver, target, input, MAX_ENTRY_BYTES);
        }
    }

    static String validateArchivePath(String raw) {
        String path = raw == null ? "" : raw.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty()) return "";
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP 包含非法路径：" + raw);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) throw new IllegalArgumentException("ZIP 包含越界路径：" + raw);
        }
        return path;
    }

    private static Uri ensureDirectory(ContentResolver resolver, Uri tree, Map<String, Uri> cache, String path)
            throws IOException {
        if (path == null || path.isEmpty()) return cache.get("");
        Uri cached = cache.get(path);
        if (cached != null) return cached;
        String parentPath = parentPath(path);
        Uri parent = ensureDirectory(resolver, tree, cache, parentPath);
        String name = safeDisplayName(baseName(path));
        Uri existing = findDirectory(resolver, tree, parent, name);
        Uri created = existing != null ? existing : DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("无法创建文件夹：" + name);
        cache.put(path, created);
        return created;
    }

    private static Uri findDirectory(ContentResolver resolver, Uri tree, Uri parent, String name) {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static long copyBounded(ContentResolver resolver, Uri target, InputStream source, long limit)
            throws IOException {
        try (InputStream input = source; OutputStream output = resolver.openOutputStream(target, "w")) {
            if (output == null) throw new IOException("无法写入接收文件夹");
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > limit) throw new IOException("解压文件超过 4GB");
                output.write(buffer, 0, count);
            }
            return total;
        }
    }

    private static String trimTrailingSlash(String path) {
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String safeDisplayName(String value) {
        String safe = value.replace('/', '_').replace('\\', '_').trim();
        return safe.isEmpty() ? "未命名" : safe;
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    static final class ExportResult {
        final int files;
        final int directories;

        ExportResult(int files, int directories) {
            this.files = files;
            this.directories = directories;
        }
    }
}
