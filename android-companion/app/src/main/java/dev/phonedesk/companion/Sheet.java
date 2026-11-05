package dev.phonedesk.companion;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * One sheet: a scrim over the screen and a panel that comes up from the bottom,
 * with an overline for a title and a round way out.
 *
 * The shape is the site's drawer, turned through ninety degrees: the same scrim at
 * 36 % black, the same 11sp overline at .14em, the same 40dp circle carrying an X,
 * and a hairline between the head and the content. There is no password to a sheet
 * here — no drag handle to discover, no swipe to guess — because the panel is a
 * list with a close on it.
 *
 * It is a view rather than a dialog so the render host can lay it out and draw it
 * the way the phone does; a DialogFragment would be a second window that no capture
 * of this one could include.
 */
final class Sheet extends FrameLayout {

    private final LinearLayout panel;
    private final LinearLayout body;
    private final TextView label;
    /** A sheet holds one content view at a time; the app reuses its three. */
    private View content;
    private boolean open;

    Sheet(Activity activity) {
        super(activity);
        setVisibility(GONE);

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x5C000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = Ui.column(activity);
        panel.setBackgroundColor(Ui.BG);
        panel.setClickable(true);

        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 18), Ui.dp(activity, 12),
                Ui.dp(activity, 12));
        label = Ui.text(activity, "MORE", 11, Ui.DIM);
        label.setLetterSpacing(0.14f);
        label.setTypeface(Ui.medium(activity));
        head.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(activity, "\u00d7", 20, Ui.TEXT);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.circle(activity, 0, Ui.EDGE));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(activity, 40),
                Ui.dp(activity, 40)));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(Ui.rule(activity));

        ScrollView scroll = new ScrollView(activity);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20),
                Ui.dp(activity, 28));
        scroll.addView(body, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams panelParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        addView(panel, panelParams);
    }

    /**
     * A sheet rises from the foot of the screen, so its last row is under the
     * gesture handle or a navigation bar unless the panel is told otherwise; its
     * sides are the window's edges too when a cutout sits on the long edge. The
     * scrim still covers the whole screen and the panel's fill still reaches its
     * own edge — only the content moves in.
     */
    void inset(int left, int right, int bottom) {
        panel.setPadding(left, 0, right, bottom);
    }

    /** Show {@code view} under {@code title}; the panel is closed when null. */
    void open(String title, View view) {
        label.setText(title.toUpperCase(Locale.US));
        if (content != null && content.getParent() == body) body.removeView(content);
        content = view;
        body.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        open = true;
        setVisibility(VISIBLE);
        // Not attached means a render: draw it where it will end up. An attached
        // panel rides up from below, which is the only animation a sheet needs.
        if (isAttachedToWindow()) {
            panel.setTranslationY(panel.getHeight());
            panel.animate().translationY(0f).setDuration(180).start();
        } else {
            panel.setTranslationY(0f);
        }
    }

    void close() {
        open = false;
        panel.setTranslationY(0f);
        setVisibility(GONE);
    }

    boolean isOpen() {
        return open;
    }

    /** The view on show, so the activity can refresh whichever sheet is up. */
    View content() {
        return content;
    }
}
