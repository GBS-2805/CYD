package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Remembers decks the phone has linked to, so the monitor can list them even
 * when they are switched off, and so a manual choice of deck survives restarts.
 *
 * Keyed by <b>deck</b>, not by address: the record is the {@code <widget>-<TAG>}
 * from the board's identity characteristic, and the MAC is kept inside it as the
 * address the link happened over. A re-flash keeps its row, a second board of the
 * same widget is a second row, and a board that has not published a document
 * (flashed before the characteristic existed, or a read that never came back) is
 * stored under its MAC and upgraded the first time it answers.
 *
 * The preferred deck is still a MAC, because that is what a scan hands back.
 */
final class DeckStore {

    private static final String PREFS = "decks";
    private static final String PREFERRED = "preferred";
    private static final char SEP = '\u0001';

    static final class Known {
        /** {@code phone_deck_cyd24R-A1B2C3}, or "" for a deck that named nothing. */
        final String id;
        final String widget;
        final String tag;
        final String name;
        final String mac;
        final long seenMs;

        Known(String id, String widget, String tag, String name, String mac, long seenMs) {
            this.id = id;
            this.widget = widget;
            this.tag = tag;
            this.name = name;
            this.mac = mac;
            this.seenMs = seenMs;
        }

        /** The store key: the id when there is one, the address when there is not. */
        String key() {
            return id == null || id.isEmpty() ? mac : id;
        }
    }

    private DeckStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Writes what the deck said about itself, dropping the rows it displaces: an
     * older copy of this id (the board moved to another MAC) and any other id at
     * this MAC (a different board owns that address now). A record written before
     * this change is keyed by MAC alone and is removed here, so the list upgrades
     * itself the next time each board links.
     */
    static void remember(Context context, DeckIdentity identity, String mac, String fallbackName) {
        if (mac == null || mac.isEmpty()) return;
        final String id = identity != null && identity.usable() ? identity.id : "";
        final String key = id.isEmpty() ? mac : id;
        final String label = name(identity, fallbackName);

        final SharedPreferences.Editor edit = prefs(context).edit();
        for (Known other : all(context)) {
            final boolean sameDeck = other.id.equals(id);
            final boolean sameAddress = other.mac.equalsIgnoreCase(mac);
            if (sameDeck || sameAddress) edit.remove("d:" + other.key());
        }
        edit.remove("m:" + mac);
        edit.putString("d:" + key, (identity == null ? "" : identity.widget) + SEP
                + (identity == null ? "" : identity.tag) + SEP
                + label + SEP + mac + SEP + System.currentTimeMillis());
        edit.apply();
    }

    private static String name(DeckIdentity identity, String fallbackName) {
        if (identity != null && !identity.label.isEmpty()) return identity.label;
        return fallbackName == null || fallbackName.isEmpty() ? "Phone Deck" : fallbackName;
    }

    static List<Known> all(Context context) {
        List<Known> out = new ArrayList<>();
        try {
            for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
                String key = entry.getKey();
                if (!(entry.getValue() instanceof String)) continue;
                String[] parts = ((String) entry.getValue()).split("\u0001", -1);
                if (key.startsWith("d:")) {
                    // id, widget, tag, name, mac, seen
                    if (parts.length < 5) continue;
                    out.add(new Known(key.substring(2), parts[0], parts[1],
                            parts[2].isEmpty() ? "Phone Deck" : parts[2], parts[3],
                            parseLong(parts.length > 4 ? parts[4] : "")));
                } else if (key.startsWith("m:")) {
                    // A row written before decks had ids: name, seen.
                    out.add(new Known("", "", "",
                            parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "Phone Deck",
                            key.substring(2), parseLong(parts.length > 1 ? parts[1] : "")));
                }
            }
        } catch (ClassCastException ignored) {
            // Unreadable prefs: an empty list is better than a crash.
        }
        Collections.sort(out, new Comparator<Known>() {
            @Override
            public int compare(Known a, Known b) {
                return Long.compare(b.seenMs, a.seenMs);
            }
        });
        return out;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Known byMac(Context context, String mac) {
        if (mac == null) return null;
        for (Known known : all(context)) {
            if (known.mac.equalsIgnoreCase(mac)) return known;
        }
        return null;
    }

    /** Forgets the deck itself, wherever it is now addressed. */
    static void forget(Context context, String idOrMac) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (Known known : all(context)) {
            if (known.key().equals(idOrMac) || known.mac.equalsIgnoreCase(idOrMac)) {
                edit.remove("d:" + known.key());
            }
        }
        edit.remove("m:" + idOrMac);
        edit.apply();
    }

    static String preferred(Context context) {
        return prefs(context).getString(PREFERRED, "");
    }

    static void setPreferred(Context context, String mac) {
        prefs(context).edit().putString(PREFERRED, mac == null ? "" : mac).apply();
    }
}
