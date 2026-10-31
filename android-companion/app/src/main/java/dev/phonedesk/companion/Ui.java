package dev.phonedesk.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small view factory so every screen shares the deck's palette without a resource
 * pipeline. Keeping the UI programmatic is what lets the app build with no
 * AndroidX and lets javac type-check it.
 */
final class Ui {

    // The deck's INK palette (cyd_phone_deck.ino, THEMES[1]) slot for slot: black
    // ground, #0e0e0e panels, #333333 hairlines, white ink, and a grey ramp where
    // the coloured build had blue, violet, pink and green. The names keep their
    // old jobs — ACCENT for the link and a primary button, PINK for the one
    // destructive action and an error, VIOLET for a section tag, GOOD for success
    // — so on a monochrome screen they separate by luminance instead of hue, the
    // same way batteryColor() does on the deck.
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF0E0E0E;
    static final int CARD_HI = 0xFF1C1C1C;
    static final int EDGE = 0xFF333333;
    static final int TEXT = 0xFFFFFFFF;
    static final int DIM = 0xFF8C8C8C;
    static final int ACCENT = 0xFFFFFFFF;
    static final int VIOLET = 0xFF707070;
    static final int PINK = 0xFFD4D4D4;
    static final int GOOD = 0xFFA0A0A0;

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

    static Button action(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Typeface.MONOSPACE);
        button.setTextSize(13);
        button.setTextColor(primary ? Ui.BG : Ui.TEXT);
        button.setBackground(rounded(context, primary ? ACCENT : CARD_HI,
                primary ? ACCENT : EDGE, 10));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
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
