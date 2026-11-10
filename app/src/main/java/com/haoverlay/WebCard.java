package com.haoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Draws a pinned card with Home Assistant's own web interface, so it looks exactly like the dashboard
 * (theme, custom cards and all). One hidden WebView keeps the Home Assistant page loaded for the whole
 * time the overlay runs; showing a card only swaps which card that page draws, so popups open quickly.
 * Sign-in uses the same "external auth" hook as the official Home Assistant app: the page asks this
 * class for an access token instead of showing a login form.
 */
final class WebCard {

    interface Host {
        void onWebCardReady();

        void onWebCardSize();

        void onWebCardFailed();
    }

    private static final String TAG = "WebCard";

    private final Context ui;
    private final Store store;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private String script;

    private WebView web;
    private boolean ready;
    private boolean failed;
    private boolean expanded;
    private int cardHeightCss;
    private String shownConfig;
    private boolean released;

    WebCard(Context ui, Store store, Host host) {
        this.ui = ui;
        this.store = store;
        this.host = host;
        this.density = ui.getResources().getDisplayMetrics().density;
    }

    boolean isReady() {
        return ready && web != null;
    }

    boolean hasFailed() {
        return failed;
    }

    View view() {
        return web;
    }

    /** Height the card wants, in pixels; the whole allowance while a Home Assistant dialog is open. */
    int desiredHeight(int maxPx) {
        if (expanded) return maxPx;
        int h = Math.round(cardHeightCss * density);
        return Math.max(Math.round(48 * density), Math.min(maxPx, h));
    }

    /** (Re)loads the Home Assistant page. Safe to call again after a failure. */
    @SuppressLint("SetJavaScriptEnabled")
    void load() {
        if (released) return;
        ready = false;
        failed = false;
        expanded = false;
        try {
            if (web == null) {
                web = new WebView(ui);
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                web.setBackgroundColor(Color.TRANSPARENT);
                web.setOverScrollMode(View.OVER_SCROLL_NEVER);
                web.setVerticalScrollBarEnabled(false);
                web.addJavascriptInterface(new ExternalApp(), "externalApp");
                web.addJavascriptInterface(new HostBridge(), "haOverlayHost");
                web.setWebViewClient(new Client());
                web.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage m) {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.w(TAG, "page: " + m.message());
                        }
                        return true;
                    }
                });
            }
            String path = store.dashboardPath();
            web.loadUrl(store.serverUrl() + "/" + (path.isEmpty() ? "lovelace" : path) + "?external_auth=1");
        } catch (RuntimeException e) {
            // No WebView on this device, or it is being updated.
            Log.e(TAG, "WebView unavailable", e);
            fail();
        }
    }

    /** Draws this card (its dashboard config). Remembered, so it is redrawn if the page reloads. */
    void show(JSONObject config) {
        shownConfig = config.toString();
        if (isReady()) run("window.__haOverlay.show(" + shownConfig + ")");
    }

    /** The popup closed: close any Home Assistant dialog left open inside it. */
    void popupClosed() {
        expanded = false;
        if (isReady()) run("window.__haOverlay.reset()");
    }

    /** Shuts down for good (the overlay stopped or the setting was turned off). */
    void release() {
        released = true;
        destroy();
    }

    private void destroy() {
        main.removeCallbacksAndMessages(null);
        ready = false;
        if (web != null) {
            try {
                if (web.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) web.getParent()).removeView(web);
                }
                web.destroy();
            } catch (RuntimeException ignored) {
            }
            web = null;
        }
    }

    private void run(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    private void fail() {
        ready = false;
        if (failed) return;
        failed = true;
        main.post(host::onWebCardFailed);
    }

    private boolean onServer(String url) {
        if (url == null) return false;
        Uri a = Uri.parse(url), b = Uri.parse(store.serverUrl());
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }

    private String script() {
        if (script == null) {
            try (InputStream in = ui.getAssets().open("card_host.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                script = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                script = "";
            }
        }
        return script;
    }

    // ------------------------------------------------------------------ page events

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Stay on the Home Assistant server; links to anywhere else are ignored.
            return !onServer(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            ready = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!failed && onServer(url)) view.evaluateJavascript(script(), null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                Log.w(TAG, "Page failed: " + error.getDescription());
                fail();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            // The page's process crashed or was killed for memory. Without this the whole app would be
            // closed; instead drop the dead page and fall back to the built-in card until it is reloaded.
            Log.w(TAG, "Web page process ended (crashed=" + detail.didCrash() + ")");
            main.post(() -> {
                destroy();
                fail();
                main.postDelayed(() -> {
                    if (failed && web == null && !released) load();
                }, 20000);
            });
            return true;
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) {
                Log.w(TAG, "Page failed: HTTP " + response.getStatusCode());
                fail();
            }
        }
    }

    /** Called by card_host.js. These arrive on a WebView thread, so everything hops to the main thread. */
    private final class HostBridge {
        @JavascriptInterface
        public void ready() {
            main.post(() -> {
                if (web == null) return;
                ready = true;
                failed = false;
                if (shownConfig != null) run("window.__haOverlay.show(" + shownConfig + ")");
                host.onWebCardReady();
            });
        }

        @JavascriptInterface
        public void size(int cssPx) {
            main.post(() -> {
                if (cssPx == cardHeightCss) return;
                cardHeightCss = cssPx;
                host.onWebCardSize();
            });
        }

        @JavascriptInterface
        public void expanded(boolean on) {
            main.post(() -> {
                if (on == expanded) return;
                expanded = on;
                host.onWebCardSize();
            });
        }

        @JavascriptInterface
        public void failed(String message) {
            Log.w(TAG, "Card host failed: " + message);
            main.post(WebCard.this::fail);
        }
    }

    /** The hook Home Assistant's page uses to get a token from a companion app instead of asking the user to log in. */
    private final class ExternalApp {
        @JavascriptInterface
        public void getExternalAuth(String payload) {
            String callback = "externalAuthSetToken";
            boolean force = false;
            try {
                JSONObject o = new JSONObject(payload);
                callback = o.optString("callback", callback);
                force = o.optBoolean("force", false);
            } catch (JSONException ignored) {
            }
            if (!callback.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return;
            final String cb = callback;
            final boolean refresh = force;
            new Thread(() -> {
                String token = null;
                try {
                    if (refresh) store.expireAccessToken();
                    token = HaAuth.validAccessToken(store);
                } catch (IOException e) {
                    Log.w(TAG, "Couldn't get a token: " + e.getMessage());
                }
                final String t = token;
                main.post(() -> deliver(cb, t));
            }, "ha-webcard-auth").start();
        }

        @JavascriptInterface
        public void revokeExternalAuth(String payload) {
            // Logging out is done from the app's own screen, not from inside a card.
        }
    }

    private void deliver(String callback, String token) {
        // Only ever hand the token to a page on the Home Assistant server itself.
        if (web == null || !onServer(web.getUrl())) return;
        if (token == null) {
            run("window." + callback + "(false)");
            return;
        }
        long expiresIn = store.usesLongLivedToken() ? 365L * 24 * 3600
                : Math.max(60, (store.accessExpiresAt() - System.currentTimeMillis()) / 1000);
        try {
            JSONObject data = new JSONObject().put("access_token", token).put("expires_in", expiresIn);
            run("window." + callback + "(true, " + data + ")");
        } catch (JSONException ignored) {
        }
    }
}
