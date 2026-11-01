package dev.phonedesk.companion;

import java.nio.charset.StandardCharsets;

/**
 * The one line the deck's Now Playing characteristic takes: {@code
 * state|position|duration|title|artist|album}, seconds without a unit, {@code
 * play} / {@code pause} / {@code stop}.
 *
 * Plain Java with no Android imports, so test.sh can hold it to the sketch's
 * buffers on a JVM. Three rules matter more than the shape:
 *
 * <ul>
 *   <li>A {@code |} inside the text becomes {@code /}. The board splits on it, so
 *       a track called "Left | Right" would otherwise move every field after it
 *       and put a duration where the artist belongs.</li>
 *   <li>Lengths are byte caps that match the sketch's arrays — 48, 32 and 32
 *       including the terminator — and the whole line stays inside one ATT write
 *       at MTU 185, which carries 182 bytes.</li>
 *   <li>Anything unreadable is zero or empty, never a guess: the deck shows
 *       {@code --:--} rather than a duration it invented.</li>
 * </ul>
 */
final class NowPacket {

    /** The sketch's {@code NOW_TITLE} / {@code NOW_ARTIST} / {@code NOW_ALBUM}, minus the NUL. */
    static final int TITLE_BYTES = 47;
    static final int ARTIST_BYTES = 31;
    static final int ALBUM_BYTES = 31;

    /** One write at MTU 185 leaves this much for the value; the line never needs it all. */
    static final int ONE_WRITE_BYTES = 182;

    /** A day, in seconds: past that a line is broken rather than long. */
    private static final long MAX_SECONDS = 86_400L;

    private NowPacket() {
    }

    static String encode(String state, long positionMs, long durationMs,
                         String title, String artist, String album) {
        return word(state) + '|' + seconds(positionMs) + '|' + seconds(durationMs) + '|'
                + text(title, TITLE_BYTES) + '|' + text(artist, ARTIST_BYTES) + '|'
                + text(album, ALBUM_BYTES);
    }

    /**
     * What the deck is told: {@code play}, {@code pause} or {@code stop}. A state
     * this app does not know — buffering, seeking, a session that just died — reads
     * as {@code pause}, because the title on screen is still true and a cleared
     * screen would not be.
     */
    static String word(String state) {
        if (state == null) return "pause";
        if ("play".equals(state)) return "play";
        if ("stop".equals(state)) return "stop";
        return "pause";
    }

    static long seconds(long ms) {
        if (ms <= 0) return 0;
        final long value = Math.round(ms / 1000.0);
        return Math.min(value, MAX_SECONDS);
    }

    /** Flat, {@code |}-free, trimmed, and cut on a character boundary. */
    static String text(String value, int maxBytes) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        flat = flat.replaceAll("\\s+", " ").trim();
        final byte[] raw = flat.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) return flat;
        int end = maxBytes;
        while (end > 0 && (raw[end] & 0xC0) == 0x80) --end;
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }
}
