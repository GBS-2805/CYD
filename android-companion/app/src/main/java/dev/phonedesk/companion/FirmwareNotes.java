package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What the user actually flashed: build label, whether the touch panel came up,
 * and free-text notes. Keyed by firmware name, stored in SharedPreferences so a
 * record survives restarts but never leaves the phone.
 */
final class FirmwareNotes {

    static final int TOUCH_UNTESTED = 0;
    static final int TOUCH_WORKS = 1;
    static final int TOUCH_NEEDS_CALIBRATION = 2;
    static final int TOUCH_BROKEN = 3;

    static final String[] TOUCH_LABELS = {
            "Touch: untested",
            "Touch: works",
            "Touch: needs calibration",
            "Touch: broken"
    };

    private static final String PREFS = "fwlog";
    private static final char SEP = '\u0001';

    static final class Record {
        final String build;
        final String notes;
        final int touch;
        final long updatedMs;

        Record(String build, String notes, int touch, long updatedMs) {
            this.build = build;
            this.notes = notes;
            this.touch = touch;
            this.updatedMs = updatedMs;
        }
    }

    private FirmwareNotes() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Record load(Context context, String firmware) {
        String raw = prefs(context).getString(key(firmware), null);
        if (raw == null) return null;
        String[] parts = raw.split(String.valueOf(SEP), -1);
        String build = parts.length > 0 ? parts[0] : "";
        String notes = parts.length > 1 ? parts[1] : "";
        int touch = TOUCH_UNTESTED;
        if (parts.length > 2) {
            try {
                touch = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                // Older or corrupted entry: fall back to untested.
            }
        }
        long updated = 0;
        if (parts.length > 3) {
            try {
                updated = Long.parseLong(parts[3]);
            } catch (NumberFormatException ignored) {
                // Timestamp is cosmetic.
            }
        }
        if (touch < 0 || touch >= TOUCH_LABELS.length) touch = TOUCH_UNTESTED;
        return new Record(build, notes, touch, updated);
    }

    static void save(Context context, String firmware, String build, String notes, int touch) {
        int safeTouch = Math.max(0, Math.min(TOUCH_LABELS.length - 1, touch));
        String value = build + SEP + notes + SEP + safeTouch + SEP + System.currentTimeMillis();
        prefs(context).edit().putString(key(firmware), value).apply();
    }

    static void clear(Context context, String firmware) {
        prefs(context).edit().remove(key(firmware)).apply();
    }

    static String touchLabel(int touch) {
        if (touch < 0 || touch >= TOUCH_LABELS.length) return "untested";
        return TOUCH_LABELS[touch].substring("Touch: ".length());
    }

    private static String key(String firmware) {
        return "f:" + firmware;
    }
}
