package com.zwm.gallery;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    public static final String EXTRA_IMAGE_NAMES = "imageNames";
    private static final int REQUEST_SHARE = 501;
    private static final String ACTION_TARGET_CHOSEN = "com.zwm.gallery.SHARE_TARGET_CHOSEN";
    private static final long QUICK_TARGET_RETURN_MS = 6_000L;
    // Clone-space routers on low-memory VIVO devices can need well over ten seconds
    // to warm the real editor after their lightweight routing activity returns.
    private static final long DEFERRED_TARGET_WAIT_MS = 20_000L;

    private WorkLibrary library;
    private String workId;
    private boolean launched;
    private boolean targetChosen;
    private long targetChosenAtMs;
    private boolean waitingForDeferredTarget;
    private boolean shareFlowFinished;
    private boolean chosenReceiverRegistered;
    private int pendingResultCode;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver chosenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            targetChosen = true;
            targetChosenAtMs = SystemClock.elapsedRealtime();
            android.content.ComponentName component = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
            DiagnosticLog.write(ShareActivity.this, "share_target_chosen",
                    component == null ? "unknown" : component.getPackageName());
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Flag overload only exists on API 33+; older releases use the legacy API.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            workId = getIntent().getStringExtra(EXTRA_WORK_ID);
            if (workId == null || workId.isEmpty()) throw new IllegalStateException("没有指定作品");
            library = new WorkLibrary(new java.io.File(getFilesDir(), "work-library"));
            IntentFilter chosenFilter = new IntentFilter(ACTION_TARGET_CHOSEN);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(chosenReceiver, chosenFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(chosenReceiver, chosenFilter);
            }
            chosenReceiverRegistered = true;
            WorkLibrary.WorkEntry work = library.getActive(workId);
            if (work == null) throw new IllegalStateException("作品不存在或已进入回收站");
            DiagnosticLog.write(this, "share_activity_open", workId);
            setContentView(ScreenInsets.protect(preparingView()));
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
                ArrayList<String> requested = getIntent().getStringArrayListExtra(EXTRA_IMAGE_NAMES);
                GalleryShareBridge.PreparedShare prepared = requested == null || requested.isEmpty()
                        ? GalleryShareBridge.prepare(this, work)
                        : GalleryShareBridge.prepare(this, work, requested);
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
        Intent callback = new Intent(ACTION_TARGET_CHOSEN).setPackage(getPackageName());
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) pendingFlags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent chosenCallback = PendingIntent.getBroadcast(this, REQUEST_SHARE, callback, pendingFlags);
        Intent chooser = Intent.createChooser(send, "选择要发布的平台", chosenCallback.getIntentSender());
        // VIVO and some other OEM clone-space forwarders inspect the outer chooser intent.
        // Carry the same read grant and ClipData there instead of relying on inner-intent forwarding.
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(clipData);
        startActivityForResult(chooser, REQUEST_SHARE);
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
        if (!targetChosen) {
            DiagnosticLog.write(this, "share_chooser_cancelled", workId);
            finishShareFlow(false, resultCode, "chooser_cancelled");
            return;
        }
        long targetOpenMs = SystemClock.elapsedRealtime() - targetChosenAtMs;
        if (targetOpenMs < QUICK_TARGET_RETURN_MS) {
            waitingForDeferredTarget = true;
            pendingResultCode = resultCode;
            setContentView(ScreenInsets.protect(waitingView()));
            DiagnosticLog.write(this, "share_target_returned_early", workId + " ms=" + targetOpenMs);
            mainHandler.postDelayed(this::finishDeferredWait, DEFERRED_TARGET_WAIT_MS);
            return;
        }
        finishShareFlow(true, resultCode, "target_returned");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!waitingForDeferredTarget || shareFlowFinished) return;
        // Some OEM clone apps return from their lightweight router, then open the real editor shortly after.
        // A second pause during this window is the reliable signal that the editor actually took over.
        mainHandler.postDelayed(() -> {
            if (waitingForDeferredTarget && !shareFlowFinished && !hasWindowFocus()) {
                finishShareFlow(true, pendingResultCode, "deferred_target_opened");
            }
        }, 250L);
    }

    private void finishDeferredWait() {
        if (!waitingForDeferredTarget || shareFlowFinished) return;
        waitingForDeferredTarget = false;
        DiagnosticLog.write(this, "share_target_open_failed", workId + " clone_cold_start");
        Toast.makeText(this, "分身首次启动较慢，本次未计入分享次数；请再点一次", Toast.LENGTH_LONG).show();
        finishShareFlow(false, pendingResultCode, "target_open_failed");
    }

    private void finishShareFlow(boolean markShared, int resultCode, String outcome) {
        if (shareFlowFinished) return;
        shareFlowFinished = true;
        waitingForDeferredTarget = false;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            if (markShared) {
                // Android reports that the target was opened, not whether publishing succeeded.
                library.markShared(workId, LocalDate.now());
                DiagnosticLog.write(this, "share_target_opened", workId + " result=" + resultCode
                        + " outcome=" + outcome);
            }
        } catch (Exception error) {
            DiagnosticLog.write(this, "share_mark_failed", error.getMessage());
        }
        startService(new Intent(this, OnlineService.class)
                .setAction(OnlineService.ACTION_SHARE_FINISHED)
                .putExtra(EXTRA_WORK_ID, workId));
        sendBroadcast(new Intent(OnlineService.ACTION_TASK_READY).setPackage(getPackageName()));
        finish();
    }

    private LinearLayout waitingView() {
        LinearLayout root = preparingView();
        TextView label = (TextView) root.getChildAt(1);
        label.setText("正在等待分身打开…");
        return root;
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (chosenReceiverRegistered) {
            try {
                unregisterReceiver(chosenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            chosenReceiverRegistered = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
