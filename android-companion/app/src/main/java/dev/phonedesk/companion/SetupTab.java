package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings section: the two permissions that matter, background survival, and
 * the bridge start/stop switch.
 */
final class SetupTab extends ScrollView {

    private final Activity activity;
    private final TextView statusText;
    private final TextView accessText;
    private final TextView bluetoothText;
    /** What the per-app allowlist currently lets through. */
    private final TextView noticeText;
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

        body.addView(Ui.text(activity, "SETTINGS", 20, Ui.TEXT));

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

        body.addView(Ui.section(activity, "NOTIFICATIONS"));

        LinearLayout noticeCard = Ui.card(activity);
        noticeText = Ui.text(activity, "", 13, Ui.DIM);
        noticeCard.addView(noticeText);

        TextView noticeHint = Ui.text(activity,
                "The deck sits where other people can read it. Pick the apps it may show, "
                        + "and a one-time code or a private message stays on the phone.",
                11, Ui.DIM);
        noticeHint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        noticeHint.setLineSpacing(Ui.dp(activity, 2), 1f);
        noticeCard.addView(noticeHint);

        LinearLayout noticeActions = Ui.row(activity);
        noticeActions.setPadding(0, Ui.dp(activity, 10), 0, 0);
        Button chooseApps = Ui.action(activity, "CHOOSE APPS", false);
        chooseApps.setOnClickListener(v -> chooseApps());
        noticeActions.addView(chooseApps, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button allowAll = Ui.action(activity, "ALL APPS", false);
        allowAll.setTextColor(Ui.DIM);
        allowAll.setOnClickListener(v -> {
            NoticeFilter.allowAll(activity);
            refresh();
        });
        LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        allowParams.leftMargin = Ui.dp(activity, 8);
        noticeActions.addView(allowAll, allowParams);
        noticeCard.addView(noticeActions);

        Ui.space(activity, noticeCard, 10);
        body.addView(noticeCard);

        body.addView(Ui.section(activity, "BACKGROUND"));

        LinearLayout backgroundCard = Ui.card(activity);
        backgroundCard.addView(Ui.text(activity,
                "Android may stop the bridge overnight. Exempting it from battery optimisation "
                        + "keeps the deck updating with the screen off.",
                13, Ui.DIM));
        Button batteryButton = Ui.action(activity, "BATTERY SETTINGS", false);
        batteryButton.setOnClickListener(v -> {
            Context context = activity.getApplicationContext();
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null && power.isIgnoringBatteryOptimizations(context.getPackageName())) {
                // Already exempt: the settings list is where that can be undone.
                try {
                    activity.startActivity(new Intent(
                            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                    // Nothing else to offer.
                }
                return;
            }
            // Ask for this app's exemption directly. The settings list works too,
            // but leaves the user hunting for one name among every app installed.
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.getPackageName())));
                return;
            } catch (Exception e) {
                // Some builds refuse the direct request; the list still works.
            }
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

        body.addView(Ui.section(activity, "APPEARANCE"));

        LinearLayout themeCard = Ui.card(activity);
        themeCard.addView(Ui.text(activity,
                "Two themes over the same screens: black ink on an off-white page, and the "
                        + "same page inverted. The band stays light either way, because it is "
                        + "the ground the mark was drawn on.",
                13, Ui.DIM));
        LinearLayout themeActions = Ui.row(activity);
        themeActions.setPadding(0, Ui.dp(activity, 10), 0, 0);

        // The live palette is the filled button, so the row says which one is on
        // without a second label. Switching rebuilds the activity: a colour here is
        // read when a view is constructed, and screens already built keep theirs.
        final int live = Appearance.get(activity);
        Button dark = Ui.action(activity, "DARK", live == Ui.DARK);
        dark.setOnClickListener(v -> pickTheme(Ui.DARK));
        themeActions.addView(dark, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button light = Ui.action(activity, "LIGHT", live == Ui.LIGHT);
        light.setOnClickListener(v -> pickTheme(Ui.LIGHT));
        LinearLayout.LayoutParams lightParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lightParams.leftMargin = Ui.dp(activity, 8);
        themeActions.addView(light, lightParams);

        themeCard.addView(themeActions);
        Ui.space(activity, themeCard, 10);
        body.addView(themeCard);

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
                        + "A stale \"Phone Deck\" entry from an older build can be forgotten.",
                12, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 18), 0, 0);
        body.addView(note);

        crashCard = Ui.card(activity);
        crashCard.setVisibility(GONE);
        Ui.space(activity, crashCard, 18);
        body.addView(crashCard);
    }

    /**
     * Store the choice, tell the linked deck and rebuild, so every screen is drawn
     * in the palette picked. One theme over both ends is the point: the board is
     * the half a person looks at from across the room. A deck that should stay on
     * the other palette gets it from its own pair on PAIRED.
     */
    private void pickTheme(int theme) {
        if (theme == Appearance.get(activity)) return;
        Appearance.set(activity, theme);
        DeckService.pushTheme(theme);
        activity.recreate();
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

        if (!NoticeFilter.customised(activity)) {
            noticeText.setText("Apps allowed: all of them");
            noticeText.setTextColor(Ui.DIM);
        } else {
            int count = NoticeFilter.allowed(activity).size();
            noticeText.setText(count == 0
                    ? "Apps allowed: none — nothing reaches the deck"
                    : "Apps allowed: " + count + (count == 1 ? " app" : " apps"));
            noticeText.setTextColor(count == 0 ? Ui.PINK : Ui.GOOD);
        }

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

    /**
     * The allowlist picker. A checked row means "this app may reach the deck";
     * the first SAVE is what turns filtering on, so a fresh install still works
     * the way it did before the filter existed.
     */
    private void chooseApps() {
        final List<NoticeFilter.App> apps = NoticeFilter.launchable(activity);
        if (apps.isEmpty()) {
            Actions.toast(activity, "No apps to list");
            return;
        }

        final String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        final Set<String> allowed = NoticeFilter.allowed(activity);
        final boolean custom = NoticeFilter.customised(activity);
        for (int i = 0; i < apps.size(); ++i) {
            labels[i] = apps.get(i).label;
            checked[i] = !custom || allowed.contains(apps.get(i).packageName);
        }

        new AlertDialog.Builder(activity)
                .setTitle("Apps the deck may show")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    Set<String> chosen = new HashSet<>();
                    for (int i = 0; i < apps.size(); ++i) {
                        if (checked[i]) chosen.add(apps.get(i).packageName);
                    }
                    NoticeFilter.allow(activity, chosen);
                    refresh();
                })
                .setNeutralButton("ALL APPS", (dialog, which) -> {
                    NoticeFilter.allowAll(activity);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) refresh();
    }
}
