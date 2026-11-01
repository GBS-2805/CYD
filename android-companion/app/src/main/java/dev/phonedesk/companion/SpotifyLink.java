package dev.phonedesk.companion;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

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
