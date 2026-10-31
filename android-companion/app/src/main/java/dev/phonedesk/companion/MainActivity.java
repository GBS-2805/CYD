package dev.phonedesk.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
import android.widget.TextView;

/**
 * Host for the drawer and the sections behind it — Decks (live link), Projects
 * (the shelf) and Serial (OTG connection, flashing and the monitor) — plus
 * Settings (permissions and the bridge), which the gear in the top bar opens so
 * the drawer stays three rows of navigation.
 *
 * There is no tab bar. The drawer is the front page, so a section is opened from
 * a row and left with BACK — the gesture the platform already teaches — and the
 * top bar carries the way back plus the link pill.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 2;

    /** The drawer itself; sections are numbered in the order the drawer lists them. */
    static final int HOME = -1;
    static final int SECTION_DECKS = 0;
    static final int SECTION_PROJECTS = 1;
    static final int SECTION_SERIAL = 2;
    static final int SECTION_SETTINGS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private DrawerHome home;
    private DecksTab decksTab;
    private ProjectsTab projectsTab;
    private BoardTab boardTab;
    private SetupTab setupTab;
    private FrameLayout content;
    private LinearLayout topBar;
    private TextView crumb;
    private TextView gear;
    private TextView pill;
    private int activeSection = HOME;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            DeckService.Snapshot snapshot = DeckService.snapshot();
            if (pill != null) {
                if (snapshot.linked) {
                    pill.setText("LINKED");
                    pill.setTextColor(Ui.GOOD);
                } else if (DeckService.isRunning()) {
                    pill.setText("SCANNING");
                    pill.setTextColor(Ui.ACCENT);
                } else {
                    pill.setText("IDLE");
                    pill.setTextColor(Ui.DIM);
                }
            }
            switch (activeSection) {
                case SECTION_DECKS:
                    decksTab.refresh();
                    break;
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
                    home.refresh();
                    break;
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Before any view is built: a crash with no adb attached is otherwise just
        // a system dialog with no trace. SETTINGS shows what this records.
        CrashLog.install(this);
        setContentView(buildUi());
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

    @Override
    public void onBackPressed() {
        // A section is one step from the drawer, so BACK is that step. Only the
        // drawer itself leaves the app.
        if (activeSection != HOME) {
            showSection(HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        topBar = buildTopBar();
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        home = new DrawerHome(this, this::showSection);
        decksTab = new DecksTab(this);
        projectsTab = new ProjectsTab(this);
        boardTab = new BoardTab(this);
        setupTab = new SetupTab(this);
        content.addView(home, frameParams());
        content.addView(decksTab, frameParams());
        content.addView(projectsTab, frameParams());
        content.addView(boardTab, frameParams());
        content.addView(setupTab, frameParams());

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        applySystemBarInsets(root);
        showSection(HOME);
        return root;
    }

    /**
     * The crumb, the link pill and the settings gear. The crumb reads OFF-SITE on
     * the drawer and carries a back chevron everywhere else, so the bar is the
     * same height and the same place on every screen. SETTINGS is not a row in the
     * drawer: it is configuration, not navigation, so it sits at the outer end of
     * the bar, past the pill.
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 8));

        crumb = Ui.text(this, "OFF-SITE", 12, Ui.DIM);
        crumb.setLetterSpacing(0.12f);
        crumb.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        crumb.setOnClickListener(v -> showSection(HOME));
        bar.addView(crumb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pill = Ui.text(this, "IDLE", 11, Ui.DIM);
        pill.setLetterSpacing(0.14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setBackground(Ui.rounded(this, Ui.CARD, Ui.EDGE, 9));
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        bar.addView(pill);

        // \u2699 with the text-presentation selector: a glyph, not the emoji, which
        // would arrive in colour on a monochrome screen. It sits outside the pill,
        // hard against the screen edge, so it reads as chrome rather than as part
        // of the link state the pill reports.
        gear = Ui.text(this, "\u2699\uFE0E", 15, Ui.DIM);
        gear.setGravity(android.view.Gravity.CENTER);
        gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
        gear.setOnClickListener(v -> showSection(SECTION_SETTINGS));
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gearParams.leftMargin = Ui.dp(this, 10);
        bar.addView(gear, gearParams);
        return bar;
    }

    /**
     * Android 15 draws every window edge to edge for apps targeting 35, so the bar
     * would sit under the clock and the last row under the gesture handle. Pad the
     * two ends and leave the middle alone. Below 15 the window still fits the bars
     * itself, and adding this padding there would push the bar down for no reason.
     */
    private void applySystemBarInsets(LinearLayout root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        final int side = Ui.dp(this, 18);
        final int top = Ui.dp(this, 14);
        final int under = Ui.dp(this, 8);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.setPadding(side, top + bars.top, side, under);
            content.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private FrameLayout.LayoutParams frameParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Shows one screen: {@link #HOME} for the drawer, otherwise a section. */
    void showSection(int index) {
        activeSection = index;
        home.setVisibility(index == HOME ? View.VISIBLE : View.GONE);
        decksTab.setVisibility(index == SECTION_DECKS ? View.VISIBLE : View.GONE);
        projectsTab.setVisibility(index == SECTION_PROJECTS ? View.VISIBLE : View.GONE);
        boardTab.setVisibility(index == SECTION_SERIAL ? View.VISIBLE : View.GONE);
        setupTab.setVisibility(index == SECTION_SETTINGS ? View.VISIBLE : View.GONE);

        crumb.setText(index == HOME ? "OFF-SITE" : "‹  OFF-SITE");
        crumb.setTextColor(index == HOME ? Ui.DIM : Ui.TEXT);
        if (gear != null) gear.setTextColor(index == SECTION_SETTINGS ? Ui.TEXT : Ui.DIM);

        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    // ---------------------------------------------------------- permissions ---

    /** Also called from Settings when permissions were denied earlier. */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }
}
