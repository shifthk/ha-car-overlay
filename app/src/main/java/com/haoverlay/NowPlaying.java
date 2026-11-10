package com.haoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is playing on this device right now, read from Android's media sessions (the same data the
 * system media controls show). Prefers Spotify, then whatever else is playing.
 * Needs notification access; see {@link MediaWatcher}.
 */
final class NowPlaying {

    /** A song and where playback is in it. */
    static final class Track {
        String title = "";
        String artist = "";
        String album = "";
        long durationMs;
        /** Playback position at the moment {@link #atRealtime} (SystemClock.elapsedRealtime). */
        long positionMs;
        long atRealtime;
        float speed = 1f;
        boolean playing;
        boolean positionKnown;
        boolean fromHomeAssistant;

        String key() {
            return (title + "|" + artist).toLowerCase(Locale.ROOT);
        }
    }

    private static final String TAG = "NowPlaying";
    private static final String SPOTIFY = "com.spotify.music";

    private final Context app;
    private final Handler main;
    private final Runnable onChange;
    private final ComponentName component;
    private MediaSessionManager msm;
    private boolean started;

    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessions;

    NowPlaying(Context context, Handler main, Runnable onChange) {
        this.app = context.getApplicationContext();
        this.main = main;
        this.onChange = onChange;
        this.component = new ComponentName(app, MediaWatcher.class);
    }

    static boolean hasAccess(Context c) {
        return NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.getPackageName());
    }

    /** Safe to call repeatedly; returns false until notification access has been granted. */
    boolean start() {
        if (started) return true;
        if (!hasAccess(app)) return false;
        try {
            msm = app.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main);
            started = true;
            onSessions(msm.getActiveSessions(component));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Media sessions unavailable", e);
            started = false;
            return false;
        }
    }

    void stop() {
        if (msm != null && started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
        started = false;
        clearControllers();
    }

    private void clearControllers() {
        for (int i = 0; i < controllers.size(); i++) {
            try {
                controllers.get(i).unregisterCallback(callbacks.get(i));
            } catch (RuntimeException ignored) {
            }
        }
        controllers.clear();
        callbacks.clear();
    }

    private void onSessions(List<MediaController> list) {
        clearControllers();
        if (list != null) {
            for (MediaController c : list) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        onChange.run();
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        onChange.run();
                    }

                    @Override
                    public void onSessionDestroyed() {
                        onChange.run();
                    }
                };
                try {
                    c.registerCallback(cb, main);
                    controllers.add(c);
                    callbacks.add(cb);
                } catch (RuntimeException ignored) {
                }
            }
        }
        onChange.run();
    }

    /** The best current track, or {@code null} when nothing with a title is playing or paused. */
    Track current() {
        Track best = null;
        int bestScore = -1;
        for (MediaController c : controllers) {
            try {
                MediaMetadata md = c.getMetadata();
                if (md == null) continue;
                String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
                if (title.isEmpty()) continue;
                PlaybackState ps = c.getPlaybackState();
                boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                int score = (playing ? 2 : 0) + (SPOTIFY.equals(c.getPackageName()) ? 1 : 0);
                if (score <= bestScore) continue;

                Track t = new Track();
                t.title = title;
                t.artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
                if (t.artist.isEmpty()) t.artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                t.album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
                t.durationMs = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                t.playing = playing;
                if (ps != null && ps.getPosition() >= 0) {
                    t.positionKnown = true;
                    t.positionMs = ps.getPosition();
                    t.atRealtime = ps.getLastPositionUpdateTime();
                    t.speed = ps.getPlaybackSpeed() > 0 ? ps.getPlaybackSpeed() : 1f;
                }
                best = t;
                bestScore = score;
            } catch (RuntimeException ignored) {
                // The session went away while we were reading it.
            }
        }
        return best;
    }

    private static String str(MediaMetadata md, String key) {
        CharSequence s = md.getText(key);
        return s == null ? "" : s.toString().trim();
    }
}
