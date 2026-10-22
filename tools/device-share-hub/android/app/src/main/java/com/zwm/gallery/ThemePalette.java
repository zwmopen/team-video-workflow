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

    /**
     * DSH-183：系统弹窗（AlertDialog）主题资源。
     * AppTheme 写死 Theme.Material.Light，系统弹窗在深色模式下仍白底刺眼；
     * 统一改用本方法取主题，深色模式给深色对话框、浅色给浅色对话框。
     */
    public static int dialogThemeRes(Context context) {
        return isDarkMode(context) ? R.style.AppDialogTheme_Dark : R.style.AppDialogTheme_Light;
    }

    /**
     * DSH-183：PopupMenu（排序/视图菜单）要传「完整主题」给 ContextThemeWrapper。
     * 第一版用 5 参构造的 Widget style（Widget.Material.PopupMenu）实测不生效——
     * 那只是 widget 属性，不含弹窗窗口背景；必须传完整主题让 popupMenuStyle 解析出深色背景。
     */
    public static int popupThemeRes(Context context) {
        return isDarkMode(context) ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light;
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

    /** DSH-193：绿色强调文字（深色荧光绿 #4BD78C，浅色深墨绿 #356952） */
    public static int getAccentText(Context c) {
        return isDarkMode(c) ? Color.rgb(75, 215, 140) : Color.rgb(53, 105, 82);
    }

    /** DSH-193：危险/删除色（深色亮红 #FF6961，浅色砖红 #BC423C） */
    public static int getDangerColor(Context c) {
        return isDarkMode(c) ? Color.rgb(255, 105, 97) : Color.rgb(188, 66, 60);
    }

    /** DSH-193：危险操作浅底卡片（深色 #38201E，浅色 #FAE6E2） */
    public static int getDangerSoftBg(Context c) {
        return isDarkMode(c) ? Color.rgb(56, 32, 30) : Color.rgb(250, 230, 226);
    }

    /** DSH-193：危险操作浅底卡片描边（深色 #6E3A34，浅色 #D17E74） */
    public static int getDangerSoftStroke(Context c) {
        return isDarkMode(c) ? Color.rgb(110, 58, 52) : Color.rgb(209, 126, 116);
    }

    /** DSH-193：可点击链接蓝（深色 #0A84FF，浅色 #007AFF） */
    public static int getLinkColor(Context c) {
        return isDarkMode(c) ? Color.rgb(10, 132, 255) : Color.rgb(0, 122, 255);
    }

    /** DSH-193：蓝色信息徽章底（深色 #0B3650，浅色 #E0F2FE） */
    public static int getInfoBadgeBg(Context c) {
        return isDarkMode(c) ? Color.rgb(11, 54, 80) : Color.rgb(224, 242, 254);
    }

    /** DSH-193：蓝色信息徽章文字（深色 #56BEF0，浅色 #0284C7） */
    public static int getInfoBadgeText(Context c) {
        return isDarkMode(c) ? Color.rgb(86, 190, 240) : Color.rgb(2, 132, 199);
    }

    /** DSH-193：蓝色信息徽章描边（深色 #1E5A82，浅色 #BADCF0） */
    public static int getInfoBadgeStroke(Context c) {
        return isDarkMode(c) ? Color.rgb(30, 90, 130) : Color.rgb(186, 220, 240);
    }

    /** DSH-193：橙色提醒徽章底（深色 #3C2A1C，浅色 #FEF3EB） */
    public static int getWarnBadgeBg(Context c) {
        return isDarkMode(c) ? Color.rgb(60, 42, 28) : Color.rgb(254, 243, 235);
    }

    /** DSH-193：橙色提醒徽章文字（深色 #F0B478，浅色 #B45514） */
    public static int getWarnBadgeText(Context c) {
        return isDarkMode(c) ? Color.rgb(240, 180, 120) : Color.rgb(180, 85, 20);
    }

    /** DSH-193：品牌绿浅描边（深色 #235F41，浅色 #C8E6D7） */
    public static int getBrandSoftStroke(Context c) {
        return isDarkMode(c) ? Color.rgb(35, 95, 65) : Color.rgb(200, 230, 215);
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
