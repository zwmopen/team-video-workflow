package com.zwm.livecore;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Toast;

/** Preferences are local to each app. Native online providers are deliberately not enabled yet. */
public final class LiveEngineSettings {
    private static final String PREFS = "live_engine_preferences";
    private static final String KEY = "preferred_engine_v1";
    private LiveEngineSettings() {}
    public static LiveEnginePreference get(Context c) {
        return LiveEnginePreference.fromId(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null));
    }
    public static String summary(Context c) {
        return get(c).title + " / 当前本地";
    }
    public static void show(Activity a, LiveQuickMenu menu, View anchor, Runnable changed) {
        LiveEnginePreference[] options = LiveEnginePreference.values();
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i=0;i<options.length;i++) {
            labels[i]=options[i].title;
            if(options[i]==get(a))checked=i;
        }
        menu.show(anchor, labels, checked, n -> {
            a.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,options[n].id).apply();
            if(changed!=null)changed.run();
            Toast.makeText(a,"偏好已保存；当前实际处理仍为本地，无自动上传",Toast.LENGTH_SHORT).show();
        });
    }
}
