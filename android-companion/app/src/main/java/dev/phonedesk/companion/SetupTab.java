package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings: every permission this app can hold, in one list, then the notification
 * filter and the last crash.
 *
 * The permissions used to be two cards at the top and a battery card further down,
 * with the bridge at the bottom; the bridge has its own sheet now, and what is left
 * is the part that is genuinely setup. Each row carries an LED — lit when the
 * permission is held, dark when it is not — so the state of all five is readable by
 * scanning one column rather than by reading five sentences.
 *
 * The LED is also why an error is not written in a red the site does not have: the
 * lit/off square is the state, and the words say what is missing.
 */
final class SetupTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout permissions;
    /** What the per-app allowlist currently lets through. */
    private final TextView noticeText;
    /** Only shown when the previous run left a trace in {@link CrashLog}. */
    private final LinearLayout crashCard;
    private String crashShown;
    private String permissionSignature = "";

    SetupTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);

        LinearLayout body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 12), Ui.dp(activity, 18),
                Ui.dp(activity, 28));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(activity, "Settings", 26, Ui.TEXT);
        title.setLetterSpacing(-0.01f);
        body.addView(title);

        body.addView(Ui.section(activity, "ALL PERMISSIONS"));
        permissions = Ui.column(activity);
        body.addView(permissions);

        body.addView(Ui.section(activity, "NOTIFICATIONS"));

        LinearLayout noticeCard = Ui.card(activity);
        noticeText = Ui.text(activity, "", 15, Ui.TEXT);
        noticeCard.addView(noticeText);

        TextView noticeHint = Ui.text(activity,
                "The deck sits where other people can read it. Pick the apps it may show, "
                        + "and a one-time code or a private message stays on the phone.",
                13, Ui.DIM);
        noticeHint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        noticeHint.setLineSpacing(Ui.dp(activity, 2), 1f);
        noticeCard.addView(noticeHint);

        LinearLayout noticeActions = Ui.row(activity);
        noticeActions.setPadding(0, Ui.dp(activity, 14), 0, 0);
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

        Ui.space(activity, noticeCard, 12);
        body.addView(noticeCard);

        crashCard = Ui.card(activity);
        crashCard.setVisibility(GONE);
        Ui.space(activity, crashCard, 18);
        body.addView(crashCard);

        // Built once here rather than on the first refresh: the activity restores
        // each screen's scroll offset from its own saved state while the view tree
        // is rebuilt, and a scroll view whose child is still empty clamps that
        // offset to zero. A screen that fills itself in a posted refresh would come
        // back at the top however far down it was.
        buildPermissions();
    }

    // -------------------------------------------------------- permissions ---

    /**
     * The five rows, rebuilt only when one of them changes: this runs once a second
     * while the screen is on show.
     */
    private void buildPermissions() {
        final boolean access = DeckService.notificationAccess(activity);
        final boolean bluetooth = DeckService.bluetoothReady(activity);
        final boolean notify = noticesGranted();
        final boolean battery = batteryExempt();

        final String signature = access + "|" + bluetooth + "|" + notify + "|" + battery;
        if (signature.equals(permissionSignature) && permissions.getChildCount() > 0) return;
        permissionSignature = signature;
        permissions.removeAllViews();

        permissions.addView(permission("Notification access",
                "what the deck is allowed to show", access, "OPEN", this::openNotificationAccess));
        permissions.addView(permission("Bluetooth",
                bluetoothDetail(), bluetooth, "GRANT", this::grantBluetooth));
        permissions.addView(permission("Notifications",
                "posts the bridge's own updates", notify, "GRANT",
                this::grantNotifications));
        permissions.addView(permission("Battery",
                "keeps the bridge alive with the screen off", battery, "ALLOW",
                this::askBattery));
        permissions.addView(permission("USB device",
                "asked each time a cable is plugged", null, "", null));
    }

    /**
     * One permission: a lit or dark LED, its name, the state in words, and the
     * button that grants it. {@code granted == null} is a fact rather than a
     * permission — USB access is asked per plug — so its LED stays neutral instead of
     * claiming an answer.
     */
    private LinearLayout permission(String name, String detail, Boolean granted,
                                    String action, Runnable tap) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12));

        View led = new View(activity);
        led.setBackground(Ui.circle(activity, granted == null ? 0
                        : (granted ? Ui.ACCENT : 0), granted != null && granted ? 0 : Ui.EDGE));
        LinearLayout.LayoutParams ledParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 8), Ui.dp(activity, 8));
        ledParams.rightMargin = Ui.dp(activity, 14);
        row.addView(led, ledParams);

        LinearLayout labels = Ui.column(activity);
        TextView title = Ui.text(activity, name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        labels.addView(title);
        TextView sub = Ui.text(activity, detail, 13, Ui.DIM);
        sub.setPadding(0, Ui.dp(activity, 2), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!action.isEmpty()) {
            TextView chip = Ui.chip(activity, action, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 7), Ui.dp(activity, 12),
                    Ui.dp(activity, 7));
            if (tap != null) chip.setOnClickListener(v -> tap.run());
            Ui.pressable(activity, chip, 0);
            row.addView(chip);
        }

        LinearLayout cell = Ui.column(activity);
        if (tap != null) {
            row.setOnClickListener(v -> tap.run());
            Ui.pressable(activity, row, 0);
        }
        cell.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(Ui.rule(activity));
        return cell;
    }

    private String bluetoothDetail() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return "scan and connect to a deck";
        }
        return "location, for a scan on this version of Android";
    }

    private boolean noticesGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryExempt() {
        PowerManager power = (PowerManager) activity.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(
                activity.getPackageName());
    }

    private void openNotificationAccess() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void grantBluetooth() {
        if (activity instanceof MainActivity) ((MainActivity) activity).requestBluetoothPermissions();
    }

    private void grantNotifications() {
        if (activity instanceof MainActivity) ((MainActivity) activity).requestNotifications();
    }

    /**
     * Ask for this app's exemption directly. The settings list works too, but leaves
     * the user hunting for one name among every app installed.
     */
    private void askBattery() {
        final Context context = activity.getApplicationContext();
        if (batteryExempt()) {
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                // Nothing else to offer.
            }
            return;
        }
        try {
            activity.startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName())));
            return;
        } catch (Exception e) {
            // Some builds refuse the direct request; the list still works.
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                // Nothing else to offer.
            }
        }
    }

    // ------------------------------------------------------------ refresh ---

    void refresh() {
        buildPermissions();

        if (!NoticeFilter.customised(activity)) {
            noticeText.setText("Apps allowed: all of them");
            noticeText.setTextColor(Ui.TEXT);
        } else {
            int count = NoticeFilter.allowed(activity).size();
            noticeText.setText(count == 0
                    ? "Apps allowed: none — nothing reaches the deck"
                    : "Apps allowed: " + count + (count == 1 ? " app" : " apps"));
            noticeText.setTextColor(count == 0 ? Ui.PINK : Ui.TEXT);
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
        tag.setTypeface(Ui.medium(activity));
        crashCard.addView(tag);

        TextView hint = Ui.text(activity,
                "The app closed unexpectedly. This is the trace from that run; COPY puts it on "
                        + "the clipboard so it can be sent along.", 13, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        crashCard.addView(hint);

        final String shown = trace.length() > 4000 ? trace.substring(0, 4000) : trace;
        TextView detail = Ui.text(activity, shown.trim(), 11, Ui.TEXT);
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
            Actions.toast(activity, "Trace copied");
        });
        Button clear = Ui.action(activity, "CLEAR", false);
        clear.setOnClickListener(v -> {
            CrashLog.clear(activity);
            crashShown = null;
            refreshCrashCard();
        });
        LinearLayout traceActions = Ui.row(activity);
        Ui.space(activity, traceActions, 12);
        traceActions.addView(copy);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = Ui.dp(activity, 8);
        traceActions.addView(clear, clearParams);
        crashCard.addView(traceActions);
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
