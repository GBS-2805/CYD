package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which palette the app draws in, and whether its page is the dark one.
 *
 * Two values, because that is what the site's two controls are: three swatches pick
 * the set, one circle flips the page. The band is part of the set and never flips,
 * so a palette and a light flag is the whole state; storing a "combination" would
 * be five values that have to agree.
 *
 * The app's alone: a deck's palette is its own two pages, written down the link
 * from that deck's row on PAIRED.
 *
 * The choice lives here rather than in {@link Ui} because {@code test.sh} compiles
 * the palette without any Android class, and a preference needs a Context.
 * {@code MainActivity} applies it before the first view exists, the appearance
 * sheet writes it, and the render host applies it per frame.
 */
final class Appearance {

    private static final String PREFS = "appearance";
    /** {@link Palette#GREEN}, {@link Palette#MOSS} or {@link Palette#NAVY}. */
    private static final String KEY_PALETTE = "palette";
    /** The page: false dark, true light. */
    private static final String KEY_LIGHT = "light";
    /**
     * The one key this app used before the site's palettes: 0 INK, 1 PAPER. Read
     * once so the first run after the restyle keeps the page the owner was reading,
     * and never written again — a preference migrated in place is a preference that
     * cannot come back.
     */
    private static final String KEY_LEGACY = "theme";

    private Appearance() {
    }

    /**
     * {@link Palette#FIELD} on a fresh install: the Deck Index page, near-black with
     * one pale moss accent. The three deck palettes are still selectable, because an
     * install that chose one should keep it.
     */
    static int palette(Context context) {
        if (context == null) return Palette.FIELD;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int stored = prefs.getInt(KEY_PALETTE, -1);
        if (stored == Palette.FIELD) return Palette.FIELD;
        if (stored >= 0 && stored < Palette.SCHEMES.length) return stored;
        // Nothing stored yet. An install from before the restyle had a single
        // theme; keep its light/dark answer rather than resetting it to dark.
        int legacy = prefs.getInt(KEY_LEGACY, -1);
        if (legacy == 1) return Palette.FIELD;
        return Palette.FIELD;
    }

    /** Dark unless this install asked for the light page. */
    static boolean light(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_LIGHT)) return prefs.getBoolean(KEY_LIGHT, false);
        return prefs.getInt(KEY_LEGACY, -1) == 1;
    }

    static void setPalette(Context context, int palette) {
        if (palette != Palette.FIELD && (palette < 0 || palette >= Palette.SCHEMES.length)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PALETTE, palette).apply();
    }

    static void setLight(Context context, boolean light) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LIGHT, light).apply();
    }

    /** Read the stored choice into {@link Ui}; call before building any view. */
    static void apply(Context context) {
        Ui.applyTheme(palette(context), light(context));
    }
}
