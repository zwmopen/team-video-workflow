package com.zwm.gallery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
