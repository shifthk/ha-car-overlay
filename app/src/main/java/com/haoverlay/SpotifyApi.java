package com.haoverlay;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Spotify's Web API: what the signed-in account is playing on any device (car, phone or speaker),
 * with the playback position. Sign-in is the authorization-code flow with PKCE, so only the user's
 * own Client ID is needed (made at developer.spotify.com), never a secret.
 */
final class SpotifyApi {

    /** Must be added, exactly, as a Redirect URI in the Spotify app's settings. Never actually loaded. */
    static final String REDIRECT_URI = "http://127.0.0.1:8888/haoverlay-callback";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";
    private static final String ACCOUNTS = "https://accounts.spotify.com";
    private static final String API = "https://api.spotify.com/v1";

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** The server refused this app's sign-in; the user has to connect again. */
    static final class Rejected extends IOException {
        Rejected(String msg) {
            super(msg);
        }
    }

    private SpotifyApi() {
    }

    // ------------------------------------------------------------------ sign-in

    static String newVerifier() {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        return b64(b);
    }

    static String authorizeUrl(String clientId, String verifier, String state) {
        String challenge;
        try {
            challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return Uri.parse(ACCOUNTS + "/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("state", state)
                .build().toString();
    }

    /** Blocking. Swaps the code from the redirect for tokens and saves them. */
    static void exchangeCode(Store store, String code, String verifier) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", store.spotifyClientId())
                .add("code_verifier", verifier)
                .build();
        save(store, postToken(body));
    }

    private static synchronized String accessToken(Store store) throws IOException {
        if (!store.spotifyConnected()) return null;
        if (store.spotifyExpiresAt() - 60_000L > System.currentTimeMillis()) return store.spotifyAccess();
        FormBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", store.spotifyRefresh())
                .add("client_id", store.spotifyClientId())
                .build();
        try {
            save(store, postToken(body));
        } catch (Rejected r) {
            store.clearSpotify();
            throw r;
        }
        return store.spotifyAccess();
    }

    private static JSONObject postToken(FormBody body) throws IOException {
        Request req = new Request.Builder().url(ACCOUNTS + "/api/token").post(body).build();
        try (Response resp = HTTP.newCall(req).execute()) {
            String text = bodyText(resp);
            if (resp.code() == 400 || resp.code() == 401) {
                throw new Rejected("Spotify refused the sign-in (" + errorText(text, resp.code()) + ").");
            }
            if (!resp.isSuccessful()) throw new IOException("Spotify answered HTTP " + resp.code() + ".");
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("Unexpected reply from Spotify.");
            }
        }
    }

    private static void save(Store store, JSONObject json) throws IOException {
        String access = json.optString("access_token", "");
        if (access.isEmpty()) throw new IOException("Spotify did not return an access token.");
        String refresh = json.optString("refresh_token", "");
        if (refresh.isEmpty()) refresh = store.spotifyRefresh();   // refreshes may keep the old one
        store.saveSpotifyTokens(access, refresh,
                System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L);
    }

    // ------------------------------------------------------------------ now playing

    /**
     * Blocking. The song the account is playing or has paused, or {@code null} when nothing is.
     *
     * @throws IOException with a readable message (offline, signed out, account not allowed, ...)
     */
    static NowPlaying.Track currentlyPlaying(Store store) throws IOException {
        String token = accessToken(store);
        if (token == null) return null;
        Request req = new Request.Builder()
                .url(API + "/me/player/currently-playing?additional_types=episode")
                .header("Authorization", "Bearer " + token)
                .build();
        long sentAt = SystemClock.elapsedRealtime();
        try (Response resp = HTTP.newCall(req).execute()) {
            long gotAt = SystemClock.elapsedRealtime();
            String text = bodyText(resp);
            if (resp.code() == 204 || text.isEmpty()) return null;
            if (resp.code() == 401) {
                store.expireSpotifyAccess();
                throw new IOException("Spotify sign-in expired; retrying.");
            }
            if (resp.code() == 403) {
                throw new IOException("Spotify refused (" + errorText(text, 403) + "). In the Spotify developer "
                        + "dashboard, add your Spotify account's email under User Management.");
            }
            if (resp.code() == 429) throw new IOException("Spotify asked us to slow down.");
            if (!resp.isSuccessful()) throw new IOException("Spotify answered HTTP " + resp.code() + ".");

            JSONObject j = new JSONObject(text);
            JSONObject item = j.optJSONObject("item");
            if (item == null) return null;
            NowPlaying.Track t = new NowPlaying.Track();
            t.fromSpotifyApi = true;
            t.title = item.optString("name", "").trim();
            if (t.title.isEmpty()) return null;
            t.durationMs = item.optLong("duration_ms", 0);
            JSONArray artists = item.optJSONArray("artists");
            if (artists != null && artists.length() > 0) {
                t.artist = artists.getJSONObject(0).optString("name", "").trim();
            } else {
                JSONObject show = item.optJSONObject("show");
                if (show != null) t.artist = show.optString("publisher", "").trim();
            }
            JSONObject album = item.optJSONObject("album");
            if (album != null) t.album = album.optString("name", "").trim();
            t.playing = j.optBoolean("is_playing", false);
            if (j.has("progress_ms") && !j.isNull("progress_ms")) {
                t.positionMs = j.optLong("progress_ms", 0);
                t.atRealtime = (sentAt + gotAt) / 2;   // the position was read about halfway through the request
                t.positionKnown = true;
            }
            JSONObject device = j.optJSONObject("device");
            if (device != null) t.device = device.optString("name", "");
            return t;
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Spotify.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String bodyText(Response resp) throws IOException {
        ResponseBody rb = resp.body();
        return rb == null ? "" : rb.string().trim();
    }

    private static String errorText(String body, int code) {
        try {
            JSONObject j = new JSONObject(body);
            Object e = j.opt("error");
            if (e instanceof JSONObject) return ((JSONObject) e).optString("message", "HTTP " + code);
            String d = j.optString("error_description", "");
            if (!d.isEmpty()) return d;
            if (e != null) return e.toString();
        } catch (JSONException ignored) {
        }
        return body.isEmpty() || body.length() > 120 ? "HTTP " + code : body;
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
