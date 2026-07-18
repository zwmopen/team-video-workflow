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

    private DocumentTreeImporter() {
    }

    static ImportResult importTree(ContentResolver resolver, Uri tree, WorkLibrary library, File cacheRoot)
            throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        ArrayList<Folder> works = new ArrayList<>();
        scan(resolver, tree, rootId, 0, works);
        int imported = 0;
        int skipped = 0;
        for (Folder work : works) {
            String id = "lark-" + Integer.toHexString(work.documentId.hashCode());
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
        return new ImportResult(imported, skipped, works.size());
    }

    private static void scan(ContentResolver resolver, Uri tree, String documentId, int depth,
                             List<Folder> works) throws Exception {
        if (depth > MAX_DEPTH) return;
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
        for (Item file : files) {
            if (file.mime.startsWith("image/") || WorkRules.isSupportedImage(file.name)) images.add(file);
            if (file.name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                texts.add(file);
                textNames.add(file.name);
            }
        }
        String captionName = WorkRules.chooseCaption(textNames);
        if (!images.isEmpty() && !captionName.isEmpty()) {
            Item caption = null;
            for (Item text : texts) if (captionName.equals(text.name)) caption = text;
            if (caption != null) works.add(new Folder(documentId, leafName(documentId), images, caption, texts.size()));
            return;
        }
        for (Item folder : folders) scan(resolver, tree, folder.documentId, depth + 1, works);
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
        ImportResult(int imported, int skipped, int detected) {
            this.imported = imported;
            this.skipped = skipped;
            this.detected = detected;
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
        final int textCount;
        Folder(String documentId, String name, ArrayList<Item> images, Item caption, int textCount) {
            this.documentId = documentId;
            this.name = name;
            this.images = images;
            this.caption = caption;
            this.textCount = textCount;
        }
    }
}
