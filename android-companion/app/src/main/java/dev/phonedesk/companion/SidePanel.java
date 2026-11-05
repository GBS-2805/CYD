package dev.phonedesk.companion;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The side panel: the site's right-hand tab, holding the things that are set up
 * once rather than visited — settings, the bridge, and the palette.
 *
 * Three hundred dp or 86 % of the screen, from the right, over a 36 % scrim: the
 * site's measurements. The rows are its rows — a name at 15sp, a hint at 13sp in
 * the dim ink, a hairline under each — and the palette row sits at the foot under
 * its own border, which is where a thumb reaches it and where the site puts it.
 *
 * It is separate from the pull-down on purpose. The pull-down is <i>where you go</i>
 * (Paired, Boards, Serial); this is <i>how the app is set up</i> (permissions, the
 * decks it knows, the bridge, the look). One list for both was why the old front
 * page had to be a menu.
 */
final class SidePanel extends FrameLayout {

    /** What a row does: a section index, or one of the sheet indexes. */
    interface Nav {
        void open(int index);
    }

    /** Row indexes: the first is a section, the other two are sheets. */
    static final int ROW_SETTINGS = 0;
    static final int ROW_BRIDGE = 1;
    static final int ROW_APPEARANCE = 2;

    private final Activity activity;
    private final LinearLayout panel;
    private final TextView status;
    private boolean open;

    SidePanel(Activity activity, Nav nav) {
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
        panel.setPadding(Ui.dp(activity, 20), 0, Ui.dp(activity, 20), Ui.dp(activity, 18));

        LinearLayout head = Ui.row(activity);
        head.setMinimumHeight(Ui.dp(activity, 56));
        head.setPadding(0, Ui.dp(activity, 6), 0, 0);
        TextView label = Ui.text(activity, "MORE", 11, Ui.DIM);
        label.setLetterSpacing(0.14f);
        label.setTypeface(Ui.medium(activity));
        head.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(activity, "\u00d7", 20, Ui.TEXT);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.circle(activity, 0, Ui.EDGE));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(activity, 40),
                Ui.dp(activity, 40)));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(Ui.rule(activity));

        panel.addView(row("SETTINGS", "all permissions", () -> nav.open(ROW_SETTINGS)));
        panel.addView(row("BRIDGE", "start · stop", () -> nav.open(ROW_BRIDGE)));
        panel.addView(row("APPEARANCE", "palette · page", () -> nav.open(ROW_APPEARANCE)));

        // The palette at the foot, under its own border, exactly where the site
        // keeps it: the links stay the top of the panel and the look stays within a
        // thumb of the bottom edge.
        View spacer = new View(activity);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panel.addView(spacer, spacerParams);

        LinearLayout foot = Ui.column(activity);
        foot.addView(Ui.rule(activity));
        TextView note = Ui.text(activity,
                "The app's own palette. Each deck keeps its own, set from its row on "
                        + "PAIRED.", 12, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 12), 0, 0);
        note.setLineSpacing(Ui.dp(activity, 2), 1f);
        foot.addView(note);
        foot.addView(new AppearanceStrip(activity, new AppearanceStrip.Pick() {
            @Override
            public void palette(int index) {
                if (index == Appearance.palette(activity)) return;
                Appearance.setPalette(activity, index);
                activity.recreate();
            }

            @Override
            public void light(boolean light) {
                if (light == Appearance.light(activity)) return;
                Appearance.setLight(activity, light);
                activity.recreate();
            }
        }));
        panel.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(activity, "", 11, Ui.DIM);
        status.setLetterSpacing(0.14f);
        status.setPadding(0, Ui.dp(activity, 10), 0, 0);
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams panelParams = new LayoutParams(
                Math.min(Ui.dp(activity, 300), Math.round(
                        activity.getResources().getDisplayMetrics().widthPixels * 0.86f)),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        addView(panel, panelParams);
    }

    /** One row: its name, its hint, and a hairline under it. */
    private LinearLayout row(String name, String hint, Runnable tap) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 13), 0, Ui.dp(activity, 13));
        TextView title = Ui.text(activity, name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        title.setLetterSpacing(0.02f);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView aside = Ui.text(activity, hint, 13, Ui.DIM);
        row.addView(aside);
        row.setOnClickListener(v -> tap.run());
        Ui.pressable(activity, row, 0);

        LinearLayout cell = Ui.column(activity);
        cell.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(Ui.rule(activity));
        return cell;
    }

    /** The line at the foot: the link, or why there is none. */
    void refresh() {
        DeckService.Snapshot snapshot = DeckService.snapshot();
        if (snapshot.linked) {
            status.setText("LINKED · " + snapshot.deviceName.toUpperCase(Locale.US));
        } else if (snapshot.running) {
            status.setText("SCANNING");
        } else {
            status.setText("IDLE · BRIDGE STOPPED");
        }
    }

    void show() {
        refresh();
        open = true;
        setVisibility(VISIBLE);
        if (isAttachedToWindow()) {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0f).setDuration(200).start();
        } else {
            panel.setTranslationX(0f);
        }
    }

    void close() {
        open = false;
        panel.setTranslationX(0f);
        setVisibility(GONE);
    }

    boolean isOpen() {
        return open;
    }

    /** The panel itself, for a render that draws it as it appears. */
    LinearLayout panel() {
        return panel;
    }
}
