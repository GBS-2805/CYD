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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Host for the four surfaces: Decks (live link), Projects (the notebook),
 * Board (OTG connection and flashing) and Setup (permissions, bridge).
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 2;
    private static final String[] TAB_TITLES = { "DECKS", "PROJECTS", "BOARD", "SETUP" };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] tabViews = new TextView[TAB_TITLES.length];

    private DecksTab decksTab;
    private ProjectsTab projectsTab;
    private BoardTab boardTab;
    private SetupTab setupTab;
    private FrameLayout content;
    private TextView pill;
    private int activeTab;

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
            if (activeTab == 0) decksTab.refresh();
            else if (activeTab == 1) projectsTab.refresh();
            else if (activeTab == 2) boardTab.refresh();
            else setupTab.refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Before any view is built: a crash with no adb attached is otherwise just
        // a system dialog with no trace. SETUP shows what this records.
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

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 10));

        LinearLayout titles = Ui.column(this);
        titles.addView(Ui.text(this, "OFF-SITE", 22, Ui.TEXT));
        TextView subtitle = Ui.text(this, "cyd projects  //  board", 11, Ui.DIM);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setPadding(0, Ui.dp(this, 2), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pill = Ui.text(this, "IDLE", 11, Ui.DIM);
        pill.setLetterSpacing(0.14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setBackground(Ui.rounded(this, Ui.CARD, Ui.EDGE, 9));
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        header.addView(pill);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        decksTab = new DecksTab(this);
        projectsTab = new ProjectsTab(this);
        boardTab = new BoardTab(this);
        setupTab = new SetupTab(this);
        content.addView(decksTab, frameParams());
        content.addView(projectsTab, frameParams());
        content.addView(boardTab, frameParams());
        content.addView(setupTab, frameParams());

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Tab bar at the bottom, inside thumb reach.
        View tabBar = buildTabBar();
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applySystemBarInsets(root, header, tabBar);

        selectTab(0);
        return root;
    }

    /**
     * Android 15 draws every window edge to edge for apps targeting 35, so the
     * header would sit under the clock and the tab bar under the gesture handle.
     * Pad the two ends by the system bar height and leave the middle alone. Below
     * 15 the window still fits the bars itself, and adding this padding there
     * would push the header down by a status bar for no reason.
     */
    private void applySystemBarInsets(View root, View header, View tabBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        final int side = Ui.dp(this, 18);
        final int top = Ui.dp(this, 18);
        final int under = Ui.dp(this, 10);
        final int tabsSide = Ui.dp(this, 10);
        final int tabsTop = Ui.dp(this, 6);
        final int tabsBottom = Ui.dp(this, 12);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            header.setPadding(side, top + bars.top, side, under);
            tabBar.setPadding(tabsSide, tabsTop, tabsSide, tabsBottom + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private FrameLayout.LayoutParams frameParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 12));

        for (int i = 0; i < TAB_TITLES.length; ++i) {
            final int index = i;
            TextView tab = Ui.text(this, TAB_TITLES[i], 12, Ui.DIM);
            tab.setLetterSpacing(0.1f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            tab.setOnClickListener(v -> selectTab(index));
            tabViews[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void selectTab(int index) {
        activeTab = index;
        decksTab.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        projectsTab.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        boardTab.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        setupTab.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < tabViews.length; ++i) {
            boolean selected = i == index;
            tabViews[i].setTextColor(selected ? Ui.VIOLET : Ui.DIM);
            tabViews[i].setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
            tabViews[i].setBackground(selected
                    ? Ui.rounded(this, Ui.CARD, Ui.VIOLET, 10)
                    : null);
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    // ---------------------------------------------------------- permissions ---

    /** Also called from Setup when permissions were denied earlier. */
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
