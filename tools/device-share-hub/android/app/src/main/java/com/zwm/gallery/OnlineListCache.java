package com.zwm.gallery;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 在线相册列表的**本地快照**（「秒开」用）。
 *
 * 存在的理由（2026-09-20 实测）：
 * 电脑端 {@code /api/online/works} 全量响应裸发 **2.75 MB**，瘦身 + gzip 后约 **420 KB**。
 * 冷启动后第一次点进在线相册，无论如何都要等一次网络往返 —— 这就是用户看到的
 * 「正在连接电脑在线相册…」。而**用户点进在线相册是必然动作**，
 * 所以把上一次成功的列表落盘：下次进页面先渲染快照、再后台刷新，
 * 体感从「等网络」变成「秒开」；电脑关机/换网时也还能看到上次的内容。
 *
 * 设计约束：
 * 1. **只做缓存，不做判定**。原样存服务端返回的 JSON 字符串，不解析、不改写字段，
 *    避免与 {@link OnlineWorkEntry} 的字段演进脱节（解析一律走
 *    {@link OnlineGalleryClient#parseWorks} 同一条路径）。
 * 2. **原子写**：先写 {@code .tmp} 再 rename，杜绝进程被杀时留下半截 JSON，
 *    下次读到一个损坏的快照比没有快照更糟。
 * 3. **有保质期**：超过 {@link #MAX_AGE_MS} 的快照不再使用，
 *    避免展示严重过期的数据（宁可显示「正在连接」，也不给错的内容）。
 */
final class OnlineListCache {
    private static final String TAG = "OnlineListCache";
    private static final String DIR_NAME = "online_cache";
    private static final String FILE_WORKS = "last_works.json";
    private static final String FILE_CATEGORIES = "last_categories.json";

    /** 快照保质期：7 天。超期直接丢弃，宁可慢也不要错。 */
    static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000L;

    private OnlineListCache() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static void saveWorks(Context c, String json) {
        write(c, new File(dir(c), FILE_WORKS), json);
    }

    static void saveCategories(Context c, String json) {
        write(c, new File(dir(c), FILE_CATEGORIES), json);
    }

    /**
     * @return 上次成功的作品列表 JSON；不存在 / 过期 / 读失败时返回 null。
     *         <p>JSON 内容是否可用由调用方用 {@link OnlineGalleryClient#parseWorks} 判定 ——
     *         本类刻意不做语义校验（见类注释约束 1）。
     */
    static String loadWorks(Context c) {
        return read(c, new File(dir(c), FILE_WORKS));
    }

    /**
     * @return 上次成功的分类 JSON；不存在 / 过期 / 读失败时返回 null。
     *         <p>解析失败同样由调用方判定，本类只负责「原样取回」。
     */
    static String loadCategories(Context c) {
        return read(c, new File(dir(c), FILE_CATEGORIES));
    }

    /** 快照落盘时间（0 表示没有快照） */
    static long snapshotAtMs(Context c) {
        File f = new File(dir(c), FILE_WORKS);
        return f.exists() ? f.lastModified() : 0L;
    }

    static void clear(Context c) {
        File d = dir(c);
        File[] kids = d.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (!f.delete()) Log.w(TAG, "快照删除失败: " + f.getName());
            }
        }
    }

    private static void write(Context c, File f, String s) {
        if (s == null || s.isEmpty()) return;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            // 【DSH-118】Debug 回传铁律：失败必须落盘，不能只 Log.w
            // （logcat 会被系统随时清空，「秒开为什么没生效」永远查不到现场）。
            String detail = f.getName() + " | " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "快照写入失败: " + e.getMessage());
            DiagnosticLog.write(c, "list_cache_write_failed", detail);
            return;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) { }
            }
        }
        // 原子替换：同目录 rename 在 Android 上是原子的
        if (!tmp.renameTo(f)) {
            // 少数机型/文件系统上 rename 会失败，退化为「先删再改名」
            if (f.exists() && !f.delete()) Log.w(TAG, "旧快照删除失败");
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "快照改名失败，本次不缓存");
                DiagnosticLog.write(c, "list_cache_rename_failed", f.getName());
                tmp.delete();
            }
        }
    }

    private static String read(Context c, File f) {
        if (!f.exists()) return null;
        long age = System.currentTimeMillis() - f.lastModified();
        if (age > MAX_AGE_MS) {
            Log.i(TAG, "快照已过期（" + (age / 3600_000L) + " 小时），忽略");
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String s = out.toString(StandardCharsets.UTF_8.name());
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            // 【DSH-118】同上：读失败也要留痕。之前只有 Log.w，真机上等于没有。
            String detail = f.getName() + " | " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "快照读取失败: " + e.getMessage());
            DiagnosticLog.write(c, "list_cache_read_failed", detail);
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }
}
