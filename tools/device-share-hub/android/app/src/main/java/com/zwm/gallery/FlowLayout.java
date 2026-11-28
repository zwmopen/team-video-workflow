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
    private boolean centerRows;

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

    public void setCenterRows(boolean centerRows) {
        this.centerRows = centerRows;
        requestLayout();
    }

    private int withSpacing(int rowWidth, int childWidth) {
        return rowWidth == 0 ? childWidth : rowWidth + horizontalSpacing + childWidth;
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

            if (widthMode != MeasureSpec.UNSPECIFIED && withSpacing(lineWidth, childWidth) > widthSize && lineWidth > 0) {
                totalHeight += lineHeight + verticalSpacing;
                maxLineWidth = Math.max(maxLineWidth, lineWidth);
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth = withSpacing(lineWidth, childWidth);
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
        int available = r - l - getPaddingLeft() - getPaddingRight();
        int rowTop = getPaddingTop();
        int rowStart = 0;
        int rowWidth = 0;
        int rowHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int childWidth = child.getMeasuredWidth();
            if (rowWidth > 0 && withSpacing(rowWidth, childWidth) > available) {
                layoutRow(rowStart, i, rowTop, rowWidth, rowHeight, available);
                rowTop += rowHeight + verticalSpacing;
                rowStart = i;
                rowWidth = 0;
                rowHeight = 0;
            }
            rowWidth = withSpacing(rowWidth, childWidth);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        layoutRow(rowStart, getChildCount(), rowTop, rowWidth, rowHeight, available);
    }

    private void layoutRow(int start, int end, int top, int rowWidth, int rowHeight, int available) {
        int left = getPaddingLeft() + (centerRows ? Math.max(0, (available - rowWidth) / 2) : 0);
        for (int i = start; i < end; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int childTop = top + (centerRows ? (rowHeight - child.getMeasuredHeight()) / 2 : 0);
            child.layout(left, childTop, left + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
            left += child.getMeasuredWidth() + horizontalSpacing;
        }
    }
}
