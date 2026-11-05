package dev.phonedesk.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Host for the app's four sections and its two sheets.
 *
 * The screens are PAIRED (the live link), BOARDS (the shelf), SERIAL (OTG, flashing,
 * the monitor) and SETTINGS (every permission in one list). Paired is where the app
 * opens, because the link is the app's job.
 *
 * Around it, the two controls the owner's site has: a <b>pull-down</b> under the
 * header carrying the three destinations, and a <b>side panel</b> from the right
 * carrying setup — settings, the bridge sheet and the appearance sheet. The two lists exist because they answer different questions: where you go,
 * and how the app is set up.
 *
 * There is still no tab bar. BACK closes whatever is open, then leaves a section for
 * PAIRED, then leaves the app — the order a person expects from a stack, which is
 * what this is.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 2;
    /** Where the section was, across the rebuild an appearance change causes. */
    private static final String KEY_SECTION = "section";
    private static final String KEY_PULL = "pull";
    /** The cards that were unfolded, which no view property can carry. */
    private static final String KEY_EXPANDED = "expanded";
    /** How far down the screen on show was, across the same rebuild. */
    private static final String KEY_SCROLL = "scroll";

    /**
     * A stable id per screen, so the render host and the tests can find the one they
     * mean without holding the view.
     *
     * The <b>scroll offset</b> is carried by this class rather than left to the view
     * hierarchy. {@code ScrollView} does save and restore its own offset, but it
     * applies the restored value during its first layout — when the rebuilt tree has
     * no child height yet — so the value is clamped to zero and lost. Reading it here
     * and scrolling after layout is one integer in the saved state and it survives a
     * rebuild at any depth, which the capture proves rather than assumes.
     */
    static final int ID_DECKS = 0x0f000002;
    static final int ID_PROJECTS = 0x0f000003;
    static final int ID_SERIAL = 0x0f000004;
    static final int ID_SETTINGS = 0x0f000005;
    static final int ID_PULL = 0x0f000006;

    /** Sections, in the order the pull-down and the panel list them. PAIRED is first. */
    static final int SECTION_DECKS = 0;
    static final int SECTION_PROJECTS = 1;
    static final int SECTION_SERIAL = 2;
    static final int SECTION_SETTINGS = 3;

    /** Sheets, opened from the side panel. */
    static final int SHEET_BRIDGE = 0;
    static final int SHEET_APPEARANCE = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private DecksTab decksTab;
    private ProjectsTab projectsTab;
    private BoardTab boardTab;
    private SetupTab setupTab;
    private BridgeSheet bridgeSheet;
    private AppearanceSheet appearanceSheet;
    private PullDown pullDown;
    private SidePanel sidePanel;
    private Sheet sheet;
    private FrameLayout content;
    private TopBar topBar;
    private int activeSection = SECTION_DECKS;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            DeckService.Snapshot snapshot = DeckService.snapshot();
            if (topBar != null) topBar.showLink(snapshot, DeckService.isRunning());
            switch (activeSection) {
                case SECTION_PROJECTS:
                    projectsTab.refresh();
                    break;
                case SECTION_SERIAL:
                    boardTab.refresh();
                    break;
                case SECTION_SETTINGS:
                    setupTab.refresh();
                    break;
                default:
                    decksTab.refresh();
                    break;
            }
            if (sidePanel != null && sidePanel.isOpen()) sidePanel.refresh();
            refreshSheet();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Before any view is built: a crash with no adb attached is otherwise just
        // a system dialog with no trace. SETTINGS shows what this records.
        CrashLog.install(this);
        // Before any view exists: every colour below is read when one is built.
        Appearance.apply(this);
        // The bars are the system's, not ours, so they need telling.
        Ui.applySystemBars(this);
        setContentView(buildUi());
        if (savedInstanceState != null) {
            // Before the first refresh, so an unfolded card is built open rather
            // than folded and then flipped a frame later.
            projectsTab.restoreExpanded(savedInstanceState.getStringArray(KEY_EXPANDED));
            showSection(savedInstanceState.getInt(KEY_SECTION, SECTION_DECKS));
            if (savedInstanceState.getBoolean(KEY_PULL, false)) openPullDown();
            // Applied on the first layout that has content under it, because a scroll
            // view cannot move past a child it has not measured: a post lands before
            // the rebuilt screen has been filled in, and the offset is clamped to
            // zero. The listener fires from layout itself, runs once, and removes
            // itself.
            final int scroll = savedInstanceState.getInt(KEY_SCROLL, 0);
            if (scroll > 0) {
                final android.widget.ScrollView target = scrollOf(activeSection);
                target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View view, int left, int top, int right,
                                               int bottom, int oldLeft, int oldTop,
                                               int oldRight, int oldBottom) {
                        if (target.getChildCount() == 0
                                || target.getChildAt(0).getHeight() == 0) {
                            return;
                        }
                        target.scrollTo(0, scroll);
                        target.removeOnLayoutChangeListener(this);
                        // Once more, after the screen's own queued restore. Each
                        // screen re-applies the offset it read at the top of its
                        // refresh, and on a rebuild that read is zero — so the
                        // screen's message would undo this one a frame later.
                        target.post(() -> target.scrollTo(0, scroll));
                    }
                });
            }
        }
        requestBluetoothPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);

        // Once setup is done there is nothing left to tap: bring the link up.
        if (DeckService.notificationAccess(this) && DeckService.bluetoothReady(this)
                && !DeckService.isRunning()) {
            DeckService.start(this);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /**
     * BACK unwinds this activity's own stack before it leaves: a sheet, then the
     * panel, then the pull-down, then the section, then the app.
     */
    @Override
    public void onBackPressed() {
        if (sheet.isOpen()) {
            sheet.close();
            return;
        }
        if (sidePanel.isOpen()) {
            sidePanel.close();
            return;
        }
        if (pullDown.getVisibility() == View.VISIBLE) {
            closePullDown();
            return;
        }
        if (activeSection != SECTION_DECKS) {
            showSection(SECTION_DECKS);
            return;
        }
        super.onBackPressed();
    }

    /**
     * The section survives a rebuild, which today means an appearance change: a
     * colour is read when a view is constructed, so {@code recreate()} is how the
     * app repaints itself, and returning to PAIRED would throw away the screen the
     * user was reading.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SECTION, activeSection);
        outState.putBoolean(KEY_PULL, pullDown.getVisibility() == View.VISIBLE);
        outState.putInt(KEY_SCROLL, scrollOf(activeSection).getScrollY());
        outState.putStringArray(KEY_EXPANDED, projectsTab.expandedIds());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Two screens can import now, and each gets its own answer back.
        if (requestCode == ProjectsTab.REQUEST_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                projectsTab.imported(null);
                return;
            }
            projectsTab.imported(FlashLibrary.importEntry(this, data.getData()));
            return;
        }
        if (requestCode != BoardTab.REQUEST_IMPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            boardTab.imported(false);
            return;
        }
        Uri uri = data.getData();
        boardTab.imported(FlashLibrary.importUri(this, uri));
    }

    // ------------------------------------------------------------------ ui ---

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.BG);
        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The pull-down is in flow, not an overlay: a menu that covers the page it is
        // a menu for makes you close it to see where you are.
        pullDown = new PullDown(this, this::showSection);
        pullDown.setId(ID_PULL);
        pullDown.setVisibility(View.GONE);
        page.addView(pullDown, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        decksTab = new DecksTab(this);
        projectsTab = new ProjectsTab(this);
        boardTab = new BoardTab(this);
        // A card's FLASH is a request to the one screen that writes bytes: hand it
        // the image and open SERIAL rather than growing a second flasher here.
        projectsTab.handoff(name -> {
            boardTab.pick(name);
            showSection(SECTION_SERIAL);
        });
        setupTab = new SetupTab(this);
        decksTab.setId(ID_DECKS);
        projectsTab.setId(ID_PROJECTS);
        boardTab.setId(ID_SERIAL);
        setupTab.setId(ID_SETTINGS);
        content.addView(decksTab, frameParams());
        content.addView(projectsTab, frameParams());
        content.addView(boardTab, frameParams());
        content.addView(setupTab, frameParams());

        page.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bridgeSheet = new BridgeSheet(this);
        appearanceSheet = new AppearanceSheet(this);
        sheet = new Sheet(this);
        root.addView(sheet, frameParams());

        sidePanel = new SidePanel(this, this::openPanelRow);
        root.addView(sidePanel, frameParams());

        applySystemBarInsets(page);
        showSection(SECTION_DECKS);
        return root;
    }

    /**
     * The bar itself lives in {@link TopBar}, because the render host measures it
     * and this class cannot be built off a device. Here it is only the views, the
     * two taps they answer and the row they are added as.
     */
    private LinearLayout buildTopBar() {
        topBar = new TopBar(this);
        topBar.crumb.setOnClickListener(v -> {
            if (pullDown.getVisibility() == View.VISIBLE) closePullDown();
            else openPullDown();
        });
        topBar.menu.setOnClickListener(v -> openPanel());
        return topBar.bar;
    }

    /**
     * Android 15 draws every window edge to edge for apps targeting 35, so the bar
     * would sit under the clock and the last row under the gesture handle. Pad the
     * two ends and leave the middle alone. Below 15 the window still fits the bars
     * itself, and adding this padding there would push the bar down for no reason.
     */
    private void applySystemBarInsets(LinearLayout page) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        final int side = Ui.dp(this, 14);
        final int top = Ui.dp(this, 12);
        final int under = Ui.dp(this, 8);

        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.bar.setPadding(side, top + bars.top, side, under);
            content.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        page.requestApplyInsets();
    }

    private FrameLayout.LayoutParams frameParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** The section on screen, which is what a rebuild has to restore. */
    int section() {
        return activeSection;
    }

    /** The scroll view behind a section: every screen here is one. */
    private android.widget.ScrollView scrollOf(int section) {
        switch (section) {
            case SECTION_PROJECTS:
                return projectsTab;
            case SECTION_SERIAL:
                return boardTab;
            case SECTION_SETTINGS:
                return setupTab;
            default:
                return decksTab;
        }
    }

    /** Shows one screen. */
    void showSection(int index) {
        activeSection = index;
        decksTab.setVisibility(index == SECTION_DECKS ? View.VISIBLE : View.GONE);
        projectsTab.setVisibility(index == SECTION_PROJECTS ? View.VISIBLE : View.GONE);
        boardTab.setVisibility(index == SECTION_SERIAL ? View.VISIBLE : View.GONE);
        setupTab.setVisibility(index == SECTION_SETTINGS ? View.VISIBLE : View.GONE);

        topBar.showSection(index);
        closePullDown();
        sidePanel.close();

        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    void openPullDown() {
        pullDown.setVisibility(View.VISIBLE);
        topBar.showDrawer(true);
    }

    void closePullDown() {
        pullDown.setVisibility(View.GONE);
        topBar.showDrawer(false);
    }

    void openPanel() {
        closePullDown();
        sidePanel.show();
        topBar.showPanel(true);
    }

    /** A side panel row: SETTINGS is a screen, the other two are sheets. */
    private void openPanelRow(int row) {
        if (row == SidePanel.ROW_SETTINGS) {
            showSection(SECTION_SETTINGS);
            return;
        }
        openSheet(row == SidePanel.ROW_BRIDGE ? SHEET_BRIDGE : SHEET_APPEARANCE);
    }

    void openSheet(int which) {
        sidePanel.close();
        if (which == SHEET_BRIDGE) {
            bridgeSheet.refresh();
            sheet.open("Bridge", bridgeSheet);
        } else {
            appearanceSheet.refresh();
            sheet.open("Appearance", appearanceSheet);
        }
    }

    /** The sheet on show asks its own view to redraw, once a second. */
    private void refreshSheet() {
        if (!sheet.isOpen()) return;
        View content = sheet.content();
        if (content == bridgeSheet) bridgeSheet.refresh();
        else if (content == appearanceSheet) appearanceSheet.refresh();
    }

    // ---------------------------------------------------------- permissions ---

    /** Also called from SETTINGS when a permission was denied earlier. */
    public void requestBluetoothPermissions() {
        java.util.ArrayList<String> wanted = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    /** POST_NOTIFICATIONS, which is its own request since Android 13. */
    public void requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQ_PERMISSIONS);
    }
}
