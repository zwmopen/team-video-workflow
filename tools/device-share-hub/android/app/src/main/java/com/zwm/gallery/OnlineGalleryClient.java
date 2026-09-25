package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Communicates with the PC-side Online Gallery Service (port 45835).
 */
public final class OnlineGalleryClient {
    public static final int DEFAULT_PC_PORT = 45835;
    /** 局域网信标端口：电脑端在此端口周期广播自身地址，手机被动接收 */
    public static final int BEACON_PORT = 45832;
    private static final String BEACON_MAGIC = "ZWMDS2_GALLERY_DISCOVER";
    /** 信标地址保鲜期：超过该时长视为陈旧，需重新确认 */
    private static final long BEACON_FRESH_MS = 5 * 60 * 1000L;
    private static final int TIMEOUT_MS = 15000;
    /** DSH-098 统一日志 TAG，方便 logcat 过滤 */
    private static final String TAG = "OnlineGalleryClient";
    /**
     * 列表/分类请求专用超时（2026-09-20）。
     *
     * 原来和通用请求共用 {@link #TIMEOUT_MS}=15000，含义是：电脑端 IP 一旦过期/电脑关机，
     * 用户要盯着「正在连接电脑在线相册…」最多 **15 秒**（失败后还会再去试回环地址，
     * 合计最长可达 30 秒）。服务端实测：列表请求本地 42 ms、冷扫 1.0 s —— 用不上 15 秒。
     * 这里收紧为「连接 4 s / 读 12 s」：
     *   - 4 s 足够在局域网里建立连接，失败也能快速失败并转「本地快照」展示；
     *   - 12 s 是给「服务端刚被 restart、需要冷扫 384 套」留的余量（实测 1.0 s，10 倍冗余）。
     */
    private static final int LIST_CONNECT_TIMEOUT_MS = 4000;
    private static final int LIST_READ_TIMEOUT_MS = 12000;
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    /**
     * 缩略图专用线程池（2026-09-20 新增）：
     * 旧实现把缩略图与列表/分类/操作请求塞进同一个 4 线程池，几百张缩略图一进来，
     * 列表请求就排在它们后面，界面长时间卡在「正在读取…」。
     */
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "online-thumb");
        t.setDaemon(true);
        return t;
    });
    /** 在途缩略图请求去重：(workId::fileName) -> 等待回调列表，同一张图只发一次网络请求 */
    private final java.util.concurrent.ConcurrentHashMap<String, List<Callback<Bitmap>>> inflightThumbs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Object thumbLock = new Object();
    /** 缩略图磁盘缓存目录名：二次进入在线列表几乎零流量 */
    private static final String THUMB_CACHE_DIR = "online_thumbs";
    /** 缩略图体积上限：超过这个大小说明服务端没生成缩略图（降级发了原图），不写进缩略图缓存 */
    private static final int THUMB_MAX_BYTES = 512 * 1024;
    private static final int THUMB_CONNECT_TIMEOUT_MS = 4000;
    private static final int THUMB_READ_TIMEOUT_MS = 8000;
    /** 缩略图解码目标边长（px）：卡片显示约 84×112dp，3x 屏约 336px，取 384 留余量 */
    private static final int THUMB_TARGET_PX = 384;
    /** 缩略图失败重试次数与重试间隔：冷缓存大图偶发读超时用，重试基本能命中服务端已生成的缓存 */
    private static final int THUMB_MAX_ATTEMPTS = 2;
    private static final long THUMB_RETRY_DELAY_MS = 900L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String cachedBaseUrl = null;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    public static final class CategoriesResult {
        public final List<CategoryItem> categories;
        public final List<CategoryItem> stages;
        public final int total;
        /** 服务端原始响应体，供 {@link OnlineListCache} 原样落盘（避免二次序列化失真） */
        public final String rawJson;

        public CategoriesResult(List<CategoryItem> categories, List<CategoryItem> stages, int total) {
            this(categories, stages, total, null);
        }

        public CategoriesResult(List<CategoryItem> categories, List<CategoryItem> stages, int total, String rawJson) {
            this.categories = categories;
            this.stages = stages;
            this.total = total;
            this.rawJson = rawJson;
        }
    }

    public static final class CategoryItem {
        public final String name;
        public final int count;

        public CategoryItem(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public static final class UseResult {
        public final boolean ok;
        public final String workId;
        public final int useCount;
        public final int remainingUses;
        public final boolean moved;
        public final String message;

        public UseResult(boolean ok, String workId, int useCount, int remainingUses, boolean moved, String message) {
            this.ok = ok;
            this.workId = workId;
            this.useCount = useCount;
            this.remainingUses = remainingUses;
            this.moved = moved;
            this.message = message;
        }
    }

    public OnlineGalleryClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 判断是否为回环地址（仅 ADB reverse 隧道在场时才可达，纯 Wi-Fi 设备必然失败） */
    public static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        return url.contains("127.0.0.1") || url.contains("localhost");
    }

    /**
     * 地址选路优先级（修复 vivo/华为「同一 Wi-Fi 只有红米能读」）：
     * 1) 用户手填或已自动发现的局域网地址（非回环才认）
     * 2) 局域网信标地址（电脑主动广播，5 分钟内新鲜）
     * 3) 最近一次成功连通的地址
     * 4) 回环 127.0.0.1（最后兜底，只有 ADB reverse 隧道才有效）
     */
    public String resolveBaseUrl() {
        if (cachedBaseUrl != null && !cachedBaseUrl.isEmpty()) {
            return cachedBaseUrl;
        }

        SharedPreferences prefs = context.getSharedPreferences("device_share", Context.MODE_PRIVATE);

        // 1) 用户在设置里手填的地址：优先级最高，明确表达意图
        String manual = prefs.getString("manualPcServerUrl", "").trim();
        if (!manual.isEmpty() && !isLoopbackUrl(manual)) {
            cachedBaseUrl = manual;
            return manual;
        }

        // 2) 局域网信标（电脑主动广播，5 分钟内新鲜）—— 电脑换 IP 也能自动跟上
        String beacon = prefs.getString("beaconPcServerUrl", "").trim();
        long beaconAt = prefs.getLong("beaconPcServerAtMs", 0L);
        if (!beacon.isEmpty() && System.currentTimeMillis() - beaconAt <= BEACON_FRESH_MS) {
            cachedBaseUrl = beacon;
            return beacon;
        }

        // 3) 自动发现/上次成功过的局域网地址
        String custom = prefs.getString("customPcServerUrl", "").trim();
        if (!custom.isEmpty() && !isLoopbackUrl(custom)) {
            cachedBaseUrl = custom;
            return custom;
        }

        String lastGood = prefs.getString("lastGoodPcServerUrl", "").trim();
        if (!lastGood.isEmpty() && !isLoopbackUrl(lastGood)) {
            cachedBaseUrl = lastGood;
            return lastGood;
        }

        // 4) 最后兜底：回环（仅 ADB reverse / USB 隧道有效）
        String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
        cachedBaseUrl = loopback;
        return loopback;
    }

    /** 自动发现或信标命中的地址（非用户手填，允许被更新的信标覆盖） */
    public void setCustomBaseUrl(String url) {
        this.cachedBaseUrl = url;
        context.getSharedPreferences("device_share", Context.MODE_PRIVATE).edit()
                .putString("customPcServerUrl", url == null ? "" : url).apply();
    }

    /** 用户在设置里手工指定的地址：视为明确意图，不被信标自动覆盖 */
    public void setManualBaseUrl(String url) {
        String cleaned = url == null ? "" : url.trim();
        this.cachedBaseUrl = cleaned.isEmpty() ? null : cleaned;
        context.getSharedPreferences("device_share", Context.MODE_PRIVATE).edit()
                .putString("manualPcServerUrl", cleaned)
                .putString("customPcServerUrl", cleaned)
                .apply();
    }

    /** 任意一次请求成功后调用：记录「最近可用地址」，供下次直接复用 */
    public void markBaseUrlGood(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return;
        context.getSharedPreferences("device_share", Context.MODE_PRIVATE).edit()
                .putString("lastGoodPcServerUrl", baseUrl).apply();
        // 顺手把本机登记到 PC 端白名单：首次连接 → 直接放行 + 记 last_seen；
        // 后续每次成功 ping 都续期一次。Server 端幂等，无任何阻拦（满足"下载即用"铁律）。
        ensureDeviceRegistered(baseUrl);
    }

    /**
     * 向 PC 端登记本机（device_id + device_name）→ 直接进入白名单。
     * 用 Settings.Secure.ANDROID_ID 作为稳定 device_id（卸载/重装后会变，但同一次安装永远一致）。
     * Server 端行为：新设备直接白名单 + 返回 ok；已知设备只更新 last_seen，无任何 UI/弹框。
     */
    private void ensureDeviceRegistered(String baseUrl) {
        executor.execute(() -> {
            try {
                String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (deviceId == null || deviceId.isEmpty()) {
                    deviceId = "android-" + UUID.randomUUID().toString();
                }
                String deviceName = android.os.Build.MODEL != null && !android.os.Build.MODEL.isEmpty()
                        ? android.os.Build.MODEL : "Android 设备";
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("device_name", deviceName);
                URL url = new URL(baseUrl + "/api/online/device-register");
                rawHttpPost(url, body.toString());
            } catch (Exception ignored) {
                // 注册失败不影响主流程：白名单是辅助能力，断网/PС未启动时跳过即可。
            }
        });
    }

    /** 当前地址已失效：清空内存缓存，强制下一次重新选路 */
    public void invalidateBaseUrl() {
        this.cachedBaseUrl = null;
    }

    /**
     * 连接自检：依次尝试「当前地址 → 信标地址 → 最近可用 → 回环」。
     * 全部失败时清理掉已经失效的手填局域网地址，避免陈旧 IP 永久卡死。
     */
    public void checkConnection(Callback<Boolean> callback) {
        executor.execute(() -> {
            SharedPreferences prefs = context.getSharedPreferences("device_share", Context.MODE_PRIVATE);
            java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
            candidates.add(resolveBaseUrl());

            String beacon = prefs.getString("beaconPcServerUrl", "").trim();
            if (!beacon.isEmpty()) candidates.add(beacon);
            String lastGood = prefs.getString("lastGoodPcServerUrl", "").trim();
            if (!lastGood.isEmpty()) candidates.add(lastGood);
            candidates.add("http://127.0.0.1:" + DEFAULT_PC_PORT);

            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                if (ping(candidate)) {
                    cachedBaseUrl = candidate;
                    markBaseUrlGood(candidate);
                    mainHandler.post(() -> callback.onSuccess(true));
                    return;
                }
                if (candidate.equals(cachedBaseUrl)) cachedBaseUrl = null;
            }

            // 全部不通：清掉失效的「自动发现」地址，让信标/扫描重新接管；
            // 用户手填的地址保留（尊重明确意图），但下一次请求仍会走信标优先的兜底链路。
            String custom = prefs.getString("customPcServerUrl", "").trim();
            String manual = prefs.getString("manualPcServerUrl", "").trim();
            if (!custom.isEmpty() && !isLoopbackUrl(custom) && !custom.equals(manual)) {
                prefs.edit().putString("customPcServerUrl", "").apply();
            }
            cachedBaseUrl = null;
            mainHandler.post(() -> callback.onSuccess(false));
        });
    }

    // ==================== 局域网信标监听（电脑主动广播，手机被动接收） ====================

    private volatile boolean beaconRunning = false;
    private volatile Thread beaconThread = null;
    private volatile DatagramSocket beaconSocket = null;
    private volatile android.net.wifi.WifiManager.MulticastLock beaconMulticastLock = null;

    /**
     * Wi-Fi 驱动默认会过滤广播/组播报文（省电策略），部分机型（如 vivo）因此收不到电脑信标。
     * 持有 MulticastLock 可放行这些报文，是在线相册稳定可读的关键一步。
     */
    private void acquireBeaconMulticastLock() {
        try {
            android.net.wifi.WifiManager manager = (android.net.wifi.WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return;
            android.net.wifi.WifiManager.MulticastLock lock = manager.createMulticastLock("zwm-online-gallery-beacon");
            lock.setReferenceCounted(false);
            lock.acquire();
            beaconMulticastLock = lock;
        } catch (Exception ignored) {
            // 部分平台不支持组播控制，缺少该锁仍可尝试接收
        }
    }

    private void releaseBeaconMulticastLock() {
        android.net.wifi.WifiManager.MulticastLock lock = beaconMulticastLock;
        beaconMulticastLock = null;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动信标监听（UDP 45832）。电脑端每 2 秒广播一次自身地址，
     * 手机被动收包即可秒级拿到地址，不必全 /24 扫描；电脑换 IP 也能自动跟随。
     * 同时每 30 秒主动补发一次探测，覆盖路由器丢弃广播的情况。
     */
    public void startBeaconListener(final Runnable onAddressChanged) {
        if (beaconRunning) return;
        beaconRunning = true;
        beaconThread = new Thread(() -> {
            acquireBeaconMulticastLock();
            DatagramSocket sock;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.bind(new java.net.InetSocketAddress("0.0.0.0", BEACON_PORT));
                sock.setSoTimeout(15000);
                beaconSocket = sock;
            } catch (Exception e) {
                beaconRunning = false;
                releaseBeaconMulticastLock();
                return;
            }

            try {
                sendBeaconProbe(sock);
                byte[] buf = new byte[2048];
                int idleRounds = 0;
                while (beaconRunning) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        idleRounds = 0;
                        String text = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                        String url = parseBeaconUrl(text);
                        if (url != null && !url.isEmpty()) {
                            applyBeaconUrl(url, onAddressChanged);
                        }
                    } catch (SocketTimeoutException te) {
                        idleRounds++;
                        if (idleRounds % 2 == 0) sendBeaconProbe(sock);
                    } catch (Exception ignored) {
                        if (!beaconRunning) break;
                    }
                }
            } finally {
                try { sock.close(); } catch (Exception ignored) {}
                if (beaconSocket == sock) beaconSocket = null;
                releaseBeaconMulticastLock();
            }
        }, "LanBeaconListener");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    public void stopBeaconListener() {
        beaconRunning = false;
        DatagramSocket sock = beaconSocket;
        if (sock != null) {
            try { sock.close(); } catch (Exception ignored) {}
        }
        beaconThread = null;
        beaconSocket = null;
    }

    /** 收到信标：持久化，并在地址变化时通知界面刷新 */
    private void applyBeaconUrl(String url, Runnable onAddressChanged) {
        SharedPreferences prefs = context.getSharedPreferences("device_share", Context.MODE_PRIVATE);
        String prev = prefs.getString("beaconPcServerUrl", "").trim();
        boolean changed = !url.equals(prev);

        prefs.edit()
                .putString("beaconPcServerUrl", url)
                .putLong("beaconPcServerAtMs", System.currentTimeMillis())
                .apply();

        // 仅当用户「手工指定」了地址时才不让信标接管；
        // 自动发现的地址必须允许被信标更新，否则电脑换 IP 后手机会一直连旧地址。
        String manual = prefs.getString("manualPcServerUrl", "").trim();
        boolean userPinned = !manual.isEmpty() && !isLoopbackUrl(manual);

        if (!userPinned) {
            String current = cachedBaseUrl;
            if (changed || current == null || !url.equals(current)) {
                cachedBaseUrl = url;
                markBaseUrlGood(url);
                if (changed && onAddressChanged != null) {
                    mainHandler.post(onAddressChanged);
                }
            }
        }
    }

    private String parseBeaconUrl(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (!t.startsWith("{")) return null;
        try {
            JSONObject obj = new JSONObject(t);
            String url = obj.optString("url", "").trim();
            if (url.isEmpty()) {
                String ip = obj.optString("ip", "").trim();
                int port = obj.optInt("port", DEFAULT_PC_PORT);
                if (!ip.isEmpty()) url = "http://" + ip + ":" + port;
            }
            return url.isEmpty() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    /** 主动广播探测报文，电脑收到会立刻单播回信标 */
    private void sendBeaconProbe(DatagramSocket sock) {
        try {
            byte[] data = BEACON_MAGIC.getBytes(StandardCharsets.UTF_8);
            for (String target : localBroadcastTargets()) {
                try {
                    sock.send(new DatagramPacket(data, data.length, InetAddress.getByName(target), BEACON_PORT));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private List<String> localBroadcastTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("255.255.255.255");
        try {
            java.util.Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b == null) continue;
                    String s = b.getHostAddress();
                    if (s != null && !s.isEmpty() && !targets.contains(s)) targets.add(s);
                }
            }
        } catch (Exception ignored) {}
        return targets;
    }

    private boolean ping(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/api/online/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 快速探测：仅校验目标是否为电脑在线相册服务（返回体含 server 字段） */
    private boolean probeGalleryServer(String host, int port) {
        try {
            URL url = new URL("http://" + host + ":" + port + "/api/online/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(700);
            conn.setReadTimeout(900);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return false;
            }
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            conn.disconnect();
            String body = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return body.contains("DeviceShareHub-OnlineGallery");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 自动发现局域网内的电脑在线相册服务（端口 45835）。
     * 用于没有任何 ADB reverse 隧道、也从未手工配置过电脑 IP 的设备（例如纯 Wi-Fi 的 vivo）。
     * 命中后立即写入 customPcServerUrl，后续无需再次扫描。
     */
    public void discoverPcServer(Callback<String> callback) {
        executor.execute(() -> {
            java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();

            // 0) 最快通路：局域网信标（电脑 2 秒一播，通常几十毫秒即可拿到地址）
            String beaconUrl = probeBeaconOnce(3500);
            if (beaconUrl != null) {
                setCustomBaseUrl(beaconUrl);
                markBaseUrlGood(beaconUrl);
                mainHandler.post(() -> callback.onSuccess(beaconUrl));
                return;
            }

            // 1) 优先尝试已发现的对端设备（含电脑端信标）
            try {
                List<com.zwm.gallery.PeerDevice> peers = OnlineService.peers();
                if (peers != null) {
                    for (com.zwm.gallery.PeerDevice p : peers) {
                        if (p != null && p.ip != null && !p.ip.trim().isEmpty()) {
                            candidates.add(p.ip.trim());
                        }
                    }
                }
            } catch (Exception ignored) {}
            final int peerCount = candidates.size();

            // 2) 兜底：本机所在网段的 /24 全量并发探测
            try {
                java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
                while (nis.hasMoreElements()) {
                    java.net.NetworkInterface ni = nis.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        java.net.InetAddress addr = ia.getAddress();
                        if (addr == null || addr.isLoopbackAddress() || !(addr instanceof java.net.Inet4Address)) continue;
                        byte[] b = addr.getAddress();
                        String prefix = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
                        for (int i = 1; i <= 254; i++) candidates.add(prefix + i);
                    }
                }
            } catch (Exception ignored) {}

            if (candidates.isEmpty()) {
                mainHandler.post(() -> callback.onError(new IllegalStateException("未找到可探测的局域网地址")));
                return;
            }

            // 已发现对端保持原顺序优先探测，本网段地址随机打散以尽快命中
            java.util.List<String> list = new ArrayList<>();
            java.util.List<String> rest = new ArrayList<>();
            int idx = 0;
            for (String c : candidates) {
                if (idx++ < peerCount) list.add(c);
                else rest.add(c);
            }
            java.util.Collections.shuffle(rest);
            list.addAll(rest);
            java.util.concurrent.atomic.AtomicReference<String> found = new java.util.concurrent.atomic.AtomicReference<>(null);
            int threads = Math.min(48, list.size());
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
            int chunk = (list.size() + threads - 1) / threads;
            for (int t = 0; t < threads; t++) {
                final int from = t * chunk;
                final int to = Math.min(list.size(), from + chunk);
                new Thread(() -> {
                    try {
                        for (int i = from; i < to; i++) {
                            if (found.get() != null) break;
                            String host = list.get(i);
                            if (probeGalleryServer(host, DEFAULT_PC_PORT)) {
                                found.compareAndSet(null, host);
                                break;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
            try {
                latch.await(12, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            String host = found.get();
            if (host == null) {
                mainHandler.post(() -> callback.onError(new IllegalStateException("局域网未发现电脑在线相册服务")));
                return;
            }
            String base = "http://" + host + ":" + DEFAULT_PC_PORT;
            setCustomBaseUrl(base);
            markBaseUrlGood(base);
            mainHandler.post(() -> callback.onSuccess(base));
        });
    }

    /**
     * 一次性信标探测：广播探测报文并等待电脑回信。
     * 命中即返回 http://ip:45835，超时返回 null（随后再走扫描兜底）。
     */
    private String probeBeaconOnce(long waitMs) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(700);
            sendBeaconProbe(sock);
            long deadline = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String text = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    String url = parseBeaconUrl(text);
                    if (url != null && !url.isEmpty()) return url;
                } catch (SocketTimeoutException te) {
                    sendBeaconProbe(sock);
                } catch (Exception ignored) {
                    return null;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * 解析 `/api/online/categories` 响应。
     *
     * 抽成 public static 的唯一目的：让 {@link OnlineListCache} 的本地快照
     * 能走**完全同一条**解析路径。缓存另写一套解析 = 迟早与网络路径行为不一致。
     */
    public static CategoriesResult parseCategories(String resp) throws Exception {
        JSONObject json = new JSONObject(resp);
        List<CategoryItem> categories = new ArrayList<>();
        JSONArray catArr = json.optJSONArray("categories");
        if (catArr != null) {
            for (int i = 0; i < catArr.length(); i++) {
                JSONObject obj = catArr.optJSONObject(i);
                if (obj != null) {
                    categories.add(new CategoryItem(obj.optString("name", ""), obj.optInt("count", 0)));
                }
            }
        }
        List<CategoryItem> stages = new ArrayList<>();
        JSONArray stageArr = json.optJSONArray("stages");
        if (stageArr != null) {
            for (int i = 0; i < stageArr.length(); i++) {
                JSONObject obj = stageArr.optJSONObject(i);
                if (obj != null) {
                    stages.add(new CategoryItem(obj.optString("name", ""), obj.optInt("count", 0)));
                }
            }
        }
        return new CategoriesResult(categories, stages, json.optInt("total", 0), resp);
    }

    /** 解析 `/api/online/works` 响应（与本地快照共用同一条路径）。 */
    public static List<OnlineWorkEntry> parseWorks(String resp) throws Exception {
        JSONObject json = new JSONObject(resp);
        List<OnlineWorkEntry> list = new ArrayList<>();
        JSONArray arr = json.optJSONArray("works");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wObj = arr.optJSONObject(i);
                if (wObj != null) {
                    OnlineWorkEntry entry = OnlineWorkEntry.fromJson(wObj);
                    if (entry != null) list.add(entry);
                }
            }
        }
        return list;
    }

    /**
     * DSH-111：轻量探测「电脑端的作品有没有变」——只请求 `/api/online/status`，
     * 用 `totalWorks` + `watchdog.lastChangeAt` 拼成一个指纹串。
     *
     * 为什么需要它：在线相册如果每隔几十秒直接调 refreshOnlineWorks()，
     * 列表会被整个清空重建，用户正在滚动或搜索时会被拽回顶部。
     * 有了指纹就能先问一句「变了吗」，没变就完全不动 UI —— 自动刷新对用户零打扰。
     *
     * 用 lastChangeAt 而不是只看 totalWorks：加了 1 套又删了 1 套时总数不变，
     * 但 lastChangeAt 会变，能抓到这种「内容变了」的情况。
     *
     * @return 形如 "472|1790293067.11" 的指纹；请求失败走 onError（调用方应保持原状）
     */
    public void fetchServerFingerprint(Callback<String> callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(resolveBaseUrl() + "/api/online/status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    mainHandler.post(() -> callback.onError(new IllegalStateException("HTTP " + code)));
                    return;
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                conn.disconnect();
                String body = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(body);
                int total = json.optInt("totalWorks", -1);
                // watchdog 缺字段时留空，调用方见空串会保守地照常刷新（宁可多刷，不可漏刷）
                JSONObject wd = json.optJSONObject("watchdog");
                String changeAt = (wd != null) ? String.valueOf(wd.optDouble("lastChangeAt", 0d)) : "";
                final String fingerprint = total + "|" + changeAt;
                mainHandler.post(() -> callback.onSuccess(fingerprint));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void fetchCategories(Callback<CategoriesResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/categories");
                String resp = rawHttpGet(url, LIST_CONNECT_TIMEOUT_MS, LIST_READ_TIMEOUT_MS);
                CategoriesResult result = parseCategories(resp);
                // 成功即落盘快照：下次进页面/离线时可直接渲染
                OnlineListCache.saveCategories(context, resp);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void fetchWorks(String category, String query, Callback<List<OnlineWorkEntry>> callback) {
        fetchWorks(category, query, null, callback);
    }

    // DSH-109：sortKey 可选，与服务端 SORT_KEYS 对齐；传 null 则不发 sort 参数（服务端默认 default）
    public void fetchWorks(String category, String query, String sortKey, Callback<List<OnlineWorkEntry>> callback) {
        final boolean fullList = (category == null || category.isEmpty())
                && (query == null || query.isEmpty());
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                StringBuilder sb = new StringBuilder(baseUrl).append("/api/online/works?");
                if (category != null && !category.isEmpty()) {
                    sb.append("category=").append(URLEncoder.encode(category, "UTF-8")).append("&");
                }
                if (query != null && !query.isEmpty()) {
                    sb.append("query=").append(URLEncoder.encode(query, "UTF-8")).append("&");
                }
                if (sortKey != null && !sortKey.isEmpty()) {
                    sb.append("sort=").append(URLEncoder.encode(sortKey, "UTF-8")).append("&");
                }
                URL url = new URL(sb.toString());
                String resp = rawHttpGet(url, LIST_CONNECT_TIMEOUT_MS, LIST_READ_TIMEOUT_MS);
                List<OnlineWorkEntry> list = parseWorks(resp);
                // 只有「全量列表」才值得做快照：带分类/关键词的响应是子集，
                // 存下去会让下次秒开时看到一个不完整的列表。
                if (fullList) OnlineListCache.saveWorks(context, resp);
                mainHandler.post(() -> callback.onSuccess(list));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * 拉取在线缩略图（三级取图：磁盘缓存 → 网络；同一张图的在途请求自动合并）。
     *
     * 2026-09-20 改造要点：
     * 1. 走独立的 thumbExecutor（8 线程），不再和列表请求抢同一个 4 线程池；
     * 2. 新增磁盘缓存，二次进入在线列表不再重复下载；
     * 3. 同一 (workId, fileName) 并发只发一次请求，结果广播给所有等待者；
     * 4. 解码按目标尺寸降采样，避免服务端降级发原图时把内存打爆。
     */
    public void loadThumbnail(final String workId, final String fileName, final Callback<Bitmap> callback) {
        final String key = workId + "::" + fileName;
        synchronized (thumbLock) {
            List<Callback<Bitmap>> waiters = inflightThumbs.get(key);
            if (waiters != null) {
                waiters.add(callback);
                return;
            }
            List<Callback<Bitmap>> list = new ArrayList<>();
            list.add(callback);
            inflightThumbs.put(key, list);
        }
        thumbExecutor.execute(() -> doLoadThumbnail(key, workId, fileName));
    }

    private void doLoadThumbnail(String key, String workId, String fileName) {
        Bitmap bmp = null;
        Exception err = null;
        File diskFile = new File(thumbCacheDir(), getDiskCacheKey(workId, fileName));
        // 冷缓存时服务端要现场跑 PIL 生成缩略图，个别几 MB 的大图在并发下可能超过读超时。
        // 这种超时的本质是「客户端先放弃、服务端其实还在生成」——稍等重试一次基本就命中缓存了，
        // 否则那几张图会永久停在灰色占位块。（真机实测：231 次取图里曾出现 7 次此类超时）
        for (int attempt = 1; attempt <= THUMB_MAX_ATTEMPTS && bmp == null; attempt++) {
            try {
                // 1) 磁盘缓存
                if (diskFile.exists() && diskFile.length() > 512) {
                    bmp = decodeThumbFile(diskFile);
                }
                // 2) 网络（DSH-098：流式写盘，不再用 byte[] 中转）
                if (bmp == null) {
                    boolean ok = downloadThumb(workId, fileName, diskFile);
                    if (ok && diskFile.exists() && diskFile.length() > 512) {
                        bmp = decodeThumbFile(diskFile);
                    }
                    if (bmp == null) throw new Exception("Failed to decode thumbnail from " + diskFile);
                }
            } catch (Exception e) {
                err = e;
                String errMsg = "loadThumbnail attempt=" + attempt + " | " + workId + "/" + fileName
                        + " | " + e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt < THUMB_MAX_ATTEMPTS) {
                    Log.w(TAG, errMsg + "（稍后重试）", e);
                    DiagnosticLog.write(context, "thumb_retry", workId + "/" + fileName
                            + " | attempt=" + attempt + " | " + e.getMessage());
                    try {
                        Thread.sleep(THUMB_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    Log.w(TAG, "loadThumbnail failed: " + errMsg, e);
                    DiagnosticLog.write(context, "thumb_final_failed", workId + "/" + fileName
                            + " | " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }

        final Bitmap result = bmp;
        final Exception error = err;
        List<Callback<Bitmap>> waiters;
        synchronized (thumbLock) {
            waiters = inflightThumbs.remove(key);
        }
        if (waiters == null) return;
        for (final Callback<Bitmap> cb : waiters) {
            mainHandler.post(() -> {
                if (result != null) cb.onSuccess(result);
                else cb.onError(error != null ? error : new Exception("thumbnail unavailable"));
            });
        }
    }

    /**
     * DSH-098：流式下载缩略图到磁盘缓存。
     * - HTTP → 8KB 缓冲 → tempFile 边读边写（不再用 ByteArrayOutputStream 累积到内存）
     * - 大小限制：> THUMB_MAX_BYTES 直接拒绝（避免服务端降级发原图把内存打爆）
     * - 原子落盘：tempFile 校验通过后 renameTo
     * - 返回 boolean：true = 磁盘缓存已就绪，false = 失败（已 Log.w + DiagnosticLog 双写）
     * - 错误路径必须 Log.w（堆栈）+ DiagnosticLog.write（持久化）双写
     */
    private boolean downloadThumb(String workId, String fileName, File diskFile) throws Exception {
        HttpURLConnection conn = null;
        File tempFile = new File(diskFile.getParentFile(), diskFile.getName() + ".tmp");
        try {
            String baseUrl = resolveBaseUrl();
            String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                    + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=1";
            conn = (HttpURLConnection) new URL(uStr).openConnection();
            conn.setRequestProperty("Connection", "close");
            conn.setConnectTimeout(THUMB_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(THUMB_READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                String detail = "HTTP " + code;
                Log.w(TAG, "downloadThumb " + detail + " for " + workId + "/" + fileName);
                DiagnosticLog.write(context, "thumb_http_error", workId + "/" + fileName + " | " + detail);
                throw new Exception(detail);
            }
            String thumbState = conn.getHeaderField("X-Thumb");
            if ("fallback".equals(thumbState)) {
                // 服务端缩略图链路不可用（例如缺 Pillow），正在发原图。只提示不阻断，方便定位。
                String detail = "服务端缩略图未启用，已降级返回原图（X-Thumb=fallback）";
                Log.w(TAG, detail + " | " + workId + "/" + fileName);
                DiagnosticLog.write(context, "thumb_fallback", workId + "/" + fileName + " | " + detail);
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            File dir = tempFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int len;
            long totalBytes = 0;
            try {
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    totalBytes += len;
                    if (totalBytes > THUMB_MAX_BYTES) {
                        String detail = "thumb too large: " + totalBytes + " > " + THUMB_MAX_BYTES;
                        Log.w(TAG, detail + " | " + workId + "/" + fileName);
                        DiagnosticLog.write(context, "thumb_oversize", workId + "/" + fileName + " | " + detail);
                        throw new Exception(detail);
                    }
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (totalBytes > 0 && totalBytes <= THUMB_MAX_BYTES) {
                if (diskFile.exists()) diskFile.delete();
                if (!tempFile.renameTo(diskFile)) {
                    String detail = "renameTo failed: " + tempFile + " -> " + diskFile;
                    Log.w(TAG, detail);
                    DiagnosticLog.write(context, "thumb_rename_failed", workId + "/" + fileName + " | " + detail);
                    throw new Exception(detail);
                }
                return true;
            } else {
                if (tempFile.exists()) tempFile.delete();
                String detail = "empty stream: totalBytes=" + totalBytes;
                Log.w(TAG, "downloadThumb " + detail + " | " + workId + "/" + fileName);
                DiagnosticLog.write(context, "thumb_empty", workId + "/" + fileName + " | " + detail);
                return false;
            }
        } catch (Exception e) {
            if (tempFile.exists()) tempFile.delete();
            Log.w(TAG, "downloadThumb failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " | " + workId + "/" + fileName, e);
            DiagnosticLog.write(context, "thumb_failed", workId + "/" + fileName + " | "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private File thumbCacheDir() {
        File dir = new File(context.getCacheDir(), THUMB_CACHE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private Bitmap decodeThumbFile(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMB_TARGET_PX);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap decodeThumbBytes(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMB_TARGET_PX);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按目标边长算 2 的幂降采样，保证解码出来的位图贴近显示尺寸（卡片缩略图约 84×112dp）。 */
    private static int sampleSize(int width, int height, int targetPx) {
        int sample = 1;
        if (width <= 0 || height <= 0) return sample;
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        return sample;
    }

    public static String getDiskCacheKey(String workId, String fileName) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest((workId + "::" + fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".jpg";
        } catch (Exception e) {
            return Math.abs((workId + "_" + fileName).hashCode()) + ".jpg";
        }
    }

    public boolean hasFullImageCached(String workId, String fileName) {
        File cacheDir = new File(context.getCacheDir(), "online_full_images");
        File diskFile = new File(cacheDir, getDiskCacheKey(workId, fileName));
        return diskFile.exists() && diskFile.length() > 1024;
    }

    public void loadFullImage(String workId, String fileName, Callback<Bitmap> callback) {
        executor.execute(() -> {
            // 1. 优先从本地磁盘持久化缓存读取（秒开原画，绝不重复拉取）
            File cacheDir = new File(context.getCacheDir(), "online_full_images");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File diskFile = new File(cacheDir, getDiskCacheKey(workId, fileName));

            if (diskFile.exists() && diskFile.length() > 1024) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        mainHandler.post(() -> callback.onSuccess(bmp));
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 2. 本地无缓存或损坏，从电脑在线服务拉取并原子安全落盘
            HttpURLConnection conn = null;
            File tempFile = new File(cacheDir, getDiskCacheKey(workId, fileName) + ".tmp");
            try {
                String baseUrl = resolveBaseUrl();
                String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                        + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=0";
                URL url = new URL(uStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Connection", "close");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
                fos.close();
                in.close();

                if (tempFile.length() > 1024) {
                    if (diskFile.exists()) diskFile.delete();
                    tempFile.renameTo(diskFile);
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                if (bmp == null) throw new Exception("Failed to decode saved bitmap");
                mainHandler.post(() -> callback.onSuccess(bmp));
            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                Log.w(TAG, "loadFullImage failed for " + workId + " / " + fileName + ": " + e.getMessage(), e);
                DiagnosticLog.write(context, "full_image_failed", workId + "/" + fileName
                        + " | " + e.getClass().getSimpleName() + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void recordUse(String workId, String deviceName, String platform, Callback<UseResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/use-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("device", deviceName);
                body.put("platform", platform);
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                UseResult res = new UseResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optInt("useCount", 1),
                        json.optInt("remainingUses", 1),
                        json.optBoolean("moved", false),
                        json.optString("message", "")
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void resetWork(String workId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/reset-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                boolean ok = json.optBoolean("ok", false);
                mainHandler.post(() -> callback.onSuccess(ok));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // 在线回收站：已使用（_已发送1次）/ 已标记垃圾（_垃圾作品）
    // ------------------------------------------------------------------

    public static class RecycleResult {
        public final boolean ok;
        public final String tab;
        public final String label;
        public final int total;
        public final int sentCount;
        public final int garbageCount;
        public final List<OnlineWorkEntry> works;

        public RecycleResult(boolean ok, String tab, String label, int total,
                             int sentCount, int garbageCount, List<OnlineWorkEntry> works) {
            this.ok = ok;
            this.tab = tab == null ? "sent" : tab;
            this.label = label == null ? "" : label;
            this.total = total;
            this.sentCount = sentCount;
            this.garbageCount = garbageCount;
            this.works = works == null ? new ArrayList<OnlineWorkEntry>() : works;
        }
    }

    /** 拉取在线回收站某个 Tab 的列表；counts 里同时带有两个 Tab 的角标数字。 */
    public void fetchRecycle(String tab, Callback<RecycleResult> callback) {
        final String wantTab = (tab == null || tab.trim().isEmpty()) ? "sent" : tab.trim();
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/recycle?tab="
                        + URLEncoder.encode(wantTab, "UTF-8") + "&refresh=1");
                String resp = httpGet(url);
                JSONObject json = new JSONObject(resp);

                List<OnlineWorkEntry> list = new ArrayList<>();
                JSONArray arr = json.optJSONArray("works");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        OnlineWorkEntry entry = OnlineWorkEntry.fromJson(o);
                        if (entry != null) list.add(entry);
                    }
                }

                JSONObject counts = json.optJSONObject("counts");
                int sentCount = counts != null ? counts.optInt("sent", 0) : 0;
                int garbageCount = counts != null ? counts.optInt("garbage", 0) : 0;

                RecycleResult res = new RecycleResult(
                        json.optBoolean("ok", false),
                        json.optString("tab", wantTab),
                        json.optString("label", ""),
                        json.optInt("total", list.size()),
                        sentCount,
                        garbageCount,
                        list
                );
                markBaseUrlGood(baseUrl);
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public static class ActionResult {
        public final boolean ok;
        public final String workId;
        public final String message;
        public final String targetPath;

        public ActionResult(boolean ok, String workId, String message, String targetPath) {
            this.ok = ok;
            this.workId = workId == null ? "" : workId;
            this.message = message == null ? "" : message;
            this.targetPath = targetPath == null ? "" : targetPath;
        }
    }

    /** 回收站「恢复」：移回「已发送0次」并归零次数、撤销垃圾标记。 */
    public void restoreWork(String workId, Callback<ActionResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/restore");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("device", android.os.Build.MODEL != null ? android.os.Build.MODEL : "移动端");
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                ActionResult res = new ActionResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optString("message", ""),
                        json.optString("targetPath", "")
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /** 垃圾样本库备注：写入 quality_tag.json + manifest.json。 */
    public void remarkGarbage(String workId, String remark, Callback<ActionResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/remark-garbage");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("remark", remark == null ? "" : remark);
                body.put("device", android.os.Build.MODEL != null ? android.os.Build.MODEL : "移动端");
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                ActionResult res = new ActionResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optString("message", ""),
                        ""
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public static class DeleteResult {
        public final boolean ok;
        public final String workId;
        public final String action;
        public final String message;
        public final String targetPath;
        public final int remainingWorks;

        public DeleteResult(boolean ok, String workId, String action, String message, String targetPath, int remainingWorks) {
            this.ok = ok;
            this.workId = workId;
            this.action = action;
            this.message = message;
            this.targetPath = targetPath;
            this.remainingWorks = remainingWorks;
        }
    }

    public void deleteWork(String workId, Callback<DeleteResult> callback) {
        deleteWork(workId, "", callback);
    }

    public void deleteWork(String workId, String remark, Callback<DeleteResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/delete-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("deviceName", android.os.Build.MODEL != null ? android.os.Build.MODEL : "移动端");
                if (remark != null && !remark.trim().isEmpty()) {
                    body.put("remark", remark.trim());
                }
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                DeleteResult res = new DeleteResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optString("action", ""),
                        json.optString("message", ""),
                        json.optString("targetPath", ""),
                        json.optInt("remainingWorks", -1)
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public interface DownloadProgressCallback {
        void onProgress(int downloaded, int total, String currentFileName);
        void onSuccess(List<java.io.File> files);
        void onError(Exception error);
    }

    public void downloadWorkImages(String workId, List<String> fileNames, Callback<List<java.io.File>> callback) {
        downloadWorkImages(workId, fileNames, new DownloadProgressCallback() {
            @Override public void onProgress(int downloaded, int total, String currentFileName) {}
            @Override public void onSuccess(List<java.io.File> files) { callback.onSuccess(files); }
            @Override public void onError(Exception error) { callback.onError(error); }
        });
    }

    public void downloadWorkImages(String workId, List<String> fileNames, DownloadProgressCallback callback) {
        executor.execute(() -> {
            // DSH-099：记录循环中文件名，catch 块能定位到具体哪个 fileName 出错
            final String[] currentFileName = { null };
            try {
                String baseUrl = resolveBaseUrl();
                java.io.File targetDir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
                if (!targetDir.exists()) targetDir.mkdirs();

                List<java.io.File> result = new ArrayList<>();
                int total = fileNames != null ? fileNames.size() : 0;
                int count = 0;
                if (fileNames != null) {
                    for (String fileName : fileNames) {
                        currentFileName[0] = fileName;  // DSH-099：catch 块用
                        final int progStart = count;
                        final String curName = fileName;
                        mainHandler.post(() -> callback.onProgress(progStart, total, curName));

                        java.io.File localFile = new java.io.File(targetDir, fileName);
                        // DSH-100：服务端 _collect_images 两级策略 → 图在 产出素材/ 子目录时返回「成品库根相对路径」
                        // （如 "已发送0次（抖音小红书可发）/20260917_.../产出素材/P1.png"），必须先把中间子目录建出来，
                        // 否则 FileOutputStream 直接 ENOENT。iOS 走 ?path= 走 resolve_image_path 不需要这一步，
                        // Android 走 ?id+?file 必须显式 mkdirs。
                        java.io.File parent = localFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (localFile.exists() && localFile.length() > 0) {
                            result.add(localFile);
                            count++;
                            final int progDone = count;
                            mainHandler.post(() -> callback.onProgress(progDone, total, curName));
                            continue;
                        }
                        String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                                + "&file=" + URLEncoder.encode(fileName, "UTF-8");
                        URL url = new URL(uStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(TIMEOUT_MS);
                        conn.setReadTimeout(TIMEOUT_MS * 3);
                        int httpCode = conn.getResponseCode();
                        if (httpCode != 200) {
                            // DSH-099：HTTP 错误分支 —— Log.w + DiagnosticLog 双写（不抛 e 之前先留证）
                            String detail = "HTTP " + httpCode + " 下载 " + fileName + " 失败";
                            Log.w(TAG, "downloadWorkImages HTTP " + httpCode + " | " + workId + "/" + fileName);
                            DiagnosticLog.write(context, "download_work_http_error", workId + "/" + fileName
                                    + " | HTTP " + httpCode);
                            throw new Exception(detail);
                        }
                        InputStream in = new BufferedInputStream(conn.getInputStream());
                        java.io.FileOutputStream out = new java.io.FileOutputStream(localFile);
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                        out.close();
                        in.close();
                        conn.disconnect();
                        result.add(localFile);
                        count++;
                        final int progDone = count;
                        mainHandler.post(() -> callback.onProgress(progDone, total, curName));
                    }
                }
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                // DSH-099：总出口 —— Log.w（含堆栈）+ DiagnosticLog 双写（持久化 debug 回传铁律）
                String fname = currentFileName[0] != null ? currentFileName[0] : "<none>";
                Log.w(TAG, "downloadWorkImages failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + " | " + workId + "/" + fname, e);
                DiagnosticLog.write(context, "download_work_failed", workId + "/" + fname
                        + " | " + e.getClass().getSimpleName() + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    private String httpGet(URL url) throws Exception {
        try {
            return rawHttpGet(url);
        } catch (Exception e) {
            String uStr = url.toString();
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            if (!uStr.startsWith(loopback)) {
                try {
                    String fallback = uStr.replaceFirst("^https?://[^/]+", loopback);
                    String res = rawHttpGet(new URL(fallback));
                    cachedBaseUrl = loopback;
                    return res;
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static String rawHttpGet(URL url) throws Exception {
        return rawHttpGet(url, TIMEOUT_MS, TIMEOUT_MS);
    }

    /**
     * 带超时参数的 GET。列表/分类走 {@link #LIST_CONNECT_TIMEOUT_MS} 等更短的值，
     * 其余调用沿用原来的 {@link #TIMEOUT_MS}，行为不变。
     */
    private static String rawHttpGet(URL url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        conn.disconnect();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String httpPost(URL url, String jsonBody) throws Exception {
        try {
            return rawHttpPost(url, jsonBody);
        } catch (Exception e) {
            String uStr = url.toString();
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            if (!uStr.startsWith(loopback)) {
                try {
                    String fallback = uStr.replaceFirst("^https?://[^/]+", loopback);
                    String res = rawHttpPost(new URL(fallback), jsonBody);
                    cachedBaseUrl = loopback;
                    return res;
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static String rawHttpPost(URL url, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        OutputStream out = conn.getOutputStream();
        out.write(data);
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        ByteArrayOutputStream respOut = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            respOut.write(buf, 0, len);
        }
        in.close();
        conn.disconnect();
        return respOut.toString(StandardCharsets.UTF_8.name());
    }
}
