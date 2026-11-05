package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one row every screen sits under: the pull-down handle and the page name on
 * the left, the owner's mark on the centre line, the link pill and the gear on the
 * right.
 *
 * It is the site's header band: the palette's band colour, a 1px rule where it meets
 * the page, one name at 15sp, and a pill whose fill is the only highlight in the
 * chrome. Because the band does not turn over with the page, neither does this bar —
 * flipping the theme changes the field under it and nothing in it.
 *
 * The mark is drawn in the band's own ink. Green's band is lime in both themes, so
 * the artwork stays near-black there; moss and navy have dark bands, where a black
 * mark would be invisible. {@code SRC_IN} keeps the PNG's alpha — the ghost inside
 * the C survives — and only changes the colour it is drawn in, which is what the
 * site does with {@code currentColor}.
 *
 * It is its own class because {@link MainActivity} cannot be built off a device — it
 * owns a window, a service binding and permission requests — and the render host
 * measures this bar. One builder, so the picture of the bar is the bar.
 */
final class TopBar {

    final LinearLayout bar;
    /** The pull-down and its name: one tappable area, the way the site's is. */
    final LinearLayout crumb;
    final TextView crumbText;
    final TextView pill;
    /** The side panel's burger, and the second of the bar's two controls. */
    final ImageView menu;
    final ImageView logo;
    private final ImageView chevron;

    /** The page names, in {@link MainActivity}'s section order. */
    private static final String[] TITLES = {"PAIRED", "BOARDS", "SERIAL", "SETTINGS"};

    TopBar(Context context) {
        bar = Ui.row(context);
        bar.setBackgroundColor(Ui.BAND);
        bar.setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14),
                Ui.dp(context, 12));

        // The pull-down: the site's chevron-double-down in a 40dp tap target, pulled
        // back so the glyph lines up with the page gutter. One chevron here, drawn
        // by rotating the back arrow rather than by carrying a second asset that can
        // drift from it.
        chevron = Ui.icon(context, R.drawable.ic_chevron_back, 22, Ui.BAND_INK);
        chevron.setRotation(-90f);

        crumbText = Ui.text(context, TITLES[0], 15, Ui.BAND_INK);
        crumbText.setTypeface(Ui.medium(context));
        crumbText.setLetterSpacing(0.02f);
        crumbText.setPadding(Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 10),
                Ui.dp(context, 8));

        crumb = Ui.row(context);
        crumb.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(context, 22),
                Ui.dp(context, 22)));
        crumb.addView(crumbText);
        bar.addView(crumb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // The mark sits on the band's centre line. The crumb's half of the spare
        // width and the pill's half are the same weight, so the fixed-width mark
        // between them can only land on the screen's centre whatever the crumb and
        // the pill say.
        logo = new ImageView(context);
        logo.setImageResource(R.drawable.logo_mark);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Ui.tint(logo, Ui.BAND_INK);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 34), Ui.dp(context, 34));
        logoParams.gravity = Gravity.CENTER_VERTICAL;
        logoParams.leftMargin = Ui.dp(context, 10);
        logoParams.rightMargin = Ui.dp(context, 10);
        bar.addView(logo, logoParams);

        pill = Ui.text(context, "IDLE", 11, Ui.BAND_DIM);
        pill.setLetterSpacing(0.1f);
        pill.setTypeface(Ui.medium(context));
        pill.setBackground(Ui.rounded(context, 0, Ui.BAND_RULE, 0));
        pill.setPadding(Ui.dp(context, 9), Ui.dp(context, 5), Ui.dp(context, 9),
                Ui.dp(context, 5));

        menu = Ui.icon(context, R.drawable.ic_burger, 22, Ui.BAND_INK);
        menu.setPadding(Ui.dp(context, 6), Ui.dp(context, 3), Ui.dp(context, 2),
                Ui.dp(context, 3));
        menu.setContentDescription("More");

        LinearLayout outer = Ui.row(context);
        outer.setGravity(Gravity.END);
        outer.addView(pill);
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gearParams.leftMargin = Ui.dp(context, 8);
        outer.addView(menu, gearParams);
        bar.addView(outer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** The name of the screen on show. */
    void showSection(int index) {
        crumbText.setText(TITLES[Math.max(0, Math.min(TITLES.length - 1, index))]);
    }

    /** The burger stays the band's ink; the panel is a control, not a state. */
    void showPanel(boolean open) {
        Ui.tint(menu, Ui.BAND_INK);
    }

    /** The pull-down chevron turns over while the sheet behind it is open. */
    void showDrawer(boolean open) {
        chevron.setRotation(open ? 90f : -90f);
    }

    /**
     * The pill reports the link, and says IDLE when the bridge is not running. The
     * live pill is the one filled shape in the chrome: the accent, with its own ink
     * on it, which is the site's {@code .pill.live} exactly.
     */
    void showLink(DeckService.Snapshot snapshot, boolean running) {
        Context where = pill.getContext();
        if (snapshot.linked) {
            pill.setText("LINKED");
            pill.setTextColor(Ui.ON_ACCENT);
            pill.setBackground(Ui.rounded(where, Ui.ACCENT, Ui.ACCENT, 0));
        } else if (running) {
            pill.setText("SCANNING");
            pill.setTextColor(Ui.BAND_INK);
            pill.setBackground(Ui.rounded(where, 0, Ui.BAND_RULE, 0));
        } else {
            pill.setText("IDLE");
            pill.setTextColor(Ui.BAND_DIM);
            pill.setBackground(Ui.rounded(where, 0, Ui.BAND_RULE, 0));
        }
    }
}
