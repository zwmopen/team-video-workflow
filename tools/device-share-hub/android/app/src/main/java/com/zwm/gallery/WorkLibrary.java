package com.zwm.gallery;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Persistent, app-private queue of works and its recoverable trash. */
public final class WorkLibrary {
    private static final String META_FILE = "meta.properties";
    private static final Object MIGRATION_LOCK = new Object();

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
                    + collapseLegacyDuplicates(trashRoot);
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
            for (File source : sorted) {
                if (!source.isFile()) throw new IOException("图片不存在：" + source.getName());
                String storedName = uniqueName(staging, safeFileName(source.getName()));
                Files.copy(source.toPath(), child(staging, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                storedImages.add(storedName);
            }

            Properties meta = new Properties();
            meta.setProperty("id", id);
            meta.setProperty("name", valueOrEmpty(name));
            meta.setProperty("text", valueOrEmpty(text));
            meta.setProperty("warning", valueOrEmpty(warning));
            meta.setProperty("sourceDocumentId", valueOrEmpty(sourceDocumentId));
            meta.setProperty("sourceParentDocumentId", valueOrEmpty(sourceParentDocumentId));
            meta.setProperty("sourceRelativePath", valueOrEmpty(sourceRelativePath));
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

    public synchronized List<WorkEntry> maintain(LocalDate today) throws IOException {
        ArrayList<WorkEntry> moved = new ArrayList<>();
        for (WorkEntry entry : list(activeRoot)) {
            if (!RetentionPolicy.shouldMoveToTrash(entry.sharedDate, today)) continue;
            Properties meta = loadMeta(entry.directory);
            meta.setProperty("trashedDate", today.toString());
            saveMeta(entry.directory, meta);
            moveDirectory(entry.directory, child(trashRoot, entry.id));
            moved.add(readEntry(child(trashRoot, entry.id)));
        }
        for (WorkEntry entry : list(trashRoot)) {
            if (RetentionPolicy.shouldPurge(entry.trashedDate, today)
                    && entry.sourceDocumentId.isEmpty() && entry.sourceRelativePath.isEmpty()) {
                deleteTree(entry.directory);
            }
        }
        return moved;
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

    private void mergeDuplicateMetadata(WorkEntry retained, WorkEntry duplicate) throws IOException {
        Properties meta = loadMeta(retained.directory);
        int combinedCount = Math.min(10000, retained.shareCount + duplicate.shareCount);
        meta.setProperty("shareCount", Integer.toString(combinedCount));
        LocalDate shared = later(retained.sharedDate, duplicate.sharedDate);
        if (shared != null) meta.setProperty("sharedDate", shared.toString());
        LocalDate trashed = later(retained.trashedDate, duplicate.trashedDate);
        if (trashed != null) meta.setProperty("trashedDate", trashed.toString());
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
        public final String sourceDocumentId;
        public final String sourceParentDocumentId;
        public final String sourceRelativePath;
        public final String trashDocumentId;
        public final String externalTrashName;
        public final File directory;

        private WorkEntry(String id, String name, String text, String warning,
                          List<String> images, LocalDate sharedDate, LocalDate trashedDate,
                          int shareCount, String sourceDocumentId, String sourceParentDocumentId,
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
            this.sourceDocumentId = sourceDocumentId;
            this.sourceParentDocumentId = sourceParentDocumentId;
            this.sourceRelativePath = sourceRelativePath;
            this.trashDocumentId = trashDocumentId;
            this.externalTrashName = externalTrashName;
            this.directory = directory;
        }
    }
}
