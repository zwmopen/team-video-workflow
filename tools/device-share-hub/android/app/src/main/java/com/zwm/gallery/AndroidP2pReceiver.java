package com.zwm.gallery;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;

final class AndroidP2pReceiver {
    private static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long ACK_FLUSH_DELAY_MS = 600L;
    private static final String LABEL = "album-transfer-v1";
    private static PeerConnectionFactory factory;

    private final OnlineService service;
    private final RemoteRelayClient.Session session;
    private final String id;
    private final String sender;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final Set<String> signals = new HashSet<>();
    private final List<IceCandidate> pending = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> mimes = new ArrayList<>();
    private final List<String> hashes = new ArrayList<>();
    private final List<Long> sizes = new ArrayList<>();
    private PeerConnection peer;
    private DataChannel channel;
    private File directory;
    private FileOutputStream output;
    private String transferId;
    private int current;
    private long offset;
    private long total;
    private boolean appliedOffer;
    private boolean remoteReady;
    private boolean done;
    private volatile boolean closed;
    private long lastActivity = android.os.SystemClock.elapsedRealtime();

    AndroidP2pReceiver(OnlineService service, RemoteRelayClient.Session session, JSONObject metadata) {
        this.service = service;
        this.session = session;
        id = metadata.optString("sessionId");
        sender = metadata.optString("initiatorDeviceId");
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.execute(this::start);
    }

    boolean isClosed() { return closed; }
    void cancel() { enqueue(this::close); }

    private void enqueue(Runnable action) {
        try { worker.execute(action); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    static synchronized PeerConnectionFactory factory(android.content.Context service) {
        if (factory == null) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(service.getApplicationContext()).createInitializationOptions());
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        }
        return factory;
    }

    private void start() {
        try {
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(java.util.Collections.singletonList(
                    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory(service).createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                @Override public void onAddStream(MediaStream stream) {}
                @Override public void onRemoveStream(MediaStream stream) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.FAILED) enqueue(() -> fail("ICE failed"));
                }
                @Override public void onIceCandidate(IceCandidate ice) {
                    enqueue(() -> {
                        try {
                            if (!done) signal("ice", new JSONObject().put("mid", ice.sdpMid)
                                    .put("mLineIndex", ice.sdpMLineIndex).put("candidate", ice.sdp));
                        } catch (Exception failure) { fail("ICE signaling failed"); }
                    });
                }
                @Override public void onDataChannel(DataChannel data) { enqueue(() -> bind(data)); }
            });
            if (peer == null) throw new IllegalStateException("Peer connection unavailable");
            worker.scheduleWithFixedDelay(this::poll, 0, 400, TimeUnit.MILLISECONDS);
        } catch (Exception | LinkageError failure) {
            fail("WebRTC initialization failed: " + failure.getClass().getSimpleName());
        }
    }

    private void poll() {
        if (done) return;
        if (!OnlineService.isAutoReceiveEnabled(service)) {
            close();
            return;
        }
        long idle = android.os.SystemClock.elapsedRealtime() - lastActivity;
        if (idle > (channel == null || channel.state() != DataChannel.State.OPEN ? 22_000 : 70_000)) {
            fail("P2P timeout");
            return;
        }
        if (channel != null && channel.state() == DataChannel.State.OPEN) return;
        try {
            JSONObject state = RemoteRelayClient.p2pRequest(session, "/" + id, null).getJSONObject("p2p");
            String status = state.optString("state");
            if ("closed".equals(status) || "failed".equals(status)) {
                close();
                return;
            }
            JSONArray events = state.optJSONArray("signals");
            if (events == null) return;
            for (int index = 0; index < events.length(); index++) {
                JSONObject event = events.getJSONObject(index);
                if (!sender.equals(event.optString("fromDeviceId"))) continue;
                JSONObject data = event.getJSONObject("data");
                if (!signals.add(event.optString("type") + ":" + data)) continue;
                if ("offer".equals(event.optString("type")) && !appliedOffer) {
                    appliedOffer = true;
                    peer.setRemoteDescription(new Sdp() {
                        @Override public void onSetSuccess() { enqueue(AndroidP2pReceiver.this::answer); }
                    }, new SessionDescription(SessionDescription.Type.OFFER, data.getString("sdp")));
                } else if ("ice".equals(event.optString("type"))) {
                    IceCandidate ice = new IceCandidate(data.optString("mid"),
                            data.optInt("mLineIndex"), data.getString("candidate"));
                    if (remoteReady) peer.addIceCandidate(ice);
                    else pending.add(ice);
                }
            }
        } catch (Exception failure) {
            android.util.Log.w("AndroidP2p", "Signal poll: " + failure.getClass().getSimpleName());
        }
    }

    private void answer() {
        if (done) return;
        remoteReady = true;
        for (IceCandidate candidate : pending) peer.addIceCandidate(candidate);
        pending.clear();
        peer.createAnswer(new Sdp() {
            @Override public void onCreateSuccess(SessionDescription description) {
                enqueue(() -> {
                    if (done) return;
                    peer.setLocalDescription(new Sdp() {
                        @Override public void onSetSuccess() {
                            enqueue(() -> {
                                try {
                                    if (!done) signal("answer", new JSONObject().put("type", "answer")
                                            .put("sdp", peer.getLocalDescription().description));
                                } catch (Exception failure) { fail("Answer signaling failed"); }
                            });
                        }
                    }, description);
                });
            }
        }, new MediaConstraints());
    }

    private void signal(String type, JSONObject data) throws Exception {
        RemoteRelayClient.p2pRequest(session, "/" + id + "/signals",
                new JSONObject().put("type", type).put("data", data));
    }

    private void bind(DataChannel data) {
        if (done || channel != null || !LABEL.equals(data.label())) {
            data.close();
            data.dispose();
            return;
        }
        channel = data;
        channel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {}
            @Override public void onStateChange() {
                enqueue(() -> {
                    if (!done && channel.state() == DataChannel.State.CLOSED) fail("Data channel closed");
                });
            }
            @Override public void onMessage(DataChannel.Buffer frame) {
                int size = frame.data.remaining();
                if (size > (frame.binary ? 20 + 48 * 1024 : 512 * 1024)) {
                    enqueue(() -> fail("P2P frame too large"));
                    return;
                }
                byte[] bytes = new byte[size];
                frame.data.duplicate().get(bytes);
                enqueue(() -> receive(bytes, frame.binary));
            }
        });
    }

    private void receive(byte[] bytes, boolean binary) {
        if (done) return;
        lastActivity = android.os.SystemClock.elapsedRealtime();
        try {
            if (binary) {
                ByteBuffer header = ByteBuffer.wrap(bytes);
                require(output != null && bytes.length > 20 && header.getInt() == 0x44534850
                        && header.getInt() == 0x01010000 && header.getInt() == current
                        && header.getLong() == offset && bytes.length - 20 <= sizes.get(current) - offset);
                output.write(bytes, 20, bytes.length - 20);
                offset += bytes.length - 20;
                return;
            }
            JSONObject text = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            require(text.getInt("v") == 1);
            switch (text.getString("kind")) {
                case "manifest": manifest(text); break;
                case "object-start":
                    require(transferId != null && output == null && current < files.size()
                            && text.getInt("index") == current);
                    output = new FileOutputStream(files.get(current));
                    offset = 0;
                    break;
                case "object-end":
                    require(output != null && text.getInt("index") == current && offset == sizes.get(current));
                    output.close();
                    output = null;
                    require(hashes.get(current).equalsIgnoreCase(RemoteProtocol.sha256FileHex(files.get(current))));
                    current++;
                    break;
                case "complete":
                    require(transferId != null && transferId.equals(text.getString("transferId"))
                            && output == null && current == files.size()
                            && OnlineService.isAutoReceiveEnabled(service));
                    RemoteRelayTask.commitReceived(service, transferId, "work", files, names, mimes, "p2p");
                    sendAck(true);
                    done = true;
                    service.updateRemoteStatus("已通过 P2P 收到作品");
                    DiagnosticLog.write(service, "p2p_received", "objects=" + current + " bytes=" + total);
                    service.sendBroadcast(new android.content.Intent(OnlineService.ACTION_TASK_READY)
                            .setPackage(service.getPackageName()));
                    worker.schedule(this::close, ACK_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
                    break;
                default: require(false);
            }
        } catch (Exception failure) { fail(failure.getMessage()); }
    }

    private void manifest(JSONObject text) throws Exception {
        String incoming = text.getString("transferId");
        require(transferId == null && safeId(incoming) && sender.equals(text.getString("senderDeviceId"))
                && session.deviceId.equals(text.getString("recipientDeviceId"))
                && "work".equals(text.optString("contentKind", "work")));
        JSONArray objects = text.getJSONArray("objects");
        require(objects.length() > 0 && objects.length() <= 1000);
        Set<String> usedNames = new HashSet<>();
        directory = Files.createTempDirectory(service.getCacheDir().toPath(), "remote-p2p-").toFile();
        for (int i = 0; i < objects.length(); i++) {
            JSONObject object = objects.getJSONObject(i);
            long size = object.getLong("bytes");
            String hash = object.getString("sha256");
            String name = object.getString("name");
            require(object.getInt("index") == i && size > 0 && size <= MAX_BYTES - total
                    && hash.matches("[a-fA-F0-9]{64}") && name.length() <= 240 && !name.trim().isEmpty()
                    && !name.equals(".") && !name.equals("..") && !name.contains("/") && !name.contains("\\")
                    && name.indexOf(0) < 0 && usedNames.add(name));
            files.add(new File(directory, name));
            sizes.add(size);
            hashes.add(hash);
            names.add(name);
            mimes.add(object.optString("mime", "application/octet-stream"));
            total += size;
        }
        require(text.getLong("totalBytes") == total && directory.getUsableSpace() >= total);
        transferId = incoming;
    }

    static boolean safeId(String id) { return id.matches("[A-Za-z0-9_-]{8,128}"); }

    private static void require(boolean condition) throws java.io.IOException {
        if (!condition) throw new java.io.IOException("Invalid P2P frame or file");
    }

    private void sendAck(boolean success) throws Exception {
        JSONObject ack = new JSONObject().put("v", 1).put("kind", "ack")
                .put("transferId", transferId).put("ok", success).put("objects", current).put("bytes", total);
        require(channel.send(new DataChannel.Buffer(ByteBuffer.wrap(
                ack.toString().getBytes(StandardCharsets.UTF_8)), false)));
    }

    private void fail(String reason) {
        if (done) return;
        android.util.Log.w("AndroidP2p", String.valueOf(reason));
        DiagnosticLog.write(service, "p2p_receive_failed", String.valueOf(reason));
        try { if (channel != null && transferId != null) sendAck(false); }
        catch (Exception ignored) {}
        close();
    }

    private void close() {
        if (closed) return;
        done = true;
        closed = true;
        if (channel != null) {
            channel.unregisterObserver();
            channel.close();
            channel.dispose();
        }
        if (peer != null) peer.dispose();
        if (output != null) {
            try { output.close(); } catch (java.io.IOException ignored) {}
        }
        if (directory != null) {
            for (File file : files) file.delete();
            directory.delete();
        }
        worker.shutdown();
        try {
            RemoteRelayClient.p2pRequest(session, "/" + id + "/close", new JSONObject());
        } catch (Exception ignored) {
            // The peer is already released. The sender can close the session too.
        }
    }

    private class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { enqueue(() -> fail("SDP creation failed")); }
        @Override public void onSetFailure(String error) { enqueue(() -> fail("SDP application failed")); }
    }
}
