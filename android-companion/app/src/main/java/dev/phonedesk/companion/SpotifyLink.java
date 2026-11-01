package dev.phonedesk.companion;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The two fields the deck's sign-in needs out of a Spotify redirect.
 *
 * The deck asks Spotify for {@code phonedesk://spotify-callback} and puts its own
 * address in {@code state}, because a custom scheme carries no host: the app is
 * handed a code and has to be told where to deliver it. Plain Java with no
 * Android imports, so test.sh can check it on a JVM.
 */
final class SpotifyLink {

    static final String SCHEME = "phonedesk";
    static final String HOST = "spotify-callback";

    private SpotifyLink() {
    }

    /** The authorization code, or "" when the link carries none (a refusal). */
    static String code(String link) {
        return query(link, "code");
    }

    /** The deck's LAN address from {@code state}, or "" when it is not one. */
    static String deck(String link) {
        String state = query(link, "state");
        return isDeckAddress(state) ? state : "";
    }

    /**
     * The one request the app makes for the deck: its address, the code, and
     * {@code json=1}, which asks the deck for a one-line answer instead of a page.
     * Everything but unreserved characters is escaped, so a code containing
     * {@code +}, {@code /} or {@code =} arrives as typed — the deck's own decoder
     * does not read {@code +} as a space.
     */
    static String codeUrl(String deck, String code) {
        return "http://" + deck + "/code?redir=app&code=" + escape(code) + "&json=1";
    }

    /** RFC 3986 escaping: percent signs, never {@code +}. */
    private static String escape(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 8);
        for (byte raw : text.getBytes(StandardCharsets.UTF_8)) {
            final int value = raw & 0xFF;
            if ((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '.' || value == '_' || value == '~') {
                out.append((char) value);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(
                                Character.forDigit((value >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** One decoded query value; a later copy wins, as it does in a browser. */
    static String query(String link, String key) {
        if (link == null) return "";
        int mark = link.indexOf('?');
        if (mark < 0) return "";
        int hash = link.indexOf('#', mark + 1);
        String query = hash < 0 ? link.substring(mark + 1) : link.substring(mark + 1, hash);
        String found = "";
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            if (!pair.substring(0, equals).equals(key)) continue;
            found = decode(pair.substring(equals + 1));
        }
        return found;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            // A malformed escape is passed through rather than thrown away: the
            // deck will refuse a code it cannot use, which is the right failure.
            return text;
        }
    }

    /**
     * True for a bare IPv4 literal with an optional port ({@code 192.168.1.50},
     * {@code 192.168.4.1:8080}). A hostname is refused on purpose: this value
     * comes back from Spotify's redirect, and only a LAN address the deck could
     * hold is worth opening in the browser.
     */
    static boolean isDeckAddress(String text) {
        if (text == null || text.isEmpty() || text.length() > 21) return false;
        String host = text;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            String port = text.substring(colon + 1);
            if (port.isEmpty() || port.length() > 5 || !allDigits(port)) return false;
            host = text.substring(0, colon);
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !allDigits(part)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean allDigits(String text) {
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
