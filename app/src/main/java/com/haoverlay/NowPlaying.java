package com.haoverlay;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is playing on this device right now. Two sources:
 * <ul>
 * <li>Android's media sessions (the same data the system media controls show): any player, but it
 * needs notification access; see {@link MediaWatcher}.</li>
 * <li>Spotify's own "Device Broadcast Status" announcements: Spotify only, no permission needed,
 * but the user has to switch that option on in Spotify's settings.</li>
 * </ul>
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
        boolean fromSpotifyApi;
        /** The Spotify device playing it (Web API only). */
        String device = "";

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

    /** Latest song announced by Spotify's broadcasts, or {@code null}. */
    private Track broadcastTrack;
    private boolean receiverRegistered;
    private final BroadcastReceiver spotifyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onSpotifyBroadcast(intent);
        }
    };

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
        listenForBroadcasts();
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
        if (receiverRegistered) {
            try {
                app.unregisterReceiver(spotifyReceiver);
            } catch (RuntimeException ignored) {
            }
            receiverRegistered = false;
        }
        if (msm != null && started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
        started = false;
        clearControllers();
    }

    private void listenForBroadcasts() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(SPOTIFY + ".metadatachanged");
        f.addAction(SPOTIFY + ".playbackstatechanged");
        try {
            ContextCompat.registerReceiver(app, spotifyReceiver, f, ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't listen for Spotify broadcasts", e);
        }
    }

    /**
     * Spotify sends "metadatachanged" (track, artist, album, length) when the song changes and
     * "playbackstatechanged" (playing, playbackPosition) on play, pause and seek; both carry timeSent.
     */
    private void onSpotifyBroadcast(Intent intent) {
        Bundle x = intent.getExtras();
        if (x == null) return;
        Track old = broadcastTrack;
        Track t = new Track();
        if (old != null) {
            t.title = old.title;
            t.artist = old.artist;
            t.album = old.album;
            t.durationMs = old.durationMs;
            t.positionMs = old.positionMs;
            t.atRealtime = old.atRealtime;
            t.playing = old.playing;
            t.positionKnown = old.positionKnown;
        }
        if (x.containsKey("track")) {
            String title = text(x, "track");
            String artist = text(x, "artist");
            boolean changed = !title.equals(t.title) || !artist.equals(t.artist);
            t.title = title;
            t.artist = artist;
            t.album = text(x, "album");
            t.durationMs = number(x, "length", 0);
            if (changed && !x.containsKey("playbackPosition")) {
                // A new song starts from the top; the position broadcast usually follows straight after.
                t.positionMs = 0;
                t.atRealtime = SystemClock.elapsedRealtime();
                t.positionKnown = true;
            }
        }
        if (x.containsKey("playing")) t.playing = x.getBoolean("playing", t.playing);
        if (x.containsKey("playbackPosition")) {
            long sent = number(x, "timeSent", 0);
            long age = sent > 0 ? System.currentTimeMillis() - sent : 0;
            if (age < 0 || age > 10000) age = 0;
            t.positionMs = number(x, "playbackPosition", 0);
            t.atRealtime = SystemClock.elapsedRealtime() - age;
            t.positionKnown = true;
        }
        broadcastTrack = t;
        onChange.run();
    }

    private static String text(Bundle x, String key) {
        Object v = x.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static long number(Bundle x, String key, long fallback) {
        Object v = x.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    /** True once Spotify has announced a song, i.e. its "Device Broadcast Status" option is on. */
    boolean hasBroadcasts() {
        return broadcastTrack != null;
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
        if (best == null && broadcastTrack != null && !broadcastTrack.title.isEmpty()) return broadcastTrack;
        return best;
    }

    private static String str(MediaMetadata md, String key) {
        CharSequence s = md.getText(key);
        return s == null ? "" : s.toString().trim();
    }
}
