package dev.phonedesk.companion;

/**
 * The compress palette, as plain ints, so the host suite can measure it.
 *
 * It lives beside {@link Ui} rather than inside it because {@code Ui} builds Android
 * views and {@code test.sh} compiles against no Android classes at all: a colour that
 * exists only inside a class javac cannot compile here is a colour no check can read.
 * {@code Ui} keeps its own names as aliases, so no screen changed when the values
 * moved.
 *
 * The field is #3f4e96 and the band #d9d9d9; the inks are the band grey at an alpha,
 * and {@code HostTests.contrast()} holds every pair to a floor, so the ramp can get
 * quieter only while it stays readable.
 */
final class Palette {

    /** The page field: every screen's ground, and a card's fill. */
    static final int FIELD = 0xFF3F4E96;
    /** A wash over the field: secondary action, chip off, search field, board row. */
    static final int WASH = 0x29000000;
    /** The header band, and a live pill's text. */
    static final int BAND = 0xFFD9D9D9;
    /** Ink on the band. */
    static final int INK = 0xFF000000;
    /** Hints on the band: rgba(0, 0, 0, .5). */
    static final int BAND_DIM = 0x80000000;
    /** A rule on the band, and the pill's outline: rgba(0, 0, 0, .14). */
    static final int BAND_RULE = 0x24000000;

    /** Primary ink on the field. */
    static final int TEXT = 0xFFD9D9D9;
    /** A fill that carries {@link #INK}: a primary action, a lit chip. */
    static final int ACCENT = 0xFFD9D9D9;
    /** The one ink brighter than the band: an error, a banned library. */
    static final int PINK = 0xFFE8E8E8;
    /** Success, and the WORKING status — the band grey at 78 %. */
    static final int GOOD = 0xC7D9D9D9;
    /** Secondary ink — 70 %. It was 64 % until the contrast check found the floor. */
    static final int DIM = 0xB3D9D9D9;
    /** The quietest ink that still carries words — 66 %, for WANT TO TRY. */
    static final int VIOLET = 0xA8D9D9D9;
    /** A hairline on the field — 26 %. A line, not a word; it is not asked for 3:1. */
    static final int EDGE = 0x42D9D9D9;

    /** Inks that carry words on the field, quietest first. */
    static final int[] ON_FIELD = {VIOLET, DIM, GOOD, TEXT, ACCENT, PINK};
    /** Inks that carry words on the band, quietest first. */
    static final int[] ON_BAND = {BAND_DIM, INK};
    /** Text on a filled primary action, and on the live pill. */
    static final int[] ON_ACCENT = {INK};
    /** The one line colour on the field. */
    static final int[] RULES = {EDGE};

    private Palette() {
    }
}
