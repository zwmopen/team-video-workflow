package com.zwm.gallery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SharedClipboardStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void mergesAddsEditsAndDeletesByLatestTimestamp() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("clipboard"));
        SharedClipboardStore.Item added = store.add(
                "phone-a", SharedClipboardStore.KIND_PHRASE, "你好", 100);
        store.merge(Arrays.asList(new SharedClipboardStore.Item(
                added.id, SharedClipboardStore.KIND_PHRASE, "旧内容", 99, false)));
        assertEquals("你好", store.visible(SharedClipboardStore.KIND_PHRASE).get(0).text);

        store.merge(Arrays.asList(new SharedClipboardStore.Item(
                added.id, SharedClipboardStore.KIND_PHRASE, "新内容", 101, false)));
        assertEquals("新内容", store.visible(SharedClipboardStore.KIND_PHRASE).get(0).text);

        store.delete(added.id, 102);
        assertTrue(store.visible(SharedClipboardStore.KIND_PHRASE).isEmpty());
        assertTrue(store.all().get(0).deleted);
    }

    @Test
    public void keepsClipboardHistoryNewestFirst() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("history"));
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "第一条", 100);
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "第二条", 200);
        assertEquals("第二条", store.newestClipboard().text);
        assertEquals(2, store.visible(SharedClipboardStore.KIND_CLIPBOARD).size());
    }

    @Test
    public void seedsFrontDeskPhrasesOnceAndKeepsDeletion() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("defaults"));
        assertEquals(8, ClipboardDefaults.ensure(store));
        assertEquals(0, ClipboardDefaults.ensure(store));
        assertEquals(8, store.visible(SharedClipboardStore.KIND_PHRASE).size());

        SharedClipboardStore.Item first =
                store.visible(SharedClipboardStore.KIND_PHRASE).get(0);
        store.delete(first.id, 100);
        assertEquals(0, ClipboardDefaults.ensure(store));
        assertEquals(7, store.visible(SharedClipboardStore.KIND_PHRASE).size());
    }

    @Test
    public void syncsAllPhrasesButOnlyNewestClipboardValue() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("snapshot"));
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "旧内容", 100);
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "最新内容", 200);
        store.add("phone-a", SharedClipboardStore.KIND_PHRASE, "常用语一", 50);
        store.add("phone-a", SharedClipboardStore.KIND_PHRASE, "常用语二", 60);

        java.util.List<SharedClipboardStore.Item> snapshot = store.syncSnapshot();
        assertEquals(3, snapshot.size());
        assertEquals("最新内容", snapshot.get(0).text);
        assertEquals(2, snapshot.stream()
                .filter(item -> SharedClipboardStore.KIND_PHRASE.equals(item.kind)).count());
    }

    @Test
    public void receivingDeviceKeepsOnlyLatestValueFromEachSender() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("received"));
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "旧内容", 100);
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "最新内容", 200);
        store.add("phone-b", SharedClipboardStore.KIND_CLIPBOARD, "另一台", 150);

        assertEquals(1, store.retainLatestClipboardFrom("phone-a", 300));
        assertEquals(2, store.visible(SharedClipboardStore.KIND_CLIPBOARD).size());
        assertEquals("最新内容", store.visible(SharedClipboardStore.KIND_CLIPBOARD).get(0).text);
    }

    @Test
    public void equalTimestampConflictsConvergeDeterministically() throws Exception {
        SharedClipboardStore left = new SharedClipboardStore(temporary.newFolder("conflict-left"));
        SharedClipboardStore right = new SharedClipboardStore(temporary.newFolder("conflict-right"));
        SharedClipboardStore.Item a = new SharedClipboardStore.Item(
                "shared-phrase", SharedClipboardStore.KIND_PHRASE, "A", 100, false);
        SharedClipboardStore.Item b = new SharedClipboardStore.Item(
                "shared-phrase", SharedClipboardStore.KIND_PHRASE, "B", 100, false);

        left.merge(Arrays.asList(a));
        right.merge(Arrays.asList(b));
        left.merge(Arrays.asList(b));
        right.merge(Arrays.asList(a));

        assertEquals("B", left.visible(SharedClipboardStore.KIND_PHRASE).get(0).text);
        assertEquals("B", right.visible(SharedClipboardStore.KIND_PHRASE).get(0).text);
    }

    @Test
    public void compactsClipboardToOnePhysicalCurrentValue() throws Exception {
        SharedClipboardStore store = new SharedClipboardStore(temporary.newFolder("compact"));
        store.add("phone-a", SharedClipboardStore.KIND_CLIPBOARD, "第一条", 100);
        store.add("phone-b", SharedClipboardStore.KIND_CLIPBOARD, "第二条", 200);
        store.add("phone-c", SharedClipboardStore.KIND_CLIPBOARD, "第三条", 300);

        assertEquals(2, store.retainNewestClipboardOnly());
        assertEquals(1, store.all().size());
        assertEquals("第三条", store.newestClipboard().text);
    }

    @Test
    public void separateStoreInstancesSerializeWritesToSameDirectory() throws Exception {
        java.io.File root = temporary.newFolder("concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<?>> writes = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            final int value = index;
            writes.add(executor.submit(() -> {
                new SharedClipboardStore(root).put(
                        "shared-phrase", SharedClipboardStore.KIND_PHRASE,
                        "内容-" + value, 100);
                return null;
            }));
        }
        for (Future<?> write : writes) write.get();
        executor.shutdownNow();

        SharedClipboardStore store = new SharedClipboardStore(root);
        assertEquals(1, store.all().size());
        assertEquals(0, root.listFiles((dir, name) -> name.endsWith(".tmp")).length);
    }
}
