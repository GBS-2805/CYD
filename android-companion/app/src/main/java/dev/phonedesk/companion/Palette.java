package dev.phonedesk.companion;

/**
 * Two monochrome themes, as plain ints, so the host suite can measure them.
 *
 * Black ink on an off-white page, or the same page inverted: off-white ink on
 * black. That is the whole thing — no hue, no brand tint, no second shade to keep
 * in step. The band is a shade of the same off-white, because the owner's mark is
 * drawn in black on a light ground, so it stays light in both themes and the ink
 * on it never changes.
 *
 * It lives beside {@link Ui} rather than inside it because {@code Ui} builds
 * Android views and {@code test.sh} compiles against no Android classes at all: a
 * value that exists only inside a class javac cannot compile here is a value no
 * check can read.
 */
final class Palette {

    /** The page, dark: black. */
    static final int DARK_FIELD = 0xFF000000;
    /** A wash over black: a lifted row, a secondary action, a search field. */
    static final int DARK_WASH = 0x1FFFFFFF;
    /** A hairline on black. A line is not a word; it is not asked for 3:1. */
    static final int DARK_EDGE = 0x3AF1F0EB;
    /** Body ink on black: the off-white. */
    static final int DARK_TEXT = 0xFFF1F0EB;
    /** Secondary ink on black. */
    static final int DARK_DIM = 0xB3F1F0EB;
    /** The quietest ink that still carries words. */
    static final int DARK_QUIET = 0xA8F1F0EB;
    /** Success, and the healthy status. */
    static final int DARK_OK = 0xC7F1F0EB;
    /** The loudest ink on black: an error, a banned library. */
    static final int DARK_ALOUD = 0xFFFFFFFF;
    /** A filled primary action on black: the off-white, with black ink on it. */
    static final int DARK_PRIMARY = 0xFFF1F0EB;

    /** The page, light: the off-white. */
    static final int LIGHT_FIELD = 0xFFF1F0EB;
    /** A wash over the off-white. */
    static final int LIGHT_WASH = 0x14000000;
    /** A hairline on the off-white. */
    static final int LIGHT_EDGE = 0x38000000;
    /** Body ink on the off-white: black. */
    static final int LIGHT_TEXT = 0xFF000000;
    /** Secondary ink on the off-white. */
    static final int LIGHT_DIM = 0xB3000000;
    /** The quietest ink that still carries words. */
    static final int LIGHT_QUIET = 0xA8000000;
    /** Success, and the healthy status. */
    static final int LIGHT_OK = 0xC7000000;
    /** The loudest ink on the off-white: black, with nothing lighter above it. */
    static final int LIGHT_ALOUD = 0xFF000000;
    /** A filled primary action on the off-white: black, with off-white ink on it. */
    static final int LIGHT_PRIMARY = 0xFF000000;

    /**
     * The band the mark sits on: a shade of the off-white. It holds black artwork
     * and black ink in both themes, which is why it does not turn over with them.
     */
    static final int BAND = 0xFFE4E3DE;
    /** Ink on the band. */
    static final int INK = 0xFF000000;
    /** Hints on the band: rgba(0, 0, 0, .5). */
    static final int BAND_DIM = 0x80000000;
    /** A rule on the band, and the pill's outline: rgba(0, 0, 0, .14). */
    static final int BAND_RULE = 0x24000000;

    /** Inks that carry words on black, quietest first. */
    static final int[] DARK_ON_FIELD = {DARK_QUIET, DARK_DIM, DARK_OK, DARK_TEXT,
            DARK_PRIMARY, DARK_ALOUD};
    /** Inks that carry words on the off-white, quietest first. */
    static final int[] LIGHT_ON_FIELD = {LIGHT_QUIET, LIGHT_DIM, LIGHT_OK, LIGHT_TEXT,
            LIGHT_PRIMARY, LIGHT_ALOUD};
    /** Inks that carry words on the band, quietest first. */
    static final int[] ON_BAND = {BAND_DIM, INK};
    /** Text on a filled dark action: black on the off-white fill. */
    static final int[] DARK_ON_ACTION = {0xFF000000};
    /** Text on a filled light action: the off-white on the black fill. */
    static final int[] LIGHT_ON_ACTION = {0xFFF1F0EB};
    /** The one line colour on black. */
    static final int[] DARK_RULES = {DARK_EDGE};
    /** The one line colour on the off-white. */
    static final int[] LIGHT_RULES = {LIGHT_EDGE};

    private Palette() {
    }
}
