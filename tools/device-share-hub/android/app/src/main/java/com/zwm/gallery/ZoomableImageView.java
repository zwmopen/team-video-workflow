package com.zwm.gallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * 丝滑全屏手势缩放图片组件（完全对齐 iOS UIScrollView 缩放体验）：
 * 1. 双指捏合平滑缩放（1.0x ~ 3.5x），以手势焦点为中心；
 * 2. 双击平滑动画放大至 2.5x，再次双击平滑还原至 1.0x；
 * 3. 放大状态下自由平移拖拽，具备严密屏幕边界吸附约束；
 * 4. 1.0x 常规状态下左右水平滑动手势切图（Swipe Left / Swipe Right）。
 */
public class ZoomableImageView extends ImageView {

    public interface OnSwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 3.5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OnSwipeListener swipeListener;
    private Runnable singleTapListener;

    private float currentScale = 1.0f;
    private boolean isAnimating = false;

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                float prevScale = currentScale;
                currentScale *= scaleFactor;

                if (currentScale < MIN_SCALE) {
                    currentScale = MIN_SCALE;
                    scaleFactor = MIN_SCALE / prevScale;
                } else if (currentScale > MAX_SCALE) {
                    currentScale = MAX_SCALE;
                    scaleFactor = MAX_SCALE / prevScale;
                }

                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                checkMatrixBounds();
                setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (singleTapListener != null) {
                    singleTapListener.run();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isAnimating) return true;
                float targetScale = currentScale > 1.1f ? MIN_SCALE : DOUBLE_TAP_SCALE;
                animateScale(currentScale, targetScale, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentScale > 1.05f) {
                    matrix.postTranslate(-distanceX, -distanceY);
                    checkMatrixBounds();
                    setImageMatrix(matrix);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (currentScale <= 1.05f && e1 != null && e2 != null) {
                    float diffX = e2.getX() - e1.getX();
                    float diffY = e2.getY() - e1.getY();
                    float density = getResources().getDisplayMetrics().density;
                    float threshold = 40 * density;
                    float velocityThreshold = 80 * density;

                    if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > threshold && Math.abs(velocityX) > velocityThreshold) {
                        if (diffX < 0) {
                            if (swipeListener != null) swipeListener.onSwipeLeft();
                        } else {
                            if (swipeListener != null) swipeListener.onSwipeRight();
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.swipeListener = listener;
    }

    public void setOnSingleTapListener(Runnable listener) {
        this.singleTapListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isAnimating) return true;
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        boolean gestureHandled = gestureDetector.onTouchEvent(event);
        return scaleHandled || gestureHandled || super.onTouchEvent(event);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        resetMatrixToFitCenter();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        resetMatrixToFitCenter();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            resetMatrixToFitCenter();
        }
    }

    public void resetMatrixToFitCenter() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;

        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        int vw = getWidth();
        int vh = getHeight();

        matrix.reset();
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        float dx = (vw - dw * scale) * 0.5f;
        float dy = (vh - dh * scale) * 0.5f;

        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        currentScale = 1.0f;
        setImageMatrix(matrix);
    }

    private void animateScale(float fromScale, float toScale, float focusX, float focusY) {
        isAnimating = true;
        ValueAnimator animator = ValueAnimator.ofFloat(fromScale, toScale);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());

        final float[] prevScale = new float[]{fromScale};
        animator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            float factor = val / prevScale[0];
            prevScale[0] = val;
            currentScale = val;

            matrix.postScale(factor, factor, focusX, focusY);
            checkMatrixBounds();
            setImageMatrix(matrix);
        });

        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                isAnimating = false;
                if (toScale == MIN_SCALE) {
                    resetMatrixToFitCenter();
                }
            }
        });
        animator.start();
    }

    private void checkMatrixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;

        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);

        int vw = getWidth();
        int vh = getHeight();

        float deltaX = 0;
        float deltaY = 0;

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) * 0.5f - rect.left;
        } else {
            if (rect.left > 0) deltaX = -rect.left;
            else if (rect.right < vw) deltaX = vw - rect.right;
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) * 0.5f - rect.top;
        } else {
            if (rect.top > 0) deltaY = -rect.top;
            else if (rect.bottom < vh) deltaY = vh - rect.bottom;
        }

        matrix.postTranslate(deltaX, deltaY);
    }
}
