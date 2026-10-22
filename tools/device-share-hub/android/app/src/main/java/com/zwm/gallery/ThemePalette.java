package com.zwm.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/**
 * 全局主题调色盘：支持跟随系统、手动浅色、手动深色三种模式，
 * 纯净暗黑背景 (#121212)、优雅卡片 (#1E1E1E)、浅灰文字 (#F5F5F7) 与高质感墨绿 (#0F8758)。
 */
public final class ThemePalette {
    private static final String PREFS_NAME = "device_share";
    public static final String PREF_THEME_MODE = "pref_theme_mode";

    public static final int THEME_MODE_SYSTEM = 0; // 跟随系统
    public static final int THEME_MODE_LIGHT = 1;  // 浅色模式
    public static final int THEME_MODE_DARK = 2;   // 深色模式

    private ThemePalette() {}

    public static int getThemeMode(Context context) {
        if (context == null) return THEME_MODE_SYSTEM;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(PREF_THEME_MODE, THEME_MODE_SYSTEM);
    }

    public static void setThemeMode(Context context, int mode) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(PREF_THEME_MODE, mode).apply();
    }

    public static boolean isDarkMode(Context context) {
        if (context == null) return false;
        int mode = getThemeMode(context);
        if (mode == THEME_MODE_LIGHT) return false;
        if (mode == THEME_MODE_DARK) return true;
        // MODE_SYSTEM: 跟随系统
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 主页面背景色：深色 #121212，浅色 #F8F9F8 */
    public static int getBgPrimary(Context c) {
        return isDarkMode(c) ? Color.rgb(18, 18, 18) : Color.rgb(248, 249, 248);
    }

    /** 次级背景色：深色 #1C1C1E，浅色 #FFFFFF */
    public static int getBgSecondary(Context c) {
        return isDarkMode(c) ? Color.rgb(28, 28, 30) : Color.WHITE;
    }

    /** 作品/设置卡片背景色：深色 #1E1E1E，浅色 #FFFFFF */
    public static int getCardBg(Context c) {
        return isDarkMode(c) ? Color.rgb(30, 30, 30) : Color.WHITE;
    }

    /** 卡片微描边边框颜色：深色 #2C2C2E，浅色 #E0E4E2 */
    public static int getCardStroke(Context c) {
        return isDarkMode(c) ? Color.rgb(44, 44, 46) : Color.rgb(224, 228, 226);
    }

    /** 已使用作品的置灰卡片背景色 */
    public static int getCardUsedBg(Context c) {
        return isDarkMode(c) ? Color.rgb(24, 24, 24) : Color.rgb(243, 245, 243);
    }

    /** 已使用作品的卡片描边色 */
    public static int getCardUsedStroke(Context c) {
        return isDarkMode(c) ? Color.rgb(36, 36, 38) : Color.rgb(218, 224, 220);
    }

    /** 一级文本颜色（标题、正文）：深色 #F5F5F7，浅色 #202221 */
    public static int getTextPrimary(Context c) {
        return isDarkMode(c) ? Color.rgb(245, 245, 247) : Color.rgb(32, 34, 33);
    }

    /** 二级文本颜色（副标题、说明）：深色 #8E8E93，浅色 #646967 */
    public static int getTextSecondary(Context c) {
        return isDarkMode(c) ? Color.rgb(142, 142, 147) : Color.rgb(100, 105, 103);
    }

    /** 弱化文本颜色（时间、提示）：深色 #636366，浅色 #9AA09D */
    public static int getTextMuted(Context c) {
        return isDarkMode(c) ? Color.rgb(99, 99, 102) : Color.rgb(154, 160, 157);
    }

    /** 药丸按钮未选背景色：深色 #2C2C2E，浅色 #E8ECE9 */
    public static int getPillBg(Context c) {
        return isDarkMode(c) ? Color.rgb(44, 44, 46) : Color.rgb(232, 236, 233);
    }

    /** 药丸按钮未选文字颜色：深色 #AEAEB2，浅色 #505553 */
    public static int getPillText(Context c) {
        return isDarkMode(c) ? Color.rgb(174, 174, 178) : Color.rgb(80, 85, 83);
    }

    /** 药丸按钮选中背景色：高质感墨绿 #0F8758 */
    public static int getPillSelectedBg() {
        return Color.rgb(15, 135, 88);
    }

    /** 药丸按钮选中文字颜色：纯白 */
    public static int getPillSelectedText() {
        return Color.WHITE;
    }

    /** 分隔线颜色：深色 #2C2C2E，浅色 #E6E9E7 */
    public static int getDividerColor(Context c) {
        return isDarkMode(c) ? Color.rgb(44, 44, 46) : Color.rgb(230, 233, 231);
    }

    /** 品牌主强调色：#0F9B63 */
    public static int getBrandPrimary() {
        return Color.rgb(15, 155, 99);
    }

    /** 目的地等浅绿徽章背景色：深色暗墨绿 #163826，浅色浅嫩绿 #E4F4EB */
    public static int getBrandLightBg(Context c) {
        return isDarkMode(c) ? Color.rgb(22, 56, 38) : Color.rgb(228, 244, 235);
    }

    /** 目的地等浅绿徽章文字颜色：深色荧光亮绿 #4BD78C，浅色深墨绿 #197850 */
    public static int getBrandLightText(Context c) {
        return isDarkMode(c) ? Color.rgb(75, 215, 140) : Color.rgb(25, 120, 80);
    }

    /** 半屏面板/弹窗遮罩背景色：45% 黑色透明遮罩 */
    public static int getBackdropColor() {
        return Color.argb(115, 0, 0, 0);
    }

    /** 创建圆角 Drawable */
    public static GradientDrawable round(int color, int radiusDp, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** 创建带描边圆角 Drawable */
    public static GradientDrawable roundWithStroke(int bgColor, int radiusDp, int strokeColor, int strokeWidthDp, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(strokeWidthDp * density)), strokeColor);
        return d;
    }

    /** 创建仅顶部左右两角圆角的 Drawable（用于底部半屏 Sheet） */
    public static GradientDrawable roundTop(int color, int radiusDp, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = radiusDp * density;
        // top-left, top-right, bottom-right, bottom-left
        d.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return d;
    }

    /** 动态应用状态栏与导航栏沉浸式颜色及亮/暗图标 */
    public static void applySystemBarAppearance(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        boolean dark = isDarkMode(activity);
        int bg = getBgPrimary(activity);
        Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 2026-10-22 修复启动崩溃：onCreate 里 setContentView 之前调用本方法时，
            // 部分 ROM（小米 MIUI / Android 15+）的 PhoneWindow.getInsetsController
            // 直接解引用尚未创建的 mDecor 导致 NPE（0.8.114 引入，真机启动即崩）。
            // 先调 getDecorView() 触发 installDecor，保证 DecorView 存在；
            // 视觉效果由 MainActivity.onResume 里的再次调用兜底。
            window.getDecorView();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (dark) {
                    controller.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                } else {
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }
}
