package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test public void usbNetworkNamesAreRecognizedWithoutMislabelingWifi() {
        assertTrue(OnlineService.isUsbNetworkInterface("rndis0"));
        assertTrue(OnlineService.isUsbNetworkInterface("usb0"));
        assertTrue(OnlineService.isUsbNetworkInterface("ncm0"));
        assertFalse(OnlineService.isUsbNetworkInterface("wlan0"));
        assertEquals("USB", PeerDevice.normalizeTransport("usb"));
        assertEquals("WiFi", PeerDevice.normalizeTransport("unknown"));
    }

    @Test public void subnetMatchingUsesTheInterfacePrefix() {
        assertTrue(OnlineService.sameSubnet(
                new byte[]{(byte) 192, (byte) 168, 42, (byte) 129},
                new byte[]{(byte) 192, (byte) 168, 42, 2}, 24));
        assertFalse(OnlineService.sameSubnet(
                new byte[]{(byte) 192, (byte) 168, 42, (byte) 129},
                new byte[]{(byte) 192, (byte) 168, 43, 2}, 24));
    }
}
