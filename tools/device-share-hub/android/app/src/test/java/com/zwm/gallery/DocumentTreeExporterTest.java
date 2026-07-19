package com.zwm.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.zip.ZipEntry;

public final class DocumentTreeExporterTest {
    @Test
    public void normalizesWindowsArchivePathsWithoutFlatteningThem() {
        assertEquals("作品合集/作品一/01.jpg",
                DocumentTreeExporter.validateArchivePath("作品合集\\作品一\\01.jpg"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsArchiveTraversalBeforeWritingToPhone() {
        DocumentTreeExporter.validateArchivePath("作品合集/../../逃逸.txt");
    }

    @Test
    public void recognizesWindowsBackslashDirectoryEntries() {
        org.junit.Assert.assertTrue(DocumentTreeExporter.isDirectoryEntry(new ZipEntry("作品合集\\")));
    }
}
