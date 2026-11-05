package dev.phonedesk.companion;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small view factory so every screen shares the app's palette without a resource
 * pipeline. Keeping the UI programmatic is what lets the app build with no
 * AndroidX and lets javac type-check it.
 *
 * The shapes are the owner's site's: <b>no radius anywhere</b> except a circle (the
 * drawer's close, the palette swatches, the theme toggle). A card is a hairline
 * rectangle with the page showing through it, a chip is a hairline rectangle with an
 * uppercase word in it, and the filled action is the band colour with the band's own
 * ink on it. That is the whole grammar; there is no elevation, no shadow and no
 * second border weight.
 */
final class Ui {

    // The values live in {@link Palette} so the host suite can measure them without
    // Android; these names are what every screen still uses, and they keep their
    // jobs — ACCENT is the one highlight (a live chip, a lit LED), PINK the ink an
    // error is written in, GOOD a healthy status, VIOLET the quietest status.
    static int BG = Palette.MOSS_SCHEME.pageDark;
    static int CARD = Palette.MOSS_SCHEME.pageDark;
    static int CARD_HI = Palette.MOSS_SCHEME.tintDark;
    /** A text field's fill: the same wash, so a box reads as a box by its hairline. */
    static int INPUT = Palette.MOSS_SCHEME.tintDark;
    static int EDGE = Palette.MOSS_SCHEME.ruleDark;
    /** A wash over the page: the site's {@code --tint}. */
    static int TINT = Palette.MOSS_SCHEME.tintDark;
    static int TEXT = Palette.MOSS_SCHEME.onDark;
    static int DIM = Palette.MOSS_SCHEME.dimDark;
    /** The one highlight, as a <b>fill</b>: a live chip, a lit LED, a marked word. */
    static int ACCENT = Palette.MOSS_SCHEME.accent;
    /** The word on that fill. */
    static int ON_ACCENT = Palette.MOSS_SCHEME.accentInk;
    static int VIOLET = Palette.MOSS_SCHEME.dimDark;
    static int PINK = Palette.MOSS_SCHEME.onDark;
    static int GOOD = Palette.MOSS_SCHEME.accent;
    static int BAND = Palette.MOSS_SCHEME.band;
    static int BAND_INK = Palette.MOSS_SCHEME.bandInk;
    static int BAND_DIM = Palette.MOSS_SCHEME.bandDim;
    static int BAND_RULE = Palette.MOSS_SCHEME.bandLine;
    static int BAND_HOVER = Palette.MOSS_SCHEME.bandHover;
    /** Text on a filled primary action: the band's own ink. */
    static int ON_FILL = Palette.MOSS_SCHEME.bandInk;

    /** The palette on show, and whether its light page is the one drawn. */
    private static Palette.Scheme scheme = Palette.MOSS_SCHEME;
    private static boolean light;
    private static Typeface regular;
    private static Typeface mediumFace;
    private static Typeface strongFace;

    /**
     * Swap every ink for the chosen palette and page. Called before the first view
     * is built, and again after a control stores its answer — which is why those
     * controls recreate the activity: a colour here is read when a view is
     * constructed, so a screen already on show keeps the palette it was built in.
     *
     * This is the <b>app's</b> palette and nothing else. A deck keeps its own row of
     * its sketch's {@code THEMES[]} in NVS and is set from its row on PAIRED, so the
     * two ends never share a value — a deck can be moss dark while the phone is green
     * light.
     */
    static void applyTheme(int palette, boolean lightPage) {
        scheme = Palette.scheme(palette);
        light = lightPage;
        final boolean l = lightPage;
        BG = l ? scheme.pageLight : scheme.pageDark;
        CARD = BG;
        CARD_HI = l ? scheme.tintLight : scheme.tintDark;
        INPUT = CARD_HI;
        EDGE = l ? scheme.ruleLight : scheme.ruleDark;
        TINT = CARD_HI;
        TEXT = l ? scheme.onLight : scheme.onDark;
        DIM = l ? scheme.dimLight : scheme.dimDark;
        ACCENT = scheme.accent;
        ON_ACCENT = scheme.accentInk;
        VIOLET = DIM;
        // An error is written in the page's loudest ink rather than a red the site
        // does not have: the lit/off LED beside it is what carries the state, so
        // colour is never the only channel.
        PINK = TEXT;
        // The accent is a fill first. Where it cannot carry a word on this page —
        // moss's lime on moss's own cream is 1.03:1 — the healthy status takes body
        // ink instead of an unreadable one.
        GOOD = Contrast.ratio(scheme.accent, BG) >= Contrast.QUIET ? scheme.accent : TEXT;
        BAND = scheme.band;
        BAND_INK = scheme.bandInk;
        BAND_DIM = scheme.bandDim;
        BAND_RULE = scheme.bandLine;
        BAND_HOVER = scheme.bandHover;
        ON_FILL = scheme.bandInk;
    }

    /** The palette on show, for the one screen that draws a swatch of each. */
    static Palette.Scheme scheme() {
        return scheme;
    }

    static boolean light() {
        return light;
    }

    private Ui() {
    }

    /**
     * Poppins, subset to what this app prints, from {@code res/font}. It is the same
     * three weights the owner's site sets its prose in, and it is what makes the
     * phone read as the site rather than as a generic Android app.
     *
     * A build with no font resource — a stripped APK, a host render with no
     * resources — falls back to the platform face instead of failing: a screen in
     * Roboto is a wrong typeface, a crash is a missing screen.
     */
    static Typeface face(Context context) {
        if (regular == null) regular = load(context, R.font.poppins_400);
        return regular;
    }

    /** 500: a row's name, a live chip, the word on a filled action. */
    static Typeface medium(Context context) {
        if (mediumFace == null) mediumFace = load(context, R.font.poppins_500);
        return mediumFace;
    }

    /** 600: the loudest word on a screen, and nothing else. */
    static Typeface strong(Context context) {
        if (strongFace == null) strongFace = load(context, R.font.poppins_600);
        return strongFace;
    }

    private static Typeface load(Context context, int id) {
        try {
            return context.getResources().getFont(id);
        } catch (Throwable missing) {
            return Typeface.DEFAULT;
        }
    }

    /** Put a view's word in the 500 face without reaching for a family name. */
    static void medium(TextView view) {
        view.setTypeface(medium(view.getContext()));
    }

    /** Put a view's word in the 600 face. */
    static void strong(TextView view) {
        view.setTypeface(strong(view.getContext()));
    }

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * A rectangle with an optional hairline. {@code radiusDp} stays in the signature
     * because the four circles — the drawer's close, three swatches and the theme
     * toggle — are the only rounded things on the site, and they are drawn by passing
     * half their width here.
     */
    static GradientDrawable rounded(Context context, int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    /** A circle: the four round controls, and the LED. */
    static GradientDrawable circle(Context context, int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTypeface(face(context));
        view.setTextColor(color);
        view.setLineSpacing(0, 1.35f);
        return view;
    }

    /**
     * A section label: 11sp, uppercase, .14em. The site's overline, which is the
     * only place a heading is allowed to be quiet.
     */
    static TextView section(Context context, String value) {
        TextView view = text(context, value.toUpperCase(java.util.Locale.US), 11, DIM);
        view.setLetterSpacing(0.14f);
        view.setPadding(0, dp(context, 22), 0, dp(context, 10));
        return view;
    }

    /**
     * A card: the page showing through, one hairline, no radius. The site's cells
     * share their hairlines, so the fill is the page itself and the border is the
     * whole of the shape.
     */
    static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setBackground(rounded(context, Color.TRANSPARENT, EDGE, 0));
        card.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16));
        return card;
    }

    /** A hairline across a column: the site's {@code border-top}. */
    static View rule(Context context) {
        View line = new View(context);
        line.setBackgroundColor(EDGE);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))));
        return line;
    }

    /**
     * An icon from `res/drawable`, square and already tinted.
     *
     * Icons are shapes, not characters: U+2699 and U+2039 were whatever the typeface
     * happened to carry, so their weight and optical size were the font's and a
     * device without the glyph had no icon at all. A drawable is the same mark on
     * every phone, and `SRC_IN` recolours it to the ink the row is drawn in instead
     * of asking for a second asset per colour.
     */
    static ImageView icon(Context context, int drawable, int sizeDp, int color) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tint(view, color);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }

    /** Recolour an icon in place, the way the bar lights the gear on its own screen. */
    static void tint(ImageView view, int color) {
        view.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    /**
     * Press feedback for anything with a tap listener.
     *
     * Every surface here is a GradientDrawable, and a GradientDrawable has no
     * pressed state: nothing on screen moved between the tap and the next screen
     * arriving, so a tap read as ignored until it worked. This keeps the view's
     * own background as the ripple's content and its shape as the mask, so the
     * ripple is clipped to the card's shape instead of washing past it.
     */
    static void pressable(Context context, View view, int radiusDp) {
        final Drawable content = view.getBackground();
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(context, radiusDp));
        mask.setColor(Color.BLACK);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(pressTone(content)), content, mask));
    }

    /**
     * The ripple's own colour, taken from the fill it sits on: dark ink on a light
     * surface, light ink on a dark one. A single fixed shade disappeared on half
     * the buttons — which is the same bug as no ripple, one colour at a time.
     */
    private static int pressTone(Drawable fill) {
        int surface = Color.TRANSPARENT;
        if (fill instanceof GradientDrawable) {
            final ColorStateList tint = ((GradientDrawable) fill).getColor();
            if (tint != null) surface = tint.getDefaultColor();
        }
        return (0x40 << 24) | (luminance(surface) > 0x80 ? 0x000000 : 0xFFFFFF);
    }

    /**
     * The window chrome is part of the palette too. Below Android 15 the system
     * paints the status and navigation bars itself, so a paper page sat under a
     * black strip; and even edge to edge it picks the bar icons' own colour, so a
     * light icon on the paper field disappeared.
     *
     * On 15 and above the two bars sit on different colours: the window is edge to
     * edge, the clock is over the band, and the navigation bar is over the page. So
     * the status icons are chosen against {@link #BAND} there and against the page
     * below, which is what stopped dark icons disappearing on moss's dark band.
     * Called once, before the first view.
     */
    static void applySystemBars(Activity activity) {
        final Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        final boolean topIsLight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                ? luminance(BAND) > 0x80 : luminance(BG) > 0x80;
        final boolean bottomIsLight = luminance(BG) > 0x80;
        int flags = window.getDecorView().getSystemUiVisibility();
        flags = topIsLight ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = bottomIsLight ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** 0..255, the arithmetic both the ripple and the bar icons decide on. */
    private static int luminance(int argb) {
        return (int) (0.299 * Color.red(argb) + 0.587 * Color.green(argb)
                + 0.114 * Color.blue(argb));
    }

    /**
     * The one filled action on a screen: the band colour, the band's ink, square,
     * 56dp tall. {@code primary == false} is the same box outlined instead of filled,
     * so a pair reads as one offer and its alternative rather than two offers.
     */
    static Button action(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(medium(context));
        button.setTextSize(14);
        button.setTextColor(primary ? ON_FILL : TEXT);
        button.setBackground(rounded(context, primary ? BAND : Color.TRANSPARENT,
                primary ? 0 : EDGE, 0));
        pressable(context, button, 0);
        // 48dp is the shortest a finger hits reliably; the site's control is 56dp.
        button.setMinHeight(dp(context, 52));
        button.setMinimumHeight(dp(context, 52));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10));
        button.setLetterSpacing(0.04f);
        return button;
    }

    /**
     * One half of a two-state switch.
     *
     * Both halves are the same box with the same border, and the live one is filled
     * with the page's own ink. That is the difference from {@link #action}: a
     * primary and a secondary button read as two offers, while two equal boxes with
     * one filled read as one control showing which of two states is on.
     */
    static Button segment(Context context, String label, boolean on) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(medium(context));
        button.setTextSize(13);
        button.setTextColor(on ? ON_FILL : TEXT);
        button.setBackground(rounded(context, on ? TEXT : Color.TRANSPARENT, on ? TEXT : EDGE, 0));
        pressable(context, button, 0);
        button.setMinHeight(dp(context, 44));
        button.setMinimumHeight(dp(context, 44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return button;
    }

    /** A chip: an uppercase word in a hairline box, or the accent fill when live. */
    static TextView chip(Context context, String label, boolean live) {
        TextView view = text(context, label.toUpperCase(java.util.Locale.US), 11,
                live ? ON_ACCENT : DIM);
        view.setLetterSpacing(0.1f);
        view.setTypeface(medium(context));
        view.setBackground(live ? rounded(context, ACCENT, ACCENT, 0)
                : rounded(context, Color.TRANSPARENT, EDGE, 0));
        view.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        return view;
    }

    static void space(Context context, View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topDp);
        view.setLayoutParams(params);
    }

    static void fullWidth(Context context, View view) {
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static String ago(long timestampMs, long nowMs) {
        if (timestampMs <= 0) return "never";
        long seconds = Math.max(0, (nowMs - timestampMs) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    static String duration(long sinceMs, long nowMs) {
        if (sinceMs <= 0) return "—";
        long seconds = Math.max(0, (nowMs - sinceMs) / 1000);
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " kB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
