package com.zwm.gallery;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Keeps app controls outside status bars, navigation bars (including 3-button bar) and display cutouts. */
final class ScreenInsets {
    private ScreenInsets() { }

    static View protect(View view) {
        if (view == null) return null;

        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();

        if (Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
                android.graphics.Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int navHeight = getNavigationBarHeight(target.getContext());
                int bottom = Math.max(safe.bottom, navHeight);
                target.setPadding(
                        initialLeft + safe.left,
                        initialTop + safe.top,
                        initialRight + safe.right,
                        initialBottom + bottom);
                return windowInsets;
            });
            view.requestApplyInsets();
        } else if (Build.VERSION.SDK_INT >= 26) {
            view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
                int navHeight = getNavigationBarHeight(target.getContext());
                int bottom = Math.max(windowInsets.getSystemWindowInsetBottom(), navHeight);
                target.setPadding(
                        initialLeft + windowInsets.getSystemWindowInsetLeft(),
                        initialTop + windowInsets.getSystemWindowInsetTop(),
                        initialRight + windowInsets.getSystemWindowInsetRight(),
                        initialBottom + bottom);
                return windowInsets;
            });
            view.requestApplyInsets();
        } else {
            int navHeight = getNavigationBarHeight(view.getContext());
            view.setPadding(initialLeft, initialTop, initialRight, initialBottom + navHeight);
        }
        return view;
    }

    static int getNavigationBarHeight(Context context) {
        try {
            Resources resources = context.getResources();
            int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
            if (resourceId > 0) {
                return resources.getDimensionPixelSize(resourceId);
            }
        } catch (Throwable ignored) { }
        return 0;
    }
}
