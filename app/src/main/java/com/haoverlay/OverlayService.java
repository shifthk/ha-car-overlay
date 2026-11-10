package com.haoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps one floating button per pinned card on screen (a {@code TYPE_APPLICATION_OVERLAY} window each)
 * plus one popup window that shows the card that was tapped. Holds a live WebSocket to Home Assistant
 * so button colours and the popup follow entity states in real time.
 */
public class OverlayService extends Service implements HaSocket.Listener, CardRenderer.Controller, WebCard.Host {

    private static final String TAG = "OverlayService";

    static final String ACTION_START = "com.haoverlay.action.START";
    static final String ACTION_STOP = "com.haoverlay.action.STOP";
    static final String ACTION_REFRESH = "com.haoverlay.action.REFRESH";
    static final String ACTION_RESET_POSITIONS = "com.haoverlay.action.RESET_POSITIONS";
    static final String ACTION_CLOSE_PANEL = "com.haoverlay.action.CLOSE_PANEL";

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION_ID = 42;

    static volatile boolean running;
    static volatile String lastError = "";

    // ------------------------------------------------------------------ static helpers for callers

    static void start(Context c) {
        Intent i = new Intent(c, OverlayService.class).setAction(ACTION_START);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            lastError = "Couldn't start: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }
    }

    static void stop(Context c) {
        if (!running) return;
        send(c, ACTION_STOP);
    }

    static void refresh(Context c) {
        send(c, ACTION_REFRESH);
    }

    static void resetPositions(Context c) {
        send(c, ACTION_RESET_POSITIONS);
    }

    private static void send(Context c, String action) {
        try {
            c.startService(new Intent(c, OverlayService.class).setAction(action));
        } catch (RuntimeException e) {
            Log.w(TAG, "send " + action + " failed", e);
        }
    }

    // ------------------------------------------------------------------ state

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Context ui;
    private WindowManager wm;
    private HaSocket socket;
    private CardRenderer renderer;
    private LyricsOverlay lyrics;
    /** Draws cards with Home Assistant's own web interface; {@code null} when that setting is off. */
    private WebCard webCard;
    private FrameLayout webWrap;
    private String webShownId;
    private Map<String, EntityState> states = new HashMap<>();
    private boolean online;

    private final LinkedHashMap<String, CardInfo> cards = new LinkedHashMap<>();
    private final Map<String, FloatingCardButton> fabs = new HashMap<>();
    private final Map<String, WindowManager.LayoutParams> fabLps = new HashMap<>();

    private FrameLayout panel;
    private WindowManager.LayoutParams panelLp;
    private String panelCardId;
    private boolean panelAdded;
    private boolean interacting;
    private boolean rebuildPending;
    private String outsideClosedCardId;
    private long outsideClosedAt;

    private int screenW, screenH;
    private ConnectivityManager.NetworkCallback netCallback;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        Context windowContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            windowContext = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        ui = new ContextThemeWrapper(windowContext, R.style.Theme_HAOverlay);
        wm = windowContext.getSystemService(WindowManager.class);
        renderer = new CardRenderer(ui, this);
        socket = new HaSocket(store, this, true);
        lyrics = new LyricsOverlay(ui, wm, store, () -> states);
        goForeground();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            store.setOverlayEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            lastError = "“Display over other apps” is not allowed.";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!store.isLoggedIn()) {
            lastError = "Not signed in to Home Assistant.";
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_RESET_POSITIONS:
                for (String id : fabLps.keySet()) placeFab(id, true);
                closePanel();
                lyrics.resetPosition();
                break;
            case ACTION_CLOSE_PANEL:
                closePanel();
                break;
            default:   // START or REFRESH
                syncWebCard();
                syncCards();
                lyrics.sync(screenW, screenH, cards.size());
                break;
        }
        if (cards.isEmpty() && !lyrics.isShown()) {
            lastError = "No cards are pinned and the lyrics button is off.";
            stopSelf();
            return START_NOT_STICKY;
        }
        lastError = "";
        running = true;
        socket.connect();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        measureScreen();
        for (String id : fabLps.keySet()) {
            WindowManager.LayoutParams lp = fabLps.get(id);
            if (lp == null) continue;
            lp.x = clamp(lp.x, 0, screenW - lp.width);
            lp.y = clamp(lp.y, 0, screenH - lp.height);
            updateWindow(fabs.get(id), lp);
        }
        // Colours may have switched between light and dark.
        refreshFabs();
        if (panelAdded) rebuildPanel();
        lyrics.onScreenChanged(screenW, screenH);
    }

    @Override
    public void onDestroy() {
        lyrics.destroy();
        if (webCard != null) webCard.release();
        webCard = null;
        running = false;
        main.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
        }
        socket.close();
        closePanel();
        for (FloatingCardButton b : fabs.values()) removeWindow(b);
        fabs.clear();
        fabLps.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ floating buttons

    /** Makes the on-screen buttons match the pinned cards in storage. */
    private void syncCards() {
        measureScreen();
        List<CardInfo> pinned = store.selectedCards();
        Set<String> keep = new HashSet<>();
        cards.clear();
        for (CardInfo c : pinned) {
            cards.put(c.id, c);
            keep.add(c.id);
        }
        for (String id : new ArrayList<>(fabs.keySet())) {
            if (!keep.contains(id)) {
                removeWindow(fabs.remove(id));
                fabLps.remove(id);
            }
        }
        int size = dp(store.fabSizeDp());
        for (CardInfo c : cards.values()) {
            FloatingCardButton b = fabs.get(c.id);
            if (b == null) {
                addFab(c, size);
            } else {
                WindowManager.LayoutParams lp = fabLps.get(c.id);
                if (lp != null && lp.width != size) {
                    lp.width = size;
                    lp.height = size;
                    lp.x = clamp(lp.x, 0, screenW - size);
                    lp.y = clamp(lp.y, 0, screenH - size);
                    updateWindow(b, lp);
                }
            }
        }
        refreshFabs();
        if (panelCardId != null && !cards.containsKey(panelCardId)) closePanel();
        else if (panelAdded) rebuildPanel();
    }

    private void addFab(CardInfo card, int size) {
        FloatingCardButton b = new FloatingCardButton(ui);
        b.setContentDescription(card.title);
        WindowManager.LayoutParams lp = overlayParams(size, size);
        fabs.put(card.id, b);
        fabLps.put(card.id, lp);
        placeFab(card.id, false);
        final String id = card.id;
        b.setOnTouchListener(new DragTouch(ui, lp, new DragTouch.Listener() {
            @Override
            public void onTap() {
                // The tap that closed this card's popup (as an outside touch) must not reopen it.
                if (id.equals(outsideClosedCardId) && SystemClock.uptimeMillis() - outsideClosedAt < 500) return;
                togglePanel(id);
            }

            @Override
            public void onLongPress() {
                runPrimaryAction(id);
            }

            @Override
            public void onMove(int x, int y) {
                lp.x = clamp(x, 0, screenW - lp.width);
                lp.y = clamp(y, 0, screenH - lp.height);
                updateWindow(b, lp);
                if (id.equals(panelCardId)) positionPanel();
            }

            @Override
            public void onDragEnd() {
                store.setFabPosition(id, lp.x, lp.y);
            }
        }));
        try {
            wm.addView(b, lp);
        } catch (RuntimeException e) {
            lastError = "Couldn't show a floating button: " + e.getMessage();
            Log.e(TAG, lastError, e);
            fabs.remove(card.id);
            fabLps.remove(card.id);
        }
    }

    /** Saved position, or stacked down the right edge in pin order. */
    private void placeFab(String id, boolean forceDefault) {
        WindowManager.LayoutParams lp = fabLps.get(id);
        if (lp == null) return;
        int[] saved = forceDefault ? null : store.fabPosition(id);
        if (saved != null) {
            lp.x = saved[0];
            lp.y = saved[1];
        } else {
            int index = new ArrayList<>(cards.keySet()).indexOf(id);
            int gap = dp(16);
            lp.x = screenW - lp.width - dp(24);
            lp.y = dp(120) + Math.max(0, index) * (lp.height + gap);
        }
        lp.x = clamp(lp.x, 0, screenW - lp.width);
        lp.y = clamp(lp.y, 0, screenH - lp.height);
        updateWindow(fabs.get(id), lp);
    }

    private void refreshFabs() {
        for (Map.Entry<String, FloatingCardButton> e : fabs.entrySet()) {
            CardInfo card = cards.get(e.getKey());
            if (card != null) styleFab(e.getValue(), card);
        }
    }

    private void styleFab(FloatingCardButton b, CardInfo card) {
        boolean active = false;
        boolean light = false;
        boolean allUnavailable = !card.entities.isEmpty();
        for (CardInfo.EntityRef r : card.entities) {
            EntityState s = states.get(r.entityId);
            if (s == null || !s.isUnavailable()) allUnavailable = false;
            if (s != null && s.isActive()) {
                active = true;
                if ("light".equals(s.domain())) light = true;
            }
        }
        int bg, fg;
        if (active && light) {
            bg = ui.getColor(R.color.ha_light_on);
            fg = 0xFF3B2F00;
        } else if (active) {
            bg = MaterialColors.getColor(ui, com.google.android.material.R.attr.colorPrimary, 0xFF84CFFF);
            fg = MaterialColors.getColor(ui, com.google.android.material.R.attr.colorOnPrimary, 0xFF00344C);
        } else {
            bg = MaterialColors.getColor(ui, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF252B2E);
            fg = MaterialColors.getColor(ui, com.google.android.material.R.attr.colorOnSurface, 0xFFDFE3E7);
        }
        int outline = MaterialColors.getColor(ui, com.google.android.material.R.attr.colorOutlineVariant, 0xFF41484D);
        b.setColors(bg, fg, outline);
        b.setAlpha(allUnavailable ? 0.6f : 1f);
        b.setGlyph(MdiIcons.glyphOf(ui, card.iconName(states), "mdi:home-assistant"));
        b.setOffline(!online);
    }

    // ------------------------------------------------------------------ card popup

    private void togglePanel(String cardId) {
        if (panelAdded && cardId.equals(panelCardId)) {
            closePanel();
        } else {
            openPanel(cardId);
        }
    }

    private void openPanel(String cardId) {
        if (!cards.containsKey(cardId)) return;
        panelCardId = cardId;
        if (panel == null) {
            panel = new FrameLayout(ui) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                        onOutsideTouch();
                        return true;
                    }
                    return super.dispatchTouchEvent(ev);
                }
            };
            panelLp = overlayParams(dp(store.panelWidthDp()), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        panelLp.width = Math.min(dp(store.panelWidthDp()), screenW - dp(16));
        if (store.closeOnOutside()) panelLp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        else panelLp.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        rebuildPanel();
        positionPanel();
        if (!panelAdded) {
            try {
                wm.addView(panel, panelLp);
                panelAdded = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Couldn't show card popup", e);
                toast("Couldn't show the card: " + e.getMessage());
                return;
            }
        } else {
            updateWindow(panel, panelLp);
        }
        // Re-measure once laid out so the popup stays fully on screen.
        panel.post(this::positionPanel);
        updateNotification();
    }

    private void closePanel() {
        if (panel != null && panelAdded) removeWindow(panel);
        if (panelAdded && webCard != null) webCard.popupClosed();
        panelAdded = false;
        panelCardId = null;
        interacting = false;
        rebuildPending = false;
        if (running) updateNotification();
    }

    private void onOutsideTouch() {
        if (!panelAdded || interacting) return;
        outsideClosedCardId = panelCardId;
        outsideClosedAt = SystemClock.uptimeMillis();
        closePanel();
    }

    private void rebuildPanel() {
        if (panel == null || panelCardId == null) return;
        CardInfo card = cards.get(panelCardId);
        if (card == null) return;
        if (interacting) {
            rebuildPending = true;
            return;
        }
        rebuildPending = false;
        int maxHeight = (int) (screenH * 0.8f);
        if (webCard != null && webCard.isReady()) {
            showWebCard(card, maxHeight);
            return;
        }
        // Home Assistant's page isn't loaded (yet): draw the card natively meanwhile.
        webShownId = null;
        panel.removeAllViews();
        panel.addView(renderer.build(card, states, online, maxHeight));
    }

    // ------------------------------------------------------------------ cards drawn by Home Assistant itself

    /** Creates or drops the web renderer to match the setting. */
    private void syncWebCard() {
        if (store.webCards() && webCard == null) {
            webCard = new WebCard(ui, store, this);
            // Let the buttons appear first; loading Home Assistant's page is the heavy part.
            final WebCard w = webCard;
            main.postDelayed(() -> {
                if (w == webCard) w.load();
            }, 1500);
        } else if (!store.webCards() && webCard != null) {
            webCard.release();
            webCard = null;
            webShownId = null;
            if (panelAdded) rebuildPanel();
        }
    }

    private void showWebCard(CardInfo card, int maxHeight) {
        View v = webCard.view();
        int height = webCard.desiredHeight(maxHeight - dp(20));
        if (webWrap == null || panel.getChildCount() != 1 || panel.getChildAt(0) != webWrap || v.getParent() != webWrap) {
            panel.removeAllViews();
            if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
            webWrap = new FrameLayout(ui);
            webWrap.setPadding(dp(8), dp(8), dp(8), dp(12));
            webWrap.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height));
            panel.addView(webWrap);
        } else if (v.getLayoutParams().height != height) {
            v.getLayoutParams().height = height;
            v.requestLayout();
        }
        if (!card.id.equals(webShownId)) {
            webShownId = card.id;
            webCard.show(card.config);
        }
    }

    @Override
    public void onWebCardReady() {
        if (!panelAdded) return;
        rebuildPanel();
        panel.post(this::positionPanel);
    }

    @Override
    public void onWebCardSize() {
        if (!panelAdded || webShownId == null) return;
        rebuildPanel();
        panel.post(this::positionPanel);
    }

    @Override
    public void onWebCardFailed() {
        webShownId = null;
        if (panelAdded) rebuildPanel();
    }

    /** Puts the popup beside its button: to the left when the button is on the right half, else to the right. */
    private void positionPanel() {
        if (panel == null || panelLp == null || panelCardId == null) return;
        WindowManager.LayoutParams fab = fabLps.get(panelCardId);
        int w = panelLp.width;
        int h = panel.getHeight() > 0 ? panel.getHeight() : dp(240);
        if (fab != null) {
            boolean rightHalf = fab.x + fab.width / 2 > screenW / 2;
            panelLp.x = rightHalf ? fab.x - w - dp(4) : fab.x + fab.width + dp(4);
            panelLp.y = fab.y + fab.height / 2 - h / 2;
        } else {
            panelLp.x = (screenW - w) / 2;
            panelLp.y = (screenH - h) / 2;
        }
        panelLp.x = clamp(panelLp.x, 0, screenW - w);
        panelLp.y = clamp(panelLp.y, 0, screenH - h);
        if (panelAdded) updateWindow(panel, panelLp);
    }

    // ------------------------------------------------------------------ CardRenderer.Controller

    @Override
    public void call(String entityId, String domain, String service, JSONObject data) {
        try {
            socket.callService(domain, service, data, new JSONObject().put("entity_id", entityId),
                    (ok, result, error) -> {
                        if (!ok) toast(domain + "." + service + " failed: " + error);
                    });
        } catch (JSONException e) {
            toast(e.getMessage());
        }
    }

    @Override
    public void close() {
        closePanel();
    }

    @Override
    public void setInteracting(boolean on) {
        interacting = on;
        if (!on && rebuildPending) main.post(this::rebuildPanel);
    }

    // ------------------------------------------------------------------ long-press action

    /** The card's own tap_action if it calls a service; otherwise toggle / activate its first actionable entity. */
    private void runPrimaryAction(String cardId) {
        CardInfo card = cards.get(cardId);
        if (card == null) return;
        if (!online) {
            toast("Not connected to Home Assistant");
            return;
        }
        JSONObject tap = card.config.optJSONObject("tap_action");
        String act = tap == null ? "" : tap.optString("action", "");
        if ("perform-action".equals(act) || "call-service".equals(act)) {
            String svc = tap.optString("perform_action", tap.optString("service", ""));
            int dot = svc.indexOf('.');
            if (dot > 0) {
                JSONObject data = tap.optJSONObject("data");
                if (data == null) data = tap.optJSONObject("service_data");
                socket.callService(svc.substring(0, dot), svc.substring(dot + 1), data, tap.optJSONObject("target"),
                        (ok, r, err) -> toast(ok ? card.title + " ✓" : "Action failed: " + err));
                return;
            }
        }
        for (CardInfo.EntityRef ref : card.entities) {
            String domain = Domains.domainOf(ref.entityId);
            if (!Domains.hasPrimaryAction(domain)) continue;
            EntityState s = states.get(ref.entityId);
            String state = s == null ? "" : s.state;
            String d = domain, svc;
            switch (domain) {
                case "cover": svc = "toggle"; break;
                case "valve": svc = "toggle"; break;
                case "lock": svc = "locked".equals(state) ? "unlock" : "lock"; break;
                case "scene": case "script": svc = "turn_on"; break;
                case "button": case "input_button": svc = "press"; break;
                case "media_player": svc = "media_play_pause"; break;
                case "vacuum": svc = "cleaning".equals(state) ? "return_to_base" : "start"; break;
                default:
                    d = "homeassistant";
                    svc = "toggle";
                    break;
            }
            final String name = ref.displayName(s);
            try {
                socket.callService(d, svc, null, new JSONObject().put("entity_id", ref.entityId),
                        (ok, r, err) -> toast(ok ? name + " ✓" : "Action failed: " + err));
            } catch (JSONException ignored) {
            }
            return;
        }
        openPanel(cardId);   // nothing to toggle: show the card instead
    }

    // ------------------------------------------------------------------ HaSocket.Listener

    @Override
    public void onConnected() {
        online = true;
        if (webCard != null && webCard.hasFailed()) webCard.load();   // the network is back: try the page again
        refreshFabs();
        updateNotification();
    }

    @Override
    public void onDisconnected(String reason) {
        online = false;
        refreshFabs();
        if (panelAdded) rebuildPanel();
        updateNotification();
    }

    @Override
    public void onAuthFailed() {
        online = false;
        lastError = "Home Assistant login expired. Open the app and sign in again.";
        toast(lastError);
        store.setOverlayEnabled(false);
        stopSelf();
    }

    @Override
    public void onStates(Map<String, EntityState> all) {
        states = all;
        refreshFabs();
        if (panelAdded) rebuildPanel();
    }

    @Override
    public void onStateChanged(String entityId, EntityState state) {
        if (state == null) states.remove(entityId);
        else states.put(entityId, state);
        for (CardInfo card : cards.values()) {
            for (CardInfo.EntityRef r : card.entities) {
                if (!r.entityId.equals(entityId)) continue;
                FloatingCardButton b = fabs.get(card.id);
                if (b != null) styleFab(b, card);
                if (panelAdded && card.id.equals(panelCardId)) rebuildPanel();
                break;
            }
        }
    }

    // ------------------------------------------------------------------ notification

    private void goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            lastError = "Couldn't run in the background: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }
    }

    private void updateNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException ignored) {
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Floating buttons", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), flags);

        int n = cards.size() + (lyrics != null && lyrics.isShown() ? 1 : 0);
        String text = (online ? "Connected" : "Connecting to Home Assistant…")
                + " · " + n + (n == 1 ? " button" : " buttons");
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_home)
                .setContentTitle("HA Overlay")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop", stop).build());
        if (panelAdded) {
            PendingIntent closeP = PendingIntent.getService(this, 2,
                    new Intent(this, OverlayService.class).setAction(ACTION_CLOSE_PANEL), flags);
            b.addAction(new Notification.Action.Builder((Icon) null, "Close card", closeP).build());
        }
        return b.build();
    }

    // ------------------------------------------------------------------ helpers

    private void registerNetworkCallback() {
        try {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    main.post(() -> socket.reconnectNow());
                }
            };
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException e) {
            netCallback = null;
        }
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

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = wm.getCurrentWindowMetrics().getBounds();
            screenW = r.width();
            screenH = r.height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            screenW = m.widthPixels;
            screenH = m.heightPixels;
        }
    }

    private void updateWindow(View v, WindowManager.LayoutParams lp) {
        if (v == null || v.getParent() == null && !v.isAttachedToWindow()) return;
        try {
            wm.updateViewLayout(v, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void removeWindow(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    private int dp(float v) {
        return Math.round(v * ui.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }
}
