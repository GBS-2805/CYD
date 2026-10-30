package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Live monitor for CYD decks: what is linked, how healthy the link is, what is
 * advertising nearby, and which decks the phone has met before.
 */
final class DecksTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout body;
    private String signature = "";
    private TextView liveLine;

    DecksTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void refresh() {
        DeckService.Snapshot snapshot = DeckService.snapshot();
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
                .append(snapshot.rssi / 5).append('|');
        for (DeckService.Nearby nearby : snapshot.nearby) {
            sb.append(nearby.mac).append(':').append(nearby.rssi / 5).append(';');
        }
        sb.append('#');
        for (DeckStore.Known deck : known) sb.append(deck.mac).append(';');
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
            TextView mac = Ui.text(activity, snapshot.deviceMac + "  ·  last packet "
                    + Ui.ago(snapshot.lastWriteMs, System.currentTimeMillis()), 12, Ui.DIM);
            mac.setPadding(0, Ui.dp(activity, 4), 0, 0);
            deck.addView(mac);
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
        TextView detail = Ui.text(activity,
                deck.mac + "  ·  last linked " + Ui.ago(deck.seenMs, System.currentTimeMillis()),
                12, Ui.DIM);
        detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
        labels.addView(detail);

        card.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (deck.mac.equalsIgnoreCase(snapshot.deviceMac)) {
            card.addView(Ui.text(activity, "LINKED", 11, Ui.GOOD));
        } else {
            Button link = Ui.action(activity, "LINK", false);
            link.setOnClickListener(v -> DeckService.connectTo(deck.mac));
            card.addView(link);
        }
        Ui.space(activity, card, 8);
        body.addView(card);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.VISIBLE) refresh();
    }
}
