package dev.phonedesk.companion;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns posted notifications into Phone Link writes.
 *
 * Only the visible title and text are read; nothing is stored. Group summaries
 * and quiet ongoing entries are skipped because they duplicate real posts, and
 * {@link NoticeFilter} decides which apps are allowed through at all.
 */
public class DeckNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        String packageName = sbn.getPackageName();
        if (getPackageName().equals(packageName)) return;
        // The deck is readable by whoever is in the room; the allowlist is what
        // keeps one-time codes and private messages on the phone.
        if (!NoticeFilter.allows(this, packageName)) return;

        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (sbn.isOngoing() && !Notification.CATEGORY_CALL.equals(notification.category)) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        List<String> textLines = new ArrayList<>();
        if (lines != null) {
            for (CharSequence line : lines) textLines.add(string(line));
        }

        // A few apps with fully custom layouts set none of the obvious fields, so
        // the line falls back through what Android does fill in and finally to the
        // app's own name, rather than dropping the post with no explanation.
        NoticeText notice = NoticeText.from(
                label(packageName),
                string(extras.getCharSequence(Notification.EXTRA_TITLE)),
                string(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                string(extras.getCharSequence(Notification.EXTRA_TEXT)),
                string(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                string(notification.tickerText),
                textLines);
        if (notice.empty()) return;

        DeckService.forwardNotification(label(packageName), notice.title, notice.body);
    }

    /** Extras arrive as CharSequence; the chooser works in Strings. */
    private static String string(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private String label(String packageName) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}
