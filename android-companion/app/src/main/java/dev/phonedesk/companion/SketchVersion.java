package dev.phonedesk.companion;

/**
 * The build name a sketch declares: {@code DECK_VERSION "1.0.0"}.
 *
 * The flash shelf reads it out of the bundled sketch so the card can say which
 * firmware a COPY would flash, and the identity document carries the same string
 * so a linked deck says which firmware it is actually running. One constant per
 * sketch is the single place either answer comes from, which is why this reads the
 * declaration instead of a copy of it.
 *
 * Plain Java with no Android imports, so test.sh can hold it to the shapes the two
 * sketches actually use.
 */
final class SketchVersion {

    private SketchVersion() {
    }

    /** The declared build name, or "" when the sketch declares none. */
    static String of(String sketchText) {
        if (sketchText == null) return "";
        final int key = sketchText.indexOf("DECK_VERSION");
        if (key < 0) return "";
        final int equals = sketchText.indexOf('=', key);
        if (equals < 0) return "";
        final int open = sketchText.indexOf('"', equals);
        if (open < 0) return "";
        final int close = sketchText.indexOf('"', open + 1);
        if (close < 0) return "";
        return sketchText.substring(open + 1, close).trim();
    }

    /** How a version reads on a card: "v1.0.0", or "" when there is nothing to show. */
    static String label(String version) {
        if (version == null || version.isEmpty()) return "";
        return "v" + version;
    }
}
