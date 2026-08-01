package com.zwm.gallery;

import android.app.ActivityManager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateDownloadReceiverTest {
    @Test
    public void autoOpensInstallerFromAnyForegroundAppScreen() {
        assertTrue(UpdateDownloadReceiver.shouldAutoOpenInstaller(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND));
        assertFalse(UpdateDownloadReceiver.shouldAutoOpenInstaller(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE));
        assertFalse(UpdateDownloadReceiver.shouldAutoOpenInstaller(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED));
    }
}
