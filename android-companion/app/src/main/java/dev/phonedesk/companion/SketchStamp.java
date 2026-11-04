package dev.phonedesk.companion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The fingerprint of a sketch as it sits in this APK, so a COPY can be checked
 * against the build it came from instead of trusted by eye.
 *
 * The flash shelf prints this beside COPY. Arduino then reports the compiled
 * size of whatever it was handed ({@code Sketch uses 1,179,406 bytes}), and the
 * repository records the same file's md5, so a stale copy stops being a matter of
 * opinion: the shelf names the bytes that will be pasted, and the compiler's own
 * line says whether those bytes arrived. A sketch whose text was copied out of an
 * older install gives a different number on both sides of that comparison.
 *
 * Pure Java on purpose: test.sh runs it on a JVM with no Android SDK, and the
 * formatting rules are the ones a person reads off the card.
 */
final class SketchStamp {

    private SketchStamp() {
    }

    /** e.g. {@code 150,544 B  ·  351028cf}, or a word when there is nothing to hash. */
    static String of(byte[] bytes) {
        if (bytes == null) return "unreadable";
        if (bytes.length == 0) return "empty";
        return String.format(Locale.US, "%,d B  ·  %s", bytes.length, digest(bytes));
    }

    /** The first eight hex digits of the md5, which is how this tree quotes a sketch. */
    static String digest(byte[] bytes) {
        if (bytes == null) return "no md5";
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            StringBuilder out = new StringBuilder(32);
            for (byte b : md5.digest(bytes)) out.append(String.format(Locale.US, "%02x", b));
            return out.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is required of every JVM; say so rather than print an empty stamp.
            return "no md5";
        }
    }
}
