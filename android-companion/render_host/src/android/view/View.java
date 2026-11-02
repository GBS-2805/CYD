package android.view;

import android.content.Context;
import android.graphics.drawable.Drawable;

import render.Ops;

/**
 * A measured box. The app decides everything that matters — padding, margins, dp
 * sizes, weights — and this only carries those numbers to a rectangle.
 *
 * `measure(available, exact)` is not Android's `MeasureSpec`, because a renderer does
 * not need the spec's thirty-two bits of mode: a child is either told how wide it is
 * (a match-parent, or a share of a weighted row) or given a maximum and left to wrap.
 */
public class View {

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public interface OnClickListener {
        void onClick(View view);
    }

    protected final Context context;

    public int padLeft;
    public int padTop;
    public int padRight;
    public int padBottom;

    public Drawable background;
    public int backgroundColor;
    public int visibility = VISIBLE;

    public ViewGroup.LayoutParams lp;
    public ViewGroup parent;

    /** Absolute bounds in dp, set by {@link #layout}. */
    public int left;
    public int top;
    public int right;
    public int bottom;

    public int measuredWidth;
    public int measuredHeight;

    private OnClickListener listener;

    public View(Context context) {
        this.context = context;
    }

    // -- what the app sets -------------------------------------------------

    public void setPadding(int left, int top, int right, int bottom) {
        this.padLeft = left;
        this.padTop = top;
        this.padRight = right;
        this.padBottom = bottom;
    }

    public void setBackground(Drawable drawable) {
        this.background = drawable;
    }

    public void setBackgroundColor(int color) {
        this.backgroundColor = color;
    }

    public void setLayoutParams(ViewGroup.LayoutParams params) {
        this.lp = params;
    }

    public ViewGroup.LayoutParams getLayoutParams() {
        return lp;
    }

    public void setVisibility(int visibility) {
        this.visibility = visibility;
    }

    public int getVisibility() {
        return visibility;
    }

    public void setOnClickListener(OnClickListener listener) {
        this.listener = listener;
    }

    /** A tap, so a frame that only exists after one can still be rendered. */
    public boolean performClick() {
        if (listener == null) return false;
        listener.onClick(this);
        return true;
    }

    /** The drawer posts a scroll restore; there is nothing to restore during a render. */
    public void post(Runnable runnable) {
        runnable.run();
    }

    // -- measuring ---------------------------------------------------------

    public final void measure(int available, boolean exact) {
        measuredWidth = 0;
        measuredHeight = 0;
        onMeasure(available, exact);
    }

    protected void onMeasure(int available, boolean exact) {
        measuredWidth = exact ? available : widthOrDefault(0, available);
        measuredHeight = heightOrDefault(padTop + padBottom);
    }

    protected int widthOrDefault(int wrapped, int available) {
        int width = wrapped;
        if (lp != null && lp.width > 0) width = lp.width;
        return Math.max(0, Math.min(exactLimit(available), width));
    }

    protected int heightOrDefault(int wrapped) {
        if (lp != null && lp.height > 0) return lp.height;
        return Math.max(0, wrapped);
    }

    private int exactLimit(int available) {
        return available;
    }

    public final void layout(int x, int y) {
        left = x;
        top = y;
        right = x + measuredWidth;
        bottom = y + measuredHeight;
        onLayout();
    }

    protected void onLayout() {
    }

    public int getMeasuredWidth() {
        return measuredWidth;
    }

    public int getMeasuredHeight() {
        return measuredHeight;
    }

    // -- painting ----------------------------------------------------------

    /** Paint order: the view's own ground, then whatever it contains. */
    public void emit(Ops out) {
        if (background != null) {
            out.card(left, top, measuredWidth, measuredHeight, background.radius,
                    background.fill, background.stroke);
        } else if (backgroundColor != 0) {
            out.color(left, top, measuredWidth, measuredHeight, backgroundColor);
        }
    }
}
