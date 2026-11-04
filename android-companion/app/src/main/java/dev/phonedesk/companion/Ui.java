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
 * Small view factory so every screen shares the deck's palette without a resource
 * pipeline. Keeping the UI programmatic is what lets the app build with no
 * AndroidX and lets javac type-check it.
 */
final class Ui {

    // The values live in {@link Palette} so the host suite can measure them without
    // Android; these names are what every screen still uses, and they keep their
    // jobs — ACCENT is the fill a primary action carries, PINK the one error ink,
    // GOOD success, VIOLET the quietest status.
    static int BG = Palette.DARK_FIELD;
    static int CARD = Palette.DARK_FIELD;
    static int CARD_HI = Palette.DARK_WASH;
    /** A text field's fill: one step above CARD_HI, so the box reads as a box. */
    static int INPUT = Palette.DARK_INPUT;
    static int EDGE = Palette.DARK_EDGE;
    static int TEXT = Palette.DARK_TEXT;
    static int DIM = Palette.DARK_DIM;
    static int ACCENT = Palette.DARK_PRIMARY;
    static int VIOLET = Palette.DARK_QUIET;
    static int PINK = Palette.DARK_ALOUD;
    static int GOOD = Palette.DARK_OK;
    static int BAND = Palette.BAND;
    static int BAND_INK = Palette.INK;
    static int BAND_DIM = Palette.BAND_DIM;
    static int BAND_RULE = Palette.BAND_RULE;
    /** Text on a filled primary action: the page's own ink, so it is always the
     * opposite of the fill — black on the off-white button, off-white on the black. */
    static int ON_FILL = Palette.DARK_FIELD;

    /** The app's two palettes, by the name its switch uses — the same two words a
     * deck's row uses, so one vocabulary covers both ends. */
    static final int INK = 0;
    static final int PAPER = 1;

    /**
     * Swap every ink for the chosen palette. Called before the first view is built,
     * and again after the switch stores its answer — which is why the switch
     * recreates the activity: a colour here is read when a view is constructed, so a
     * screen already on show keeps the palette it was built in.
     *
     * This is the <b>app's</b> palette and nothing else. A deck keeps its own in
     * NVS and is set from its row on PAIRED, so the two ends never share a value.
     */
    static void applyTheme(int palette) {
        final boolean light = palette == PAPER;
        BG = light ? Palette.LIGHT_FIELD : Palette.DARK_FIELD;
        CARD = BG;
        CARD_HI = light ? Palette.LIGHT_WASH : Palette.DARK_WASH;
        INPUT = light ? Palette.LIGHT_INPUT : Palette.DARK_INPUT;
        EDGE = light ? Palette.LIGHT_EDGE : Palette.DARK_EDGE;
        TEXT = light ? Palette.LIGHT_TEXT : Palette.DARK_TEXT;
        DIM = light ? Palette.LIGHT_DIM : Palette.DARK_DIM;
        ACCENT = light ? Palette.LIGHT_PRIMARY : Palette.DARK_PRIMARY;
        VIOLET = light ? Palette.LIGHT_QUIET : Palette.DARK_QUIET;
        PINK = light ? Palette.LIGHT_ALOUD : Palette.DARK_ALOUD;
        GOOD = light ? Palette.LIGHT_OK : Palette.DARK_OK;
        BAND = Palette.BAND;
        BAND_INK = Palette.INK;
        BAND_DIM = Palette.BAND_DIM;
        BAND_RULE = Palette.BAND_RULE;
        ON_FILL = light ? Palette.LIGHT_FIELD : Palette.DARK_FIELD;
    }

    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(Context context, int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
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
        // Monospace throughout: the deck is a terminal, so the phone reads as the
        // same instrument rather than a generic Material app.
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(color);
        return view;
    }

    static TextView section(Context context, String value) {
        TextView view = text(context, value, 12, DIM);
        view.setLetterSpacing(0.14f);
        view.setPadding(0, dp(context, 18), 0, dp(context, 8));
        return view;
    }

    /** Rounded card with the standard fill, hairline edge and inner padding. */
    static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setBackground(rounded(context, CARD, EDGE, 14));
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        return card;
    }

    /**
     * An icon from `res/drawable`, square and already tinted.
     *
     * Icons are shapes now, not characters: U+2699 and U+2039 were whatever the
     * monospace face happened to carry, so their weight and optical size were the
     * font's and a device without the glyph had no icon at all. A drawable is the
     * same mark on every phone, and `SRC_IN` recolours it to the ink the row is
     * drawn in instead of asking for a second asset per colour.
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
     * ripple is clipped to the card's radius instead of washing a square over it.
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
     * light icon on the paper field disappeared. Both bars take the field, and the
     * icons turn over with its luminance. Called once, before the first view.
     */
    static void applySystemBars(Activity activity) {
        final Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        final boolean paper = luminance(BG) > 0x80;
        int flags = window.getDecorView().getSystemUiVisibility();
        flags = paper ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = paper ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** 0..255, the arithmetic both the ripple and the bar icons decide on. */
    private static int luminance(int argb) {
        return (int) (0.299 * Color.red(argb) + 0.587 * Color.green(argb)
                + 0.114 * Color.blue(argb));
    }

    static Button action(Context context, String label, boolean primary) {        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(13);
        button.setTextColor(primary ? ON_FILL : TEXT);
        button.setBackground(rounded(context, primary ? ACCENT : CARD_HI,
                primary ? ACCENT : EDGE, 10));
        pressable(context, button, 10);
        // 48dp is the shortest a finger hits reliably. Thirteen-sp monospace text
        // with 8dp of padding above and below came to about 33dp, which is short
        // enough that a tap aimed at the button lands on the card behind it.
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
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
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(13);
        button.setTextColor(on ? ON_FILL : TEXT);
        button.setBackground(rounded(context, on ? TEXT : Color.TRANSPARENT, on ? TEXT : EDGE, 8));
        pressable(context, button, 8);
        button.setMinHeight(dp(context, 40));
        button.setMinimumHeight(dp(context, 40));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return button;
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
