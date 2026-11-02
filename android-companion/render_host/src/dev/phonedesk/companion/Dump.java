package dev.phonedesk.companion;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
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

        drawer.measure(WIDTH_DP, true);
        drawer.layout(0, 0);

        Ops ops = new Ops();
        drawer.emit(ops);
        return ops.document(open ? "home-open" : "home", WIDTH_DP,
                Math.max(HEIGHT_DP, drawer.measuredHeight));
    }

    /** SETTINGS: permissions, the allowlist and the bridge switch, as the app builds them. */
    private static String settings() {
        Activity activity = new Activity();
        SetupTab tab = new SetupTab(activity);
        tab.refresh();
        tab.measure(WIDTH_DP, true);
        tab.layout(0, 0);

        Ops ops = new Ops();
        tab.emit(ops);
        return ops.document("settings", WIDTH_DP, Math.max(HEIGHT_DP, tab.measuredHeight));
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
