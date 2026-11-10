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
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps every pinned card floating on screen, each in its own {@code TYPE_APPLICATION_OVERLAY} window
 * ({@link CardWindow}), plus the lyrics button. Holds a live WebSocket to Home Assistant for the
 * built-in card drawing and for sending actions.
 */
public class OverlayService extends Service implements HaSocket.Listener, CardWindow.Owner {

    private static final String TAG = "OverlayService";

    static final String ACTION_START = "com.haoverlay.action.START";
    static final String ACTION_STOP = "com.haoverlay.action.STOP";
    static final String ACTION_REFRESH = "com.haoverlay.action.REFRESH";
    static final String ACTION_RESET_POSITIONS = "com.haoverlay.action.RESET_POSITIONS";

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
    private LyricsOverlay lyrics;
    private Map<String, EntityState> states = new HashMap<>();
    private boolean online;

    private final LinkedHashMap<String, CardInfo> cards = new LinkedHashMap<>();
    private final Map<String, CardWindow> windows = new HashMap<>();

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
            case ACTION_RESET_POSITIONS: {
                int i = 0;
                for (String id : cards.keySet()) {
                    CardWindow w = windows.get(id);
                    if (w != null) w.resetPosition(i);
                    i++;
                }
                lyrics.resetPosition();
                break;
            }
            case ACTION_REFRESH:
                syncCards();
                lyrics.sync(screenW, screenH, 0);
                break;
            default:
                syncCards();
                lyrics.sync(screenW, screenH, 0);
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
        syncCards();   // re-measures the screen and re-fits every card
        lyrics.onScreenChanged(screenW, screenH);
    }

    @Override
    public void onDestroy() {
        lyrics.destroy();
        running = false;
        main.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
        }
        socket.close();
        for (CardWindow w : windows.values()) w.destroy();
        windows.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ floating cards

    /** Makes the card windows on screen match the pinned cards in storage . */
    private void syncCards() {
        measureScreen();
        cards.clear();
        for (CardInfo c : store.selectedCards()) cards.put(c.id, c);
        for (String id : new ArrayList<>(windows.keySet())) {
            if (!cards.containsKey(id)) windows.remove(id).destroy();
        }
        boolean useWeb = store.webCards();
        int index = 0, fresh = 0;
        for (CardInfo c : cards.values()) {
            int i = index++;
            CardWindow w = windows.get(c.id);
            if (w == null) {
                w = new CardWindow(ui, wm, store, this, c);
                windows.put(c.id, w);
                // Each card loads its own copy of Home Assistant's page; start them one after another.
                w.open(screenW, screenH, i, useWeb, 1200 + 2500L * fresh++);
            } else {
                w.refresh(c, screenW, screenH, useWeb);
            }
        }
    }

    // ------------------------------------------------------------------ CardWindow.Owner

    @Override
    public Map<String, EntityState> states() {
        return states;
    }

    @Override
    public boolean online() {
        return online;
    }

    @Override
    public void callService(String entityId, String domain, String service, JSONObject data) {
        try {
            socket.callService(domain, service, data, new JSONObject().put("entity_id", entityId),
                    (ok, result, error) -> {
                        if (!ok) toast(domain + "." + service + " failed: " + error);
                    });
        } catch (JSONException e) {
            toast(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ HaSocket.Listener

    @Override
    public void onConnected() {
        online = true;
        for (CardWindow w : windows.values()) {
            w.onConnected();
            w.onStates();
        }
        updateNotification();
    }

    @Override
    public void onDisconnected(String reason) {
        online = false;
        for (CardWindow w : windows.values()) w.onStates();
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
        for (CardWindow w : windows.values()) w.onStates();
    }

    @Override
    public void onStateChanged(String entityId, EntityState state) {
        if (state == null) states.remove(entityId);
        else states.put(entityId, state);
        for (CardWindow w : windows.values()) {
            if (w.showsEntity(entityId)) w.onStates();
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
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Floating cards", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), flags);

        int n = windows.size();
        String text = (online ? "Connected" : "Connecting to Home Assistant…")
                + " · " + n + (n == 1 ? " card" : " cards");
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_home)
                .setContentTitle("HA Overlay")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop", stop).build());
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

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Overlay windows are positioned inside the area left by the status and navigation bars
            // (and any dock the car draws), so measure that area rather than the whole panel.
            android.view.WindowMetrics wmx = wm.getCurrentWindowMetrics();
            Rect r = wmx.getBounds();
            android.graphics.Insets in = wmx.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            screenW = r.width() - in.left - in.right;
            screenH = r.height() - in.top - in.bottom;
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            screenW = m.widthPixels;
            screenH = m.heightPixels;
        }
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }
}
