package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * What the deck being flashed will actually look like: a 2.4" CYD drawn as a
 * bezel, with the deck's own front page inside it, at the deck's own palette.
 *
 * <b>The picture is the sketch's.</b> {@code artifacts/deck_screens.py} draws every
 * page each sketch can put on the glass, call for call from the draw functions, and
 * the front pages are packaged in {@code assets/decks}. This view does not redraw
 * the layout — it picks the file that matches the deck and the palette, so a sketch
 * change that moves a rule shows up here after the next run of that script rather
 * than being re-typed into a second mockup that slowly stops matching.
 *
 * <b>A palette with no picture yet still shows something.</b> The Spotify deck only
 * ships its two monochrome pages, and a deck flashed before a palette existed has
 * no picture at all: the nearest file is drawn instead of an empty rectangle, because
 * a preview that sometimes goes blank is worse than one that is one page behind.
 */
final class DeckPreview extends View {

    /** A CYD's screen, in the sketch's own pixels. */
    static final int DECK_W = 320;
    static final int DECK_H = 240;

    private final Paint bezel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();

    private Bitmap shot;
    private String shown = "";
    private String title = "";

    DeckPreview(Context context) {
        super(context);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, Ui.dp(context, 1)));
        label.setTypeface(Ui.mono(context));
        label.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Point the preview at a deck's picture. {@code widget} is the sketch folder
     * ({@code phone_deck_cyd24R}); {@code look} is the palette and page to draw,
     * which is the same value the swatch beside the preview sets.
     */
    void show(String widget, DeckLook look) {
        final String name = assetName(widget, look);
        final String title = widget == null ? "" : widget;
        if (name.equals(shown)) return;
        shown = name;
        this.title = title;
        shot = load(name);
        if (shot == null && !name.isEmpty()) shot = load(fallbackName(widget, look));
        invalidate();
    }

    /** The deck name under the bezel, printed in the mono face. */
    void caption(String text) {
        title = text == null ? "" : text;
        invalidate();
    }

    /**
     * Which file to draw. The phone deck carries all eight rows, so the palette and
     * page are part of the name; the Spotify deck carries its two.
     */
    static String assetName(String widget, DeckLook look) {
        if (widget == null || widget.isEmpty()) return "";
        if ("phone_deck_cyd24R".equals(widget)) {
            final String[] palette = {"green", "moss", "navy"};
            final int index = Math.max(0, Math.min(palette.length - 1, look.palette));
            return "decks/phone_deck_cyd24R-" + palette[index] + (look.light ? "-light" : "-dark")
                    + ".png";
        }
        return "decks/" + widget + (look.light ? "-paper" : "-ink") + ".png";
    }

    /** Nearest drawable file when the exact page is missing. */
    static String fallbackName(String widget, DeckLook look) {
        if (widget == null || widget.isEmpty()) return "";
        if ("phone_deck_cyd24R".equals(widget)) {
            return "decks/phone_deck_cyd24R-" + (look.light ? "paper" : "ink") + ".png";
        }
        return "decks/" + widget + "-ink.png";
    }

    private Bitmap load(String name) {
        try (InputStream in = getContext().getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int width = MeasureSpec.getSize(widthSpec);
        // 320×240 is 4:3, and the bezel adds a margin on every side plus a label
        // strip, so the height follows from the width rather than from the parent.
        final int height = Math.round(width * DECK_H / (float) DECK_W) + Ui.dp(getContext(), 26);
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Context context = getContext();
        final int margin = Ui.dp(context, 10);
        final int top = Ui.dp(context, 8);
        final int width = getWidth() - margin * 2;
        if (width <= 0) return;
        final int height = Math.round(width * DECK_H / (float) DECK_W);

        // The bezel: the board's own near-black, a hairline, and a slight corner so
        // it reads as a device rather than as a second card inside a card.
        final float radius = Ui.dp(context, 6);
        frame.set(margin, top, margin + width, top + height + Ui.dp(context, 8));
        bezel.setColor(0xFF07080A);
        canvas.drawRoundRect(frame, radius, radius, bezel);
        edge.setColor(Ui.EDGE);
        canvas.drawRoundRect(frame, radius, radius, edge);

        final int inset = Ui.dp(context, 4);
        final RectF screen = new RectF(frame.left + inset, frame.top + inset,
                frame.right - inset, frame.bottom - inset);
        final float screenRadius = Math.max(0, radius - inset);
        if (shot != null) {
            canvas.drawBitmap(shot, null, screen, glass);
        } else {
            // No picture for this deck yet: the glass is the page's own colour and
            // the label says so, rather than showing a broken-looking hole.
            bezel.setColor(Ui.CARD_HI);
            canvas.drawRoundRect(screen, screenRadius, screenRadius, bezel);
        }

        // The page's own proportion, printed under the bezel: a person choosing what
        // to flash wants to know it is this board's 320×240 and not a 3.5" panel.
        label.setColor(Ui.DIM);
        label.setTextSize(9 * context.getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(DECK_W + " × " + DECK_H + "  ·  " + title.toUpperCase(java.util.Locale.US),
                getWidth() / 2f, frame.bottom + Ui.dp(context, 18), label);
    }
}
