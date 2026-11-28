package com.zwm.gallery;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;

/** A short-lived DataChannel opened while the remote catalog is already visible. */
final class CatalogDirectClient implements PeerConnection.Observer {
    private static final String TAG = "CatalogDirect";
    private static final long MAX_SIZE = 32L * 1024 * 1024;
    private static CatalogDirectClient current;

    private final RemoteRelayClient.Session session;
    private final String desktop;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final Map<String, Pending> requests = new HashMap<>();
    private final long created = SystemClock.elapsedRealtime();
    private volatile boolean ready;
    private volatile boolean closed;
    private boolean offerSent;
    private String route;
    private PeerConnection peer;
    private DataChannel channel;

    static synchronized void prewarm(Context context, RemoteRelayClient.Session session, String desktop) {
        if (!AndroidP2pReceiver.safeId(desktop) || desktop.equals(session.deviceId)
                || RemoteRelayProfile.getStrategy(context) == RemoteRelayProfile.TransportStrategy.R2_FIRST) return;
        if (current != null && !current.closed && current.session.token.equals(session.token)) return;
        if (current != null && !current.closed) current.post(current::close);
        current = new CatalogDirectClient(context.getApplicationContext(), session, desktop);
    }

    static boolean download(RemoteRelayClient.Session session, String workId, String file, File destination) {
        CatalogDirectClient active;
        synchronized (CatalogDirectClient.class) {
            active = current;
        }
        return active != null && active.ready && !active.closed && active.session.token.equals(session.token)
                && active.fetch(workId, file, destination);
    }

    private CatalogDirectClient(Context context, RemoteRelayClient.Session session, String desktop) {
        this.session = session;
        this.desktop = desktop;
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        post(() -> start(context));
    }

    private void post(Runnable action) {
        try { worker.execute(action); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void start(Context context) {
        try {
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(
                    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = AndroidP2pReceiver.factory(context).createPeerConnection(config, this);
            if (peer == null) throw new IllegalStateException("Peer unavailable");
            channel = peer.createDataChannel("album-catalog-original-v1", new DataChannel.Init());
            channel.registerObserver(new DataChannel.Observer() {
                @Override public void onBufferedAmountChange(long previousAmount) {}
                @Override public void onStateChange() { post(CatalogDirectClient.this::channelChanged); }
                @Override public void onMessage(DataChannel.Buffer frame) {
                    ByteBuffer buffer = frame.data.duplicate();
                    if (buffer.remaining() > 32 * 1024 + 20) return;
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    post(() -> receive(bytes, frame.binary));
                }
            });
            peer.createOffer(new Sdp() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    post(() -> {
                        if (!closed) peer.setLocalDescription(new Sdp(), offer);
                    });
                }
            }, new MediaConstraints());
            worker.scheduleWithFixedDelay(this::poll, 500, 700, TimeUnit.MILLISECONDS);
            worker.schedule(this::close, 105, TimeUnit.SECONDS);
        } catch (Exception | LinkageError error) { close(); }
    }

    private void channelChanged() {
        if (closed) return;
        if (channel.state() == DataChannel.State.CLOSED) {
            close();
        } else if (channel.state() == DataChannel.State.OPEN) {
            ready = true;
            Log.i(TAG, "Catalog P2P channel ready after " + (SystemClock.elapsedRealtime() - created) + "ms");
        }
    }

    private void poll() {
        if (closed || ready) return;
        if (SystemClock.elapsedRealtime() - created > 23_000L) {
            close();
            return;
        }
        try {
            if (!offerSent) {
                if (peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return;
                JSONObject open = new JSONObject().put("recipientDeviceId", desktop);
                String id = RemoteRelayClient.p2pRequest(session, "", open).getJSONObject("p2p").getString("sessionId");
                if (!AndroidP2pReceiver.safeId(id)) throw new IllegalStateException("Invalid session");
                route = "/" + id;
                JSONObject offer = new JSONObject().put("type", "offer")
                        .put("catalogOriginals", 1).put("sdp", peer.getLocalDescription().description);
                RemoteRelayClient.p2pRequest(session, route + "/signals",
                        new JSONObject().put("type", "offer").put("data", offer));
                offerSent = true;
                Log.i(TAG, "Catalog P2P offer sent");
                return;
            }
            if (peer.getRemoteDescription() != null) return;
            JSONObject state = RemoteRelayClient.p2pRequest(session, route, null).getJSONObject("p2p");
            JSONArray signals = state.getJSONArray("signals");
            for (int index = 0; index < signals.length(); index++) {
                JSONObject signal = signals.getJSONObject(index);
                if ("answer".equals(signal.optString("type")) && desktop.equals(signal.optString("fromDeviceId"))) {
                    peer.setRemoteDescription(new Sdp(), new SessionDescription(SessionDescription.Type.ANSWER,
                            signal.getJSONObject("data").getString("sdp")));
                    Log.i(TAG, "Catalog P2P answer received");
                    break;
                }
            }
        } catch (Exception failure) { close(); }
    }

    private boolean fetch(String workId, String file, File destination) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Pending pending = new Pending(new File(destination.getPath() + "." + id + ".p2p"));
        long start = SystemClock.elapsedRealtime();
        post(() -> {
            try {
                if (!ready || requests.size() >= 4) throw new IllegalStateException("Direct channel busy");
                requests.put(id, pending);
                JSONObject json = new JSONObject().put("id", id).put("workId", workId).put("fileName", file);
                if (!channel.send(new DataChannel.Buffer(ByteBuffer.wrap(
                        json.toString().getBytes(StandardCharsets.UTF_8)), false))) {
                    throw new IllegalStateException("Direct channel unavailable");
                }
            } catch (Exception failure) { finish(id, false); pending.result.complete(false); }
        });
        try {
            if (!pending.result.get(3, TimeUnit.SECONDS)) return false;
            Files.move(pending.file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(TAG, "Catalog P2P original verified bytes=" + destination.length()
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            Log.i(TAG, "Catalog P2P image request will fall back to R2");
            return false;
        } finally {
            post(() -> {
                finish(id, false);
                pending.file.delete();
            });
        }
    }

    private void receive(byte[] bytes, boolean binary) {
        String id = "";
        try {
            if (!binary) {
                JSONObject header = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                id = header.getString("id");
                Pending pending = requests.get(id);
                if (pending == null) return;
                if (header.optBoolean("error")) { finish(id, false); return; }
                pending.size = header.getLong("bytes");
                pending.sha = header.getString("sha256");
                if (pending.stream != null || pending.size <= 0 || pending.size > MAX_SIZE
                        || !pending.sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid size or checksum");
                pending.stream = new FileOutputStream(pending.file);
                return;
            }
            if (bytes.length <= 20) return;
            StringBuilder hex = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                int value = bytes[index] & 255;
                hex.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 15, 16));
            }
            id = hex.toString();
            Pending pending = requests.get(id);
            if (pending == null) return;
            int offset = ByteBuffer.wrap(bytes, 16, 4).getInt();
            int count = bytes.length - 20;
            if (pending.stream == null || offset != pending.received
                    || count > pending.size - pending.received) throw new IllegalArgumentException("Invalid offset");
            pending.stream.write(bytes, 20, count);
            pending.received += count;
            if (pending.received == pending.size) {
                pending.stream.close();
                pending.stream = null;
                finish(id, pending.sha.equals(RemoteProtocol.sha256FileHex(pending.file)));
            }
        } catch (Exception invalid) { finish(id, false); }
    }

    private void finish(String id, boolean success) {
        Pending pending = requests.remove(id);
        if (pending == null) return;
        if (pending.stream != null) {
            try { pending.stream.close(); } catch (java.io.IOException ignored) {}
        }
        if (!success) pending.file.delete();
        pending.result.complete(success);
    }

    private void close() {
        if (closed) return;
        closed = true;
        ready = false;
        for (String id : new java.util.ArrayList<>(requests.keySet())) finish(id, false);
        if (channel != null) { channel.unregisterObserver(); channel.close(); channel.dispose(); }
        if (peer != null) peer.dispose();
        worker.shutdown();
    }

    private static final class Pending {
        final File file;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        FileOutputStream stream;
        String sha;
        long size;
        long received;
        Pending(File file) { this.file = file; }
    }

    private class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { post(CatalogDirectClient.this::close); }
        @Override public void onSetFailure(String error) { post(CatalogDirectClient.this::close); }
    }
    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
        if (state == PeerConnection.IceConnectionState.FAILED) post(this::close);
    }
    @Override public void onDataChannel(DataChannel other) { other.close(); other.dispose(); }
    @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
    @Override public void onIceConnectionReceivingChange(boolean receiving) {}
    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
    @Override public void onIceCandidate(IceCandidate candidate) {}
    @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
    @Override public void onAddStream(MediaStream stream) {}
    @Override public void onRemoveStream(MediaStream stream) {}
    @Override public void onRenegotiationNeeded() {}
}
