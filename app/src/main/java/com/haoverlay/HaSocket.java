package com.haoverlay;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Home Assistant WebSocket API client.
 *
 * <p>All callbacks and all internal state live on the main thread; OkHttp's threads only hand
 * messages over. It reconnects by itself with back-off while {@link #connect()} is in effect.</p>
 */
final class HaSocket {

    private static final String TAG = "HaSocket";

    interface Listener {
        default void onConnected() {
        }

        default void onDisconnected(String reason) {
        }

        /** Login is gone (revoked or expired refresh token); the user must sign in again. */
        default void onAuthFailed() {
        }

        /** Full snapshot of all entity states (only when subscribing to states). */
        default void onStates(Map<String, EntityState> states) {
        }

        /** One entity changed. {@code state} is {@code null} when the entity was removed. */
        default void onStateChanged(String entityId, EntityState state) {
        }
    }

    interface ResultCallback {
        /** {@code result} is a JSONObject, JSONArray or {@code null}. {@code error} is set when not successful. */
        void onResult(boolean success, Object result, String error);
    }

    private static final OkHttpClient WS_CLIENT = HaAuth.HTTP.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build();

    private final Store store;
    private final Listener listener;
    private final boolean subscribeStates;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, ResultCallback> pending = new HashMap<>();

    private WebSocket ws;
    private int generation;
    private int nextId = 1;
    private boolean wanted;
    private boolean authed;
    private boolean retriedAuth;
    private long backoffMs = 2000;
    private String token;

    private final Runnable reconnect = this::open;

    HaSocket(Store store, Listener listener, boolean subscribeStates) {
        this.store = store;
        this.listener = listener;
        this.subscribeStates = subscribeStates;
    }

    boolean isConnected() {
        return authed;
    }

    void connect() {
        wanted = true;
        if (ws == null) open();
    }

    /** Drops the current connection (if any) and connects again right away, e.g. when the network comes back. */
    void reconnectNow() {
        if (!wanted) return;
        if (authed) return;
        main.removeCallbacks(reconnect);
        backoffMs = 2000;
        closeSocket();
        open();
    }

    void close() {
        wanted = false;
        main.removeCallbacks(reconnect);
        closeSocket();
        failPending("Connection closed");
    }

    /** Sends a command; {@code id} is filled in. Fails immediately when not connected. */
    void send(JSONObject cmd, ResultCallback cb) {
        if (!authed || ws == null) {
            if (cb != null) cb.onResult(false, null, "Not connected to Home Assistant");
            return;
        }
        int id = nextId++;
        try {
            cmd.put("id", id);
        } catch (JSONException e) {
            if (cb != null) cb.onResult(false, null, e.getMessage());
            return;
        }
        if (cb != null) pending.put(id, cb);
        if (!ws.send(cmd.toString())) {
            pending.remove(id);
            if (cb != null) cb.onResult(false, null, "Could not send to Home Assistant");
        }
    }

    void callService(String domain, String service, JSONObject data, JSONObject target, ResultCallback cb) {
        try {
            JSONObject cmd = new JSONObject()
                    .put("type", "call_service")
                    .put("domain", domain)
                    .put("service", service);
            if (data != null && data.length() > 0) cmd.put("service_data", data);
            if (target != null && target.length() > 0) cmd.put("target", target);
            send(cmd, cb);
        } catch (JSONException e) {
            if (cb != null) cb.onResult(false, null, e.getMessage());
        }
    }

    /** Calls {@code domain.service} on one entity. */
    void callEntityService(String entityId, String domain, String service, JSONObject data, ResultCallback cb) {
        try {
            callService(domain, service, data, new JSONObject().put("entity_id", entityId), cb);
        } catch (JSONException e) {
            if (cb != null) cb.onResult(false, null, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ connection

    private void open() {
        if (!wanted) return;
        final int gen = ++generation;
        authed = false;
        new Thread(() -> {
            String t;
            try {
                t = HaAuth.validAccessToken(store);
            } catch (IOException e) {
                main.post(() -> {
                    if (gen != generation) return;
                    listener.onDisconnected(describe(e));
                    scheduleReconnect();
                });
                return;
            }
            final String tok = t;
            main.post(() -> {
                if (gen != generation || !wanted) return;
                if (tok == null) {
                    wanted = false;
                    listener.onAuthFailed();
                    return;
                }
                token = tok;
                Request req = new Request.Builder().url(websocketUrl(store.serverUrl())).build();
                ws = WS_CLIENT.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        main.post(() -> {
                            if (gen == generation) handle(text);
                        });
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        main.post(() -> {
                            if (gen == generation) dropped("Connection closed");
                        });
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        main.post(() -> {
                            if (gen == generation) dropped(describe(t));
                        });
                    }
                });
            });
        }, "ha-connect").start();
    }

    private void dropped(String reason) {
        boolean wasAuthed = authed;
        authed = false;
        ws = null;
        failPending(reason);
        if (wasAuthed || wanted) listener.onDisconnected(reason);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!wanted) return;
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, 60_000L);
    }

    private void closeSocket() {
        generation++;
        authed = false;
        if (ws != null) {
            try {
                ws.close(1000, null);
            } catch (RuntimeException ignored) {
            }
            ws = null;
        }
    }

    private void failPending(String reason) {
        if (pending.isEmpty()) return;
        Map<Integer, ResultCallback> copy = new HashMap<>(pending);
        pending.clear();
        for (ResultCallback cb : copy.values()) cb.onResult(false, null, reason);
    }

    // ------------------------------------------------------------------ protocol

    private void handle(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (JSONException e) {
            Log.w(TAG, "Bad message: " + e.getMessage());
            return;
        }
        switch (msg.optString("type")) {
            case "auth_required":
                try {
                    ws.send(new JSONObject().put("type", "auth").put("access_token", token).toString());
                } catch (JSONException ignored) {
                }
                break;
            case "auth_ok":
                authed = true;
                retriedAuth = false;
                backoffMs = 2000;
                listener.onConnected();
                if (subscribeStates) subscribe();
                break;
            case "auth_invalid":
                closeSocket();
                if (!retriedAuth && !store.usesLongLivedToken()) {
                    // The access token may have been revoked early; refresh once and retry.
                    retriedAuth = true;
                    store.expireAccessToken();
                    open();
                } else {
                    wanted = false;
                    store.clearAuth();
                    listener.onAuthFailed();
                }
                break;
            case "result": {
                ResultCallback cb = pending.remove(msg.optInt("id", -1));
                if (cb == null) break;
                boolean ok = msg.optBoolean("success", false);
                JSONObject err = msg.optJSONObject("error");
                cb.onResult(ok, msg.opt("result"), err == null ? (ok ? null : "Request failed") : err.optString("message", "Request failed"));
                break;
            }
            case "event":
                handleEvent(msg.optJSONObject("event"));
                break;
            default:
                break;
        }
    }

    private void subscribe() {
        try {
            send(new JSONObject().put("type", "get_states"), (ok, result, error) -> {
                if (!ok || !(result instanceof JSONArray)) return;
                JSONArray arr = (JSONArray) result;
                Map<String, EntityState> all = new HashMap<>(arr.length() * 2);
                for (int i = 0; i < arr.length(); i++) {
                    EntityState s = EntityState.from(arr.optJSONObject(i));
                    if (s != null) all.put(s.entityId, s);
                }
                listener.onStates(all);
            });
            send(new JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"), null);
        } catch (JSONException ignored) {
        }
    }

    private void handleEvent(JSONObject event) {
        if (event == null || !"state_changed".equals(event.optString("event_type"))) return;
        JSONObject data = event.optJSONObject("data");
        if (data == null) return;
        String id = data.optString("entity_id", "");
        if (id.isEmpty()) return;
        JSONObject ns = data.optJSONObject("new_state");
        listener.onStateChanged(id, ns == null ? null : EntityState.from(ns));
    }

    // ------------------------------------------------------------------ helpers

    static String websocketUrl(String base) {
        String ws;
        if (base.startsWith("https://")) ws = "wss://" + base.substring(8);
        else if (base.startsWith("http://")) ws = "ws://" + base.substring(7);
        else ws = base;
        return ws + "/api/websocket";
    }

    static String describe(Throwable t) {
        String m = t.getMessage();
        if (t instanceof java.net.UnknownHostException) return "Can't find the server (" + m + ")";
        if (t instanceof java.net.SocketTimeoutException) return "The server did not answer in time";
        if (t instanceof java.net.ConnectException) return "Can't reach the server";
        if (t instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + m + ")";
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
