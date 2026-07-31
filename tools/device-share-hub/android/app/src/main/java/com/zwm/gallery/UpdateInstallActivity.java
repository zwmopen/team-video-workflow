package com.zwm.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public final class UpdateInstallActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String fileName = getSharedPreferences("device_share", MODE_PRIVATE)
                .getString(UpdateChecker.PREF_READY_FILE_NAME, "");
        if (!fileName.matches("[A-Za-z0-9._-]+\\.apk")) {
            showFailure("已验证的安装包不存在，请重新检查更新");
            return;
        }
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".files")
                .appendPath("updates")
                .appendPath(fileName)
                .build();
        try {
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            DiagnosticLog.write(this, "update_installer_opened", fileName);
            finish();
        } catch (Exception error) {
            DiagnosticLog.write(this, "update_installer_open_failed", error.getMessage());
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                finish();
            } catch (Exception ignored) {
                showFailure("无法打开系统安装器：" + safe(error.getMessage()));
            }
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
