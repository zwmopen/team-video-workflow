package com.zwm.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorkArchiveImporterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importsEachValidFolderAsAWork() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("作品合集/作品2/文案.txt", "第二条文案");
        content.put("作品合集/作品2/10.jpg", "ten");
        content.put("作品合集/作品2/2.jpg", "two");
        content.put("作品合集/说明.txt", "not a work");
        File archive = zip(content);
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library"));

        int count = WorkArchiveImporter.importZip(archive, library, "batch-1");

        assertEquals(1, count);
        WorkLibrary.WorkEntry entry = library.listActive().get(0);
        assertEquals("作品2", entry.name);
        assertEquals("第二条文案", entry.text);
        assertEquals("作品合集", entry.getFolderName());
        assertEquals("2.jpg", entry.images.get(0));
        assertEquals("10.jpg", entry.images.get(1));
    }

    @Test
    public void importsWindowsBackslashZipEntries() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("合集\\作品1\\文案.txt", "Windows ZIP 文案");
        content.put("合集\\作品1\\1.jpg", "image");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("windows-library"));

        assertEquals(1, WorkArchiveImporter.importZip(zip(content), library, "windows-batch"));
        assertEquals("Windows ZIP 文案", library.listActive().get(0).text);
        assertEquals("合集", library.listActive().get(0).getFolderName());
    }

    @Test
    public void ignoresSessionMetadataWhenImportingArchive() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("合集/作品/会话追踪.txt", "母版 URL 和执行账号");
        content.put("合集/作品/小红书文案.txt", "真实小红书文案");
        content.put("合集/作品/1.jpg", "image");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("metadata-library"));

        assertEquals(1, WorkArchiveImporter.importZip(zip(content), library, "metadata-batch"));
        assertEquals("真实小红书文案", library.listActive().get(0).text);
    }

    @Test
    public void repairsAnAlreadyImportedSessionSummaryOnArchiveReimport() throws Exception {
        WorkLibrary library = new WorkLibrary(temporary.newFolder("repair-library"));
        File image = temporary.newFile("existing.jpg");
        Files.write(image.toPath(), "image".getBytes(StandardCharsets.UTF_8));
        library.importWork("repair-batch-1", "作品",
                "母版URL: x\n分支URL: y\n执行账号: 账号1\n生成卡片数: 8\n完成时间: now",
                Arrays.asList(image), "");

        Map<String, String> content = new LinkedHashMap<>();
        content.put("合集/作品/会话追踪.txt", "母版URL: x\n执行账号: 账号1\n完成时间: now");
        content.put("合集/作品/小红书文案.txt", "修复后的真实文案");
        content.put("合集/作品/1.jpg", "image");

        assertEquals(0, WorkArchiveImporter.importZip(zip(content), library, "repair-batch"));
        assertEquals("修复后的真实文案", library.listActive().get(0).text);
    }

    @Test
    public void acceptsGeneralArchiveWithoutInventingAWork() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("资料/说明.pdf", "pdf");
        content.put("资料/原始数据.bin", "binary");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("general-library"));

        assertEquals(0, WorkArchiveImporter.importZip(zip(content), library, "general-batch"));
        assertEquals(0, library.listActive().size());
    }

    private File zip(Map<String, String> content) throws Exception {
        File file = temporary.newFile("works.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> item : content.entrySet()) {
                output.putNextEntry(new ZipEntry(item.getKey()));
                output.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }
}
