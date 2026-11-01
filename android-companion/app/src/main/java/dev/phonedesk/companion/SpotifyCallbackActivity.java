package dev.phonedesk.companion;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Catches the Spotify deck's sign-in redirect.
 *
 * Spotify's only plain-HTTP redirect URI is a loopback literal, and no browser
 * hands {@code http://127.0.0.1} to another app, so the deck's app path uses a
 * custom scheme instead: {@code phonedesk://spotify-callback?code=...&state=...}.
 * This activity lifts the code out of that link and gives it back to the deck
 * through the phone's browser, at {@code http://<deck>/code}. It opens no socket
 * of its own, which is why the APK keeps its no-INTERNET profile.
 */
public class SpotifyCallbackActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private View close;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ui ---

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 30), Ui.dp(this, 18), Ui.dp(this, 18));

        root.addView(Ui.text(this, "SPOTIFY DECK", 20, Ui.TEXT));

        TextView subtitle = Ui.text(this, "sign-in return", 12, Ui.DIM);
        subtitle.setLetterSpacing(0.12f);
        root.addView(subtitle);

        LinearLayout card = Ui.card(this);
        status = Ui.text(this, "Reading the link Spotify sent back…", 14, Ui.TEXT);
        card.addView(status);
        Ui.space(this, card, Ui.dp(this, 18));
        root.addView(card);

        close = Ui.action(this, "CLOSE", false);
        close.setOnClickListener(v -> finish());
        Ui.space(this, close, Ui.dp(this, 18));
        root.addView(close);
        return root;
    }

    private void note(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private void fail(String text) {
        note(text, Ui.PINK);
        if (close != null) close.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------- hand-off ---

    private void handle(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null
                || !SpotifyLink.SCHEME.equals(data.getScheme())
                || !SpotifyLink.HOST.equals(data.getHost())) {
            fail("That link is not a Spotify Deck sign-in. Start again from the deck's "
                    + "setup page.");
            return;
        }

        // The host test above covers the intent filter; this covers an explicit
        // intent from another app, which reaches an exported activity without one.
        String link = data.toString();
        String code = SpotifyLink.code(link);
        String deck = SpotifyLink.deck(link);

        if (code.length() < 8) {
            String refused = SpotifyLink.query(link, "error");
            fail(refused.isEmpty()
                    ? "Spotify sent no code back. Start the sign-in again from the deck's "
                            + "page and approve the request."
                    : "Spotify refused the request (" + refused + "). Start again from the "
                            + "deck's page.");
            return;
        }
        if (deck.isEmpty()) {
            fail("This link carries no deck address, so there is nowhere to send the code. "
                    + "Start again from the deck's setup page.");
            return;
        }

        note("Handing the sign-in to the deck at " + deck + "…", Ui.TEXT);
        handler.postDelayed(() -> deliver(code, deck), 600);
    }

    /**
     * The browser makes the request, not this app: the code travels to the deck
     * over the LAN the same way a pasted one does, and the APK never needs a
     * network permission.
     */
    private void deliver(String code, String deck) {
        Uri url = Uri.parse("http://" + deck + "/code?redir=app&code=" + Uri.encode(code));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception e) {
            fail("No browser answered, so the code could not reach the deck. Open "
                    + "http://" + deck + "/spotify on the phone and sign in there.");
            return;
        }
        finish();
    }
}
