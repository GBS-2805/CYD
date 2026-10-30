package dev.phonedesk.companion;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Records the last uncaught exception on disk so it can be read back in the app.
 *
 * There is no adb on the phone this app is developed against, so a crash that
 * only happens there is otherwise invisible: the system dialog says "has a bug"
 * and nothing more. The handler writes the trace, then hands the exception to the
 * platform handler as usual — it never swallows a crash or keeps a broken process
 * alive.
 */
final class CrashLog {

    private static final String FILE = "last-crash.txt";

    private CrashLog() {
    }

    /** Call once, from the activity, before anything else can throw. */
    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            write(context.getApplicationContext(), thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void write(Context context, Thread thread, Throwable error) {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("thread: " + (thread == null ? "?" : thread.getName()));
        out.println("when: " + new java.util.Date().toString());
        out.println();
        error.printStackTrace(out);
        out.flush();

        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(new java.io.File(context.getFilesDir(), FILE), false);
            stream.write(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Nothing left to report to: the process is going down anyway.
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // As above.
                }
            }
        }
    }

    /** Last recorded trace, or null when the app has not crashed since install. */
    static String read(Context context) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(new java.io.File(context.getFilesDir(), FILE));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Read-only failure: the text is already in hand.
                }
            }
        }
    }

    static void clear(Context context) {
        new java.io.File(context.getFilesDir(), FILE).delete();
    }
}
