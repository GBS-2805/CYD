package dev.phonedesk.companion;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A deck that answers on the network: what it is, and the one URL that starts its
 * Spotify sign-in.
 *
 * Two things live here and nothing else. The identity is read from
 * {@code /deck.json}, which the sketch serves whenever the deck is awake and on
 * the network; the sign-in URL comes from {@code /auth?app=1&json=1}, because the
 * PKCE verifier never leaves the board — the app can open the URL the deck built,
 * it cannot build one itself. Plain Java with no Android imports, so test.sh can
 * check the parser and the addresses on a JVM.
 */
final class LanDeck {

    /** What the deck says it is. Empty fields mean the answer was not one. */
    static final class Identity {
        String id = "";
        String widget = "";
        String tag = "";
        String label = "";
        String role = "";
        String ip = "";
        boolean signed;
        boolean setup;

        /** True when the document named both a deck and the widget behind it. */
        boolean usable() {
            return !id.isEmpty() && !widget.isEmpty();
        }
    }

    /** One HTTP answer, with the redirect left unfollowed on purpose. */
    static final class Reply {
        final int status;
        final String body;
        final String location;

        Reply(int status, String body, String location) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
        }

        /** {@code "<status> <body>"}, the shape the callback activity reads. */
        String answer() {
            return status + " " + body;
        }

        /**
         * The Spotify URL the deck handed back: the body when it answered
         * {@code json=1}, or the {@code Location} of a deck still answering 302.
         */
        String url() {
            final String text = body.trim();
            if (text.startsWith(AUTH_PREFIX)) return text;
            final String redirect = location.trim();
            if (redirect.startsWith(AUTH_PREFIX)) return redirect;
            return "";
        }

        /** What to tell the person when there is no URL to open. */
        String why(String host) {
            String text = body.trim();
            if (text.startsWith("refused:")) {
                text = text.substring("refused:".length()).trim();
                if (!text.isEmpty()) return text;
            }
            return "The deck at " + host + " did not answer. Is the phone on the same WiFi?";
        }
    }

    /** Everything the deck's authorize URL starts with; a hostile answer cannot. */
    static final String AUTH_PREFIX = "https://accounts.spotify.com/authorize?";

    static final int CONNECT_MS = 3000;
    static final int READ_MS = 8000;

    private LanDeck() {
    }

    static String identityUrl(String host) {
        return url(host, "/deck.json");
    }

    static String signInUrl(String host) {
        return url(host, "/auth?app=1&json=1");
    }

    /** {@code http://<host><path>}, with the path taken from the descriptor. */
    static String url(String host, String path) {
        if (host == null || host.isEmpty()) return "";
        String tail = path == null ? "" : path.trim();
        if (tail.isEmpty()) return "";
        if (!tail.startsWith("/")) tail = "/" + tail;
        return "http://" + host + tail;
    }

    /**
     * A typed address, as the deck's own host: a scheme, a trailing slash or a
     * path is stripped, and anything that is not an IPv4 literal with an optional
     * port is refused — the same rule the sign-in link's {@code state} is held to,
     * because both end up in a URL the app opens.
     */
    static String host(String typed) {
        if (typed == null) return "";
        String text = typed.trim();
        final int scheme = text.indexOf("://");
        if (scheme >= 0) text = text.substring(scheme + 3);
        final int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        text = text.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return SpotifyLink.isDeckAddress(text) ? text : "";
    }

    /** The identity document, read without org.json so a JVM test can hold it. */
    static Identity parse(String json) {
        final Identity identity = new Identity();
        if (json == null) return identity;
        identity.id = value(json, "id");
        identity.widget = value(json, "widget");
        identity.tag = value(json, "tag");
        identity.label = value(json, "name");
        identity.role = value(json, "role");
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

    static Reply fetch(String url) {
        return fetch(url, CONNECT_MS, READ_MS);
    }

    /**
     * One request, redirects left alone. An older deck answers {@code /auth} with a
     * 302 instead of the text, so the {@code Location} is read rather than
     * followed: following it would open Spotify inside this socket and lose the
     * URL the browser needs.
     */
    static Reply fetch(String url, int connectMs, int readMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(connectMs);
            connection.setReadTimeout(readMs);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control", "no-store");
            final int status = connection.getResponseCode();
            final String location = connection.getHeaderField("Location");
            final InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return new Reply(status, "", location);
            final StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && body.length() < 4096) {
                    if (body.length() > 0) body.append(' ');
                    body.append(line.trim());
                }
            }
            return new Reply(status, body.toString(), location);
        } catch (Exception e) {
            // No answer rather than a refusal: a sleeping deck, a wrong address, or
            // a phone that moved to mobile data.
            return new Reply(0, "", "");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
