package dev.phonedesk.companion;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

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

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null) body = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && body == null) return;

        DeckService.forwardNotification(
                label(packageName),
                title == null ? "" : title.toString(),
                body == null ? "" : body.toString());
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
