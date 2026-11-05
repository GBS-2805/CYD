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
    /** A deck's own palette and page, keyed by {@link Known#key()}. */
    private static final String LOOK = "k:";
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
        rememberAt(context, identity, mac, fallbackName, System.currentTimeMillis());
    }

    /**
     * The same write at a known instant. A capture pins the row's age this way, so the
     * frame reads "1d ago" on every run instead of aging between them.
     */
    static void rememberAt(Context context, DeckIdentity identity, String mac,
                           String fallbackName, long seenMs) {
        if (mac == null || mac.isEmpty()) return;
        final String id = identity != null && identity.usable() ? identity.id : "";
        final String key = id.isEmpty() ? mac : id;
        final String label = name(identity, fallbackName, mac);

        final SharedPreferences.Editor edit = prefs(context).edit();
        for (Known other : all(context)) {
            final boolean sameDeck = other.id.equals(id);
            final boolean sameAddress = other.mac.equalsIgnoreCase(mac);
            if (sameDeck || sameAddress) edit.remove("d:" + other.key());
        }
        edit.remove("m:" + mac);
        edit.putString("d:" + key, (identity == null ? "" : identity.widget) + SEP
                + (identity == null ? "" : identity.tag) + SEP
                + label + SEP + mac + SEP + seenMs);
        edit.apply();
    }

    private static String name(DeckIdentity identity, String fallbackName, String mac) {
        if (identity != null && !identity.label.isEmpty()) return identity.label;
        return withMacSuffix(fallbackName, mac);
    }

    /**
     * Two boards of one widget are both "Phone Deck" until they are named, so an
     * unnamed one carries the tail of its address instead: two rows a person can
     * tell apart without opening either.
     */
    static String withMacSuffix(String name, String mac) {
        final String base = name == null || name.isEmpty() ? "Phone Deck" : name;
        if (mac == null) return base;
        final String tail = mac.replace(":", "");
        if (tail.length() < 4) return base;
        return base + " \u00b7" + tail.substring(tail.length() - 4);
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
                            withMacSuffix(parts.length > 0 ? parts[0] : "",
                                    key.substring(2)),
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

    /** Forgets the deck itself, wherever it is now addressed, and the look it had. */
    static void forget(Context context, String idOrMac) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (Known known : all(context)) {
            if (known.key().equals(idOrMac) || known.mac.equalsIgnoreCase(idOrMac)) {
                edit.remove("d:" + known.key());
                edit.remove(LOOK + known.key());
            }
        }
        edit.remove("m:" + idOrMac);
        edit.remove(LOOK + idOrMac);
        edit.apply();
    }

    /**
     * The palette and page a deck is drawn in on PAIRED. A deck that has never been
     * given one takes the app's own default, so the list never has a row with no
     * colour, and the answer survives a reboot because it is a preference.
     */
    static DeckLook look(Context context, String key) {
        return DeckLook.decode(prefs(context).getString(LOOK + key, ""), DeckLook.DEFAULT);
    }

    static void setLook(Context context, String key, DeckLook look) {
        if (key == null || key.isEmpty() || look == null) return;
        prefs(context).edit().putString(LOOK + key, look.encode()).apply();
    }

    static String preferred(Context context) {
        return prefs(context).getString(PREFERRED, "");
    }

    static void setPreferred(Context context, String mac) {
        prefs(context).edit().putString(PREFERRED, mac == null ? "" : mac).apply();
    }
}
