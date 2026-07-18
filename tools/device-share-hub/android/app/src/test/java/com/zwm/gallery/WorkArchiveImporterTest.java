package com.zwm.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
