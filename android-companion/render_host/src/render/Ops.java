package render;

import java.util.Locale;

/**
 * The drawing operations a measured view tree produces, as JSON.
 *
 * A render is then two steps that cannot disagree: Java measures and says where
 * everything is, the painter draws exactly that. `check_render_fit.py` reads the same
 * operations, so a run that crosses an edge or lands on another run is caught by the
 * numbers the app produced rather than by a second opinion.
 */
public final class Ops {

    private final StringBuilder json = new StringBuilder();
    private boolean first = true;

    public void card(float x, float y, float w, float h, float radius, int fill, int stroke) {
        start("card");
        number("x", x);
        number("y", y);
        number("w", w);
        number("h", h);
        number("r", radius);
        string("fill", color(fill));
        string("stroke", stroke == 0 ? color(fill) : color(stroke));
        close();
    }

    public void color(float x, float y, float w, float h, int argb) {
        start("fill");
        number("x", x);
        number("y", y);
        number("w", w);
        number("h", h);
        string("fill", color(argb));
        close();
    }

    public void text(float x, float y, float w, float h, String value, float sp,
                     boolean bold, float spacing, int argb) {
        start("text");
        number("x", x);
        number("y", y);
        number("w", w);
        number("h", h);
        string("text", value);
        number("sp", sp);
        if (bold) raw("bold", "true");
        if (spacing != 0f) number("spacing", spacing);
        string("color", color(argb));
        close();
    }

    public void image(float x, float y, float w, float h, String art) {
        start("image");
        number("x", x);
        number("y", y);
        number("w", w);
        number("h", h);
        string("art", art);
        close();
    }

    public String document(String name, float width, float height) {
        return "{\"name\":" + quote(name) + ",\"width\":" + format(width)
                + ",\"height\":" + format(height) + ",\"ops\":[" + json + "]}\n";
    }

    private static String format(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    /** `#rrggbb`, which is all the INK palette and any Android colour it prints need. */
    static String color(int argb) {
        return String.format(Locale.US, "#%06x", argb & 0xFFFFFF);
    }

    private void start(String op) {
        if (!first) json.append(",\n");
        first = false;
        json.append("{\"op\":\"").append(op).append('"');
    }

    private void close() {
        json.append('}');
    }

    private void number(String key, float value) {
        raw(key, String.format(Locale.US, "%.2f", value));
    }

    private void string(String key, String value) {
        raw(key, quote(value));
    }

    private void raw(String key, String value) {
        json.append(",\"").append(key).append("\":").append(value);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); ++i) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        return out.append('"').toString();
    }
}
