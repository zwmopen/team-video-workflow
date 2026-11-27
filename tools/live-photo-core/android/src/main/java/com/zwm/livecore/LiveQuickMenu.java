package com.zwm.livecore;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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

/** Anchored, theme-aware quick menu. No dialog or network dependency. */
public final class LiveQuickMenu {
    public interface Selection { void select(int index); }
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout overlay, panel;
    private ScrollView scroll;
    private TextView[] rows;
    private Selection selection;
    private View anchor;
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
        target.setOnTouchListener((v,event) -> {
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
                    float dx = event.getRawX()-downX, dy = event.getRawY()-downY;
                    if (!held && Math.hypot(dx,dy)>ViewConfiguration.get(activity).getScaledTouchSlop()) {
                        handler.removeCallbacks(hold);
                        tracking=false; v.setPressed(false); releaseParent(v);
                    } else if (held && isOpen()) {
                        if (Math.hypot(dx,dy)>dp(8)) moved=true;
                        if (moved) updateHighlight(event.getRawX(),event.getRawY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(hold);
                    v.setPressed(false); releaseParent(v);
                    if (tracking) {
                        if (held && isOpen() && moved) {
                            updateHighlight(event.getRawX(),event.getRawY());
                            if (highlighted >= 0) commit(highlighted); else close();
                        } else if (!held) v.performClick();
                        // A stationary hold leaves the popup open for ordinary taps.
                    }
                    tracking=false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(hold);
                    tracking=false; v.setPressed(false); releaseParent(v);
                    close(); return true;
                default: return true;
            }
        });
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(hold); tracking=false; close();
            }
        });
    }

    public boolean isOpen() { return overlay != null; }
    public boolean close() {
        if (overlay == null) return false;
        ViewGroup parent=(ViewGroup)overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay=null; panel=null; rows=null; scroll=null; selection=null; highlighted=-1;
        return true;
    }

    public void show(View target, String[] labels, int selected, Selection onSelect) {
        close();
        if (target == null || !target.isAttachedToWindow() || labels.length == 0 || activity.isFinishing()) return;
        ViewGroup root=(ViewGroup)activity.getWindow().getDecorView();
        if (root.getWidth()==0 || root.getHeight()==0) return;
        current=selected; selection=onSelect;
        boolean night=dark();
        overlay=new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close());
        overlay.setBackgroundColor(0x08000000);
        panel=new FrameLayout(activity);
        panel.setClickable(true);
        panel.setBackground(shape(night?0xF22B2D33:0xF7FAFAFC,23,night?0x44FFFFFF:0x28000000));
        panel.setElevation(dp(12));
        panel.setClipToOutline(true);
        scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list=new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(6),dp(6),dp(6),dp(6));
        rows=new TextView[labels.length];
        for(int i=0;i<labels.length;i++) {
            final int n=i;
            TextView row=new TextView(activity);
            row.setText(labels[i]+(i==selected?"  ✓":""));
            row.setTextSize(14); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(13),0,dp(12),0);
            row.setSingleLine(false); row.setMaxLines(2);
            row.setTextColor(night?Color.WHITE:0xFF22252A);
            row.setContentDescription(labels[i]+(i==selected?"，已选中":""));
            row.setOnClickListener(v -> commit(n));
            list.addView(row,new LinearLayout.LayoutParams(-1,dp(44)));
            rows[i]=row;
        }
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new FrameLayout.LayoutParams(-1,-1));

        int[] a=new int[2], r=new int[2];
        target.getLocationOnScreen(a); root.getLocationOnScreen(r);
        Rect visible=new Rect(); root.getWindowVisibleDisplayFrame(visible);
        int minX=Math.max(dp(8),visible.left-r[0]+dp(8));
        int minY=Math.max(dp(8),visible.top-r[1]+dp(8));
        int maxX=Math.min(root.getWidth()-dp(8),visible.right-r[0]-dp(8));
        int maxY=Math.min(root.getHeight()-dp(8),visible.bottom-r[1]-dp(8));
        int width=Math.min(dp(248),maxX-minX);
        int below=a[1]-r[1]+target.getHeight()+dp(6), above=a[1]-r[1]-dp(6);
        boolean openBelow=(maxY-below)>=Math.min(dp(44*labels.length+12),above-minY);
        int available=openBelow?maxY-below:above-minY;
        if (available<dp(88) || width<dp(80)) { close(); return; }
        int height=Math.min(dp(44*labels.length+12),available);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,height,Gravity.TOP|Gravity.LEFT);
        lp.leftMargin=Math.max(minX,Math.min(maxX-width,a[0]-r[0]+target.getWidth()-width));
        lp.topMargin=openBelow?below:above-height;
        overlay.addView(panel,lp);
        root.addView(overlay,new ViewGroup.LayoutParams(-1,-1));
        paint(-1);
        panel.setPivotX(width-dp(20));
        panel.setPivotY(openBelow?0:height);
        if (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            panel.setAlpha(0); panel.setScaleX(.94f); panel.setScaleY(.94f);
            panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
        if (selected>=0 && selected*dp(44)>height-dp(50))
            scroll.post(() -> { if (scroll!=null && !tracking) scroll.scrollTo(0,selected*dp(44)-dp(44)); });
    }
    private void paint(int hit) {
        if(rows==null)return;
        for(int i=0;i<rows.length;i++) {
            int color=i==hit?(dark()?0xFF535D70:0xFFDCE5F3):
                    i==current?(dark()?0xFF3A3D46:0xFFECEEF3):Color.TRANSPARENT;
            rows[i].setBackground(shape(color,14,0));
        }
    }
    private void updateHighlight(float x,float y) {
        if(rows==null || panel==null)return;
        // getLocationOnScreen is also used for row hit testing.
        int[] loc=new int[2]; panel.getLocationOnScreen(loc);
        int hit=-1;
        if(x>=loc[0] && x<loc[0]+panel.getWidth() && y>=loc[1] && y<loc[1]+panel.getHeight()) {
            if(scroll!=null && panel.getHeight()<dp(44*rows.length+12)) {
                if(y>loc[1]+panel.getHeight()-dp(24))scroll.scrollBy(0,dp(12));
                else if(y<loc[1]+dp(24))scroll.scrollBy(0,-dp(12));
            }
            for(int i=0;i<rows.length;i++) {
                int[] pos=new int[2];rows[i].getLocationOnScreen(pos);
                if(y>=pos[1] && y<pos[1]+rows[i].getHeight()){hit=i;break;}
            }
        }
        if(hit!=highlighted) {
            highlighted=hit; paint(hit);
            if(hit>=0 && anchor!=null)anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }
    private void commit(int n) {
        Selection callback=selection;
        close();
        if(callback!=null)callback.select(n);
    }
}
