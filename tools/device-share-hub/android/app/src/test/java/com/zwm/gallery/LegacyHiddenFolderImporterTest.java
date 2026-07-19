package com.zwm.gallery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class LegacyHiddenFolderImporterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importsOnlyDotPrefixedWorkFolders() throws Exception {
        File source = temporary.newFolder("Lark");
        createWork(new File(source, ".隐藏作品"));
        createWork(new File(source, "普通作品"));
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library"));

        LegacyHiddenFolderImporter.Result result = LegacyHiddenFolderImporter.importFrom(source, library);

        assertEquals(1, result.detected);
        assertEquals(1, result.imported);
        assertEquals(1, library.listActive().size());
        assertEquals("隐藏作品", library.listActive().get(0).name);
    }

    @Test
    public void skipsHiddenWorkAlreadyImportedThroughDocumentTree() throws Exception {
        File source = temporary.newFolder("Lark-duplicate");
        File collection = new File(source, "作品集");
        File hidden = new File(collection, ".同一个作品");
        createWork(hidden);
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library-duplicate"));
        library.importWork(
                "lark-one", ".同一个作品", "测试文案",
                Collections.singletonList(new File(hidden, "1.jpg")), "",
                "primary:Download/Lark/作品集/.同一个作品",
                "primary:Download/Lark/作品集", "");

        LegacyHiddenFolderImporter.Result result = LegacyHiddenFolderImporter.importFrom(source, library);

        assertEquals(0, result.imported);
        assertEquals(1, result.skipped);
        assertEquals(1, library.listActive().size());
        assertEquals("作品集" + File.separator + ".同一个作品",
                library.listActive().get(0).sourceRelativePath);
    }

    private static void createWork(File directory) throws Exception {
        if (!directory.mkdirs()) throw new IllegalStateException("cannot create test directory");
        Files.write(new File(directory, "1.jpg").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(directory, "文案.txt").toPath(), "测试文案".getBytes(StandardCharsets.UTF_8));
    }
}
