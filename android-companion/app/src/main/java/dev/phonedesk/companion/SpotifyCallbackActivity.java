package dev.phonedesk.companion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Catches the Spotify deck's sign-in redirect and finishes it.
 *
 * Spotify's only plain-HTTP redirect URI is a loopback literal, and no browser
 * hands {@code http://127.0.0.1} to another app, so the deck's app path uses a
 * custom scheme instead: {@code phonedesk://spotify-callback?code=...&state=...}.
 * This activity lifts the code out of that link, asks the deck to exchange it, and
 * reports what the deck said — no browser tab. That request is this app's only
 * network use: the deck's own address is the only host it ever opens.
 *
 * The code is single-use, so the request is sent at most once. Silence is not a
 * refusal: when no answer comes back the code has not been spent, and the old
 * browser hand-off is tried instead, because the browser reaches the deck from
 * wherever the phone actually is.
 */
public class SpotifyCallbackActivity extends Activity {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * The deck exchanges the code with Spotify before it answers, and that request
     * can take seconds, so this waits far longer than a local page ever would.
     */
    private static final int READ_TIMEOUT_MS = 30000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private View close;
    private Thread worker;

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
        if (worker != null) worker.interrupt();
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
     * One request, off the main thread, to the deck's own address. The answer
     * decides what happens next: {@code signed-in} finishes here, a 409 is a
     * refusal worth showing (the code is spent, so a browser replay would earn
     * {@code invalid_grant} instead of an explanation), and anything else — silence,
     * a hotspot's login page, a deck that moved — falls back to the browser, which
     * still holds a usable code.
     */
    private void deliver(String code, String deck) {
        final String url = SpotifyLink.codeUrl(deck, code);
        worker = new Thread(() -> {
            final String answer = LanDeck.fetch(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                    .answer();
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (answer.startsWith("200") && answer.contains("signed-in")) {
                    note("Signed in. The deck is storing the token and restarting…",
                            Ui.TEXT);
                    // The sign-in is also the pairing: ask the deck which board it is
                    // while it is still awake, and keep that as this phone's record of
                    // which deck the address belongs to.
                    new Thread(() -> DeckRegistry.rememberLan(getApplicationContext(), deck),
                            "deck-register").start();
                    handler.postDelayed(this::finish, 1200);
                } else if (answer.startsWith("409")) {
                    fail(readable(answer));
                } else {
                    note("The deck did not answer. Handing the sign-in to the browser…",
                            Ui.TEXT);
                    openBrowser(code, deck);
                }
            });
        }, "spotify-hand-off");
        worker.start();
    }

    // The request itself lives in LanDeck, beside the identity read: one place in the
    // app knows how to talk to a deck on the LAN.

    /** {@code "409 refused: Spotify said no"} becomes {@code "Spotify said no"}. */
    private static String readable(String answer) {
        String text = answer;
        final int space = text.indexOf(' ');
        if (space > 0 && space < 4) text = text.substring(space + 1).trim();
        if (text.startsWith("refused:")) text = text.substring("refused:".length()).trim();
        return text.isEmpty() ? "The deck refused the code without saying why." : text;
    }

    /**
     * The hand-off as it was before: the phone's browser makes the request. Slower
     * and it leaves a tab behind, which is why it is no longer the first choice —
     * but it needs no answer from the app's own socket, so a deck that is reachable
     * from the browser and not from here still finishes.
     */
    private void openBrowser(String code, String deck) {
        Uri url = Uri.parse("http://" + deck + "/code?redir=app&code=" + Uri.encode(code));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
            finish();
        } catch (ActivityNotFoundException e) {
            fail("No browser answered, so the code could not reach the deck. Open "
                    + "http://" + deck + "/spotify on the phone and sign in there.");
        }
    }
}
