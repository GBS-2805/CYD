package dev.phonedesk.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * A picture of the app, not a picture of a redraw.
 *
 * Every frame here is the app's own screen: {@link TopBar} and the tab class are
 * instantiated, laid out and drawn by Android's own renderer — the platform's
 * fonts, the platform's vector drawables, the platform's text layout — so nothing
 * here can disagree with the phone about where a glyph sits, how wide a row is or
 * what a drawable looks like.
 *
 * A state the screen reaches by tapping is reached by tapping: {@code home-open}
 * is the BOARDS row clicked, {@code projects-open} is the first project's header
 * clicked. Nothing sets a fold flag behind the screen's back.
 *
 * Frames are the full height of what the screen builds, not a viewport, so a
 * scrolled-away row is in the picture and a screen that grows stays visible.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public final class CaptureTest {

    private static final int WIDTH = 393;


    private Activity host;

    @Test
    public void capturesEveryScreen() throws Exception {
        // A frozen clock. The shelf prints "3s ago" against System.currentTimeMillis(),
        // so without this the frame says a different number every run and the gate
        // flakes on pixels that are not a change.
        host = Robolectric.buildActivity(Activity.class).setup().get();
        pinAges();

        capture("home", MainActivity.HOME, drawer(), null);
        capture("home-open", MainActivity.HOME, drawer(), "BOARDS");
        capture("paired", MainActivity.SECTION_DECKS, new DecksTab(host), null);
        capture("projects", MainActivity.SECTION_PROJECTS, new ProjectsTab(host), null);
        capture("projects-open", MainActivity.SECTION_PROJECTS, new ProjectsTab(host),
                firstName());
        capture("serial", MainActivity.SECTION_SERIAL, new BoardTab(host), null);
        capture("settings", MainActivity.SECTION_SETTINGS, new SetupTab(host), null);
    }

    /**
     * Pins the ages the shelf prints.
     *
     * No clock on this JVM can be frozen: Robolectric's paused clock has no setter and
     * `System.currentTimeMillis()` is the real one, which is why the first attempt at
     * this produced a frame that said `1s ago` on one run and `2s ago` on the next. So
     * pin the data instead — every project is one hour old when the capture starts,
     * which prints `1h ago` then and `1h ago` in an hour's time, because the offset
     * moves with the clock the app reads.
     */
    private void pinAges() {
        List<ProjectStore.Project> projects = ProjectStore.all(host);
        long now = System.currentTimeMillis();
        for (ProjectStore.Project project : projects) project.updatedMs = now - 3_600_000L;
        ProjectStore.save(host, projects);
    }

    private DrawerHome drawer() {
        return new DrawerHome(host, index -> { });
    }

    /** The top of the shelf: whatever the store would put first, opened like a tap. */
    private String firstName() {
        List<ProjectStore.Project> projects = ProjectStore.all(host);
        if (projects.isEmpty()) throw new IllegalStateException("the shelf is empty");
        return projects.get(0).name;
    }

    /**
     * The bar and one screen in a column, exactly as {@code MainActivity.buildUi}
     * stacks them, optionally tapped, then drawn and written out.
     */
    private void capture(String name, int section, View screen, String tap) throws Exception {
        LinearLayout root = Ui.column(host);
        root.setBackgroundColor(Ui.BG);

        TopBar bar = new TopBar(host);
        bar.showSection(section);
        root.addView(bar.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (screen != null) {
            refresh(screen);
            root.addView(screen, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (tap != null) {
                tapText(screen, tap);
                refresh(screen);
            }
        }

        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(
                root.getMeasuredWidth(), root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));

        File dir = new File("build/capture");
        dir.mkdirs();
        File file = new File(dir, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        System.out.println("capture " + name + ": " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " -> " + file.getAbsolutePath());
    }

    /**
     * Clicks what says {@code label}: the view itself when it carries the click
     * listener, the row around it when the listener is one level up, which is how
     * {@code DrawerHome.menuRow} and the shelf's header are wired.
     */
    private static boolean tapText(View root, String label) {
        TextView found = find(root, label);
        if (found == null) throw new IllegalStateException("no view says " + label);
        found.performClick();
        View parent = (View) found.getParent();
        if (parent != null) parent.performClick();
        return true;
    }

    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView hit = find(group.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** The screens build themselves on refresh, the same call the phone makes. */
    private static void refresh(View screen) {
        if (screen instanceof DrawerHome) ((DrawerHome) screen).refresh();
        else if (screen instanceof DecksTab) ((DecksTab) screen).refresh();
        else if (screen instanceof ProjectsTab) ((ProjectsTab) screen).refresh();
        else if (screen instanceof BoardTab) ((BoardTab) screen).refresh();
        else if (screen instanceof SetupTab) ((SetupTab) screen).refresh();
    }
}
