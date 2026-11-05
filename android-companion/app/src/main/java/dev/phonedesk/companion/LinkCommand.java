package dev.phonedesk.companion;

import java.nio.charset.StandardCharsets;

/**
 * The one line that comes back up the link: {@code <serial>|<action>}, raised as a
 * notify on the deck's command characteristic when a transport chip is tapped.
 *
 * A chip is a request, not a change of state. The deck has no player of its own,
 * so this app runs the key against the phone's media session and the next mirror
 * line is what proves the round trip — the lit chip only means the notify left the
 * board.
 *
 * Plain Java with no Android imports, so test.sh can hold it to the sketch's own
 * buffer on a JVM. The rules come from {@code sendLinkCommand()} rather than from
 * taste:
 *
 * <ul>
 *   <li>{@code char line[20]}, so anything longer is a different kind of message
 *       rather than a longer one of these.</li>
 *   <li>The serial is a uint32 that restarts at 1 after every reboot and climbs by
 *       one per tap, so a repeat of the same value is one notify arriving twice,
 *       not a second tap.</li>
 *   <li>Only {@code prev}, {@code toggle} and {@code next} exist. A key this app
 *       cannot map is dropped: a transport control that guesses is worse than one
 *       that does nothing.</li>
 * </ul>
 */
final class LinkCommand {

    /** {@code char line[20]} in the sketch, terminator included. */
    static final int MAX_BYTES = 20;

    static final String PREVIOUS = "prev";
    static final String TOGGLE = "toggle";
    static final String NEXT = "next";

    /** Largest value a uint32 can hold; the sketch's counter never passes it. */
    private static final long MAX_SERIAL = 4_294_967_295L;

    final long serial;
    final String action;

    private LinkCommand(long serial, String action) {
        this.serial = serial;
        this.action = action;
    }

    /** The bytes Android hands {@code onCharacteristicChanged}; null when unusable. */
    static LinkCommand parse(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        return parse(new String(raw, StandardCharsets.UTF_8));
    }

    /** The same line as text, for tests and for a log that has already decoded it. */
    static LinkCommand parse(String text) {
        if (text == null) return null;
        // setValue() is given strlen(), so a terminator arrives only if a board
        // ever writes one; trailing NULs are padding, not characters.
        final String line = strip(text);
        if (line.isEmpty() || line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;

        final int bar = line.indexOf('|');
        if (bar <= 0 || bar == line.length() - 1) return null;

        final String digits = line.substring(0, bar);
        if (digits.length() > 10) return null;
        for (int i = 0; i < digits.length(); ++i) {
            final char c = digits.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        final long serial = Long.parseLong(digits);
        if (serial <= 0 || serial > MAX_SERIAL) return null;

        final String action = line.substring(bar + 1);
        if (!PREVIOUS.equals(action) && !TOGGLE.equals(action) && !NEXT.equals(action)) return null;
        return new LinkCommand(serial, action);
    }

    /**
     * True when this line is a tap the app has not already run. The board's counter
     * survives a repeat notify — the same value arriving twice is one gesture
     * reported twice — so the caller remembers the last one it ran.
     */
    static boolean fresh(LinkCommand command, long lastSerial) {
        return command != null && command.serial != lastSerial;
    }

    private static String strip(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\0' || Character.isWhitespace(text.charAt(end - 1)))) {
            --end;
        }
        int start = 0;
        while (start < end && Character.isWhitespace(text.charAt(start))) ++start;
        return text.substring(start, end);
    }
}
