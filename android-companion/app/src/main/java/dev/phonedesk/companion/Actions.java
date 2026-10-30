package dev.phonedesk.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** The small things both shelves do: say, copy, open a link, save a file. */
final class Actions {

    private Actions() {
    }

    static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /** Clipboard write; false when there is nothing to copy or no clipboard. */
    static boolean copy(Activity activity, String label, String text) {
        if (text == null || text.isEmpty()) return false;
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        return true;
    }

    static void copyOrSay(Activity activity, String label, String text) {
        if (text == null || text.isEmpty()) {
            toast(activity, "Nothing to copy");
            return;
        }
        toast(activity, copy(activity, label, text) ? "Copied " + label : "No clipboard");
    }

    static void openUrl(Activity activity, String url) {
        if (url == null || url.isEmpty()) {
            toast(activity, "No link saved");
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(activity, "No browser");
        }
    }

    static void save(Activity activity, FlashLibrary.Entry entry) {
        String saved = FlashLibrary.saveToDownloads(activity, entry);
        toast(activity, saved == null ? "SAVE needs Android 10+" : "Saved " + saved);
    }
}
