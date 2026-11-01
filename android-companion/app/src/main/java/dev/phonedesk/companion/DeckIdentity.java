package dev.phonedesk.companion;

/**
 * What a deck says it is, whichever way it said it.
 *
 * A network deck serves this document at {@code GET /deck.json}; a Bluetooth deck
 * publishes the same keys in a read-only characteristic on its Phone Link service.
 * The common keys are {@code id}, {@code widget}, {@code tag}, {@code name},
 * {@code role}, {@code board} and {@code link}, and only a network deck adds
 * {@code ip}, {@code setup} and {@code signed} — a missing key reads as empty or
 * false, so one parser holds either deck.
 *
 * The id is {@code <widget>-<tag>}, the tag being the low 24 bits of the board's
 * efuse MAC. That, not the address, is what the app keys a record by: a re-flash
 * keeps the id, a second board of the same widget is a second id, and DHCP may
 * move or reassign an address whenever it likes.
 *
 * Plain Java with no Android imports, so test.sh can hold the parser to the exact
 * line a deck prints.
 */
final class DeckIdentity {

    String id = "";
    String widget = "";
    String tag = "";
    String label = "";
    String role = "";
    String board = "";
    String link = "";
    String ip = "";
    boolean signed;
    boolean setup;

    /** True when the document named both a deck and the widget behind it. */
    boolean usable() {
        return !id.isEmpty() && !widget.isEmpty();
    }

    /** True when this deck lives at the far end of a BLE link, not an address. */
    boolean bluetooth() {
        return "ble".equals(link);
    }

    /** One line of the deck's own JSON, read without org.json so a JVM test can hold it. */
    static DeckIdentity parse(String json) {
        final DeckIdentity identity = new DeckIdentity();
        if (json == null) return identity;
        identity.id = value(json, "id");
        identity.widget = value(json, "widget");
        identity.tag = value(json, "tag");
        identity.label = value(json, "name");
        identity.role = value(json, "role");
        identity.board = value(json, "board");
        identity.link = value(json, "link");
        identity.ip = value(json, "ip");
        identity.signed = flag(json, "signed");
        identity.setup = flag(json, "setup");
        return identity;
    }

    /** One string value; "" when the key is absent or is not a string. */
    static String value(String json, String key) {
        if (json == null || key == null) return "";
        final String needle = "\"" + key + "\"";
        final int at = json.indexOf(needle);
        if (at < 0) return "";
        final int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return "";
        final int open = json.indexOf('"', colon + 1);
        if (open < 0) return "";
        final StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < json.length(); ++i) {
            final char c = json.charAt(i);
            if (c == '"') return out.toString();
            if (c == '\\' && i + 1 < json.length()) {
                final char next = json.charAt(++i);
                switch (next) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(
                                        json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append("\\u");
                            }
                        }
                        break;
                    default:
                        out.append(next);
                        break;
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** One boolean value; false when the key is absent or is not a boolean. */
    static boolean flag(String json, String key) {
        if (json == null || key == null) return false;
        final String needle = "\"" + key + "\"";
        final int at = json.indexOf(needle);
        if (at < 0) return false;
        final int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return false;
        int from = colon + 1;
        while (from < json.length() && Character.isWhitespace(json.charAt(from))) ++from;
        return json.startsWith("true", from);
    }
}
