#!/usr/bin/env python3
"""Write the Android stand-ins the render host compiles against.

The app's screens are built by Java, so a render that is not a transcription has to
run that Java. This script emits the little of `android.*` the builders touch, plus
the app classes a phone would answer — the link, the stores, the catalog. What it
never emits is a *layout*: paddings, weights, sizes and text are the app's own.

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
    "android/util/Log.java": """
package android.util;

public class Log {
    public static int d(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message) {
        return 0;
    }
}
""",
    "android/content/Context.java": """
package android.content;

import android.content.res.Resources;

/** Resources, and the system services a dialog or a clipboard asks for. */
public class Context {
    public static final String CLIPBOARD_SERVICE = "clipboard";

    private final Resources resources = new Resources();

    public Resources getResources() {
        return resources;
    }

    public Object getSystemService(String name) {
        return CLIPBOARD_SERVICE.equals(name) ? new ClipboardManager() : null;
    }

    public void startActivity(Intent intent) {
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return new SharedPreferences();
    }
}
""",
    "android/content/SharedPreferences.java": """
package android.content;

/** A store with nothing in it: the render's fixtures are the data, not preferences. */
public class SharedPreferences {
    public String getString(String key, String fallback) {
        return fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        return fallback;
    }

    public int getInt(String key, int fallback) {
        return fallback;
    }

    public long getLong(String key, long fallback) {
        return fallback;
    }

    public Editor edit() {
        return new Editor();
    }

    public static class Editor {
        public Editor putString(String key, String value) {
            return this;
        }

        public Editor putBoolean(String key, boolean value) {
            return this;
        }

        public Editor putInt(String key, int value) {
            return this;
        }

        public Editor putLong(String key, long value) {
            return this;
        }

        public Editor remove(String key) {
            return this;
        }

        public void apply() {
        }

        public boolean commit() {
            return true;
        }
    }
}
""",
    "android/content/ClipboardManager.java": """
package android.content;

public class ClipboardManager {
    public void setPrimaryClip(ClipData clip) {
    }
}
""",
    "android/content/ClipData.java": """
package android.content;

public class ClipData {
    public static ClipData newPlainText(String label, String text) {
        return new ClipData();
    }
}
""",
    "android/content/Intent.java": """
package android.content;

import android.net.Uri;

public class Intent {
    private final String action;
    private Uri data;

    public Intent() {
        this((String) null);
    }

    public Intent(String action) {
        this.action = action;
    }

    public Intent(Context context, Class<?> target) {
        this((String) null);
    }

    public Intent(String action, Uri data) {
        this.action = action;
        this.data = data;
    }

    public String getAction() {
        return action;
    }

    public Uri getData() {
        return data;
    }

    public Intent setData(Uri data) {
        this.data = data;
        return this;
    }

    public String getStringExtra(String name) {
        return null;
    }

    public Intent putExtra(String name, String value) {
        return this;
    }

    public Intent setType(String type) {
        return this;
    }

    public Intent addFlags(int flags) {
        return this;
    }

    public Intent setFlags(int flags) {
        return this;
    }
}
""",
    "android/net/Uri.java": """
package android.net;

public class Uri {
    private final String value;

    private Uri(String value) {
        this.value = value;
    }

    public static Uri parse(String value) {
        return new Uri(value);
    }

    public static Uri fromFile(java.io.File file) {
        return new Uri(file.toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
""",
    "android/provider/Settings.java": """
package android.provider;

public class Settings {
    public static final String ACTION_SETTINGS = "android.settings.SETTINGS";
    public static final String ACTION_NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
    public static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS";
}
""",
    "android/os/Build.java": """
package android.os;

public class Build {
    public static class VERSION {
        public static final int SDK_INT = 35;
    }

    public static class VERSION_CODES {
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int VANILLA_ICE_CREAM = 35;
    }
}
""",
    "android/os/Handler.java": """
package android.os;

public class Handler {
    public Handler(Looper looper) {
    }

    public boolean post(Runnable runnable) {
        runnable.run();
        return true;
    }

    public boolean postDelayed(Runnable runnable, long delay) {
        return true;
    }

    public void removeCallbacks(Runnable runnable) {
    }
}
""",
    "android/os/Looper.java": """
package android.os;

public class Looper {
    public static Looper getMainLooper() {
        return new Looper();
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
    public static final Typeface DEFAULT_BOLD = new Typeface(BOLD);
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
    "android/text/InputType.java": """
package android.text;

public class InputType {
    public static final int TYPE_CLASS_TEXT = 1;
    public static final int TYPE_TEXT_FLAG_MULTI_LINE = 0x20000;
}
""",
    "android/text/Editable.java": """
package android.text;

public interface Editable {
    String toString();
}
""",
    "android/text/TextWatcher.java": """
package android.text;

public interface TextWatcher {
    void beforeTextChanged(CharSequence text, int start, int count, int after);

    void onTextChanged(CharSequence text, int start, int before, int count);

    void afterTextChanged(Editable text);
}
""",
    "android/widget/Toast.java": """
package android.widget;

import android.content.Context;

public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    public static Toast makeText(Context context, CharSequence text, int duration) {
        return new Toast();
    }

    public void show() {
    }
}
""",
    "android/widget/AdapterView.java": """
package android.widget;

public class AdapterView<T> {
    public interface OnItemSelectedListener {
        void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id);

        void onNothingSelected(AdapterView<?> parent);
    }
}
""",
    "android/R.java": """
package android;

/** The two platform layouts the shelf's spinner names. */
public final class R {
    public static final class layout {
        public static final int simple_spinner_item = 0x01090003;
        public static final int simple_spinner_dropdown_item = 0x01090005;
    }
}
""",

    # -- widgets ------------------------------------------------------------
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
    "android/widget/EditText.java": """
package android.widget;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;

/** A field: the app only ever asks it to show a hint and hold a line. */
public class EditText extends TextView {
    private String hint = "";
    private int hintColor;
    private CharSequence error;
    private boolean singleLine;

    public EditText(Context context) {
        super(context);
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public String getHint() {
        return hint;
    }

    public void setHintTextColor(int color) {
        this.hintColor = color;
    }

    public int getHintColor() {
        return hintColor;
    }

    public void setSingleLine(boolean single) {
        this.singleLine = single;
    }

    public boolean isSingleLine() {
        return singleLine;
    }

    public void setInputType(int type) {
    }

    public void addTextChangedListener(TextWatcher watcher) {
    }

    public void setSelection(int index) {
    }

    public void setError(CharSequence message) {
        this.error = message;
    }

    public boolean hasFocus() {
        return false;
    }

    public void requestFocus() {
    }
}
""",
    "android/widget/Spinner.java": """
package android.widget;

import android.content.Context;
import android.widget.ArrayAdapter;

/** A picker, measured as one line of its own selected label. */
public class Spinner extends android.view.View {
    private ArrayAdapter<?> adapter;
    private AdapterView.OnItemSelectedListener listener;
    private int selected;

    public Spinner(Context context) {
        super(context);
    }

    public void setAdapter(ArrayAdapter<?> adapter) {
        this.adapter = adapter;
    }

    public ArrayAdapter<?> getAdapter() {
        return adapter;
    }

    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelection(int index) {
        this.selected = index;
    }

    public int getSelectedItemPosition() {
        return selected;
    }

    public Object getSelectedItem() {
        return adapter == null ? null : adapter.getItem(selected);
    }

    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
    }
}
""",
    "android/widget/ArrayAdapter.java": """
package android.widget;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A spinner's labels, and nothing else: the render never opens its dropdown. */
public class ArrayAdapter<T> {
    private final List<T> items;

    public ArrayAdapter(Context context, int resource, T[] items) {
        this.items = new ArrayList<>(Arrays.asList(items));
    }

    public ArrayAdapter(Context context, int resource, List<T> items) {
        this.items = new ArrayList<>(items);
    }

    public void setDropDownViewResource(int resource) {
    }

    public int getCount() {
        return items.size();
    }

    public T getItem(int position) {
        return items.get(position);
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
            child.measure(Math.max(0, width - padLeft - padRight), true);
            height += child.measuredHeight;
        }
        measuredWidth = width;
        measuredHeight = exactWidth && lp != null && lp.height > 0
                ? lp.height : Math.max(height, 0);
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
    "android/widget/HorizontalScrollView.java": """
package android.widget;

import android.content.Context;
import android.view.View;

/**
 * One child, measured at its natural width and clipped at the frame: the chip row is
 * wider than the phone and a thumb reaches the rest, which is why its runs are
 * allowed to leave the canvas.
 */
public class HorizontalScrollView extends android.view.ViewGroup {
    private int scrollX;
    private boolean bar;

    public HorizontalScrollView(Context context) {
        super(context);
    }

    public void setHorizontalScrollBarEnabled(boolean enabled) {
        this.bar = enabled;
    }

    @Override
    protected void onMeasure(int availWidth, boolean exact) {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        int height = padTop + padBottom;
        if (child != null) {
            child.measure(Integer.MAX_VALUE / 4, false);
            height += child.measuredHeight;
        }
        measuredWidth = lp != null && lp.width > 0 ? lp.width : availWidth;
        measuredHeight = height;
    }

    @Override
    protected void onLayout() {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child != null) {
            child.layout(left + padLeft - scrollX, top + padTop);
        }
    }
}
""",
    "android/widget/FrameLayout.java": """
package android.widget;

import android.content.Context;
import android.view.View;

/** Every child on top of the last: the sections all fill the one content frame. */
public class FrameLayout extends android.view.ViewGroup {

    public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }

    public FrameLayout(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int avail, boolean exact) {
        int widest = 0;
        int tallest = 0;
        for (View child : children()) {
            if (child.visibility == GONE) continue;
            child.measure(Math.max(0, avail - padLeft - padRight), true);
            widest = Math.max(widest, child.measuredWidth);
            tallest = Math.max(tallest, child.measuredHeight);
        }
        measuredWidth = exact ? avail : widest + padLeft + padRight;
        measuredHeight = tallest + padTop + padBottom;
        if (lp != null && lp.height > 0) measuredHeight = lp.height;
    }

    @Override
    protected void onLayout() {
        for (View child : children()) {
            if (child.visibility == GONE) continue;
            child.layout(left + padLeft, top + padTop);
        }
    }
}
""",
    "android/app/AlertDialog.java": """
package android.app;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;

/**
 * The dialog shell. Nothing here is drawn — every alert in the app is behind a tap the
 * render never takes — so it only has to accept the calls the click handlers make.
 */
public class AlertDialog {

    public interface OnClickListener {
        void onClick(AlertDialog dialog, int which);
    }

    public interface OnMultiChoiceClickListener {
        void onClick(AlertDialog dialog, int which, boolean checked);
    }

    public static class Builder {
        public Builder(Context context) {
        }

        public Builder setTitle(CharSequence title) {
            return this;
        }

        public Builder setMessage(CharSequence message) {
            return this;
        }

        public Builder setView(View view) {
            return this;
        }

        public Builder setItems(CharSequence[] items, OnClickListener listener) {
            return this;
        }

        public Builder setSingleChoiceItems(CharSequence[] items, int checked,
                                            OnClickListener listener) {
            return this;
        }

        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked,
                                           OnMultiChoiceClickListener listener) {
            return this;
        }

        public Builder setAdapter(ArrayAdapter<?> adapter, OnClickListener listener) {
            return this;
        }

        public Builder setPositiveButton(CharSequence text, OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(CharSequence text, OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(CharSequence text, OnClickListener listener) {
            return this;
        }

        public AlertDialog create() {
            return new AlertDialog();
        }

        public AlertDialog show() {
            return new AlertDialog();
        }
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
 * screen. The activity itself needs a window, a Bundle and a service; nothing about a
 * screen's layout depends on any of that.
 */
public class MainActivity extends Activity {
    static final int HOME = -1;
    static final int SECTION_DECKS = 0;
    static final int SECTION_PROJECTS = 1;
    static final int SECTION_SERIAL = 2;
    static final int SECTION_SETTINGS = 3;

    public void requestBluetoothPermissions() {
    }
}
""",
    "dev/phonedesk/companion/CrashLog.java": """
package dev.phonedesk.companion;

import android.content.Context;

/** No trace stored, so SETTINGS draws without its crash card — as on a clean run. */
public final class CrashLog {

    private CrashLog() {
    }

    public static void install(Context context) {
    }

    public static String read(Context context) {
        return null;
    }

    public static void clear(Context context) {
    }
}
""",
    "dev/phonedesk/companion/Actions.java": """
package dev.phonedesk.companion;

import android.content.Context;

/** Anything that leaves the app: a link, a toast, the clipboard. Not part of a layout. */
public final class Actions {

    private Actions() {
    }

    public static void toast(Context context, String message) {
    }

    public static void openUrl(Context context, String url) {
    }

    public static void copyOrSay(Context context, String label, String value) {
    }

    public static void save(Context context, Object entry) {
    }
}
""",
    "dev/phonedesk/companion/DeckService.java": """
package dev.phonedesk.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * The live link, as a fixture: the state the render shows, in the shape the tabs read.
 * The real class owns Bluetooth and a foreground service, and none of that decides
 * where a row sits. Timestamps are pinned one hour ahead so `Ui.ago` and
 * `Ui.duration` clamp to zero: a committed PNG cannot change with the wall clock.
 */
public final class DeckService {

    public static final class Nearby {
        public final String name;
        public final String mac;
        public final int rssi;
        public final long seenMs;

        Nearby(String name, String mac, int rssi, long seenMs) {
            this.name = name;
            this.mac = mac;
            this.rssi = rssi;
            this.seenMs = seenMs;
        }
    }

    public static final class Snapshot {
        public final boolean linked;
        public final boolean running;
        public final String status;
        public final String deviceName;
        public final String deviceTag;
        public final String deviceMac;
        public final String deviceId;
        public final int rssi;
        public final int writes;
        public final int notices;
        public final long connectedAtMs;
        public final long lastWriteMs;
        public final List<Nearby> nearby;

        Snapshot(boolean linked, boolean running, String status, String deviceName,
                 String deviceTag, String deviceMac, String deviceId, int rssi, int writes,
                 int notices, long connectedAtMs, long lastWriteMs, List<Nearby> nearby) {
            this.linked = linked;
            this.running = running;
            this.status = status;
            this.deviceName = deviceName;
            this.deviceTag = deviceTag;
            this.deviceMac = deviceMac;
            this.deviceId = deviceId;
            this.rssi = rssi;
            this.writes = writes;
            this.notices = notices;
            this.connectedAtMs = connectedAtMs;
            this.lastWriteMs = lastWriteMs;
            this.nearby = nearby;
        }
    }

    /** One hour ahead: `Ui.ago` and `Ui.duration` clamp a future stamp to "0s". */
    private static final long NOW = System.currentTimeMillis() + 3_600_000L;

    private static final Snapshot IDLE = new Snapshot(false, false,
            "Bridge stopped", "", "", "", "", 0, 0, 0, 0, 0, new ArrayList<>());

    private static final Snapshot LINKED = new Snapshot(true, true,
            "Linked to Phone Deck A1B2C3", "Phone Deck A1B2C3", "A1B2C3",
            "24:0A:C4:11:22:33", "phone_deck_cyd24R-A1B2C3", -58, 412, 7,
            NOW, NOW, near());

    private static Snapshot snapshot = IDLE;

    private DeckService() {
    }

    private static List<Nearby> near() {
        List<Nearby> list = new ArrayList<>();
        list.add(new Nearby("Phone Deck A1B2C3", "24:0A:C4:11:22:33", -58, NOW));
        return list;
    }

    public static void link() {
        snapshot = LINKED;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean isRunning() {
        return snapshot.running;
    }

    public static String getStatus() {
        return snapshot.running ? "Bridge running" : "Bridge stopped — start it below";
    }

    public static boolean notificationAccess(android.content.Context context) {
        return false;
    }

    public static boolean bluetoothReady(android.content.Context context) {
        return true;
    }

    public static void start(android.content.Context context) {
    }

    public static void stop(android.content.Context context) {
    }

    public static void connectTo(String mac) {
    }
}
""",
    "dev/phonedesk/companion/DeckStore.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The decks this phone has met, as fixtures. */
public final class DeckStore {

    public static final class Known {
        public final String id;
        public final String name;
        public final String tag;
        public final String mac;
        public final long seenMs;

        Known(String id, String name, String tag, String mac, long seenMs) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.mac = mac;
            this.seenMs = seenMs;
        }

        public String key() {
            return id + "@" + mac;
        }
    }

    private static final long NOW = System.currentTimeMillis() + 3_600_000L;

    private DeckStore() {
    }

    public static List<Known> all(Context context) {
        List<Known> list = new ArrayList<>();
        list.add(new Known("phone_deck_cyd24R-A1B2C3", "Phone Deck A1B2C3", "A1B2C3",
                "24:0A:C4:11:22:33", NOW));
        list.add(new Known("phone_deck_cyd24R-D4E5F6", "Phone Deck D4E5F6", "D4E5F6",
                "24:0A:C4:44:55:66", NOW));
        return list;
    }
}
""",
    "dev/phonedesk/companion/DeckRegistry.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Network decks paired by address; the shelf's DECK block reads one of these. */
public final class DeckRegistry {

    public static final class Deck {
        public String id;
        public String widget;
        public String label;
        public String tag;
        public String address;
        public String state;
        public boolean signed;
        public long seenMs;
    }

    private DeckRegistry() {
    }

    public static int version(Context context) {
        return 1;
    }

    public static List<Deck> forWidget(Context context, String widgetId) {
        List<Deck> list = new ArrayList<>();
        if (!"spotify_deck_cyd24R".equals(widgetId)) return list;
        Deck deck = new Deck();
        deck.id = "spotify_deck_cyd24R-A1B2C3";
        deck.widget = widgetId;
        deck.label = "Spotify Deck";
        deck.tag = "A1B2C3";
        deck.address = "192.168.1.50";
        deck.state = "signed in";
        deck.signed = true;
        deck.seenMs = System.currentTimeMillis() + 3_600_000L;
        list.add(deck);
        return list;
    }

    public static void upsert(Context context, Deck deck) {
    }

    public static void forget(Context context, String id) {
    }
}
""",
    "dev/phonedesk/companion/FlashLibrary.java": """
package dev.phonedesk.companion;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** The sketches bundled in the APK, by name and size. */
public final class FlashLibrary {

    public static final class Entry {
        public final String name;
        public final long size;

        Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private FlashLibrary() {
    }

    public static List<Entry> list(Context context) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("phone_deck_cyd24R.ino", 1168314L));
        list.add(new Entry("spotify_deck_cyd24R.ino", 1125781L));
        return list;
    }

    public static String read(Context context, Entry entry) {
        return "";
    }

    public static boolean importUri(Context context, Uri uri) {
        return false;
    }
}
""",
    "dev/phonedesk/companion/BoardCatalog.java": """
package dev.phonedesk.companion;

import android.content.Context;

/** The board picker's selection: the one CYD this build targets. */
public final class BoardCatalog {

    public static final class Board {
        public final String name;
        public final String model;

        Board(String name, String model) {
            this.name = name;
            this.model = model;
        }
    }

    private static final Board ONE = new Board("ESP32-2432S024R", "2432S024R");

    private BoardCatalog() {
    }

    public static int selectedIndex(Context context) {
        return 0;
    }

    public static Board selected(Context context) {
        return ONE;
    }
}
""",
    "dev/phonedesk/companion/FirmwareNotes.java": """
package dev.phonedesk.companion;

/** The touch state a project records, and the label SETTINGS shows for it. */
public final class FirmwareNotes {

    public static final int TOUCH_UNTESTED = 0;
    public static final int TOUCH_WORKS = 1;
    public static final int TOUCH_BROKEN = 2;

    public static final String[] TOUCH_LABELS = {"UNTESTED", "WORKS", "BROKEN"};

    private FirmwareNotes() {
    }

    public static String touchLabel(int touch) {
        return TOUCH_LABELS[Math.max(0, Math.min(TOUCH_LABELS.length - 1, touch))];
    }
}
""",
    "dev/phonedesk/companion/NoticeFilter.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The per-app allowlist: on a fresh install every app is allowed, so it is off. */
public final class NoticeFilter {

    public static final class App {
        public final String label;
        public final String packageName;

        App(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    private NoticeFilter() {
    }

    public static boolean customised(Context context) {
        return false;
    }

    public static Set<String> allowed(Context context) {
        return new HashSet<>();
    }

    public static void allow(Context context, Set<String> packages) {
    }

    public static void allowAll(Context context) {
    }

    public static List<App> launchable(Context context) {
        return new ArrayList<>();
    }
}
""",
    "dev/phonedesk/companion/WidgetCatalog.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The one board this build ships, with the libraries its sketch needs. */
public final class WidgetCatalog {

    public static final class Widget {
        public final String id;
        public final String name;
        public final String sketch;
        public final String tagline;
        public final String board;
        public final List<String> libraries;
        public final List<String> never;
        public final boolean lan;

        Widget(String id, String name, String sketch, String tagline, String board,
               List<String> libraries, List<String> never, boolean lan) {
            this.id = id;
            this.name = name;
            this.sketch = sketch;
            this.tagline = tagline;
            this.board = board;
            this.libraries = libraries;
            this.never = never;
            this.lan = lan;
        }
    }

    private static final List<String> LIBS =
            Arrays.asList("Adafruit GFX Library", "Adafruit ILI9341", "XPT2046_Touchscreen");
    private static final List<String> NEVER = Arrays.asList("ArduinoBLE");

    private static final Widget PHONE = new Widget("phone_deck_cyd24R",
            "2.4\\" CYD (resistive)", "phone_deck_cyd24R.ino",
            "A desk dashboard for your phone", "2432S024R", LIBS, NEVER, false);
    private static final Widget SPOTIFY = new Widget("spotify_deck_cyd24R",
            "2.4\\" CYD (resistive)", "spotify_deck_cyd24R.ino",
            "What you are playing on Spotify", "2432S024R", LIBS, NEVER, true);

    private WidgetCatalog() {
    }

    public static List<Widget> all(Context context) {
        return Arrays.asList(PHONE, SPOTIFY);
    }

    public static Widget forProject(Context context, String projectId) {
        if ("phone_deck_cyd24R".equals(projectId)) return PHONE;
        if ("spotify_deck_cyd24R".equals(projectId)) return SPOTIFY;
        return null;
    }

    public static String boardLabel(Context context, String projectId) {
        return "2.4\\" CYD (resistive)";
    }
}
""",
    "dev/phonedesk/companion/ProjectStore.java": """
package dev.phonedesk.companion;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** The shelf's contents as fixtures, in the shape the shelf reads. */
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
        public final String libraries;
        public final String installerUrl;
        public final String sourceUrl;
        public final String build;
        public final int touch;

        Project(String id, String name, int status, String body, String libraries,
                String installerUrl, String sourceUrl, String build, int touch) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.body = body;
            this.libraries = libraries;
            this.installerUrl = installerUrl;
            this.sourceUrl = sourceUrl;
            this.build = build;
            this.touch = touch;
        }

        public boolean inside() {
            return id.startsWith("phone_deck") || id.startsWith("spotify_deck");
        }
    }

    private static final List<Project> ALL = new ArrayList<>();

    static {
        ALL.add(new Project("launcher-bruce", "Bruce", STATUS_PARKED,
                "A launcher that swaps firmware from a menu instead of reflashing: "
                        + "pick an app, and it hands the board over to it.",
                "", "https://example.invalid/installer", "https://example.invalid/source",
                "", FirmwareNotes.TOUCH_UNTESTED));
        ALL.add(new Project("marauder", "ESP32 Marauder", STATUS_WANT,
                "Wi-Fi and Bluetooth survey suite. Prebuilt image, so there is nothing "
                        + "to compile for it.",
                "", "https://example.invalid/installer", "https://example.invalid/source",
                "", FirmwareNotes.TOUCH_UNTESTED));
        ALL.add(new Project("launcher", "Launcher", STATUS_PARKED,
                "A menu of the images already on the board, booted without a cable.",
                "", "https://example.invalid/installer", "https://example.invalid/source",
                "", FirmwareNotes.TOUCH_UNTESTED));
        ALL.add(new Project("phone_deck_cyd24R", "Phone Deck \\u00b7 2.4\\" CYD (resistive)",
                STATUS_WORKING,
                "A desk dashboard for your phone: clock, battery and notifications, set "
                        + "like a woodblock print in two monochrome themes.",
                "Adafruit GFX Library, Adafruit ILI9341, XPT2046_Touchscreen",
                "", "", "2432S024R-v2.1", FirmwareNotes.TOUCH_WORKS));
        ALL.add(new Project("spotify_deck_cyd24R",
                "Spotify Deck \\u00b7 2.4\\" CYD (resistive)", STATUS_WORKING,
                "What you are playing on Spotify, with previous / play / next on the "
                        + "screen.",
                "Adafruit GFX Library, Adafruit ILI9341, XPT2046_Touchscreen",
                "", "", "2432S024R-v2.1", FirmwareNotes.TOUCH_WORKS));
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
