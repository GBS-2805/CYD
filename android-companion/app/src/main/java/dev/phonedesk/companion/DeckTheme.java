package dev.phonedesk.companion;

/**
 * The one byte a deck's palette is set with, and the name the app draws for it.
 *
 * The board's own table has two entries — 0 PAPER, black ink on an off-white
 * page, and 1 INK, the same page inverted — the two palettes the app's own switch
 * the same two words the app's own switch uses, though the two choices are
 * independent. Keeping the encoding in one place is what stops a chip that says
 * PAPER from lighting the ink page, and it is plain Java so
 * {@code test.sh} can hold the encoding the board actually reads.
 */
final class DeckTheme {

    /** The board's ground is paper: black ink on an off-white page. */
    static final int PAPER = 0;
    /** The board's ground is ink: off-white ink on black. */
    static final int INK = 1;
    /** No answer yet, or a deck flashed before the characteristic existed. */
    static final int UNKNOWN = -1;

    private DeckTheme() {
    }

    /** A palette as the deck's own table spells it, or an em dash when unknown. */
    static String name(int theme) {
        if (theme == PAPER) return "PAPER";
        if (theme == INK) return "INK";
        return "\u2014";
    }

    /**
     * The byte the app writes: one palette index, so the board has nothing to
     * parse. {@link #UNKNOWN} is never written — a caller with no palette has
     * nothing to say to the board.
     */
    static byte[] payload(int theme) {
        return new byte[] { (byte) (theme == PAPER ? PAPER : INK) };
    }

    /** What a read came back with, or {@link #UNKNOWN} when it is not one palette. */
    static int decode(byte[] raw) {
        if (raw == null || raw.length < 1) return UNKNOWN;
        final int value = raw[0] & 0xFF;
        return value == PAPER || value == INK ? value : UNKNOWN;
    }

    static boolean known(int theme) {
        return theme == PAPER || theme == INK;
    }
}
