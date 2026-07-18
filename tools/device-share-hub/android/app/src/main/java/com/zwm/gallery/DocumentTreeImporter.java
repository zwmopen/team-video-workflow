package com.zwm.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Imports valid work folders from a user-granted Lark/document tree. */
final class DocumentTreeImporter {
    private static final int MAX_DEPTH = 8;
    private static final long MAX_TEXT_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_SCAN_NOTES = 24;

    private DocumentTreeImporter() {
    }

    static ImportResult importTree(ContentResolver resolver, Uri tree, WorkLibrary library, File cacheRoot)
            throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        ScanStats stats = new ScanStats();
        ArrayList<Folder> works = new ArrayList<>();
        scan(resolver, tree, rootId, 0, works, stats);
        int imported = 0;
        int skipped = 0;
        for (Folder work : works) {
            String id = work.marker == null ? "lark-" + Integer.toHexString(work.documentId.hashCode())
                    : readText(resolver, work.marker.uri).trim();
            if (!id.matches("[A-Za-z0-9._-]{1,120}")) id = "lark-" + Integer.toHexString(work.documentId.hashCode());
            if (library.contains(id)) {
                skipped++;
                continue;
            }
            File temporary = new File(cacheRoot, id);
            deleteTree(temporary);
            if (!temporary.mkdirs()) throw new IOException("无法创建读取缓存");
            try {
                ArrayList<File> images = new ArrayList<>();
                work.images.sort((left, right) -> WorkRules.compareNatural(left.name, right.name));
                for (Item image : work.images) {
                    File target = new File(temporary, image.name.replace('/', '_').replace('\\', '_'));
                    copy(resolver, image.uri, target);
                    images.add(target);
                }
                String text = readText(resolver, work.caption.uri);
                String warning = work.textCount > 1
                        ? "检测到多个 TXT，已使用“" + work.caption.name + "”" : "";
                library.importWork(id, work.name, text, images, warning);
                imported++;
            } finally {
                deleteTree(temporary);
            }
        }
        return new ImportResult(imported, skipped, works.size(), stats.scannedFolders,
                stats.aggregateFolders, stats.notes.toString());
    }

    private static int scan(ContentResolver resolver, Uri tree, String documentId, int depth,
                            List<Folder> works, ScanStats stats) throws Exception {
        if (depth > MAX_DEPTH) return 0;
        stats.scannedFolders++;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        ArrayList<Item> files = new ArrayList<>();
        ArrayList<Item> folders = new ArrayList<>();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取所选文件夹");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                Item item = new Item(id, name == null ? "未命名" : name, mime == null ? "" : mime, uri);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) folders.add(item);
                else files.add(item);
            }
        }

        ArrayList<Item> images = new ArrayList<>();
        ArrayList<String> textNames = new ArrayList<>();
        ArrayList<Item> texts = new ArrayList<>();
        Item marker = null;
        for (Item file : files) {
            if (".zwm-work-id".equals(file.name)) {
                marker = file;
                continue;
            }
            if (file.mime.startsWith("image/") || WorkRules.isSupportedImage(file.name)) images.add(file);
            if (file.name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                texts.add(file);
                textNames.add(file.name);
            }
        }
        String captionName = WorkRules.chooseCaption(textNames);
        stats.addNote(depth, leafName(documentId), folders.size(), files.size(), images.size(), texts.size(), captionName);
        int childWorks = 0;
        for (Item folder : folders) childWorks += scan(resolver, tree, folder.documentId, depth + 1, works, stats);
        if (!images.isEmpty() && !captionName.isEmpty()) {
            Item caption = null;
            for (Item text : texts) if (captionName.equals(text.name)) caption = text;
            if (caption != null) {
                if (childWorks == 0) {
                    works.add(new Folder(documentId, leafName(documentId), images, caption, marker, texts.size()));
                    return 1;
                }
                stats.aggregateFolders++;
            }
        }
        return childWorks;
    }

    private static String readText(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法读取 TXT");
            byte[] buffer = new byte[16 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_TEXT_BYTES) throw new IOException("TXT 文案过大");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF
                    && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        }
    }

    private static void copy(ContentResolver resolver, Uri uri, File target) throws IOException {
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("无法读取图片");
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
        }
    }

    private static String leafName(String documentId) {
        int slash = documentId.lastIndexOf('/');
        return slash < 0 ? documentId.substring(documentId.lastIndexOf(':') + 1) : documentId.substring(slash + 1);
    }

    private static void deleteTree(File target) {
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (target.exists()) target.delete();
    }

    static final class ImportResult {
        final int imported;
        final int skipped;
        final int detected;
        final int scannedFolders;
        final int aggregateFolders;
        final String scanNotes;
        ImportResult(int imported, int skipped, int detected, int scannedFolders,
                     int aggregateFolders, String scanNotes) {
            this.imported = imported;
            this.skipped = skipped;
            this.detected = detected;
            this.scannedFolders = scannedFolders;
            this.aggregateFolders = aggregateFolders;
            this.scanNotes = scanNotes;
        }
    }

    private static final class ScanStats {
        int scannedFolders;
        int aggregateFolders;
        int noteCount;
        final StringBuilder notes = new StringBuilder();

        void addNote(int depth, String name, int folderCount, int fileCount,
                     int imageCount, int textCount, String captionName) {
            if (noteCount >= MAX_SCAN_NOTES) return;
            if (notes.length() > 0) notes.append(" | ");
            notes.append("d").append(depth)
                    .append(":").append(compact(name))
                    .append(" dirs=").append(folderCount)
                    .append(" files=").append(fileCount)
                    .append(" img=").append(imageCount)
                    .append(" txt=").append(textCount);
            if (!captionName.isEmpty()) notes.append(" cap=").append(compact(captionName));
            noteCount++;
        }

        private String compact(String value) {
            String normalized = value == null ? "" : value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
            return normalized.length() <= 24 ? normalized : normalized.substring(0, 24) + "…";
        }
    }

    private static final class Item {
        final String documentId;
        final String name;
        final String mime;
        final Uri uri;
        Item(String documentId, String name, String mime, Uri uri) {
            this.documentId = documentId;
            this.name = name;
            this.mime = mime;
            this.uri = uri;
        }
    }

    private static final class Folder {
        final String documentId;
        final String name;
        final ArrayList<Item> images;
        final Item caption;
        final Item marker;
        final int textCount;
        Folder(String documentId, String name, ArrayList<Item> images, Item caption, Item marker, int textCount) {
            this.documentId = documentId;
            this.name = name;
            this.images = images;
            this.caption = caption;
            this.marker = marker;
            this.textCount = textCount;
        }
    }
}
