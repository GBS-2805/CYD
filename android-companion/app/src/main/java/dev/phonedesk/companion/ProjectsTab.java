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
    /** The project whose card is the edit form, and whether that form adds a new one. */
    private String editing;
    private boolean creating;
    /** The project whose file picker is showing, or null for none. */
    private String picking;
    /** A sketch a fresh record will be made from, and the import that answers it. */
    private String seedFile = "";
    private String seedName = "";
    private boolean pendingSketch;
    /** Where a card sends a file it cannot flash itself; null in a JVM capture. */
    private Handoff handoff;

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
        search.setBackground(Ui.rounded(activity, Ui.INPUT, Ui.EDGE, 8));
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
                .append('k').append(picking)
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

        body.addView(Ui.title(activity, "Shelf"));

        // Which board the shelf below belongs to. The bar's crumb already carries
        // the way back, so this line does not repeat the section name.
        TextView trail = Ui.text(activity, breadcrumb(projects), 10, Ui.DIM);
        trail.setLetterSpacing(0.12f);
        Ui.mono(trail);
        trail.setPadding(0, Ui.dp(activity, 6), 0, 0);
        body.addView(trail);

        TextView subtitle = Ui.subtitle(activity,
                "Project notes, logs, and the sketches that produced them.");
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
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

        // Add and edit sit with the shelf, and the form is one of its cards rather
        // than a dialog: a dialog is a second window, so the capture could not draw
        // it and the render gate would have nothing to compare.
        body.addView(addRow());
        if (creating) body.addView(editorCard(null));

        int shown = 0;
        for (ProjectStore.Project project : projects) {
            if (activeFilter >= 0 && project.status != activeFilter) continue;
            if (!matches(project)) continue;
            if (!creating && project.id.equals(editing)) {
                body.addView(editorCard(project));
            } else if (project.id.equals(picking)) {
                body.addView(pickerCard(project));
            } else {
                body.addView(projectCard(project));
            }
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
            // Two different refusals, so one line each: ArduinoBLE shadows the core's
            // own headers, and another BLE library is not a compile problem at all —
            // it is a rewrite of every call this sketch makes, which is why it is
            // named here rather than left to be discovered after installing it.
            StringBuilder banned = new StringBuilder();
            for (String name : never) {
                if (banned.length() > 0) banned.append('\n');
                banned.append("Never install ").append(name).append(": ");
                if (name.toLowerCase(Locale.US).contains("arduinoble")) {
                    banned.append("it shadows the ESP32 core's BLE headers and every build fails.");
                } else {
                    banned.append("the deck is built on the core's own stack, so another "
                            + "BLE library is a rewrite of the link, not an install.");
                }
            }
            TextView warning = Ui.text(activity, banned.toString(), 11, Ui.PINK);
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

    /**
     * One filter, as the study's underlined tab: a word, and a rule under the live
     * one. A filled chip was a second kind of button on a screen that already has
     * buttons; a tab says "this is the same list, filtered" without competing.
     */
    private void addChip(LinearLayout row, String label, int filter) {
        final boolean on = activeFilter == filter;
        LinearLayout tab = Ui.column(activity);
        tab.setClickable(true);
        Ui.pressable(activity, tab, 0);

        TextView word = Ui.text(activity, label, 11, on ? Ui.TEXT : Ui.DIM);
        word.setLetterSpacing(0.06f);
        if (on) Ui.medium(word);
        word.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 6),
                Ui.dp(activity, 2), Ui.dp(activity, 6));
        tab.addView(word);

        View underline = new View(activity);
        underline.setBackgroundColor(on ? Ui.ACCENT : android.graphics.Color.TRANSPARENT);
        tab.addView(underline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(activity, 1))));

        tab.setOnClickListener(v -> {
            activeFilter = filter;
            signature = "";
            refresh();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(activity, 18);
        row.addView(tab, params);
    }

    /**
     * One accordion. The header is the whole card while folded — kind, status,
     * name — and the row that opens it is the name itself, which is what a thumb
     * aims at anyway.
     */
    private LinearLayout projectCard(ProjectStore.Project project) {
        LinearLayout card = Ui.column(activity);
        card.setPadding(0, Ui.dp(activity, 14), 0, Ui.dp(activity, 14));
        final boolean open = expanded.contains(project.id);

        LinearLayout header = Ui.row(activity);
        header.setClickable(true);

        LinearLayout meta = Ui.row(activity);
        TextView kind = Ui.text(activity, project.kindLabel(), 10, Ui.DIM);
        kind.setLetterSpacing(0.12f);
        Ui.mono(kind);
        meta.addView(kind);

        TextView status = Ui.text(activity, ProjectStore.statusLabel(project.status), 10,
                ProjectStore.statusColor(project.status));
        status.setLetterSpacing(0.12f);
        Ui.mono(status);
        status.setPadding(Ui.dp(activity, 10), 0, 0, 0);
        meta.addView(status);

        // A filed card says so while folded: whether it can run a file is the
        // difference between a note and something to copy or flash.
        if (!project.fileName.isEmpty() && !project.catalog) {
            TextView file = Ui.text(activity, "FILE", 10, Ui.ACCENT);
            file.setLetterSpacing(0.12f);
            Ui.mono(file);
            file.setPadding(Ui.dp(activity, 10), 0, 0, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.leftMargin = Ui.dp(activity, 8);
            meta.addView(file, params);
        }
        header.addView(meta, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView fold = Ui.text(activity, open ? "\u2212" : "+", 15, Ui.DIM);
        header.addView(fold);
        card.addView(header);

        TextView name = Ui.text(activity, project.name, 17, Ui.TEXT);
        name.setTypeface(Ui.medium(activity));
        name.setLetterSpacing(-0.01f);
        name.setPadding(0, Ui.dp(activity, 7), 0, 0);
        card.addView(name);

        View.OnClickListener flip = v -> toggle(project.id);
        // The header is the target for the whole accordion; without a ripple it was
        // a row that either opened the card or did not, with nothing in between.
        header.setBackground(Ui.rounded(activity, 0, 0, 10));
        Ui.pressable(activity, header, 10);
        header.setOnClickListener(flip);
        name.setOnClickListener(flip);
        fold.setOnClickListener(flip);

        if (!open) {
            card.addView(Ui.rule(activity));
            return card;
        }

        if (!project.body.isEmpty()) {
            TextView description = Ui.text(activity, project.body, 12, Ui.DIM);
            description.setPadding(0, Ui.dp(activity, 6), 0, 0);
            description.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(description);
        }

        // A prebuilt image's card says "Prebuilt image." in that field, which as a
        // NEEDS line reads like a library nobody has installed.
        if (!project.libraries.isEmpty() && project.kind != ProjectStore.KIND_IMAGE) {
            TextView libs = Ui.text(activity, "NEEDS  " + project.libraries, 11, Ui.ACCENT);
            libs.setPadding(0, Ui.dp(activity, 6), 0, 0);
            libs.setLineSpacing(Ui.dp(activity, 2), 1f);
            card.addView(libs);
        }

        WidgetCatalog.Widget lan = WidgetCatalog.forProject(activity, project.id);
        if (lan != null && lan.lan) card.addView(deckCard(lan));

        card.addView(logView(project));

        // Only a card the app may change can be given a file: a widget's card is
        // the descriptor's and already knows which sketch it ships with, and a
        // catalogue card is an installer link with no bytes here to attach.
        if (!project.inside() && !project.catalog) card.addView(fileBlock(project));

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
            // A project added here has no links yet, and a button that opens
            // nothing is a lie about what the card can do.
            if (!project.installerUrl.isEmpty()) {
                Button installer = Ui.action(activity, "INSTALLER", true);
                installer.setOnClickListener(v -> Actions.openUrl(activity, project.installerUrl));
                primary.addView(installer);
            }
            if (!project.sourceUrl.isEmpty()) {
                Button source = Ui.action(activity, "SOURCE", false);
                source.setOnClickListener(v -> Actions.openUrl(activity, project.sourceUrl));
                if (primary.getChildCount() == 0) primary.addView(source);
                else primary.addView(source, spaced(8));
            }
        }
        if (primary.getChildCount() > 0) card.addView(primary);

        LinearLayout secondary = Ui.row(activity);
        secondary.setPadding(0, Ui.dp(activity, 8), 0, 0);

        Button log = Ui.action(activity, "LOG", false);
        log.setOnClickListener(v -> logBuild(project));
        secondary.addView(log);

        Button state = Ui.action(activity, "STATUS", false);
        state.setOnClickListener(v -> pickStatus(project));
        secondary.addView(state, spaced(8));

        // Only a record this app made can be edited: a widget's card belongs to its
        // descriptor and a catalogue card's name, tagline and body are the
        // catalogue's; both are rewritten on read, so a save there would forget.
        if (!project.inside() && !project.catalog) {
            Button edit = Ui.action(activity, "EDIT", false);
            edit.setOnClickListener(v -> startEdit(project));
            secondary.addView(edit, spaced(8));
        }
        // A recipe is an esptool line for a prebuilt image; a sketch has no image
        // to merge, so the button would describe a flash that cannot happen.
        if (project.kind == ProjectStore.KIND_IMAGE) {
            Button recipe = Ui.action(activity, "RECIPE", false);
            recipe.setOnClickListener(v -> copyRecipe(project));
            secondary.addView(recipe, spaced(8));
        }
        card.addView(secondary);
        card.addView(Ui.rule(activity));
        return card;
    }

    /** The one affordance the shelf was missing: a way to put something on it. */
    private LinearLayout addRow() {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 6), 0, 0);
        Button add = Ui.action(activity, "ADD A PROJECT", false);
        add.setOnClickListener(v -> startCreate());
        Ui.fullWidth(activity, add);
        row.addView(add);
        return row;
    }

    private void startCreate() {
        creating = true;
        editing = null;
        signature = "";
        seedFile = "";
        seedName = "";
        pendingSketch = false;
        refresh();
    }

    private void startEdit(ProjectStore.Project project) {
        creating = false;
        editing = project.id;
        signature = "";
        refresh();
    }

    private void closeEditor() {
        creating = false;
        editing = null;
        signature = "";
        seedFile = "";
        seedName = "";
        pendingSketch = false;
        refresh();
    }

    /**
     * Add and edit, in place.
     *
     * The fields are the record's own: name, tagline, status and notes. Name and
     * tagline are descriptor-owned on a card that ships inside the app, which is
     * why those cards offer no EDIT - a save there would be overwritten by the
     * next read, and a control that forgets is worse than one that is absent.
     */
    private LinearLayout editorCard(ProjectStore.Project project) {
        final boolean fresh = project == null;
        LinearLayout card = Ui.card(activity);

        TextView title = Ui.text(activity, fresh ? "NEW PROJECT" : "EDIT PROJECT", 10,
                Ui.ACCENT);
        title.setLetterSpacing(0.14f);
        card.addView(title);

        // A new project can start from the sketch rather than from its name: the
        // file is what the card is for, and retyping its name is the step that gets
        // skipped. An existing card attaches from its own ATTACH A FILE instead.
        if (fresh) {
            if (seedFile.isEmpty()) {
                Button sketch = Ui.action(activity, "IMPORT A .INO", false);
                sketch.setOnClickListener(v -> openSketchPicker());
                Ui.space(activity, sketch, 8);
                Ui.fullWidth(activity, sketch);
                card.addView(sketch);
                TextView hint = Ui.text(activity,
                        "Optional. A sketch names the card and stays attached to it.",
                        11, Ui.DIM);
                hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
                hint.setLineSpacing(Ui.dp(activity, 2), 1f);
                card.addView(hint);
            } else {
                TextView line = Ui.text(activity, "SKETCH  " + seedFile, 11, Ui.ACCENT);
                line.setTypeface(Ui.face(line.getContext()));
                card.addView(line);
                Button another = Ui.action(activity, "CHANGE SKETCH", false);
                another.setOnClickListener(v -> openSketchPicker());
                Ui.space(activity, another, 6);
                card.addView(another);
            }
        }

        EditText name = input("Name, e.g. Kitchen sensor", fresh ? seedName : project.name);
        Ui.space(activity, name, 8);
        card.addView(name);

        EditText tagline = input("One line: what it is", fresh ? "" : project.tagline);
        Ui.space(activity, tagline, 6);
        card.addView(tagline);

        Spinner status = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, ProjectStore.STATUS_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        status.setAdapter(adapter);
        status.setSelection(fresh ? ProjectStore.STATUS_WANT : project.status);
        Ui.space(activity, status, 6);
        card.addView(status);

        EditText notes = input("Notes: what it needs, what broke", fresh ? "" : project.notes);
        notes.setSingleLine(false);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setMinLines(3);
        notes.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        Ui.space(activity, notes, 6);
        card.addView(notes);

        LinearLayout buttons = Ui.row(activity);
        buttons.setPadding(0, Ui.dp(activity, 10), 0, 0);

        Button save = Ui.action(activity, fresh ? "ADD" : "SAVE", true);
        save.setOnClickListener(v -> saveEditor(project, name, tagline, status, notes));
        buttons.addView(save);

        Button cancel = Ui.action(activity, "CANCEL", false);
        cancel.setOnClickListener(v -> closeEditor());
        buttons.addView(cancel, spaced(8));

        if (!fresh) {
            Button remove = Ui.action(activity, "DELETE", false);
            remove.setOnClickListener(v -> confirmDelete(project));
            buttons.addView(remove, spaced(8));
        }
        card.addView(buttons);
        Ui.space(activity, card, 8);
        return card;
    }

    /** One text field, styled like the search box so the form reads as the shelf. */
    private EditText input(String hint, String value) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setTextSize(13);
        field.setTypeface(Ui.face(field.getContext()));
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.DIM);
        field.setBackground(Ui.rounded(activity, Ui.INPUT, Ui.EDGE, 8));
        field.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 6),
                Ui.dp(activity, 10), Ui.dp(activity, 6));
        return field;
    }

    private void saveEditor(ProjectStore.Project project, EditText name, EditText tagline,
                            Spinner status, EditText notes) {
        String title = name.getText().toString().trim();
        if (title.isEmpty()) {
            Actions.toast(activity, "A project needs a name");
            return;
        }
        ProjectStore.Project record = project == null
                ? new ProjectStore.Project() : new ProjectStore.Project(project);
        if (project == null) {
            // An app-made record, which is also the kind this app may delete: a
            // widget's card belongs to its descriptor and would simply come back.
            record.id = "user:" + System.currentTimeMillis();
            record.kind = ProjectStore.KIND_SKETCH;
            record.touch = FirmwareNotes.TOUCH_UNTESTED;
        }
        // The sketch arrived before the record did, so it is attached here: ADD is
        // the moment the card gains a file, and the kind already says sketch.
        if (project == null && !seedFile.isEmpty()) record.fileName = seedFile;
        record.name = title;
        record.tagline = tagline.getText().toString().trim();
        record.notes = notes.getText().toString().trim();
        record.status = status.getSelectedItemPosition();
        record.updatedMs = System.currentTimeMillis();
        ProjectStore.write(activity, record);
        closeEditor();
        Actions.toast(activity, project == null ? "Added " + title : "Saved " + title);
    }

    /**
     * The file a card names, and the two things a file is good for here.
     *
     * A sketch copies its source into ArduinoDroid, which is the app that can
     * compile and upload it; an image is handed to the USB flasher on SERIAL,
     * which is the only path in this app that writes bytes to a board. A card with
     * no file offers one button rather than two controls that would do nothing.
     */
    private LinearLayout fileBlock(ProjectStore.Project project) {
        LinearLayout block = Ui.column(activity);
        block.setPadding(0, Ui.dp(activity, 10), 0, 0);

        if (project.fileName.isEmpty()) {
            Button attach = Ui.action(activity, "ATTACH A FILE", false);
            attach.setOnClickListener(v -> startPicking(project));
            Ui.fullWidth(activity, attach);
            block.addView(attach);
            return block;
        }

        FlashLibrary.Entry entry = entryNamed(project.fileName);

        TextView line = Ui.text(activity, "FILE  " + project.fileName, 11, Ui.ACCENT);
        line.setTypeface(Ui.face(line.getContext()));
        block.addView(line);

        // The record keeps the name, not the bytes: a file deleted from FILES is a
        // card naming something gone, and saying so beats a FLASH that fails on tap.
        TextView detail = Ui.text(activity, entry == null
                ? "Not in FILES any more. Attach it again."
                : Ui.size(entry.size) + "  \u00B7  "
                        + (entry.textual() ? "sketch source" : "image"), 11, Ui.DIM);
        detail.setPadding(0, Ui.dp(activity, 2), 0, 0);
        block.addView(detail);

        LinearLayout actions = Ui.row(activity);
        actions.setPadding(0, Ui.dp(activity, 8), 0, 0);
        if (entry != null && entry.textual()) {
            Button copy = Ui.action(activity, "COPY SOURCE", true);
            copy.setOnClickListener(v -> Actions.copyOrSay(activity, entry.name,
                    FlashLibrary.read(activity, entry)));
            actions.addView(copy);
        } else if (entry != null) {
            Button flash = Ui.action(activity, "FLASH", true);
            flash.setOnClickListener(v -> flashFile(entry));
            actions.addView(flash);
        }
        Button change = Ui.action(activity, entry == null ? "ATTACH" : "CHANGE", false);
        change.setOnClickListener(v -> startPicking(project));
        if (actions.getChildCount() == 0) actions.addView(change);
        else actions.addView(change, spaced(8));
        block.addView(actions);
        return block;
    }

    /** Hands an image to the flasher on SERIAL. A capture has no screen to send it to. */
    private void flashFile(FlashLibrary.Entry entry) {
        if (handoff == null) {
            Actions.toast(activity, "Open SERIAL to flash " + entry.name);
            return;
        }
        handoff.flash(entry.name);
    }

    /**
     * The picker, as a card where the project's own was: the same reason as the
     * editor. It lists what FILES holds, because that is the one place a file can
     * come from without a second window the capture could not draw.
     */
    private LinearLayout pickerCard(ProjectStore.Project project) {
        LinearLayout card = Ui.card(activity);

        TextView title = Ui.text(activity, "ATTACH TO " + project.name.toUpperCase(Locale.US),
                10, Ui.ACCENT);
        title.setLetterSpacing(0.12f);
        card.addView(title);

        TextView hint = Ui.text(activity,
                "A sketch copies into ArduinoDroid; a .bin image flashes from SERIAL.",
                11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        // The one thing this picker could not do was add to what it lists: a card
        // wanted a sketch, and the shelf sent the user to SERIAL to fetch it first.
        Button bring = Ui.action(activity, "IMPORT A FILE", true);
        bring.setOnClickListener(v -> importFile());
        Ui.space(activity, bring, 8);
        Ui.fullWidth(activity, bring);
        card.addView(bring);

        int listed = 0;
        for (FlashLibrary.Entry entry : currentEntries) {
            ++listed;
            Button pick = Ui.action(activity,
                    entry.name + "  \u00B7  " + Ui.size(entry.size), false);
            pick.setTextSize(12);
            pick.setOnClickListener(v -> saveFile(project, entry));
            Ui.space(activity, pick, 8);
            card.addView(pick);
        }
        if (listed == 0) {
            TextView empty = Ui.text(activity,
                    "FILES is empty. IMPORT A FILE above, or import on SERIAL.", 11, Ui.DIM);
            empty.setPadding(0, Ui.dp(activity, 8), 0, 0);
            card.addView(empty);
        }

        Button cancel = Ui.action(activity, "CANCEL", false);
        cancel.setOnClickListener(v -> closePicker());
        Ui.space(activity, cancel, 10);
        card.addView(cancel);

        Ui.space(activity, card, 8);
        return card;
    }

    private void startPicking(ProjectStore.Project project) {
        creating = false;
        editing = null;
        picking = project.id;
        signature = "";
        refresh();
    }

    private void closePicker() {
        picking = null;
        signature = "";
        refresh();
    }

    /**
     * Attaching is the record's own change, so it goes through the store like any
     * other edit. The kind follows the file, because the kind is what the card says
     * it is built from: a .bin makes RECIPE honest, a sketch makes it a lie.
     */
    private void saveFile(ProjectStore.Project project, FlashLibrary.Entry entry) {
        ProjectStore.Project record = new ProjectStore.Project(project);
        record.fileName = entry.name;
        record.kind = entry.textual() ? ProjectStore.KIND_SKETCH : ProjectStore.KIND_IMAGE;
        record.updatedMs = System.currentTimeMillis();
        ProjectStore.write(activity, record);
        picking = null;
        signature = "";
        refresh();
        Actions.toast(activity, "Attached " + entry.name);
    }

    /** The library entry a record names, or null once the file is gone from FILES. */
    private FlashLibrary.Entry entryNamed(String name) {
        if (name == null || name.isEmpty()) return null;
        for (FlashLibrary.Entry entry : currentEntries) {
            if (entry.name.equals(name)) return entry;
        }
        return null;
    }

    /** Beside the serial tab's own importer; the activity routes each one back. */
    static final int REQUEST_IMPORT = 42;

    /** The platform's document picker: the shelf can add to FILES, not just read it. */
    private boolean importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT);
            return true;
        } catch (Exception e) {
            Actions.toast(activity, "No file picker");
            return false;
        }
    }

    /**
     * The editor's picker. It shares the attach picker's request code because both
     * answers put a file in FILES; {@code pendingSketch} is what tells the answer
     * which of the two asked.
     */
    private void openSketchPicker() {
        pendingSketch = true;
        if (!importFile()) pendingSketch = false;
    }

    /** "kitchen_sensor.ino" offers "Kitchen sensor": a default that can be typed over. */
    private static String suggestName(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
        if (base.isEmpty()) return "";
        String lower = base.toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * What the picker came back with. The list is rebuilt in place rather than
     * closed: the file was imported to be attached to this card, so the next tap
     * should be the file, not ATTACH again.
     */
    void imported(FlashLibrary.Entry entry) {
        if (pendingSketch) {
            // The editor's import: a sketch it can be named after, or a refusal that
            // says so rather than a card that quietly gained an image as its "sketch".
            pendingSketch = false;
            seedFile = "";
            seedName = "";
            if (entry == null) {
                Actions.toast(activity, "Import failed");
            } else if (!entry.textual()) {
                Actions.toast(activity, entry.name + " is not a sketch");
            } else {
                seedFile = entry.name;
                seedName = suggestName(entry.name);
                Actions.toast(activity, "Sketch imported");
            }
            signature = "";
            refresh();
            return;
        }
        Actions.toast(activity, entry != null ? "Imported. Tap it to attach." : "Import failed");
        signature = "";
        refresh();
    }

    /** The serial tab, reached through the activity that owns both screens. */
    interface Handoff {
        void flash(String entryName);
    }

    void handoff(Handoff where) {
        handoff = where;
    }

    private void confirmDelete(ProjectStore.Project project) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete " + project.name + "?")
                .setMessage("The card and its log go with it. A project that ships inside "
                        + "the app has no DELETE: it is the descriptor's, not the shelf's.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    ProjectStore.remove(activity, project.id);
                    closeEditor();
                    Actions.toast(activity, "Deleted " + project.name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The cards that are unfolded, for the rebuild the theme switch causes. */
    String[] expandedIds() {
        return expanded.toArray(new String[0]);
    }

    /**
     * Puts the folds back. Called before the first build, so the shelf draws the
     * cards open instead of opening them a frame later, and it resets the
     * signature because the folds are part of what a rebuild compares.
     */
    void restoreExpanded(String[] ids) {
        if (ids == null) return;
        expanded.clear();
        for (String id : ids) {
            if (id != null && !id.isEmpty()) expanded.add(id);
        }
        signature = "";
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
            if (!project.build.isEmpty()) text.append("  \u00B7  BUILD: ").append(project.build);
            text.append("  \u00B7  ").append(Ui.ago(project.updatedMs, System.currentTimeMillis()));
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
        BoardTable.Board board = BoardCatalog.selected(activity);
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
