package com.zwm.gallery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * A lightweight ViewGroup that arranges children in a row,
 * wrapping them automatically to the next line when available width is exceeded.
 */
public class FlowLayout extends ViewGroup {
    private int horizontalSpacing = 0;
    private int verticalSpacing = 0;

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FlowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setHorizontalSpacing(int spacing) {
        this.horizontalSpacing = spacing;
        requestLayout();
    }

    public void setVerticalSpacing(int spacing) {
        this.verticalSpacing = spacing;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);

        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;
        int maxLineWidth = 0;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();

            if (widthMode != MeasureSpec.UNSPECIFIED && lineWidth + childWidth > widthSize && lineWidth > 0) {
                totalHeight += lineHeight + verticalSpacing;
                maxLineWidth = Math.max(maxLineWidth, lineWidth);
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth += childWidth + (lineWidth > 0 ? horizontalSpacing : 0);
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }
        totalHeight += lineHeight;
        maxLineWidth = Math.max(maxLineWidth, lineWidth);

        int measuredWidth = resolveSize(maxLineWidth + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int measuredHeight = resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int widthSize = r - l - getPaddingRight();
        int currentLeft = getPaddingLeft();
        int currentTop = getPaddingTop();
        int lineHeight = 0;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();

            if (currentLeft + childWidth > widthSize && currentLeft > getPaddingLeft()) {
                currentLeft = getPaddingLeft();
                currentTop += lineHeight + verticalSpacing;
                lineHeight = 0;
            }

            child.layout(currentLeft, currentTop, currentLeft + childWidth, currentTop + childHeight);
            currentLeft += childWidth + horizontalSpacing;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }
}
