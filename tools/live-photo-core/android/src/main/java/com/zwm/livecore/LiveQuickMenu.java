package com.zwm.livecore;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Anchored, theme-aware quick menu with FlClash-inspired sliding highlight platter,
 * continuous hold-slide-release selection, and API 33+ predictive back support.
 */
public final class LiveQuickMenu {
    public interface Selection { void select(int index); }
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout overlay, panel, listHolder;
    private ScrollView scroll;
    private View currentPlatter, hoverPlatter;
    private TextView[] rows;
    private Selection selection;
    private View anchor;
    private Object backCallback;
    private float downX, downY;
    private boolean tracking, held, moved;
    private int highlighted = -1, current = -1;
    private final Runnable hold = () -> {
        if (!tracking || anchor == null || !anchor.isAttachedToWindow()) return;
        held = true;
        anchor.performLongClick();
    };

    public LiveQuickMenu(Activity activity) { this.activity = activity; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private boolean dark() {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    private boolean animationsAllowed() {
        return Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private void releaseParent(View v) {
        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
    }

    /** The anchor keeps its ordinary click and accessibility long-click actions. */
    public void attach(View target, Runnable open) {
        anchor = target;
        target.setOnLongClickListener(v -> {
            if (!isOpen()) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                open.run();
            }
            return true;
        });
        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    close();
                    downX = event.getRawX(); downY = event.getRawY();
                    tracking = true; held = false; moved = false;
                    v.setPressed(true);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    handler.postDelayed(hold, 350);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking) return true;
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!held && Math.hypot(dx, dy) > ViewConfiguration.get(activity).getScaledTouchSlop()) {
                        handler.removeCallbacks(hold);
                        tracking = false; v.setPressed(false); releaseParent(v);
                    } else if (held && isOpen()) {
                        if (Math.hypot(dx, dy) > dp(8)) moved = true;
                        if (moved) updateHighlight(event.getRawX(), event.getRawY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(hold);
                    v.setPressed(false); releaseParent(v);
                    if (tracking) {
                        if (held && isOpen() && moved) {
                            updateHighlight(event.getRawX(), event.getRawY());
                            if (highlighted >= 0) commit(highlighted); else close();
                        } else if (!held) v.performClick();
                        // Stationary long-press keeps menu open for regular taps.
                    }
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(hold);
                    tracking = false; v.setPressed(false); releaseParent(v);
                    close(); return true;
                default: return true;
            }
        });
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(hold); tracking = false; close();
            }
        });
    }

    public boolean isOpen() { return overlay != null; }

    public boolean close() {
        unregisterBackCallback();
        if (overlay == null) return false;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay = null; panel = null; listHolder = null;
        currentPlatter = null; hoverPlatter = null;
        rows = null; scroll = null; selection = null; highlighted = -1;
        return true;
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return;
        try {
            android.window.OnBackInvokedCallback cb = this::close;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            backCallback = cb;
        } catch (Throwable ignored) {}
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backCallback == null) return;
        try {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
        } catch (Throwable ignored) {}
        backCallback = null;
    }

    public void show(View target, String[] labels, int selected, Selection onSelect) {
        close();
        if (target == null || !target.isAttachedToWindow() || labels.length == 0 || activity.isFinishing()) return;
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (root.getWidth() == 0 || root.getHeight() == 0) return;

        int[] a = new int[2], r = new int[2];
        target.getLocationOnScreen(a); root.getLocationOnScreen(r);
        Rect visible = new Rect(); root.getWindowVisibleDisplayFrame(visible);
        int minX = Math.max(dp(8), visible.left - r[0] + dp(8));
        int minY = Math.max(dp(8), visible.top - r[1] + dp(8));
        int maxX = Math.min(root.getWidth() - dp(8), visible.right - r[0] - dp(8));
        int maxY = Math.min(root.getHeight() - dp(8), visible.bottom - r[1] - dp(8));
        int width = Math.min(dp(224), maxX - minX);
        if (width < dp(80)) return;

        current = selected; selection = onSelect;
        boolean night = dark();
        overlay = new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close());
        overlay.setBackgroundColor(0x0A000000);

        panel = new FrameLayout(activity);
        panel.setClickable(true);
        panel.setBackground(shape(night ? 0xF2252830 : 0xF7FAFBFD, 24, night ? 0x42FFFFFF : 0x26000000));
        panel.setElevation(dp(12));
        panel.setClipToOutline(true);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);

        listHolder = new FrameLayout(activity);
        int pad = dp(6);
        int rowW = width - pad * 2;
        currentPlatter = new View(activity);
        currentPlatter.setBackground(shape(night ? 0xFF383C47 : 0xFFEBEEF4, 14, 0));
        currentPlatter.setVisibility(View.GONE);
        listHolder.addView(currentPlatter, new FrameLayout.LayoutParams(rowW, dp(38)));

        hoverPlatter = new View(activity);
        hoverPlatter.setBackground(shape(night ? 0xFF4D5870 : 0xFFD9E6F7, 14, night ? 0x33FFFFFF : 0x223B72C8));
        hoverPlatter.setVisibility(View.GONE);
        hoverPlatter.setAlpha(0f);
        listHolder.addView(hoverPlatter, new FrameLayout.LayoutParams(rowW, dp(38)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);
        rows = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int n = i;
            TextView row = new TextView(activity);
            row.setText(labels[i] + (i == selected ? "  ✓" : ""));
            row.setTextSize(13.5f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(38));
            row.setPadding(dp(12), dp(6), dp(10), dp(6));
            row.setSingleLine(false);
            row.setMaxLines(2);
            row.setTextColor(night ? Color.WHITE : 0xFF1F2329);
            row.setContentDescription(labels[i] + (i == selected ? "，已选中" : ""));
            row.setOnClickListener(v -> commit(n));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            rows[i] = row;
        }
        listHolder.addView(list, new FrameLayout.LayoutParams(-1, -2));
        scroll.addView(listHolder, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        list.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int desiredHeight = Math.max(dp(38) + pad * 2, list.getMeasuredHeight());
        int below = a[1] - r[1] + target.getHeight() + dp(6);
        int above = a[1] - r[1] - dp(6);
        boolean openBelow = (maxY - below) >= Math.min(desiredHeight, above - minY);
        int available = openBelow ? (maxY - below) : (above - minY);
        if (available < dp(84)) { close(); return; }
        int height = Math.min(desiredHeight, Math.min(available, dp(276)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = Math.max(minX, Math.min(maxX - width, a[0] - r[0] + target.getWidth() - width));
        lp.topMargin = openBelow ? below : above - height;
        overlay.addView(panel, lp);
        root.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        registerBackCallback();

        list.post(() -> {
            positionCurrentPlatter();
            if (scroll != null && current >= 0 && current < rows.length && !tracking) {
                TextView activeRow = rows[current];
                if (activeRow.getBottom() > height - dp(8)) {
                    scroll.scrollTo(0, Math.max(0, activeRow.getTop() - dp(36)));
                }
            }
        });

        panel.setPivotX(width - dp(20));
        panel.setPivotY(openBelow ? 0 : height);
        if (animationsAllowed()) {
            panel.setAlpha(0f); panel.setScaleX(0.94f); panel.setScaleY(0.94f);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    private void positionCurrentPlatter() {
        if (currentPlatter == null || rows == null || current < 0 || current >= rows.length) return;
        TextView row = rows[current];
        if (row.getHeight() <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) currentPlatter.getLayoutParams();
        lp.height = row.getHeight();
        currentPlatter.setLayoutParams(lp);
        currentPlatter.setTranslationX(row.getLeft());
        currentPlatter.setTranslationY(row.getTop());
        currentPlatter.setVisibility(View.VISIBLE);
    }

    private void paint(int hit) {
        if (rows == null || hoverPlatter == null) return;
        if (hit < 0 || hit >= rows.length) {
            hoverPlatter.animate().cancel();
            if (animationsAllowed() && hoverPlatter.getVisibility() == View.VISIBLE) {
                hoverPlatter.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                    if (hoverPlatter != null && highlighted < 0) hoverPlatter.setVisibility(View.GONE);
                }).start();
            } else {
                hoverPlatter.setVisibility(View.GONE);
                hoverPlatter.setAlpha(0f);
            }
            return;
        }
        TextView targetRow = rows[hit];
        int h = Math.max(dp(44), targetRow.getHeight());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hoverPlatter.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            hoverPlatter.setLayoutParams(lp);
        }
        hoverPlatter.setTranslationX(targetRow.getLeft());
        float destY = targetRow.getTop();
        boolean wasVisible = hoverPlatter.getVisibility() == View.VISIBLE && hoverPlatter.getAlpha() > 0.05f;
        hoverPlatter.animate().cancel();
        if (!wasVisible) {
            hoverPlatter.setTranslationY(destY);
            hoverPlatter.setVisibility(View.VISIBLE);
            if (animationsAllowed()) {
                hoverPlatter.setAlpha(0f);
                hoverPlatter.animate().alpha(1f).setDuration(120).start();
            } else {
                hoverPlatter.setAlpha(1f);
            }
        } else if (animationsAllowed()) {
            hoverPlatter.animate().translationY(destY).alpha(1f).setDuration(150)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        } else {
            hoverPlatter.setTranslationY(destY);
            hoverPlatter.setAlpha(1f);
        }
    }

    private void updateHighlight(float x, float y) {
        if (rows == null || panel == null) return;
        int[] loc = new int[2]; panel.getLocationOnScreen(loc);
        int hit = -1;
        if (x >= loc[0] && x < loc[0] + panel.getWidth() && y >= loc[1] && y < loc[1] + panel.getHeight()) {
            if (scroll != null && listHolder != null && panel.getHeight() < listHolder.getHeight()) {
                if (y > loc[1] + panel.getHeight() - dp(26)) scroll.scrollBy(0, dp(12));
                else if (y < loc[1] + dp(26)) scroll.scrollBy(0, -dp(12));
            }
            for (int i = 0; i < rows.length; i++) {
                int[] pos = new int[2]; rows[i].getLocationOnScreen(pos);
                if (y >= pos[1] && y < pos[1] + rows[i].getHeight()) { hit = i; break; }
            }
        }
        if (hit != highlighted) {
            highlighted = hit;
            paint(hit);
            if (hit >= 0 && anchor != null) anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void commit(int n) {
        Selection callback = selection;
        close();
        if (callback != null) callback.select(n);
    }
}
