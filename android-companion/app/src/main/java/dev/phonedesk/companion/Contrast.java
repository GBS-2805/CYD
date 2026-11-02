package dev.phonedesk.companion;

/**
 * WCAG 2.1 relative luminance and contrast, on ARGB ints, with no Android in sight.
 *
 * The palette is a grey ramp at four alphas, and an alpha can be nudged one value
 * at a time until a hint is no longer a hint but a ghost. Prose cannot catch that;
 * this can, which is why it is plain arithmetic instead of a paragraph in the docs.
 */
final class Contrast {

    /** Body text: WCAG AA for normal-size text. */
    static final double BODY = 4.5;
    /** A hint, a label or a status word: AA for large text, and the floor here. */
    static final double QUIET = 3.0;
    /** A rule is a line rather than a word: it has to be visible, not readable. */
    static final double RULE = 1.5;

    private Contrast() {
    }

    /** The ratio of {@code ink} over {@code ground}, compositing any alpha first. */
    static double ratio(int ink, int ground) {
        double shown = luminance(over(ink, ground));
        double base = luminance(ground);
        return (Math.max(shown, base) + 0.05) / (Math.min(shown, base) + 0.05);
    }

    /** {@code argb} drawn on top of an opaque {@code ground}. */
    static int over(int argb, int ground) {
        double alpha = ((argb >>> 24) & 0xFF) / 255.0;
        int red = blend((argb >> 16) & 0xFF, (ground >> 16) & 0xFF, alpha);
        int green = blend((argb >> 8) & 0xFF, (ground >> 8) & 0xFF, alpha);
        int blue = blend(argb & 0xFF, ground & 0xFF, alpha);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF)
                + 0.7152 * channel((rgb >> 8) & 0xFF)
                + 0.0722 * channel(rgb & 0xFF);
    }

    private static int blend(int ink, int ground, double alpha) {
        return (int) Math.round(alpha * ink + (1 - alpha) * ground);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
