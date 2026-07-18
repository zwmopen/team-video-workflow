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

    private final File root;
    private final File activeRoot;
    private final File trashRoot;

    public WorkLibrary(File root) throws IOException {
        this.root = root.getCanonicalFile();
        this.activeRoot = new File(this.root, "active").getCanonicalFile();
        this.trashRoot = new File(this.root, "trash").getCanonicalFile();
        ensureDirectory(activeRoot);
        ensureDirectory(trashRoot);
    }

    public synchronized WorkEntry importWork(
            String id,
            String name,
            String text,
            List<File> sourceImages,
            String warning) throws IOException {
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

    public synchronized boolean contains(String id) throws IOException {
        validateId(id);
        return child(activeRoot, id).isDirectory() || child(trashRoot, id).isDirectory();
    }

    public synchronized void markShared(String id, LocalDate sharedDate) throws IOException {
        WorkEntry entry = requireEntry(activeRoot, id);
        Properties meta = loadMeta(entry.directory);
        meta.setProperty("sharedDate", sharedDate.toString());
        saveMeta(entry.directory, meta);
    }

    public synchronized void maintain(LocalDate today) throws IOException {
        for (WorkEntry entry : list(activeRoot)) {
            if (!RetentionPolicy.shouldMoveToTrash(entry.sharedDate, today)) continue;
            Properties meta = loadMeta(entry.directory);
            meta.setProperty("trashedDate", today.toString());
            saveMeta(entry.directory, meta);
            moveDirectory(entry.directory, child(trashRoot, entry.id));
        }
        for (WorkEntry entry : list(trashRoot)) {
            if (RetentionPolicy.shouldPurge(entry.trashedDate, today)) deleteTree(entry.directory);
        }
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
        public final File directory;

        private WorkEntry(String id, String name, String text, String warning,
                          List<String> images, LocalDate sharedDate, LocalDate trashedDate,
                          File directory) {
            this.id = id;
            this.name = name;
            this.text = text;
            this.warning = warning;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.sharedDate = sharedDate;
            this.trashedDate = trashedDate;
            this.directory = directory;
        }
    }
}
