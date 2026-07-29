package com.zwm.gallery;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScreenshotSendReceiver extends BroadcastReceiver {
    static final String ACTION_SEND = "com.zwm.gallery.SEND_SCREENSHOT";
    static final String EXTRA_URI = "uri";
    static final String EXTRA_PEER_ID = "peerId";
    static final int NOTIFICATION_ID = 3410;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SEND.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        String uriValue = intent.getStringExtra(EXTRA_URI);
        String peerId = intent.getStringExtra(EXTRA_PEER_ID);
        WORKER.execute(() -> {
            try {
                PeerDevice target = null;
                for (PeerDevice peer : OnlineService.peers()) {
                    if (peer.id.equals(peerId)) {
                        target = peer;
                        break;
                    }
                }
                if (target == null) throw new IllegalStateException("接收设备当前不在线");
                PeerDevice finalTarget = target;
                new TransferClient(app.getContentResolver(), app.getCacheDir())
                        .sendFiles(finalTarget, Collections.singletonList(Uri.parse(uriValue)),
                                "", false, (percent, text) -> notifyResult(
                                        app, "截图发送中 " + percent + "%", finalTarget.name, true));
                OperationLog.add(app, "截图发送完成", finalTarget.name);
                notifyResult(app, "截图已发送", finalTarget.name, false);
            } catch (Exception error) {
                String message = error.getMessage() == null ? "未知错误" : error.getMessage();
                OperationLog.add(app, "截图发送失败", message);
                notifyResult(app, "截图发送失败", message, false);
            } finally {
                pending.finish();
            }
        });
    }

    private static void notifyResult(Context context, String title, String text, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(
                context, 41, new Intent(context, ClipboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(
                context, OnlineService.SCREENSHOT_CHANNEL_ID)
                .setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_upload
                        : android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .build();
        context.getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification);
    }
}
