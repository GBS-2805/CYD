package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

import render.HostText;
import render.Ops;

/**
 * Text that measures itself: the face's line height, the face's advances, and a word
 * wrap — which is how the platform does it, minus the shaping this app never asks for
 * (it is monospace throughout).
 */
public class TextView extends android.view.View {

    private String text = "";
    private float textSize = 14f;
    private int textColor = 0xFFFFFFFF;
    private boolean bold;
    private float letterSpacing;
    private float lineSpacingAdd;
    private float lineSpacingMultiplier = 1f;
    private int gravity;
    private int minimumWidth;
    private int minimumHeight;

    private List<String> lines = new ArrayList<>();
    private float lineHeight;
    private float widest;

    public TextView(Context context) {
        super(context);
    }

    // -- what the app sets -------------------------------------------------

    public void setText(String value) {
        this.text = value == null ? "" : value;
    }

    public String getText() {
        return text;
    }

    public void setTextSize(float sp) {
        this.textSize = sp;
    }

    public void setTextSize(int unit, float sp) {
        this.textSize = sp;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public void setTypeface(Typeface typeface) {
        this.bold = typeface != null && typeface.getStyle() == Typeface.BOLD;
    }

    public void setTypeface(Typeface typeface, int style) {
        this.bold = (style & Typeface.BOLD) != 0;
    }

    public void setLetterSpacing(float spacing) {
        this.letterSpacing = spacing;
    }

    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public void setLineSpacing(float add, float multiplier) {
        this.lineSpacingAdd = add;
        this.lineSpacingMultiplier = multiplier;
    }

    public void setMinimumWidth(int width) {
        this.minimumWidth = width;
    }

    public void setMinimumHeight(int height) {
        this.minimumHeight = height;
    }

    // -- measuring ---------------------------------------------------------

    @Override
    protected void onMeasure(int available, boolean exact) {
        int outer = Math.max(0, available);
        float inner = Math.max(0, outer - padLeft - padRight);
        lines = HostText.wrap(text, textSize, bold, letterSpacing, inner);
        lineHeight = HostText.lineHeight(textSize, bold);

        widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, HostText.advance(line, textSize, bold, letterSpacing));
        }

        int wrapped = (int) Math.ceil(widest) + padLeft + padRight;
        measuredWidth = exact ? outer : Math.max(minimumWidth, Math.min(outer, wrapped));

        float height = lines.size() * lineHeight
                + Math.max(0, lines.size() - 1) * lineSpacingAdd;
        measuredHeight = Math.max(minimumHeight,
                (int) Math.ceil(height + padTop + padBottom));
        if (lp != null && lp.height > 0) measuredHeight = lp.height;
    }

    // -- painting ----------------------------------------------------------

    @Override
    public void emit(Ops out) {
        super.emit(out);
        float contentLeft = left + padLeft;
        float contentWidth = measuredWidth - padLeft - padRight;
        float y = top + padTop;
        for (String line : lines) {
            float width = HostText.advance(line, textSize, bold, letterSpacing);
            float x = contentLeft;
            if ((gravity & Gravity.CENTER_HORIZONTAL) != 0) {
                x = contentLeft + (contentWidth - width) / 2f;
            }
            out.text(x, y, width, lineHeight, line, textSize, bold, letterSpacing, textColor);
            y += lineHeight + lineSpacingAdd;
        }
    }

    // Unused by the drawer, kept so a stub match is obvious if a screen starts using it.
    static ViewGroup.LayoutParams params(int width, int height) {
        return new ViewGroup.LayoutParams(width, height);
    }
}
