package com.zwm.gallery;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Keeps app controls outside status bars, navigation bars and display cutouts. */
final class ScreenInsets {
    private ScreenInsets() { }

    static View protect(View view) {
        if (Build.VERSION.SDK_INT < 35) return view;

        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            target.setPadding(
                    initialLeft + safe.left,
                    initialTop + safe.top,
                    initialRight + safe.right,
                    initialBottom + safe.bottom);
            return windowInsets;
        });
        view.requestApplyInsets();
        return view;
    }
}
