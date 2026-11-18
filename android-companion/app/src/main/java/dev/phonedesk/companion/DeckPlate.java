package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Random;

/**
 * A deck's plate: a moss dendrite grown from a seed, with the deck's tag in the
 * middle. The Deck Index study draws one per deck so a new board has a mark on
 * the day it is installed, without a photograph or a per-deck asset.
 *
 * <b>Seeded by the deck's own key.</b> The same deck draws the same plate on every
 * launch and on every phone, because the seed is a hash of the id the board
 * publishes — not a random number. A re-flash keeps its plate; a second board of
 * the same widget gets a different one.
 *
 * <b>Drawn, not decoded.</b> A bitmap per deck would be a cache to invalidate and
 * a file to ship. This is a few hundred line segments on a Canvas, which is cheap
 * at the 46×58dp a row uses and still sharp at the 3× density of a modern phone.
 */
final class DeckPlate extends View {

    /** The plate's ink: the study's pale moss. */
    private static final int INK = 0xFFC9F2C6;
    /** The plate's ground: darker than the page, so the mark reads as a window. */
    private static final int GROUND = 0xFF07080A;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF frame = new RectF();
    private String tag = "";
    private long seed;
    private boolean built;

    DeckPlate(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(INK);
        ground.setColor(GROUND);
        label.setColor(INK);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Ui.mono(context));
    }

    /**
     * Point the plate at a deck. {@code key} is the store key — the published id
     * when there is one, the MAC when there is not — and {@code tag} is the short
     * code the row already shows.
     */
    void set(String key, String tag) {
        this.tag = tag == null ? "" : tag;
        this.seed = hash(key == null ? "" : key);
        built = false;
        invalidate();
    }

    /** FNV-1a, the same arithmetic the study's plates use. */
    private static long hash(String text) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.drawRect(0, 0, w, h, ground);
        if (!built) {
            build(w, h);
            built = true;
        }
        canvas.drawPath(path, stroke);

        // The tag sits in a small lozenge, the way the study's plates carry it. At
        // a row's 46dp the lozenge would be a smudge, so it is drawn only when the
        // plate is big enough to read it.
        if (w >= 90 && !tag.isEmpty()) {
            final float cx = w / 2f;
            final float cy = h / 2f;
            final float half = Math.min(w * 0.3f, 34f);
            frame.set(cx - half, cy - 11f, cx + half, cy + 11f);
            canvas.drawRoundRect(frame, 11f, 11f, stroke);
            label.setTextSize(Math.max(8f, w * 0.032f));
            label.setLetterSpacing(0.18f);
            canvas.drawText(tag, cx, cy + label.getTextSize() * 0.36f, label);
        }
    }

    /**
     * Grow the dendrites once per size. The recursion is shallow on purpose: this
     * runs when a row is first laid out, not on every frame.
     */
    private void build(int w, int h) {
        path.reset();
        final Random random = new Random(seed);
        final float cx = w * (0.46f + random.nextFloat() * 0.08f);
        final float cy = h * (0.47f + random.nextFloat() * 0.06f);
        final int rays = 13 + random.nextInt(6);
        final float reach = Math.min(w, h) * 0.62f;
        stroke.setStrokeWidth(Math.max(1.1f, w * 0.012f));
        for (int i = 0; i < rays; i++) {
            final double angle = (i / (double) rays) * Math.PI * 2
                    + (random.nextDouble() - 0.5) * 0.3;
            branch(random, path, cx, cy, angle, reach * (0.55f + random.nextFloat() * 0.45f), 2);
        }
    }

    private void branch(Random random, Path out, float x, float y, double angle,
                        float length, int depth) {
        float px = x;
        float py = y;
        double a = angle;
        final int steps = 3 + random.nextInt(3);
        for (int i = 0; i < steps; i++) {
            a += (random.nextDouble() - 0.5) * 0.5;
            final float nx = px + (float) Math.cos(a) * (length / steps);
            final float ny = py + (float) Math.sin(a) * (length / steps);
            out.moveTo(px, py);
            out.lineTo(nx, ny);
            if (depth > 0 && random.nextFloat() > 0.58f) {
                branch(random, out, nx, ny, a + (random.nextBoolean() ? 0.7 : -0.7),
                        length * 0.52f, depth - 1);
            }
            px = nx;
            py = ny;
        }
        if (depth > 0 && random.nextFloat() > 0.3f) {
            branch(random, out, px, py, a + (random.nextDouble() - 0.5) * 0.9,
                    length * 0.6f, depth - 1);
        }
    }

    /** The plate's own ink, for a caller that wants to match it. */
    static int ink() {
        return INK;
    }

    /** A hairline frame, so a plate on a dark page still has an edge. */
    static int edge() {
        return Color.argb(0x24, 0xFF, 0xFF, 0xFF);
    }
}
