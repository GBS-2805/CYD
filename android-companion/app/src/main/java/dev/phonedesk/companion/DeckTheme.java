package dev.phonedesk.companion;

/**
 * The one byte a deck's palette is set with, and how both ends read it.
 *
 * The board's table has eight rows. Rows 0 and 1 are the neutral pair a deck shipped
 * before the site's palettes existed — 0 PAPER, black ink on an off-white page, and
 * 1 INK, the same page inverted. Rows 2 to 7 are the site's three palettes twice, in
 * the order the app's appearance row draws them:
 *
 * <pre>
 *   2 green light   3 green dark
 *   4 moss light    5 moss dark
 *   6 navy light    7 navy dark
 * </pre>
 *
 * The arithmetic is {@code 2 + palette * 2 + (light ? 0 : 1)}, which is why a swatch
 * tapped in the app is the row the glass draws: both ends compute the same index
 * rather than share a table of words. The app's {@code Palette.GREEN/MOSS/NAVY} are
 * 0, 1, 2 and its pages are a flag, so the mapping lives here once and
 * {@code test.sh} holds it to the board's own ordering.
 *
 * Plain Java, no Android: {@code test.sh} checks the arithmetic without one.
 */
final class DeckTheme {

    /** The board's ground is paper: black ink on an off-white page. */
    static final int PAPER = 0;
    /** The board's ground is ink: off-white ink on black. */
    static final int INK = 1;
    /** The first of the six palette rows: green on its light page. */
    static final int PALETTE_BASE = 2;
    /** No answer yet, or a deck flashed before the palettes reached the sketch. */
    static final int UNKNOWN = -1;

    private DeckTheme() {
    }

    /**
     * The row for a palette and a page: the byte the app writes and the index the
     * sketch's {@code THEMES[]} is read at. A palette past the three is clamped to
     * moss rather than written as a row that does not exist.
     */
    static int of(int palette, boolean light) {
        final int index = palette < 0 || palette >= Palette.SCHEMES.length
                ? Palette.MOSS : palette;
        return PALETTE_BASE + index * 2 + (light ? 0 : 1);
    }

    /** True when that row draws its palette's light page. */
    static boolean page(int theme) {
        if (theme == PAPER) return true;
        if (theme == INK) return false;
        if (theme < PALETTE_BASE) return true;
        return (theme - PALETTE_BASE) % 2 == 0;
    }

    /** The palette that row belongs to, or {@link #UNKNOWN} for the neutral pair. */
    static int palette(int theme) {
        if (theme < PALETTE_BASE) return UNKNOWN;
        final int index = (theme - PALETTE_BASE) / 2;
        return index < Palette.SCHEMES.length ? index : UNKNOWN;
    }

    /** The byte count the sketch's own table has, for a read that answers nothing. */
    static int rows() {
        return PALETTE_BASE + Palette.SCHEMES.length * 2;
    }

    /** A palette as the deck's own table spells it, or an em dash when unknown. */
    static String name(int theme) {
        if (theme == PAPER) return "PAPER";
        if (theme == INK) return "INK";
        final int index = palette(theme);
        if (index == UNKNOWN) return "\u2014";
        return Palette.scheme(index).id.toUpperCase(java.util.Locale.US)
                + (page(theme) ? " LIGHT" : " DARK");
    }

    /**
     * The byte the app writes: one row index, so the board has nothing to parse.
     * {@link #UNKNOWN} is never written — a caller with no palette has nothing to
     * say to the board.
     */
    static byte[] payload(int theme) {
        return new byte[] { (byte) (known(theme) ? theme : INK) };
    }

    /** What a read came back with, or {@link #UNKNOWN} when it is not one row. */
    static int decode(byte[] raw) {
        if (raw == null || raw.length < 1) return UNKNOWN;
        final int value = raw[0] & 0xFF;
        return value < rows() ? value : UNKNOWN;
    }

    static boolean known(int theme) {
        return theme >= 0 && theme < rows();
    }
}
