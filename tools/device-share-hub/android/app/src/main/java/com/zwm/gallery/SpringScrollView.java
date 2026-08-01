package com.zwm.gallery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/** A restrained iOS-like edge resistance for content and settings lists. */
public final class SpringScrollView extends ScrollView {
    public interface PullRefreshListener {
        void onPull(float progress, boolean ready);
        void onRefresh();
        void onReset();
    }

    private float lastY;
    private PullRefreshListener pullRefreshListener;
    private boolean refreshing;
    private final int refreshThresholdDp = 58;

    public SpringScrollView(Context context) { super(context); init(); }
    public SpringScrollView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
    }

    public void setOnPullRefresh(Runnable action) {
        if (action == null) {
            pullRefreshListener = null;
            return;
        }
        pullRefreshListener = new PullRefreshListener() {
            @Override public void onPull(float progress, boolean ready) { }
            @Override public void onRefresh() { action.run(); }
            @Override public void onReset() { }
        };
    }

    public void setPullRefreshListener(PullRefreshListener listener) {
        pullRefreshListener = listener;
    }

    public void finishRefresh() {
        refreshing = false;
        animate().translationY(0f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        if (pullRefreshListener != null) pullRefreshListener.onReset();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) lastY = event.getY();
        if (refreshing) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float currentY = event.getY();
            float delta = currentY - lastY;
            boolean atTop = getScrollY() <= 0;
            boolean atBottom = getChildCount() == 0
                    || getScrollY() + getHeight() >= getChildAt(0).getHeight();
            boolean shortContent = getChildCount() == 0 || getChildAt(0).getHeight() <= getHeight();
            if ((atTop && delta > 0) || (atBottom && delta < 0) || shortContent) {
                float limit = getHeight() * 0.12f;
                float next = Math.max(-limit, Math.min(limit, getTranslationY() + delta * 0.28f));
                setTranslationY(next);
                if (atTop && next > 0 && pullRefreshListener != null) {
                    float progress = Math.min(1f, next / dp(refreshThresholdDp));
                    pullRefreshListener.onPull(progress, progress >= 1f);
                }
            }
            lastY = currentY;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            boolean shouldRefresh = event.getActionMasked() == MotionEvent.ACTION_UP
                    && getTranslationY() >= dp(refreshThresholdDp)
                    && pullRefreshListener != null;
            if (shouldRefresh) {
                refreshing = true;
                animate().translationY(dp(52)).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
                pullRefreshListener.onRefresh();
            } else {
                animate().translationY(0f).setDuration(360)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
                if (pullRefreshListener != null) pullRefreshListener.onReset();
            }
        }
        return super.onTouchEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
