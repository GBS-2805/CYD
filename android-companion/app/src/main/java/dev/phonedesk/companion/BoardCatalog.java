package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Boards the Flash tab can target, and the one the person picked.
 *
 * The table itself lives in {@link BoardTable} so the host suite can read it
 * without Android. This class is only the remembered choice: a code, not an
 * index, so inserting a board in the middle of the table does not silently move
 * somebody's selection to a different panel.
 */
final class BoardCatalog {

    static final class Board {
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

    private static final String PREFS = "board";
    private static final String KEY = "selected_code";

    private BoardCatalog() {
    }

    static List<BoardTable.Board> all() {
        return BoardTable.all();
    }

    static int selectedIndex(Context context) {
        final BoardTable.Board selected = selected(context);
        return Math.max(0, all().indexOf(selected));
    }

    static BoardTable.Board selected(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final BoardTable.Board stored = BoardTable.byCode(prefs.getString(KEY, ""));
        return stored == null ? BoardTable.defaultBoard() : stored;
    }

    static void select(Context context, int index) {
        final List<BoardTable.Board> boards = all();
        if (index < 0 || index >= boards.size()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, boards.get(index).code).apply();
    }
}
