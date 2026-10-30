package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.List;

/**
 * Boards the Flash tab can target. One entry today — the 2.4" CYD this deck
 * runs on — but the picker exists so more can be added without reshaping the UI
 * or the firmware catalogue keyed off it.
 */
final class BoardCatalog {

    static final class Board {
        /** Short code used in section headers, e.g. 2432S024R. */
        final String code;
        final String label;
        final String note;

        Board(String code, String label, String note) {
            this.code = code;
            this.label = label;
            this.note = note;
        }

        String spinnerLabel() {
            return label + "  ·  " + code;
        }
    }

    private static final List<Board> BOARDS = Arrays.asList(
            new Board("2432S024R", "ESP32-2432S024R",
                    "2.4\" CYD · resistive touch · CH340 USB bridge · 4 MB flash. "
                            + "This is the board the deck firmware runs on."));

    private static final String PREFS = "board";
    private static final String KEY = "selected";

    private BoardCatalog() {
    }

    static List<Board> all() {
        return BOARDS;
    }

    static int selectedIndex(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final int index = prefs.getInt(KEY, 0);
        return (index < 0 || index >= BOARDS.size()) ? 0 : index;
    }

    static Board selected(Context context) {
        return BOARDS.get(selectedIndex(context));
    }

    static void select(Context context, int index) {
        if (index < 0 || index >= BOARDS.size()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, index).apply();
    }
}
