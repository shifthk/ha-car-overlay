package com.haoverlay;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Home Assistant sign-in (OAuth2 authorization-code flow) and token upkeep.
 *
 * <p>Home Assistant accepts any client_id that is a URL, and a redirect_uri on the same host
 * needs no extra verification. The login WebView intercepts the redirect before it is loaded,
 * so nothing is ever requested from {@link #REDIRECT_URI}.</p>
 */
final class HaAuth {

    static final String CLIENT_ID = "http://localhost/haoverlay/";
    static final String REDIRECT_URI = "http://localhost/haoverlay/callback";

    static final OkHttpClient HTTP = ChainTrust.apply(new OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();

    /** Thrown when the server says the credentials are no longer valid (as opposed to being unreachable). */
    static final class AuthRejected extends IOException {
        AuthRejected(String msg) {
            super(msg);
        }
    }

    private HaAuth() {
    }

    static String authorizeUrl(String base, String state) {
        return Uri.parse(base + "/auth/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("state", state)
                .build()
                .toString();
    }

    /** Swaps the one-time code from the login page for access + refresh tokens and saves them. */
    static void exchangeCode(Store store, String code) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .build();
        JSONObject json = postToken(store.serverUrl(), body);
        saveTokens(store, json);
    }

    /**
     * Returns an access token that is good for at least another minute, refreshing it if needed.
     * Blocking: call off the main thread.
     *
     * @return the token, or {@code null} if the user is not logged in (or the login was revoked)
     * @throws IOException when the server cannot be reached; try again later
     */
    static synchronized String validAccessToken(Store store) throws IOException {
        if (!store.isLoggedIn()) return null;
        if (store.usesLongLivedToken()) return store.accessToken();

        String access = store.accessToken();
        if (!access.isEmpty() && store.accessExpiresAt() - 60_000L > System.currentTimeMillis()) {
            return access;
        }
        FormBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", store.refreshToken())
                .add("client_id", CLIENT_ID)
                .build();
        try {
            JSONObject json = postToken(store.serverUrl(), body);
            saveTokens(store, json);
            return store.accessToken();
        } catch (AuthRejected rejected) {
            store.clearAuth();
            return null;
        }
    }

    /** Checks a long-lived access token against {@code /api/}. */
    static void verifyToken(String base, String token) throws IOException {
        Request req = new Request.Builder()
                .url(base + "/api/")
                .header("Authorization", "Bearer " + token)
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (resp.code() == 401 || resp.code() == 403) {
                throw new AuthRejected("Home Assistant rejected this token.");
            }
            if (!resp.isSuccessful()) {
                throw new IOException("Home Assistant answered HTTP " + resp.code() + ".");
            }
        }
    }

    /** Best-effort logout: tells Home Assistant to forget the refresh token. Blocking. */
    static void revoke(Store store) {
        if (store.usesLongLivedToken() || store.refreshToken().isEmpty()) return;
        FormBody body = new FormBody.Builder()
                .add("action", "revoke")
                .add("token", store.refreshToken())
                .build();
        Request req = new Request.Builder().url(store.serverUrl() + "/auth/token").post(body).build();
        try (Response ignored = HTTP.newCall(req).execute()) {
            // Nothing to read; the server answers 200 either way.
        } catch (IOException ignored) {
            // Offline: the token simply expires unused.
        }
    }

    private static JSONObject postToken(String base, FormBody body) throws IOException {
        Request req = new Request.Builder().url(base + "/auth/token").post(body).build();
        try (Response resp = HTTP.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String text = rb == null ? "" : rb.string();
            if (resp.code() == 400 || resp.code() == 401 || resp.code() == 403) {
                throw new AuthRejected("Home Assistant refused the login (" + resp.code() + ").");
            }
            if (!resp.isSuccessful()) {
                throw new IOException("Home Assistant answered HTTP " + resp.code() + ".");
            }
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("Unexpected reply from Home Assistant.");
            }
        }
    }

    private static void saveTokens(Store store, JSONObject json) throws IOException {
        String access = json.optString("access_token", "");
        if (access.isEmpty()) throw new IOException("Home Assistant did not return an access token.");
        long expiresIn = json.optLong("expires_in", 1800L);
        store.saveOAuthTokens(access, json.optString("refresh_token", ""),
                System.currentTimeMillis() + expiresIn * 1000L);
    }
}
