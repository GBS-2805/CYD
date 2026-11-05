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

    PullDown(Activity activity, Nav nav) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        setPadding(Ui.dp(activity, 18), Ui.dp(activity, 2), Ui.dp(activity, 18),
                Ui.dp(activity, 10));

        addView(Ui.rule(activity));
        addRow("PAIRED", "live link", () -> nav.openSection(MainActivity.SECTION_DECKS));
        addRow("BOARDS", "the shelf", () -> nav.openSection(MainActivity.SECTION_PROJECTS));
        addRow("SERIAL", "usb · flash", () -> nav.openSection(MainActivity.SECTION_SERIAL));
    }

    private void addRow(String name, String hint, Runnable tap) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 13), 0, Ui.dp(activity, 13));
        TextView title = Ui.text(activity, name, 15, Ui.TEXT);
        title.setTypeface(Ui.medium(activity));
        title.setLetterSpacing(0.02f);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(activity, hint, 13, Ui.DIM));
        row.setOnClickListener(v -> tap.run());
        Ui.pressable(activity, row, 0);

        LinearLayout cell = Ui.column(activity);
        cell.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(Ui.rule(activity));
        addView(cell);
    }
}
