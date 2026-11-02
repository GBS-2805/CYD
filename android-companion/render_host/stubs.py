#!/usr/bin/env python3
"""Write the Android stand-ins the render host compiles against.

The app's screens are built by Java, so a render that is not a transcription has to
run that Java. This script emits the little of `android.*` those builders touch, plus
the four app classes that would need a phone to answer — the deck's live link, the
project store, the widget catalog, and the section numbers `MainActivity` hands out.
Everything the *layout* needs is the app's own; what is faked here is the data a
device would supply, and the platform, which is not on a JVM.

    python3 stubs.py <out-dir>

The output is generated into the build directory, never committed: the source of
truth is this file, and `run.sh` regenerates it before every compile.
"""

import os
import sys

TRIVIAL = {
    # -- the platform, as far as these builders reach -----------------------
    "android/content/res/Resources.java": """
package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    private final DisplayMetrics metrics = new DisplayMetrics();

    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }
}
""",
    "android/util/DisplayMetrics.java": """
package android.util;

/** Density 1.0: one dp is one unit here, and the painter scales the canvas. */
public class DisplayMetrics {
    public float density = 1f;
    public float scaledDensity = 1f;
    public int densityDpi = 160;

    public float density() {
        return density;
    }
}
""",
    "android/util/TypedValue.java": """
package android.util;

import android.util.DisplayMetrics;

public class TypedValue {
    public static final int COMPLEX_UNIT_PX = 0;
    public static final int COMPLEX_UNIT_DIP = 1;
    public static final int COMPLEX_UNIT_SP = 2;

    public static float applyDimension(int unit, float value, DisplayMetrics metrics) {
        switch (unit) {
            case COMPLEX_UNIT_DIP:
                return value * metrics.density;
            case COMPLEX_UNIT_SP:
                return value * metrics.scaledDensity;
            default:
                return value;
        }
    }
}
""",
    "android/content/Context.java": """
package android.content;

import android.content.res.Resources;

/** Only what a view constructor asks for: resources, and so a density. */
public class Context {
    private final Resources resources = new Resources();

    public Resources getResources() {
        return resources;
    }
}
""",
    "android/app/Activity.java": """
package android.app;

import android.content.Context;

public class Activity extends Context {
}
""",
    "android/graphics/Typeface.java": """
package android.graphics;

public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;

    public static final Typeface DEFAULT = new Typeface();
    /** The app asks for MONOSPACE everywhere; the host resolves it to DejaVu Sans Mono. */
    public static final Typeface MONOSPACE = new Typeface();

    private final int style;

    public Typeface() {
        this(NORMAL);
    }

    public Typeface(int style) {
        this.style = style;
    }

    public int getStyle() {
        return style;
    }
}
""",
    "android/graphics/drawable/Drawable.java": """
package android.graphics.drawable;

/** A background, as much of one as a render needs: a fill and maybe an edge. */
public class Drawable {
    public int fill;
    public int stroke;
    public float radius;
}
""",
    "android/graphics/drawable/GradientDrawable.java": """
package android.graphics.drawable;

/** Ui.rounded() builds one of these for every card and button. */
public class GradientDrawable extends Drawable {
    public static final int RECTANGLE = 0;
    public static final int OVAL = 1;

    public int shape;

    public void setShape(int shape) {
        this.shape = shape;
    }

    public void setColor(int color) {
        fill = color;
    }

    public void setCornerRadius(float radius) {
        this.radius = radius;
    }

    public void setStroke(int width, int color) {
        stroke = color;
    }
}
""",
    "android/view/Gravity.java": """
package android.view;

public class Gravity {
    public static final int NO_GRAVITY = 0;
    public static final int CENTER_HORIZONTAL = 1;
    public static final int CENTER_VERTICAL = 16;
    public static final int CENTER = CENTER_HORIZONTAL | CENTER_VERTICAL;
    public static final int START = 3;
    public static final int LEFT = 3;
    public static final int TOP = 48;
    public static final int END = 5;
    public static final int RIGHT = 5;
    public static final int BOTTOM = 80;
}
""",

    # -- the widgets these two screens use that carry no layout of their own -
    "android/widget/Button.java": """
package android.widget;

import android.content.Context;
import android.view.Gravity;

/** A TextView with the platform's centred label and no minimum size surprises. */
public class Button extends TextView {
    public Button(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
    }

    public void setAllCaps(boolean allCaps) {
    }

    public void setMinHeight(int min) {
        setMinimumHeight(min);
    }

    public void setMinWidth(int min) {
        setMinimumWidth(min);
    }
}
""",
    "android/widget/ImageView.java": """
package android.widget;

import android.content.Context;
import android.view.View;

public class ImageView extends View {
    private String art;

    public ImageView(Context context) {
        super(context);
    }

    public void setImageResource(int resource) {
        this.art = resource == dev.phonedesk.companion.R.drawable.logo_mark ? "logo" : null;
    }

    public String art() {
        return art;
    }

    @Override
    public void emit(render.Ops out) {
        super.emit(out);
        if (art != null) {
            out.image(left, top, measuredWidth, measuredHeight, art);
        }
    }
}
""",
    "android/widget/ScrollView.java": """
package android.widget;

import android.content.Context;
import android.view.View;

/** One child, measured without a height limit: the frame is as tall as its content. */
public class ScrollView extends android.view.ViewGroup {
    private int scrollY;

    public ScrollView(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int availWidth, boolean exactWidth) {
        int width = lp != null && lp.width > 0 ? lp.width : availWidth;
        View child = getChildCount() == 0 ? null : getChildAt(0);
        int height = padTop + padBottom;
        if (child != null) {
            child.measure(Math.max(0, width - padLeft - padRight), !wraps(child));
            height += child.measuredHeight;
        }
        measuredWidth = width;
        measuredHeight = exactWidth && lp != null && lp.height > 0
                ? lp.height : Math.max(height, 0);
    }

    private boolean wraps(View child) {
        return child.lp == null || child.lp.width <= 0;
    }

    @Override
    protected void onLayout() {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child != null) {
            child.layout(left + padLeft, top + padTop - scrollY);
        }
    }

    public int getScrollY() {
        return scrollY;
    }

    public void scrollTo(int x, int y) {
        scrollY = y;
    }
}
""",

    # -- the app classes a phone would answer ------------------------------
    "dev/phonedesk/companion/R.java": """
package dev.phonedesk.companion;

/** The two resource ids a generated R would hold: only the mark is drawn by code. */
public final class R {
    public static final class drawable {
        public static final int logo_mark = 0x7f070001;
    }
}
""",
    "dev/phonedesk/companion/MainActivity.java": """
package dev.phonedesk.companion;

import android.app.Activity;

/**
 * Only the section numbers, because DrawerHome names them when it wires a row to a
 * screen. The activity itself needs a Bundle, a window and a service; nothing about
 * the drawer's layout depends on any of that.
 */
public class MainActivity extends Activity {
    static final int HOME = -1;
    static final int SECTION_DECKS = 0;
    static final int SECTION_PROJECTS = 1;
    static final int SECTION_SERIAL = 2;
    static final int SECTION_SETTINGS = 3;
}
""",
    "dev/phonedesk/companion/DeckService.java": """
package dev.phonedesk.companion;

/**
 * The live link, as a fixture: the state the render shows, in the shape DrawerHome
 * reads. The real class owns Bluetooth and a foreground service, and none of that
 * decides where a wordmark sits.
 */
public final class DeckService {

    public static final class Snapshot {
        public final boolean linked;
        public final boolean running;
        public final String deviceName;

        public Snapshot(boolean linked, boolean running, String deviceName) {
            this.linked = linked;
            this.running = running;
            this.deviceName = deviceName;
        }
    }

    private static Snapshot snapshot = new Snapshot(false, false, "");

    private DeckService() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean isRunning() {
        return snapshot.running;
    }
}
""",
    "dev/phonedesk/companion/ProjectStore.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The shelf's contents as fixtures, in the shape the tabs read. */
public final class ProjectStore {

    static final int STATUS_WANT = 0;
    static final int STATUS_FLASHED = 1;
    static final int STATUS_WORKING = 2;
    static final int STATUS_PARKED = 3;
    static final String[] STATUS_LABELS =
            {"WANT TO TRY", "FLASHED", "WORKING", "PARKED"};

    public static final class Project {
        public final String id;
        public final String name;
        public final int status;
        public final String body;

        public Project(String id, String name, int status, String body) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.body = body;
        }
    }

    private static final List<Project> ALL = new ArrayList<>();

    static {
        ALL.add(new Project("launcher", "Bruce", STATUS_PARKED,
                "A launcher that swaps firmware from a menu instead of reflashing: "
                        + "pick an app, and it hands the board over to it."));
        ALL.add(new Project("marauder", "ESP32 Marauder", STATUS_WANT,
                "Wi-Fi and Bluetooth survey suite. Prebuilt image, so there is nothing "
                        + "to compile for it."));
        ALL.add(new Project("launcher2", "Launcher", STATUS_PARKED,
                "A menu of the images already on the board, booted without a cable."));
        ALL.add(new Project("phone_deck_cyd24R", "Phone Deck \\u00b7 2.4\\" CYD (resistive)",
                STATUS_WORKING,
                "A desk dashboard for your phone: clock, battery and notifications, set "
                        + "like a woodblock print in two monochrome themes."));
        ALL.add(new Project("spotify_deck_cyd24R",
                "Spotify Deck \\u00b7 2.4\\" CYD (resistive)", STATUS_WORKING,
                "What you are playing on Spotify, with previous / play / next on the "
                        + "screen."));
    }

    private ProjectStore() {
    }

    public static List<Project> all(Context context) {
        return ALL;
    }

    public static int version(Context context) {
        return 1;
    }

    public static int count(List<Project> projects, int status) {
        int count = 0;
        for (Project project : projects) if (project.status == status) ++count;
        return count;
    }
}
""",
    "dev/phonedesk/companion/WidgetCatalog.java": """
package dev.phonedesk.companion;

import android.content.Context;

/** The one board this build ships, labelled the way its descriptor names it. */
public final class WidgetCatalog {

    private WidgetCatalog() {
    }

    public static String boardLabel(Context context, String projectId) {
        return "2.4\\" CYD (resistive)";
    }
}
""",
}


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: stubs.py <out-dir>")
    out = argv[1]
    for path, source in TRIVIAL.items():
        target = os.path.join(out, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(source.lstrip("\n"))
    print("%d stand-ins written under %s" % (len(TRIVIAL), out))


if __name__ == "__main__":
    main(sys.argv)
