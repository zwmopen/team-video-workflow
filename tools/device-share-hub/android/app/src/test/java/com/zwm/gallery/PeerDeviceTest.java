package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class PeerDeviceTest {
    @Test public void parsesOptionalWorkCountWithoutBreakingOldPackets() {
        assertEquals(15, PeerDevice.parseWorkCount("15"));
        assertEquals(0, PeerDevice.parseWorkCount("0"));
        assertEquals(-1, PeerDevice.parseWorkCount(""));
        assertEquals(-1, PeerDevice.parseWorkCount("unknown"));
    }

    @Test public void countChangeRefreshesPeerDisplay() {
        PeerDevice before = new PeerDevice("id", "手机", "Android", "127.0.0.1", 45833,
                "online", 1, 100);
        PeerDevice after = new PeerDevice("id", "手机", "Android", "127.0.0.1", 45833,
                "online", 2, 200);
        assertFalse(after.equalsForDisplay(before));
    }
}
