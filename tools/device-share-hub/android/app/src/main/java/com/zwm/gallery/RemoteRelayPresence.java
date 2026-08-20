package com.zwm.gallery;

import android.content.Context;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Re-authenticates and publishes relay presence only after a profile was enrolled. */
final class RemoteRelayPresence {
    private final Context context;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile RemoteRelayClient.Session session;
    private volatile boolean started;
    private String profileKey = "";
    private long retryAfterMs;
    private long failureDelayMs;

    RemoteRelayPresence(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (started) return;
        started = true;
        executor.scheduleWithFixedDelay(this::tick, 0, 10, TimeUnit.SECONDS);
    }

    void stop() {
        started = false;
        session = null;
        executor.shutdownNow();
    }

    private void tick() {
        if (!started) return;
        RemoteRelayProfile.Profile profile = RemoteRelayProfile.load(context);
        if (profile == null) {
            session = null;
            profileKey = "";
            retryAfterMs = 0;
            failureDelayMs = 0;
            return;
        }
        String nextProfileKey = profile.endpoint + "\n" + profile.certificateSignature
                + "\n" + profile.certificate.optString("deviceId", "");
        if (!nextProfileKey.equals(profileKey)) {
            profileKey = nextProfileKey;
            session = null;
            retryAfterMs = 0;
            failureDelayMs = 0;
        }
        long now = System.currentTimeMillis();
        if (now < retryAfterMs) return;
        try {
            RemoteRelayClient.Session current = session;
            if (current == null || current.expired(System.currentTimeMillis())
                    || !current.endpoint.equals(profile.endpoint)
                    || !current.deviceId.equals(profile.certificate.getString("deviceId"))) {
                current = RemoteRelayClient.createSession(context, profile.endpoint,
                        profile.certificate, profile.certificateSignature);
                session = current;
            }
            RemoteRelayClient.heartbeat(current);
            retryAfterMs = 0;
            failureDelayMs = 0;
        } catch (Exception error) {
            session = null;
            long delay = failureDelayMs <= 0 ? 10_000L : Math.min(5 * 60_000L, failureDelayMs * 2);
            failureDelayMs = delay;
            retryAfterMs = now + delay;
            DiagnosticLog.write(context, "remote_presence_failed", error.getClass().getSimpleName());
        }
    }
}
