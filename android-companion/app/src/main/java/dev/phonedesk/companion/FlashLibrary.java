package dev.phonedesk.companion;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The flash shelf: the deck sketch bundled in the APK plus any `.ino` / `.bin`
 * files the user imported, ready to hand to ArduinoDroid or a PC flasher.
 *
 * The bundled copy is produced by Gradle from `cyd_phone_deck/cyd_phone_deck.ino`
 * at build time, so the firmware in this repo stays the single source of truth.
 */
final class FlashLibrary {

    private static final String DIR = "flash";

    static final class Entry {
        final String name;
        final long size;
        /** Where the sketch sits inside the APK, or null for an imported file. */
        final String assetPath;
        final boolean bundled;

        Entry(String name, long size, String assetPath) {
            this.name = name;
            this.size = size;
            this.assetPath = assetPath;
            this.bundled = assetPath != null;
        }

        boolean textual() {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".ino") || lower.endsWith(".txt") || lower.endsWith(".h");
        }
    }

    private FlashLibrary() {
    }

    private static File folder(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            // Read-only storage: listing will simply come back empty.
        }
        return dir;
    }

    static List<Entry> list(Context context) {
        List<Entry> out = new ArrayList<>();

        for (WidgetCatalog.Widget widget : WidgetCatalog.all(context)) {
            byte[] bytes = readAsset(context, widget.assetPath);
            // No syncWidgets run, or a descriptor naming a sketch that was not
            // packaged: leave it off the shelf rather than offer an empty copy.
            if (bytes == null) continue;
            out.add(new Entry(widget.sketch, bytes.length, widget.assetPath));
        }

        File[] files = folder(context).listFiles();
        if (files != null) {
            List<Entry> imported = new ArrayList<>();
            for (File file : files) {
                if (file.isFile()) imported.add(new Entry(file.getName(), file.length(), null));
            }
            Collections.sort(imported, new java.util.Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            out.addAll(imported);
        }
        return out;
    }

    /** Text only; use {@link #readBytes} for binaries. */
    static String read(Context context, Entry entry) {
        if (!entry.textual()) return null;
        byte[] bytes = readBytes(context, entry);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] readBytes(Context context, Entry entry) {
        if (entry.assetPath != null) return readAsset(context, entry.assetPath);
        try {
            return readAll(new FileInputStream(new File(folder(context), entry.name)));
        } catch (IOException e) {
            return null;
        }
    }

    static boolean delete(Context context, Entry entry) {
        if (entry.assetPath != null) return false;  // bundled sketches are part of the app
        File file = new File(folder(context), entry.name);
        return file.delete();
    }

    static boolean importUri(Context context, Uri uri) {
        String name = queryName(context, uri);
        if (name == null || name.trim().isEmpty()) name = "imported.ino";
        name = sanitize(name);
        if (!name.toLowerCase(Locale.US).matches(".*\\.(ino|bin|zip|txt|h)$")) {
            name += ".ino";
        }

        File target = uniqueName(folder(context), name);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            out = new FileOutputStream(target);
            copy(in, out);
            return true;
        } catch (IOException e) {
            target.delete();
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** Downloads is the folder ArduinoDroid's file picker can reach. */
    static String saveToDownloads(Context context, Entry entry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        byte[] bytes = readBytes(context, entry);
        if (bytes == null) return null;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, entry.name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeFor(entry.name));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        ContentResolver resolver = context.getContentResolver();
        Uri target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (target == null) return null;
        OutputStream out = null;
        try {
            out = resolver.openOutputStream(target);
            if (out == null) return null;
            out.write(bytes);
            return entry.name;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(out);
        }
    }

    // ------------------------------------------------------------- helpers ---

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".bin")) return "application/octet-stream";
        return "text/plain";
    }

    // Bundled sketches by asset path, read once: the Flash tab refreshes every
    // second and each sketch is ~70 kB. A miss is cached as null so an absent
    // asset is not reopened on every refresh.
    private static final Map<String, byte[]> cachedAssets = new HashMap<>();

    private static byte[] readAsset(Context context, String path) {
        if (path == null) return null;
        synchronized (cachedAssets) {
            if (cachedAssets.containsKey(path)) return cachedAssets.get(path);
            byte[] bytes = null;
            try {
                bytes = readAll(context.getAssets().open(path));
            } catch (IOException ignored) {
                // Build without Gradle: the asset sync task never ran.
            }
            cachedAssets.put(path, bytes);
            return bytes;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            copy(in, out);
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }

    private static String queryName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
            // Fall back to the last path segment below.
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.replace('/', '_');
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return cleaned.isEmpty() ? "imported.ino" : cleaned;
    }

    private static File uniqueName(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 2; i < 100; ++i) {
            candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + extension);
    }
}
