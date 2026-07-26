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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    public void importDropsByteIdenticalImagesWithDifferentNames() throws Exception {
        File source = temporary.newFolder("duplicate-images");
        File first = write(source, "1.png", "same");
        File second = write(source, "2.png", "same");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("dedup-images-library"));

        library.importWork("work-images", "作品", "文案", Arrays.asList(first, second), "");

        assertEquals(1, library.getActive("work-images").images.size());
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

    @Test
    public void recordsEveryTapImmediatelyAndMovesAfterConfiguredDelay() throws Exception {
        File source = temporary.newFolder("tap-source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("tap-library"));
        library.importWork("tap", "点击记账", "文案",
                Arrays.asList(write(source, "1.jpg", "one")), "");
        long firstTap = 1_000_000L;

        library.markShareAttempt("tap", firstTap);
        library.markShareAttempt("tap", firstTap + 5_000L);

        assertEquals(2, library.getActive("tap").shareCount);
        assertEquals(firstTap, library.getActive("tap").firstSharedAtMs);
        library.maintain(firstTap + 3_599_999L, 3_600_000L);
        assertEquals(1, library.listActive().size());
        library.maintain(firstTap + 3_600_000L, 3_600_000L);
        assertTrue(library.listActive().isEmpty());
        assertEquals(1, library.listTrash().size());
    }

    @Test
    public void reopeningForAnAppUpgradePreservesCountsAndTrash() throws Exception {
        File source = temporary.newFolder("upgrade-source");
        File root = temporary.newFolder("upgrade-library");
        WorkLibrary beforeUpgrade = new WorkLibrary(root);
        beforeUpgrade.importWork("active", "未分享作品", "文案",
                Arrays.asList(write(source, "active.jpg", "one")), "");
        beforeUpgrade.importWork("shared", "已分享作品", "文案",
                Arrays.asList(write(source, "shared.jpg", "two")), "");
        LocalDate firstDay = LocalDate.of(2026, 7, 20);
        beforeUpgrade.markShared("shared", firstDay);
        beforeUpgrade.markShared("shared", firstDay);
        beforeUpgrade.maintain(firstDay.plusDays(1));

        // Opening the same app-private directory models an in-place APK upgrade.
        WorkLibrary afterUpgrade = new WorkLibrary(root);

        assertEquals(1, afterUpgrade.listActive().size());
        assertEquals("active", afterUpgrade.listActive().get(0).id);
        assertEquals(1, afterUpgrade.listTrash().size());
        assertEquals("shared", afterUpgrade.listTrash().get(0).id);
        assertEquals(2, afterUpgrade.listTrash().get(0).shareCount);
        assertEquals(firstDay.plusDays(1), afterUpgrade.listTrash().get(0).trashedDate);
    }

    @Test
    public void manualTrashMovePreservesShareCountAndCanRollbackWithoutLosingIt() throws Exception {
        File source = temporary.newFolder("manual-trash-source");
        WorkLibrary library = new WorkLibrary(temporary.newFolder("manual-trash-library"));
        library.importWork("manual", "手动回收", "文案",
                Arrays.asList(write(source, "manual.jpg", "one")), "");
        LocalDate today = LocalDate.of(2026, 7, 20);
        library.markShared("manual", today);

        library.moveToTrash("manual", today);
        assertTrue(library.listActive().isEmpty());
        assertEquals(1, library.getTrash("manual").shareCount);

        library.rollbackTrashMove("manual");
        assertTrue(library.listTrash().isEmpty());
        assertEquals(1, library.getActive("manual").shareCount);
        assertEquals(today, library.getActive("manual").sharedDate);
    }

    @Test
    public void reopeningCollapsesExistingSafAndLegacyCopies() throws Exception {
        File source = temporary.newFolder("duplicate-source");
        File image = write(source, "1.jpg", "one");
        File root = temporary.newFolder("duplicate-library");
        WorkLibrary library = new WorkLibrary(root);
        library.importWork(
                "lark-one", ".同一个作品", "文案", Arrays.asList(image), "",
                "primary:Download/Lark/作品集/.同一个作品",
                "primary:Download/Lark/作品集", "");
        library.importWork(
                "huawei-hidden-one", "同一个作品", "文案", Arrays.asList(image), "",
                "", "", "作品集" + File.separator + ".同一个作品");
        library.markShared("lark-one", LocalDate.of(2026, 7, 18));
        library.markShared("huawei-hidden-one", LocalDate.of(2026, 7, 18));
        library.markShared("huawei-hidden-one", LocalDate.of(2026, 7, 19));

        WorkLibrary reopened = new WorkLibrary(root);

        assertEquals(1, reopened.listActive().size());
        WorkLibrary.WorkEntry retained = reopened.listActive().get(0);
        assertEquals("lark-one", retained.id);
        assertEquals(3, retained.shareCount);
        assertEquals("primary:Download/Lark/作品集/.同一个作品", retained.sourceDocumentId);
        assertEquals("作品集" + File.separator + ".同一个作品", retained.sourceRelativePath);
    }

    @Test
    public void trashedSafCopyPreventsLegacyCopyFromReappearingAsActive() throws Exception {
        File source = temporary.newFolder("cross-area-source");
        File image = write(source, "1.jpg", "one");
        File root = temporary.newFolder("cross-area-library");
        WorkLibrary library = new WorkLibrary(root);
        library.importWork("lark-one", ".同一个作品", "文案", Arrays.asList(image), "",
                "primary:Download/Lark/作品集/.同一个作品",
                "primary:Download/Lark/作品集", "");
        library.importWork("huawei-hidden-one", "同一个作品", "文案", Arrays.asList(image), "",
                "", "", "作品集" + File.separator + ".同一个作品");
        library.markShared("lark-one", LocalDate.of(2026, 7, 26));
        library.moveToTrash("lark-one", LocalDate.of(2026, 7, 26));

        WorkLibrary reopened = new WorkLibrary(root);

        assertTrue(reopened.listActive().isEmpty());
        assertEquals(1, reopened.listTrash().size());
        assertEquals(1, reopened.listTrash().get(0).shareCount);
    }

    @Test
    public void identicalContentInTrashPreventsPathlessCopyFromReappearing() throws Exception {
        File source = temporary.newFolder("fingerprint-source");
        File image = write(source, "1.jpg", "same-image");
        File root = temporary.newFolder("fingerprint-library");
        WorkLibrary library = new WorkLibrary(root);
        library.importWork("old-copy", "旧副本", "同一文案", Arrays.asList(image), "");
        library.importWork("new-copy", "新副本", "同一文案", Arrays.asList(image), "");
        library.markShared("old-copy", LocalDate.of(2026, 7, 26));
        library.moveToTrash("old-copy", LocalDate.of(2026, 7, 26));

        WorkLibrary reopened = new WorkLibrary(root);

        assertTrue(reopened.listActive().isEmpty());
        assertEquals(1, reopened.listTrash().size());
        assertEquals(1, reopened.listTrash().get(0).shareCount);
    }

    @Test
    public void concurrentReopenDoesNotRaceDuringDuplicateMigration() throws Exception {
        File source = temporary.newFolder("concurrent-source");
        File image = write(source, "1.jpg", "one");
        File root = temporary.newFolder("concurrent-library");
        WorkLibrary library = new WorkLibrary(root);
        for (int index = 0; index < 24; index++) {
            String relative = "作品集/.作品-" + index;
            library.importWork(
                    "lark-" + index, ".作品-" + index, "文案", Arrays.asList(image), "",
                    "primary:Download/Lark/" + relative,
                    "primary:Download/Lark/作品集", "");
            library.importWork(
                    "huawei-hidden-" + index, "作品-" + index, "文案", Arrays.asList(image), "",
                    "", "", relative.replace('/', File.separatorChar));
        }
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<WorkLibrary>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return new WorkLibrary(root);
            }));
        }
        ready.await();
        start.countDown();
        for (Future<WorkLibrary> future : futures) future.get();
        executor.shutdownNow();

        assertEquals(24, new WorkLibrary(root).listActive().size());
    }

    private static File write(File directory, String name, String value) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
