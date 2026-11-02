package render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A TrueType face, read straight from the file.
 *
 * A JVM with no fontconfig cannot load a font through `java.awt`, and a render host
 * that needed a desktop font stack would fail on exactly the machine it is meant to
 * run on. The four tables that matter are small and well specified: `head` for the
 * em, `hhea` for the ascent and descent, `hmtx` plus `cmap` for an advance. That is
 * also the pair of numbers Android lays a `TextView` out on, so reading them is not
 * a shortcut around the platform — it is the platform's own arithmetic.
 */
public final class Face {

    private static final String[] DIRS = {
            "/usr/share/fonts/truetype/dejavu",
            "/usr/share/fonts/TTF",
            "/usr/share/fonts",
            "/opt/freestyle/python/lib/python3.12/site-packages/matplotlib/mpl-data/fonts/ttf",
            "/Library/Fonts",
    };

    private static final Map<String, Face> CACHE = new HashMap<>();

    private final byte[] data;
    private final int unitsPerEm;
    private final int ascent;
    private final int descent;
    private final int numberOfHMetrics;
    private final int cmap;
    private final int hmtx;

    private Face(byte[] data) {
        this.data = data;
        int head = table("head");
        int hhea = table("hhea");
        this.unitsPerEm = u16(head + 18);
        this.ascent = s16(hhea + 4);
        this.descent = s16(hhea + 6);
        this.numberOfHMetrics = u16(hhea + 34);
        this.cmap = table("cmap") + subtableOffset(table("cmap"));
        this.hmtx = table("hmtx");
    }

    public static Face of(boolean bold) throws IOException {
        String key = bold ? "bold" : "regular";
        Face cached = CACHE.get(key);
        if (cached != null) return cached;
        Path path = locate(bold);
        Face face = new Face(Files.readAllBytes(path));
        CACHE.put(key, face);
        return face;
    }

    private static Path locate(boolean bold) throws IOException {
        String name = bold ? "DejaVuSansMono-Bold.ttf" : "DejaVuSansMono.ttf";
        for (String dir : DIRS) {
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        for (String dir : DIRS) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.list(root)) {
                return stream.filter(file -> {
                    String file1 = file.getFileName().toString().toLowerCase();
                    return file1.endsWith(".ttf") && file1.contains("mono");
                }).findFirst().orElseThrow(() -> new IOException("no monospace TTF in " + dir));
            } catch (IOException missing) {
                // try the next directory
            }
        }
        throw new IOException("no monospace TTF found in " + String.join(", ", DIRS));
    }

    /** One line's height: ascent plus descent, at `sizePx`. */
    public float lineHeight(float sizePx) {
        return (ascent - descent) * sizePx / unitsPerEm;
    }

    /** One run's width, letter spacing included, at `sizePx`. */
    public float advance(String value, float sizePx, float spacingEm, float sp) {
        float width = 0f;
        for (int i = 0; i < value.length(); ++i) {
            width += glyphAdvance(value.charAt(i)) * sizePx / unitsPerEm;
        }
        if (spacingEm != 0f && value.length() > 1) {
            width += spacingEm * sp * (value.length() - 1);
        }
        return width;
    }

    private float glyphAdvance(int codePoint) {
        int gid = glyph(codePoint);
        if (gid <= 0) gid = glyph('M');
        int index = Math.min(gid, numberOfHMetrics - 1);
        return u16(hmtx + 4 * index);
    }

    // -- tables ------------------------------------------------------------

    private int table(String tag) {
        int count = u16(4);
        for (int i = 0; i < count; ++i) {
            int entry = 12 + 16 * i;
            if (data[entry] == tag.charAt(0) && data[entry + 1] == tag.charAt(1)
                    && data[entry + 2] == tag.charAt(2) && data[entry + 3] == tag.charAt(3)) {
                return (int) u32(entry + 8);
            }
        }
        throw new IllegalStateException("font has no " + tag + " table");
    }

    /** The Unicode subtable, preferring the Windows BMP format 4 one. */
    private int subtableOffset(int cmapTable) {
        int count = u16(cmapTable + 2);
        int fallback = -1;
        for (int i = 0; i < count; ++i) {
            int record = cmapTable + 4 + 8 * i;
            int platform = u16(record);
            int encoding = u16(record + 2);
            int offset = (int) u32(record + 4);
            if (platform == 3 && encoding == 1) return offset;
            if (fallback < 0 && (platform == 3 || platform == 0)) fallback = offset;
        }
        return fallback < 0 ? 0 : fallback;
    }

    /** cmap format 4 lookup: segment search for the glyph id of one character. */
    private int glyph(int codePoint) {
        if (codePoint > 0xFFFF) return 0;
        int format = u16(cmap + 0);
        if (format != 4) return 0;
        int segments = u16(cmap + 6) / 2;
        int endCodes = cmap + 14;
        int startCodes = endCodes + segments * 2 + 2;
        int deltas = startCodes + segments * 2;
        int offsets = deltas + segments * 2;

        for (int i = 0; i < segments; ++i) {
            int end = u16(endCodes + i * 2);
            if (codePoint > end) continue;
            int start = u16(startCodes + i * 2);
            if (codePoint < start) return 0;
            int delta = s16(deltas + i * 2);
            int range = u16(offsets + i * 2);
            if (range == 0) return (codePoint + delta) & 0xFFFF;
            int address = offsets + i * 2 + range + (codePoint - start) * 2;
            int gid = u16(address + delta);
            return gid;
        }
        return 0;
    }

    private int u16(int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private int s16(int at) {
        return (short) u16(at);
    }

    private long u32(int at) {
        return ((long) u16(at) << 16) | u16(at + 2);
    }
}
