package com.zwm.livecore;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * FlClash-style elliptical sliding glass bar supporting both tap and continuous finger scrubbing.
 * Works as a compact top mode switcher (icon segments + long-press) or a floating bottom action dock.
 */
public class SlidingGlassBar extends FrameLayout {

    public interface OnSegmentSelectedListener {
        void onSelected(int index, boolean fromScrub);
    }

    public interface OnSegmentLongPressListener {
        boolean onLongPress(int index);
    }

    public static final class Segment {
        public final String label;
        public final String contentDescription;
        public final Drawable icon;
        public final boolean accent;
        public final boolean destructive;

        public Segment(String label, String contentDescription, Drawable icon, boolean accent, boolean destructive) {
            this.label = label;
            this.contentDescription = contentDescription != null ? contentDescription : label;
            this.icon = icon;
            this.accent = accent;
            this.destructive = destructive;
        }
    }

    private final boolean dockMode;
    private final LinearLayout row;
    private final View pillView;
    private Segment[] segments = new Segment[0];
    private View[] itemViews = new View[0];
    private ImageView[] iconViews = new ImageView[0];
    private TextView[] labelViews = new TextView[0];
    private boolean[] enabledFlags = new boolean[0];

    private boolean darkMode = false;
    private boolean glassStyle = true;
    private int accentColor = 0xFF0F8758;
    private int selectedIndex = 0;
    private int hoverIndex = -1;
    private float pillPosition = 0f;
    private boolean showHoverPill = false;

    private boolean tracking = false;
    private boolean scrubbing = false;
    private boolean cancelledByDragOut = false;
    private boolean longPressFired = false;
    private float downX, downY;
    private int lastHapticIdx = -1;

    private ValueAnimator pillAnimator;
    private OnSegmentSelectedListener selectedListener;
    private OnSegmentLongPressListener longPressListener;

    private final Runnable longPressRunnable = () -> {
        if (!tracking || scrubbing || longPressListener == null || segments.length == 0) return;
        int idx = indexAtLocalX(downX);
        if (idx >= 0 && idx < segments.length && isSegmentEnabled(idx)) {
            if (longPressListener.onLongPress(idx)) {
                longPressFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }
    };

    public SlidingGlassBar(Context context, boolean dockMode) {
        super(context);
        this.dockMode = dockMode;
        setClipChildren(false);
        setClipToPadding(false);

        int pad = dp(dockMode ? 5 : 3);
        setPadding(pad, pad, pad, pad);

        pillView = new View(context);
        LayoutParams pillLp = new LayoutParams(dp(40), LayoutParams.MATCH_PARENT);
        pillLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        addView(pillView, pillLp);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int r = Math.max(dp(18), view.getHeight() / 2);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        setClipToOutline(true);
        setElevation(dp(dockMode ? 12 : 2));
        applyChrome();
    }

    public void setSegments(Segment[] items, int initialSelected) {
        this.segments = items != null ? items : new Segment[0];
        this.itemViews = new View[this.segments.length];
        this.iconViews = new ImageView[this.segments.length];
        this.labelViews = new TextView[this.segments.length];
        this.enabledFlags = new boolean[this.segments.length];
        for (int i = 0; i < this.segments.length; i++) enabledFlags[i] = true;

        row.removeAllViews();
        for (int i = 0; i < this.segments.length; i++) {
            Segment seg = this.segments[i];
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER);
            cell.setContentDescription(seg.contentDescription);
            cell.setFocusable(true);
            int hPad = dp(dockMode ? 4 : 9);
            cell.setPadding(hPad, 0, hPad, 0);

            if (seg.icon != null) {
                ImageView iv = new ImageView(getContext());
                iv.setImageDrawable(seg.icon.mutate());
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int iconSize = dp(dockMode ? 15 : 17);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
                if (!TextUtils.isEmpty(seg.label)) ilp.rightMargin = dp(4);
                cell.addView(iv, ilp);
                iconViews[i] = iv;
            }
            if (!TextUtils.isEmpty(seg.label)) {
                TextView tv = new TextView(getContext());
                tv.setText(seg.label);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, dockMode ? 12.5f : 12f);
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                cell.addView(tv, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                labelViews[i] = tv;
            }

            LinearLayout.LayoutParams lp = dockMode
                    ? new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    : new LinearLayout.LayoutParams(dp(38), dp(34));
            row.addView(cell, lp);
            itemViews[i] = cell;
        }
        this.selectedIndex = clampIndex(initialSelected);
        this.pillPosition = this.selectedIndex;
        applyChrome();
        post(this::layoutPillImmediate);
    }

    public void setSelectedIndex(int index, boolean animate) {
        int clamped = clampIndex(index);
        this.selectedIndex = clamped;
        if (!dockMode) {
            animatePillTo(clamped, animate);
        }
        applyChrome();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public View getSegmentView(int index) {
        if (index < 0 || index >= itemViews.length) return null;
        return itemViews[index];
    }

    public void setSegmentEnabled(int index, boolean enabled) {
        if (index < 0 || index >= enabledFlags.length) return;
        enabledFlags[index] = enabled;
        applyChrome();
    }

    public void setOnSegmentSelectedListener(OnSegmentSelectedListener listener) {
        this.selectedListener = listener;
    }

    public void setOnSegmentLongPressListener(OnSegmentLongPressListener listener) {
        this.longPressListener = listener;
    }

    public void applyAppearance(boolean darkMode, boolean glassStyle, int accentColor) {
        this.darkMode = darkMode;
        this.glassStyle = glassStyle;
        this.accentColor = accentColor;
        applyChrome();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutPillImmediate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (segments.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (pillAnimator != null) pillAnimator.cancel();
                downX = event.getX();
                downY = event.getY();
                tracking = true;
                scrubbing = false;
                cancelledByDragOut = false;
                longPressFired = false;
                lastHapticIdx = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);

                int idx = indexAtLocalX(downX);
                if (dockMode) {
                    hoverIndex = isSegmentEnabled(idx) ? idx : -1;
                    showHoverPill = hoverIndex >= 0;
                    if (showHoverPill) {
                        pillPosition = hoverIndex;
                        scalePill(true);
                    }
                    layoutPillImmediate();
                    applyChrome();
                } else if (longPressListener != null) {
                    postDelayed(longPressRunnable, 360);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!tracking) return true;
                float x = event.getX();
                float y = event.getY();
                float dx = x - downX;
                float dy = y - downY;
                float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

                if (!scrubbing && Math.abs(dx) > slop * 0.7f && Math.abs(dx) >= Math.abs(dy) * 0.7f) {
                    scrubbing = true;
                    removeCallbacks(longPressRunnable);
                    scalePill(true);
                }

                if (dockMode) {
                    float cancelMargin = dp(38);
                    boolean outY = y < -cancelMargin || y > getHeight() + cancelMargin;
                    if (outY) {
                        cancelledByDragOut = true;
                        showHoverPill = false;
                        hoverIndex = -1;
                        layoutPillImmediate();
                        applyChrome();
                        return true;
                    }
                    cancelledByDragOut = false;
                }

                if (scrubbing) {
                    float continuous = continuousIndexAtX(x);
                    int nearest = clampIndex(Math.round(continuous));
                    if (dockMode) {
                        hoverIndex = isSegmentEnabled(nearest) ? nearest : -1;
                        showHoverPill = hoverIndex >= 0;
                    } else {
                        selectedIndex = nearest;
                    }
                    pillPosition = continuous;
                    layoutPillImmediate();
                    applyChrome();
                    if (nearest != lastHapticIdx && isSegmentEnabled(nearest)) {
                        lastHapticIdx = nearest;
                        performHapticFeedback(Build.VERSION.SDK_INT >= 21
                                ? HapticFeedbackConstants.CLOCK_TICK
                                : HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRunnable);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (!tracking) return true;
                tracking = false;
                scalePill(false);

                int targetIdx = indexAtLocalX(event.getX());
                boolean wasScrub = scrubbing;
                boolean wasLong = longPressFired;
                boolean wasCancelled = cancelledByDragOut;
                scrubbing = false;
                cancelledByDragOut = false;

                if (dockMode) {
                    showHoverPill = false;
                    hoverIndex = -1;
                    layoutPillImmediate();
                    applyChrome();
                    if (!wasCancelled && !wasLong && targetIdx >= 0 && isSegmentEnabled(targetIdx)) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        if (selectedListener != null) selectedListener.onSelected(targetIdx, wasScrub);
                    }
                } else {
                    if (!wasLong && targetIdx >= 0 && isSegmentEnabled(targetIdx)) {
                        selectedIndex = targetIdx;
                        animatePillTo(targetIdx, true);
                        applyChrome();
                        if (selectedListener != null) {
                            selectedListener.onSelected(targetIdx, wasScrub);
                        }
                    } else {
                        animatePillTo(selectedIndex, true);
                        applyChrome();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN: {
                removeCallbacks(longPressRunnable);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                tracking = false;
                scrubbing = false;
                cancelledByDragOut = false;
                scalePill(false);
                if (dockMode) {
                    showHoverPill = false;
                    hoverIndex = -1;
                    layoutPillImmediate();
                } else {
                    animatePillTo(selectedIndex, true);
                }
                applyChrome();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void animatePillTo(int targetIdx, boolean animate) {
        if (pillAnimator != null) pillAnimator.cancel();
        if (!animate || !animationsAllowed()) {
            pillPosition = targetIdx;
            layoutPillImmediate();
            return;
        }
        float start = pillPosition;
        float end = targetIdx;
        if (Math.abs(start - end) < 0.01f) {
            pillPosition = end;
            layoutPillImmediate();
            return;
        }
        pillAnimator = ValueAnimator.ofFloat(start, end);
        pillAnimator.setDuration(220);
        pillAnimator.setInterpolator(new DecelerateInterpolator(2.2f));
        pillAnimator.addUpdateListener(anim -> {
            pillPosition = (float) anim.getAnimatedValue();
            layoutPillImmediate();
        });
        pillAnimator.start();
    }

    private void scalePill(boolean active) {
        if (!animationsAllowed()) return;
        pillView.animate()
                .scaleX(active ? 1.05f : 1f)
                .scaleY(active ? 1.07f : 1f)
                .setDuration(140)
                .start();
    }

    private void layoutPillImmediate() {
        if (segments.length == 0 || itemViews.length == 0) {
            pillView.setVisibility(View.GONE);
            return;
        }
        if (dockMode && !showHoverPill) {
            pillView.setVisibility(View.GONE);
            return;
        }
        int leftIdx = clampIndex((int) Math.floor(pillPosition));
        int rightIdx = clampIndex((int) Math.ceil(pillPosition));
        View leftV = itemViews[leftIdx];
        View rightV = itemViews[rightIdx];
        if (leftV == null || rightV == null || leftV.getWidth() == 0) {
            pillView.setVisibility(View.INVISIBLE);
            return;
        }
        float frac = Math.max(0f, Math.min(1f, pillPosition - leftIdx));
        float rowLeft = row.getLeft();
        float x = rowLeft + leftV.getLeft() + (rightV.getLeft() - leftV.getLeft()) * frac;
        int w = Math.round(leftV.getWidth() + (rightV.getWidth() - leftV.getWidth()) * frac);
        int h = Math.max(dp(24), getHeight() - getPaddingTop() - getPaddingBottom());

        LayoutParams lp = (LayoutParams) pillView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            pillView.setLayoutParams(lp);
        }
        pillView.setTranslationX(x - getPaddingLeft());
        pillView.setVisibility(View.VISIBLE);
    }

    private void applyChrome() {
        int trackBg;
        int trackStroke;
        if (glassStyle) {
            if (dockMode) {
                trackBg = darkMode ? 0xE6222630 : 0xF0FFFFFF;
                trackStroke = darkMode ? 0x38FFFFFF : 0x24000000;
            } else {
                trackBg = darkMode ? 0xBF1E222A : 0xCCECEFF3;
                trackStroke = darkMode ? 0x26FFFFFF : 0x1A000000;
            }
        } else {
            trackBg = darkMode ? 0xFF232529 : (dockMode ? 0xFFFFFFFF : 0xFFEAECEF);
            trackStroke = darkMode ? 0x30FFFFFF : 0x1F000000;
        }

        GradientDrawable track = new GradientDrawable();
        track.setColor(trackBg);
        track.setCornerRadius(dp(40));
        track.setStroke(Math.max(1, dp(1)), trackStroke);
        setBackground(track);

        int activeIdx = dockMode ? hoverIndex : selectedIndex;
        Segment activeSeg = (activeIdx >= 0 && activeIdx < segments.length) ? segments[activeIdx] : null;
        int pillBg;
        int pillStroke;
        if (dockMode && activeSeg != null && activeSeg.destructive) {
            pillBg = darkMode ? 0x52E05252 : 0x26D93838;
            pillStroke = darkMode ? 0x66FF6B6B : 0x44D93838;
        } else if (dockMode && activeSeg != null && activeSeg.accent) {
            pillBg = applyAlpha(accentColor, darkMode ? 0x52 : 0x28);
            pillStroke = applyAlpha(accentColor, 0x66);
        } else if (glassStyle) {
            pillBg = darkMode ? 0xD9373D4A : 0xFCFFFFFF;
            pillStroke = darkMode ? 0x42FFFFFF : 0x1FFFFFFF;
        } else {
            pillBg = darkMode ? 0xFF343840 : 0xFFFFFFFF;
            pillStroke = darkMode ? 0x28FFFFFF : 0x1A000000;
        }

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(pillBg);
        pill.setCornerRadius(dp(36));
        pill.setStroke(Math.max(1, dp(1)), pillStroke);
        pillView.setBackground(pill);

        for (int i = 0; i < segments.length; i++) {
            Segment seg = segments[i];
            boolean enabled = isSegmentEnabled(i);
            boolean active = dockMode ? (i == hoverIndex) : (i == selectedIndex);
            int color;
            if (!enabled) {
                color = darkMode ? 0x55A0A6B0 : 0x558B929E;
            } else if (seg.destructive && (active || dockMode)) {
                color = darkMode ? (active ? 0xFFFF8282 : 0xFFEB6B6B) : 0xFFD13438;
            } else if (seg.accent) {
                color = active ? accentColor : accentColor;
            } else if (active) {
                color = accentColor;
            } else {
                color = darkMode ? 0xFFB8C0CC : 0xFF555E6B;
            }
            if (iconViews[i] != null) {
                iconViews[i].setColorFilter(color);
            }
            if (labelViews[i] != null) {
                labelViews[i].setTextColor(color);
            }
            if (itemViews[i] != null) {
                itemViews[i].setAlpha(enabled ? 1f : 0.45f);
            }
        }
    }

    private float continuousIndexAtX(float localX) {
        if (segments.length <= 1) return 0f;
        float start = row.getLeft();
        float width = Math.max(1f, row.getWidth());
        float segW = width / segments.length;
        float pos = (localX - start - segW * 0.5f) / segW;
        return Math.max(0f, Math.min(segments.length - 1, pos));
    }

    private int indexAtLocalX(float localX) {
        if (segments.length == 0) return -1;
        float start = row.getLeft();
        for (int i = 0; i < itemViews.length; i++) {
            View v = itemViews[i];
            if (v == null) continue;
            float l = start + v.getLeft();
            float r = start + v.getRight();
            if (localX >= l && localX <= r) return i;
        }
        return clampIndex(Math.round(continuousIndexAtX(localX)));
    }

    private boolean isSegmentEnabled(int idx) {
        return idx >= 0 && idx < enabledFlags.length && enabledFlags[idx];
    }

    private int clampIndex(int idx) {
        if (segments.length == 0) return 0;
        return Math.max(0, Math.min(segments.length - 1, idx));
    }

    private static int applyAlpha(int rgbColor, int alpha) {
        return Color.argb(alpha, Color.red(rgbColor), Color.green(rgbColor), Color.blue(rgbColor));
    }

    private boolean animationsAllowed() {
        try {
            return Settings.Global.getFloat(
                    getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
