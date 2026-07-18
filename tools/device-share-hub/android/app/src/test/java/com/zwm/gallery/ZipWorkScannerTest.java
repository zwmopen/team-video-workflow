package com.zwm.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipWorkScannerTest {
    @Test
    public void findsNestedWorkFoldersAndSortsImages() throws Exception {
        byte[] zip = zipOf(
                "作品合集包1/作品A/10.png", "image-10",
                "作品合集包1/作品A/2.png", "image-2",
                "作品合集包1/作品A/文案.txt", "作品A文案",
                "作品合集包1/作品B/1.jpg", "image-1",
                "作品合集包1/作品B/说明.txt", "作品B文案",
                "作品合集包1/说明.txt", "不是作品"
        );

        List<ZipWorkScanner.WorkPlan> works = ZipWorkScanner.scan(new ByteArrayInputStream(zip));

        assertEquals(2, works.size());
        assertEquals("作品A", works.get(0).name);
        assertEquals("作品合集包1/作品A/文案.txt", works.get(0).captionEntry);
        assertEquals("作品合集包1/作品A/2.png", works.get(0).imageEntries.get(0));
        assertEquals("作品合集包1/作品A/10.png", works.get(0).imageEntries.get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZipTraversalPaths() throws Exception {
        byte[] zip = zipOf("../escape/1.png", "image", "../escape/文案.txt", "text");
        ZipWorkScanner.scan(new ByteArrayInputStream(zip));
    }

    private static byte[] zipOf(String... values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int i = 0; i < values.length; i += 2) {
                zip.putNextEntry(new ZipEntry(values[i]));
                zip.write(values[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
