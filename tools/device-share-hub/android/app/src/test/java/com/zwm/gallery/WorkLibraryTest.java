package com.zwm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;

public final class WorkLibraryTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importsAndListsAWork() throws Exception {
        File source = temporary.newFolder("source");
        File second = write(source, "2.png", "two");
        File tenth = write(source, "10.png", "ten");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library"));

        library.importWork("work-1", "作品一", "这是一条文案", Arrays.asList(tenth, second), "");

        WorkLibrary.WorkEntry entry = library.listActive().get(0);
        assertEquals("作品一", entry.name);
        assertEquals("这是一条文案", entry.text);
        assertEquals("2.png", entry.images.get(0));
        assertEquals("10.png", entry.images.get(1));
    }

    @Test
    public void movesOnNextDayAndRestoresFromTrash() throws Exception {
        File source = temporary.newFolder("source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library"));
        library.importWork("work-2", "作品二", "文案", Arrays.asList(write(source, "1.jpg", "one")), "");
        LocalDate shared = LocalDate.of(2026, 7, 18);

        library.markShared("work-2", shared);
        library.maintain(shared);
        assertEquals(1, library.listActive().size());
        library.maintain(shared.plusDays(1));
        assertTrue(library.listActive().isEmpty());
        assertEquals(1, library.listTrash().size());

        library.restore("work-2");
        assertEquals(1, library.listActive().size());
        assertTrue(library.listTrash().isEmpty());
    }

    @Test
    public void purgesTrashAfterSevenDays() throws Exception {
        File source = temporary.newFolder("source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("library"));
        library.importWork("work-3", "作品三", "文案", Arrays.asList(write(source, "1.jpg", "one")), "");
        LocalDate shared = LocalDate.of(2026, 7, 18);
        library.markShared("work-3", shared);
        library.maintain(shared.plusDays(1));
        assertFalse(library.listTrash().isEmpty());

        library.maintain(shared.plusDays(8));
        assertTrue(library.listTrash().isEmpty());
    }

    @Test
    public void clearsTrashWithoutTouchingActiveWorks() throws Exception {
        File source = temporary.newFolder("clear-source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("clear-library"));
        library.importWork("keep", "保留", "文案", Arrays.asList(write(source, "keep.jpg", "one")), "");
        library.importWork("remove", "清理", "文案", Arrays.asList(write(source, "remove.jpg", "two")), "");
        LocalDate shared = LocalDate.of(2026, 7, 18);
        library.markShared("remove", shared);
        library.maintain(shared.plusDays(1));

        library.clearTrash();

        assertEquals(1, library.listActive().size());
        assertTrue(library.listTrash().isEmpty());
    }

    @Test
    public void countsEachOpenedShareTarget() throws Exception {
        File source = temporary.newFolder("count-source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("count-library"));
        library.importWork("count", "计数", "文案", Arrays.asList(write(source, "1.jpg", "one")), "");

        library.markShared("count", LocalDate.of(2026, 7, 18));
        library.markShared("count", LocalDate.of(2026, 7, 18));

        assertEquals(2, library.getActive("count").shareCount);
    }

    private static File write(File directory, String name, String value) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
