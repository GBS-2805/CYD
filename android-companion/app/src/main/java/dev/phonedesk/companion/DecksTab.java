package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Supplier;

/**
 * Live monitor for CYD decks: what is linked, how healthy the link is, what is
 * advertising nearby, and which decks the phone has met before.
 */
final class DecksTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout body;
    private final Supplier<DeckService.Snapshot> source;
    private String signature = "";
    private TextView liveLine;

    DecksTab(Activity activity) {
        this(activity, DeckService::snapshot);
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
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
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
        for (DeckStore.Known deck : known) sb.append(deck.key()).append(';');
        return sb.toString();
    }

    private String live(DeckService.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        if (!snapshot.running) return "Bridge stopped — start it from Setup";
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

        LinearLayout status = Ui.card(activity);
        TextView heading = Ui.text(activity, snapshot.running ? "BRIDGE ACTIVE" : "BRIDGE STOPPED",
                11, snapshot.running ? Ui.GOOD : Ui.PINK);
        heading.setLetterSpacing(0.14f);
        status.addView(heading);

        liveLine = Ui.text(activity, live(snapshot), 14, Ui.TEXT);
        liveLine.setPadding(0, Ui.dp(activity, 6), 0, 0);
        status.addView(liveLine);

        TextView statusText = Ui.text(activity, snapshot.status, 12, Ui.DIM);
        statusText.setPadding(0, Ui.dp(activity, 6), 0, 0);
        status.addView(statusText);

        body.addView(status);

        if (snapshot.linked) {
            LinearLayout deck = Ui.card(activity);
            deck.addView(Ui.text(activity, snapshot.deviceName, 18, Ui.TEXT));
            // Tag first: it is the half of the id that cannot move, and the MAC
            // below it is only the address this connection happened over.
            final String where = snapshot.deviceTag.isEmpty()
                    ? snapshot.deviceMac
                    : snapshot.deviceTag + " \u00B7 " + snapshot.deviceMac;
            final String palette = snapshot.theme == DeckTheme.UNKNOWN
                    ? "" : " \u00B7 " + DeckTheme.name(snapshot.theme);
            TextView mac = Ui.text(activity, where + palette + " \u00B7 packet "
                    + Ui.ago(snapshot.lastWriteMs, System.currentTimeMillis()), 12, Ui.DIM);
            mac.setPadding(0, Ui.dp(activity, 4), 0, 0);
            deck.addView(mac);
            // The way out of a link from the card that shows it, so an unlink never
            // depends on finding the row for this board further down the page.
            Button unlink = Ui.action(activity, "UNLINK", false);
            unlink.setOnClickListener(v -> unlinkLinked(snapshot));
            Ui.space(activity, unlink, 10);
            deck.addView(unlink);
            Ui.space(activity, deck, 10);
            body.addView(deck);
        }

        body.addView(Ui.section(activity, "NEARBY"));
        if (snapshot.nearby.isEmpty()) {
            body.addView(Ui.text(activity, snapshot.running
                    ? "No decks advertising right now."
                    : "Start the bridge to scan for decks.", 13, Ui.DIM));
        } else {
            for (DeckService.Nearby entry : snapshot.nearby) addNearbyRow(entry, snapshot);
        }

        body.addView(Ui.section(activity, "SAVED DECKS"));
        if (known.isEmpty()) {
            body.addView(Ui.text(activity,
                    "Decks appear here after the first successful link.", 13, Ui.DIM));
        } else {
            for (DeckStore.Known deck : known) addKnownRow(deck, snapshot);
        }

        TextView hint = Ui.text(activity,
                "One deck is linked at a time. Linking here pins the bridge to that board, "
                        + "so a second CYD in range never steals the connection.",
                12, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 18), 0, 0);
        body.addView(hint);
    }

    private void addNearbyRow(DeckService.Nearby entry, DeckService.Snapshot snapshot) {
        LinearLayout card = Ui.row(activity);
        card.setBackground(Ui.rounded(activity, Ui.CARD, Ui.EDGE, 12));
        card.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10),
                Ui.dp(activity, 10), Ui.dp(activity, 10));

        LinearLayout labels = Ui.column(activity);
        labels.addView(Ui.text(activity, entry.name, 15, Ui.TEXT));
        TextView detail = Ui.text(activity,
                entry.mac + "  ·  " + entry.rssi + " dBm  ·  " + Ui.ago(entry.seenMs,
                        System.currentTimeMillis()), 12, Ui.DIM);
        detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
        labels.addView(detail);

        card.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (entry.mac.equalsIgnoreCase(snapshot.deviceMac)) {
            TextView badge = Ui.text(activity, "LINKED", 11, Ui.GOOD);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(badge);
        } else {
            Button link = Ui.action(activity, "LINK", true);
            link.setOnClickListener(v -> DeckService.connectTo(entry.mac));
            card.addView(link);
        }

        Ui.space(activity, card, 8);
        body.addView(card);
    }

    private void addKnownRow(DeckStore.Known deck, DeckService.Snapshot snapshot) {
        LinearLayout card = Ui.row(activity);
        card.setBackground(Ui.rounded(activity, Ui.CARD, Ui.EDGE, 12));
        card.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10),
                Ui.dp(activity, 10), Ui.dp(activity, 10));

        LinearLayout labels = Ui.column(activity);
        labels.addView(Ui.text(activity, deck.name, 15, Ui.TEXT));
        // One row per board, keyed by widget and tag: the tag is the half of the id
        // that cannot move. The address is in the card above, where the link is.
        final String where = deck.tag.isEmpty() ? deck.mac : deck.tag;
        TextView detail = Ui.text(activity,
                where + " \u00B7 linked "
                        + Ui.ago(deck.seenMs, System.currentTimeMillis()),
                12, Ui.DIM);
        detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
        labels.addView(detail);
        final boolean linked = deck.mac.equalsIgnoreCase(snapshot.deviceMac);
        labels.addView(themePair(deck, snapshot, linked));

        card.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (linked) {
            // The card above already says LINKED; here the useful control is the way
            // out, which drops the link and forgets the row in one tap.
            Button unlink = Ui.action(activity, "UNLINK", false);
            unlink.setOnClickListener(v -> unlink(deck, true));
            card.addView(unlink);
        } else {
            Button link = Ui.action(activity, "LINK", false);
            link.setOnClickListener(v -> DeckService.connectTo(deck.mac));
            card.addView(link);

            Button forget = Ui.action(activity, "FORGET", false);
            forget.setOnClickListener(v -> unlink(deck, false));
            LinearLayout.LayoutParams spare =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            spare.leftMargin = Ui.dp(activity, 8);
            card.addView(forget, spare);
        }
        Ui.space(activity, card, 8);
        body.addView(card);
    }

    /**
     * The palette pair for one deck. Every row carries it, because every board
     * keeps its own palette in NVS; only the linked one can be written, since the
     * link carries one deck at a time, so an unlinked row says what it needs first
     * instead of a chip that would write nowhere.
     */
    private LinearLayout themePair(DeckStore.Known deck, DeckService.Snapshot snapshot,
                                   boolean linked) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 8), 0, 0);
        final int live = linked ? snapshot.theme : DeckTheme.UNKNOWN;
        row.addView(chip(DeckTheme.PAPER, live, deck, linked));
        row.addView(chip(DeckTheme.INK, live, deck, linked));
        if (!linked) {
            TextView hint = Ui.text(activity, "link to set", 11, Ui.DIM);
            hint.setPadding(Ui.dp(activity, 8), 0, 0, 0);
            row.addView(hint);
        }
        return row;
    }

    /** One palette chip: filled when the deck is on it, dead when the deck is not linked. */
    private Button chip(int theme, int live, DeckStore.Known deck, boolean linked) {
        Button chip = Ui.action(activity, DeckTheme.name(theme), live == theme);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(activity, 8);
        chip.setLayoutParams(params);
        if (linked) {
            chip.setOnClickListener(v -> DeckService.setDeckTheme(deck.mac, theme));
        } else {
            chip.setAlpha(0.45f);
            chip.setEnabled(false);
        }
        return chip;
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

    /** The card above carries no {@link DeckStore.Known}, so it forgets by address. */
    private void unlinkLinked(DeckService.Snapshot snapshot) {
        DeckStore.Known known = DeckStore.byMac(activity, snapshot.deviceMac);
        if (known != null) {
            unlink(known, true);
            return;
        }
        DeckStore.forget(activity, snapshot.deviceMac);
        if (!snapshot.deviceId.isEmpty()) DeckRegistry.forget(activity, snapshot.deviceId);
        DeckService.unlink(activity, snapshot.deviceMac);
        Actions.toast(activity, "Unlinked " + snapshot.deviceName);
        signature = "";
        refresh();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.VISIBLE) refresh();
    }
}
