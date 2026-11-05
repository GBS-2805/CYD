package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * Every frame here is the app's own screen: {@link TopBar} and the screen or overlay
 * class are instantiated, laid out and drawn by Android's own renderer — the real
 * Poppins from {@code res/font}, the platform's vector drawables, the platform's text
 * layout — so nothing here can disagree with the phone about where a glyph sits, how
 * wide a row is or what a drawable looks like.
 *
 * The overlays are drawn as they are presented: a frame whose name ends in
 * {@code -sheet} or is {@code side-panel} is laid out at the phone's own height with
 * the scrim and the panel over the page behind it, because a sheet is a thing on top
 * of a screen rather than a screen. {@code pull-down} is in flow under the bar, which
 * is where the app puts it.
 *
 * One fixture is not something a tap can reach: every frame that shows a link is
 * drawn against a linked-deck snapshot the test supplies, because a JVM has no radio
 * and no board to talk to. That snapshot goes to the bar as well, because
 * {@code MainActivity} pushes one into it every second — without the same call here
 * the bar said IDLE above a screen that said linked, which is a state the phone
 * cannot reach. The rows, the marquee and every string are still the app's own code:
 * the test hands them a link, not a layout.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public final class CaptureTest {

    private static final int WIDTH = 393;
    private static final int PHONE_HEIGHT = 852;

    private static final String DECK_MAC = "A4:CF:12:8B:3D:6E";
    private static final String DECK_TAG = "8B3D6E";
    private static final String DECK_ID = "phone_deck_cyd24R-" + DECK_TAG;
    /** The one card of the user's own, which is the only kind a file may be filed on. */
    private static final String OWN_CARD = "Imported sketch";

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

        // PAIRED is where the app opens: the live link, no overlay.
        capture("paired", MainActivity.SECTION_DECKS,
                new DecksTab(host, this::linked), linked(), null);

        // The two controls the site has, each in a picture of its own.
        capture("pull-down", MainActivity.SECTION_DECKS,
                new DecksTab(host, this::linked), linked(),
                new PullDown(host, index -> { }));

        SidePanel panel = new SidePanel(host, row -> { });
        panel.show();
        captureOverlay("side-panel", MainActivity.SECTION_DECKS,
                new DecksTab(host, this::linked), linked(), panel);

        // The two sheets.
        BridgeSheet bridge = new BridgeSheet(host);
        bridge.refresh();
        Sheet bridgeSheet = new Sheet(host);
        bridgeSheet.open("Bridge", bridge);
        captureOverlay("bridge-sheet", MainActivity.SECTION_DECKS,
                new DecksTab(host, this::linked), linked(), bridgeSheet);

        AppearanceSheet appearance = new AppearanceSheet(host);
        Sheet appearanceSheet = new Sheet(host);
        appearanceSheet.open("Appearance", appearance);
        captureOverlay("appearance-sheet", MainActivity.SECTION_DECKS,
                new DecksTab(host, this::linked), linked(), appearanceSheet);

        capture("projects", MainActivity.SECTION_PROJECTS, new ProjectsTab(host), null, null);
        capture("projects-open", MainActivity.SECTION_PROJECTS, new ProjectsTab(host), null,
                null, firstName());
        capture("serial", MainActivity.SECTION_SERIAL, new BoardTab(host), null, null);

        // SETTINGS is drawn on the light page on purpose: it is the screen the
        // permissions live on, so one frame shows the list and the light page it can
        // be read on, and the light theme is in the gate instead of only described.
        Ui.applyTheme(Palette.MOSS, true);
        capture("settings", MainActivity.SECTION_SETTINGS, new SetupTab(host), null, null);
        Ui.applyTheme(Appearance.palette(host), Appearance.light(host));
    }

    /**
     * The appearance change rebuilds the activity — a colour is read when a view is
     * built, so {@code recreate()} is how the app repaints itself. That rebuild
     * used to land on the front page however deep the user was, because nothing
     * carried the section across. This drives the platform's own save and restore,
     * the same pair {@code recreate()} calls, rather than a copy of the logic.
     */
    @Test
    public void sectionSurvivesTheRebuild() {
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().showSection(MainActivity.SECTION_SETTINGS);
        controller.recreate();
        assertEquals(MainActivity.SECTION_SETTINGS, controller.get().section());
    }

    /**
     * The scroll offset comes back with the rebuild.
     *
     * It is carried by {@code MainActivity} rather than by the view hierarchy: a
     * scroll view restores its own offset during its first layout, when the rebuilt
     * child has no height, so the value is clamped away. This lays the app out,
     * scrolls SETTINGS, rebuilds, lays the rebuilt tree out and lets the posted
     * restore run — the order a phone goes through.
     */
    @Test
    public void theScrollComesBackWithTheRebuild() {
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        // The shelf, not SETTINGS: a scroll offset can only be restored on a screen
        // with something to scroll, and SETTINGS now fits a tall phone outright.
        activity.showSection(MainActivity.SECTION_PROJECTS);

        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PHONE_HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, PHONE_HEIGHT);

        ScrollView shelf = activity.findViewById(MainActivity.ID_PROJECTS);
        assertNotNull("BOARDS is a screen with a scroll offset", shelf);
        assertTrue("the shelf has to be taller than the screen for this to mean anything",
                shelf.getChildAt(0).getHeight() > shelf.getHeight());
        shelf.scrollTo(0, 120);
        assertEquals(120, shelf.getScrollY());

        controller.recreate();

        // The app carries the offset and applies it on the first layout that has
        // content under it; the platform's own restore lands before the new child has
        // a height and is clamped away.
        //
        // Robolectric runs the activity's first posted work during recreate() — before
        // any layout — and the shelf's own refresh queues a run that re-applies the
        // offset it read at that moment, which is zero. On a phone the layout comes
        // first and that read is the restored value. So the queued work is drained
        // first here, and the tree is laid out after it, which is the same order seen
        // from the other side.
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        View rebuilt = controller.get().getWindow().getDecorView();
        rebuilt.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PHONE_HEIGHT, View.MeasureSpec.EXACTLY));
        rebuilt.layout(0, 0, WIDTH, PHONE_HEIGHT);

        ScrollView again = controller.get().findViewById(MainActivity.ID_PROJECTS);
        assertNotNull(again);
        assertEquals(120, again.getScrollY());
    }

    /**
     * A fold is not a view property, so the shelf carries the open ids in the
     * activity's saved state. This checks both halves: put a card back open and its
     * body is drawn, clear them and the body is gone.
     */
    @Test
    public void theOpenCardsComeBackWithTheRebuild() {
        host = Robolectric.buildActivity(Activity.class).setup().get();

        ProjectsTab open = new ProjectsTab(host);
        open.restoreExpanded(new String[] { "bruce" });
        open.refresh();
        assertTrue("the shelf drew: " + texts(open), texts(open).contains("Bruce"));
        assertTrue("folded ids " + java.util.Arrays.toString(open.expandedIds()),
                find(open, "INSTALLER") != null);

        ProjectsTab folded = new ProjectsTab(host);
        folded.restoreExpanded(new String[0]);
        folded.refresh();
        assertTrue("a folded shelf draws no card body", find(folded, "INSTALLER") == null);
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
        // The row prints the linked deck's build; the frame should show one.
        snapshot.deviceVersion = "1.0.0";
        // The row the board answers with: navy on its dark page, the same look the
        // store holds, so the page circle and the deck agree.
        snapshot.theme = DeckTheme.of(Palette.NAVY, false);
        snapshot.rssi = -61;
        snapshot.writes = 214;
        snapshot.notices = 3;
        snapshot.connectedAtMs = now - 7_200_000L;
        snapshot.lastWriteMs = now - 3_600_000L;
        snapshot.nearby.add(new DeckService.Nearby(DECK_MAC, snapshot.deviceName, -61,
                now - 3_600_000L));
        return snapshot;
    }

    /**
     * The saved-deck row the same board would leave behind, a day old and steady.
     *
     * The look is navy on its dark page, which is also the page the board answered
     * with: the frame then shows a chosen palette, the crescent and a linked deck all
     * agreeing. The palette itself is drawn on the deck now, not on this row, so what
     * the capture has to prove here is only that the ring sits on the right swatch.
     */
    private void pinDeck() {
        DeckStore.rememberAt(host, DeckIdentity.parse("{\"id\":\"" + DECK_ID
                        + "\",\"widget\":\"phone_deck_cyd24R\",\"tag\":\"" + DECK_TAG
                        + "\",\"name\":\"Phone Deck " + DECK_TAG + "\"}"),
                DECK_MAC, "Phone Deck", now - 86_400_000L);
        DeckStore.setLook(host, DECK_ID, new DeckLook(Palette.NAVY, false));
    }

    /** The top of the shelf: whatever the store would put first, opened like a tap. */
    private String firstName() {
        List<ProjectStore.Project> projects = ProjectStore.all(host);
        if (projects.isEmpty()) throw new IllegalStateException("the shelf is empty");
        return projects.get(0).name;
    }

    /**
     * One card with a file, stored the way the shelf would store one. It has to be a
     * card of the user's own: a widget ships its sketch inside the app and a
     * catalogue card is an installer link, so neither can hold a file.
     */
    private void pinFile() {
        List<FlashLibrary.Entry> entries = FlashLibrary.list(host);
        if (entries.isEmpty()) return;
        FlashLibrary.Entry entry = entries.get(0);
        List<ProjectStore.Project> projects = new ArrayList<>(ProjectStore.all(host));
        ProjectStore.Project mine = new ProjectStore.Project();
        mine.id = "user:imported";
        mine.name = OWN_CARD;
        mine.tagline = "A sketch off this phone";
        mine.fileName = entry.name;
        mine.kind = entry.textual()
                ? ProjectStore.KIND_SKETCH : ProjectStore.KIND_IMAGE;
        mine.status = ProjectStore.STATUS_FLASHED;
        mine.touch = FirmwareNotes.TOUCH_UNTESTED;
        mine.updatedMs = now - 3_600_000L;
        projects.add(mine);
        ProjectStore.save(host, projects);
    }

    /** One screen in the bar's column, full height, optionally tapped. */
    private void capture(String name, int section, View screen, DeckService.Snapshot link,
                         View flow) throws Exception {
        draw(name, section, screen, flow, new String[0], link, null, false);
    }

    private void capture(String name, int section, View screen, DeckService.Snapshot link,
                         View flow, String tap) throws Exception {
        draw(name, section, screen, flow, new String[] { tap }, link, null, false);
    }

    /** A screen with an overlay on top, at the phone's own height. */
    private void captureOverlay(String name, int section, View screen,
                                DeckService.Snapshot link, View overlay) throws Exception {
        draw(name, section, screen, null, new String[0], link, overlay, true);
    }

    /**
     * The bar and one screen in a column, exactly as {@code MainActivity.buildUi}
     * stacks them, optionally tapped, then drawn and written out.
     */
    private void draw(String name, int section, View screen, View flow, String[] taps,
                      DeckService.Snapshot link, View overlay, boolean viewport)
            throws Exception {
        LinearLayout page = Ui.column(host);
        page.setBackgroundColor(Ui.BG);

        TopBar bar = new TopBar(host);
        bar.showSection(section);
        // MainActivity.refresh() makes this call every second; a capture that skipped
        // it drew a bridge the frame below it contradicted.
        bar.showLink(link == null ? new DeckService.Snapshot() : link,
                link != null && link.running);
        page.addView(bar.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (flow != null) {
            page.addView(flow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (screen != null) {
            refresh(screen);
            page.addView(screen, viewport
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            for (String tap : taps) {
                tapText(screen, tap);
                refresh(screen);
            }
        }

        FrameLayout root = new FrameLayout(host);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (overlay != null) {
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshOverlay(overlay);
        }

        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewport ? PHONE_HEIGHT : 4000,
                        viewport ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST));
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

    /** A sheet's content is asked for its state the way the app's loop does. */
    private static void refreshOverlay(View overlay) {
        if (overlay instanceof Sheet) {
            View content = ((Sheet) overlay).content();
            refresh(content);
        }
    }

    /**
     * Clicks what says {@code label}: the view itself when it carries the click
     * listener, the row around it when the listener is one level up, which is how
     * the pull-down's rows and the shelf's header are wired.
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

    /** Every label on a screen, for a failure message that says what was drawn. */
    private static String texts(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText()).append(" | ");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append(texts(group.getChildAt(i)));
        }
        return out.toString();
    }

    /** The screens and sheets build themselves on refresh, the same call the phone makes. */
    private static void refresh(View screen) {
        if (screen == null) return;
        if (screen instanceof DecksTab) ((DecksTab) screen).refresh();
        else if (screen instanceof ProjectsTab) ((ProjectsTab) screen).refresh();
        else if (screen instanceof BoardTab) ((BoardTab) screen).refresh();
        else if (screen instanceof SetupTab) ((SetupTab) screen).refresh();
        else if (screen instanceof BridgeSheet) ((BridgeSheet) screen).refresh();
        else if (screen instanceof AppearanceSheet) ((AppearanceSheet) screen).refresh();
    }
}
