package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PAIRED: the live link, then every deck the app knows.
 *
 * <b>One list, two states.</b> The decks this phone has paired with come first, each
 * led by its own {@link DeckPlate} — a mark generated from the deck's id, so a new
 * board is recognisable on the day it is installed. A live deck carries a LIVE chip
 * beside its name; the decks it has never been paired with follow dimmed, including
 * any bundled deck advertising in range with nothing to link, so a board can still be
 * paired without a section of its own.
 *
 * <b>A deck carries a palette, and the deck is what wears it.</b> Each paired deck has
 * a {@link DeckLook} — one of the site's three palettes and one of its two pages — and
 * the byte that look encodes is written to the board, whose own {@code THEMES[]} table
 * has a row per palette per page. So the swatch changes the glass, and this row only
 * reports it: the ringed swatch is the palette that deck is set to, and the page circle
 * is the page the board answered with. Keeping the row in the app's inks is what lets a
 * list of three decks read as one list while each of their screens is a different
 * palette.
 *
 * The page half is the deck's, not the app's: a board's sketch carries two pages, and
 * the sun/crescent circle writes that one byte down the link. It is dimmed on a deck
 * with no link, because there is nothing to write to.
 *
 * The card that used to sit above this list said the same three facts as the row it
 * belonged to, so it is gone; the bridge card still opens the page with what the link
 * is doing.
 */
final class DecksTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout body;
    private final Supplier<DeckService.Snapshot> source;
    private String signature = "";
    private TextView liveLine;
    /** Called when a deck's plate is tapped: the activity opens the preview bubble. */
    private Preview preview;

    /** What a tap on a plate asks for: a deck's key, and where the plate is. */
    interface Preview {
        void open(String key, View plate);
    }

    DecksTab(Activity activity) {
        this(activity, DeckService::snapshot);
    }

    /** The app wires this once; a capture without it simply has no bubble. */
    void onPreview(Preview preview) {
        this.preview = preview;
    }

    /**
     * The monitor asks for its state instead of reaching for the running service, so a
     * capture can draw a linked deck with no radio behind it. The app always takes the
     * one-argument constructor above.
     */
    DecksTab(Activity activity, Supplier<DeckService.Snapshot> source) {
        super(activity);
        this.activity = activity;
        this.source = source;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 6),
                Ui.dp(activity, 16), Ui.dp(activity, 20));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void refresh() {
        DeckService.Snapshot snapshot = source.get();
        List<DeckStore.Known> known = DeckStore.all(activity);
        String structural = structural(snapshot, known);
        if (!structural.equals(signature)) {
            signature = structural;
            int keepScroll = getScrollY();
            build(snapshot, known);
            post(() -> scrollTo(0, keepScroll));
        }
        if (liveLine != null) liveLine.setText(live(snapshot));
    }

    private String structural(DeckService.Snapshot snapshot, List<DeckStore.Known> known) {
        StringBuilder sb = new StringBuilder();
        sb.append(snapshot.running).append('|').append(snapshot.linked).append('|')
                .append(snapshot.status).append('|').append(snapshot.deviceMac).append('|')
                .append(snapshot.deviceId).append('|')
                .append(snapshot.theme).append('|')
                .append(snapshot.rssi / 5).append('|');
        for (DeckService.Nearby nearby : snapshot.nearby) {
            sb.append(nearby.mac).append(':').append(nearby.rssi / 5).append(';');
        }
        sb.append('#');
        // The name and the look are in here on purpose: a rename writes to the board,
        // comes back in the identity document and moves nothing else, and a ringed
        // swatch is read when the row is built — without them the row would keep the old
        // label and the old ring until something unrelated changed.
        for (DeckStore.Known deck : known) {
            sb.append(deck.key()).append(':').append(deck.name).append(':')
                    .append(DeckStore.look(activity, deck.key()).encode()).append(';');
        }
        return sb.toString();
    }

    private String live(DeckService.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        if (!snapshot.running) return "Bridge stopped — start it from the side panel";
        if (snapshot.linked) {
            return "linked " + Ui.duration(snapshot.connectedAtMs, now)
                    + "  ·  " + snapshot.rssi + " dBm"
                    + "  ·  " + snapshot.writes + " packets"
                    + "  ·  " + snapshot.notices + " notices";
        }
        if (!snapshot.nearby.isEmpty()) {
            return "not linked  ·  " + snapshot.nearby.size() + " deck(s) in range";
        }
        return snapshot.status;
    }

    private void build(DeckService.Snapshot snapshot, List<DeckStore.Known> known) {
        body.removeAllViews();
        body.addView(Ui.title(activity, "Decks"));
        TextView sub = Ui.subtitle(activity, snapshot.linked
                ? "One link at a time. " + linkedName(snapshot) + " is on the cable."
                : "One link at a time. Nothing is linked right now.");
        sub.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 16));
        body.addView(sub);

        body.addView(bridgeCard(snapshot));

        body.addView(Ui.section(activity, "ALL DECKS"));
        if (known.isEmpty()) {
            body.addView(hint("No deck has been paired yet. Start the bridge and link one "
                    + "from the OFF list below."));
        } else {
            for (DeckStore.Known deck : known) {
                View card = deckCard(deck, snapshot);
                Ui.space(activity, card, 6);
                body.addView(card);
            }
        }
        body.addView(offList(snapshot, known));

        TextView note = hint("One deck is linked at a time, so a second CYD in range never "
                + "steals the connection. A swatch sets that deck's own palette and writes it "
                + "down the link; the page circle is the board's own, the same way.");
        note.setPadding(0, Ui.dp(activity, 14), 0, 0);
        body.addView(note);
    }

    /** The deck the bridge is talking to, for the subtitle. */
    private String linkedName(DeckService.Snapshot snapshot) {
        for (DeckStore.Known deck : DeckStore.all(activity)) {
            if (deck.mac.equalsIgnoreCase(snapshot.deviceMac)) return deck.name;
        }
        return "A deck";
    }

    /**
     * What the link is doing, as the study's metadata list: a status line, then
     * label/value rows. No card, no heading in caps — the title above already says
     * which screen this is.
     */
    private View bridgeCard(DeckService.Snapshot snapshot) {
        LinearLayout block = Ui.column(activity);
        block.setPadding(0, 0, 0, Ui.dp(activity, 6));

        LinearLayout statusLine = Ui.row(activity);
        TextView dot = Ui.text(activity, "●", 10, snapshot.running ? Ui.GOOD : Ui.DIM);
        statusLine.addView(dot);
        TextView heading = Ui.text(activity,
                snapshot.running ? "Bridge running" : "Bridge stopped", 12,
                snapshot.running ? Ui.TEXT : Ui.DIM);
        heading.setPadding(Ui.dp(activity, 7), 0, 0, 0);
        statusLine.addView(heading);
        block.addView(statusLine);

        liveLine = Ui.text(activity, live(snapshot), 12, Ui.DIM);
        liveLine.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 10));
        Ui.mono(liveLine);
        block.addView(liveLine);

        block.addView(Ui.rule(activity));
        block.addView(Ui.fact(activity, "Bridge",
                snapshot.running ? "Scanning" : "Stopped",
                snapshot.running ? Ui.TEXT : Ui.DIM));
        block.addView(Ui.rule(activity));
        block.addView(Ui.fact(activity, "Link",
                snapshot.linked ? snapshot.rssi + " dBm · " + snapshot.writes + " packets" : "—",
                snapshot.linked ? Ui.TEXT : Ui.DIM));
        block.addView(Ui.rule(activity));
        block.addView(Ui.fact(activity, "Notifications",
                snapshot.linked ? snapshot.notices + " this session" : "Not mirrored", Ui.DIM));
        block.addView(Ui.rule(activity));
        return block;
    }

    /**
     * One paired deck: its marquee, its name and what is known, then the four circles
     * and the actions the link allows — two rows, no third.
     */
    private View deckCard(DeckStore.Known deck, DeckService.Snapshot snapshot) {
        final boolean live = deck.mac.equalsIgnoreCase(snapshot.deviceMac) && snapshot.linked;
        final DeckLook look = DeckStore.look(activity, deck.key());
        // The board's own answer wins while it is the board being talked to: the page
        // circle shows what the deck is showing, not what was last asked for. A deck
        // flashed before the palettes reached the sketch answers 0 or 1, and then the
        // page comes from the stored look instead of from a row it does not have.
        final boolean light = live && DeckTheme.known(snapshot.theme)
                ? DeckTheme.page(snapshot.theme) : look.light;

        // A row, not a card: the study's list is one column of hairlines, and a
        // boxed row inside a boxed list is a border around a border.
        LinearLayout card = Ui.column(activity);
        card.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12));

        LinearLayout head = Ui.row(activity);
        // The plate replaces the marquee: a deck's own generated mark says which
        // board this is, and the LIVE chip beside the name says what the link is
        // doing. Two signals, one of them unique to the deck.
        DeckPlate plate = new DeckPlate(activity);
        plate.set(deck.key(), deck.tag.isEmpty() ? "" : deck.tag);
        plate.setBackground(Ui.rounded(activity, 0, Ui.EDGE, 0));
        plate.setContentDescription("Preview " + deck.name);
        plate.setOnClickListener(v -> {
            if (preview != null) preview.open(deck.key(), plate);
        });
        Ui.pressable(activity, plate, 0);
        LinearLayout.LayoutParams plateParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 46), Ui.dp(activity, 58));
        plateParams.rightMargin = Ui.dp(activity, 12);
        head.addView(plate, plateParams);

        LinearLayout labels = Ui.column(activity);
        TextView title = Ui.text(activity, deck.name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        labels.addView(title);
        final String where = deck.tag.isEmpty() ? deck.mac : deck.tag;
        // The build only comes from a live read, so a deck that is not linked right
        // now shows no version rather than the last one it happened to report.
        final String build = live ? SketchVersion.label(snapshot.deviceVersion) : "";
        TextView detail = Ui.text(activity,
                where + "  ·  " + (live ? "linked now"
                        : "paired " + Ui.ago(deck.seenMs, System.currentTimeMillis()))
                        + (build.isEmpty() ? "" : "  ·  " + build),
                11, Ui.DIM);
        Ui.mono(detail);
        labels.addView(detail);
        head.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (live) head.addView(Ui.pill(activity, "live", true));
        card.addView(head);

        LinearLayout foot = Ui.row(activity);
        foot.setPadding(0, Ui.dp(activity, 9), 0, 0);
        foot.addView(circles(deck, look, light, live));
        View spacer = new View(activity);
        foot.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(actionButtons(deck, live));
        card.addView(foot);
        card.addView(Ui.rule(activity));
        return card;
    }

    /**
     * The three swatches and the page circle.
     *
     * A swatch is the palette's band with a dot of the ink that sits on it, ringed when
     * it is the one this deck is set to — the same drawing as the app's own appearance
     * row, because it is the same choice made for a smaller thing. A tap stores the look
     * and writes its byte to the board: the swatch is a remote control for the deck's
     * own palette, not a repaint of this row.
     */
    private View circles(DeckStore.Known deck, DeckLook look, boolean light, boolean live) {
        LinearLayout row = Ui.row(activity);
        for (int i = 0; i < Palette.SCHEMES.length; ++i) {
            final Palette.Scheme scheme = Palette.SCHEMES[i];
            final int index = i;
            View swatch = AppearanceStrip.swatch(activity, scheme, i == look.palette, Ui.TEXT);
            swatch.setContentDescription("Palette " + scheme.id);
            swatch.setOnClickListener(v -> {
                DeckStore.setLook(activity, deck.key(), look.withPalette(index));
                signature = "";
                refresh();
            });
            Ui.pressable(activity, swatch, 13);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 26), Ui.dp(activity, 26));
            params.rightMargin = Ui.dp(activity, 8);
            row.addView(swatch, params);
        }

        View page = AppearanceStrip.page(activity, light, Ui.EDGE, Ui.TEXT);
        page.setContentDescription(light ? "Dark page" : "Light page");
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 26), Ui.dp(activity, 26));
        pageParams.leftMargin = Ui.dp(activity, 2);
        if (live) {
            page.setOnClickListener(v -> {
                DeckStore.setLook(activity, deck.key(), look.withLight(!light));
                DeckService.setDeckTheme(deck.mac, !light ? DeckTheme.PAPER : DeckTheme.INK);
                signature = "";
                refresh();
            });
            Ui.pressable(activity, page, 13);
        } else {
            // The page is a write to the board, and the link carries one deck at a
            // time: an unlinked row says what it needs instead of a circle that would
            // write nowhere.
            page.setAlpha(0.4f);
            page.setEnabled(false);
        }
        row.addView(page, pageParams);
        return row;
    }

    /** RENAME and UNLINK while the deck is linked; LINK and FORGET when it is not. */
    private View actionButtons(DeckStore.Known deck, boolean live) {
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.END);

        if (live) {
            Button rename = button("RENAME", false);
            rename.setOnClickListener(v -> rename(deck));
            row.addView(rename);

            Button unlink = button("UNLINK", false);
            unlink.setOnClickListener(v -> unlink(deck, true));
            row.addView(unlink, gap());
        } else {
            Button link = button("LINK", true);
            link.setOnClickListener(v -> DeckService.connectTo(deck.mac));
            row.addView(link);

            Button forget = button("FORGET", false);
            forget.setOnClickListener(v -> unlink(deck, false));
            row.addView(forget, gap());
        }
        return row;
    }

    /**
     * A deck the phone has no record of: the bundled sketches, and any board
     * advertising this minute. Dark marquee, no circles — there is nothing to remember
     * yet — and a LINK where there is an address to link to.
     */
    private View offList(DeckService.Snapshot snapshot, List<DeckStore.Known> known) {
        LinearLayout group = Ui.column(activity);

        Set<String> seenIds = new HashSet<>();
        Set<String> seenMacs = new HashSet<>();
        for (DeckStore.Known deck : known) {
            seenIds.add(deck.id);
            seenMacs.add(deck.mac.toUpperCase(Locale.US));
        }

        int rows = 0;
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(activity)) {
            if (!widget.usable() || seenIds.contains(widget.id)) continue;
            group.addView(offRow(widget.name.isEmpty() ? widget.id : widget.name,
                    widget.board.isEmpty() ? "never paired" : widget.board + " · never paired",
                    null));
            ++rows;
        }

        for (DeckService.Nearby entry : snapshot.nearby) {
            if (seenMacs.contains(entry.mac.toUpperCase(Locale.US))) continue;
            group.addView(offRow(entry.name,
                    entry.mac + "  ·  " + entry.rssi + " dBm  ·  in range", entry));
            ++rows;
        }

        if (rows == 0) {
            group.addView(hint(snapshot.running
                    ? "Every deck this app ships is paired with this phone, and nothing "
                            + "else is advertising."
                    : "Start the bridge from the side panel to scan for decks."));
        }
        return group;
    }

    /** One unpaired deck: an unlit strip, its name, what is known, and maybe a LINK. */
    private View offRow(String name, String detail, DeckService.Nearby advertising) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 10));

        // An unpaired deck still gets its plate, dimmed: the mark is the deck's,
        // not the link's, and a board that is switched off should still be
        // recognisable in the list.
        DeckPlate plate = new DeckPlate(activity);
        plate.set(name, "");
        plate.setAlpha(0.45f);
        plate.setBackground(Ui.rounded(activity, 0, Ui.EDGE, 0));
        plate.setContentDescription("Preview " + name);
        plate.setOnClickListener(v -> {
            if (preview != null) preview.open(name, plate);
        });
        Ui.pressable(activity, plate, 0);
        LinearLayout.LayoutParams plateParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 46), Ui.dp(activity, 58));
        plateParams.rightMargin = Ui.dp(activity, 12);
        row.addView(plate, plateParams);

        LinearLayout labels = Ui.column(activity);
        TextView title = Ui.text(activity, name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        labels.addView(title);
        TextView sub = Ui.text(activity, detail, 11, Ui.DIM);
        Ui.mono(sub);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (advertising != null) {
            final String mac = advertising.mac;
            Button link = Ui.action(activity, "LINK", true);
            link.setOnClickListener(v -> DeckService.connectTo(mac));
            row.addView(link);
        }

        LinearLayout cell = Ui.column(activity);
        cell.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(Ui.rule(activity));
        return cell;
    }

    /** The row's own button: 11sp in a hairline box, or the accent fill for LINK. */
    private Button button(String label, boolean primary) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Ui.medium(activity));
        button.setTextSize(11);
        button.setTextColor(primary ? Ui.ON_ACCENT : Ui.TEXT);
        button.setBackground(Ui.rounded(activity, primary ? Ui.ACCENT : Color.TRANSPARENT,
                primary ? 0 : Ui.EDGE, 0));
        Ui.pressable(activity, button, 0);
        button.setMinHeight(Ui.dp(activity, 34));
        button.setMinimumHeight(Ui.dp(activity, 34));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 4),
                Ui.dp(activity, 12), Ui.dp(activity, 4));
        button.setLetterSpacing(0.04f);
        return button;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(activity, 8);
        return params;
    }

    private TextView hint(String text) {
        TextView view = Ui.text(activity, text, 12, Ui.DIM);
        view.setLineSpacing(Ui.dp(activity, 2), 1f);
        return view;
    }

    /**
     * The dialog behind RENAME. Sixteen characters is the board's field width, so
     * the input cannot promise more than the write can keep; an empty field clears
     * the name and the row falls back to the tag.
     */
    private void rename(DeckStore.Known deck) {
        final EditText field = new EditText(activity);
        field.setHint("Name this deck");
        field.setSingleLine(true);
        field.setText(deck.name);
        field.setSelection(field.getText().length());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new InputFilter[] { new InputFilter.LengthFilter(16) });
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.DIM);
        new AlertDialog.Builder(activity)
                .setTitle("Name this deck")
                .setMessage("Up to 16 characters, kept on the board.")
                .setView(field)
                .setPositiveButton("SAVE", (dialog, which) ->
                        DeckService.rename(field.getText().toString()))
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /**
     * UNLINK and FORGET are one write and an optional disconnection, in that order:
     * the record is gone even when the bridge is stopped, so a forgotten deck stays
     * forgotten.
     */
    private void unlink(DeckStore.Known deck, boolean linked) {
        DeckStore.forget(activity, deck.key());
        if (!deck.id.isEmpty()) DeckRegistry.forget(activity, deck.id);
        if (linked) DeckService.unlink(activity, deck.mac);
        Actions.toast(activity, (linked ? "Unlinked " : "Forgot ") + deck.name);
        signature = "";
        refresh();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.VISIBLE) refresh();
    }
}
