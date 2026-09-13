package com.zwm.gallery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/** A restrained iOS-like edge resistance for content and settings lists with horizontal swipe detection. */
public final class SpringScrollView extends ScrollView {
    public interface PullRefreshListener {
        void onPull(float progress, boolean ready);
        void onRefresh();
        void onReset();
    }

    public interface SwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private float lastY;
    private float downX;
    private float downY;
    private boolean isHorizontalSwipe;
    private boolean disallowIntercept;
    private boolean touchStartedInHorizontalChild;
    private int touchSlop;
    private int minSwipeDistance;
    private VelocityTracker velocityTracker;

    private PullRefreshListener pullRefreshListener;
    private SwipeListener swipeListener;
    private boolean refreshing;
    private final int refreshThresholdDp = 58;

    public SpringScrollView(Context context) { super(context); init(); }
    public SpringScrollView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
        ViewConfiguration vc = ViewConfiguration.get(getContext());
        touchSlop = vc.getScaledTouchSlop();
        minSwipeDistance = dp(42);
    }

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
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

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        disallowIntercept = disallow;
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    private static HorizontalScrollView findHorizontalScrollViewAt(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof HorizontalScrollView) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            int left = loc[0];
            int top = loc[1];
            int right = left + view.getWidth();
            int bottom = top + view.getHeight();
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return (HorizontalScrollView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                HorizontalScrollView child = findHorizontalScrollViewAt(group.getChildAt(i), rawX, rawY);
                if (child != null) return child;
            }
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (disallowIntercept || swipeListener == null) {
            return super.onInterceptTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            lastY = ev.getY();
            isHorizontalSwipe = false;
            disallowIntercept = false;
            touchStartedInHorizontalChild = (findHorizontalScrollViewAt(this, ev.getRawX(), ev.getRawY()) != null);
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
            else velocityTracker.clear();
            velocityTracker.addMovement(ev);
            return super.onInterceptTouchEvent(ev);
        }
        if (touchStartedInHorizontalChild) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchStartedInHorizontalChild = false;
            }
            return super.onInterceptTouchEvent(ev);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (velocityTracker != null) velocityTracker.addMovement(ev);
            if (isHorizontalSwipe) return true;
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            float absDx = Math.abs(dx);
            float absDy = Math.abs(dy);
            if (absDx > touchSlop && absDx > absDy * 1.3f) {
                isHorizontalSwipe = true;
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isHorizontalSwipe = false;
            touchStartedInHorizontalChild = false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker != null) velocityTracker.addMovement(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastY = event.getY();
            downX = event.getX();
            downY = event.getY();
        }
        if (isHorizontalSwipe) {
            if (action == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float xVelocity = 0;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    xVelocity = velocityTracker.getXVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                isHorizontalSwipe = false;
                boolean isDistance = Math.abs(dx) >= minSwipeDistance && Math.abs(dx) > Math.abs(dy);
                boolean isFling = Math.abs(xVelocity) >= 800;
                if ((isDistance || isFling) && swipeListener != null) {
                    if (dx < -dp(20) || xVelocity < -800) {
                        swipeListener.onSwipeLeft();
                    } else if (dx > dp(20) || xVelocity > 800) {
                        swipeListener.onSwipeRight();
                    }
                }
                return true;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                isHorizontalSwipe = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
            }
            return true;
        }
        if (refreshing) return true;
        if (action == MotionEvent.ACTION_MOVE) {
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
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            boolean shouldRefresh = action == MotionEvent.ACTION_UP
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

