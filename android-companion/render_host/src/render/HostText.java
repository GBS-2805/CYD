package render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Text measured against the face the painter draws with.
 *
 * `line()` and `advance()` are the two numbers every block of the app depends on,
 * and both come from `Face` — the file's own ascent, descent and advance, scaled by
 * the same sp the `TextView` was given. Nothing here assumes a multiplier or an em
 * width, because a render that estimates typography is a render that disagrees with
 * the phone in exactly the place a reader notices.
 */
public final class HostText {

    private HostText() {
    }

    public static float lineHeight(float sp, boolean bold) {
        return face(bold).lineHeight(sp);
    }

    public static float advance(String value, float sp, boolean bold, float spacing) {
        return face(bold).advance(value, sp, spacing, sp);
    }

    private static Face face(boolean bold) {
        try {
            return Face.of(bold);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read the monospace face", error);
        }
    }

    /**
     * Greedy word wrap at `maxWidth` dp, the way a `TextView` breaks a monospace run:
     * a word that does not fit starts the next line, and a line is never empty.
     */
    public static List<String> wrap(String value, float sp, boolean bold, float spacing,
                                    float maxWidth) {
        List<String> lines = new ArrayList<>();
        String current = "";
        for (String word : value.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (advance(candidate, sp, bold, spacing) <= maxWidth || current.isEmpty()) {
                current = candidate;
            } else {
                lines.add(current);
                current = word;
            }
        }
        if (!current.isEmpty()) lines.add(current);
        if (lines.isEmpty()) lines.add("");
        return lines;
    }
}
