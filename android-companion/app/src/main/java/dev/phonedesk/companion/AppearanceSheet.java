package dev.phonedesk.companion;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The appearance sheet: the three palettes and the page toggle, with the names the
 * site gives them spelled out.
 *
 * The site puts these at the foot of its drawer, where four circles need no words:
 * you are looking at the page while you press them, so the page is the label. A
 * sheet has room for the words, and the words are what make the choice reviewable a
 * week later — "moss, light page" is a thing a person can ask for again.
 */
final class AppearanceSheet extends LinearLayout {

    private final Activity activity;
    private final TextView live;

    AppearanceSheet(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        TextView heading = Ui.text(activity, "PALETTE", 10, Ui.DIM);
        heading.setLetterSpacing(0.16f);
        heading.setTypeface(Ui.mono(activity));
        addView(heading);

        addView(new AppearanceStrip(activity, new AppearanceStrip.Pick() {
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

        live = Ui.text(activity, "", 14, Ui.TEXT);
        live.setPadding(0, Ui.dp(activity, 12), 0, 0);
        addView(live);

        TextView note = Ui.text(activity,
                "One set of three colours per palette, and a page that turns over on its "
                        + "own: the band keeps its colour in both, which is why the header "
                        + "does not change under the sun and the crescent.", 12, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 14), 0, 0);
        note.setLineSpacing(Ui.dp(activity, 2), 1f);
        addView(note);

        TextView deck = Ui.text(activity,
                "The app only. Each deck carries the same three swatches on its own row on "
                        + "Decks, and the page circle there writes the board's own byte.", 12, Ui.DIM);
        deck.setPadding(0, Ui.dp(activity, 10), 0, 0);
        deck.setLineSpacing(Ui.dp(activity, 2), 1f);
        addView(deck);

        refresh();
    }

    void refresh() {
        Palette.Scheme scheme = Palette.appScheme(Appearance.palette(activity));
        live.setText(capitalise(scheme.id) + " · " + (Appearance.light(activity)
                ? "light page" : "dark page"));
    }

    private static String capitalise(String id) {
        if (id.isEmpty()) return id;
        return id.substring(0, 1).toUpperCase(Locale.US) + id.substring(1);
    }
}
