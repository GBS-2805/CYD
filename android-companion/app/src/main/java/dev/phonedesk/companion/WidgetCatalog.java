package dev.phonedesk.companion;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The widgets this app knows about: one entry per `widget.json` descriptor that
 * Gradle copied into `assets/widgets/<id>/`.
 *
 * The descriptor is the single place a project describes itself, so the shelf,
 * the notebook and the BLE matcher read the same fields instead of repeating
 * names in Java. Everything here comes from the assets, which means a new project
 * is a new folder plus its descriptor — no code change.
 *
 * When no descriptor is present (a build that skipped Gradle) the built-in
 * fallback below keeps the deck card instead of leaving the app empty.
 */
final class WidgetCatalog {

    /** Project records for bundled widgets carry this id prefix. */
    static final String PREFIX = "widget:";

    static final class Widget {
        String id = "";
        String name = "";
        String sketch = "";
        String board = "";
        String tagline = "";
        String body = "";
        String notes = "";
        String assetPath = "";
        List<String> libraries = new ArrayList<>();
        List<String> never = new ArrayList<>();
        List<String> nameMatch = new ArrayList<>();
        UUID service;

        boolean usable() {
            return !id.isEmpty() && !sketch.isEmpty();
        }
    }

    private static List<Widget> cache;

    private WidgetCatalog() {
    }

    static synchronized List<Widget> all(Context context) {
        if (cache == null) cache = load(context.getApplicationContext());
        return cache;
    }

    static Widget byId(Context context, String id) {
        if (id == null) return null;
        for (Widget widget : all(context)) {
            if (widget.id.equals(id)) return widget;
        }
        return null;
    }

    static Widget forProject(Context context, String projectId) {
        if (projectId == null || !projectId.startsWith(PREFIX)) return null;
        return byId(context, projectId.substring(PREFIX.length()));
    }

    static List<UUID> services(Context context) {
        List<UUID> out = new ArrayList<>();
        for (Widget widget : all(context)) {
            if (widget.service != null) out.add(widget.service);
        }
        return out;
    }

    static List<String> nameMatches(Context context) {
        List<String> out = new ArrayList<>();
        for (Widget widget : all(context)) out.addAll(widget.nameMatch);
        return out;
    }

    private static List<Widget> load(Context context) {
        List<Widget> out = new ArrayList<>();
        try {
            String[] dirs = context.getAssets().list("widgets");
            List<String> names = new ArrayList<>();
            if (dirs != null) Collections.addAll(names, dirs);
            Collections.sort(names);
            for (String dir : names) {
                byte[] bytes = read(context, "widgets/" + dir + "/widget.json");
                if (bytes == null) continue;
                Widget widget = parse(new String(bytes, StandardCharsets.UTF_8), dir);
                if (widget != null && widget.usable()) out.add(widget);
            }
        } catch (IOException ignored) {
            // No asset sync ran; the fallback below keeps the deck card.
        }
        if (out.isEmpty()) out.add(fallback());
        return out;
    }

    private static Widget parse(String json, String dir) {
        try {
            JSONObject root = new JSONObject(json);
            Widget widget = new Widget();
            widget.id = root.optString("id", dir).trim();
            widget.name = root.optString("name", widget.id);
            widget.sketch = root.optString("sketch", widget.id + ".ino").trim();
            widget.board = root.optString("board", "");
            widget.tagline = root.optString("tagline", "INSIDE APP");
            widget.body = root.optString("body", "");
            widget.notes = root.optString("notes", "");
            widget.assetPath = "widgets/" + dir + "/" + widget.sketch;
            readInto(root.optJSONArray("libraries"), widget.libraries, false);
            readInto(root.optJSONArray("never"), widget.never, false);
            JSONObject ble = root.optJSONObject("ble");
            if (ble != null) {
                readInto(ble.optJSONArray("nameMatch"), widget.nameMatch, true);
                widget.service = uuidOrNull(ble.optString("service", ""));
            }
            return widget;
        } catch (JSONException e) {
            return null;
        }
    }

    private static void readInto(JSONArray array, List<String> out, boolean lower) {
        if (array == null) return;
        for (int i = 0; i < array.length(); ++i) {
            String value = array.optString(i, "").trim();
            if (value.isEmpty()) continue;
            out.add(lower ? value.toLowerCase(Locale.US) : value);
        }
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] read(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A raw build that skipped Gradle still gets the deck, never an empty app. The
     * values mirror `cyd_phone_deck/widget.json`, which is what wins when Gradle did
     * run; edit both or the two builds show different cards.
     */
    private static Widget fallback() {
        Widget widget = new Widget();
        widget.id = "phone_deck";
        widget.name = "Phone Deck";
        widget.sketch = "cyd_phone_deck.ino";
        widget.board = "2432S024R";
        widget.tagline = "INSIDE APP";
        widget.body = "A desk dashboard for your phone: clock, battery and notifications, "
                + "drawn in the GMK Dracula keycap palette.";
        widget.notes = "Bundled with the app: copy it into ArduinoDroid and compile, there is "
                + "nothing to download.";
        widget.assetPath = "widgets/phone_deck/cyd_phone_deck.ino";
        widget.libraries.add("Adafruit GFX Library");
        widget.libraries.add("Adafruit ILI9341");
        widget.libraries.add("XPT2046_Touchscreen");
        widget.never.add("ArduinoBLE");
        widget.nameMatch.add("phone_widget");
        widget.nameMatch.add("phone deck");
        widget.service = UUID.fromString("6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10");
        return widget;
    }
}
