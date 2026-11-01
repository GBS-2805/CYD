package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**     * Which network decks this phone has paired, by board rather than by address.
 *
 * The address is DHCP's business and moves; the identity is the efuse tag the deck
 * puts in {@code /deck.json}, so a record is keyed by {@code <widget>-<TAG>}. A
 * board that is re-flashed keeps its record, a second board of the same widget is a
 * second record, and a foreign id turning up at a known address replaces the stale
 * record instead of being merged into it — two decks cannot honestly share an
 * address at the same moment, so that is the one case where the address is
 * evidence.
 *
 * Stored as one JSON array in SharedPreferences, the same shape {@link
 * ProjectStore} uses. This is the app's memory, not the deck's: nothing here is
 * read back by a board, and no token is stored.
 */
final class DeckRegistry {

    private static final String PREFS = "deck-registry";
    private static final String KEY_LIST = "list";
    private static final String KEY_VERSION = "version";

    /** The widget whose sign-in comes back to this app through a custom scheme. */
    static final String SPOTIFY = "spotify_deck_cyd24R";

    static final class Deck {
        String id = "";
        String widget = "";
        String tag = "";
        String label = "";
        String host = "";
        String ip = "";
        boolean signed;
        boolean setup;
        long seenMs;

        Deck() {
        }

        Deck(Deck other) {
            id = other.id;
            widget = other.widget;
            tag = other.tag;
            label = other.label;
            host = other.host;
            ip = other.ip;
            signed = other.signed;
            setup = other.setup;
            seenMs = other.seenMs;
        }

        /** The descriptor's name, or a plain fallback when the deck named none. */
        String name() {
            return label == null || label.isEmpty() ? "Deck" : label;
        }

        /** {@code A1B2C3 · 192.168.1.50 · signed in · 4m ago}. */
        String detail(long nowMs) {
            StringBuilder text = new StringBuilder();
            if (tag != null && !tag.isEmpty()) text.append(tag).append("  \u00B7  ");
            text.append(host).append("  \u00B7  ").append(state());
            if (seenMs > 0) text.append("  \u00B7  ").append(Ui.ago(seenMs, nowMs));
            return text.toString();
        }

        String state() {
            if (setup) return "in setup";
            return signed ? "signed in" : "not signed in";
        }
    }

    private DeckRegistry() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Bumped on every write so a screen can tell whether to rebuild. */
    static int version(Context context) {
        return prefs(context).getInt(KEY_VERSION, 0);
    }

    static List<Deck> all(Context context) {
        final List<Deck> out = new ArrayList<>();
        final String raw = prefs(context).getString(KEY_LIST, null);
        if (raw == null) return out;
        try {
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject json = array.optJSONObject(i);
                if (json != null) out.add(fromJson(json));
            }
        } catch (JSONException e) {
            // Unreadable store: an empty list is better than a crash, and pairing
            // again is one tap.
            out.clear();
        }
        return out;
    }

    /** Every record for one widget, most recently seen first. */
    static List<Deck> forWidget(Context context, String widget) {
        final List<Deck> out = new ArrayList<>();
        for (Deck deck : all(context)) {
            if (widget != null && widget.equals(deck.widget)) out.add(deck);
        }
        out.sort((a, b) -> Long.compare(b.seenMs, a.seenMs));
        return out;
    }

    static Deck byHost(Context context, String host) {
        for (Deck deck : all(context)) {
            if (host != null && host.equalsIgnoreCase(deck.host)) return deck;
        }
        return null;
    }

    /**
     * Writes one record and drops the ones it displaces: any other id at this
     * address (a different board took it over) and the older copy of this id (the
     * deck moved, or its state changed). One id, one address, one row.
     */
    static void upsert(Context context, Deck deck) {
        if (deck == null || deck.id == null || deck.id.isEmpty()) return;
        final List<Deck> decks = all(context);
        for (int i = decks.size() - 1; i >= 0; --i) {
            final Deck other = decks.get(i);
            final boolean sameDeck = deck.id.equals(other.id);
            final boolean sameAddress = !deck.host.isEmpty()
                    && deck.host.equalsIgnoreCase(other.host);
            if (sameDeck || sameAddress) decks.remove(i);
        }
        decks.add(new Deck(deck));
        save(context, decks);
    }

    static void forget(Context context, String id) {
        final List<Deck> decks = all(context);
        for (int i = decks.size() - 1; i >= 0; --i) {
            if (decks.get(i).id.equals(id)) decks.remove(i);
        }
        save(context, decks);
    }

    /**
     * Reads {@code /deck.json} once and records what it says. This is what turns the
     * first browser sign-in into a pairing without a second step: the app already
     * has the address from the redirect's state, and the deck is awake at the far
     * end of it.
     */
    static void rememberLan(Context context, String host) {
        if (host == null || host.isEmpty()) return;
        final LanDeck.Reply reply = LanDeck.fetch(LanDeck.identityUrl(host), 3000, 5000);
        if (reply.status != 200) return;
        final DeckIdentity identity = DeckIdentity.parse(reply.body);
        if (!identity.usable()) return;
        final Deck deck = new Deck();
        deck.id = identity.id;
        deck.widget = identity.widget;
        deck.tag = identity.tag;
        deck.label = identity.label;
        deck.host = host;
        deck.ip = identity.ip;
        deck.signed = identity.signed;
        deck.setup = identity.setup;
        deck.seenMs = System.currentTimeMillis();
        upsert(context.getApplicationContext(), deck);
    }

    private static void save(Context context, List<Deck> decks) {
        final JSONArray array = new JSONArray();
        for (Deck deck : decks) array.put(toJson(deck));
        prefs(context).edit()
                .putString(KEY_LIST, array.toString())
                .putInt(KEY_VERSION, prefs(context).getInt(KEY_VERSION, 0) + 1)
                .apply();
    }

    private static JSONObject toJson(Deck deck) {
        final JSONObject json = new JSONObject();
        try {
            json.put("id", deck.id);
            json.put("widget", deck.widget);
            json.put("tag", deck.tag);
            json.put("label", deck.label);
            json.put("host", deck.host);
            json.put("ip", deck.ip);
            json.put("signed", deck.signed);
            json.put("setup", deck.setup);
            json.put("seen", deck.seenMs);
        } catch (JSONException ignored) {
            // Every value here is a string, a boolean or a number.
        }
        return json;
    }

    private static Deck fromJson(JSONObject json) {
        final Deck deck = new Deck();
        deck.id = json.optString("id", "");
        deck.widget = json.optString("widget", "");
        deck.tag = json.optString("tag", "");
        deck.label = json.optString("label", "");
        deck.host = json.optString("host", "");
        deck.ip = json.optString("ip", "");
        deck.signed = json.optBoolean("signed", false);
        deck.setup = json.optBoolean("setup", false);
        deck.seenMs = json.optLong("seen", 0);
        return deck;
    }
}
