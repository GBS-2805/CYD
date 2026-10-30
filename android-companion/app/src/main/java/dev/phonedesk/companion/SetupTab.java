package dev.phonedesk.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Setup tab: the two permissions that matter, background survival, and the
 * bridge start/stop switch.
 */
final class SetupTab extends ScrollView {

    private final Activity activity;
    private final TextView statusText;
    private final TextView accessText;
    private final TextView bluetoothText;
    private final Button toggle;
    /** Only shown when the previous run left a trace in {@link CrashLog}. */
    private final LinearLayout crashCard;
    private String crashShown;

    SetupTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);

        LinearLayout body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        body.addView(Ui.text(activity, "SETUP", 20, Ui.TEXT));

        LinearLayout statusCard = Ui.card(activity);
        statusText = Ui.text(activity, DeckService.getStatus(), 14, Ui.ACCENT);
        statusCard.addView(statusText);
        Ui.space(activity, statusCard, 12);
        body.addView(statusCard);

        body.addView(Ui.section(activity, "PERMISSIONS"));

        LinearLayout accessCard = Ui.card(activity);
        accessText = Ui.text(activity, "", 13, Ui.DIM);
        accessCard.addView(accessText);
        Button accessButton = Ui.action(activity, "NOTIFICATION ACCESS", false);
        accessButton.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        Ui.space(activity, accessButton, 10);
        accessCard.addView(accessButton);
        Ui.space(activity, accessCard, 0);
        body.addView(accessCard);

        LinearLayout bluetoothCard = Ui.card(activity);
        bluetoothText = Ui.text(activity, "", 13, Ui.DIM);
        bluetoothCard.addView(bluetoothText);
        Button bluetoothButton = Ui.action(activity, "BLUETOOTH PERMISSIONS", false);
        bluetoothButton.setOnClickListener(v -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).requestBluetoothPermissions();
            }
        });
        Ui.space(activity, bluetoothButton, 10);
        bluetoothCard.addView(bluetoothButton);
        Ui.space(activity, bluetoothCard, 10);
        body.addView(bluetoothCard);

        body.addView(Ui.section(activity, "BACKGROUND"));

        LinearLayout backgroundCard = Ui.card(activity);
        backgroundCard.addView(Ui.text(activity,
                "Android may stop the bridge overnight. Exempting it from battery optimisation "
                        + "keeps the deck updating with the screen off.",
                13, Ui.DIM));
        Button batteryButton = Ui.action(activity, "BATTERY SETTINGS", false);
        batteryButton.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                    // Nothing else to offer.
                }
            }
        });
        Ui.space(activity, batteryButton, 10);
        backgroundCard.addView(batteryButton);
        Ui.space(activity, backgroundCard, 10);
        body.addView(backgroundCard);

        body.addView(Ui.section(activity, "BRIDGE"));

        toggle = Ui.action(activity, "START BRIDGE", true);
        toggle.setOnClickListener(v -> {
            if (DeckService.isRunning()) {
                DeckService.stop(activity);
            } else if (DeckService.bluetoothReady(activity)) {
                DeckService.start(activity);
            } else {
                bluetoothText.setText("Bluetooth permission: missing");
            }
        });
        body.addView(toggle);

        TextView note = Ui.text(activity,
                "The deck never appears in the system Bluetooth list: one service, no pairing. "
                        + "A stale \"ESP32 Phone_Widget\" entry from an older build can be forgotten.",
                12, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 18), 0, 0);
        body.addView(note);

        crashCard = Ui.card(activity);
        crashCard.setVisibility(GONE);
        Ui.space(activity, crashCard, 18);
        body.addView(crashCard);
    }

    void refresh() {
        boolean running = DeckService.isRunning();
        statusText.setText(DeckService.getStatus());
        statusText.setTextColor(running ? Ui.ACCENT : Ui.DIM);

        boolean access = DeckService.notificationAccess(activity);
        accessText.setText(access
                ? "Notification access: granted"
                : "Notification access: not granted — no notifications will reach the deck");
        accessText.setTextColor(access ? Ui.GOOD : Ui.PINK);

        boolean bluetooth = DeckService.bluetoothReady(activity);
        bluetoothText.setText(bluetooth
                ? "Bluetooth: granted"
                : "Bluetooth: not granted — the bridge cannot scan");
        bluetoothText.setTextColor(bluetooth ? Ui.GOOD : Ui.PINK);

        toggle.setText(running ? "STOP BRIDGE" : "START BRIDGE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bluetooth) {
            toggle.setText("START BRIDGE");
        }

        refreshCrashCard();
    }

    /**
     * Surfaces the last crash so it can be read and copied off the phone. Rebuilt
     * only when the stored trace changes: this runs once a second.
     */
    private void refreshCrashCard() {
        String trace = CrashLog.read(activity);
        if (trace == null || trace.trim().isEmpty()) {
            crashCard.setVisibility(GONE);
            crashShown = null;
            return;
        }
        if (trace.equals(crashShown)) {
            crashCard.setVisibility(VISIBLE);
            return;
        }
        crashShown = trace;
        crashCard.removeAllViews();
        crashCard.setVisibility(VISIBLE);

        TextView tag = Ui.text(activity, "LAST CRASH", 11, Ui.PINK);
        tag.setLetterSpacing(0.14f);
        crashCard.addView(tag);

        TextView hint = Ui.text(activity,
                "The app closed unexpectedly. This is the trace from that run; COPY puts it on "
                        + "the clipboard so it can be sent along.", 11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        crashCard.addView(hint);

        final String shown = trace.length() > 4000 ? trace.substring(0, 4000) : trace;
        TextView detail = Ui.text(activity, shown.trim(), 10, Ui.TEXT);
        detail.setPadding(0, Ui.dp(activity, 10), 0, 0);
        detail.setLineSpacing(Ui.dp(activity, 2), 1f);
        crashCard.addView(detail);

        Button copy = Ui.action(activity, "COPY TRACE", true);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("crash trace", shown));
            android.widget.Toast.makeText(activity, "Trace copied",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        Ui.space(activity, copy, 10);
        crashCard.addView(copy);

        Button clear = Ui.action(activity, "CLEAR", false);
        clear.setOnClickListener(v -> {
            CrashLog.clear(activity);
            crashShown = null;
            refreshCrashCard();
        });
        Ui.space(activity, clear, 8);
        crashCard.addView(clear);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) refresh();
    }
}
