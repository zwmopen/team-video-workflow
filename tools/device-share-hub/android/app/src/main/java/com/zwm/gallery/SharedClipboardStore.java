package com.zwm.gallery;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Local-first clipboard and reusable phrase store with last-write-wins merge semantics. */
final class SharedClipboardStore {
    static final String KIND_CLIPBOARD = "clipboard";
    static final String KIND_PHRASE = "phrase";
    private static final int MAX_TEXT_LENGTH = 20_000;

    private final File root;

    SharedClipboardStore(File root) throws IOException {
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs()) {
            throw new IOException("无法创建共享剪切板目录");
        }
    }

    synchronized Item put(String id, String kind, String text, long updatedAt) throws IOException {
        String safeId = normalizeId(id);
        String safeKind = normalizeKind(kind);
        String safeText = normalizeText(text);
        Item existing = read(safeId);
        if (existing != null && existing.updatedAt > updatedAt) return existing;
        Item item = new Item(safeId, safeKind, safeText, Math.max(1, updatedAt), false);
        write(item);
        return item;
    }

    synchronized Item add(String deviceId, String kind, String text, long updatedAt)
            throws IOException {
        String prefix = normalizeDeviceId(deviceId);
        return put(prefix + "-" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT),
                kind, text, updatedAt);
    }

    synchronized boolean putIfAbsent(String id, String kind, String text, long updatedAt)
            throws IOException {
        String safeId = normalizeId(id);
        if (read(safeId) != null) return false;
        put(safeId, kind, text, updatedAt);
        return true;
    }

    synchronized Item delete(String id, long updatedAt) throws IOException {
        String safeId = normalizeId(id);
        Item existing = read(safeId);
        if (existing == null) return null;
        if (existing.updatedAt > updatedAt) return existing;
        Item tombstone = new Item(existing.id, existing.kind, "",
                Math.max(1, updatedAt), true);
        write(tombstone);
        return tombstone;
    }

    synchronized int merge(List<Item> incoming) throws IOException {
        int changed = 0;
        for (Item candidate : incoming) {
            if (candidate == null) continue;
            Item normalized = new Item(
                    normalizeId(candidate.id),
                    normalizeKind(candidate.kind),
                    candidate.deleted ? "" : normalizeText(candidate.text),
                    Math.max(1, candidate.updatedAt),
                    candidate.deleted);
            Item existing = read(normalized.id);
            if (existing != null && existing.updatedAt >= normalized.updatedAt) continue;
            write(normalized);
            changed++;
        }
        return changed;
    }

    synchronized List<Item> visible(String kind) throws IOException {
        String safeKind = normalizeKind(kind);
        ArrayList<Item> items = new ArrayList<>();
        for (Item item : all()) {
            if (!item.deleted && safeKind.equals(item.kind)) items.add(item);
        }
        return items;
    }

    synchronized List<Item> all() throws IOException {
        ArrayList<Item> items = new ArrayList<>();
        File[] files = root.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files != null) {
            for (File file : files) {
                Item item = readFile(file);
                if (item != null) items.add(item);
            }
        }
        items.sort((left, right) -> {
            int time = Long.compare(right.updatedAt, left.updatedAt);
            return time != 0 ? time : left.id.compareTo(right.id);
        });
        return Collections.unmodifiableList(items);
    }

    /** Sync reusable phrases completely, but only the newest clipboard value. */
    synchronized List<Item> syncSnapshot() throws IOException {
        ArrayList<Item> result = new ArrayList<>();
        boolean clipboardIncluded = false;
        for (Item item : all()) {
            if (KIND_PHRASE.equals(item.kind)) {
                result.add(item);
            } else if (!clipboardIncluded && !item.deleted) {
                result.add(item);
                clipboardIncluded = true;
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Keep at most one visible clipboard value from a given sending device. */
    synchronized int retainLatestClipboardFrom(String deviceId, long updatedAt) throws IOException {
        String prefix = normalizeDeviceId(deviceId) + "-";
        boolean retained = false;
        int removed = 0;
        for (Item item : visible(KIND_CLIPBOARD)) {
            if (!item.id.startsWith(prefix)) continue;
            if (!retained) {
                retained = true;
                continue;
            }
            delete(item.id, Math.max(updatedAt + removed, item.updatedAt + 1));
            removed++;
        }
        return removed;
    }

    synchronized Item newestClipboard() throws IOException {
        List<Item> items = visible(KIND_CLIPBOARD);
        return items.isEmpty() ? null : items.get(0);
    }

    synchronized int trimVisible(String kind, int maximum, long updatedAt) throws IOException {
        List<Item> items = visible(kind);
        int trimmed = 0;
        for (int index = Math.max(0, maximum); index < items.size(); index++) {
            delete(items.get(index).id, updatedAt + trimmed);
            trimmed++;
        }
        return trimmed;
    }

    private Item read(String id) throws IOException {
        return readFile(fileFor(id));
    }

    private Item readFile(File file) throws IOException {
        if (!file.isFile()) return null;
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        try {
            return new Item(
                    normalizeId(properties.getProperty("id", "")),
                    normalizeKind(properties.getProperty("kind", "")),
                    normalizeText(properties.getProperty("text", "")),
                    Long.parseLong(properties.getProperty("updatedAt", "0")),
                    Boolean.parseBoolean(properties.getProperty("deleted", "false")));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void write(Item item) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", item.id);
        properties.setProperty("kind", item.kind);
        properties.setProperty("text", item.text);
        properties.setProperty("updatedAt", Long.toString(item.updatedAt));
        properties.setProperty("deleted", Boolean.toString(item.deleted));
        File target = fileFor(item.id);
        File temp = new File(root, "." + item.id + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            properties.store(output, null);
            output.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File fileFor(String id) throws IOException {
        File file = new File(root, normalizeId(id) + ".properties").getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("剪切板记录路径无效");
        return file;
    }

    private static String normalizeKind(String kind) {
        if (KIND_CLIPBOARD.equals(kind) || KIND_PHRASE.equals(kind)) return kind;
        throw new IllegalArgumentException("剪切板记录类型无效");
    }

    private static String normalizeId(String id) {
        String value = id == null ? "" : id.trim();
        if (!value.matches("[A-Za-z0-9._-]{6,160}")) {
            throw new IllegalArgumentException("剪切板记录 ID 无效");
        }
        return value;
    }

    private static String normalizeDeviceId(String deviceId) {
        String value = deviceId == null ? "device" :
                deviceId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.length() > 80) value = value.substring(0, 80);
        return value.length() < 3 ? "device" : value;
    }

    private static String normalizeText(String text) {
        String value = text == null ? "" : text.replace("\u0000", "");
        if (value.length() > MAX_TEXT_LENGTH) value = value.substring(0, MAX_TEXT_LENGTH);
        return value;
    }

    static final class Item {
        final String id;
        final String kind;
        final String text;
        final long updatedAt;
        final boolean deleted;

        Item(String id, String kind, String text, long updatedAt, boolean deleted) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.updatedAt = updatedAt;
            this.deleted = deleted;
        }
    }
}
