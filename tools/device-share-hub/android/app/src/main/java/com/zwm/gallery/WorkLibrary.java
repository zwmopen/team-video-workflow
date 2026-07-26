package com.zwm.gallery;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Persistent, app-private queue of works and its recoverable trash. */
public final class WorkLibrary {
    private static final String META_FILE = "meta.properties";
    private static final Object MIGRATION_LOCK = new Object();
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final File root;
    private final File activeRoot;
    private final File trashRoot;
    private final int reconciledDuplicates;

    public WorkLibrary(File root) throws IOException {
        this.root = root.getCanonicalFile();
        this.activeRoot = new File(this.root, "active").getCanonicalFile();
        this.trashRoot = new File(this.root, "trash").getCanonicalFile();
        int merged;
        synchronized (MIGRATION_LOCK) {
            ensureDirectory(activeRoot);
            ensureDirectory(trashRoot);
            merged = collapseLegacyDuplicates(activeRoot)
                    + collapseLegacyDuplicates(trashRoot)
                    + collapseDuplicatesAcrossActiveAndTrash()
                    + collapseContentDuplicatesAcrossActiveAndTrash();
        }
        this.reconciledDuplicates = merged;
    }

    public synchronized WorkEntry importWork(
            String id,
            String name,
            String text,
            List<File> sourceImages,
            String warning) throws IOException {
        return importWork(id, name, text, sourceImages, warning, "", "", "");
    }

    public synchronized WorkEntry importWork(
            String id,
            String name,
            String text,
            List<File> sourceImages,
            String warning,
            String sourceDocumentId,
            String sourceParentDocumentId,
            String sourceRelativePath) throws IOException {
        validateId(id);
        if (sourceImages == null || sourceImages.isEmpty()) {
            throw new IOException("作品中没有可导入的图片");
        }
        File destination = child(activeRoot, id);
        if (destination.exists() || child(trashRoot, id).exists()) {
            throw new IOException("作品已存在：" + id);
        }

        File staging = child(root, ".import-" + id);
        if (staging.exists()) deleteTree(staging);
        ensureDirectory(staging);
        boolean completed = false;
        try {
            ArrayList<File> sorted = new ArrayList<>(sourceImages);
            sorted.sort((left, right) -> WorkRules.compareNatural(left.getName(), right.getName()));
            ArrayList<String> storedImages = new ArrayList<>();
            ArrayList<String> contentHashes = new ArrayList<>();
            HashSet<String> seenContent = new HashSet<>();
            for (File source : sorted) {
                if (!source.isFile()) throw new IOException("图片不存在：" + source.getName());
                String contentHash = hashFile(source);
                if (!seenContent.add(contentHash)) continue;
                String storedName = uniqueName(staging, safeFileName(source.getName()));
                Files.copy(source.toPath(), child(staging, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                storedImages.add(storedName);
                contentHashes.add(contentHash);
            }

            Properties meta = new Properties();
            meta.setProperty("id", id);
            meta.setProperty("name", valueOrEmpty(name));
            meta.setProperty("text", valueOrEmpty(text));
            meta.setProperty("warning", valueOrEmpty(warning));
            meta.setProperty("sourceDocumentId", valueOrEmpty(sourceDocumentId));
            meta.setProperty("sourceParentDocumentId", valueOrEmpty(sourceParentDocumentId));
            meta.setProperty("sourceRelativePath", valueOrEmpty(sourceRelativePath));
            meta.setProperty("contentSignature", signature(valueOrEmpty(text), contentHashes));
            meta.setProperty("image.count", Integer.toString(storedImages.size()));
            for (int index = 0; index < storedImages.size(); index++) {
                meta.setProperty("image." + index, storedImages.get(index));
            }
            saveMeta(staging, meta);
            moveDirectory(staging, destination);
            completed = true;
            return readEntry(destination);
        } finally {
            if (!completed && staging.exists()) deleteTree(staging);
        }
    }

    public synchronized List<WorkEntry> listActive() throws IOException {
        return list(activeRoot);
    }

    public synchronized List<WorkEntry> listTrash() throws IOException {
        return list(trashRoot);
    }

    public synchronized WorkEntry getActive(String id) throws IOException {
        validateId(id);
        File directory = child(activeRoot, id);
        if (!directory.isDirectory()) return null;
        return readEntry(directory);
    }

    public synchronized WorkEntry getTrash(String id) throws IOException {
        validateId(id);
        File directory = child(trashRoot, id);
        if (!directory.isDirectory()) return null;
        return readEntry(directory);
    }

    public synchronized boolean contains(String id) throws IOException {
        validateId(id);
        return child(activeRoot, id).isDirectory() || child(trashRoot, id).isDirectory();
    }

    public synchronized WorkEntry findBySourceRelativePath(String relativePath) throws IOException {
        String normalized = normalizeSourcePath(relativePath);
        if (normalized.isEmpty()) return null;
        for (WorkEntry entry : list(activeRoot)) {
            if (matchesSourcePath(entry, normalized)) return entry;
        }
        for (WorkEntry entry : list(trashRoot)) {
            if (matchesSourcePath(entry, normalized)) return entry;
        }
        return null;
    }

    public int reconciledDuplicates() {
        return reconciledDuplicates;
    }

    public synchronized void updateSourceReference(
            String id, String sourceDocumentId, String sourceParentDocumentId,
            String sourceRelativePath) throws IOException {
        validateId(id);
        File directory = child(activeRoot, id);
        if (!directory.isDirectory()) directory = child(trashRoot, id);
        if (!directory.isDirectory()) return;
        Properties meta = loadMeta(directory);
        if (sourceDocumentId != null && !sourceDocumentId.isEmpty()) {
            meta.setProperty("sourceDocumentId", sourceDocumentId);
        }
        if (sourceParentDocumentId != null && !sourceParentDocumentId.isEmpty()) {
            meta.setProperty("sourceParentDocumentId", sourceParentDocumentId);
        }
        if (sourceRelativePath != null && !sourceRelativePath.isEmpty()) {
            meta.setProperty("sourceRelativePath", sourceRelativePath);
        }
        saveMeta(directory, meta);
    }

    public synchronized void updateExternalTrashLocation(
            String id, String trashDocumentId, String externalTrashName) throws IOException {
        validateId(id);
        File directory = child(trashRoot, id);
        if (!directory.isDirectory()) return;
        Properties meta = loadMeta(directory);
        meta.setProperty("trashDocumentId", valueOrEmpty(trashDocumentId));
        meta.setProperty("externalTrashName", valueOrEmpty(externalTrashName));
        saveMeta(directory, meta);
    }

    public synchronized void clearExternalTrashLocation(String id) throws IOException {
        updateExternalTrashLocation(id, "", "");
    }

    public synchronized void markShared(String id, LocalDate sharedDate) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        Properties meta = loadMeta(entry.directory);
        meta.setProperty("sharedDate", sharedDate.toString());
        int count = parseCount(meta.getProperty("shareCount", "0"));
        meta.setProperty("shareCount", Integer.toString(count + 1));
        saveMeta(entry.directory, meta);
    }

    /** Records the user's tap immediately. Chooser callbacks never control this counter. */
    public synchronized WorkEntry markShareAttempt(String id, long nowMs) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        Properties meta = loadMeta(entry.directory);
        meta.setProperty("sharedDate", Instant.ofEpochMilli(nowMs).atZone(BEIJING).toLocalDate().toString());
        if (parseLong(meta.getProperty("firstSharedAtMs", "0")) <= 0) {
            meta.setProperty("firstSharedAtMs", Long.toString(nowMs));
        }
        int count = parseCount(meta.getProperty("shareCount", "0"));
        meta.setProperty("shareCount", Integer.toString(count + 1));
        saveMeta(entry.directory, meta);
        return readEntry(entry.directory);
    }

    /**
     * Converts day-only records written by 0.5.5 and earlier into the precise cleanup clock.
     * Earlier Beijing dates are already due; a same-day record receives a one-hour grace period
     * beginning at upgrade so a recent share is never guessed to be older than it is.
     */
    public synchronized int migrateLegacyCleanupTimestamps(long nowMs) throws IOException {
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(BEIJING).toLocalDate();
        int migrated = migrateLegacyCleanupTimestamps(activeRoot, nowMs, today, false);
        return migrated + migrateLegacyCleanupTimestamps(trashRoot, nowMs, today, true);
    }

    private int migrateLegacyCleanupTimestamps(
            File parent, long nowMs, LocalDate today, boolean trashed) throws IOException {
        int migrated = 0;
        for (WorkEntry entry : list(parent)) {
            if (entry.firstSharedAtMs > 0) continue;
            LocalDate legacyDate = entry.sharedDate != null ? entry.sharedDate : entry.trashedDate;
            if (legacyDate == null) continue;
            long anchor = legacyDate.isBefore(today)
                    ? legacyDate.atStartOfDay(BEIJING).toInstant().toEpochMilli()
                    : nowMs;
            Properties meta = loadMeta(entry.directory);
            meta.setProperty("firstSharedAtMs", Long.toString(anchor));
            if (trashed && entry.trashedAtMs <= 0) {
                meta.setProperty("trashedAtMs", Long.toString(anchor));
            }
            saveMeta(entry.directory, meta);
            migrated++;
        }
        return migrated;
    }

    /** Moves selected managed image copies into this work's recoverable image bin. */
    public synchronized int moveImagesToTrash(String id, List<String> names, LocalDate date) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        ArrayList<String> retained = new ArrayList<>(entry.images);
        File imageTrash = new File(new File(entry.directory, ".image-trash"), date.toString());
        ensureDirectory(imageTrash);
        int moved = 0;
        for (String name : new ArrayList<>(names)) {
            if (!retained.contains(name)) continue;
            File source = child(entry.directory, name);
            File destination = new File(imageTrash, uniqueName(imageTrash, name));
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            retained.remove(name);
            moved++;
        }
        if (moved > 0) writeImageList(entry.directory, retained);
        return moved;
    }

    public synchronized int imageTrashCount(String id) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        return collectImageTrashFiles(new File(entry.directory, ".image-trash")).size();
    }

    public synchronized int restoreAllImages(String id) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        File trash = new File(entry.directory, ".image-trash");
        ArrayList<File> files = collectImageTrashFiles(trash);
        ArrayList<String> images = new ArrayList<>(entry.images);
        for (File source : files) {
            String name = uniqueName(entry.directory, source.getName());
            Files.move(source.toPath(), new File(entry.directory, name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            images.add(name);
        }
        images.sort(WorkRules::compareNatural);
        if (!files.isEmpty()) writeImageList(entry.directory, images);
        if (trash.exists()) deleteEmptyDirectories(trash);
        return files.size();
    }

    public synchronized List<WorkEntry> maintain(LocalDate today) throws IOException {
        ArrayList<WorkEntry> moved = new ArrayList<>();
        for (WorkEntry entry : list(activeRoot)) {
            if (!RetentionPolicy.shouldMoveToTrash(entry.sharedDate, today)) continue;
            moved.add(moveToTrash(entry.id, today));
        }
        for (WorkEntry entry : list(trashRoot)) {
            if (RetentionPolicy.shouldPurge(entry.trashedDate, today)
                    && entry.sourceDocumentId.isEmpty() && entry.sourceRelativePath.isEmpty()) {
                deleteTree(entry.directory);
            }
        }
        purgeImageTrash(activeRoot, today);
        purgeImageTrash(trashRoot, today);
        return moved;
    }

    public synchronized List<WorkEntry> maintain(long nowMs, long moveAfterMs) throws IOException {
        ArrayList<WorkEntry> moved = new ArrayList<>();
        for (WorkEntry entry : list(activeRoot)) {
            if (!RetentionPolicy.shouldMoveToTrash(entry.firstSharedAtMs, nowMs, moveAfterMs)) continue;
            moved.add(moveToTrash(entry.id, nowMs));
        }
        return moved;
    }

    private void writeImageList(File directory, List<String> images) throws IOException {
        Properties meta = loadMeta(directory);
        int previous = parseCount(meta.getProperty("image.count", "0"));
        for (int index = 0; index < previous; index++) meta.remove("image." + index);
        meta.setProperty("image.count", Integer.toString(images.size()));
        for (int index = 0; index < images.size(); index++) meta.setProperty("image." + index, images.get(index));
        saveMeta(directory, meta);
    }

    private static ArrayList<File> collectImageTrashFiles(File root) {
        ArrayList<File> result = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) return result;
        for (File child : children) {
            if (child.isDirectory()) result.addAll(collectImageTrashFiles(child));
            else if (child.isFile()) result.add(child);
        }
        return result;
    }

    private static void deleteEmptyDirectories(File root) {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) if (child.isDirectory()) deleteEmptyDirectories(child);
        children = root.listFiles();
        if (children != null && children.length == 0) root.delete();
    }

    private void purgeImageTrash(File parent, LocalDate today) throws IOException {
        for (WorkEntry entry : list(parent)) {
            File trash = new File(entry.directory, ".image-trash");
            File[] days = trash.listFiles(File::isDirectory);
            if (days == null) continue;
            for (File day : days) {
                try {
                    LocalDate created = LocalDate.parse(day.getName());
                    if (!today.isBefore(created.plusDays(7))) deleteTree(day);
                } catch (RuntimeException ignored) { }
            }
            deleteEmptyDirectories(trash);
        }
    }

    public synchronized WorkEntry moveToTrash(String id, LocalDate trashedDate) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        Properties meta = loadMeta(entry.directory);
        meta.setProperty("trashedDate", trashedDate.toString());
        saveMeta(entry.directory, meta);
        File destination = child(trashRoot, entry.id);
        moveDirectory(entry.directory, destination);
        return readEntry(destination);
    }

    public synchronized WorkEntry moveToTrash(String id, long trashedAtMs) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        Properties meta = loadMeta(entry.directory);
        meta.setProperty("trashedDate",
                Instant.ofEpochMilli(trashedAtMs).atZone(BEIJING).toLocalDate().toString());
        meta.setProperty("trashedAtMs", Long.toString(trashedAtMs));
        saveMeta(entry.directory, meta);
        File destination = child(trashRoot, entry.id);
        moveDirectory(entry.directory, destination);
        return readEntry(destination);
    }

    public synchronized void rollbackTrashMove(String id) throws IOException {
        WorkEntry entry = requireEntry(trashRoot, id);
        File destination = child(activeRoot, id);
        if (destination.exists()) throw new IOException("作品已在列表中：" + id);
        Properties meta = loadMeta(entry.directory);
        meta.remove("trashedDate");
        meta.remove("trashDocumentId");
        meta.remove("externalTrashName");
        saveMeta(entry.directory, meta);
        moveDirectory(entry.directory, destination);
    }

    public synchronized void deleteTrash(String id) throws IOException {
        WorkEntry entry = requireEntry(trashRoot, id);
        deleteTree(entry.directory);
    }

    public synchronized void restore(String id) throws IOException {
        WorkEntry entry = requireEntry(trashRoot, id);
        File destination = child(activeRoot, id);
        if (destination.exists()) throw new IOException("作品已在列表中：" + id);
        Properties meta = loadMeta(entry.directory);
        meta.remove("sharedDate");
        meta.remove("trashedDate");
        meta.remove("firstSharedAtMs");
        meta.remove("trashedAtMs");
        saveMeta(entry.directory, meta);
        moveDirectory(entry.directory, destination);
    }

    public synchronized void clearTrash() throws IOException {
        for (WorkEntry entry : list(trashRoot)) deleteTree(entry.directory);
    }

    private List<WorkEntry> list(File parent) throws IOException {
        File[] directories = parent.listFiles(File::isDirectory);
        if (directories == null) return Collections.emptyList();
        ArrayList<WorkEntry> entries = new ArrayList<>();
        for (File directory : directories) {
            if (new File(directory, META_FILE).isFile()) entries.add(readEntry(directory));
        }
        entries.sort((left, right) -> WorkRules.compareNatural(left.name, right.name));
        return entries;
    }

    private int collapseLegacyDuplicates(File parent) throws IOException {
        List<WorkEntry> entries = list(parent);
        int removed = 0;
        for (WorkEntry legacy : entries) {
            String relative = normalizeSourcePath(legacy.sourceRelativePath);
            if (relative.isEmpty() || !legacy.directory.isDirectory()) continue;
            WorkEntry document = null;
            for (WorkEntry candidate : entries) {
                if (candidate.id.equals(legacy.id) || candidate.sourceDocumentId.isEmpty()
                        || !candidate.directory.isDirectory()) continue;
                if (documentPathEndsWith(candidate.sourceDocumentId, relative)) {
                    document = candidate;
                    break;
                }
            }
            if (document == null) continue;
            mergeDuplicateMetadata(document, legacy);
            deleteTree(legacy.directory);
            removed++;
        }
        return removed;
    }

    /**
     * Android 10 can expose one hidden work through both SAF and the legacy storage fallback.
     * If one copy was already moved to trash, the other must not reappear as a fresh active work.
     */
    private int collapseDuplicatesAcrossActiveAndTrash() throws IOException {
        List<WorkEntry> active = list(activeRoot);
        List<WorkEntry> trash = list(trashRoot);
        int removed = 0;
        for (WorkEntry current : active) {
            if (!current.directory.isDirectory()) continue;
            WorkEntry trashedMatch = null;
            for (WorkEntry trashed : trash) {
                if (!trashed.directory.isDirectory()) continue;
                String currentRelative = normalizeSourcePath(current.sourceRelativePath);
                String trashRelative = normalizeSourcePath(trashed.sourceRelativePath);
                boolean same = (!currentRelative.isEmpty()
                        && documentPathEndsWith(trashed.sourceDocumentId, currentRelative))
                        || (!trashRelative.isEmpty()
                        && documentPathEndsWith(current.sourceDocumentId, trashRelative))
                        || (!current.sourceDocumentId.isEmpty()
                        && current.sourceDocumentId.equals(trashed.sourceDocumentId));
                if (same) {
                    trashedMatch = trashed;
                    break;
                }
            }
            if (trashedMatch == null) continue;
            mergeDuplicateMetadata(trashedMatch, current);
            deleteTree(current.directory);
            removed++;
        }
        return removed;
    }

    private int collapseContentDuplicatesAcrossActiveAndTrash() throws IOException {
        List<WorkEntry> trash = list(trashRoot);
        Map<String, WorkEntry> trashedByContent = new HashMap<>();
        for (WorkEntry entry : trash) {
            if (!entry.directory.isDirectory()) continue;
            trashedByContent.put(ensureContentSignature(entry), entry);
        }
        int removed = 0;
        for (WorkEntry current : list(activeRoot)) {
            if (!current.directory.isDirectory()) continue;
            WorkEntry match = trashedByContent.get(ensureContentSignature(current));
            if (match == null || !match.directory.isDirectory()) continue;
            mergeDuplicateMetadata(match, current);
            deleteTree(current.directory);
            removed++;
        }
        return removed;
    }

    /** Lazily migrates old libraries and removes byte-identical app-private image copies. */
    private String ensureContentSignature(WorkEntry entry) throws IOException {
        Properties meta = loadMeta(entry.directory);
        String existing = meta.getProperty("contentSignature", "");
        if (!existing.isEmpty()) return existing;
        ArrayList<String> retainedNames = new ArrayList<>();
        ArrayList<String> hashes = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String name : entry.images) {
            File image = child(entry.directory, name);
            String hash = hashFile(image);
            if (seen.add(hash)) {
                retainedNames.add(name);
                hashes.add(hash);
            } else if (image.isFile() && !image.delete()) {
                throw new IOException("无法整理重复图片");
            }
        }
        if (retainedNames.size() != entry.images.size()) {
            int previous = parseCount(meta.getProperty("image.count", "0"));
            for (int index = 0; index < previous; index++) meta.remove("image." + index);
            meta.setProperty("image.count", Integer.toString(retainedNames.size()));
            for (int index = 0; index < retainedNames.size(); index++) {
                meta.setProperty("image." + index, retainedNames.get(index));
            }
        }
        String signature = signature(entry.text, hashes);
        meta.setProperty("contentSignature", signature);
        saveMeta(entry.directory, meta);
        return signature;
    }

    private void mergeDuplicateMetadata(WorkEntry retained, WorkEntry duplicate) throws IOException {
        Properties meta = loadMeta(retained.directory);
        int combinedCount = Math.min(10000, retained.shareCount + duplicate.shareCount);
        meta.setProperty("shareCount", Integer.toString(combinedCount));
        LocalDate shared = later(retained.sharedDate, duplicate.sharedDate);
        if (shared != null) meta.setProperty("sharedDate", shared.toString());
        LocalDate trashed = later(retained.trashedDate, duplicate.trashedDate);
        if (trashed != null) meta.setProperty("trashedDate", trashed.toString());
        long firstShared = earlierPositive(retained.firstSharedAtMs, duplicate.firstSharedAtMs);
        if (firstShared > 0) meta.setProperty("firstSharedAtMs", Long.toString(firstShared));
        long trashedAt = later(retained.trashedAtMs, duplicate.trashedAtMs);
        if (trashedAt > 0) meta.setProperty("trashedAtMs", Long.toString(trashedAt));
        if (meta.getProperty("sourceRelativePath", "").isEmpty()) {
            meta.setProperty("sourceRelativePath", duplicate.sourceRelativePath);
        }
        copyIfEmpty(meta, "sourceDocumentId", duplicate.sourceDocumentId);
        copyIfEmpty(meta, "sourceParentDocumentId", duplicate.sourceParentDocumentId);
        copyIfEmpty(meta, "trashDocumentId", duplicate.trashDocumentId);
        copyIfEmpty(meta, "externalTrashName", duplicate.externalTrashName);
        saveMeta(retained.directory, meta);
    }

    private static void copyIfEmpty(Properties target, String key, String value) {
        if (target.getProperty(key, "").isEmpty() && value != null && !value.isEmpty()) {
            target.setProperty(key, value);
        }
    }

    private static LocalDate later(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static long later(long left, long right) {
        return Math.max(left, right);
    }

    private static long earlierPositive(long left, long right) {
        if (left <= 0) return right;
        if (right <= 0) return left;
        return Math.min(left, right);
    }

    private static String signature(String text, List<String> imageHashes) throws IOException {
        try {
            ArrayList<String> sorted = new ArrayList<>(imageHashes);
            Collections.sort(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(valueOrEmpty(text).trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (String hash : sorted) {
                digest.update(hash.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
            return hex(digest.digest());
        } catch (Exception error) {
            throw new IOException("无法生成作品内容指纹", error);
        }
    }

    private static String hashFile(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Exception error) {
            throw new IOException("无法读取作品图片", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static boolean matchesSourcePath(WorkEntry entry, String normalizedRelative) {
        return normalizedRelative.equals(normalizeSourcePath(entry.sourceRelativePath))
                || documentPathEndsWith(entry.sourceDocumentId, normalizedRelative);
    }

    private static boolean documentPathEndsWith(String documentId, String normalizedRelative) {
        String normalizedDocument = normalizeSourcePath(documentId);
        return normalizedDocument.equals(normalizedRelative)
                || normalizedDocument.endsWith("/" + normalizedRelative)
                || normalizedDocument.endsWith(":" + normalizedRelative);
    }

    private static String normalizeSourcePath(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    private WorkEntry requireEntry(File parent, String id) throws IOException {
        validateId(id);
        File directory = child(parent, id);
        if (!directory.isDirectory()) throw new IOException("找不到作品：" + id);
        return readEntry(directory);
    }

    private WorkEntry readEntry(File directory) throws IOException {
        Properties meta = loadMeta(directory);
        int imageCount = parseCount(meta.getProperty("image.count", "0"));
        ArrayList<String> images = new ArrayList<>();
        for (int index = 0; index < imageCount; index++) {
            String image = meta.getProperty("image." + index, "");
            if (!image.isEmpty() && child(directory, image).isFile()) images.add(image);
        }
        return new WorkEntry(
                meta.getProperty("id", directory.getName()),
                meta.getProperty("name", directory.getName()),
                meta.getProperty("text", ""),
                meta.getProperty("warning", ""),
                images,
                parseDate(meta.getProperty("sharedDate")),
                parseDate(meta.getProperty("trashedDate")),
                parseCount(meta.getProperty("shareCount", "0")),
                parseLong(meta.getProperty("firstSharedAtMs", "0")),
                parseLong(meta.getProperty("trashedAtMs", "0")),
                meta.getProperty("sourceDocumentId", ""),
                meta.getProperty("sourceParentDocumentId", ""),
                meta.getProperty("sourceRelativePath", ""),
                meta.getProperty("trashDocumentId", ""),
                meta.getProperty("externalTrashName", ""),
                directory.getCanonicalFile());
    }

    private static Properties loadMeta(File directory) throws IOException {
        Properties meta = new Properties();
        try (InputStream input = new FileInputStream(new File(directory, META_FILE))) {
            meta.load(input);
        }
        return meta;
    }

    private static void saveMeta(File directory, Properties meta) throws IOException {
        File next = new File(directory, META_FILE + ".next");
        try (OutputStream output = new FileOutputStream(next)) {
            meta.store(output, "album work");
        }
        Files.move(next.toPath(), new File(directory, META_FILE).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static LocalDate parseDate(String value) throws IOException {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            throw new IOException("作品日期记录损坏", error);
        }
    }

    private static int parseCount(String value) throws IOException {
        try {
            int count = Integer.parseInt(value);
            if (count < 0 || count > 10000) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException error) {
            throw new IOException("作品图片记录损坏", error);
        }
    }

    private static long parseLong(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            return Math.max(0, parsed);
        } catch (NumberFormatException error) {
            throw new IOException("作品时间记录损坏", error);
        }
    }

    private static void moveDirectory(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source.toPath(), destination.toPath());
        }
    }

    private File child(File parent, String name) throws IOException {
        File candidate = new File(parent, name).getCanonicalFile();
        String parentPath = parent.getCanonicalPath() + File.separator;
        if (!candidate.getPath().startsWith(parentPath)) throw new IOException("非法文件路径");
        return candidate;
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}") || ".".equals(id) || "..".equals(id)) {
            throw new IOException("非法作品编号");
        }
    }

    private static String safeFileName(String name) {
        String safe = name.replace('/', '_').replace('\\', '_').trim();
        return safe.isEmpty() ? "image" : safe;
    }

    private static String uniqueName(File directory, String requested) {
        if (!new File(directory, requested).exists()) return requested;
        int dot = requested.lastIndexOf('.');
        String base = dot > 0 ? requested.substring(0, dot) : requested;
        String extension = dot > 0 ? requested.substring(dot) : "";
        for (int index = 2; ; index++) {
            String candidate = base + "-" + index + extension;
            if (!new File(directory, candidate).exists()) return candidate;
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建作品目录：" + directory.getName());
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("无法读取目录：" + target.getName());
            for (File child : children) deleteTree(child);
        }
        if (target.exists() && !target.delete()) throw new IOException("无法清理：" + target.getName());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public static final class WorkEntry {
        public final String id;
        public final String name;
        public final String text;
        public final String warning;
        public final List<String> images;
        public final LocalDate sharedDate;
        public final LocalDate trashedDate;
        public final int shareCount;
        public final long firstSharedAtMs;
        public final long trashedAtMs;
        public final String sourceDocumentId;
        public final String sourceParentDocumentId;
        public final String sourceRelativePath;
        public final String trashDocumentId;
        public final String externalTrashName;
        public final File directory;

        private WorkEntry(String id, String name, String text, String warning,
                          List<String> images, LocalDate sharedDate, LocalDate trashedDate,
                          int shareCount, long firstSharedAtMs, long trashedAtMs,
                          String sourceDocumentId, String sourceParentDocumentId,
                          String sourceRelativePath, String trashDocumentId, String externalTrashName,
                          File directory) {
            this.id = id;
            this.name = name;
            this.text = text;
            this.warning = warning;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.sharedDate = sharedDate;
            this.trashedDate = trashedDate;
            this.shareCount = shareCount;
            this.firstSharedAtMs = firstSharedAtMs;
            this.trashedAtMs = trashedAtMs;
            this.sourceDocumentId = sourceDocumentId;
            this.sourceParentDocumentId = sourceParentDocumentId;
            this.sourceRelativePath = sourceRelativePath;
            this.trashDocumentId = trashDocumentId;
            this.externalTrashName = externalTrashName;
            this.directory = directory;
        }
    }
}
