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
 */
final class DeckStore {

    private static final String PREFS = "decks";
    private static final String PREFERRED = "preferred";

    static final class Known {
        final String mac;
        final String name;
        final long seenMs;

        Known(String mac, String name, long seenMs) {
            this.mac = mac;
            this.name = name;
            this.seenMs = seenMs;
        }
    }

    private DeckStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void remember(Context context, String mac, String name) {
        if (mac == null || mac.isEmpty()) return;
        String label = name == null || name.isEmpty() ? "Phone Deck" : name;
        prefs(context).edit()
                .putString("m:" + mac, label + '\u0001' + System.currentTimeMillis())
                .apply();
    }

    static List<Known> all(Context context) {
        List<Known> out = new ArrayList<>();
        try {
            for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("m:") || !(entry.getValue() instanceof String)) continue;
                String[] parts = ((String) entry.getValue()).split("\u0001");
                String name = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "Phone Deck";
                long seen = 0;
                if (parts.length > 1) {
                    try {
                        seen = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // Keep going with an unknown timestamp.
                    }
                }
                out.add(new Known(key.substring(2), name, seen));
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

    static void forget(Context context, String mac) {
        prefs(context).edit().remove("m:" + mac).apply();
    }

    static String preferred(Context context) {
        return prefs(context).getString(PREFERRED, "");
    }

    static void setPreferred(Context context, String mac) {
        prefs(context).edit().putString(PREFERRED, mac == null ? "" : mac).apply();
    }
}
