package com.zwm.gallery;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ConversionActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PeerDevice computer = null;
        for (PeerDevice peer : OnlineService.peers()) {
            if (peer.id.startsWith("windows-") || peer.model.contains("Windows")) {
                computer = peer;
                break;
            }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(232, 237, 243));
        if (computer == null) {
            TextView message = new TextView(this);
            message.setText("流量转化助手\n\n请先让手机和电脑连接同一 Wi‑Fi，并打开电脑端“团建工作台”。连接后这里会直接进入搜问题话术、转化 SOP、按需求找方案和用户旅程。");
            message.setTextSize(18);
            message.setGravity(Gravity.CENTER);
            message.setPadding(48, 48, 48, 48);
            root.addView(message, new LinearLayout.LayoutParams(-1, -1));
        } else {
            WebView web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.setWebViewClient(new WebViewClient());
            web.loadUrl("http://" + computer.ip + ":4327/#conversion");
            root.addView(web, new LinearLayout.LayoutParams(-1, -1));
        }
        setContentView(ScreenInsets.protect(root));
    }
}
