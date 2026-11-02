package android.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

/**
 * The one layout the drawer and the shelf are built from: a column or a row, with the
 * platform's rules for margins, weights and padding.
 *
 * A weighted child in a row is measured twice, as Android measures it: first to learn
 * its fixed siblings' widths, then with the share of what is left. A `WRAP_CONTENT`
 * parent ignores weights, because there is no leftover to share.
 */
public class LinearLayout extends ViewGroup {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static class LayoutParams extends MarginLayoutParams {
        public float weight;
        public int gravity;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }
    }

    private int orientation = VERTICAL;
    private int gravity;

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public int getOrientation() {
        return orientation;
    }

    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public int getGravity() {
        return gravity;
    }

    private static LayoutParams params(View child) {
        if (child.getLayoutParams() instanceof LayoutParams) {
            return (LayoutParams) child.getLayoutParams();
        }
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onMeasure(int available, boolean exact) {
        int inner = Math.max(0, available - padLeft - padRight);
        if (orientation == VERTICAL) {
            measureColumn(inner, available, exact);
        } else {
            measureRow(inner, available, exact);
        }
    }

    private void measureColumn(int inner, int available, boolean exact) {
        int widest = 0;
        int height = padTop + padBottom;
        for (View child : children()) {
            if (child.visibility == GONE) continue;
            LayoutParams lp = params(child);
            int margins = lp.leftMargin + lp.rightMargin;
            child.measure(Math.max(0, inner - margins), lp.width == LayoutParams.MATCH_PARENT);
            widest = Math.max(widest, child.measuredWidth + margins);
            height += child.measuredHeight + lp.topMargin + lp.bottomMargin;
        }
        measuredWidth = exact ? available
                : Math.max(0, Math.min(available, widest + padLeft + padRight));
        measuredHeight = heightOrDefault(height);
    }

    private void measureRow(int inner, int available, boolean exact) {
        float weightSum = 0f;
        int used = 0;
        int widest = 0;

        for (View child : children()) {
            if (child.visibility == GONE) continue;
            LayoutParams lp = params(child);
            used += lp.leftMargin + lp.rightMargin;
            if (lp.weight > 0f) {
                weightSum += lp.weight;
            } else {
                child.measure(inner, false);
                used += child.measuredWidth;
            }
        }

        int spare = Math.max(0, inner - used);
        int tallest = 0;
        for (View child : children()) {
            if (child.visibility == GONE) continue;
            LayoutParams lp = params(child);
            if (lp.weight > 0f) {
                int share = weightSum > 0f
                        ? (int) Math.floor(spare * (lp.weight / weightSum)) : 0;
                child.measure(Math.max(0, share), true);
            }
            tallest = Math.max(tallest, child.measuredHeight + lp.topMargin + lp.bottomMargin);
            widest += child.measuredWidth + lp.leftMargin + lp.rightMargin;
        }
        measuredWidth = exact ? available
                : Math.max(0, Math.min(available, widest + padLeft + padRight));
        measuredHeight = heightOrDefault(tallest + padTop + padBottom);
    }

    @Override
    protected void onLayout() {
        if (orientation == VERTICAL) {
            int y = top + padTop;
            int inner = measuredWidth - padLeft - padRight;
            for (View child : children()) {
                if (child.visibility == GONE) continue;
                LayoutParams lp = params(child);
                int x = left + padLeft + lp.leftMargin;
                if ((lp.gravity & Gravity.CENTER_HORIZONTAL) != 0) {
                    x = left + padLeft + (inner - child.measuredWidth) / 2;
                }
                child.layout(x, y + lp.topMargin);
                y += child.measuredHeight + lp.topMargin + lp.bottomMargin;
            }
            return;
        }

        int contentHeight = measuredHeight - padTop - padBottom;
        int x = left + padLeft;
        for (View child : children()) {
            if (child.visibility == GONE) continue;
            LayoutParams lp = params(child);
            x += lp.leftMargin;
            int y = top + padTop + lp.topMargin;
            if ((gravity & Gravity.CENTER_VERTICAL) != 0) {
                y = top + padTop + (contentHeight - child.measuredHeight) / 2;
            }
            child.layout(x, y);
            x += child.measuredWidth + lp.rightMargin;
        }
    }
}
