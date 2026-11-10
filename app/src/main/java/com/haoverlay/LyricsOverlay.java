package com.haoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The floating lyrics button and its popup. Tap the button to show time-synced lyrics for whatever is
 * playing; drag the popup by its title. The song comes from this device's media player (Spotify first),
 * or from a Home Assistant media player when the device can't be read.
 */
final class LyricsOverlay {

    interface StatesSource {
        Map<String, EntityState> states();
    }

    private static final String TAG = "LyricsOverlay";
    private static final String FAB_ID = "__lyrics__";
    private static final String PANEL_ID = "__lyrics_panel__";
    private static final long OFFSET_STEP_MS = 250;
    private static final long RETRY_MS = 15000;
    private static final int CACHE_SIZE = 40;

    private final Context ui;
    private final WindowManager wm;
    private final Store store;
    private final StatesSource source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NowPlaying nowPlaying;
    private final LrcLib lrc = new LrcLib();
    private final ExecutorService spotifyWorker = Executors.newSingleThreadExecutor();

    /** Latest answer from Spotify's Web API ({@code null}: nothing playing on the account, or not connected). */
    private NowPlaying.Track spotifyTrack;
    private String spotifyError = "";
    private boolean spotifyBusy;
    private long spotifyNextAt;

    private FloatingCardButton fab;
    private WindowManager.LayoutParams fabLp;
    private int stackIndex;

    private FrameLayout panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelAdded;
    private TextView titleView;
    private TextView subtitleView;
    private LyricsView lyricsView;
    private MaterialButton accessButton;

    private int screenW, screenH;

    private final Map<String, LrcLib.Result> cache = new LinkedHashMap<String, LrcLib.Result>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LrcLib.Result> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private String shownKey = "";
    private String loadingKey = "";
    private String failedKey = "";
    private long failedAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (panelAdded) main.postDelayed(this, 1000);
        }
    };

    LyricsOverlay(Context ui, WindowManager wm, Store store, StatesSource source) {
        this.ui = ui;
        this.wm = wm;
        this.store = store;
        this.source = source;
        this.nowPlaying = new NowPlaying(ui, main, this::update);
    }

    boolean isShown() {
        return fab != null;
    }

    // ------------------------------------------------------------------ lifecycle (driven by OverlayService)

    /** Shows or hides the button to match the setting; {@code index} is its default slot down the right edge. */
    void sync(int screenWidth, int screenHeight, int index) {
        screenW = screenWidth;
        screenH = screenHeight;
        stackIndex = index;
        if (!store.lyricsEnabled()) {
            hide();
            return;
        }
        int size = dp(store.fabSizeDp());
        if (fab == null) {
            addFab(size);
        } else if (fabLp.width != size) {
            fabLp.width = size;
            fabLp.height = size;
            clampFab();
        }
        nowPlaying.start();
        if (panelAdded) reopenPanel();
    }

    void onScreenChanged(int screenWidth, int screenHeight) {
        screenW = screenWidth;
        screenH = screenHeight;
        if (fab == null) return;
        clampFab();
        styleFab();
        if (panelAdded) reopenPanel();   // also picks up a light/dark switch
    }

    void resetPosition() {
        if (fab == null) return;
        placeFab(true);
        if (panelAdded) reopenPanel();
    }

    void destroy() {
        hide();
        lrc.shutdown();
        spotifyWorker.shutdownNow();
    }

    private void hide() {
        closePanel();
        if (fab != null) removeWindow(fab);
        fab = null;
        fabLp = null;
        nowPlaying.stop();
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ floating button

    private void addFab(int size) {
        fab = new FloatingCardButton(ui);
        fab.setContentDescription("Lyrics");
        fab.setGlyph(MdiIcons.glyphOf(ui, "mdi:microphone-variant", "mdi:music-note"));
        fabLp = overlayParams(size, size);
        placeFab(false);
        styleFab();
        final WindowManager.LayoutParams lp = fabLp;
        fab.setOnTouchListener(new DragTouch(ui, lp, new DragTouch.Listener() {
            @Override
            public void onTap() {
                if (panelAdded) closePanel();
                else openPanel();
            }

            @Override
            public void onLongPress() {
            }

            @Override
            public void onMove(int x, int y) {
                lp.x = clamp(x, 0, screenW - lp.width);
                lp.y = clamp(y, 0, screenH - lp.height);
                updateWindow(fab, lp);
            }

            @Override
            public void onDragEnd() {
                store.setFabPosition(FAB_ID, lp.x, lp.y);
            }
        }));
        try {
            wm.addView(fab, fabLp);
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't show the lyrics button", e);
            fab = null;
            fabLp = null;
        }
    }

    private void placeFab(boolean forceDefault) {
        int[] saved = forceDefault ? null : store.fabPosition(FAB_ID);
        if (saved != null) {
            fabLp.x = saved[0];
            fabLp.y = saved[1];
        } else {
            fabLp.x = screenW - fabLp.width - dp(24);
            fabLp.y = dp(120) + stackIndex * (fabLp.height + dp(16));
        }
        clampFab();
    }

    private void clampFab() {
        fabLp.x = clamp(fabLp.x, 0, screenW - fabLp.width);
        fabLp.y = clamp(fabLp.y, 0, screenH - fabLp.height);
        updateWindow(fab, fabLp);
    }

    private void styleFab() {
        if (fab == null) return;
        int bg, fg;
        if (panelAdded) {
            bg = color(com.google.android.material.R.attr.colorPrimary, 0xFF84CFFF);
            fg = color(com.google.android.material.R.attr.colorOnPrimary, 0xFF00344C);
        } else {
            bg = color(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF252B2E);
            fg = color(com.google.android.material.R.attr.colorOnSurface, 0xFFDFE3E7);
        }
        fab.setColors(bg, fg, color(com.google.android.material.R.attr.colorOutlineVariant, 0xFF41484D));
    }

    // ------------------------------------------------------------------ popup

    private void openPanel() {
        if (fab == null) return;
        int width = Math.min(dp(Math.max(360, store.panelWidthDp())), screenW - dp(16));
        panelLp = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT);
        panel = buildPanel(panelLp);
        positionPanel();
        try {
            wm.addView(panel, panelLp);
            panelAdded = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't show the lyrics popup", e);
            toast("Couldn't show lyrics: " + e.getMessage());
            panel = null;
            return;
        }
        panel.post(this::positionPanel);
        styleFab();
        shownKey = "";
        spotifyNextAt = 0;
        nowPlaying.start();
        main.removeCallbacks(tick);
        tick.run();
    }

    private void closePanel() {
        if (panel != null && panelAdded) removeWindow(panel);
        panelAdded = false;
        panel = null;
        lyricsView = null;
        main.removeCallbacks(tick);
        styleFab();
    }

    private void reopenPanel() {
        closePanel();
        openPanel();
    }

    /** Where it was last dragged to, otherwise beside the button. */
    private void positionPanel() {
        if (panel == null || panelLp == null) return;
        int w = panelLp.width;
        int h = panel.getHeight() > 0 ? panel.getHeight() : dp(420);
        int[] saved = store.fabPosition(PANEL_ID);
        if (saved != null) {
            panelLp.x = saved[0];
            panelLp.y = saved[1];
        } else if (fabLp != null) {
            boolean rightHalf = fabLp.x + fabLp.width / 2 > screenW / 2;
            panelLp.x = rightHalf ? fabLp.x - w - dp(4) : fabLp.x + fabLp.width + dp(4);
            panelLp.y = fabLp.y + fabLp.height / 2 - h / 2;
        }
        panelLp.x = clamp(panelLp.x, 0, screenW - w);
        panelLp.y = clamp(panelLp.y, 0, screenH - h);
        if (panelAdded) updateWindow(panel, panelLp);
    }

    private FrameLayout buildPanel(final WindowManager.LayoutParams lp) {
        FrameLayout wrapper = new FrameLayout(ui);
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(12));

        MaterialCardView root = new MaterialCardView(ui);
        root.setRadius(dp(28));
        root.setCardElevation(dp(6));
        root.setStrokeWidth(0);
        root.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF252B2E));
        wrapper.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(ui);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(8), dp(8), dp(12));
        root.addView(col);

        // ---- header: song (drag handle), sync nudges, close ----
        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(ui);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), dp(8), dp(4), dp(8));
        titleView = text(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                com.google.android.material.R.attr.colorOnSurface);
        subtitleView = text(com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        titles.addView(titleView);
        titles.addView(subtitleView);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.setOnTouchListener(new DragTouch(ui, lp, new DragTouch.Listener() {
            @Override
            public void onTap() {
            }

            @Override
            public void onLongPress() {
            }

            @Override
            public void onMove(int x, int y) {
                if (panel == null || lp != panelLp) return;
                lp.x = clamp(x, 0, screenW - lp.width);
                lp.y = clamp(y, 0, screenH - panel.getHeight());
                updateWindow(panel, lp);
            }

            @Override
            public void onDragEnd() {
                store.setFabPosition(PANEL_ID, lp.x, lp.y);
            }
        }));

        header.addView(iconButton("minus", "Lyrics later", () -> nudge(-OFFSET_STEP_MS)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(iconButton("plus", "Lyrics earlier", () -> nudge(OFFSET_STEP_MS)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(iconButton("close", "Close", this::closePanel), new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(header);

        // ---- lyrics ----
        lyricsView = new LyricsView(ui,
                color(com.google.android.material.R.attr.colorOnSurface, 0xFFDFE3E7),
                MaterialColors.compositeARGBWithAlpha(color(com.google.android.material.R.attr.colorOnSurface, 0xFFDFE3E7), 95));
        lyricsView.setTextSizeSp(store.lyricsTextSp());
        lyricsView.setOffsetMs(store.lyricsOffsetMs());
        int height = Math.min(dp(360), (int) (screenH * 0.6f));
        col.addView(lyricsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        accessButton = new MaterialButton(ui);
        accessButton.setText("Try notification access instead");
        accessButton.setMinHeight(dp(56));
        accessButton.setVisibility(View.GONE);
        accessButton.setOnClickListener(v -> openAccessSettings());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(dp(12), dp(4), dp(12), 0);
        col.addView(accessButton, alp);
        return wrapper;
    }

    private void nudge(long deltaMs) {
        long v = Math.max(-5000, Math.min(5000, store.lyricsOffsetMs() + deltaMs));
        store.setLyricsOffsetMs(v);
        if (lyricsView != null) lyricsView.setOffsetMs(v);
        update();
    }

    // ------------------------------------------------------------------ what's playing → lyrics

    private void update() {
        if (!panelAdded || lyricsView == null) return;
        boolean access = nowPlaying.start();
        pollSpotify();
        NowPlaying.Track t = store.spotifyConnected() ? spotifyTrack : null;
        if (t == null) t = nowPlaying.current();
        if (t == null) t = homeAssistantTrack();

        if (t == null) {
            shownKey = "";
            titleView.setText("Lyrics");
            subtitleView.setText(offsetLabel(""));
            boolean spotify = store.spotifyConnected();
            boolean canSee = access || nowPlaying.hasBroadcasts() || spotify;
            accessButton.setVisibility(canSee ? View.GONE : View.VISIBLE);
            if (spotify && !spotifyError.isEmpty()) {
                lyricsView.setMessage(spotifyError);
                return;
            }
            lyricsView.setMessage(canSee ? "Nothing is playing."
                    : "No song detected yet.\n\nIn Spotify, open Settings and turn on “Device Broadcast Status”, "
                    + "then play a song.");
            return;
        }
        accessButton.setVisibility(View.GONE);
        titleView.setText(t.title);
        String sub = t.artist;
        if (t.fromHomeAssistant) sub = join(sub, "via Home Assistant");
        if (t.fromSpotifyApi && !t.device.isEmpty()) sub = join(sub, "on " + t.device);
        subtitleView.setText(offsetLabel(sub));

        String key = t.key();
        if (!key.equals(shownKey)) {
            shownKey = key;
            LrcLib.Result cached = cache.get(key);
            if (cached != null) show(cached);
            else lyricsView.setMessage("Looking for lyrics…");
        }
        if (!cache.containsKey(key) && !key.equals(loadingKey)
                && !(key.equals(failedKey) && SystemClock.elapsedRealtime() - failedAt < RETRY_MS)) {
            load(key, t);
        }
        if (t.positionKnown) {
            lyricsView.setClock(t.positionMs, t.atRealtime, t.speed, t.playing, t.durationMs);
        } else {
            lyricsView.setClock(0, SystemClock.elapsedRealtime(), 1f, false, t.durationMs);
        }
    }

    /** Asks Spotify what's playing every few seconds while the popup is open, and right when a song should end. */
    private void pollSpotify() {
        if (!store.spotifyConnected()) {
            spotifyTrack = null;
            spotifyError = "";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (spotifyBusy || now < spotifyNextAt) return;
        spotifyBusy = true;
        spotifyWorker.execute(() -> {
            NowPlaying.Track got = null;
            String err = "";
            try {
                got = SpotifyApi.currentlyPlaying(store);
            } catch (SpotifyApi.Rejected e) {
                err = e.getMessage() + " Connect Spotify again in HA Overlay.";
            } catch (java.io.IOException | RuntimeException e) {
                err = e.getMessage() == null ? "Couldn't reach Spotify." : e.getMessage();
                if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) {
                    err = "Couldn't reach Spotify (no connection?).";
                }
            }
            final NowPlaying.Track t = got;
            final String error = err;
            main.post(() -> {
                spotifyBusy = false;
                long at = SystemClock.elapsedRealtime();
                if (!error.isEmpty()) {
                    Log.w(TAG, "Spotify: " + error);
                    spotifyError = error;
                    spotifyNextAt = at + 10000;
                    // keep showing the last song for a moment rather than blanking on one failed poll
                    if (spotifyTrack != null && !spotifyTrack.playing) spotifyTrack = null;
                } else {
                    spotifyError = "";
                    spotifyTrack = t;
                    long next = at + (t != null && t.playing ? 4000 : 8000);
                    if (t != null && t.playing && t.positionKnown && t.durationMs > 0) {
                        long endsAt = t.atRealtime + (t.durationMs - t.positionMs) + 700;
                        if (endsAt > at && endsAt < next) next = endsAt;
                    }
                    spotifyNextAt = next;
                }
                update();
            });
        });
    }

    private void load(String key, NowPlaying.Track t) {
        loadingKey = key;
        lrc.fetch(t.title, t.artist, t.album, t.durationMs, result -> {
            if (key.equals(loadingKey)) loadingKey = "";
            if (result == null) {
                failedKey = key;
                failedAt = SystemClock.elapsedRealtime();
                if (key.equals(shownKey) && lyricsView != null) {
                    lyricsView.setMessage("Couldn't reach the lyrics service. Trying again shortly…");
                }
                return;
            }
            cache.put(key, result);
            if (key.equals(shownKey)) show(result);
        });
    }

    private void show(LrcLib.Result r) {
        if (lyricsView == null) return;
        if (r.isEmpty()) {
            lyricsView.setMessage(r.instrumental ? "♪ Instrumental ♪" : "No lyrics found for this song.");
        } else {
            lyricsView.setLyrics(r.lines, r.synced);
        }
    }

    private String offsetLabel(String base) {
        long off = store.lyricsOffsetMs();
        if (off == 0) return base;
        return join(base, String.format(Locale.US, "lyrics %+.2f s", off / 1000.0));
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + " · " + b;
    }

    /** Fallback when this device's player can't be read: a Home Assistant media player that has a song title. */
    private NowPlaying.Track homeAssistantTrack() {
        Map<String, EntityState> all = source.states();
        if (all == null) return null;
        EntityState best = null;
        int bestScore = -1;
        try {
            for (EntityState s : all.values()) {
                if (!"media_player".equals(s.domain())) continue;
                boolean playing = "playing".equals(s.state);
                if (!playing && !"paused".equals(s.state)) continue;
                if (s.attrs.optString("media_title", "").isEmpty()) continue;
                int score = (playing ? 2 : 0) + (s.entityId.contains("spotify") ? 1 : 0);
                if (score > bestScore) {
                    best = s;
                    bestScore = score;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (best == null) return null;

        NowPlaying.Track t = new NowPlaying.Track();
        t.fromHomeAssistant = true;
        t.title = best.attrs.optString("media_title", "");
        t.artist = best.attrs.optString("media_artist", "");
        t.album = best.attrs.optString("media_album_name", "");
        t.durationMs = (long) (best.attrs.optDouble("media_duration", 0) * 1000);
        t.playing = "playing".equals(best.state);
        double pos = best.attrs.optDouble("media_position", Double.NaN);
        String at = best.attrs.optString("media_position_updated_at", "");
        if (!Double.isNaN(pos) && !at.isEmpty()) {
            try {
                long updatedAt = OffsetDateTime.parse(at).toInstant().toEpochMilli();
                t.positionMs = (long) (pos * 1000);
                t.atRealtime = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - updatedAt);
                t.positionKnown = true;
            } catch (DateTimeParseException ignored) {
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ notification access

    private void openAccessSettings() {
        Intent detail = null;
        if (Build.VERSION.SDK_INT >= 30) {
            detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(ui, MediaWatcher.class).flattenToString());
        }
        Intent[] tries = {
                detail,
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ui.getPackageName())),
        };
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                ui.getApplicationContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                closePanel();
                return;
            } catch (RuntimeException ignored) {
                // try the next one
            }
        }
        toast("This screen has no notification access page.");
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(int appearance, int colorAttr) {
        TextView t = new TextView(ui);
        t.setTextAppearance(appearance);
        t.setTextColor(color(colorAttr, 0xFFDFE3E7));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View iconButton(String icon, String description, Runnable action) {
        TextView b = new TextView(ui);
        b.setTypeface(MdiIcons.typeface(ui));
        b.setText(MdiIcons.glyphOf(ui, "mdi:" + icon));
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFC0C8CD));
        b.setContentDescription(description);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), null, mask));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void updateWindow(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null || !v.isAttachedToWindow()) return;
        try {
            wm.updateViewLayout(v, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void removeWindow(View v) {
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private void toast(String msg) {
        Toast.makeText(ui.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private int color(int attr, int fallback) {
        return MaterialColors.getColor(ui, attr, fallback);
    }

    private int dp(float v) {
        return Math.round(v * ui.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }
}
