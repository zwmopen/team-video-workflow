package com.zwm.gallery;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TransferActivity extends Activity {
    private static final int PICK_FILES = 81;
    private static final int PICK_FOLDER = 82;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout peersContainer;
    private TextView status;
    private ProgressBar progress;
    private PeerDevice selected;
    private boolean receiverRegistered;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() { renderPeers(); refreshHandler.postDelayed(this, 2000); }
    };

    private final BroadcastReceiver peerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { renderPeers(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("传送");
        setContentView(buildUi());
        startService(new Intent(this, OnlineService.class).setAction(OnlineService.ACTION_START));
        renderPeers();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OnlineService.ACTION_PEERS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(filter);
        receiverRegistered = true;
        renderPeers();
        refreshHandler.postDelayed(refreshPeers, 2000);
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) { registerReceiver(peerReceiver, filter); }

    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(peerReceiver); receiverRegistered = false; }
        refreshHandler.removeCallbacks(refreshPeers);
        super.onStop();
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 244, 240));
        TextView title = text("传送到", 28, true);
        root.addView(title);
        TextView hint = text("先选设备，再选文件或文件夹。两台设备需在同一 Wi‑Fi。", 14, false);
        hint.setTextColor(Color.rgb(103, 100, 95));
        root.addView(hint, margins(0, dp(8), 0, dp(14)));
        peersContainer = new LinearLayout(this);
        peersContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(peersContainer);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button files = action("选择文件");
        files.setOnClickListener(v -> chooseFiles());
        Button folder = action("选择文件夹");
        folder.setOnClickListener(v -> chooseFolder());
        actions.addView(files, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        folderParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(folder, folderParams);
        root.addView(actions, margins(0, dp(18), 0, 0));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, margins(0, dp(18), 0, 0));
        status = text("正在发现附近设备…", 13, false);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(71, 104, 87));
        root.addView(status, margins(0, dp(12), 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private void renderPeers() {
        if (peersContainer == null) return;
        List<PeerDevice> peers = OnlineService.peers();
        peersContainer.removeAllViews();
        if (peers.isEmpty()) {
            TextView empty = text("暂未发现设备\n请在另一台设备上打开“相册”或电脑中控", 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            peersContainer.addView(empty);
            return;
        }
        if (selected == null) status.setText("已发现 " + peers.size() + " 台设备，请选择");
        for (PeerDevice peer : peers) {
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setPadding(dp(16), dp(10), dp(16), dp(10));
            item.setText(peer.name + "\n" + peer.model);
            item.setTextSize(15);
            boolean chosen = selected != null && selected.id.equals(peer.id);
            item.setTextColor(chosen ? Color.WHITE : Color.rgb(38, 39, 38));
            item.setBackground(round(chosen ? Color.rgb(38, 145, 94) : Color.WHITE, 16));
            item.setOnClickListener(v -> { selected = peer; status.setText("已选择“" + peer.name + "”"); renderPeers(); });
            peersContainer.addView(item, margins(0, 0, 0, dp(9)));
        }
    }

    private void chooseFiles() {
        if (!ensurePeer()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILES);
    }

    private void chooseFolder() {
        if (!ensurePeer()) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_FOLDER);
    }

    private boolean ensurePeer() {
        if (selected != null) return true;
        Toast.makeText(this, "请先选择接收设备", Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || selected == null) return;
        if (request == PICK_FOLDER && data.getData() != null) sendFolder(data.getData());
        if (request == PICK_FILES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) sendFiles(uris);
        }
    }

    private void sendFiles(List<Uri> uris) { runTransfer(client -> client.sendFiles(selected, uris, this::updateProgress)); }
    private void sendFolder(Uri tree) { runTransfer(client -> client.sendFolder(selected, tree, this::updateProgress)); }

    private void runTransfer(TransferWork work) {
        progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        PeerDevice target = selected;
        worker.execute(() -> {
            try {
                work.run(new TransferClient(getContentResolver(), getCacheDir()));
                DiagnosticLog.write(this, "outgoing_done", target.name + " " + target.ip);
            } catch (Exception error) {
                DiagnosticLog.write(this, "outgoing_failed", target.name + " " + error.getMessage());
                updateProgress(0, "传送失败：" + (error.getMessage() == null ? "请检查两台设备的 Wi‑Fi" : error.getMessage()));
            }
        });
    }

    private void updateProgress(int value, String message) {
        runOnUiThread(() -> { progress.setProgress(value); progress.setVisibility(View.VISIBLE); status.setText(message); });
    }

    private Button action(String title) {
        Button button = new Button(this);
        button.setText(title); button.setTextSize(16); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE); button.setAllCaps(false);
        button.setBackground(round(Color.rgb(38, 145, 94), 14));
        return button;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(l, t, r, b); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface TransferWork { void run(TransferClient client) throws Exception; }
}
