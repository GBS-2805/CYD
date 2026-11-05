package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Six square LEDs in a row: the sheet's whole distinction between a deck that is
 * paired and one that is not.
 *
 * Three states, and only three:
 * <ul>
 *   <li><b>off</b> — outlines and no light. The deck has never been paired, so there
 *       is nothing to report.</li>
 *   <li><b>steady</b> — every LED lit. The phone has the deck's record: it is
 *       paired, whether or not the radio is up right now.</li>
 *   <li><b>chase</b> — one LED bright and the rest dim, stepping along. The bridge is
 *       linked to this deck at this moment, and the marquee is the only thing on the
 *       screen that moves.</li>
 * </ul>
 *
 * It is a fill and a direction of travel rather than three colours, so the same
 * drawing works on all three palettes and both pages. The chase runs only while the
 * view is attached, so a sheet that is closed costs nothing.
 */
final class LedMarquee extends View {

    /** Off, steady, chase. */
    static final int OFF = 0;
    static final int STEADY = 1;
    static final int CHASE = 2;

    private static final int LEDS = 6;
    /** One step every 140ms: fast enough to read as alive, slow enough not to nag. */
    private static final long STEP_MS = 140;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private int mode = OFF;
    private int phase;
    /** 0 means the app's own accent and hairline; a deck's row passes its palette. */
    private int accent;
    private int rule;

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (mode != CHASE) return;
            phase = (phase + 1) % LEDS;
            invalidate();
            postDelayed(this, STEP_MS);
        }
    };

    LedMarquee(Context context) {
        super(context);
    }

    /** Set the state; a view already in that state is left alone. */
    void set(int state) {
        if (mode == state) return;
        mode = state;
        removeCallbacks(step);
        if (state == CHASE) postDelayed(step, STEP_MS);
        invalidate();
    }

    int mode() {
        return mode;
    }

    /**
     * Draw the lamps in a given palette: one deck's row is a tile of that palette,
     * so the LED that says it is live cannot be the page's accent underneath it.
     */
    void setInks(int accentColor, int ruleColor) {
        accent = accentColor;
        rule = ruleColor;
        invalidate();
    }

    private int accentInk() {
        return accent == 0 ? Ui.ACCENT : accent;
    }

    private int ruleInk() {
        return rule == 0 ? Ui.EDGE : rule;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mode == CHASE) postDelayed(step, STEP_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(step);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int led = Ui.dp(getContext(), 4);
        final int gap = Ui.dp(getContext(), 4);
        int wanted = LEDS * led + (LEDS - 1) * gap;
        int width = resolveSize(wanted, widthSpec);
        int height = resolveSize(led, heightSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int led = Ui.dp(getContext(), 4);
        final int gap = Ui.dp(getContext(), 4);
        final float top = (getHeight() - led) / 2f;
        for (int i = 0; i < LEDS; ++i) {
            float left = i * (led + gap);
            cell.set(left, top, left + led, top + led);
            if (mode == OFF) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1) * 0.9f));
                paint.setColor(ruleInk());
            } else if (mode == STEADY) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accentInk());
            } else {
                paint.setStyle(Paint.Style.FILL);
                // The head is the accent at full strength; the rest is the same ink
                // at a third of it, so the row reads as one moving light rather than
                // as six independent lamps.
                paint.setColor(i == phase ? accentInk() : withAlpha(accentInk(), 0x55));
            }
            canvas.drawRect(cell, paint);
        }
    }

    /** The accent at a given alpha, whatever the palette's own opacity is. */
    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}
