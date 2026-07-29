package com.zwm.gallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android system Share target that forwards selected content to an online trusted device. */
public final class ShareImportActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> uris = new ArrayList<>();
    private String sharedText = "";
    private LinearLayout peersContainer;
    private ProgressBar progress;
    private TextView status;
    private boolean receiverRegistered;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() {
            renderPeers();
            refreshHandler.postDelayed(this, 2000);
        }
    };
    private final BroadcastReceiver peerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            renderPeers();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        readSharedContent(getIntent());
        setTitle("发送到设备");
        setContentView(ScreenInsets.protect(buildUi()));
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
        renderPeers();
    }

    @Override protected void onResume() {
        super.onResume();
        renderPeers();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OnlineService.ACTION_PEERS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        receiverRegistered = true;
        refreshHandler.postDelayed(refreshPeers, 1000);
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(peerReceiver, filter);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(peerReceiver);
            receiverRegistered = false;
        }
        refreshHandler.removeCallbacks(refreshPeers);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void readSharedContent(Intent intent) {
        LinkedHashSet<Uri> values = new LinkedHashSet<>();
        if (intent != null) {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) values.addAll(streams);
            } else {
                Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) values.add(stream);
            }
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int index = 0; index < clip.getItemCount(); index++) {
                    Uri uri = clip.getItemAt(index).getUri();
                    if (uri != null) values.add(uri);
                }
            }
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            sharedText = text == null ? "" : text.toString().trim();
        }
        uris.addAll(values);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 244, 240));
        root.addView(text("发送到在线设备", 27, true));
        String summary = uris.size() + " 个文件";
        if (!sharedText.isEmpty()) summary += "＋文字";
        TextView hint = text(summary
                + "\n普通文件按作品文件夹存放；图片和文字一起分享时，到达后进入分享准备。",
                13, false);
        hint.setTextColor(Color.rgb(96, 94, 90));
        root.addView(hint, margins(0, dp(7), 0, dp(14)));
        peersContainer = new LinearLayout(this);
        peersContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(peersContainer);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, margins(0, dp(16), 0, 0));
        status = text("正在发现在线设备…", 13, false);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(54, 105, 72));
        root.addView(status, margins(0, dp(10), 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void renderPeers() {
        if (peersContainer == null) return;
        List<PeerDevice> peers = OnlineService.peers();
        peersContainer.removeAllViews();
        if (peers.isEmpty()) {
            TextView empty = text("暂未发现设备\n请让另一台设备打开相册并连接同一 Wi‑Fi", 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            empty.setBackground(round(Color.WHITE, 15));
            peersContainer.addView(empty);
            return;
        }
        status.setText("选择一台设备即可发送");
        for (PeerDevice peer : peers) {
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setText(peer.name + "\n" + peer.model + " · 在线");
            item.setTextSize(15);
            item.setPadding(dp(16), dp(8), dp(16), dp(8));
            item.setBackground(round(Color.WHITE, 15));
            item.setOnClickListener(v -> send(peer));
            peersContainer.addView(item, margins(0, 0, 0, dp(9)));
        }
    }

    private void send(PeerDevice peer) {
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("准备发送到“" + peer.name + "”…");
        worker.execute(() -> {
            try {
                TransferClient client = new TransferClient(getContentResolver(), getCacheDir());
                if (uris.isEmpty()) {
                    File textFile = new File(getCacheDir(), "分享文字.txt");
                    try (FileOutputStream output = new FileOutputStream(textFile, false)) {
                        output.write(sharedText.getBytes(StandardCharsets.UTF_8));
                    }
                    client.sendLocalFiles(peer, java.util.Collections.singletonList(textFile),
                            sharedText, false, this::updateProgress);
                } else {
                    client.sendFiles(peer, uris, sharedText, shouldAutoShare(),
                            this::updateProgress);
                }
                OperationLog.add(this, "系统分享发送完成", peer.name);
                runOnUiThread(() -> {
                    status.setText("已发送到“" + peer.name + "”");
                    progress.setProgress(100);
                    progress.postDelayed(this::finish, 900);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "请检查两台设备的 Wi‑Fi" : error.getMessage();
                OperationLog.add(this, "系统分享发送失败", peer.name + "：" + message);
                runOnUiThread(() -> status.setText("发送失败：" + message));
            }
        });
    }

    private boolean shouldAutoShare() {
        if (sharedText.isEmpty() || uris.isEmpty()) return false;
        for (Uri uri : uris) {
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("image/")) return false;
        }
        return true;
    }

    private void updateProgress(int value, String message) {
        runOnUiThread(() -> {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(value);
            status.setText(message);
        });
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 35, 33));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
