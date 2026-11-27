package com.zwm.gallery;

import android.util.Log;

import org.json.JSONObject;

/**
 * Maintains presence heartbeat and inbox polling against zwm-device-share-relay when enrolled.
 * Sleeps cleanly on un-enrolled devices (zero background requests).
 */
public final class RemoteRelayPresence {
    private static final String TAG = "RemoteRelayPresence";
    private final OnlineService service;
    private volatile boolean active = false;
    private Thread worker;

    public RemoteRelayPresence(OnlineService service) {
        this.service = service;
    }

    public synchronized void start() {
        if (active) return;
        active = true;
        worker = new Thread(this::runLoop, "RemoteRelayPresence");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        active = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void runLoop() {
        long lastHeartbeatAt = 0L;
        while (active && service.isServiceRunning()) {
            try {
                if (!RemoteRelayProfile.isEnrolled(service)) {
                    Thread.sleep(6_000L);
                    continue;
                }
                RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(service);
                long now = System.currentTimeMillis();
                if (now - lastHeartbeatAt >= 20_000L) {
                    JSONObject info = service.buildPublicDeviceInfo();
                    RemoteRelayClient.heartbeat(session, info);
                    lastHeartbeatAt = now;
                }
                if (OnlineService.isAutoReceiveEnabled(service)) {
                    RemoteRelayTask.processInbox(service, session);
                }
                Thread.sleep(8_000L);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "Relay presence loop warning: " + e.getMessage());
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }
}
