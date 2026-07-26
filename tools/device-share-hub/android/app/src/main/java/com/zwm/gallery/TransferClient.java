package com.zwm.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class TransferClient {
    interface Progress { void update(int percent, String text); }

    private final ContentResolver resolver;
    private final File cacheDir;

    TransferClient(ContentResolver resolver, File cacheDir) {
        this.resolver = resolver;
        this.cacheDir = cacheDir;
    }

    void sendFiles(PeerDevice peer, List<Uri> uris, Progress progress) throws Exception {
        if (uris.isEmpty()) throw new IOException("没有选择文件");
        ArrayList<Source> sources = new ArrayList<>();
        for (Uri uri : uris) sources.add(source(uri));
        send(peer, sources, progress);
    }

    void sendLocalFiles(PeerDevice peer, List<File> files, Progress progress) throws Exception {
        if (files.isEmpty()) throw new IOException("没有选择文件");
        ArrayList<Source> sources = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) throw new IOException("文件不存在：" + file.getName());
            sources.add(new Source(Uri.fromFile(file), file.getName(),
                    mimeForName(file.getName()), file.length(), file));
        }
        send(peer, sources, progress);
    }

    void sendFolder(PeerDevice peer, Uri tree, Progress progress) throws Exception {
        progress.update(0, "正在整理文件夹…");
        File archive = createFolderArchive(tree);
        try {
            ArrayList<Source> sources = new ArrayList<>();
            sources.add(new Source(Uri.fromFile(archive), archive.getName(), "application/zip", archive.length(), archive));
            send(peer, sources, progress);
        } finally {
            archive.delete();
        }
    }

    private void send(PeerDevice peer, List<Source> sources, Progress progress) throws Exception {
        String taskId = "android-" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        JSONObject task = new JSONObject().put("taskId", taskId).put("text", "").put("fileCount", sources.size());
        request(peer, "POST", "/v2/tasks", "application/json", task.toString().getBytes(StandardCharsets.UTF_8), null);
        long total = 0;
        for (Source source : sources) total += source.size;
        long completed = 0;
        try {
            for (int index = 0; index < sources.size(); index++) {
                Source source = sources.get(index);
                String sha = sha256(source);
                URL url = new URL("http", peer.ip, peer.port, "/v2/tasks/" + taskId + "/files/" + index);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("PUT");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(source.size);
                connection.setRequestProperty("Content-Type", source.mime);
                connection.setRequestProperty("X-File-Name", Uri.encode(source.name));
                connection.setRequestProperty("X-File-Mime", source.mime);
                connection.setRequestProperty("X-File-Sha256", sha);
                long base = completed;
                try (InputStream input = source.open(resolver);
                     OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                    byte[] buffer = new byte[128 * 1024];
                    long sent = 0;
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        output.write(buffer, 0, count);
                        sent += count;
                        int percent = total == 0 ? 100 : (int) Math.min(100, (base + sent) * 100 / total);
                        progress.update(percent, "WiFi 传送中 · “" + peer.name + "” " + percent + "%");
                    }
                }
                check(connection);
                completed += source.size;
            }
            request(peer, "POST", "/v2/tasks/" + taskId + "/commit", "text/plain", new byte[0], null);
            progress.update(100, "WiFi 传送完成 · “" + peer.name + "”");
        } catch (Exception error) {
            try { request(peer, "POST", "/v2/tasks/" + taskId + "/cancel", "text/plain", new byte[0], null); }
            catch (Exception ignored) { }
            throw error;
        }
    }

    private void request(PeerDevice peer, String method, String path, String type, byte[] body,
                         java.util.Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http", peer.ip, peer.port, path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", type);
        if (headers != null) for (java.util.Map.Entry<String, String> header : headers.entrySet())
            connection.setRequestProperty(header.getKey(), header.getValue());
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        check(connection);
    }

    private static void check(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) { connection.disconnect(); return; }
        InputStream stream = connection.getErrorStream();
        String detail = stream == null ? "" : new String(readLimited(stream, 4096), StandardCharsets.UTF_8).trim();
        connection.disconnect();
        throw new IOException(detail.isEmpty() ? "对方返回错误 " + status : detail);
    }

    private Source source(Uri uri) throws IOException {
        String name = "文件";
        long size = -1;
        String mime = resolver.getType(uri);
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        }
        if (size < 0) {
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取“" + name + "”");
                size = 0;
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) size += count;
            }
        }
        return new Source(uri, name, mime == null ? "application/octet-stream" : mime, size, null);
    }

    private String sha256(Source source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = source.open(resolver)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private File createFolderArchive(Uri tree) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        String rootName = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), "文件夹");
        ArrayList<Doc> files = new ArrayList<>();
        collect(tree, rootId, safe(rootName), files);
        File output = new File(cacheDir, "album-folder-" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Doc file : files) {
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream input = resolver.openInputStream(file.uri)) {
                    if (input == null) throw new IOException("无法读取“" + file.path + "”");
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) { crc.update(buffer, 0, count); size += count; }
                }
                ZipEntry entry = new ZipEntry(file.path);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(size);
                entry.setCompressedSize(size);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                try (InputStream input = resolver.openInputStream(file.uri)) {
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
        return output;
    }

    private void collect(Uri tree, String parentId, String relative, List<Doc> files) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取所选文件夹");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = safe(cursor.getString(1));
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) collect(tree, id, relative + "/" + name, files);
                else files.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), relative + "/" + name));
                if (files.size() > 10_000) throw new IOException("文件夹内文件超过 10000 个，请分批传送");
            }
        }
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return fallback;
    }

    private static String safe(String name) {
        String value = name == null ? "未命名" : name.replace('/', '_').replace('\\', '_').trim();
        return value.isEmpty() ? "未命名" : value;
    }

    private static byte[] readLimited(InputStream input, int max) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while (output.size() < max && (count = input.read(buffer, 0, Math.min(buffer.length, max - output.size()))) > 0)
            output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String mimeForName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static final class Doc {
        final Uri uri; final String path;
        Doc(Uri uri, String path) { this.uri = uri; this.path = path; }
    }

    private static final class Source {
        final Uri uri; final String name; final String mime; final long size; final File file;
        Source(Uri uri, String name, String mime, long size, File file) {
            this.uri = uri; this.name = name; this.mime = mime; this.size = size; this.file = file;
        }
        InputStream open(ContentResolver resolver) throws IOException {
            InputStream input = file == null ? resolver.openInputStream(uri) : new FileInputStream(file);
            if (input == null) throw new IOException("无法读取“" + name + "”");
            return new BufferedInputStream(input);
        }
    }
}
