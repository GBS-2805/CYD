package dev.phonedesk.companion;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * Mirrors whatever the phone is playing down the link, so the deck can show a song
 * without any of it talking to Spotify: no client id, no browser, no token, and
 * nothing stored.
 *
 * The source is the phone's own media session, which is readable because this app
 * already holds notification access for {@link DeckNotificationListener} — the
 * same grant, not a new one. Spotify is preferred when it is in the list, because
 * that is what this is for; otherwise the first playing session wins, so a podcast,
 * a video or a browser tab shows up too.
 *
 * One line a second while something plays: the deck moves its progress bar on its
 * own between writes, so this is a heartbeat, not a stream.
 */
final class NowPlaying {

    /** The package this exists for. Not a requirement: any session will do. */
    static final String SPOTIFY = "com.spotify.music";

    private static final long POLL_MS = 1000L;
    /** The line for "nothing is playing": sent once, and it clears the deck. */
    private static final String NOTHING = "stop|0|0|||";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private boolean running;
    private String last = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                sample();
            } catch (RuntimeException e) {
                // A session can go away between the list and the read; skip a tick
                // rather than stop, because the next one may find a new one.
            }
            if (running) handler.postDelayed(this, POLL_MS);
        }
    };

    NowPlaying(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Idempotent: the service calls this whenever a deck that has the characteristic links. */
    void start() {
        if (running) return;
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessions == null) return;
        running = true;
        last = "";
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    private void sample() {
        final ComponentName listener = new ComponentName(context, DeckNotificationListener.class);
        final List<MediaController> active = sessions.getActiveSessions(listener);

        MediaController controller = null;
        for (MediaController candidate : active) {
            if (SPOTIFY.equals(candidate.getPackageName())) {
                controller = candidate;
                break;
            }
        }
        if (controller == null) {
            for (MediaController candidate : active) {
                if (isPlaying(candidate)) {
                    controller = candidate;
                    break;
                }
            }
        }
        if (controller == null) {
            publish(NOTHING);
            return;
        }

        final PlaybackState state = controller.getPlaybackState();
        final MediaMetadata meta = controller.getMetadata();
        final String word = word(state);
        final long duration = meta == null
                ? 0L : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        final String title = meta == null ? "" : string(meta, MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta == null ? "" : string(meta, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty() && meta != null) {
            artist = string(meta, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        final String album = meta == null ? "" : string(meta, MediaMetadata.METADATA_KEY_ALBUM);

        publish(NowPacket.encode(word, position(state), duration, title, artist, album));
    }

    /**
     * Where the playhead is now. A paused session reports the position it stopped
     * at, so the elapsed wall time is only added while it is actually running.
     */
    private static long position(PlaybackState state) {
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            final long since = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            if (since > 0) position += (long) (since * state.getPlaybackSpeed());
        }
        return position;
    }

    private static boolean isPlaying(MediaController controller) {
        final PlaybackState state = controller.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private static String word(PlaybackState state) {
        if (state == null) return "stop";
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                return "play";
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return "pause";
            default:
                return "stop";
        }
    }

    private static String string(MediaMetadata meta, String key) {
        final CharSequence value = meta.getText(key);
        return value == null ? "" : value.toString();
    }

    private void publish(String packet) {
        if (packet.equals(last)) return;
        last = packet;
        DeckService.publishNowPlaying(packet);
    }
}
