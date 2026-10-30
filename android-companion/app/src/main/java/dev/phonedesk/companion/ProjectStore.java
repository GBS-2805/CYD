package dev.phonedesk.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The project notebook: one record per CYD project, whether it is a prebuilt
 * image, a sketch compiled in ArduinoDroid, or the deck that ships inside this
 * app.
 *
 * Stored as a single JSON array in SharedPreferences. `org.json` is part of the
 * platform, so this still resolves no third-party dependency. Records are keyed
 * by id rather than by display name: renaming a project keeps its log attached,
 * which the old name-keyed {@link FirmwareNotes} could not do.
 *
 * The notebook is seeded once from {@link FirmwareCatalog} plus every bundled widget,
 * and any log already written through FirmwareNotes is carried over, so an
 * upgrade does not lose what the user typed.
 */
final class ProjectStore {

    // Status is what the user is doing with the project, not how it behaves:
    // touch and build are separate fields on the record.
    static final int STATUS_WANT = 0;
    static final int STATUS_FLASHED = 1;
    static final int STATUS_WORKING = 2;
    static final int STATUS_PARKED = 3;

    static final String[] STATUS_LABELS = {
            "WANT TO TRY", "FLASHED", "WORKING", "PARKED"
    };

    // What kind of file this project is built from, which decides the actions the
    // card offers: source for ArduinoDroid, a merged image for the USB flasher, or
    // the deck the APK already carries.
    static final int KIND_SKETCH = 0;
    static final int KIND_IMAGE = 1;
    static final int KIND_INSIDE = 2;

    /** Project ids for widgets that ship inside the APK, e.g. `widget:phone_deck`. */
    static final String WIDGET_PREFIX = "widget:";
    /** What that id looked like before the descriptors; renamed in place on read. */
    private static final String LEGACY_DECK_ID = "deck";

    private static final String PREFS = "projects";
    private static final String KEY_LIST = "list";
    private static final String KEY_VERSION = "version";

    static final class Project {
        String id;
        String name;
        String tagline;
        String body;
        String libraries;
        String sourceUrl;
        String installerUrl;
        String notes;
        String build;
        String fileName;
        int kind;
        int status;
        int touch;
        long updatedMs;

        Project() {
        }

        Project(Project other) {
            id = other.id;
            name = other.name;
            tagline = other.tagline;
            body = other.body;
            libraries = other.libraries;
            sourceUrl = other.sourceUrl;
            installerUrl = other.installerUrl;
            notes = other.notes;
            build = other.build;
            fileName = other.fileName;
            kind = other.kind;
            status = other.status;
            touch = other.touch;
            updatedMs = other.updatedMs;
        }

        boolean inside() {
            return kind == KIND_INSIDE;
        }

        String kindLabel() {
            if (kind == KIND_INSIDE) return "INSIDE APP";
            return kind == KIND_SKETCH ? "SKETCH" : "IMAGE";
        }
    }

    private ProjectStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Bumped on every write so a screen can tell whether to rebuild. */
    static int version(Context context) {
        return prefs(context).getInt(KEY_VERSION, 0);
    }

    static List<Project> all(Context context) {
        String raw = prefs(context).getString(KEY_LIST, null);
        if (raw == null) return withWidgets(context, seed(context));
        List<Project> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); ++i) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) out.add(fromJson(json));
            }
        } catch (JSONException e) {
            // Unreadable store: fall back to the seeds rather than showing nothing.
            return withWidgets(context, seed(context));
        }
        if (out.isEmpty()) return withWidgets(context, seed(context));
        return withWidgets(context, out);
    }

    /**
     * Keeps the notebook in step with the widgets the APK carries: the old `deck`
     * record is renamed in place so an upgrade keeps its log, and any widget with
     * no record yet gets its seed. Writes only when something actually changed.
     */
    private static List<Project> withWidgets(Context context, List<Project> projects) {
        boolean changed = false;
        for (Project project : projects) {
            if (LEGACY_DECK_ID.equals(project.id)) {
                project.id = WIDGET_PREFIX + "phone_deck";
                changed = true;
            }
        }
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(context)) {
            String id = WIDGET_PREFIX + widget.id;
            boolean present = false;
            for (Project project : projects) {
                if (id.equals(project.id)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                projects.add(widgetSeed(widget));
                changed = true;
            }
        }
        if (changed) save(context, projects);
        return projects;
    }

    static Project byId(Context context, String id) {
        for (Project project : all(context)) {
            if (project.id.equals(id)) return project;
        }
        return null;
    }

    /** Writes one record, replacing any with the same id, and bumps the version. */
    static void write(Context context, Project project) {
        if (project == null || project.id == null) return;
        List<Project> projects = all(context);
        boolean replaced = false;
        for (int i = 0; i < projects.size(); ++i) {
            if (projects.get(i).id.equals(project.id)) {
                projects.set(i, new Project(project));
                replaced = true;
                break;
            }
        }
        if (!replaced) projects.add(new Project(project));
        save(context, projects);
    }

    static void remove(Context context, String id) {
        List<Project> projects = all(context);
        for (int i = 0; i < projects.size(); ++i) {
            if (projects.get(i).id.equals(id)) {
                projects.remove(i);
                break;
            }
        }
        save(context, projects);
    }

    static void save(Context context, List<Project> projects) {
        JSONArray array = new JSONArray();
        for (Project project : projects) array.put(toJson(project));
        prefs(context).edit()
                .putString(KEY_LIST, array.toString())
                .putInt(KEY_VERSION, prefs(context).getInt(KEY_VERSION, 0) + 1)
                .apply();
    }

    static int count(List<Project> projects, int status) {
        int total = 0;
        for (Project project : projects) {
            if (project.status == status) ++total;
        }
        return total;
    }

    static String statusLabel(int status) {
        if (status < 0 || status >= STATUS_LABELS.length) return STATUS_LABELS[STATUS_WANT];
        return STATUS_LABELS[status];
    }

    static int statusColor(int status) {
        switch (status) {
            case STATUS_WORKING:
                return Ui.GOOD;
            case STATUS_FLASHED:
                return Ui.ACCENT;
            case STATUS_PARKED:
                return Ui.DIM;
            default:
                return Ui.VIOLET;
        }
    }

    // ---------------------------------------------------------------- seeds ---

    private static List<Project> seed(Context context) {
        List<Project> out = new ArrayList<>();
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(context)) {
            out.add(widgetSeed(widget));
        }
        int index = 0;
        for (FirmwareCatalog.Item item : FirmwareCatalog.all()) {
            Project project = new Project();
            project.id = item.name.equals("ESP32 Marauder") ? "marauder"
                    : item.name.toLowerCase(java.util.Locale.US).replace(' ', '-');
            project.name = item.name;
            project.tagline = item.tagline;
            project.body = item.body;
            project.libraries = item.libraries;
            project.sourceUrl = item.repoUrl;
            project.installerUrl = item.flasherUrl;
            project.notes = item.note;
            project.kind = KIND_IMAGE;
            project.status = STATUS_WANT;
            project.touch = FirmwareNotes.TOUCH_UNTESTED;
            project.updatedMs = System.currentTimeMillis() - (long) index * 1000;

            // Carry over anything already logged through the old name-keyed store.
            FirmwareNotes.Record legacy = FirmwareNotes.load(context, item.name);
            if (legacy != null) {
                project.build = legacy.build;
                project.touch = legacy.touch;
                project.updatedMs = legacy.updatedMs;
                if (!legacy.notes.isEmpty()) {
                    project.notes = legacy.notes + "\n" + item.note;
                }
                if (legacy.touch != FirmwareNotes.TOUCH_UNTESTED
                        || !legacy.build.isEmpty()) {
                    project.status = legacy.touch == FirmwareNotes.TOUCH_BROKEN
                            ? STATUS_PARKED : STATUS_FLASHED;
                }
            }
            out.add(project);
            ++index;
        }
        save(context, out);
        return out;
    }

    private static Project widgetSeed(WidgetCatalog.Widget widget) {
        Project project = new Project();
        project.id = WIDGET_PREFIX + widget.id;
        project.name = widget.name;
        project.tagline = widget.tagline;
        project.body = widget.body;
        project.libraries = String.join(", ", widget.libraries);
        project.notes = widget.notes;
        project.sourceUrl = "";
        project.installerUrl = "";
        project.kind = KIND_INSIDE;
        project.status = STATUS_WORKING;
        project.touch = FirmwareNotes.TOUCH_WORKS;
        project.updatedMs = System.currentTimeMillis();
        return project;
    }

    // ------------------------------------------------------------- json glue ---

    private static JSONObject toJson(Project project) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", project.id);
            json.put("name", project.name);
            json.put("tagline", project.tagline);
            json.put("body", project.body);
            json.put("libraries", project.libraries);
            json.put("source", project.sourceUrl);
            json.put("installer", project.installerUrl);
            json.put("notes", project.notes);
            json.put("build", project.build);
            json.put("file", project.fileName);
            json.put("kind", project.kind);
            json.put("status", project.status);
            json.put("touch", project.touch);
            json.put("updated", project.updatedMs);
        } catch (JSONException ignored) {
            // Every value here is a string or a number; this cannot fail.
        }
        return json;
    }

    private static Project fromJson(JSONObject json) {
        Project project = new Project();
        project.id = json.optString("id", "");
        project.name = json.optString("name", "Untitled");
        project.tagline = json.optString("tagline", "");
        project.body = json.optString("body", "");
        project.libraries = json.optString("libraries", "");
        project.sourceUrl = json.optString("source", "");
        project.installerUrl = json.optString("installer", "");
        project.notes = json.optString("notes", "");
        project.build = json.optString("build", "");
        project.fileName = json.optString("file", "");
        project.kind = clamp(json.optInt("kind", KIND_IMAGE), 0, KIND_INSIDE);
        project.status = clamp(json.optInt("status", STATUS_WANT), 0, STATUS_PARKED);
        project.touch = clamp(json.optInt("touch", FirmwareNotes.TOUCH_UNTESTED), 0,
                FirmwareNotes.TOUCH_LABELS.length - 1);
        project.updatedMs = json.optLong("updated", 0);
        return project;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
