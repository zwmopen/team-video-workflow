package com.zwm.gallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShareActivity extends Activity {
    public static final String EXTRA_WORK_ID = "workId";
    private static final int REQUEST_SHARE = 501;

    private WorkLibrary library;
    private String workId;
    private boolean launched;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            workId = getIntent().getStringExtra(EXTRA_WORK_ID);
            if (workId == null || workId.isEmpty()) throw new IllegalStateException("没有指定作品");
            library = new WorkLibrary(new java.io.File(getFilesDir(), "work-library"));
            WorkLibrary.WorkEntry work = library.getActive(workId);
            if (work == null) throw new IllegalStateException("作品不存在或已进入回收站");
            DiagnosticLog.write(this, "share_activity_open", workId);
            setContentView(preparingView());
            prepareShare(work);
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_activity_failed", error.getMessage());
            Toast.makeText(this, "无法打开作品：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void prepareShare(WorkLibrary.WorkEntry work) {
        if (launched) return;
        launched = true;
        worker.execute(() -> {
            try {
                GalleryShareBridge.PreparedShare prepared = GalleryShareBridge.prepare(this, work);
                runOnUiThread(() -> {
                    try {
                        launchShare(work, prepared);
                    } catch (Exception error) {
                        DiagnosticLog.write(this, "share_launch_failed", error.getMessage());
                        Toast.makeText(this, "无法打开分享：" + error.getMessage(), Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            } catch (Exception error) {
                DiagnosticLog.write(this, "share_prepare_failed", error.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "图片准备失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void launchShare(WorkLibrary.WorkEntry work, GalleryShareBridge.PreparedShare prepared) {
        ArrayList<Uri> uris = prepared.uris;
        if (uris.isEmpty()) throw new IllegalStateException("作品中没有图片");

        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("作品文案", work.text));
        Toast.makeText(this, "文案复制成功", Toast.LENGTH_SHORT).show();

        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType("image/*");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!work.text.trim().isEmpty()) send.putExtra(Intent.EXTRA_TEXT, work.text);
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clipData = ClipData.newUri(getContentResolver(), "作品图片", uris.get(0));
        for (int index = 1; index < uris.size(); index++) clipData.addItem(new ClipData.Item(uris.get(index)));
        send.setClipData(clipData);

        // Several OEM clone-space resolvers fail to forward an implicit URI grant.
        // Grant every visible share handler as well; MediaStore remains the primary bridge on Android 10+.
        for (ResolveInfo target : getPackageManager().queryIntentActivities(send, 0)) {
            if (target.activityInfo == null || target.activityInfo.packageName == null) continue;
            for (Uri uri : uris) {
                try {
                    grantUriPermission(target.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException error) {
                    DiagnosticLog.write(this, "share_explicit_grant_skipped",
                            target.activityInfo.packageName + " " + error.getMessage());
                }
            }
        }

        DiagnosticLog.write(this, "share_sheet_launch", workId + " images=" + uris.size()
                + " strategy=" + prepared.strategy);
        if (!prepared.warning.isEmpty()) Toast.makeText(this, prepared.warning, Toast.LENGTH_LONG).show();
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_SHARE_OPENED)
                .putExtra(EXTRA_WORK_ID, workId));
        startActivityForResult(Intent.createChooser(send, "选择要发布的平台"), REQUEST_SHARE);
    }

    private LinearLayout preparingView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(246, 244, 240));
        ProgressBar progress = new ProgressBar(this);
        root.addView(progress);
        TextView label = new TextView(this);
        label.setText("正在准备图片…");
        label.setTextSize(16);
        label.setTextColor(Color.rgb(45, 45, 42));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 24, 0, 0);
        root.addView(label);
        return root;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SHARE) return;
        try {
            // Android only reports that the share target was opened, not whether publishing succeeded.
            library.markShared(workId, LocalDate.now());
            DiagnosticLog.write(this, "share_target_opened", workId + " result=" + resultCode);
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_mark_failed", error.getMessage());
        }
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_SHARE_FINISHED)
                .putExtra(EXTRA_WORK_ID, workId));
        sendBroadcast(new Intent(OnlineService.ACTION_TASK_READY).setPackage(getPackageName()));
        finish();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
