package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
    /** The crumb and its chevron: one tappable area, so the back arrow is a target too. */
    final LinearLayout crumb;
    final TextView crumbText;
    final TextView pill;
    final ImageView gear;
    private final ImageView back;

    TopBar(Context context) {
        bar = Ui.row(context);
        // The sheet's light band, so the chrome is one surface and the field below
        // it is the app. Every ink in this bar is black or a black wash.
        bar.setBackgroundColor(Ui.BAND);
        bar.setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18),
                Ui.dp(context, 8));

        crumbText = Ui.text(context, "OFF-SITE", 12, Ui.BAND_DIM);
        crumbText.setLetterSpacing(0.12f);
        crumbText.setPadding(0, Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6));

        // The way back is a shape beside the crumb, not a character inside it: U+2039
        // was the monospace face's chevron, sitting on the text baseline at the font's
        // weight. The drawable is centred on the crumb's line and hides on the drawer,
        // where there is nothing to go back to.
        back = Ui.icon(context, R.drawable.ic_chevron_back, 12, Ui.BAND_DIM);
        back.setVisibility(View.GONE);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 12), Ui.dp(context, 12));
        backParams.rightMargin = Ui.dp(context, 7);

        crumb = Ui.row(context);
        crumb.addView(back, backParams);
        crumb.addView(crumbText);
        bar.addView(crumb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pill = Ui.text(context, "IDLE", 11, Ui.BAND_DIM);
        pill.setLetterSpacing(0.14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setBackground(Ui.rounded(context, 0, Ui.BAND_RULE, 9));
        pill.setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12),
                Ui.dp(context, 6));
        bar.addView(pill);

        // The gear is a shape now, not a character. U+2699 with the text-presentation
        // selector asked the monospace face for a glyph it may not carry — a thinner
        // or fatter gear, a fallback face, or nothing at all — and its weight was the
        // font's rather than the bar's. It still sits outside the pill, hard against
        // the screen edge, so it reads as chrome rather than as part of the link state
        // the pill reports.
        gear = Ui.icon(context, R.drawable.ic_gear, 20, Ui.BAND_DIM);
        gear.setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 2),
                Ui.dp(context, 3));
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
        boolean drawer = index == MainActivity.HOME;
        back.setVisibility(drawer ? View.GONE : View.VISIBLE);
        crumbText.setTextColor(drawer ? Ui.BAND_DIM : Ui.INK);
        Ui.tint(back, drawer ? Ui.BAND_DIM : Ui.INK);
        Ui.tint(gear, index == MainActivity.SECTION_SETTINGS ? Ui.INK : Ui.BAND_DIM);
    }

    /** The pill reports the link, and says IDLE when the bridge is not running. */
    void showLink(DeckService.Snapshot snapshot, boolean running) {
        // The live pill is the one filled shape on the band: black with the band's
        // grey on it, which is the sheet's .pill.live turned round for a light bar.
        Context where = pill.getContext();
        if (snapshot.linked) {
            pill.setText("LINKED");
            pill.setTextColor(Ui.BAND);
            pill.setBackground(Ui.rounded(where, Ui.INK, Ui.INK, 9));
        } else if (running) {
            pill.setText("SCANNING");
            pill.setTextColor(Ui.INK);
            pill.setBackground(Ui.rounded(where, 0, Ui.BAND_RULE, 9));
        } else {
            pill.setText("IDLE");
            pill.setTextColor(Ui.BAND_DIM);
            pill.setBackground(Ui.rounded(where, 0, Ui.BAND_RULE, 9));
        }
    }
}
