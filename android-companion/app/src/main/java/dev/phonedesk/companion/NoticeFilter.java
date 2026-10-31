package dev.phonedesk.companion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which apps may put something on the deck.
 *
 * The listener is handed every notification the phone posts, and the deck sits on
 * a desk where other people can read it: a bank's one-time code or a private
 * message should not travel over BLE. Nothing is filtered until the user chooses,
 * so a first run behaves exactly as the unfiltered build did; the moment they pick
 * apps, only those get through and everything else stays on the phone.
 *
 * Stored as one boolean per package instead of a JSON set, so a half-finished edit
 * cannot leave the list half-written.
 */
final class NoticeFilter {

    private static final String PREFS = "notices";
    /** Set once the user has chosen; until then {@link #allows} says yes to all. */
    private static final String KEY_CHOSEN = "chosen";
    private static final String ALLOWED = "allow:";

    /** One row of the picker: a package and the name its launcher shows. */
    static final class App {
        final String packageName;
        final String label;

        App(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private NoticeFilter() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once the user has picked apps, which is what switches the filter on. */
    static boolean customised(Context context) {
        return prefs(context).getBoolean(KEY_CHOSEN, false);
    }

    static boolean allows(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (!customised(context)) return true;
        return prefs(context).getBoolean(ALLOWED + packageName, false);
    }

    static Set<String> allowed(Context context) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(ALLOWED) && Boolean.TRUE.equals(entry.getValue())) {
                out.add(entry.getKey().substring(ALLOWED.length()));
            }
        }
        return out;
    }

    /** Replaces the allowed set. An empty set is a valid answer: nothing gets through. */
    static void allow(Context context, Set<String> packages) {
        SharedPreferences all = prefs(context);
        SharedPreferences.Editor edit = all.edit();
        for (String key : all.getAll().keySet()) {
            if (key.startsWith(ALLOWED)) edit.remove(key);
        }
        for (String name : packages) {
            if (name != null && !name.isEmpty()) edit.putBoolean(ALLOWED + name, true);
        }
        edit.putBoolean(KEY_CHOSEN, true).apply();
    }

    /** Back to the unfiltered default, for someone who wants every app again. */
    static void allowAll(Context context) {
        SharedPreferences all = prefs(context);
        SharedPreferences.Editor edit = all.edit();
        for (String key : all.getAll().keySet()) {
            if (key.startsWith(ALLOWED)) edit.remove(key);
        }
        edit.putBoolean(KEY_CHOSEN, false).apply();
    }

    /**
     * Apps with a launcher entry, which is what "an app" means to the person
     * reading the list. Android 11 only reveals packages the manifest asks for,
     * which is why it declares the LAUNCHER intent in {@code <queries>}.
     */
    static List<App> launchable(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String self = context.getPackageName();

        for (ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null) continue;
            String name = info.activityInfo.packageName;
            if (name == null || name.equals(self) || !seen.add(name)) continue;
            CharSequence label = info.loadLabel(manager);
            String text = label == null ? name : label.toString().trim();
            apps.add(new App(name, text.isEmpty() ? name : text));
        }

        Collections.sort(apps, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return apps;
    }
}
