package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The front page: a drawer that is already open.
 *
 * A slide-out panel behind a button would hide the only navigation this app has,
 * so the menu is the screen. The owner's mark sits where a launcher's icon would,
 * the wordmark under it, the three sections hang below, and BOARDS expands in
 * place to name the boards
 * that hold projects, each row opening the shelf they live on.
 *
 * SETTINGS is deliberately not a row here: it is configuration rather than
 * navigation, so it lives behind the gear in the top bar.
 *
 * The rows are named for what they answer, not for the classes behind them: PAIRED
 * is the link and BOARDS is the shelf. The Java keeps the platform's words (a Decks
 * tab, a Projects store) because that is what the code models; only the labels moved.
 *
 * Sections are one tap away; every row goes through {@link Nav}, so this class
 * knows nothing about the screens behind it.
 */
final class DrawerHome extends ScrollView {

    /** Where a row goes: a section index from {@link MainActivity}. */
    interface Nav {
        void openSection(int index);
    }

    private final Activity activity;
    private final Nav nav;
    private final LinearLayout body;
    /** BOARDS is collapsed until asked; the state survives refreshes. */
    private boolean projectsOpen;
    private String signature = "";

    DrawerHome(Activity activity, Nav nav) {
        super(activity);
        this.activity = activity;
        this.nav = nav;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 8),
                Ui.dp(activity, 18), Ui.dp(activity, 28));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void refresh() {
        DeckService.Snapshot snapshot = DeckService.snapshot();
        String next = signatureOf(snapshot);
        if (next.equals(signature)) return;
        signature = next;
        build(snapshot);
    }

    /**
     * Everything that changes a pixel: the link state and the shelf. Board tags
     * come from the store through {@link WidgetCatalog}, so a new deck, or the
     * board it names, redraws the index without a restart.
     */
    private String signatureOf(DeckService.Snapshot snapshot) {
        StringBuilder sig = new StringBuilder();
        sig.append(snapshot.linked).append('|').append(snapshot.running).append('|')
                .append(snapshot.deviceName).append('|').append(projectsOpen).append('|')
                .append(ProjectStore.version(activity)).append('|');
        for (ProjectStore.Project project : ProjectStore.all(activity)) {
            sig.append(project.id).append(':').append(project.name).append(';');
        }
        return sig.toString();
    }

    private void build(DeckService.Snapshot snapshot) {
        int keepScroll = getScrollY();
        body.removeAllViews();

        // The owner's mark, from the same artwork the launcher icon is cut from:
        // one even-odd path, so the ghost is the hole its contour leaves. It stands
        // where the wordmark's 60dp of air used to be, which is what pays for it.
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.logo_mark);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 36), Ui.dp(activity, 37));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(logo, logoParams);

        TextView mark = Ui.text(activity, "[OFF-SITE]", 34, Ui.TEXT);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mark.setLetterSpacing(0.06f);
        mark.setPadding(0, Ui.dp(activity, 8), 0, 0);
        body.addView(mark);

        TextView line = Ui.text(activity, statusLine(snapshot), 10, Ui.DIM);
        line.setGravity(Gravity.CENTER);
        line.setLetterSpacing(0.16f);
        line.setPadding(0, Ui.dp(activity, 10), 0, 0);
        body.addView(line);

        body.addView(menuRow("PAIRED", "live link",
                () -> nav.openSection(MainActivity.SECTION_DECKS)));
        body.addView(projectsRow());
        if (projectsOpen) body.addView(projectsPanel());
        body.addView(menuRow("SERIAL", "usb · flash",
                () -> nav.openSection(MainActivity.SECTION_SERIAL)));

        post(() -> scrollTo(0, keepScroll));
    }

    /** The one line under the wordmark: the link, or why there is none. */
    private String statusLine(DeckService.Snapshot snapshot) {
        if (snapshot.linked) {
            return "LINKED · " + snapshot.deviceName.toUpperCase(Locale.US);
        }
        if (snapshot.running) return "SCANNING";
        return "IDLE · BRIDGE STOPPED";
    }

    /** One section row: name on the left, a hint on the right, the whole row tappable. */
    private LinearLayout menuRow(String label, String hint, Runnable tap) {
        LinearLayout row = rowCard();
        TextView name = Ui.text(activity, label, 17, Ui.TEXT);
        name.setLetterSpacing(0.10f);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView aside = Ui.text(activity, hint, 10, Ui.DIM);
        aside.setLetterSpacing(0.10f);
        row.addView(aside);

        row.setOnClickListener(v -> tap.run());
        Ui.space(activity, row, 10);
        return row;
    }

    /** BOARDS, with a plus or minus where the other rows carry a hint. */
    private LinearLayout projectsRow() {
        LinearLayout row = rowCard();
        TextView name = Ui.text(activity, "BOARDS", 17, Ui.TEXT);
        name.setLetterSpacing(0.10f);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView aside = Ui.text(activity, projectsOpen ? "−" : "+", 16, Ui.DIM);
        row.addView(aside);

        row.setOnClickListener(v -> {
            projectsOpen = !projectsOpen;
            signature = "";
            refresh();
        });
        Ui.space(activity, row, 10);
        return row;
    }

    /**
     * The boards that hold projects, one row each, and every row opens the shelf.
     * Today that is the one CYD; a second panel becomes a second row rather than a
     * second screen, which is the whole point of the board tag.
     *
     * The projects are deliberately not listed here. They are on the shelf with
     * their bodies, logs and actions, and printing them twice made two indexes of
     * one list that could disagree: the drawer's copy had a name and a status and
     * nothing else to say.
     */
    private LinearLayout projectsPanel() {
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(Ui.dp(activity, 4), 0, 0, 0);

        List<ProjectStore.Project> projects = new ArrayList<>(ProjectStore.all(activity));
        projects.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        Map<String, Boolean> boards = new LinkedHashMap<>();
        for (ProjectStore.Project project : projects) {
            boards.put(boardLabel(project), Boolean.TRUE);
        }
        for (String board : boards.keySet()) {
            panel.addView(boardRow(board));
        }
        return panel;
    }

    /** A board in the drawer: its label, and the shelf behind it. */
    private LinearLayout boardRow(String label) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6),
                Ui.dp(activity, 8), Ui.dp(activity, 6));
        row.setBackground(Ui.rounded(activity, Ui.CARD_HI, 0, 8));

        TextView name = Ui.text(activity, label.toUpperCase(Locale.US), 13, Ui.TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView aside = Ui.text(activity, "\u203A", 16, Ui.DIM);
        row.addView(aside);

        row.setOnClickListener(v -> nav.openSection(MainActivity.SECTION_PROJECTS));
        // Two boards need the gap the project rows had: the fill is only 6% above
        // the ground, so the edge alone does not separate them.
        Ui.space(activity, row, 6);
        return row;
    }

    /** The board a project runs on; one definition, in {@link WidgetCatalog}. */
    private String boardLabel(ProjectStore.Project project) {
        return WidgetCatalog.boardLabel(activity, project.id);
    }

    /** Rounded row, horizontally arranged: the card look without its column. */
    private LinearLayout rowCard() {
        LinearLayout row = Ui.card(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 16),
                Ui.dp(activity, 16), Ui.dp(activity, 16));
        return row;
    }
}
