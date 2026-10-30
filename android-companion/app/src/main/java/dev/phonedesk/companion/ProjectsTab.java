package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Project shelf: one card per CYD project, filtered by status.
 *
 * Flashing lives on the BOARD tab. This one answers what a project is, what it
 * needs, and how far it got.
 */
final class ProjectsTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout body;
    private String signature = "";
    /** Status chip selection; -1 shows every project. */
    private int activeFilter = -1;
    /** Files handed to {@link #build}, so the deck card can reach its sketch. */
    private List<FlashLibrary.Entry> currentEntries = new ArrayList<>();

    ProjectsTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void refresh() {
        List<FlashLibrary.Entry> entries = FlashLibrary.list(activity);
        StringBuilder sig = new StringBuilder();
        for (FlashLibrary.Entry entry : entries) {
            sig.append(entry.name).append(':').append(entry.size).append(';');
        }
        // Everything that changes what a card says, so a LOG or STATUS tap shows up.
        sig.append('v').append(ProjectStore.version(activity))
                .append('b').append(BoardCatalog.selectedIndex(activity))
                .append('f').append(activeFilter);
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            int keepScroll = getScrollY();
            try {
                build(entries);
            } catch (RuntimeException e) {
                showBuildError(e);
            }
            post(() -> scrollTo(0, keepScroll));
        }
    }

    /** A crash here would take the app down and tell nobody why. */
    private void showBuildError(RuntimeException error) {
        body.removeAllViews();

        body.addView(Ui.text(activity, "SHELF FAILED TO DRAW", 18, Ui.PINK));
        TextView hint = Ui.text(activity,
                "Building the list threw. Your stored projects are untouched. Send the trace.",
                12, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        body.addView(hint);

        final String trace = describe(error);
        TextView detail = Ui.text(activity, trace, 10, Ui.TEXT);
        detail.setPadding(0, Ui.dp(activity, 10), 0, 0);
        detail.setLineSpacing(Ui.dp(activity, 2), 1f);
        body.addView(detail);

        Button copy = Ui.action(activity, "COPY TRACE", true);
        copy.setOnClickListener(v -> Actions.copyOrSay(activity, "crash trace", trace));
        Ui.space(activity, copy, 12);
        body.addView(copy);
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        text.append(error.getClass().getName());
        if (error.getMessage() != null) text.append(": ").append(error.getMessage());
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < frames.length && i < 12; ++i) {
            text.append('\n').append(frames[i].toString());
        }
        return text.toString();
    }

    private void build(List<FlashLibrary.Entry> entries) {
        body.removeAllViews();
        currentEntries = entries;
        final List<ProjectStore.Project> projects = ProjectStore.all(activity);

        body.addView(Ui.text(activity, "PROJECTS", 20, Ui.TEXT));
        TextView subtitle = Ui.text(activity, "What to flash, and how far it got.", 12, Ui.DIM);
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, 0);
        body.addView(subtitle);

        body.addView(filterRow(projects));

        int shown = 0;
        for (ProjectStore.Project project : projects) {
            if (activeFilter >= 0 && project.status != activeFilter) continue;
            body.addView(projectCard(project));
            ++shown;
            if (project.inside()) {
                WidgetCatalog.Widget widget = WidgetCatalog.forProject(activity, project.id);
                if (widget != null) body.addView(libraryCard(widget));
            }
        }
        if (shown == 0) {
            body.addView(Ui.text(activity, "Nothing in that status yet.", 12, Ui.DIM));
        }
    }

    /** ALL plus one chip per status, each with its count. */
    private LinearLayout filterRow(List<ProjectStore.Project> projects) {
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);

        LinearLayout chips = Ui.row(activity);
        chips.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 4));
        addChip(chips, "ALL " + projects.size(), -1);
        for (int i = 0; i < ProjectStore.STATUS_LABELS.length; ++i) {
            addChip(chips, ProjectStore.STATUS_LABELS[i] + " "
                    + ProjectStore.count(projects, i), i);
        }
        scroller.addView(chips);
        return columnOf(scroller);
    }

    /** One-child vertical wrapper so the scroller behaves inside the body. */
    private LinearLayout columnOf(android.view.View child) {
        LinearLayout wrapper = Ui.column(activity);
        wrapper.addView(child, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void addChip(LinearLayout row, String label, int filter) {
        final boolean on = activeFilter == filter;
        TextView chip = Ui.text(activity, label, 10, on ? Ui.BG : Ui.DIM);
        chip.setLetterSpacing(0.1f);
        chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.setBackground(Ui.rounded(activity, on ? Ui.ACCENT : Ui.CARD_HI,
                on ? Ui.ACCENT : Ui.EDGE, 8));
        chip.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 5),
                Ui.dp(activity, 9), Ui.dp(activity, 5));
        chip.setOnClickListener(v -> {
            activeFilter = filter;
            signature = "";
            refresh();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(activity, 6);
        row.addView(chip, params);
    }

    private LinearLayout projectCard(ProjectStore.Project project) {
        LinearLayout card = Ui.card(activity);

        LinearLayout meta = Ui.row(activity);
        TextView kind = Ui.text(activity, project.kindLabel(), 10, Ui.DIM);
        kind.setLetterSpacing(0.12f);
        kind.setBackground(Ui.rounded(activity, Ui.CARD_HI, Ui.EDGE, 6));
        kind.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 2),
                Ui.dp(activity, 6), Ui.dp(activity, 2));
        meta.addView(kind);

        TextView status = Ui.text(activity, ProjectStore.statusLabel(project.status), 10,
                ProjectStore.statusColor(project.status));
        status.setLetterSpacing(0.12f);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status.setPadding(Ui.dp(activity, 8), 0, 0, 0);
        meta.addView(status);
        card.addView(meta);

        TextView name = Ui.text(activity, project.name, 16, Ui.TEXT);
        name.setPadding(0, Ui.dp(activity, 6), 0, 0);
        card.addView(name);

        if (!project.body.isEmpty()) {
            TextView description = Ui.text(activity, project.body, 12, Ui.DIM);
            description.setPadding(0, Ui.dp(activity, 4), 0, 0);
            description.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(description);
        }

        if (!project.libraries.isEmpty()) {
            TextView libs = Ui.text(activity, "NEEDS  " + project.libraries, 11, Ui.ACCENT);
            libs.setPadding(0, Ui.dp(activity, 6), 0, 0);
            libs.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(libs);
        }

        card.addView(logView(project));

        LinearLayout primary = Ui.row(activity);
        primary.setPadding(0, Ui.dp(activity, 10), 0, 0);

        if (project.inside()) {
            WidgetCatalog.Widget widget = WidgetCatalog.forProject(activity, project.id);
            FlashLibrary.Entry bundled = widget == null ? null : bundledEntry(widget.sketch);
            if (bundled != null) {
                Button copy = Ui.action(activity, "COPY DROID", true);
                copy.setOnClickListener(v -> Actions.copyOrSay(activity, bundled.name,
                        FlashLibrary.read(activity, bundled)));
                primary.addView(copy);

                Button save = Ui.action(activity, "SAVE", false);
                save.setOnClickListener(v -> Actions.save(activity, bundled));
                primary.addView(save, spaced(8));
            }
        } else {
            Button installer = Ui.action(activity, "INSTALLER", true);
            installer.setOnClickListener(v -> Actions.openUrl(activity, project.installerUrl));
            primary.addView(installer);

            Button source = Ui.action(activity, "SOURCE", false);
            source.setOnClickListener(v -> Actions.openUrl(activity, project.sourceUrl));
            primary.addView(source, spaced(8));
        }
        card.addView(primary);

        LinearLayout secondary = Ui.row(activity);
        secondary.setPadding(0, Ui.dp(activity, 8), 0, 0);

        Button log = Ui.action(activity, "LOG", false);
        log.setOnClickListener(v -> logBuild(project));
        secondary.addView(log);

        Button state = Ui.action(activity, "STATUS", false);
        state.setOnClickListener(v -> pickStatus(project));
        secondary.addView(state, spaced(8));

        if (!project.inside()) {
            Button recipe = Ui.action(activity, "RECIPE", false);
            recipe.setOnClickListener(v -> copyRecipe(project));
            secondary.addView(recipe, spaced(8));
        }
        card.addView(secondary);
        Ui.space(activity, card, 10);
        return card;
    }

    private void pickStatus(ProjectStore.Project project) {
        new AlertDialog.Builder(activity)
                .setTitle("Status · " + project.name)
                .setSingleChoiceItems(ProjectStore.STATUS_LABELS, project.status,
                        (dialog, which) -> {
                            ProjectStore.Project updated = new ProjectStore.Project(project);
                            updated.status = which;
                            updated.updatedMs = System.currentTimeMillis();
                            ProjectStore.write(activity, updated);
                            dialog.dismiss();
                            signature = "";
                            refresh();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void logBuild(ProjectStore.Project project) {
        final boolean hasLog = !project.build.isEmpty() || !project.notes.isEmpty()
                || project.touch != FirmwareNotes.TOUCH_UNTESTED;

        LinearLayout form = Ui.column(activity);
        int side = Ui.dp(activity, 18);
        form.setPadding(side, Ui.dp(activity, 8), side, 0);

        EditText build = new EditText(activity);
        build.setHint("Build, e.g. 2432S024R-v2.1");
        build.setSingleLine(true);
        build.setText(project.build);
        form.addView(build);

        Spinner touch = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, FirmwareNotes.TOUCH_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        touch.setAdapter(adapter);
        touch.setSelection(project.touch);
        form.addView(touch);

        EditText notes = new EditText(activity);
        notes.setHint("Notes: screen, calibration, what broke");
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setMinLines(3);
        notes.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        notes.setText(project.notes);
        form.addView(notes);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Log " + project.name)
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    ProjectStore.Project updated = new ProjectStore.Project(project);
                    updated.build = build.getText().toString().trim();
                    updated.notes = notes.getText().toString().trim();
                    updated.touch = touch.getSelectedItemPosition();
                    updated.updatedMs = System.currentTimeMillis();
                    // Logging means it reached the board.
                    if (updated.status == ProjectStore.STATUS_WANT) {
                        updated.status = ProjectStore.STATUS_FLASHED;
                    }
                    ProjectStore.write(activity, updated);
                    signature = "";
                    refresh();
                    Actions.toast(activity, "Saved");
                })
                .setNegativeButton("Cancel", null);

        if (hasLog) {
            builder.setNeutralButton("Clear", (dialog, which) -> {
                ProjectStore.Project updated = new ProjectStore.Project(project);
                updated.build = "";
                updated.notes = "";
                updated.touch = FirmwareNotes.TOUCH_UNTESTED;
                updated.updatedMs = System.currentTimeMillis();
                ProjectStore.write(activity, updated);
                signature = "";
                refresh();
            });
        }
        builder.show();
    }

    private TextView logView(ProjectStore.Project project) {
        boolean logged = !project.build.isEmpty() || !project.notes.isEmpty()
                || project.touch != FirmwareNotes.TOUCH_UNTESTED;
        TextView view;
        if (!logged) {
            view = Ui.text(activity, "No log yet.", 11, Ui.DIM);
        } else {
            StringBuilder text = new StringBuilder();
            text.append("TOUCH: ").append(FirmwareNotes.touchLabel(project.touch).toUpperCase());
            if (!project.build.isEmpty()) text.append("   ·   BUILD: ").append(project.build);
            text.append("   ·   ").append(Ui.ago(project.updatedMs, System.currentTimeMillis()));
            if (!project.notes.isEmpty()) text.append('\n').append(project.notes);
            view = Ui.text(activity, text.toString(), 11,
                    project.touch == FirmwareNotes.TOUCH_BROKEN ? Ui.PINK : Ui.GOOD);
            view.setLineSpacing(Ui.dp(activity, 2), 1f);
        }
        view.setPadding(0, Ui.dp(activity, 8), 0, 0);
        return view;
    }

    /** A flash recipe, not code: these are PlatformIO builds, not ArduinoDroid sketches. */
    private void copyRecipe(ProjectStore.Project project) {
        BoardCatalog.Board board = BoardCatalog.selected(activity);
        StringBuilder text = new StringBuilder();
        text.append("# ").append(project.name).append(" — ").append(board.label)
                .append(" (").append(board.code).append(")\n")
                .append("# Status: ").append(ProjectStore.statusLabel(project.status)).append('\n')
                .append("# Needs: ").append(project.libraries.isEmpty()
                        ? "prebuilt image" : project.libraries).append('\n')
                .append("# Installer: ").append(project.installerUrl).append('\n')
                .append("# Source: ").append(project.sourceUrl).append('\n')
                .append("# Notes: ").append(project.notes).append('\n')
                .append("python -m esptool --chip esp32 --baud 921600 ")
                .append("write_flash 0x0 <merged-").append(board.code).append(".bin>");

        if (!project.build.isEmpty() || project.touch != FirmwareNotes.TOUCH_UNTESTED) {
            text.append("\n# Log: build=").append(project.build.isEmpty() ? "—" : project.build)
                    .append(", touch=").append(FirmwareNotes.touchLabel(project.touch));
        }
        Actions.copyOrSay(activity, project.name + " recipe", text.toString());
    }

    /**
     * The sketch this widget's descriptor names, or null when the APK does not
     * carry it. Matched by name because the shelf holds every bundled widget:
     * taking the first bundled entry would hand the card whichever sorts first.
     */
    private FlashLibrary.Entry bundledEntry(String sketch) {
        for (FlashLibrary.Entry entry : currentEntries) {
            if (entry.bundled && entry.name.equalsIgnoreCase(sketch)) return entry;
        }
        return null;
    }

    private static final String[] ARDUINODROID_PACKAGES = {
            "name.antonsmirnov.android.arduinodroid2",
            "name.antonsmirnov.android.arduinodroid"
    };

    /** Libraries this widget needs, read from its descriptor rather than a list here. */
    private LinearLayout libraryCard(WidgetCatalog.Widget widget) {
        LinearLayout card = Ui.card(activity);

        TextView title = Ui.text(activity, "LIBRARIES", 16, Ui.TEXT);
        card.addView(title);

        String warning = "";
        if (!widget.never.isEmpty()) {
            warning = " Never install " + String.join(" or ", widget.never)
                    + ": it shadows the ESP32 BLE headers and every build fails.";
        }
        TextView hint = Ui.text(activity, widget.libraries.isEmpty()
                ? "Nothing extra to install for " + widget.name + "."
                : "INSTALL copies the name and opens ArduinoDroid's library list." + warning,
                11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        for (String library : widget.libraries) {
            TextView name = Ui.text(activity, library, 13, Ui.TEXT);
            name.setPadding(0, Ui.dp(activity, 10), 0, 0);
            card.addView(name);

            LinearLayout row = Ui.row(activity);
            row.setPadding(0, Ui.dp(activity, 6), 0, 0);

            Button install = Ui.action(activity, "INSTALL", true);
            install.setOnClickListener(v -> installLibrary(library, library));
            row.addView(install);

            Button copy = Ui.action(activity, "COPY NAME", false);
            copy.setOnClickListener(v -> Actions.copyOrSay(activity, library, library));
            row.addView(copy, spaced(8));
            card.addView(row);
        }

        if (!widget.libraries.isEmpty()) {
            final String joined = String.join("\n", widget.libraries);
            LinearLayout actions = Ui.row(activity);
            actions.setPadding(0, Ui.dp(activity, 12), 0, 0);

            Button all = Ui.action(activity, "INSTALL ALL", true);
            all.setOnClickListener(v -> installLibrary(widget.name, joined));
            actions.addView(all);

            Button copyAll = Ui.action(activity, "COPY ALL", false);
            copyAll.setOnClickListener(v -> Actions.copyOrSay(activity, widget.name, joined));
            actions.addView(copyAll, spaced(8));
            card.addView(actions);
        }

        Button open = Ui.action(activity, "OPEN ARDUINODROID", false);
        open.setOnClickListener(v -> openArduinoDroid());
        Ui.space(activity, open, 8);
        card.addView(open);

        Ui.space(activity, card, 10);
        return card;
    }

    /** Copy the name, then open ArduinoDroid's library list for pasting. */
    private void installLibrary(String label, String payload) {
        boolean copied = Actions.copy(activity, label, payload);
        if (openArduinoDroidLibraryManager()) {
            Actions.toast(activity, copied ? "Copied — paste it in Libraries"
                    : "Search Libraries for " + label);
            return;
        }
        openArduinoDroid();
        Actions.toast(activity, copied ? "Copied — tap Libraries in ArduinoDroid"
                : "Open Libraries and search for " + label);
    }

    /**
     * Opens the library screen rather than the editor. Activity names differ
     * between releases, so the installed package is scanned instead of hard-coding
     * a class that may not exist; the manifest <queries> entry makes it visible.
     */
    private boolean openArduinoDroidLibraryManager() {
        for (String pkg : ARDUINODROID_PACKAGES) {
            for (String className : libraryActivities(pkg)) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(pkg, className));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(intent);
                    return true;
                } catch (Exception ignored) {
                    // Not exported or renamed in this build: try the next one.
                }
            }
        }
        return false;
    }

    private List<String> libraryActivities(String pkg) {
        List<String> found = new ArrayList<>();
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (info.activities == null) return found;
            for (ActivityInfo candidate : info.activities) {
                if (candidate.name == null) continue;
                if (candidate.name.toLowerCase(Locale.US).contains("librar")) {
                    found.add(candidate.name);
                }
            }
        } catch (Exception ignored) {
            // Not installed, or hidden by package visibility.
        }
        return found;
    }

    /** Opens ArduinoDroid if it is installed, otherwise its store page. */
    private void openArduinoDroid() {
        for (String pkg : ARDUINODROID_PACKAGES) {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(launch);
                return;
            } catch (Exception ignored) {
                // Try the next package name.
            }
        }
        Actions.openUrl(activity,
                "https://play.google.com/store/apps/details?id=" + ARDUINODROID_PACKAGES[0]);
    }

    /** Wrap-content params with a left margin, for a second button in a row. */
    private LinearLayout.LayoutParams spaced(int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(activity, leftDp);
        return params;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) refresh();
    }
}
