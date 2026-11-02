package dev.phonedesk.companion;

import java.util.List;

/**
 * The line a notification contributes, chosen without any Android types.
 *
 * <p>Most apps set {@code EXTRA_TITLE} and {@code EXTRA_TEXT}. A few with fully
 * custom layouts set neither and used to be dropped here entirely, which looked
 * like a deck that ignores one app for no reason. This falls back through the
 * fields Android does fill in — a ticker, a sub-text, the first line of an inbox —
 * and finally to the app's own name, so an allowed app is never silently absent.
 * Pure, so {@code test.sh} can hold the order without a phone.
 */
final class NoticeText {
    final String title;
    final String body;

    private NoticeText(String title, String body) {
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
    }

    static NoticeText from(String label, String title, String bigText, String text,
                           String subText, String ticker, List<String> lines) {
        String first = pick(title, ticker);
        String second = pick(bigText, subText, text,
                lines == null || lines.isEmpty() ? null : lines.get(0));
        if (first.isEmpty() && second.isEmpty()) {
            first = pick(label);
        }
        return new NoticeText(first, second);
    }

    /** True only when there is nothing to say at all, not even an app name. */
    boolean empty() {
        return title.isEmpty() && body.isEmpty();
    }

    private static String pick(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }
}
