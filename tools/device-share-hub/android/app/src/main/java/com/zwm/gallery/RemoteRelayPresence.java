package com.zwm.gallery;

import android.util.Log;

import android.os.SystemClock;
import org.json.JSONArray;
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
        long lastInboxPollAt = 0L;
        AndroidP2pReceiver p2p = null;
        String lastP2pId = "";
        while (active && service.isServiceRunning()) {
            try {
                if (!RemoteRelayProfile.isEnrolled(service)) {
                    Thread.sleep(6_000L);
                    continue;
                }
                RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(service);
                long now = SystemClock.elapsedRealtime();
                if (lastHeartbeatAt == 0L || now - lastHeartbeatAt >= 12_000L) {
                    JSONObject info = service.buildPublicDeviceInfo();
                    RemoteRelayClient.heartbeat(session, info);
                    lastHeartbeatAt = now;
                }
                if (OnlineService.isAutoReceiveEnabled(service)) {
                    if (p2p == null || p2p.isClosed()) {
                        JSONArray pending = RemoteRelayClient.p2pRequest(session, "", null).optJSONArray("sessions");
                        for (int i = 0; pending != null && i < pending.length(); i++) {
                            JSONObject item = pending.getJSONObject(i);
                            String id = item.optString("sessionId");
                            if (AndroidP2pReceiver.safeId(id) && !id.equals(lastP2pId)
                                    && session.deviceId.equals(item.optString("responderDeviceId"))
                                    && "webrtc-datachannel-v1".equals(item.optString("protocol"))
                                    && "work".equals(item.optString("contentKind", "work"))
                                    && !"closed".equals(item.optString("state"))
                                    && !"failed".equals(item.optString("state"))) {
                                p2p = new AndroidP2pReceiver(service, session, item);
                                lastP2pId = id;
                                break;
                            }
                        }
                    }
                    if (lastInboxPollAt == 0L || now - lastInboxPollAt >= 8_000L) {
                        RemoteRelayTask.processInbox(service, session);
                        lastInboxPollAt = now;
                    }
                } else if (p2p != null) {
                    p2p.cancel();
                    p2p = null;
                }
                Thread.sleep(1_500L);
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
        if (p2p != null) p2p.cancel();
    }
}
