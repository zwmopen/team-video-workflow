package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong worksRequestSequence = new AtomicLong();
    private volatile long lastAppliedWorksScanRevision = -1L;
    private volatile long lastObservedServerScanRevision = -1L;
    private volatile boolean remoteCatalogActive = false;
    private volatile CategoriesResult lastRemoteCategoriesResult = null;

    public boolean isRemoteCatalogActive() {
        return remoteCatalogActive;
    }
    /**
     * 缩略图专用线程池（2026-09-20 新增）：
     * 旧实现把缩略图与列表/分类/操作请求塞进同一个 4 线程池，几百张缩略图一进来，
     * 列表请求就排在它们后面，界面长时间卡在「正在读取…」。
     */
    private final ThreadPoolExecutor thumbExecutor = new ThreadPoolExecutor(
            8, 8, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
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

    public interface ProgressCallback<T> extends Callback<T> {
        void onProgress(int percent);
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
        public final long firstSharedAtMs;
        public final long expireAtMs;
        public final String originDevice;

        public UseResult(boolean ok, String workId, int useCount, int remainingUses, boolean moved, String message) {
            this(ok, workId, useCount, remainingUses, moved, message, 0L, 0L, "");
        }

        public UseResult(boolean ok, String workId, int useCount, int remainingUses, boolean moved, String message,
                         long firstSharedAtMs, long expireAtMs, String originDevice) {
            this.ok = ok;
            this.workId = workId;
            this.useCount = useCount;
            this.remainingUses = remainingUses;
            this.moved = moved;
            this.message = message;
            this.firstSharedAtMs = firstSharedAtMs;
            this.expireAtMs = expireAtMs;
            this.originDevice = originDevice == null ? "" : originDevice;
        }
    }

    private static volatile OnlineGalleryClient instance;

    public static synchronized OnlineGalleryClient getInstance(Context context) {
        if (instance == null) {
            instance = new OnlineGalleryClient(context);
        }
        return instance;
    }

    private volatile RemoteRelayProfile.TransportStrategy lastResolvedStrategy = null;

    public OnlineGalleryClient(Context context) {
        this.context = context.getApplicationContext();
        // DSH-194：缩略图按「实际显示长边像素」向服务端要图。
        // 卡片缩略图 84×112dp / 紧凑列表 60×80dp，长边都是 112dp；红米 320dpi → 224px。
        // 服务端 1.2.2 起支持 thumb=1&w=N 按 N 缩放（64..320 夹紧），旧服务端忽略该参数，
        // 行为自动退回 320px 全量缩略图，双端版本不需要齐步走。
        thumbDisplayPx = Math.max(64, Math.round(112f * context.getResources().getDisplayMetrics().density));
    }

    /** DSH-194：缩略图请求的目标长边像素（= 112dp 换算），见构造器注释 */
    private final int thumbDisplayPx;

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
        RemoteRelayProfile.TransportStrategy strategy = RemoteRelayProfile.getStrategy(context);
        if (strategy != lastResolvedStrategy) {
            lastResolvedStrategy = strategy;
            cachedBaseUrl = null;
        }
        if (cachedBaseUrl != null && !cachedBaseUrl.isEmpty()) {
            return cachedBaseUrl;
        }
        if (strategy == RemoteRelayProfile.TransportStrategy.USB_FIRST) {
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            cachedBaseUrl = loopback;
            return loopback;
        }
        return resolveLanBaseUrl();
    }

    private String resolveLanBaseUrl() {
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

    public boolean shouldTryRemoteFirst() {
        return RemoteRelayProfile.getStrategy(context) == RemoteRelayProfile.TransportStrategy.R2_FIRST
                && RemoteRelayProfile.isEnrolled(context);
    }

    private static List<OnlineWorkEntry> filterRemoteWorks(List<OnlineWorkEntry> works, String category, String query) {
        if (works == null) return Collections.emptyList();
        boolean hasCat = category != null && !category.trim().isEmpty();
        boolean hasQuery = query != null && !query.trim().isEmpty();
        if (!hasCat && !hasQuery) return works;
        String qLower = hasQuery ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
        List<OnlineWorkEntry> out = new ArrayList<>();
        for (OnlineWorkEntry w : works) {
            if (hasCat && !category.equals(w.destination)) continue;
            if (hasQuery) {
                String haystack = ((w.title == null ? "" : w.title) + " " + (w.copyText == null ? "" : w.copyText) + " " + (w.destination == null ? "" : w.destination))
                        .toLowerCase(java.util.Locale.ROOT);
                if (!haystack.contains(qLower)) continue;
            }
            out.add(w);
        }
        return out;
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
            if (RemoteRelayProfile.getStrategy(context) == RemoteRelayProfile.TransportStrategy.USB_FIRST) {
                candidates.add("http://127.0.0.1:" + DEFAULT_PC_PORT);
            }
            candidates.add(resolveBaseUrl());

            String beacon = prefs.getString("beaconPcServerUrl", "").trim();
            if (!beacon.isEmpty()) candidates.add(beacon);
            String customUrl = prefs.getString("customPcServerUrl", "").trim();
            if (!customUrl.isEmpty()) candidates.add(customUrl);
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
                            // 【DSH-197】多网卡 / 地址漂移加固：电脑广播的是自己缓存的
                            // 「主网卡」地址，实测出现过自报 .107、而实际能连通的是 .101
                            // （DHCP 换址后日志仍是旧值）。收到包的源 IP 一定可达，
                            // 所以载荷 host 与包源 IP 不一致时，用包源 IP 重建 URL。
                            String sender = pkt.getAddress() != null
                                    ? pkt.getAddress().getHostAddress() : null;
                            applyBeaconUrl(preferPacketSource(url, sender), onAddressChanged);
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

    /** DSH-196：信标监听是否在跑。冷启动入口（系统分享面板）据此判断该不该自己拉起来。 */
    public boolean isBeaconRunning() {
        return beaconRunning;
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

    /**
     * 【DSH-197】把信标 URL 里的 host 换成「实际发包的源 IP」。
     *
     * 背景：电脑端信标载荷里的地址来自 `get_local_ip()`，多网卡/换网/换 DHCP 时
     * 可能指向一张并不在服务的网卡（实测：日志自报 192.168.0.107，实际可服务的是 .101），
     * 手机照着旧地址拉目录必然超时 ⇒ 在线设备列表长期为空。
     * 收到的 UDP 包源地址一定可达，所以载荷 host 与包源不一致且同为私网时，以包源为准。
     */
    private String preferPacketSource(String url, String sender) {
        if (url == null || sender == null || sender.isEmpty()) return url;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || host.equals(sender)) return url;
            if (!isPrivateIPv4(host)) return url;   // 域名 / 公网（远程中继）地址保持原样
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PC_PORT;
            DiagnosticLog.write(context, "beacon_host_mismatch",
                    "payload=" + host + " sender=" + sender);
            return "http://" + sender + ":" + port;
        } catch (Exception ignored) {
            return url;
        }
    }

    /** 仅 10/172.16-31/192.168 三类私网地址才算「局域网内可替换」 */
    private static boolean isPrivateIPv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            return a == 192 && b == 168;
        } catch (NumberFormatException error) {
            return false;
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
                // 扫描刷新是服务端后台完成的；revision 变更后重拉列表，避免一直停留在旧快照。
                JSONObject scan = json.optJSONObject("scan");
                String scanRevision = (scan != null) ? String.valueOf(scan.optLong("revision", -1L)) : "";
                lastObservedServerScanRevision = (scan != null) ? scan.optLong("revision", -1L) : -1L;
                final String fingerprint = total + "|" + changeAt + "|" + scanRevision;
                mainHandler.post(() -> callback.onSuccess(fingerprint));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void fetchCategories(Callback<CategoriesResult> callback) {
        fetchCategories(false, callback);
    }

    public void fetchCategories(boolean refresh, Callback<CategoriesResult> callback) {
        final long queuedAt = SystemClock.elapsedRealtime();
        executor.execute(() -> {
            final long startedAt = SystemClock.elapsedRealtime();
            if (shouldTryRemoteFirst()) {
                try {
                    fetchRemoteCatalogSnapshot();
                    CategoriesResult cached = lastRemoteCategoriesResult;
                    if (cached != null) {
                        remoteCatalogActive = true;
                        CategoriesResult finalCat = cached;
                        mainHandler.post(() -> callback.onSuccess(finalCat));
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/categories" + (refresh ? "?refresh=1" : ""));
                String resp = rawHttpGet(url, LIST_CONNECT_TIMEOUT_MS, LIST_READ_TIMEOUT_MS);
                CategoriesResult result = parseCategories(resp);
                Log.i(TAG, "fetchCategories parsed queueMs=" + (startedAt - queuedAt)
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " categories=" + result.categories.size());
                // 成功即落盘快照：下次进页面/离线时可直接渲染
                OnlineListCache.saveCategories(context, resp);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                if (RemoteRelayProfile.isEnrolled(context)) {
                    try {
                        CategoriesResult cached = lastRemoteCategoriesResult;
                        if (cached == null) {
                            fetchRemoteCatalogSnapshot();
                            cached = lastRemoteCategoriesResult;
                        }
                        if (cached != null) {
                            CategoriesResult finalCat = cached;
                            mainHandler.post(() -> callback.onSuccess(finalCat));
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                Log.w(TAG, "fetchCategories failed queueMs=" + (startedAt - queuedAt)
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " reason=" + diagnosticErrorKind(e));
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void fetchWorks(String category, String query, Callback<List<OnlineWorkEntry>> callback) {
        fetchWorks(category, query, null, false, callback);
    }

    // DSH-109：sortKey 可选，与服务端 SORT_KEYS 对齐；传 null 则不发 sort 参数（服务端默认 default）
    public void fetchWorks(String category, String query, String sortKey, Callback<List<OnlineWorkEntry>> callback) {
        fetchWorks(category, query, sortKey, false, callback);
    }

    public void fetchWorks(String category, String query, String sortKey, boolean refresh, Callback<List<OnlineWorkEntry>> callback) {
        final long requestSequence = worksRequestSequence.incrementAndGet();
        final boolean fullList = (category == null || category.isEmpty())
                && (query == null || query.isEmpty());
        final long queuedAt = SystemClock.elapsedRealtime();
        executor.execute(() -> {
            final long startedAt = SystemClock.elapsedRealtime();
            if (shouldTryRemoteFirst()) {
                try {
                    List<OnlineWorkEntry> remoteList = filterRemoteWorks(fetchRemoteCatalogSnapshot(), category, query);
                    mainHandler.post(() -> {
                        remoteCatalogActive = true;
                        callback.onSuccess(remoteList);
                    });
                    return;
                } catch (Exception remoteErr) {
                    Log.w(TAG, "R2_FIRST fetchWorks failed, falling back to LAN: " + remoteErr.getMessage());
                }
            }
            try {
                String baseUrl = resolveBaseUrl();
                StringBuilder sb = new StringBuilder(baseUrl).append("/api/online/works?");
                if (refresh) {
                    sb.append("refresh=1&");
                }
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
                JSONObject responseJson = new JSONObject(resp);
                if (responseJson.optJSONArray("works") == null) throw new java.io.IOException("在线相册响应缺少作品列表");
                long scanRevision = responseJson.optLong("scanRevision", -1L);
                if (requestSequence == worksRequestSequence.get()) {
                    lastAppliedWorksScanRevision = scanRevision;
                }
                RemoteRelayClient.enrollViaLanBestEffort(context, baseUrl);
                Log.i(TAG, "fetchWorks parsed queueMs=" + (startedAt - queuedAt)
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " fullList=" + fullList + " works=" + list.size());
                // 只有「全量列表」才值得做快照：带分类/关键词的响应是子集，
                // 存下去会让下次秒开时看到一个不完整的列表。
                if (fullList) OnlineListCache.saveWorks(context, resp);
                mainHandler.post(() -> {
                    remoteCatalogActive = false;
                    callback.onSuccess(list);
                });
            } catch (Exception e) {
                if (RemoteRelayProfile.isEnrolled(context)) {
                    try {
                        List<OnlineWorkEntry> remoteList = fetchRemoteCatalogSnapshot();
                        if (remoteList != null) {
                            mainHandler.post(() -> {
                                remoteCatalogActive = true;
                                callback.onSuccess(remoteList);
                            });
                            return;
                        }
                    } catch (Exception remoteErr) {
                        Log.w(TAG, "Remote catalog fallback failed: " + remoteErr.getMessage());
                    }
                }
                Log.w(TAG, "fetchWorks failed queueMs=" + (startedAt - queuedAt)
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " fullList=" + fullList + " reason=" + diagnosticErrorKind(e));
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /** 初次指纹轮询也能识别「列表请求返回旧快照、后台扫描已完成」的竞态。 */
    public boolean hasServerScanAdvancedSinceLastWorksFetch() {
        long observed = lastObservedServerScanRevision;
        long applied = lastAppliedWorksScanRevision;
        return observed > 0 && (applied < 0 || observed > applied);
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
            thumbExecutor.execute(new ThumbnailRequestTask(key, workId, fileName));
        }
    }

    /**
     * Discard thumbnail requests that have not started after the visible list changes.
     * Already-running requests finish normally; their callbacks are protected by view tags.
     */
    public int cancelPendingThumbnails() {
        int canceled = 0;
        synchronized (thumbLock) {
            for (Runnable pending : new ArrayList<>(thumbExecutor.getQueue())) {
                if (pending instanceof ThumbnailRequestTask) {
                    ThumbnailRequestTask task = (ThumbnailRequestTask) pending;
                    if (thumbExecutor.getQueue().remove(pending)) {
                        inflightThumbs.remove(task.key);
                        canceled++;
                    }
                }
            }
        }
        return canceled;
    }

    private final class ThumbnailRequestTask implements Runnable {
        private final String key;
        private final String workId;
        private final String fileName;

        private ThumbnailRequestTask(String key, String workId, String fileName) {
            this.key = key;
            this.workId = workId;
            this.fileName = fileName;
        }

        @Override
        public void run() {
            doLoadThumbnail(key, workId, fileName);
        }
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
    private boolean downloadThumbViaRemoteRelay(String workId, String fileName, File diskFile) {
        File tempFile = new File(diskFile.getParentFile(), diskFile.getName() + ".r2.tmp");
        try {
            RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
            String thumbKey = catalogThumbKey(workId, fileName);
            byte[] jpg;
            try {
                jpg = RemoteRelayClient.downloadCatalogThumb(session, thumbKey);
            } catch (RemoteRelayClient.HttpStatusException missing) {
                if ((missing.statusCode != 404 && missing.statusCode != 410)
                        || fileName == null || !fileName.startsWith("__src__:")) throw missing;
                RemoteRelayClient.pullCatalogOriginal(session, workId, "__preview__:" + fileName, tempFile);
                jpg = tempFile.length() <= THUMB_MAX_BYTES
                        ? java.nio.file.Files.readAllBytes(tempFile.toPath()) : null;
                tempFile.delete();
            }
            if (jpg != null && jpg.length > 0 && jpg.length <= THUMB_MAX_BYTES) {
                File dir = tempFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(tempFile)) {
                    out.write(jpg);
                    out.flush();
                }
                if (diskFile.exists()) diskFile.delete();
                if (tempFile.renameTo(diskFile)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            if (tempFile.exists()) tempFile.delete();
        }
        return false;
    }

    private boolean downloadThumb(String workId, String fileName, File diskFile) throws Exception {
        boolean remoteFirst = isRemoteCatalogActive() || shouldTryRemoteFirst();
        if (remoteFirst && downloadThumbViaRemoteRelay(workId, fileName, diskFile)) {
            return true;
        }
        HttpURLConnection conn = null;
        File tempFile = new File(diskFile.getParentFile(), diskFile.getName() + ".tmp");
        try {
            String baseUrl = resolveBaseUrl();
            String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                    + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=1&w=" + thumbDisplayPx;
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
            if (!remoteFirst && RemoteRelayProfile.isEnrolled(context)
                    && downloadThumbViaRemoteRelay(workId, fileName, diskFile)) return true;
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
                        if (callback instanceof ProgressCallback) {
                            mainHandler.post(() -> ((ProgressCallback<Bitmap>) callback).onProgress(100));
                        }
                        mainHandler.post(() -> callback.onSuccess(bmp));
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 2. 本地无缓存或损坏，优先按传输策略拉取并原子安全落盘
            if (isRemoteCatalogActive() || shouldTryRemoteFirst()) {
                try {
                    RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                    RemoteRelayClient.pullCatalogOriginal(session, workId, fileName, diskFile);
                    TransferStatsStore.record(context, "r2", diskFile.length(), 1);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        if (callback instanceof ProgressCallback) {
                            mainHandler.post(() -> ((ProgressCallback<Bitmap>) callback).onProgress(100));
                        }
                        mainHandler.post(() -> callback.onSuccess(bmp));
                        return;
                    }
                } catch (Exception remoteErr) {
                    Log.w(TAG, "R2_FIRST full image failed, falling back to LAN: " + remoteErr.getMessage());
                }
            }

            HttpURLConnection conn = null;
            File tempFile = new File(cacheDir, getDiskCacheKey(workId, fileName) + ".tmp");
            try {
                String baseUrl = resolveBaseUrl();
                String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                        + "&file=" + URLEncoder.encode(fileName, "UTF-8") + "&thumb=0"
                        // DSH-186：查看器大图改拉服务端缩放图（w=1600 长边，实测 2.7MB→492KB）；
                        // 2.7MB 原图只留给「下载到相册/分享」链路（downloadOneImageToFile）。
                        + "&w=1600";
                URL url = new URL(uStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Connection", "close");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                int totalBytes = conn.getContentLength();
                InputStream in = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int len;
                long readBytes = 0;
                while ((len = in.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    readBytes += len;
                    if (totalBytes > 0 && callback instanceof ProgressCallback) {
                        final int percent = (int) Math.min(99, (readBytes * 100L) / totalBytes);
                        mainHandler.post(() -> ((ProgressCallback<Bitmap>) callback).onProgress(percent));
                    }
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
                if (callback instanceof ProgressCallback) {
                    mainHandler.post(() -> ((ProgressCallback<Bitmap>) callback).onProgress(100));
                }
                mainHandler.post(() -> callback.onSuccess(bmp));
            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                if (RemoteRelayProfile.isEnrolled(context)) {
                    try {
                        RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                        RemoteRelayClient.pullCatalogOriginal(session, workId, fileName, diskFile);
                        TransferStatsStore.record(context, "r2", diskFile.length(), 1);
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                        if (bmp != null) {
                            if (callback instanceof ProgressCallback) {
                                mainHandler.post(() -> ((ProgressCallback<Bitmap>) callback).onProgress(100));
                            }
                            mainHandler.post(() -> callback.onSuccess(bmp));
                            return;
                        }
                    } catch (Exception remoteErr) {
                        Log.w(TAG, "Remote catalog full image failed: " + remoteErr.getMessage());
                    }
                }
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
        recordUse(workId, deviceName, platform, 3600000L, "", callback);
    }

    public void recordUse(String workId, String deviceName, String platform, long retentionDurationMs, String versionTag, Callback<UseResult> callback) {
        executor.execute(() -> {
            if (shouldTryRemoteFirst()) {
                try {
                    RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                    RemoteRelayClient.recordCatalogWorkUsed(session, workId);
                    UseResult res = new UseResult(true, workId, 1, 1, false,
                            "已通过 R2 云端中转记录使用（待电脑同步写入标签）", 0L, 0L, "");
                    mainHandler.post(() -> callback.onSuccess(res));
                    return;
                } catch (Exception ignored) {
                }
            }
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/use-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("device", deviceName);
                body.put("platform", platform);
                body.put("retentionDurationMs", retentionDurationMs);
                if (versionTag != null && !versionTag.trim().isEmpty()) {
                    body.put("versionTag", versionTag.trim());
                }
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                UseResult res = new UseResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optInt("useCount", 1),
                        json.optInt("remainingUses", 1),
                        json.optBoolean("moved", false),
                        json.optString("message", ""),
                        json.optLong("firstSharedAtMs", 0L),
                        json.optLong("expireAtMs", 0L),
                        json.optString("originDevice", "")
                );
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                if (RemoteRelayProfile.isEnrolled(context)) {
                    try {
                        RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                        RemoteRelayClient.recordCatalogWorkUsed(session, workId);
                        UseResult res = new UseResult(true, workId, 1, 1, false,
                                "已通过云端中转记录使用（待电脑同步写入标签）", 0L, 0L, "");
                        mainHandler.post(() -> callback.onSuccess(res));
                        return;
                    } catch (Exception ignored) {
                    }
                }
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void resetWork(String workId, Callback<ActionResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/reset-work");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                boolean ok = json.optBoolean("ok", false);
                String msg = json.optString("message", ok ? "已重置为待首发状态" : "重置失败");
                ActionResult res = new ActionResult(ok, workId, msg, json.optString("newPath", ""));
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * DSH-138: 手机端在线修改文案并同步写回电脑真源（文案.txt）。
     */
    public void updateCopy(String workId, String updatedCopy, String deviceName, String versionTag, Callback<ActionResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/update-copy");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("updatedCopy", updatedCopy);
                body.put("device", deviceName);
                if (versionTag != null && !versionTag.trim().isEmpty()) {
                    body.put("versionTag", versionTag.trim());
                }
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                boolean ok = json.optBoolean("ok", false);
                String msg = json.optString("message", ok ? "文案已成功同步至电脑" : "更新失败");
                ActionResult res = new ActionResult(ok, workId, msg, "");
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // 在线回收站：已使用（_已发送）/ 已标记垃圾（_垃圾作品）
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

    public static class DeleteImageResult {
        public final boolean ok;
        public final String workId;
        public final String image;
        public final String message;
        public final String error;
        public final List<String> remainingImages;

        public DeleteImageResult(boolean ok, String workId, String image, String message, String error, List<String> remainingImages) {
            this.ok = ok;
            this.workId = workId;
            this.image = image;
            this.message = message;
            this.error = error;
            this.remainingImages = remainingImages != null ? remainingImages : Collections.emptyList();
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

    /**
     * DSH-141: 预览界面直接删除单张图片（安全移入 _垃圾作品样本/_deleted_images/ 零丢失备份）
     */
    public void deleteImage(String workId, String imageName, String deviceName, Callback<DeleteImageResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                URL url = new URL(baseUrl + "/api/online/delete-image");
                JSONObject body = new JSONObject();
                body.put("workId", workId);
                body.put("image", imageName);
                body.put("deviceName", (deviceName != null && !deviceName.isEmpty()) ? deviceName : (android.os.Build.MODEL != null ? android.os.Build.MODEL : "移动端"));
                String resp = httpPost(url, body.toString());
                JSONObject json = new JSONObject(resp);
                List<String> remaining = new ArrayList<>();
                JSONArray arr = json.optJSONArray("remainingImages");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        remaining.add(arr.getString(i));
                    }
                }
                DeleteImageResult res = new DeleteImageResult(
                        json.optBoolean("ok", false),
                        json.optString("workId", workId),
                        json.optString("image", imageName),
                        json.optString("message", ""),
                        json.optString("error", ""),
                        remaining
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

    /**
     * 判断指定图片是否具备服务端实况伴生源（按全路径或 basename 匹配）
     */
    public static boolean isLiveImage(java.util.Map<String, String> livePhotos, String fileName) {
        if (livePhotos == null || livePhotos.isEmpty() || fileName == null || fileName.isEmpty()) {
            return false;
        }
        if (livePhotos.containsKey(fileName)) return true;
        String norm = fileName.replace('\\', '/');
        if (livePhotos.containsKey(norm)) return true;
        int slash = norm.lastIndexOf('/');
        String bn = slash >= 0 ? norm.substring(slash + 1) : norm;
        for (String key : livePhotos.keySet()) {
            if (key == null) continue;
            String kNorm = key.replace('\\', '/');
            int kSlash = kNorm.lastIndexOf('/');
            String kBn = kSlash >= 0 ? kNorm.substring(kSlash + 1) : kNorm;
            if (bn.equals(kBn)) return true;
        }
        return false;
    }

    private static java.io.File resolveLocalImageCacheFile(java.io.File targetDir, String fileName, boolean isLive, boolean autoLive, String liveMode) {
        if (!isLive && !autoLive) {
            return new java.io.File(targetDir, fileName);
        }
        String base = fileName;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        if (dot > slash) {
            base = fileName.substring(0, dot);
        }
        if (isLive) {
            return new java.io.File(targetDir, base + ".live.jpg");
        }
        String safeMode = LiveMotionPreset.normalizeMode(liveMode);
        return new java.io.File(targetDir, base + ".autolive." + safeMode + ".jpg");
    }

    private static java.io.File resolveLocalImageCacheFile(java.io.File targetDir, String fileName, boolean isLive, boolean autoLive) {
        return resolveLocalImageCacheFile(targetDir, fileName, isLive, autoLive, null);
    }

    private static java.io.File resolveLocalImageCacheFile(java.io.File targetDir, String fileName, boolean isLive) {
        return resolveLocalImageCacheFile(targetDir, fileName, isLive, false, null);
    }

    /**
     * DSH-136: 极速检测本地是否已 100% 缓存该作品所有图片
     */
    public boolean areAllWorkImagesCachedLocally(String workId, List<String> fileNames) {
        return areAllWorkImagesCachedLocally(workId, fileNames, null);
    }

    public boolean areAllWorkImagesCachedLocally(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos) {
        return areAllWorkImagesCachedLocally(workId, fileNames, livePhotos, false, null);
    }

    public boolean areAllWorkImagesCachedLocally(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos, boolean autoLive, String liveMode) {
        if (fileNames == null || fileNames.isEmpty()) return true;
        java.io.File targetDir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
        for (String fileName : fileNames) {
            boolean isLive = isLiveImage(livePhotos, fileName);
            boolean isAutoSynth = autoLive && !isLive;
            java.io.File localFile = resolveLocalImageCacheFile(targetDir, fileName, isLive, isAutoSynth, liveMode);
            if (!localFile.exists() || localFile.length() <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * DSH-136: 获取本地已完整缓存的文件列表（用于 0 弹窗直接秒开系统分享）
     */
    public List<java.io.File> getCachedLocalWorkImages(String workId, List<String> fileNames) {
        return getCachedLocalWorkImages(workId, fileNames, null, false, null);
    }

    public List<java.io.File> getCachedLocalWorkImages(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos) {
        return getCachedLocalWorkImages(workId, fileNames, livePhotos, false, null);
    }

    public List<java.io.File> getCachedLocalWorkImages(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos, boolean autoLive, String liveMode) {
        List<java.io.File> result = new ArrayList<>();
        if (fileNames == null || fileNames.isEmpty()) return result;
        java.io.File targetDir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
        for (String fileName : fileNames) {
            boolean isLive = isLiveImage(livePhotos, fileName);
            boolean isAutoSynth = autoLive && !isLive;
            java.io.File localFile = resolveLocalImageCacheFile(targetDir, fileName, isLive, isAutoSynth, liveMode);
            if (localFile.exists() && localFile.length() > 0) {
                result.add(localFile);
            }
        }
        return result;
    }

    public void downloadWorkVideo(String workId, String filename, Callback<java.io.File> callback) {
        executor.execute(() -> {
            try {
                if (workId.isEmpty() || workId.contains("..") || workId.contains("/") || workId.contains("\\")
                        || filename.contains("..") || filename.contains("/") || filename.contains("\\")
                        || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
                    throw new java.io.IOException("视频文件名无效");
                }
                java.io.File dir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    throw new java.io.IOException("无法创建视频缓存目录");
                }
                URL url = new URL(resolveBaseUrl() + "/api/online/video?id=" + URLEncoder.encode(workId, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS * 3);
                java.io.File temporary = null;
                java.io.File file = new java.io.File(dir, filename);
                try {
                    if (conn.getResponseCode() != 200 || !"video/mp4".equals(conn.getContentType())) {
                        throw new java.io.IOException("电脑未返回视频");
                    }
                    long expected = conn.getContentLengthLong();
                    temporary = java.io.File.createTempFile("video-", ".part", dir);
                    try (InputStream in = conn.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(temporary)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    if (expected <= 0 || temporary.length() != expected || !temporary.renameTo(file)) {
                        throw new java.io.IOException("视频未完整保存");
                    }
                } finally {
                    conn.disconnect();
                    if (temporary != null) temporary.delete();
                }
                mainHandler.post(() -> callback.onSuccess(file));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    public void downloadWorkImages(String workId, List<String> fileNames, Callback<List<java.io.File>> callback) {
        downloadWorkImages(workId, fileNames, new DownloadProgressCallback() {
            @Override public void onProgress(int downloaded, int total, String currentFileName) {}
            @Override public void onSuccess(List<java.io.File> files) { callback.onSuccess(files); }
            @Override public void onError(Exception error) { callback.onError(error); }
        });
    }

    public void downloadWorkImages(String workId, List<String> fileNames, DownloadProgressCallback callback) {
        downloadWorkImages(workId, fileNames, null, callback);
    }

    public void downloadWorkImages(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos, DownloadProgressCallback callback) {
        downloadWorkImages(workId, fileNames, livePhotos, false, callback);
    }

    public void downloadWorkImages(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos, boolean autoLive, DownloadProgressCallback callback) {
        downloadWorkImages(workId, fileNames, livePhotos, autoLive, null, callback);
    }

    public void downloadWorkImages(String workId, List<String> fileNames, java.util.Map<String, String> livePhotos, boolean autoLive, String liveMode, DownloadProgressCallback callback) {
        executor.execute(() -> {
            // DSH-099：记录循环中文件名，catch 块能定位到具体哪个 fileName 出错
            final String[] currentFileName = { null };
            try {
                String baseUrl = resolveBaseUrl();
                java.io.File targetDir = new java.io.File(context.getFilesDir(), "work-library/online/" + workId);
                if (!targetDir.exists()) targetDir.mkdirs();

                List<java.io.File> result = new ArrayList<>();
                int total = fileNames != null ? fileNames.size() : 0;
                if (fileNames != null) {
                    // DSH-185 修订：并发回退为串行。真机实测 4 路并发下 6 张原图反而 ~84s
                    // （WiFi 半双工多路碰撞 + 机械盘随机寻道），串行配合「去 fsync + 64KB 缓冲」
                    // 更稳更快。downloadOneImageToFile 保留原子落盘口径（DSH-118）。
                    int count = 0;
                    for (String fileName : fileNames) {
                        currentFileName[0] = fileName;  // DSH-099：catch 块用
                        final int progStart = count;
                        final String curName = fileName;
                        mainHandler.post(() -> callback.onProgress(progStart, total, curName));
                        boolean isLive = isLiveImage(livePhotos, fileName);
                        java.io.File f = downloadOneImageToFile(baseUrl, targetDir, workId, fileName, isLive, autoLive, liveMode);
                        result.add(f);
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

    /**
     * DSH-185：单张原图下载（原子落盘 + 64KB 缓冲，去掉逐张 fsync）。返回最终文件。
     * 与旧串行循环体等价，但抽成独立方法供并发池调用；原子 rename 口径不变（DSH-118）。
     */
    private java.io.File downloadOneImageToFile(String baseUrl, java.io.File targetDir, String workId, String fileName, boolean isLive, boolean autoLive, String liveMode) throws Exception {
        boolean isAutoSynth = autoLive && !isLive;
        if (isAutoSynth) {
            String safeMode = LiveMotionPreset.normalizeMode(liveMode);
            java.io.File rawFile = downloadOneImageToFile(baseUrl, targetDir, workId, fileName, false, false, null);
            java.io.File liveOut = resolveLocalImageCacheFile(targetDir, fileName, false, true, safeMode);
            float ratio = OnlineService.getAutoLiveRatio(context);
            float dur = OnlineService.getAutoLiveDuration(context);
            boolean skipExisting = OnlineService.isAutoLiveSkipExisting(context);
            return LiveLocalConverter.convertFileToFile(context, rawFile, liveOut, safeMode, ratio, dur, skipExisting);
        }
        java.io.File localFile = resolveLocalImageCacheFile(targetDir, fileName, isLive, false, liveMode);
        // DSH-100：图在 产出素材/ 子目录时返回「成品库根相对路径」，必须先建中间子目录。
        java.io.File parent = localFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (localFile.exists() && localFile.length() > 0) {
            return localFile;   // 已缓存直接复用
        }
        if (!isLive && shouldTryRemoteFirst()) {
            try {
                RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                RemoteRelayClient.pullCatalogOriginal(session, workId, fileName, localFile);
                TransferStatsStore.record(context, "r2", localFile.length(), 1);
                return localFile;
            } catch (Exception remoteErr) {
                Log.w(TAG, "R2_FIRST downloadOneImageToFile failed, falling back to LAN: " + remoteErr.getMessage());
            }
        }
        String uStr = baseUrl + "/api/online/image?id=" + URLEncoder.encode(workId, "UTF-8")
                + "&file=" + URLEncoder.encode(fileName, "UTF-8")
                + (isLive ? "&live=motion" : "");
        try {
            URL url = new URL(uStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS * 3);
            if (isLive) {
                conn.setRequestProperty("X-Client-Platform", "android");
            }
            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                Log.w(TAG, "downloadWorkImages HTTP " + httpCode + " | " + workId + "/" + fileName);
                DiagnosticLog.write(context, "download_work_http_error", workId + "/" + fileName
                        + " | HTTP " + httpCode);
                throw new Exception("HTTP " + httpCode + " 下载 " + fileName + " 失败");
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            // 【DSH-118】临时文件 + 原子 rename，残缺文件不会被当成成品复用。
            java.io.File partFile = new java.io.File(localFile.getAbsolutePath() + ".part");
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "残留临时文件清理失败: " + partFile.getName());
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(partFile);
            byte[] buf = new byte[65536];   // DSH-185：8KB → 64KB，2.7MB 原图系统调用少 8 倍
            int len;
            long writtenBytes = 0L;
            try {
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    writtenBytes += len;
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
            }
            conn.disconnect();
            if (writtenBytes <= 0) {
                if (partFile.exists() && !partFile.delete()) { /* ignore */ }
                Log.w(TAG, "downloadWorkImages 空文件 | " + workId + "/" + fileName);
                DiagnosticLog.write(context, "download_work_empty",
                        workId + "/" + fileName + " | written=0");
                throw new Exception("下载内容为空：" + fileName);
            }
            TransferStatsStore.record(context, "lan", writtenBytes, 1);
            if (localFile.exists() && !localFile.delete()) {
                Log.w(TAG, "旧文件删除失败: " + localFile.getName());
            }
            if (!partFile.renameTo(localFile)) {
                String detail = "renameTo failed: " + partFile + " -> " + localFile;
                Log.w(TAG, detail);
                DiagnosticLog.write(context, "download_work_rename_failed",
                        workId + "/" + fileName + " | " + detail);
                java.io.FileInputStream fin = new java.io.FileInputStream(partFile);
                java.io.FileOutputStream fout = new java.io.FileOutputStream(localFile);
                try {
                    byte[] cb = new byte[65536];
                    int cl;
                    while ((cl = fin.read(cb)) != -1) fout.write(cb, 0, cl);
                    fout.flush();
                } finally {
                    try { fout.close(); } catch (Throwable ignored) { }
                    try { fin.close(); } catch (Throwable ignored) { }
                }
                if (partFile.exists() && !partFile.delete()) { /* ignore */ }
            }
            return localFile;
        } catch (Exception lanErr) {
            if (!isLive && RemoteRelayProfile.isEnrolled(context)) {
                RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
                RemoteRelayClient.pullCatalogOriginal(session, workId, fileName, localFile);
                TransferStatsStore.record(context, "r2", localFile.length(), 1);
                return localFile;
            }
            throw lanErr;
        }
    }

    private static String catalogThumbKey(String workId, String fileName) throws Exception {
        String hex = RemoteProtocol.sha256Hex((workId + "/" + fileName).getBytes(StandardCharsets.UTF_8));
        return "th_" + hex.substring(0, 20);
    }

    private List<OnlineWorkEntry> fetchRemoteCatalogSnapshot() throws Exception {
        RemoteRelayClient.Session session = RemoteRelayClient.ensureSession(context);
        JSONObject manifest = RemoteRelayClient.fetchCatalogManifest(session);
        JSONArray rawWorks = manifest.optJSONArray("works");
        JSONArray normWorks = new JSONArray();
        java.util.Map<String, Integer> catCounts = new java.util.LinkedHashMap<>();
        if (rawWorks != null) {
            for (int i = 0; i < rawWorks.length(); i++) {
                JSONObject w = rawWorks.optJSONObject(i);
                if (w == null) continue;
                JSONObject item = new JSONObject();
                String id = w.optString("id", "");
                if (id.isEmpty()) continue;
                String cat = w.optString("category", "其他");
                item.put("id", id);
                item.put("title", w.optString("name", id));
                item.put("destination", cat);
                item.put("stage", "已发送0次（云端浏览）");
                item.put("useCount", w.optInt("useCount", 0));
                item.put("maxUses", 2);
                item.put("used", w.optInt("useCount", 0) > 0);
                item.put("images", w.optJSONArray("images") != null ? w.optJSONArray("images") : new JSONArray());
                if (w.has("sourceImages")) item.put("sourceImages", w.opt("sourceImages"));
                item.put("hasSourceCompare", w.optBoolean("hasSourceCompare", false));
                if (w.has("workLabels")) item.put("workLabels", w.opt("workLabels"));
                item.put("copyText", w.optString("copyText", ""));
                item.put("updatedAt", w.optLong("updatedAt", 0L));
                item.put("videoFilename", w.optString("videoFilename", ""));
                if (w.has("livePhotos")) item.put("livePhotos", w.opt("livePhotos"));
                normWorks.put(item);
                catCounts.put(cat, catCounts.containsKey(cat) ? catCounts.get(cat) + 1 : 1);
            }
        }
        List<OnlineWorkEntry> parsedWorks = parseWorks(new JSONObject().put("works", normWorks).toString());
        List<CategoryItem> cats = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> entry : catCounts.entrySet()) {
            cats.add(new CategoryItem(entry.getKey(), entry.getValue()));
        }
        lastRemoteCategoriesResult = new CategoriesResult(cats, Collections.emptyList(), parsedWorks.size(), "{}");
        return parsedWorks;
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
            } else {
                String lan = resolveLanBaseUrl();
                if (!lan.equals(loopback)) {
                    try {
                        String fallback = uStr.replaceFirst("^https?://[^/]+", lan);
                        String res = rawHttpGet(new URL(fallback));
                        cachedBaseUrl = lan;
                        return res;
                    } catch (Exception ignored) {}
                }
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
        final String path = url.getPath();
        final boolean listEndpoint = "/api/online/works".equals(path)
                || "/api/online/categories".equals(path);
        final long startedAt = SystemClock.elapsedRealtime();
        HttpURLConnection conn = null;
        int code = -1;
        long responseBytes = 0L;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                responseBytes += len;
            }
            in.close();
            if (listEndpoint) {
                Log.i(TAG, "GET " + path + " complete status=" + code
                        + " responseBytes=" + responseBytes
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            if (listEndpoint) {
                Log.w(TAG, "GET " + path + " failed status=" + (code < 0 ? "none" : code)
                        + " responseBytes=" + responseBytes
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " reason=" + diagnosticErrorKind(e));
            }
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String diagnosticErrorKind(Exception error) {
        String message = error.getMessage();
        if (message != null && message.startsWith("HTTP ")) {
            String[] parts = message.split("\\s+", 3);
            if (parts.length > 1) return "HTTP_" + parts[1];
        }
        return error.getClass().getSimpleName();
    }

    static String userFacingErrorMessage(Exception error) {
        if (error == null) return "网络异常";
        if (error instanceof SocketTimeoutException) return "连接超时";
        if (error instanceof ConnectException || error instanceof NoRouteToHostException) {
            return "无法连接电脑";
        }
        if (error instanceof UnknownHostException) return "电脑地址不可用";
        String message = error.getMessage();
        if (message != null && message.startsWith("HTTP ")) return "电脑服务暂不可用";
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("failed to connect") || lower.contains("network is unreachable")) {
            return "无法连接电脑";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) return "连接超时";
        return "网络异常";
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
            } else {
                String lan = resolveLanBaseUrl();
                if (!lan.equals(loopback)) {
                    try {
                        String fallback = uStr.replaceFirst("^https?://[^/]+", lan);
                        String res = rawHttpPost(new URL(fallback), jsonBody);
                        cachedBaseUrl = lan;
                        return res;
                    } catch (Exception ignored) {}
                }
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

    public static final class ViewStateResult {
        public final String viewMode;
        public final long updatedAt;
        public final String updatedBy;

        public ViewStateResult(String viewMode, long updatedAt, String updatedBy) {
            this.viewMode = viewMode == null ? "grid" : viewMode;
            this.updatedAt = updatedAt;
            this.updatedBy = updatedBy == null ? "" : updatedBy;
        }
    }

    public void fetchViewState(Callback<ViewStateResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                String raw = httpGet(new URL(baseUrl + "/api/online/view-state"));
                JSONObject obj = new JSONObject(raw);
                JSONObject vs = obj.optJSONObject("viewState");
                if (vs == null) vs = obj;
                String mode = vs.optString("viewMode", "grid");
                long updated = vs.optLong("updatedAt", 0L);
                String by = vs.optString("updatedBy", "");
                final ViewStateResult res = new ViewStateResult(mode, updated, by);
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void pushViewState(String viewMode, String device, Callback<ViewStateResult> callback) {
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                JSONObject body = new JSONObject();
                body.put("viewMode", viewMode);
                body.put("device", device != null ? device : "Android真机");
                String raw = httpPost(new URL(baseUrl + "/api/online/view-state"), body.toString());
                JSONObject obj = new JSONObject(raw);
                JSONObject vs = obj.optJSONObject("viewState");
                if (vs == null) vs = obj;
                String mode = vs.optString("viewMode", viewMode);
                long updated = vs.optLong("updatedAt", System.currentTimeMillis());
                String by = vs.optString("updatedBy", device != null ? device : "Android真机");
                final ViewStateResult res = new ViewStateResult(mode, updated, by);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(res));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    public void syncClipboard(String text) {
        syncClipboard(text, null);
    }

    public void syncClipboard(String text, Callback<Boolean> callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onSuccess(false);
            return;
        }
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                JSONObject body = new JSONObject();
                body.put("text", text);
                String devName = android.os.Build.MODEL != null && !android.os.Build.MODEL.isEmpty() ? android.os.Build.MODEL : "Android";
                body.put("deviceName", devName);
                body.put("timestamp", System.currentTimeMillis());
                httpPost(new URL(baseUrl + "/api/online/clipboard"), body.toString());
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(true));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    public void syncLiveMotionModeAsync(String mode) {
        if (mode == null || mode.trim().isEmpty()) return;
        executor.execute(() -> {
            try {
                String baseUrl = resolveBaseUrl();
                JSONObject body = new JSONObject();
                body.put("mode", mode.trim());
                httpPost(new URL(baseUrl + "/api/online/live-config"), body.toString());
            } catch (Exception ignored) {
            }
        });
    }

    public static final class LiveEngineStatus {
        public final boolean reachable;
        public final boolean zhipuReady;
        public final String zhipuMasked;
        public final boolean flowReady;
        public final int flowCredits;
        public final String flowReason;

        LiveEngineStatus(boolean reachable, boolean zhipuReady, String zhipuMasked,
                         boolean flowReady, int flowCredits, String flowReason) {
            this.reachable = reachable;
            this.zhipuReady = zhipuReady;
            this.zhipuMasked = zhipuMasked != null ? zhipuMasked : "";
            this.flowReady = flowReady;
            this.flowCredits = flowCredits;
            this.flowReason = flowReason != null ? flowReason : "";
        }

        public static LiveEngineStatus offline() {
            return new LiveEngineStatus(false, false, "", false, 0, "未连接电脑服务端");
        }
    }

    private String rawHttpGetWithLoopbackFallback(String pathAndQuery, int connectMs, int readMs) throws Exception {
        String primary = resolveBaseUrl();
        try {
            return rawHttpGet(new URL(primary + pathAndQuery), connectMs, readMs);
        } catch (Exception primaryErr) {
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            String alt = isLoopbackUrl(primary) ? resolveLanBaseUrl() : loopback;
            if (!alt.equals(primary)) {
                try {
                    return rawHttpGet(new URL(alt + pathAndQuery), connectMs, readMs);
                } catch (Exception ignored) {}
            }
            throw primaryErr;
        }
    }

    private String rawHttpPostWithLoopbackFallback(String path, String jsonBody, int connectMs, int readMs) throws Exception {
        String primary = resolveBaseUrl();
        try {
            return rawHttpPostWithTimeout(new URL(primary + path), jsonBody, connectMs, readMs);
        } catch (Exception primaryErr) {
            String loopback = "http://127.0.0.1:" + DEFAULT_PC_PORT;
            String alt = isLoopbackUrl(primary) ? resolveLanBaseUrl() : loopback;
            if (!alt.equals(primary)) {
                try {
                    return rawHttpPostWithTimeout(new URL(alt + path), jsonBody, connectMs, readMs);
                } catch (Exception ignored) {}
            }
            throw primaryErr;
        }
    }

    public LiveEngineStatus probeLiveEngineStatusSync(boolean forceRefresh) {
        try {
            String pathAndQuery = "/api/online/live-engine-status" + (forceRefresh ? "?refresh=1" : "");
            String raw = rawHttpGetWithLoopbackFallback(pathAndQuery, 3500, 35000);
            JSONObject root = new JSONObject(raw);
            JSONObject engines = root.optJSONObject("engines");
            if (engines == null) return LiveEngineStatus.offline();
            JSONObject zp = engines.optJSONObject("zhipu");
            JSONObject fl = engines.optJSONObject("google_flow");
            boolean zpReady = zp != null && zp.optBoolean("ready", false);
            String zpMasked = zp != null ? zp.optString("keyMasked", "") : "";
            boolean flReady = fl != null && fl.optBoolean("ready", false);
            int flCredits = (fl != null && !fl.isNull("credits")) ? fl.optInt("credits", 0) : 0;
            String flReason = fl != null ? fl.optString("reason", "") : "";
            return new LiveEngineStatus(true, zpReady, zpMasked, flReady, flCredits, flReason);
        } catch (Exception e) {
            return LiveEngineStatus.offline();
        }
    }

    public void fetchLiveEngineStatus(boolean forceRefresh, Callback<LiveEngineStatus> callback) {
        executor.execute(() -> {
            final LiveEngineStatus status = probeLiveEngineStatusSync(forceRefresh);
            mainHandler.post(() -> callback.onSuccess(status));
        });
    }

    public void reportTransferStatsAsync(String channel, long bytes, int objects) {
        executor.execute(() -> {
            try {
                String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (deviceId == null || deviceId.trim().isEmpty()) {
                    deviceId = android.os.Build.MODEL != null ? android.os.Build.MODEL : "android-device";
                }
                SharedPreferences sp = context.getSharedPreferences("device_share", Context.MODE_PRIVATE);
                String deviceName = sp.getString("deviceName", "").trim();
                if (deviceName.isEmpty()) {
                    deviceName = android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android 手机";
                }
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("deviceName", deviceName);
                body.put("channel", channel != null ? channel : "lan");
                body.put("bytes", Math.max(0L, bytes));
                body.put("objects", Math.max(0, objects));
                rawHttpPostWithLoopbackFallback("/api/online/transfer-stats/report", body.toString(), 2500, 4000);
            } catch (Exception ignored) {
            }
        });
    }

    public JSONObject fetchPcTransferStatsSync() {
        try {
            String raw = rawHttpGetWithLoopbackFallback("/api/online/transfer-stats", 3500, 6000);
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void fetchPcTransferStats(Callback<JSONObject> callback) {
        executor.execute(() -> {
            final JSONObject stats = fetchPcTransferStatsSync();
            mainHandler.post(() -> callback.onSuccess(stats));
        });
    }

    public void saveZhipuApiKey(String apiKey, Callback<LiveEngineStatus> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("zhipuApiKey", apiKey == null ? "" : apiKey.trim());
                String raw = rawHttpPostWithLoopbackFallback("/api/online/live-engine-config", body.toString(), 6000, 15000);
                JSONObject root = new JSONObject(raw);
                JSONObject engines = root.optJSONObject("engines");
                if (engines == null) throw new Exception("返回数据缺少 engines");
                JSONObject zp = engines.optJSONObject("zhipu");
                JSONObject fl = engines.optJSONObject("google_flow");
                LiveEngineStatus st = new LiveEngineStatus(
                        true,
                        zp != null && zp.optBoolean("ready", false),
                        zp != null ? zp.optString("keyMasked", "") : "",
                        fl != null && fl.optBoolean("ready", false),
                        (fl != null && !fl.isNull("credits")) ? fl.optInt("credits", 0) : 0,
                        fl != null ? fl.optString("reason", "") : ""
                );
                mainHandler.post(() -> callback.onSuccess(st));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Requests PC AI gateway to synthesize a motion video and pack a lossless Motion Photo JPEG.
     * Returns null when PC recommends fallback ("local") or is unreachable.
     */
    public byte[] requestPcAiLiveConvertSync(
            byte[] imageBytes,
            String fileName,
            com.zwm.livecore.LiveEnginePreference pref,
            String mode,
            float durationSec
    ) {
        if (imageBytes == null || imageBytes.length == 0
                || pref == null || pref == com.zwm.livecore.LiveEnginePreference.LOCAL_ONLY) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("engine", pref.id);
            body.put("imageBase64", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP));
            body.put("name", fileName != null ? fileName : "photo.jpg");
            if (mode != null) body.put("mode", mode);
            body.put("duration", durationSec);
            String raw = rawHttpPostWithLoopbackFallback("/api/online/live-generate", body.toString(), 6000, 185000);
            JSONObject json = new JSONObject(raw);
            if (!json.optBoolean("ok", false)) {
                Log.i(TAG, "PC AI gateway fallback: " + json.optString("reason", "unavailable"));
                return null;
            }
            String motionB64 = json.optString("motionJpegBase64", "");
            if (motionB64.isEmpty()) return null;
            return android.util.Base64.decode(motionB64, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            Log.w(TAG, "requestPcAiLiveConvertSync failed: " + e.getMessage());
            return null;
        }
    }

    private static String rawHttpPostWithTimeout(URL url, String jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(data);
            out.flush();
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " " + conn.getResponseMessage());
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             ByteArrayOutputStream respOut = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                respOut.write(buf, 0, len);
            }
            return respOut.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }
}
