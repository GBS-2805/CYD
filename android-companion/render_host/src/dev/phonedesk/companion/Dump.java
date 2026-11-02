package dev.phonedesk.companion;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import render.Ops;

/**
 * Build the screens with the app's own code, measure them, and write down where
 * everything landed.
 *
 * This is the whole point of the host: `home()` below is a fixture and a
 * `DrawerHome` — the drawer decides its own paddings, row heights and wordmark. A
 * frame that changes in the app changes here without anyone transcribing a number,
 * and the painter has nothing left to guess. It lives in the app's package because
 * the drawers are package-private, the same way they are on the device.
 *
 * Every frame is stacked under {@link TopBar}, so the bar is measured too — its
 * crumb, its pill and its gear are the app's views, not a redrawing of them. The
 * three `bar-*` frames exist for the screens whose tabs still cannot run here: they
 * paint the same measured bar and nothing else.
 *
 *     java -cp classes dev.phonedesk.companion.Dump dump.json
 */
public final class Dump {

    static final int WIDTH_DP = 393;
    static final int HEIGHT_DP = 852;

    private Dump() {
    }

    public static void main(String[] args) throws IOException {
        String out = args.length > 0 ? args[0] : "dump.json";
        List<String> frames = new ArrayList<>();
        frames.add(home(false));
        frames.add(home(true));
        frames.add(settings());
        frames.add(bar("bar-projects", MainActivity.SECTION_PROJECTS, false, false));
        frames.add(bar("bar-decks", MainActivity.SECTION_DECKS, true, true));
        frames.add(bar("bar-serial", MainActivity.SECTION_SERIAL, false, false));

        StringBuilder document = new StringBuilder("{\"frames\":[\n");
        for (int i = 0; i < frames.size(); ++i) {
            if (i > 0) document.append(",\n");
            document.append(frames.get(i).trim());
        }
        document.append("\n]}\n");
        Files.write(Path.of(out), document.toString().getBytes(StandardCharsets.UTF_8));

        for (String frame : frames) {
            System.out.println("  " + field(frame, "\"name\":") + "  "
                    + field(frame, "\"width\":") + " x " + field(frame, "\"height\":"));
        }
    }

    /** The drawer, collapsed or with BOARDS open — the open frame is a real tap. */
    private static String home(boolean open) {
        Activity activity = new Activity();
        DrawerHome drawer = new DrawerHome(activity, section -> {
        });
        drawer.refresh();

        if (open) {
            drawer.measure(WIDTH_DP, true);
            drawer.layout(0, 0);
            tap(drawer, "BOARDS");
        }

        return composed(open ? "home-open" : "home", MainActivity.HOME, false, false, drawer);
    }

    /** SETTINGS: permissions, the allowlist and the bridge switch, as the app builds them. */
    private static String settings() {
        Activity activity = new Activity();
        SetupTab tab = new SetupTab(activity);
        tab.refresh();
        return composed("settings", MainActivity.SECTION_SETTINGS, false, false, tab);
    }

    /** One screen under the bar it belongs to, measured as a column, bar included. */
    private static String composed(String name, int section, boolean linked, boolean running,
                                   View content) {
        LinearLayout root = stack(section, linked, running, content);
        Ops ops = new Ops();
        root.emit(ops);
        return ops.document(name, WIDTH_DP, Math.max(HEIGHT_DP, root.measuredHeight));
    }

    /** The bar alone, in the state a screen shows it: what a transcription paints. */
    private static String bar(String name, int section, boolean linked, boolean running) {
        LinearLayout root = stack(section, linked, running, null);
        Ops ops = new Ops();
        root.emit(ops);
        return ops.document(name, WIDTH_DP, root.measuredHeight);
    }

    private static LinearLayout stack(int section, boolean linked, boolean running,
                                      View content) {
        Activity activity = new Activity();
        TopBar top = new TopBar(activity);
        top.showLink(link(linked), running);
        top.showSection(section);

        LinearLayout root = Ui.column(activity);
        root.addView(top.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (content != null) {
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.measure(WIDTH_DP, true);
        root.layout(0, 0);
        return root;
    }

    /** The link state the phone's service would report, with the drawer's own values. */
    private static DeckService.Snapshot link(boolean linked) {
        return new DeckService.Snapshot(linked, false, linked ? "LINKED" : "IDLE",
                "Phone Deck A1B2C3", "A1B2C3", "24:0A:C4:11:22:33", "phone_deck-A1B2C3",
                -58, 412, 7, 0L, 0L, List.of());
    }

    /** Press the row that carries `label`: the drawer's own listener flips the panel. */
    private static void tap(View root, String label) {
        View holder = find(root, label);
        if (holder == null) {
            throw new IllegalStateException("no view carrying \"" + label + "\" to tap");
        }
        View row = holder;
        while (row != null) {
            if (row.performClick()) return;
            row = row.parent;
        }
        throw new IllegalStateException("\"" + label + "\" has no click listener above it");
    }

    private static View find(View node, String label) {
        if (node instanceof TextView && label.equals(((TextView) node).getText())) {
            return node;
        }
        if (node instanceof ViewGroup) {
            for (View child : ((ViewGroup) node).children()) {
                View found = find(child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String field(String frame, String key) {
        int at = frame.indexOf(key);
        int end = frame.indexOf(',', at);
        return frame.substring(at + key.length(), end);
    }
}
