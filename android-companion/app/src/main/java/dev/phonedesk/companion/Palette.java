package dev.phonedesk.companion;

/**
 * Every colour the app draws with, as plain ints, so the host suite can measure
 * them without Android.
 *
 * <b>Two vocabularies live here.</b> The first is the deck's: one off-white page and
 * its inversion, black ink on paper or paper ink on black. A deck keeps its own copy
 * of those four values in its sketch's {@code THEMES[]}, and
 * {@code check_widget_assets.py} holds {@link #LIGHT_FIELD}, {@link #LIGHT_TEXT},
 * {@link #DARK_FIELD} and {@link #DARK_TEXT} to it, so those four are a parity
 * value rather than a design one and do not move with a restyle.
 *
 * The second is the app's own: the three palettes the owner's site draws in, each
 * with a dark page and a light one. They are a straight copy of that stylesheet's
 * variables — the page, the band, the words that sit on that band, and an accent —
 * because the point of the restyle is that the phone and the site are one instrument.
 *
 * <b>The band never turns over.</b> Only the page does: {@code html[data-theme]}
 * swaps the page and the word colour and leaves {@code --band} alone, so the top
 * bar is the same in both modes and only the field behind it changes. That is why a
 * scheme carries one band and two pages rather than two of everything.
 *
 * It lives beside {@link Ui} rather than inside it because {@code Ui} builds
 * Android views and {@code test.sh} compiles against no Android classes at all: a
 * value that exists only inside a class javac cannot compile here is a value no
 * check can read.
 */
final class Palette {

    // ------------------------------------------------------------- the deck ---

    /** The page, dark: black. */
    static final int DARK_FIELD = 0xFF000000;
    /** A wash over black: a lifted row, a secondary action, a chip that is off. */
    static final int DARK_WASH = 0x1FFFFFFF;
    /** A wash one step above {@link #DARK_WASH}: a text field's own fill. */
    static final int DARK_INPUT = 0x2EFFFFFF;
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
    /** A wash one step above {@link #LIGHT_WASH}: a text field's own fill. */
    static final int LIGHT_INPUT = 0x1C000000;
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
     * The band the mark sits on, in the deck's vocabulary: a shade of the off-white.
     * It holds black artwork and black ink in both themes, which is why it does not
     * turn over with them. The app draws {@link Scheme#band} instead; this pair is
     * what the deck's page and its parity check still use.
     */
    static final int BAND = 0xFFE4E3DE;
    /** Ink on the deck's band. */
    static final int INK = 0xFF000000;
    /** Hints on the deck's band: rgba(0, 0, 0, .5). */
    static final int BAND_DIM = 0x80000000;
    /** A rule on the deck's band, and the pill's outline: rgba(0, 0, 0, .14). */
    static final int BAND_RULE = 0x24000000;

    /** Inks that carry words on black, quietest first. */
    static final int[] DARK_ON_FIELD = {DARK_QUIET, DARK_DIM, DARK_OK, DARK_TEXT,
            DARK_PRIMARY, DARK_ALOUD};
    /** Inks that carry words on the off-white, quietest first. */
    static final int[] LIGHT_ON_FIELD = {LIGHT_QUIET, LIGHT_DIM, LIGHT_OK, LIGHT_TEXT,
            LIGHT_PRIMARY, LIGHT_ALOUD};
    /** Inks that carry words on the deck's band, quietest first. */
    static final int[] ON_BAND = {BAND_DIM, INK};
    /** Text on a filled dark action: black on the off-white fill. */
    static final int[] DARK_ON_ACTION = {0xFF000000};
    /** Text on a filled light action: the off-white on the black fill. */
    static final int[] LIGHT_ON_ACTION = {0xFFF1F0EB};
    /** The one line colour on black. */
    static final int[] DARK_RULES = {DARK_EDGE};
    /** The one line colour on the off-white. */
    static final int[] LIGHT_RULES = {LIGHT_EDGE};

    // ------------------------------------------------------------- the site ---

    /** Green: the site's original. A lime band, near-black words on it. */
    static final int GREEN = 0;
    /** Moss: the dark green page and the lime accent the owner photographs. */
    static final int MOSS = 1;
    /** Navy. Its light page is a powder blue-grey, not a white. */
    static final int NAVY = 2;

    /**
     * One palette: four colours for the dark page and their light twins, plus the
     * band, which is the same in both modes.
     *
     * The band's own four values are here rather than derived because the site
     * derives them the same way — {@code --pal-band-dim} is a hand-picked alpha per
     * palette, not one number for all three, and a hint that clears 3:1 on the lime
     * band does not clear it on the navy one.
     */
    static final class Scheme {
        /** The word the switch and the render labels use. */
        final String id;
        /** The header band. Holds in both themes. */
        final int band;
        /** Words and marks on the band: the site's {@code --pal-band-ink}. */
        final int bandInk;
        /** A hint on the band. */
        final int bandDim;
        /** A rule on the band, and the pill's outline. */
        final int bandLine;
        /** The band one step brighter, under a finger. */
        final int bandHover;
        /** The one highlight: a filled action, a live chip. */
        final int accent;
        /** Words on the accent. */
        final int accentInk;

        /** The page in the dark theme. */
        final int pageDark;
        /** Body ink on it. */
        final int onDark;
        /** Hints on it: overlines, ages, secondary lines. */
        final int dimDark;
        /** Hairlines on it. */
        final int ruleDark;
        /** A wash on it: a lifted row, a field's own fill. */
        final int tintDark;

        /** The page in the light theme: the same palette's paper. */
        final int pageLight;
        /** Body ink on it. */
        final int onLight;
        /** Hints on it. */
        final int dimLight;
        /** Hairlines on it. */
        final int ruleLight;
        /** A wash on it. */
        final int tintLight;

        Scheme(String id, int[] values) {
            if (values.length != 17) {
                throw new IllegalArgumentException(id + " needs seventeen colours, not "
                        + values.length);
            }
            this.id = id;
            band = values[0];
            bandInk = values[1];
            bandDim = values[2];
            bandLine = values[3];
            bandHover = values[4];
            accent = values[5];
            accentInk = values[6];
            pageDark = values[7];
            onDark = values[8];
            dimDark = values[9];
            ruleDark = values[10];
            tintDark = values[11];
            pageLight = values[12];
            onLight = values[13];
            dimLight = values[14];
            ruleLight = values[15];
            tintLight = values[16];
        }
    }

    /**
     * The three palettes, in the order the appearance row draws them.
     *
     * Values are the site's own: {@code https://artbase-review.vercel.app}'s one
     * stylesheet, {@code :root} and {@code html[data-palette=...]}. An alpha written
     * as {@code .62} here is {@code 0x9E} of 255, which is the same amount of ink.
     *
     * Three alphas are nudged up from the stylesheet, recorded rather than hidden,
     * because the host suite refuses a hairline under 1.5:1 and these measured under
     * it. Navy's light hairline is .24 where the site writes .22 (1.46:1). Green's
     * band rule is .24 where the site writes .22 (1.48:1 on the lime band). Navy's
     * band rule is .30 where the site writes .26 (1.39:1). Every other value in the
     * three schemes is the stylesheet's own.
     */
    static final Scheme GREEN_SCHEME = new Scheme("green", new int[] {
            0xFF0FBF92, 0xFF0F120F, 0x9E0F120F, 0x3D0F120F, 0xFF12E2AD,
            0xFF0FBF92, 0xFF0F120F,
            0xFF131513, 0xFFF2EEE3, 0x9EF2EEE3, 0x38F2EEE3, 0x12F2EEE3,
            0xFFF2EEE3, 0xFF131513, 0xA3131313, 0x38131313, 0x0F131313});

    static final Scheme MOSS_SCHEME = new Scheme("moss", new int[] {
            0xFF005043, 0xFFE7DDCE, 0xA3E7DDCE, 0x42E7DDCE, 0xFF00695A,
            0xFFD6E381, 0xFF0E2A24,
            0xFF0E2A24, 0xFFE7DDCE, 0xA3E7DDCE, 0x3DE7DDCE, 0x12E7DDCE,
            0xFFE7DDCE, 0xFF0E2A24, 0xA30E2A24, 0x380E2A24, 0x0F0E2A24});

    static final Scheme NAVY_SCHEME = new Scheme("navy", new int[] {
            0xFF242D55, 0xFFA1B0C3, 0xA8A1B0C3, 0x4DA1B0C3, 0xFF2F3A6B,
            0xFF72B6B4, 0xFF1C1D2F,
            0xFF1C1D2F, 0xFFA1B0C3, 0xA3A1B0C3, 0x38A1B0C3, 0x14A1B0C3,
            0xFFA1B0C3, 0xFF1C1D2F, 0xA81C1D2F, 0x3D1C1D2F, 0x121C1D2F});

    /** Every scheme, by {@link #GREEN}, {@link #MOSS}, {@link #NAVY}. */
    static final Scheme[] SCHEMES = {GREEN_SCHEME, MOSS_SCHEME, NAVY_SCHEME};

    /** The stored value, clamped: a preference is not a promise. */
    static Scheme scheme(int index) {
        if (index < 0 || index >= SCHEMES.length) return MOSS_SCHEME;
        return SCHEMES[index];
    }

    private Palette() {
    }
}
