package dev.phonedesk.companion;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pull-down under the header: Paired, Boards, Serial.
 *
 * It is the site's accordion — the panel that opens under the band, pushes the page
 * down and closes again, with a hairline under it and the page's own colour rather
 * than an overlay's. Three rows at 15sp with a 13sp hint on the right, 13dp of air
 * around each: the same measurements as the drawer, because the two lists are the
 * same kind of list.
 *
 * It stays in flow rather than floating. A menu that covers the page it is a menu
 * for makes you close it to see where you are.
 */
final class PullDown extends LinearLayout {

    /** Where a row goes: a section index from {@link MainActivity}. */
    interface Nav {
        void openSection(int index);
    }

    private final Activity activity;

    /** The pull-down's own padding. The window's side insets are added to these. */
    private static final int SIDE_DP = 18;
    private static final int TOP_DP = 2;
    private static final int UNDER_DP = 10;

    PullDown(Activity activity, Nav nav) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        setPadding(Ui.dp(activity, SIDE_DP), Ui.dp(activity, TOP_DP),
                Ui.dp(activity, SIDE_DP), Ui.dp(activity, UNDER_DP));

        addView(Ui.rule(activity));
        addRow("Decks", "live link", () -> nav.openSection(MainActivity.SECTION_DECKS));
        addRow("Shelf", "the projects", () -> nav.openSection(MainActivity.SECTION_PROJECTS));
        addRow("Serial", "usb · flash", () -> nav.openSection(MainActivity.SECTION_SERIAL));
    }

    /**
     * The pull-down is in flow under the band, so a cutout on the long edge has to
     * move its rows as well as the band's: it is the same strip of screen.
     */
    void inset(int left, int right) {
        setPadding(Ui.dp(activity, SIDE_DP) + left, Ui.dp(activity, TOP_DP),
                Ui.dp(activity, SIDE_DP) + right, Ui.dp(activity, UNDER_DP));
    }

    private void addRow(String name, String hint, Runnable tap) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 13), 0, Ui.dp(activity, 13));
        TextView title = Ui.text(activity, name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        title.setLetterSpacing(-0.01f);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView aside = Ui.text(activity, hint, 10, Ui.DIM);
        Ui.mono(aside);
        row.addView(aside);
        row.setOnClickListener(v -> tap.run());
        Ui.pressable(activity, row, 0);

        LinearLayout cell = Ui.column(activity);
        cell.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(Ui.rule(activity));
        addView(cell);
    }
}
