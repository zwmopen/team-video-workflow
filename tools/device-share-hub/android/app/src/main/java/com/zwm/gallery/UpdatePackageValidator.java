package com.zwm.gallery;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;

final class UpdatePackageValidator {
    private UpdatePackageValidator() {}

    static String archiveVersionName(Context context, File apk) throws Exception {
        PackageInfo candidate = archiveInfo(context, apk);
        if (candidate.versionName == null || candidate.versionName.trim().isEmpty())
            throw new IllegalStateException("安装包没有版本信息");
        return candidate.versionName.trim();
    }

    /**
     * 同时申请 GET_SIGNING_CERTIFICATES 与（已废弃但仍有效的）GET_SIGNATURES。
     *
     * 必须两个一起要：一旦只请求 GET_SIGNING_CERTIFICATES，PackageInfo.signatures
     * 就恒为 null；而部分机型（华为 EMUI / Android 10）用 getPackageArchiveInfo 解析
     * 归档 APK 时 signingInfo 也可能为 null —— 两条取样路径同时为空，就会把签名完好的
     * 安装包误判成「安装包没有签名」，导致这些老机型永远无法自动更新。
     */
    @SuppressWarnings("deprecation")
    private static int signatureFlags() {
        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        return flags;
    }

    static void validate(Context context, File apk, String expectedVersion) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() < 64 * 1024) throw new IllegalStateException("安装包不完整");
        PackageManager manager = context.getPackageManager();
        PackageInfo candidate = archiveInfo(context, apk);
        if (!context.getPackageName().equals(candidate.packageName)) throw new IllegalStateException("安装包名称与当前应用不一致");
        if (expectedVersion != null && !expectedVersion.trim().isEmpty() && !expectedVersion.trim().equals(candidate.versionName)) throw new IllegalStateException("安装包版本与更新信息不一致");
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), signatureFlags());
        if (versionCode(candidate) <= versionCode(installed)) throw new IllegalStateException("安装包版本没有高于当前版本");
        if (!Arrays.equals(signingDigest(candidate), signingDigest(installed))) throw new IllegalStateException("安装包签名与当前应用不一致");
    }

    private static PackageInfo archiveInfo(Context context, File apk) {
        if (apk == null || !apk.isFile()) throw new IllegalStateException("安装包不存在");
        PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags());
        if (candidate == null) throw new IllegalStateException("系统无法解析下载的 APK");
        return candidate;
    }

    private static byte[] signingDigest(PackageInfo info) throws Exception {
        Signature[] signatures = null;

        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }

        // 兜底：signingInfo 不可用时退回旧版 signatures 字段
        if ((signatures == null || signatures.length == 0)
                && info.signatures != null && info.signatures.length > 0) {
            signatures = info.signatures;
        }

        if (signatures == null || signatures.length == 0) throw new IllegalStateException("安装包没有签名");
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
    }
    @SuppressWarnings("deprecation") private static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
}
