package com.zwm.gallery;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Authenticated responder for the album-transfer-v1 DataChannel.
 *
 * The relay only carries SDP/ICE JSON. Public work bytes are written to a
 * cache file, checked, and handed to the existing WorkLibrary import path.
 */
final class P2PTransferEngine {
    private static final String LABEL = "album-transfer-v1";
    private static final int CHUNK_HEADER = 20;
    private static final int MAX_CHUNK = 48 * 1024;
    private static final long MAX_BYTES = 20L * 1024L * 1024L * 1024L;
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    private static final long ACK_FLUSH_DELAY_MS = 500L;
    private static final Object FACTORY_LOCK = new Object();
    private static PeerConnectionFactory factory;

    interface SignalTransport {
        JSONObject snapshot() throws Exception;
        void send(String type, JSONObject data) throws Exception;
        void close() throws Exception;
    }

    interface Listener {
        boolean onCompleted(Transfer transfer) throws Exception;
        void onFailed(String message);
    }

    static final class ObjectInfo {
        final int index;
        final long bytes;
        final String sha256;
        final String name;
        final String mime;

        ObjectInfo(int index, long bytes, String sha256, String name, String mime) {
            this.index = index;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.name = name;
            this.mime = mime;
        }
    }

    static final class Transfer {
        final String transferId;
        final String senderDeviceId;
        final String recipientDeviceId;
        final List<ObjectInfo> objects;
        final List<File> files;

        Transfer(String transferId, String senderDeviceId, String recipientDeviceId,
                 List<ObjectInfo> objects, List<File> files) {
            this.transferId = transferId;
            this.senderDeviceId = senderDeviceId;
            this.recipientDeviceId = recipientDeviceId;
            this.objects = objects;
            this.files = files;
        }
    }

    private final Context context;
    private final JSONObject p2p;
    private final SignalTransport transport;
    private final Listener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> appliedSignals = new HashSet<>();
    private final Map<Integer, RandomAccessFile> openFiles = new HashMap<>();
    private final Map<Integer, Long> receivedBytes = new HashMap<>();
    private final List<IceCandidate> pendingIce = new ArrayList<>();
    // These fields are observed from WebRTC callbacks, the signal poller and
    // the serial file queue. Volatile prevents stale state from turning a
    // live channel into a false timeout or allowing cleanup after finish.
    private volatile PeerConnection peer;
    private volatile DataChannel channel;
    private File transferDirectory;
    private List<ObjectInfo> objects;
    private String transferId;
    private String senderDeviceId;
    private String recipientDeviceId;
    private volatile boolean finished;
    private volatile boolean remoteDescriptionSet;

    private P2PTransferEngine(Context context, JSONObject p2p,
                              SignalTransport transport, Listener listener) {
        this.context = context.getApplicationContext();
        this.p2p = p2p;
        this.transport = transport;
        this.listener = listener;
    }

    static P2PTransferEngine accept(Context context, JSONObject p2p,
                                    SignalTransport transport, Listener listener) {
        P2PTransferEngine engine = new P2PTransferEngine(context, p2p, transport, listener);
        engine.start();
        // A failure while creating the PeerConnection can finish the engine
        // synchronously. Do not let the caller cache a dead session and block
        // the next inbox poll from retrying it.
        return engine.finished ? null : engine;
    }

    void cancel() {
        try {
            executor.execute(() -> {
                if (finished) return;
                finished = true;
                shutdown(true);
            });
        } catch (RuntimeException ignored) {
            // The engine may already have shut down after a failed transfer.
        }
    }

    private static PeerConnectionFactory factory(Context context) {
        synchronized (FACTORY_LOCK) {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context)
                                .setEnableInternalTracer(false)
                                .createInitializationOptions());
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            }
            return factory;
        }
    }

    private void start() {
        try {
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(
                    java.util.Collections.singletonList(PeerConnection.IceServer.builder(
                            "stun:stun.cloudflare.com:3478").createIceServer()));
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory(context).createPeerConnection(configuration, new Observer());
            if (peer == null) throw new IOException("无法创建 P2P 连接");
            executor.scheduleWithFixedDelay(this::pollSignals, 0, 100, TimeUnit.MILLISECONDS);
            executor.schedule(() -> {
                if (finished || channel == null || channel.state() != DataChannel.State.OPEN) {
                    if (!finished) fail("P2P 建连超时");
                }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            fail(error.getMessage());
        }
    }

    private void pollSignals() {
        if (finished) return;
        try {
            JSONObject session = transport.snapshot();
            JSONArray signals = session.optJSONArray("signals");
            if (signals == null) return;
            for (int i = 0; i < signals.length(); i++) {
                JSONObject signal = signals.optJSONObject(i);
                if (signal == null) continue;
                String type = signal.optString("type", "");
                String from = signal.optString("fromDeviceId", "");
                long sentAt = signal.optLong("sentAt", 0L);
                String key = from + ":" + sentAt + ":" + type;
                if (!appliedSignals.add(key)) continue;
                JSONObject data = signal.optJSONObject("data");
                if (data == null) continue;
                if ("offer".equals(type)) applyOffer(data);
                else if ("ice".equals(type)) applyIce(data);
            }
        } catch (Exception error) {
            // A transient polling failure must not tear down a live channel.
            if (peer != null && peer.iceConnectionState() == PeerConnection.IceConnectionState.FAILED) {
                fail("P2P 信令连接失败");
            }
        }
    }

    private void applyOffer(JSONObject data) {
        if (peer == null) return;
        String sdp = data.optString("sdp", "");
        if (sdp.isEmpty()) { fail("P2P offer 为空"); return; }
        peer.setRemoteDescription(new SdpObserverAdapter() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                for (IceCandidate candidate : pendingIce) peer.addIceCandidate(candidate);
                pendingIce.clear();
                createAnswer();
            }
            @Override public void onSetFailure(String error) { fail("P2P offer 无法应用"); }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private void createAnswer() {
        if (peer == null) return;
        peer.createAnswer(new SdpObserverAdapter() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peer.setLocalDescription(new SdpObserverAdapter() {
                    @Override public void onSetSuccess() {
                        try {
                            SessionDescription local = peer.getLocalDescription();
                            if (local == null) throw new IOException("P2P answer 不可用");
                            transport.send("answer", new JSONObject()
                                    .put("type", "answer")
                                    .put("sdp", local.description));
                        } catch (Exception error) { fail(error.getMessage()); }
                    }
                    @Override public void onSetFailure(String error) { fail("P2P answer 设置失败"); }
                }, description);
            }
            @Override public void onCreateFailure(String error) { fail("P2P answer 创建失败"); }
        }, new MediaConstraints());
    }

    private void applyIce(JSONObject data) {
        if (peer == null) return;
        String candidate = data.optString("candidate", "");
        String mid = data.optString("mid", "");
        if (!candidate.isEmpty() && !mid.isEmpty()) {
            IceCandidate value = new IceCandidate(mid, data.optInt("mLineIndex", 0), candidate);
            if (remoteDescriptionSet) peer.addIceCandidate(value);
            else pendingIce.add(value);
        }
    }

    private void onChannel(DataChannel next) {
        if (!LABEL.equals(next.label())) { next.close(); return; }
        channel = next;
        channel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) { }
            @Override public void onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED && !finished) fail("P2P 数据通道已关闭");
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                // WebRTC owns the callback buffer and the callback thread. Copy
                // first, then do file I/O and SHA-256 work on our serial queue;
                // otherwise a large P2P object can stall ICE/DataChannel events.
                ByteBuffer source = buffer.data.slice();
                byte[] payload = new byte[source.remaining()];
                source.get(payload);
                boolean binary = buffer.binary;
                try {
                    executor.execute(() -> {
                        if (finished) return;
                        try {
                            ByteBuffer copy = ByteBuffer.wrap(payload);
                            if (binary) handleBinary(copy);
                            else handleText(readBuffer(copy));
                        } catch (Exception error) { fail(error.getMessage()); }
                    });
                } catch (RuntimeException ignored) {
                    if (!finished) fail("P2P 数据处理队列已停止");
                }
            }
        });
    }

    private String readBuffer(ByteBuffer buffer) {
        ByteBuffer copy = buffer.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void handleText(String text) throws Exception {
        JSONObject message = new JSONObject(text);
        if (message.optInt("v", 0) != 1) throw new IOException("P2P 数据版本不兼容");
        String kind = message.optString("kind", "");
        if ("manifest".equals(kind)) readManifest(message);
        else if ("complete".equals(kind)) complete(message.optString("transferId", ""));
    }

    private void readManifest(JSONObject message) throws Exception {
        transferId = safeId(message.optString("transferId", ""), "P2P 任务");
        senderDeviceId = safeId(message.optString("senderDeviceId", ""), "P2P 发件设备");
        recipientDeviceId = safeId(message.optString("recipientDeviceId", ""), "P2P 收件设备");
        String expectedSender = p2p.optString("initiatorDeviceId", "");
        String expectedRecipient = p2p.optString("responderDeviceId", "");
        if (!senderDeviceId.equals(expectedSender) || !recipientDeviceId.equals(expectedRecipient)) {
            throw new IOException("P2P 设备身份不匹配");
        }
        JSONArray raw = message.optJSONArray("objects");
        if (raw == null || raw.length() == 0 || raw.length() > 1_000) throw new IOException("P2P 文件清单无效");
        objects = new ArrayList<>();
        long total = 0;
        Set<Integer> indexes = new HashSet<>();
        transferDirectory = new File(context.getCacheDir(), "p2p/" + transferId);
        deleteRecursively(transferDirectory);
        if (!transferDirectory.mkdirs() && !transferDirectory.isDirectory()) throw new IOException("无法创建 P2P 缓存");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.getJSONObject(i);
            int index = item.optInt("index", -1);
            long bytes = item.optLong("bytes", -1L);
            String hash = item.optString("sha256", "").toLowerCase(Locale.US);
            String name = item.optString("name", "");
            if (index < 0 || !indexes.add(index) || bytes <= 0 || !hash.matches("[0-9a-f]{64}") || !safeName(name)) {
                throw new IOException("P2P 文件清单校验失败");
            }
            total = Math.addExact(total, bytes);
            if (total > MAX_BYTES) throw new IOException("P2P 任务超过大小限制");
            objects.add(new ObjectInfo(index, bytes, hash, name, item.optString("mime", "application/octet-stream")));
            receivedBytes.put(index, 0L);
        }
        if (message.optLong("totalBytes", total) != total) throw new IOException("P2P 总大小不一致");
    }

    private void handleBinary(ByteBuffer source) throws Exception {
        ByteBuffer data = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < CHUNK_HEADER || data.get() != 'D' || data.get() != 'S'
                || data.get() != 'H' || data.get() != 'P' || data.get() != 1 || data.get() != 1
                || data.getShort() != 0) throw new IOException("P2P 数据帧无效");
        int index = data.getInt();
        long offset = data.getLong();
        int payload = data.remaining();
        if (payload <= 0 || payload > MAX_CHUNK || objects == null) throw new IOException("P2P 数据帧大小无效");
        ObjectInfo object = findObject(index);
        long expectedOffset = receivedBytes.getOrDefault(index, 0L);
        if (offset != expectedOffset || offset + payload > object.bytes) throw new IOException("P2P 数据帧偏移无效");
        RandomAccessFile file = openFiles.get(index);
        if (file == null) {
            file = new RandomAccessFile(new File(transferDirectory, index + ".part"), "rw");
            openFiles.put(index, file);
        }
        file.seek(offset);
        byte[] bytes = new byte[payload];
        data.get(bytes);
        file.write(bytes);
        receivedBytes.put(index, expectedOffset + payload);
    }

    private void complete(String id) throws Exception {
        if (!id.equals(transferId) || objects == null) throw new IOException("P2P 完成帧无效");
        List<File> files = new ArrayList<>();
        long total = 0;
        for (ObjectInfo object : objects) {
            closeFile(object.index);
            File file = new File(transferDirectory, object.index + ".part");
            if (!file.isFile() || file.length() != object.bytes || !object.sha256.equalsIgnoreCase(sha256(file))) {
                throw new IOException("P2P 文件校验失败");
            }
            files.add(file);
            total += file.length();
        }
        if (listener != null && !listener.onCompleted(new Transfer(transferId, senderDeviceId,
                recipientDeviceId, objects, files))) {
            throw new IOException("P2P 作品写入失败");
        }
        if (channel == null || !channel.send(new DataChannel.Buffer(ByteBuffer.wrap(
                ("{\"v\":1,\"kind\":\"ack\",\"transferId\":\"" + transferId
                        + "\",\"ok\":true,\"objects\":" + objects.size()
                        + ",\"bytes\":" + total + "}").getBytes(StandardCharsets.UTF_8)), false))) {
            throw new IOException("P2P ACK 发送失败");
        }
        finished = true;
        // Give the WebRTC SCTP queue time to put the success ACK on the wire
        // before closing the peer. The sender uses this ACK to avoid a
        // duplicate HTTPS-relay retry.
        executor.schedule(() -> shutdown(false), ACK_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private ObjectInfo findObject(int index) throws IOException {
        if (objects != null) for (ObjectInfo object : objects) if (object.index == index) return object;
        throw new IOException("P2P 对象序号未知");
    }

    private void closeFile(int index) {
        RandomAccessFile file = openFiles.remove(index);
        if (file != null) try { file.close(); } catch (IOException ignored) { }
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        if (channel != null) {
            try { channel.send(new DataChannel.Buffer(ByteBuffer.wrap(
                    "{\"v\":1,\"kind\":\"ack\",\"ok\":false}".getBytes(StandardCharsets.UTF_8)), false)); } catch (Exception ignored) { }
        }
        try { transport.close(); } catch (Exception ignored) { }
        if (listener != null) listener.onFailed(message == null || message.isEmpty() ? "P2P 传输失败" : message);
        shutdown(true);
    }

    private void shutdown(boolean removeFiles) {
        executor.shutdownNow();
        for (Integer index : new ArrayList<>(openFiles.keySet())) closeFile(index);
        if (channel != null) channel.close();
        if (peer != null) peer.close();
        if (removeFiles) deleteRecursively(transferDirectory);
    }

    private static String safeId(String value, String label) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) throw new IOException(label + "无效");
        return value;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 240 && !".".equals(value)
                && !"..".equals(value) && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        // Cache data is owned by this engine and is safe to remove on failure.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private final class Observer implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (state == PeerConnection.IceConnectionState.FAILED) fail("P2P ICE 连接失败");
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
        @Override public void onIceCandidate(IceCandidate candidate) {
            try {
                transport.send("ice", new JSONObject().put("candidate", candidate.sdp)
                        .put("mid", candidate.sdpMid).put("mLineIndex", candidate.sdpMLineIndex));
            } catch (Exception error) { fail("P2P ICE 信令发送失败"); }
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel dataChannel) { onChannel(dataChannel); }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) { }
    }

    private abstract static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
