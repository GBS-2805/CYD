package dev.phonedesk.companion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * The site's two appearance controls, as one row: three swatches and a round
 * light/dark toggle.
 *
 * A swatch is the palette's <b>band</b> with a dot of the words that sit on that
 * band, because that is what the site draws: the fill you get on the header and the
 * ink that is legible on it, in one glance. The live one is ringed rather than
 * labelled, so the row reads without a second line of text.
 *
 * The toggle is the fourth circle: a sun or a crescent, no word. It flips the page
 * and leaves the band alone — which is why the swatches and the toggle are not the
 * same kind of control, and why they are not three plus a "light" swatch.
 *
 * A tap stores the answer and rebuilds the activity: a colour is read when a view is
 * constructed, so a screen already on show would keep the palette it was built in.
 */
final class AppearanceStrip extends LinearLayout {

    /** What a tap does; the activity supplies this so the strip stays a view. */
    interface Pick {
        void palette(int index);

        void light(boolean light);
    }

    AppearanceStrip(Activity activity, Pick pick) {
        super(activity);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));

        final int live = Appearance.palette(activity);
        // The app's own page comes first, then the three deck palettes: four
        // swatches, because the app can be drawn in any of them and the first is
        // what a fresh install gets.
        for (int i = 0; i < Palette.APP_SCHEMES.length; ++i) {
            final Palette.Scheme scheme = Palette.APP_SCHEMES[i];
            final int index = i == 0 ? Palette.FIELD : i - 1;
            View swatch = swatch(activity, scheme, index == live, Ui.TEXT);
            swatch.setOnClickListener(v -> pick.palette(index));
            swatch.setContentDescription("Palette " + scheme.id);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 30), Ui.dp(activity, 30));
            params.rightMargin = Ui.dp(activity, 12);
            addView(swatch, params);
        }

        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 30), Ui.dp(activity, 30));
        toggleParams.leftMargin = Ui.dp(activity, 4);
        View toggle = page(activity, Appearance.light(activity), Ui.EDGE, Ui.TEXT);
        toggle.setOnClickListener(v -> pick.light(!Appearance.light(activity)));
        toggle.setContentDescription(Appearance.light(activity) ? "Dark page" : "Light page");
        addView(toggle, toggleParams);
    }

    /**
     * One palette: a 26dp circle in its band colour with a 9dp dot in the ink that
     * band carries, inside a 30dp box that draws the ring when it is live.
     */
    static View swatch(Context context, Palette.Scheme scheme, boolean live, int ringInk) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(Ui.circle(context, 0, live ? ringInk : 0));

        View disc = new View(context);
        disc.setBackground(Ui.circle(context, scheme.band, 0));
        FrameLayout.LayoutParams discParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 26), Ui.dp(context, 26));
        discParams.gravity = Gravity.CENTER;
        box.addView(disc, discParams);

        View dot = new View(context);
        dot.setBackground(Ui.circle(context, scheme.bandInk, 0));
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 9), Ui.dp(context, 9));
        dotParams.gravity = Gravity.CENTER;
        box.addView(dot, dotParams);
        return box;
    }

    /**
     * The page toggle: a 26dp circle with a sun or a crescent in it. Shared with a
     * deck's row, which is why the ring and the glyph take an ink: a deck's row is a
     * tile of that deck's palette, not of the app's.
     */
    static View page(Context context, boolean light, int ringInk, int glyphInk) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(Ui.circle(context, 0, ringInk));
        box.addView(new Glyph(context, light, glyphInk), new FrameLayout.LayoutParams(
                Ui.dp(context, 15), Ui.dp(context, 15), Gravity.CENTER));
        return box;
    }

    /**
     * The sun and the crescent, drawn rather than set: neither is in the subset of
     * Poppins this app ships, and a missing glyph is an empty circle.
     */
    private static final class Glyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean light;

        Glyph(Context context, boolean light, int ink) {
            super(context);
            this.light = light;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ink);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            if (light) {
                float r = Math.min(w, h) * 0.24f;
                canvas.drawCircle(cx, cy, r, paint);
                float ray = Math.min(w, h) * 0.44f;
                float inner = Math.min(w, h) * 0.32f;
                for (int i = 0; i < 8; ++i) {
                    double angle = i * Math.PI / 4;
                    float x = (float) Math.cos(angle);
                    float y = (float) Math.sin(angle);
                    canvas.drawLine(cx + x * inner, cy + y * inner, cx + x * ray,
                            cy + y * ray, paint);
                }
                return;
            }
            // A crescent: a full disc with a second one bitten out of it, so it reads
            // at 15dp where a stroked outline would close up.
            float r = Math.min(w, h) * 0.44f;
            path.reset();
            path.addCircle(cx, cy, r, Path.Direction.CW);
            path.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.92f, Path.Direction.CCW);
            canvas.drawPath(path, paint);
        }
    }
}
