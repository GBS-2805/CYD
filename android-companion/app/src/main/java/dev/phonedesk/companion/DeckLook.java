package dev.phonedesk.companion;

/**
 * One deck's look in the app: which of the site's three palettes it is drawn in,
 * and which of that palette's two pages.
 *
 * <b>Both halves reach the glass.</b> A board's sketch carries a row per palette per
 * page, and the app writes one byte down the link to say which: {@link #boardTheme()}
 * is the only place that mapping lives, so tapping a swatch repaints the deck itself.
 * The row on PAIRED keeps the app's own inks and only rings the swatch that is on — a
 * list of three decks in three palettes should still read as one list — and the page
 * circle follows whatever the board answers, which is why the look is stored for a
 * deck that is not linked yet.
 *
 * Plain Java, no Context and no Android: {@code test.sh} holds the byte the board
 * actually reads, and the store keeps the string this encodes to.
 */
final class DeckLook {

    /** A deck's palette and page before anything is chosen: the app's own default. */
    static final DeckLook DEFAULT = new DeckLook(Palette.MOSS, false);

    /** {@link Palette#GREEN}, {@link Palette#MOSS} or {@link Palette#NAVY}. */
    final int palette;
    /** True when the light page is the one drawn, and the one the board shows. */
    final boolean light;

    DeckLook(int palette, boolean light) {
        this.palette = clamp(palette);
        this.light = light;
    }

    /** The site palette, clamped: a stored index is a preference, not a promise. */
    Palette.Scheme scheme() {
        return Palette.scheme(palette);
    }

    DeckLook withPalette(int index) {
        return new DeckLook(index, light);
    }

    DeckLook withLight(boolean lightPage) {
        return new DeckLook(palette, lightPage);
    }

    /**
     * The board's own row, written as its own byte. The palette is part of the index
     * — {@code 2 + palette * 2 + (light ? 0 : 1)} — so a moss dark deck is row 5 on
     * the glass rather than merely "dark".
     */
    int boardTheme() {
        return DeckTheme.of(palette, light);
    }

    /** The page this look draws on. */
    int page() {
        Palette.Scheme scheme = scheme();
        return light ? scheme.pageLight : scheme.pageDark;
    }

    /** Body ink on that page. */
    int on() {
        Palette.Scheme scheme = scheme();
        return light ? scheme.onLight : scheme.onDark;
    }

    /** Hints on that page. */
    int dim() {
        Palette.Scheme scheme = scheme();
        return light ? scheme.dimLight : scheme.dimDark;
    }

    /** The hairline on that page: the card's border, an unlit LED's outline. */
    int rule() {
        Palette.Scheme scheme = scheme();
        return light ? scheme.ruleLight : scheme.ruleDark;
    }

    /** The one highlight: the moving LED, the live chip, a filled action. */
    int accent() {
        return scheme().accent;
    }

    /** The word on that highlight. */
    int accentInk() {
        return scheme().accentInk;
    }

    /** The store's string: {@code palette|light}, e.g. {@code 1|0} for moss, dark. */
    String encode() {
        return palette + "|" + (light ? "1" : "0");
    }

    /**
     * A stored look, or {@code fallback} when there is none or it is not one:
     * an unreadable preference draws the deck in the app's own default rather than
     * failing to draw it at all.
     */
    static DeckLook decode(String raw, DeckLook fallback) {
        if (raw == null) return fallback;
        int bar = raw.indexOf('|');
        if (bar < 0) return fallback;
        try {
            int palette = Integer.parseInt(raw.substring(0, bar).trim());
            if (palette < 0 || palette >= Palette.SCHEMES.length) return fallback;
            String page = raw.substring(bar + 1).trim();
            if (!page.equals("0") && !page.equals("1")) return fallback;
            return new DeckLook(palette, page.equals("1"));
        } catch (NumberFormatException notALook) {
            return fallback;
        }
    }

    private static int clamp(int palette) {
        if (palette < 0 || palette >= Palette.SCHEMES.length) return Palette.MOSS;
        return palette;
    }
}
