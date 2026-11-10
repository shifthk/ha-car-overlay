package com.haoverlay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the app remembers between launches: server and tokens, the chosen dashboard,
 * cached dashboard data (so the card list shows instantly and offline), the pinned cards,
 * overlay settings and where each floating button was dragged to.
 */
final class Store {

    private static final String FILE = "ha_overlay";

    private static final String K_SERVER = "server_url";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES = "access_expires_at";
    private static final String K_LLAT = "uses_long_lived_token";

    private static final String K_DASH_PATH = "dashboard_path";
    private static final String K_DASH_TITLE = "dashboard_title";
    private static final String K_DASHBOARDS = "cached_dashboards";
    private static final String K_CONFIG_PREFIX = "cached_config_";

    private static final String K_SELECTED = "selected_cards";
    private static final String K_OVERLAY_ENABLED = "overlay_enabled";
    private static final String K_START_ON_BOOT = "start_on_boot";
    private static final String K_CLOSE_OUTSIDE = "close_outside";
    private static final String K_FAB_SIZE = "fab_size_dp";
    private static final String K_PANEL_WIDTH = "panel_width_dp";
    private static final String K_POS_PREFIX = "pos_";

    private final SharedPreferences sp;

    Store(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ server + auth

    /** Turns what the user typed into a base URL like {@code https://host:8123} (no trailing slash). */
    static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";
        if (!url.contains("://")) {
            url = (url.toLowerCase(Locale.ROOT).contains("nabu.casa") ? "https://" : "http://") + url;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    String serverUrl() {
        return sp.getString(K_SERVER, "");
    }

    void setServerUrl(String url) {
        sp.edit().putString(K_SERVER, normalizeUrl(url)).apply();
    }

    boolean isLoggedIn() {
        if (serverUrl().isEmpty()) return false;
        if (usesLongLivedToken()) return !accessToken().isEmpty();
        return !refreshToken().isEmpty();
    }

    boolean usesLongLivedToken() {
        return sp.getBoolean(K_LLAT, false);
    }

    String accessToken() {
        return sp.getString(K_ACCESS, "");
    }

    String refreshToken() {
        return sp.getString(K_REFRESH, "");
    }

    long accessExpiresAt() {
        return sp.getLong(K_EXPIRES, 0L);
    }

    void saveOAuthTokens(String access, String refresh, long expiresAtMs) {
        SharedPreferences.Editor e = sp.edit()
                .putString(K_ACCESS, access)
                .putLong(K_EXPIRES, expiresAtMs)
                .putBoolean(K_LLAT, false);
        if (refresh != null && !refresh.isEmpty()) e.putString(K_REFRESH, refresh);
        e.apply();
    }

    void saveLongLivedToken(String token) {
        sp.edit()
                .putString(K_ACCESS, token.trim())
                .putString(K_REFRESH, "")
                .putLong(K_EXPIRES, Long.MAX_VALUE)
                .putBoolean(K_LLAT, true)
                .apply();
    }

    /** Forces the next request to refresh the short-lived access token. */
    void expireAccessToken() {
        if (!usesLongLivedToken()) sp.edit().putLong(K_EXPIRES, 0L).apply();
    }

    void clearAuth() {
        sp.edit()
                .remove(K_ACCESS)
                .remove(K_REFRESH)
                .remove(K_EXPIRES)
                .remove(K_LLAT)
                .apply();
    }

    // ------------------------------------------------------------------ dashboards

    boolean hasChosenDashboard() {
        return sp.contains(K_DASH_PATH);
    }

    /** {@code ""} means the default "Overview" dashboard. */
    String dashboardPath() {
        return sp.getString(K_DASH_PATH, "");
    }

    String dashboardTitle() {
        return sp.getString(K_DASH_TITLE, "");
    }

    void setDashboard(String path, String title) {
        sp.edit().putString(K_DASH_PATH, path == null ? "" : path).putString(K_DASH_TITLE, title).apply();
    }

    String cachedDashboards() {
        return sp.getString(K_DASHBOARDS, "");
    }

    void setCachedDashboards(String json) {
        sp.edit().putString(K_DASHBOARDS, json).apply();
    }

    String cachedConfig(String path) {
        return sp.getString(K_CONFIG_PREFIX + path, "");
    }

    void setCachedConfig(String path, String json) {
        sp.edit().putString(K_CONFIG_PREFIX + path, json).apply();
    }

    // ------------------------------------------------------------------ pinned cards

    List<CardInfo> selectedCards() {
        List<CardInfo> out = new ArrayList<>();
        String raw = sp.getString(K_SELECTED, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                CardInfo c = CardInfo.fromJson(o);
                if (c != null) out.add(c);
            }
        } catch (JSONException ignored) {
            // Corrupt value: behave as if nothing is pinned.
        }
        return out;
    }

    void setSelectedCards(List<CardInfo> cards) {
        JSONArray arr = new JSONArray();
        for (CardInfo c : cards) arr.put(c.toJson());
        sp.edit().putString(K_SELECTED, arr.toString()).apply();
    }

    // ------------------------------------------------------------------ overlay settings

    boolean overlayEnabled() {
        return sp.getBoolean(K_OVERLAY_ENABLED, false);
    }

    void setOverlayEnabled(boolean on) {
        sp.edit().putBoolean(K_OVERLAY_ENABLED, on).apply();
    }

    boolean startOnBoot() {
        return sp.getBoolean(K_START_ON_BOOT, true);
    }

    void setStartOnBoot(boolean on) {
        sp.edit().putBoolean(K_START_ON_BOOT, on).apply();
    }

    boolean closeOnOutside() {
        return sp.getBoolean(K_CLOSE_OUTSIDE, true);
    }

    void setCloseOnOutside(boolean on) {
        sp.edit().putBoolean(K_CLOSE_OUTSIDE, on).apply();
    }

    int fabSizeDp() {
        return sp.getInt(K_FAB_SIZE, 64);
    }

    void setFabSizeDp(int dp) {
        sp.edit().putInt(K_FAB_SIZE, dp).apply();
    }

    int panelWidthDp() {
        return sp.getInt(K_PANEL_WIDTH, 400);
    }

    void setPanelWidthDp(int dp) {
        sp.edit().putInt(K_PANEL_WIDTH, dp).apply();
    }

    // ------------------------------------------------------------------ lyrics

    boolean lyricsEnabled() {
        return sp.getBoolean("lyrics_enabled", false);
    }

    void setLyricsEnabled(boolean on) {
        sp.edit().putBoolean("lyrics_enabled", on).apply();
    }

    int lyricsTextSp() {
        return sp.getInt("lyrics_text_sp", 26);
    }

    void setLyricsTextSp(int size) {
        sp.edit().putInt("lyrics_text_sp", size).apply();
    }

    /** Manual timing correction: positive shows lyrics earlier. */
    long lyricsOffsetMs() {
        return sp.getLong("lyrics_offset_ms", 0L);
    }

    void setLyricsOffsetMs(long ms) {
        sp.edit().putLong("lyrics_offset_ms", ms).apply();
    }

    /** True when there is at least one floating button to show (a pinned card or the lyrics button). */
    boolean hasButtons() {
        return lyricsEnabled() || !selectedCards().isEmpty();
    }

    /** Saved top-left of a card's floating button, or {@code null} if it was never moved. */
    int[] fabPosition(String cardId) {
        String v = sp.getString(K_POS_PREFIX + cardId, "");
        int comma = v.indexOf(',');
        if (comma <= 0) return null;
        try {
            return new int[]{Integer.parseInt(v.substring(0, comma)), Integer.parseInt(v.substring(comma + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void setFabPosition(String cardId, int x, int y) {
        sp.edit().putString(K_POS_PREFIX + cardId, x + "," + y).apply();
    }

    void resetPositions() {
        SharedPreferences.Editor e = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(K_POS_PREFIX)) e.remove(key);
        }
        e.apply();
    }
}
