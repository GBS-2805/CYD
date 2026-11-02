package android.view;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import render.Ops;

/** A view with children and the platform's two layout params, margins included. */
public class ViewGroup extends View {

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public static final int FILL_PARENT = -1;

        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;

        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }
    }

    private final List<View> children = new ArrayList<>();

    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View child) {
        addView(child, child.getLayoutParams() != null ? child.getLayoutParams()
                : new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void addView(View child, LayoutParams params) {
        child.setLayoutParams(params);
        child.parent = this;
        children.add(child);
    }

    public void removeAllViews() {
        children.clear();
    }

    public int getChildCount() {
        return children.size();
    }

    public View getChildAt(int index) {
        return children.get(index);
    }

    public List<View> children() {
        return children;
    }

    @Override
    public void emit(Ops out) {
        super.emit(out);
        for (View child : children) {
            if (child.visibility != GONE) child.emit(out);
        }
    }
}
