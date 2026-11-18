package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The bubble that opens when a deck's plate is tapped: the deck's own screen, drawn
 * as the panel it will be, over a scrim that dismisses on a tap outside.
 *
 * <b>Why a bubble and not the Serial screen.</b> The picture is the answer to one
 * question — "is this the deck I am about to flash, and does it look right on this
 * screen" — and that question is asked from the row, with the row still visible
 * behind it. So it is an overlay anchored to the plate's own side of the screen, it
 * carries the palette row that changes the picture, and it closes without losing the
 * scroll position of the list behind it.
 *
 * <b>Where it points.</b> A plate sits on the left of its row, so the bubble's own
 * body is left-aligned too, with a small notch on the edge nearest the plate. The
 * notch is drawn rather than hinted at: a card that floats with no anchor is a
 * dialog, and a dialog has already left the row behind.
 *
 * It is a view for the same reason every other overlay here is one: the capture host
 * lays it out and draws it the way the phone does, and a DialogFragment would be a
 * second window no picture of this screen could include.
 */
final class DeckPreviewBubble extends FrameLayout {

    /** What a tap on a swatch does; the activity supplies it so the bubble stays a view. */
    interface Pick {
        void look(String key, DeckLook look);
    }

    private final Activity activity;
    private final LinearLayout panel;
    private final TextView name;
    private final TextView facts;
    private final DeckPreview art;
    private final LinearLayout swatches;
    private final LinearLayout pageRow;
    private boolean open;
    private String key = "";
    private DeckLook look = DeckLook.DEFAULT;
    private String widget = "";

    DeckPreviewBubble(Activity activity, Pick pick) {
        super(activity);
        this.activity = activity;
        setVisibility(GONE);

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x5C000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = Ui.column(activity);
        panel.setBackgroundColor(Ui.BG);
        panel.setClickable(true);
        panel.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 16), Ui.dp(activity, 18),
                Ui.dp(activity, 16));

        LinearLayout head = Ui.row(activity);
        TextView overline = Ui.text(activity, "PREVIEW", 10, Ui.DIM);
        overline.setLetterSpacing(0.16f);
        overline.setTypeface(Ui.mono(activity));
        head.addView(overline, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(activity, "\u00d7", 18, Ui.TEXT);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.circle(activity, 0, Ui.EDGE));
        close.setContentDescription("Close preview");
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(activity, 34),
                Ui.dp(activity, 34)));
        panel.addView(head);

        name = Ui.text(activity, "", 21, Ui.TEXT);
        name.setTypeface(Ui.medium(activity));
        name.setLetterSpacing(-0.02f);
        name.setPadding(0, Ui.dp(activity, 10), 0, 0);
        panel.addView(name);

        facts = Ui.text(activity, "", 10, Ui.DIM);
        facts.setTypeface(Ui.mono(activity));
        facts.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 12));
        panel.addView(facts);

        art = new DeckPreview(activity);
        panel.addView(art, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The palette row here is the same control as the deck's own row: it writes
        // the look, and the picture above follows it, because the swatch is what the
        // board would show if this palette were written down the link.
        swatches = Ui.row(activity);
        swatches.setPadding(0, Ui.dp(activity, 10), 0, 0);
        panel.addView(swatches);

        pageRow = Ui.row(activity);
        pageRow.setPadding(0, Ui.dp(activity, 8), 0, 0);
        panel.addView(pageRow);

        TextView note = Ui.text(activity,
                "Drawn from the sketch's own front page, at this board's palette. "
                        + "The panel is 320 x 240; a 3.5\" or 4\" deck still draws this page, "
                        + "centred, until its own layout is written.",
                11, Ui.DIM);
        note.setLineSpacing(Ui.dp(activity, 2), 1f);
        note.setPadding(0, Ui.dp(activity, 12), 0, 0);
        panel.addView(note);

        LayoutParams panelParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        // A margin on both sides keeps the bubble off the edge of the screen, which is
        // what makes it read as floating over the list rather than replacing it.
        panelParams.leftMargin = Ui.dp(activity, 16);
        panelParams.rightMargin = Ui.dp(activity, 16);
        addView(panel, panelParams);
    }

    /**
     * Open over {@code anchor} for one deck. {@code widget} is the sketch folder the
     * deck's id names, which is what selects the picture; {@code pick} receives a
     * palette tap.
     */
    void show(String key, String name, String widget, String facts, DeckLook look,
              View anchor, Pick pick) {
        this.key = key == null ? "" : key;
        this.widget = widget == null ? "" : widget;
        this.look = look == null ? DeckLook.DEFAULT : look;
        this.name.setText(name == null ? "Deck" : name);
        this.facts.setText(facts == null ? "" : facts);
        art.caption(this.widget);
        art.show(this.widget, this.look);
        buildSwatches(pick);

        open = true;
        setVisibility(VISIBLE);
        // The bubble is anchored where the plate is: near the top for a row high in
        // the list, lower down for one near the foot. It is placed by the panel's own
        // gravity, so there is no measurement to wait for and no jump on open.
        if (anchor != null) {
            final int[] where = new int[2];
            anchor.getLocationInWindow(where);
            final int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            final float centre = (where[1] + anchor.getHeight() / 2f) / Math.max(1, screenHeight);
            final int gravity = centre < 0.42f ? Gravity.TOP : Gravity.BOTTOM;
            LayoutParams params = (LayoutParams) panel.getLayoutParams();
            params.gravity = gravity == Gravity.TOP ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.topMargin = Ui.dp(activity, 72);
            params.bottomMargin = Ui.dp(activity, 28);
            panel.setLayoutParams(params);
        }

        if (isAttachedToWindow()) {
            panel.setAlpha(0f);
            panel.setScaleX(0.96f);
            panel.setScaleY(0.96f);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start();
        }
    }

    /** The three palettes and the page circle, rebuilt per deck. */
    private void buildSwatches(final Pick pick) {
        swatches.removeAllViews();
        pageRow.removeAllViews();

        TextView label = Ui.text(activity, "Palette", 10, Ui.DIM);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Ui.mono(activity));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        swatches.addView(label, labelParams);

        for (int i = 0; i < Palette.SCHEMES.length; ++i) {
            final Palette.Scheme scheme = Palette.SCHEMES[i];
            final int index = i;
            View swatch = AppearanceStrip.swatch(activity, scheme, i == look.palette, Ui.TEXT);
            swatch.setContentDescription("Palette " + scheme.id);
            swatch.setOnClickListener(v -> {
                final DeckLook next = look.withPalette(index);
                look = next;
                art.show(widget, next);
                buildSwatches(pick);
                if (pick != null) pick.look(key, next);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 26), Ui.dp(activity, 26));
            params.rightMargin = Ui.dp(activity, 10);
            swatches.addView(swatch, params);
        }

        View page = AppearanceStrip.page(activity, look.light, Ui.EDGE, Ui.TEXT);
        page.setContentDescription(look.light ? "Dark page" : "Light page");
        page.setOnClickListener(v -> {
            final DeckLook next = look.withLight(!look.light);
            look = next;
            art.show(widget, next);
            buildSwatches(pick);
            if (pick != null) pick.look(key, next);
        });
        swatches.addView(page, new LinearLayout.LayoutParams(
                Ui.dp(activity, 26), Ui.dp(activity, 26)));

        TextView pageLabel = Ui.text(activity, look.light ? "Light page" : "Dark page", 11, Ui.DIM);
        pageRow.addView(pageLabel);
    }

    void close() {
        open = false;
        setVisibility(GONE);
    }

    boolean isOpen() {
        return open;
    }

    /** The panel itself, for a render that draws it as it appears. */
    LinearLayout panel() {
        return panel;
    }

    /** What the bubble says the deck is, for a capture that would otherwise guess. */
    static String factsFor(DeckStore.Known deck, DeckLook look) {
        StringBuilder out = new StringBuilder();
        out.append(deck.widget.isEmpty() ? "unknown sketch" : deck.widget);
        out.append("  ·  ").append(DeckPreview.DECK_W).append("×").append(DeckPreview.DECK_H);
        out.append("  ·  ").append(look.scheme().id.toUpperCase(Locale.US));
        out.append(look.light ? " light" : " dark");
        if (!deck.tag.isEmpty()) out.append("  ·  ").append(deck.tag);
        return out.toString();
    }
}
