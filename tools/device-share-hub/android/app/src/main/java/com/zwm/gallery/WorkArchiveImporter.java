package com.zwm.gallery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safely turns every valid direct folder in a ZIP into an app-private work. */
public final class WorkArchiveImporter {
    private static final int MAX_WORKS = 500;
    private static final int MAX_IMAGES_PER_WORK = 100;
    private static final long MAX_TEXT_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_IMAGE_BYTES = 2L * 1024L * 1024L * 1024L;

    private WorkArchiveImporter() {
    }

    public static int importZip(File archive, WorkLibrary library, String batchId) throws Exception {
        if (!archive.isFile()) throw new IOException("找不到压缩包");
        List<ZipWorkScanner.WorkPlan> plans;
        try (InputStream input = new FileInputStream(archive)) {
            plans = ZipWorkScanner.scan(input);
        }
        if (plans.isEmpty()) return 0;
        if (plans.size() > MAX_WORKS) throw new IOException("作品数量超过 " + MAX_WORKS + " 个");

        File temporaryRoot = Files.createTempDirectory("album-import-").toFile();
        int imported = 0;
        try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
            for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
                ZipWorkScanner.WorkPlan plan = plans.get(planIndex);
                if (plan.imageEntries.size() > MAX_IMAGES_PER_WORK) {
                    throw new IOException("作品“" + plan.name + "”图片超过 " + MAX_IMAGES_PER_WORK + " 张");
                }
                String text = readText(zip, plan.captionEntry);
                File workTemporary = new File(temporaryRoot, Integer.toString(planIndex));
                if (!workTemporary.mkdirs()) throw new IOException("无法创建导入缓存");
                ArrayList<File> images = new ArrayList<>();
                for (String imageEntry : plan.imageEntries) {
                    ZipEntry entry = requireFileEntry(zip, imageEntry);
                    if (entry.getSize() > MAX_IMAGE_BYTES) throw new IOException("图片过大：" + baseName(imageEntry));
                    File destination = new File(workTemporary, baseName(imageEntry));
                    copyBounded(zip.getInputStream(entry), destination, MAX_IMAGE_BYTES);
                    images.add(destination);
                }
                String warning = plan.captionFileCount > 1
                        ? "检测到多个 TXT，已使用“" + baseName(plan.captionEntry) + "”"
                        : "";
                String workId = batchId + "-" + (planIndex + 1);
                if (library.contains(workId)) continue;
                // Preserve the source folder name so [转]/[泛] archives keep the
                // same category after a remote or LAN import.
                library.importWork(workId, plan.name, text, images, warning,
                        "", "", plan.name);
                imported++;
            }
        } finally {
            deleteTree(temporaryRoot);
        }
        return imported;
    }

    private static String readText(ZipFile zip, String name) throws IOException {
        ZipEntry entry = requireFileEntry(zip, name);
        if (entry.getSize() > MAX_TEXT_BYTES) throw new IOException("TXT 文案过大：" + baseName(name));
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_TEXT_BYTES) throw new IOException("TXT 文案过大：" + baseName(name));
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF
                    && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        }
    }

    private static void copyBounded(InputStream input, File destination, long limit) throws IOException {
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > limit) throw new IOException("解压文件超过大小限制");
                output.write(buffer, 0, count);
            }
        }
    }

    private static ZipEntry requireFileEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry candidate = entries.nextElement();
                if (candidate.getName().replace('\\', '/').equals(name)) {
                    entry = candidate;
                    break;
                }
            }
        }
        if (entry == null || entry.isDirectory()) throw new IOException("压缩包缺少文件：" + name);
        return entry;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static void deleteTree(File target) {
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (target.exists()) target.delete();
    }
}
