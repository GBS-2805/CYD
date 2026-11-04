package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A picture of the app, not a picture of a redraw.
 *
 * Every frame here is the app's own screen: {@link TopBar} and the tab class are
 * instantiated, laid out and drawn by Android's own renderer — the platform's
 * fonts, the platform's vector drawables, the platform's text layout — so nothing
 * here can disagree with the phone about where a glyph sits, how wide a row is or
 * what a drawable looks like.
 *
 * A state the screen reaches by tapping is reached by tapping: {@code home-open}
 * is the BOARDS row clicked, {@code projects-open} is the first project's header
 * clicked. Nothing sets a fold flag behind the screen's back.
 *
 * Frames are the full height of what the screen builds, not a viewport, so a
 * scrolled-away row is in the picture and a screen that grows stays visible.
 *
 * One fixture is not something a tap can reach: PAIRED is drawn against a linked-deck
 * snapshot the test supplies, because a JVM has no radio and no board to talk to. That
 * snapshot goes to the bar as well, because {@code MainActivity} pushes one into it
 * every second — without the same call here the bar said IDLE above a screen that said
 * linked, which is a state the phone cannot reach. The card, the rows, the pill and
 * every string are still {@code DecksTab}'s and {@code TopBar}'s own code: the test
 * hands them a link, not a layout.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public final class CaptureTest {

    private static final int WIDTH = 393;


    private static final String DECK_MAC = "A4:CF:12:8B:3D:6E";
    private static final String DECK_TAG = "8B3D6E";
    private static final String DECK_ID = "phone_deck_cyd24R-" + DECK_TAG;

    private Activity host;
    private long now;

    @Test
    public void capturesEveryScreen() throws Exception {
        // A frozen clock. The shelf prints "3s ago" against System.currentTimeMillis(),
        // so without this the frame says a different number every run and the gate
        // flakes on pixels that are not a change.
        host = Robolectric.buildActivity(Activity.class).setup().get();
        // One instant for every fixture, so each printed age is a function of these
        // offsets and reads the same today and next week.
        now = System.currentTimeMillis();
        pinAges();
        pinDeck();
        pinFile();

        capture("home", MainActivity.HOME, drawer(), null);
        capture("home-open", MainActivity.HOME, drawer(), "BOARDS");
        capture("paired", MainActivity.SECTION_DECKS, new DecksTab(host, this::linked), null,
                linked());
        capture("projects", MainActivity.SECTION_PROJECTS, new ProjectsTab(host), null);
        capture("projects-open", MainActivity.SECTION_PROJECTS, new ProjectsTab(host),
                firstName());
        capture("projects-edit", MainActivity.SECTION_PROJECTS, new ProjectsTab(host),
                "ADD A PROJECT");
        captureTaps("projects-file", MainActivity.SECTION_PROJECTS, new ProjectsTab(host),
                firstUnfiled(), "ATTACH A FILE");
        capture("serial", MainActivity.SECTION_SERIAL, new BoardTab(host), null);
        // SETTINGS is drawn in the paper palette on purpose: it is the screen the
        // switch lives on, so one frame shows the control and what it does, and the
        // light theme is in the gate instead of only in a description of it.
        Ui.applyTheme(Ui.LIGHT);
        capture("settings", MainActivity.SECTION_SETTINGS, new SetupTab(host), null);
        Ui.applyTheme(Ui.DARK);
    }

    /**
     * Pins the ages the shelf prints.
     *
     * No clock on this JVM can be frozen: Robolectric's paused clock has no setter and
     * `System.currentTimeMillis()` is the real one, which is why the first attempt at
     * this produced a frame that said `1s ago` on one run and `2s ago` on the next. So
     * pin the data instead — every project is one hour old when the capture starts,
     * which prints `1h ago` then and `1h ago` in an hour's time, because the offset
     * moves with the clock the app reads.
     */
    private void pinAges() {
        List<ProjectStore.Project> projects = ProjectStore.all(host);
        for (ProjectStore.Project project : projects) project.updatedMs = now - 3_600_000L;
        ProjectStore.save(host, projects);
    }

    /**
     * The deck PAIRED is drawn against: a board that published its document, linked
     * two hours ago, one packet an hour ago, and the same board advertising nearby so
     * the row carries its LINKED badge. The ages are offsets from {@link #now}, which
     * is why this frame says the same thing on every run.
     */
    private DeckService.Snapshot linked() {
        DeckService.Snapshot snapshot = new DeckService.Snapshot();
        snapshot.running = true;
        snapshot.linked = true;
        snapshot.status = "Linked to Phone Deck " + DECK_TAG;
        snapshot.deviceName = "Phone Deck " + DECK_TAG;
        snapshot.deviceMac = DECK_MAC;
        snapshot.deviceId = DECK_ID;
        snapshot.deviceTag = DECK_TAG;
        // The palette the board answers with, so the PAIRED rows draw a lit chip
        // rather than a pair that says nothing about this deck.
        snapshot.theme = DeckTheme.INK;
        snapshot.rssi = -61;
        snapshot.writes = 214;
        snapshot.notices = 3;
        snapshot.connectedAtMs = now - 7_200_000L;
        snapshot.lastWriteMs = now - 3_600_000L;
        snapshot.nearby.add(new DeckService.Nearby(DECK_MAC, snapshot.deviceName, -61,
                now - 3_600_000L));
        return snapshot;
    }

    /** The saved-deck row the same board would leave behind, a day old and steady. */
    private void pinDeck() {
        DeckStore.rememberAt(host, DeckIdentity.parse("{\"id\":\"" + DECK_ID
                        + "\",\"widget\":\"phone_deck_cyd24R\",\"tag\":\"" + DECK_TAG
                        + "\",\"name\":\"Phone Deck " + DECK_TAG + "\"}"),
                DECK_MAC, "Phone Deck", now - 86_400_000L);
    }

    private DrawerHome drawer() {
        return new DrawerHome(host, index -> { });
    }

    /** The top of the shelf: whatever the store would put first, opened like a tap. */
    private String firstName() {
        List<ProjectStore.Project> projects = ProjectStore.all(host);
        if (projects.isEmpty()) throw new IllegalStateException("the shelf is empty");
        return projects.get(0).name;
    }

    /**
     * One card with a file, stored the way the shelf would store one. No seed has a
     * file, so without this the FILE badge and the FILE block would be in no frame
     * at all: a render cannot show what the app never held.
     */
    private void pinFile() {
        List<FlashLibrary.Entry> entries = FlashLibrary.list(host);
        if (entries.isEmpty()) return;
        FlashLibrary.Entry entry = entries.get(0);
        List<ProjectStore.Project> projects = new ArrayList<>(ProjectStore.all(host));
        projects.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (ProjectStore.Project project : projects) {
            if (project.kind == ProjectStore.KIND_INSIDE) continue;
            project.fileName = entry.name;
            project.kind = entry.textual()
                    ? ProjectStore.KIND_SKETCH : ProjectStore.KIND_IMAGE;
            ProjectStore.save(host, projects);
            return;
        }
    }

    /**
     * The first card the shelf would draw with an ATTACH on it, in the order it
     * draws them. A widget's card has none because its sketch ships inside the app,
     * and the card {@link #pinFile} already filed has `CHANGE` instead.
     */
    private String firstUnfiled() {
        List<ProjectStore.Project> projects = new ArrayList<>(ProjectStore.all(host));
        projects.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (ProjectStore.Project project : projects) {
            if (project.kind != ProjectStore.KIND_INSIDE && project.fileName.isEmpty()) {
                return project.name;
            }
        }
        throw new IllegalStateException("no unfiled card to attach to");
    }

    /**
     * The bar and one screen in a column, exactly as {@code MainActivity.buildUi}
     * stacks them, optionally tapped, then drawn and written out.
     */
    private void capture(String name, int section, View screen, String tap) throws Exception {
        draw(name, section, screen, tap == null ? new String[0] : new String[] { tap }, null);
    }

    /**
     * A state two taps away: {@code ATTACH A FILE} lives on an opened card, so the
     * frame opens the project first and then taps the button inside it.
     */
    private void captureTaps(String name, int section, View screen, String... taps)
            throws Exception {
        draw(name, section, screen, taps, null);
    }

    /** As above, with the link state the bar is drawn against; null is a stopped bridge. */
    private void capture(String name, int section, View screen, String tap,
                         DeckService.Snapshot link) throws Exception {
        draw(name, section, screen, tap == null ? new String[0] : new String[] { tap }, link);
    }

    private void draw(String name, int section, View screen, String[] taps,
                      DeckService.Snapshot link) throws Exception {
        LinearLayout root = Ui.column(host);
        root.setBackgroundColor(Ui.BG);

        TopBar bar = new TopBar(host);
        bar.showSection(section);
        // MainActivity.refresh() makes this call every second; a capture that skipped
        // it drew a bridge the frame below it contradicted.
        bar.showLink(link == null ? new DeckService.Snapshot() : link,
                link != null && link.running);
        root.addView(bar.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (screen != null) {
            refresh(screen);
            root.addView(screen, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (String tap : taps) {
                tapText(screen, tap);
                refresh(screen);
            }
        }

        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(
                root.getMeasuredWidth(), root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));

        File dir = new File("build/capture");
        dir.mkdirs();
        File file = new File(dir, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        System.out.println("capture " + name + ": " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " -> " + file.getAbsolutePath());
    }

    /**
     * Clicks what says {@code label}: the view itself when it carries the click
     * listener, the row around it when the listener is one level up, which is how
     * {@code DrawerHome.menuRow} and the shelf's header are wired.
     */
    private static boolean tapText(View root, String label) {
        TextView found = find(root, label);
        if (found == null) throw new IllegalStateException("no view says " + label);
        found.performClick();
        View parent = (View) found.getParent();
        if (parent != null) parent.performClick();
        return true;
    }

    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView hit = find(group.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** The screens build themselves on refresh, the same call the phone makes. */
    private static void refresh(View screen) {
        if (screen instanceof DrawerHome) ((DrawerHome) screen).refresh();
        else if (screen instanceof DecksTab) ((DecksTab) screen).refresh();
        else if (screen instanceof ProjectsTab) ((ProjectsTab) screen).refresh();
        else if (screen instanceof BoardTab) ((BoardTab) screen).refresh();
        else if (screen instanceof SetupTab) ((SetupTab) screen).refresh();
    }
}
