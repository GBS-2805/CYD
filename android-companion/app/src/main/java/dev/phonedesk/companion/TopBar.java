package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The crumb, the link pill and the settings gear: the one row every screen sits
 * under, so it is the same height and the same place whichever screen is on show.
 *
 * It is its own class because {@link MainActivity} cannot be built off a device — it
 * owns a window, a service binding and permission requests — and the render host
 * measures this bar. One builder, so the picture of the bar is the bar.
 */
final class TopBar {

    final LinearLayout bar;
    final TextView crumb;
    final TextView pill;
    final TextView gear;

    TopBar(Context context) {
        bar = Ui.row(context);
        bar.setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18),
                Ui.dp(context, 8));

        crumb = Ui.text(context, "OFF-SITE", 12, Ui.DIM);
        crumb.setLetterSpacing(0.12f);
        crumb.setPadding(0, Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6));
        bar.addView(crumb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pill = Ui.text(context, "IDLE", 11, Ui.DIM);
        pill.setLetterSpacing(0.14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setBackground(Ui.rounded(context, Ui.CARD, Ui.EDGE, 9));
        pill.setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12),
                Ui.dp(context, 6));
        bar.addView(pill);

        // \u2699 with the text-presentation selector: a glyph, not the emoji, which
        // would arrive in colour on a monochrome screen. It sits outside the pill,
        // hard against the screen edge, so it reads as chrome rather than as part
        // of the link state the pill reports.
        gear = Ui.text(context, "\u2699\uFE0E", 15, Ui.DIM);
        gear.setGravity(Gravity.CENTER);
        gear.setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 2),
                Ui.dp(context, 4));
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gearParams.leftMargin = Ui.dp(context, 10);
        bar.addView(gear, gearParams);
    }

    /**
     * The crumb reads OFF-SITE on the drawer and carries a back chevron everywhere
     * else. SETTINGS is not a row in the drawer — it is configuration, not
     * navigation — so it is the gear, lit while that screen is on show.
     */
    void showSection(int index) {
        crumb.setText(index == MainActivity.HOME ? "OFF-SITE" : "\u2039  OFF-SITE");
        crumb.setTextColor(index == MainActivity.HOME ? Ui.DIM : Ui.TEXT);
        gear.setTextColor(index == MainActivity.SECTION_SETTINGS ? Ui.TEXT : Ui.DIM);
    }

    /** The pill reports the link, and says IDLE when the bridge is not running. */
    void showLink(DeckService.Snapshot snapshot, boolean running) {
        if (snapshot.linked) {
            pill.setText("LINKED");
            pill.setTextColor(Ui.GOOD);
        } else if (running) {
            pill.setText("SCANNING");
            pill.setTextColor(Ui.ACCENT);
        } else {
            pill.setText("IDLE");
            pill.setTextColor(Ui.DIM);
        }
    }
}
