package com.zwm.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocumentTreeImporterTest {
    @Test
    public void recognizesTrashCollisionNamesWithoutHidingOrdinaryFolders() {
        assertTrue(DocumentTreeImporter.isTrashFolderName("相册回收站"));
        assertTrue(DocumentTreeImporter.isTrashFolderName("_相册回收站"));
        assertTrue(DocumentTreeImporter.isTrashFolderName("相册回收站 (1)"));
        assertTrue(DocumentTreeImporter.isTrashFolderName("_相册回收站 (23)"));
        assertFalse(DocumentTreeImporter.isTrashFolderName("相册回收站教程"));
        assertFalse(DocumentTreeImporter.isTrashFolderName("相册回收站 (A)"));
    }
}
