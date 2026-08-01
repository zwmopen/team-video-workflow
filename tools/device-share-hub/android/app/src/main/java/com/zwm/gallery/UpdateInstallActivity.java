package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

public final class UpdateInstallActivity extends Activity {
    private boolean waitingForSourcePermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String fileName = getSharedPreferences("device_share", MODE_PRIVATE)
                .getString(UpdateChecker.PREF_READY_FILE_NAME, "");
        if (!fileName.matches("[A-Za-z0-9._-]+\\.apk")) {
            showFailure("已验证的安装包不存在，请重新检查更新");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要允许安装更新")
                    .setMessage("Android 首次需要确认“允许来自此来源”。开启后返回，相册会继续打开系统安装界面；最终仍由你点击安装。")
                    .setNegativeButton("取消", (dialog, which) -> finish())
                    .setPositiveButton("去允许", (dialog, which) -> {
                        try {
                            waitingForSourcePermission = true;
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception error) {
                            waitingForSourcePermission = false;
                            showFailure("系统没有提供安装来源设置：" + safe(error.getMessage()));
                        }
                    })
                    .setOnCancelListener(dialog -> finish())
                    .show();
            return;
        }
        openSystemInstaller(fileName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!waitingForSourcePermission) return;
        waitingForSourcePermission = false;
        if (getPackageManager().canRequestPackageInstalls()) {
            String fileName = getSharedPreferences("device_share", MODE_PRIVATE)
                    .getString(UpdateChecker.PREF_READY_FILE_NAME, "");
            if (fileName.matches("[A-Za-z0-9._-]+\\.apk")) openSystemInstaller(fileName);
            else showFailure("已验证的安装包不存在，请重新检查更新");
        } else {
            showFailure("尚未允许相册安装更新。你可以稍后再次点击“安装”。");
        }
    }

    private void openSystemInstaller(String fileName) {
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".files")
                .appendPath("updates")
                .appendPath(fileName)
                .build();
        try {
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            DiagnosticLog.write(this, "update_installer_opened", fileName);
            finish();
        } catch (Exception error) {
            DiagnosticLog.write(this, "update_installer_open_failed", error.getMessage());
            showFailure("无法打开系统安装器：" + safe(error.getMessage()));
        }
    }

    private void showFailure(String text) {
        new AlertDialog.Builder(this)
                .setTitle("无法安装更新")
                .setMessage(text)
                .setPositiveButton("知道了", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "系统没有返回原因" : value.trim();
    }
}
