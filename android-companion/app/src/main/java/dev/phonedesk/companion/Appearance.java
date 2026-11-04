package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which palette the app draws in, stored so a restart and a rebuild keep it.
 *
 * The choice lives here rather than in {@link Ui} because {@code test.sh} compiles
 * the palette without any Android class, and a preference needs a Context. One
 * key, one file: the switch in Settings writes it, {@code MainActivity} applies it
 * before the first view exists, and the render host applies it per frame.
 */
final class Appearance {

    private static final String PREFS = "appearance";
    private static final String KEY = "theme";

    private Appearance() {
    }

    /** {@link Ui#DARK} on a fresh install, and after anything unreadable. */
    static int get(Context context) {
        if (context == null) return Ui.DARK;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY, Ui.DARK) == Ui.LIGHT ? Ui.LIGHT : Ui.DARK;
    }

    static void set(Context context, int theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, theme).apply();
    }

    /** Read the stored choice into {@link Ui}; call before building any view. */
    static void apply(Context context) {
        Ui.applyTheme(get(context));
    }
}
