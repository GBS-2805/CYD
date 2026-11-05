package dev.phonedesk.companion;

import android.app.Activity;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * The bridge sheet: whether the radio is up, what it is doing, and the one control
 * that starts or stops it.
 *
 * It was a button at the bottom of Settings, under a page of permission cards, which
 * meant the app's main switch was the last thing on the longest screen. The bridge is
 * the app's job; it gets its own sheet, and its state is the first thing on it.
 */
final class BridgeSheet extends ScrollView {

    private final Activity activity;
    private final TextView state;
    private final TextView line;
    private final TextView detail;
    private final Button toggle;

    BridgeSheet(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);

        LinearLayout body = Ui.column(activity);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        state = Ui.text(activity, "BRIDGE STOPPED", 11, Ui.DIM);
        state.setLetterSpacing(0.14f);
        state.setTypeface(Ui.medium(activity));
        body.addView(state);

        line = Ui.text(activity, "", 18, Ui.TEXT);
        line.setPadding(0, Ui.dp(activity, 8), 0, 0);
        body.addView(line);

        detail = Ui.text(activity, "", 13, Ui.DIM);
        detail.setPadding(0, Ui.dp(activity, 6), 0, 0);
        detail.setLineSpacing(Ui.dp(activity, 2), 1f);
        body.addView(detail);

        toggle = Ui.action(activity, "START BRIDGE", true);
        toggle.setOnClickListener(v -> {
            if (DeckService.isRunning()) {
                DeckService.stop(activity);
            } else if (DeckService.bluetoothReady(activity)) {
                DeckService.start(activity);
            } else {
                detail.setText("Bluetooth permission is missing. SETTINGS lists it with the "
                        + "button that grants it.");
            }
            refresh();
        });
        Ui.space(activity, toggle, 18);
        body.addView(toggle);

        TextView note = Ui.text(activity,
                "The deck never appears in the system Bluetooth list: one service, no pairing. "
                        + "A stale \u201cPhone Deck\u201d entry from an older build can be forgotten "
                        + "in Bluetooth settings.", 13, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 16), 0, 0);
        note.setLineSpacing(Ui.dp(activity, 2), 1f);
        body.addView(note);
    }

    void refresh() {
        final DeckService.Snapshot snapshot = DeckService.snapshot();
        final boolean running = DeckService.isRunning();

        state.setText(running ? "BRIDGE ACTIVE" : "BRIDGE STOPPED");
        state.setTextColor(running ? Ui.GOOD : Ui.DIM);

        if (snapshot.linked) {
            line.setText("Linked to " + snapshot.deviceName);
        } else if (running) {
            line.setText(snapshot.nearby.isEmpty()
                    ? "Scanning for decks" : snapshot.nearby.size() + " deck(s) in range");
        } else {
            line.setText("Not running");
        }

        detail.setText(DeckService.getStatus());
        toggle.setText(running ? "STOP BRIDGE" : "START BRIDGE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !DeckService.bluetoothReady(activity)) {
            toggle.setText("GRANT BLUETOOTH FIRST");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }
}
