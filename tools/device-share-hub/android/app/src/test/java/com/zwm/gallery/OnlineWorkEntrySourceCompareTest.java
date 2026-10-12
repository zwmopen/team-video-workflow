package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OnlineWorkEntrySourceCompareTest {
    @Test
    public void fromJsonKeepsEmptySourceSlotsAlignedWithOutputPages() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "source-slot-work");
        json.put("images", new JSONArray().put("P1.png").put("P2.png").put("P3.png"));
        json.put("sourceImages", new JSONArray().put("").put("__src__:P2.png").put("__src__:P3.png"));
        json.put("sourceNames", new JSONArray().put("").put("P2.jpg").put("P3.jpg"));

        OnlineWorkEntry entry = OnlineWorkEntry.fromJson(json);

        assertEquals(3, entry.images.size());
        assertEquals(3, entry.sourceImages.size());
        assertEquals("", entry.sourceImages.get(0));
        assertEquals("__src__:P2.png", entry.sourceImages.get(1));
        assertEquals("P2.jpg", entry.sourceNames.get(1));
    }

    @Test
    public void creationTimeWinsAndMissingTimeDoesNotBecomeDeviceNow() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "20261012_120000_work");
        json.put("createdAtMs", 1_800_000_000_000L);
        json.put("updatedAt", 1_900_000_000_000L);

        OnlineWorkEntry entry = OnlineWorkEntry.fromJson(json);
        assertEquals(1_800_000_000_000L, entry.updatedAt);

        JSONObject missing = new JSONObject();
        missing.put("id", "legacy-work-without-time");
        assertEquals(0L, OnlineWorkEntry.fromJson(missing).updatedAt);
    }
}
