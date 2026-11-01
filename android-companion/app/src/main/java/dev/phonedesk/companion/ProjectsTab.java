package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Projects section: the shelf, alphabetical, and folded down.
 *
 * One accordion per project, because the shelf exists to answer "which one am I
 * on" before it answers "what does it need": a collapsed row is the name, the
 * status and the kind, and the body, log and actions open on tap. The libraries
 * a deck needs are a single disclaimer at the top rather than a card per project,
 * with each name a button that copies exactly what ArduinoDroid's library list
 * searches for.
 */
final class ProjectsTab extends ScrollView {

    private final Activity activity;
    private final LinearLayout body;
    private String signature = "";
    /** Status chip selection; -1 shows every project. */
    private int activeFilter = -1;
    /** Search text, lower-cased; empty shows every project. */
    private String query = "";
    /** One field instance, re-added on each rebuild so typing survives a refresh. */
    private final EditText search;
    /** Files handed to {@link #build}, so the deck card can reach its sketch. */
    private List<FlashLibrary.Entry> currentEntries = new ArrayList<>();
    /** Ids whose details are showing; everything else stays folded. */
    private final Set<String> expanded = new HashSet<>();

    ProjectsTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        search = new EditText(activity);
        search.setHint("Search projects");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextSize(13);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setBackground(Ui.rounded(activity, Ui.CARD_HI, Ui.EDGE, 8));
        search.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String next = s.toString().trim().toLowerCase(Locale.US);
                if (next.equals(query)) return;
                query = next;
                signature = "";
                refresh();
            }
        });
    }

    void refresh() {
        List<FlashLibrary.Entry> entries = FlashLibrary.list(activity);
        StringBuilder sig = new StringBuilder();
        for (FlashLibrary.Entry entry : entries) {
            sig.append(entry.name).append(':').append(entry.size).append(';');
        }
        // Everything that changes what a card says, so a LOG or STATUS tap shows up.
        sig.append('v').append(ProjectStore.version(activity))
                .append('d').append(DeckRegistry.version(activity))
                .append('b').append(BoardCatalog.selectedIndex(activity))
                .append('f').append(activeFilter)
                .append('q').append(query)
                .append('e').append(expanded.size());
        for (String id : expanded) sig.append('x').append(id);
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
        // Alphabetical by what the card says it is, so the shelf reads the same on
        // every phone regardless of the order projects were added or seeded.
        List<ProjectStore.Project> projects = new ArrayList<>(ProjectStore.all(activity));
        projects.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        body.addView(Ui.text(activity, "BOARDS", 20, Ui.TEXT));

        // Which board the shelf below belongs to. The bar's crumb already carries
        // the way back, so this line does not repeat the section name.
        TextView trail = Ui.text(activity, breadcrumb(projects), 10, Ui.DIM);
        trail.setLetterSpacing(0.12f);
        trail.setPadding(0, Ui.dp(activity, 6), 0, 0);
        body.addView(trail);

        TextView subtitle = Ui.text(activity, "Alphabetical. Tap a name for its notes.", 12, Ui.DIM);
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, 0);
        body.addView(subtitle);

        // The disclaimer comes before the shelf: what to install is a precondition
        // for every project below, not a footnote on one of them.
        View libraries = librariesCard();
        if (libraries != null) body.addView(libraries);

        // The field outlives a rebuild: it is detached and re-added, so its text
        // and cursor survive the status chips and the search itself re-filtering.
        final boolean hadFocus = search.hasFocus();
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = Ui.dp(activity, 14);
        body.addView(search, fieldParams);
        if (hadFocus) {
            search.post(() -> {
                search.requestFocus();
                search.setSelection(search.getText().length());
            });
        }

        body.addView(filterRow(projects));

        int shown = 0;
        for (ProjectStore.Project project : projects) {
            if (activeFilter >= 0 && project.status != activeFilter) continue;
            if (!matches(project)) continue;
            body.addView(projectCard(project));
            ++shown;
        }
        if (shown == 0) {
            String typed = search.getText().toString().trim();
            body.addView(Ui.text(activity, typed.isEmpty()
                    ? "Nothing in that status yet."
                    : "No project matches \"" + typed + "\".", 12, Ui.DIM));
        }
    }

    /**
     * Just the board(s) the shelf holds. The bar's crumb already says
     * {@code ‹ OFF-SITE} and the title says BOARDS, so leading with the section
     * name said the same thing twice; what is left answers only "which board".
     */
    private String breadcrumb(List<ProjectStore.Project> projects) {
        List<String> boards = new ArrayList<>();
        for (ProjectStore.Project project : projects) {
            String board = WidgetCatalog.boardLabel(activity, project.id).toUpperCase(Locale.US);
            if (!boards.contains(board)) boards.add(board);
        }
        if (boards.isEmpty()) return "NO BOARD";
        if (boards.size() > 3) return boards.size() + " BOARDS";
        return String.join("  \u00B7  ", boards);
    }

    /**
     * Every library the bundled widgets name, once each, read from the descriptors
     * rather than retyped here. A button carries the exact string ArduinoDroid's
     * library list searches for, because that is what gets pasted into it. Two
     * names share a row: stacked full width, three of them made the disclaimer
     * taller than the shelf it introduces.
     */
    private View librariesCard() {
        List<String> libraries = new ArrayList<>();
        List<String> never = new ArrayList<>();
        for (WidgetCatalog.Widget widget : WidgetCatalog.all(activity)) {
            for (String library : widget.libraries) {
                if (!libraries.contains(library)) libraries.add(library);
            }
            for (String banned : widget.never) {
                if (!never.contains(banned)) never.add(banned);
            }
        }
        if (libraries.isEmpty()) return null;

        body.addView(Ui.section(activity, "LIBRARIES NEEDED"));

        LinearLayout card = Ui.card(activity);
        TextView hint = Ui.text(activity,
                "Install these once in ArduinoDroid before compiling a deck. "
                        + "Tap a name to copy it.",
                11, Ui.DIM);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        if (!never.isEmpty()) {
            TextView warning = Ui.text(activity,
                    "Never install " + String.join(" or ", never)
                            + ": it shadows the ESP32 core's BLE headers and every build fails.",
                    11, Ui.PINK);
            warning.setPadding(0, Ui.dp(activity, 8), 0, 0);
            warning.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(warning);
        }

        // Two names to a row: stacked full width, three of them made the
        // disclaimer taller than the shelf it introduces, and the shelf is what
        // the screen is for. A name still copies the exact string ArduinoDroid's
        // library list searches for.
        LinearLayout pair = null;
        for (int i = 0; i < libraries.size(); ++i) {
            if (i % 2 == 0) {
                pair = Ui.row(activity);
                Ui.space(activity, pair, 8);
                card.addView(pair);
            }
            final String library = libraries.get(i);
            Button copy = Ui.action(activity, library, false);
            // 11sp so the longest name still fits half the card; Ui.action() sets
            // 13sp, which clipped "Adafruit GFX Library" once it was halved.
            copy.setTextSize(11);
            copy.setOnClickListener(v -> Actions.copyOrSay(activity, library, library));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) params.leftMargin = Ui.dp(activity, 8);
            pair.addView(copy, params);
        }

        Button open = Ui.action(activity, "OPEN ARDUINODROID", false);
        open.setOnClickListener(v -> openArduinoDroid());
        Ui.space(activity, open, 10);
        card.addView(open);

        Ui.space(activity, card, 10);
        return card;
    }

    /**
     * Name search: the project's own name first, then the bundled descriptor's
     * name, id and tagline, so a query finds a card by what it says it is.
     */
    private boolean matches(ProjectStore.Project project) {
        if (query.isEmpty()) return true;
        if (project.name.toLowerCase(Locale.US).contains(query)) return true;
        WidgetCatalog.Widget widget = WidgetCatalog.forProject(activity, project.id);
        if (widget == null) return false;
        return widget.name.toLowerCase(Locale.US).contains(query)
                || widget.id.toLowerCase(Locale.US).contains(query)
                || widget.tagline.toLowerCase(Locale.US).contains(query);
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

    /**
     * One accordion. The header is the whole card while folded — kind, status,
     * name — and the row that opens it is the name itself, which is what a thumb
     * aims at anyway.
     */
    private LinearLayout projectCard(ProjectStore.Project project) {
        LinearLayout card = Ui.card(activity);
        final boolean open = expanded.contains(project.id);

        LinearLayout header = Ui.row(activity);
        header.setClickable(true);

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
        header.addView(meta, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView fold = Ui.text(activity, open ? "-" : "+", 16, Ui.DIM);
        header.addView(fold);
        card.addView(header);

        TextView name = Ui.text(activity, project.name, 16, Ui.TEXT);
        name.setPadding(0, Ui.dp(activity, 6), 0, 0);
        card.addView(name);

        View.OnClickListener flip = v -> toggle(project.id);
        header.setOnClickListener(flip);
        name.setOnClickListener(flip);
        fold.setOnClickListener(flip);

        if (!open) {
            Ui.space(activity, card, 8);
            return card;
        }

        if (!project.body.isEmpty()) {
            TextView description = Ui.text(activity, project.body, 12, Ui.DIM);
            description.setPadding(0, Ui.dp(activity, 6), 0, 0);
            description.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(description);
        }

        if (!project.libraries.isEmpty()) {
            TextView libs = Ui.text(activity, "NEEDS  " + project.libraries, 11, Ui.ACCENT);
            libs.setPadding(0, Ui.dp(activity, 6), 0, 0);
            libs.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(libs);
        }

        WidgetCatalog.Widget lan = WidgetCatalog.forProject(activity, project.id);
        if (lan != null && lan.lan) card.addView(deckCard(lan));

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
        Ui.space(activity, card, 8);
        return card;
    }

    private void toggle(String id) {
        if (!expanded.remove(id)) expanded.add(id);
        signature = "";
        refresh();
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
     * The shelf's view of a deck that answers on the network: which boards are
     * paired, what each said the last time it was asked, and the one action that
     * starts its sign-in. Pairing is an address; the record is the board.
     */
    private LinearLayout deckCard(WidgetCatalog.Widget widget) {
        LinearLayout card = Ui.card(activity);

        TextView label = Ui.text(activity, "DECK", 10, Ui.DIM);
        label.setLetterSpacing(0.14f);
        card.addView(label);

        List<DeckRegistry.Deck> decks = DeckRegistry.forWidget(activity, widget.id);
        final long now = System.currentTimeMillis();
        if (decks.isEmpty()) {
            TextView empty = Ui.text(activity,
                    "No deck paired yet. The deck prints its address on its setup page, "
                            + "and in the serial log at 115200.", 11, Ui.DIM);
            empty.setPadding(0, Ui.dp(activity, 6), 0, 0);
            empty.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(empty);
        } else {
            for (DeckRegistry.Deck deck : decks) {
                // Two lines and then a row of buttons: beside a name this narrow,
                // two buttons left the detail clipped to its first field.
                LinearLayout row = Ui.column(activity);
                row.setPadding(0, Ui.dp(activity, 8), 0, 0);

                row.addView(Ui.text(activity, deck.name() + " \u00B7 " + deck.tag,
                        13, Ui.TEXT));
                TextView detail = Ui.text(activity, deck.detail(now), 11,
                        deck.signed ? Ui.GOOD : Ui.DIM);
                detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
                detail.setLineSpacing(Ui.dp(activity, 2), 1f);
                row.addView(detail);

                LinearLayout buttons = Ui.row(activity);
                buttons.setPadding(0, Ui.dp(activity, 8), 0, 0);

                Button signIn = Ui.action(activity, "SIGN IN", !deck.signed);
                signIn.setOnClickListener(v -> signIn(deck));
                buttons.addView(signIn);

                Button forget = Ui.action(activity, "FORGET", false);
                forget.setOnClickListener(v -> {
                    DeckRegistry.forget(activity, deck.id);
                    signature = "";
                    refresh();
                });
                buttons.addView(forget, spaced(8));
                row.addView(buttons);
                card.addView(row);
            }
        }

        Button pair = Ui.action(activity, "PAIR A DECK", false);
        pair.setOnClickListener(v -> pairDeck(widget));
        Ui.space(activity, pair, 10);
        card.addView(pair);

        TextView hint = Ui.text(activity,
                "One record per board: a deck's address can move, its tag cannot.",
                11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 8), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        Ui.space(activity, card, 10);
        return card;
    }

    /** Asks for the address the deck printed, then reads which board answered. */
    private void pairDeck(WidgetCatalog.Widget widget) {
        List<DeckRegistry.Deck> known = DeckRegistry.forWidget(activity, widget.id);
        EditText field = new EditText(activity);
        field.setHint(known.isEmpty() ? "192.168.1.50" : known.get(0).host);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setTextSize(14);
        field.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 8), Ui.dp(activity, 18), 0);

        new AlertDialog.Builder(activity)
                .setTitle("Pair a deck")
                .setMessage("The address the deck prints on its setup page. The phone has "
                        + "to be on the same WiFi as the deck.")
                .setView(field)
                .setPositiveButton("Pair",
                        (dialog, which) -> readAndPair(widget, field.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void readAndPair(WidgetCatalog.Widget widget, String typed) {
        final String host = LanDeck.host(typed);
        if (host.isEmpty()) {
            Actions.toast(activity, "That is not a LAN address, e.g. 192.168.1.50");
            return;
        }
        Actions.toast(activity, "Asking " + host + "\u2026");
        new Thread(() -> {
            final LanDeck.Reply reply = LanDeck.fetch(LanDeck.url(host, widget.identityPath));
            activity.runOnUiThread(() -> applyPair(widget, host, reply));
        }, "deck-pair").start();
    }

    /** What answered at that address decides: a deck of this widget, or nothing. */
    private void applyPair(WidgetCatalog.Widget widget, String host, LanDeck.Reply reply) {
        if (reply.status != 200) {
            Actions.toast(activity, reply.why(host));
            return;
        }
        DeckIdentity identity = DeckIdentity.parse(reply.body);
        if (!identity.usable()) {
            Actions.toast(activity, "Something answered at " + host + ", but it is not a deck.");
            return;
        }
        if (!widget.id.equals(identity.widget)) {
            Actions.toast(activity,
                    "That deck runs " + identity.widget + ", not " + widget.id + ".");
            return;
        }
        DeckRegistry.Deck deck = new DeckRegistry.Deck();
        deck.id = identity.id;
        deck.widget = identity.widget;
        deck.tag = identity.tag;
        deck.label = identity.label;
        deck.host = host;
        deck.ip = identity.ip;
        deck.signed = identity.signed;
        deck.setup = identity.setup;
        deck.seenMs = System.currentTimeMillis();
        DeckRegistry.upsert(activity, deck);
        signature = "";
        refresh();
        Actions.toast(activity, "Paired " + identity.label + " " + identity.tag);
    }

    /**
     * Starts the sign-in the deck's own page offers, without the page. The deck
     * builds the authorize URL and keeps the PKCE verifier, so the app only asks for
     * the URL and opens it; the code comes back through the custom scheme and
     * {@link SpotifyCallbackActivity} finishes it.
     */
    private void signIn(DeckRegistry.Deck deck) {
        if (deck.signed && !deck.setup) {
            Actions.toast(activity, "That deck is already signed in. Hold SETUP on it to "
                    + "sign in to another account.");
            return;
        }
        final String host = deck.host;
        Actions.toast(activity, "Asking the deck for a sign-in link\u2026");
        new Thread(() -> {
            final LanDeck.Reply reply = LanDeck.fetch(LanDeck.signInUrl(host));
            activity.runOnUiThread(() -> {
                if (reply.status == 404) {
                    Actions.toast(activity, "The deck is signed in and out of setup, so it "
                            + "has no sign-in page. Hold SETUP on it first.");
                    return;
                }
                final String url = reply.url();
                if (url.isEmpty()) {
                    Actions.toast(activity, reply.why(host));
                    return;
                }
                Actions.openUrl(activity, url);
            });
        }, "deck-sign-in").start();
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
