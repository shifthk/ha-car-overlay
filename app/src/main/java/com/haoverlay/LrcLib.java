package com.haoverlay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Finds lyrics, trying free sources that need no account or key, best first:
 * <ol>
 * <li>LRCLIB (lrclib.net): community database with line-by-line timing for most popular songs.</li>
 * <li>NetEase Cloud Music: large catalogue with timed lyrics, including many songs LRCLIB lacks.</li>
 * <li>lyrics.ovh: words only (no timing), as a last resort.</li>
 * </ol>
 */
final class LrcLib {

    static final class Line {
        /** When the line starts, or -1 for lyrics without timing. */
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    static final class Result {
        List<Line> lines = Collections.emptyList();
        boolean synced;
        boolean instrumental;
        /** Where the lyrics came from, e.g. "LRCLIB". */
        String source = "";

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    interface Callback {
        /** @param result {@code null} when the lookup failed (no network); an empty result when nothing was found. */
        void onResult(Result result);
    }

    private static final String BASE = "https://lrclib.net/api/";
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** A timed version is only trusted if its length is this close to the song that is playing. */
    private static final double MAX_DURATION_DIFF_S = 3.0;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void fetch(String title, String artist, String album, long durationMs, Callback cb) {
        worker.execute(() -> {
            Result r;
            try {
                r = lookup(title, artist, album, durationMs / 1000.0);
            } catch (IOException | RuntimeException e) {
                r = null;
            }
            final Result out = r;
            main.post(() -> cb.onResult(out));
        });
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private Result lookup(String title, String artist, String album, double durationS) throws IOException {
        Result best = new Result();
        IOException failure = null;
        boolean anyAnswered = false;
        try {
            best = lrclib(title, artist, album, durationS);
            if (!best.isEmpty() || best.instrumental) best.source = "LRCLIB";
            anyAnswered = true;
        } catch (IOException e) {
            failure = e;
        }
        if (best.synced || best.instrumental) return best;
        try {
            Result n = netease(title, artist, durationS);
            anyAnswered = true;
            if (n.synced || best.isEmpty()) best = better(best, n);
        } catch (IOException | RuntimeException e) {
            if (failure == null && e instanceof IOException) failure = (IOException) e;
        }
        if (!best.isEmpty()) return best;
        try {
            Result o = lyricsOvh(title, artist);
            anyAnswered = true;
            best = better(best, o);
        } catch (IOException | RuntimeException e) {
            if (failure == null && e instanceof IOException) failure = (IOException) e;
        }
        if (!anyAnswered && failure != null) throw failure;   // offline: let the caller retry later
        return best;
    }

    // ------------------------------------------------------------------ NetEase Cloud Music

    private static final String NETEASE = "https://music.163.com/api/";
    /** Song-credit lines NetEase puts at the top ("作词 : ...", "Producer : ..."), not sung words. */
    private static final Pattern CREDIT = Pattern.compile(
            "^.{0,30}?(作词|作曲|编曲|制作|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|发行|词|曲|Lyricist|Composer|Producer|Arranger|Mix|Master)[^:：]{0,12}[:：].*",
            Pattern.CASE_INSENSITIVE);

    private Result netease(String title, String artist, double durationS) throws IOException {
        String cleanTitle = cleanTitle(title);
        String first = firstArtist(artist);
        HttpUrl search = HttpUrl.parse(NETEASE + "search/get").newBuilder()
                .addQueryParameter("s", (cleanTitle + " " + first).trim())
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build();
        String body = neteaseGet(search);
        Result best = new Result();
        if (body == null) return best;
        JSONArray songs;
        try {
            JSONObject res = new JSONObject(body).optJSONObject("result");
            songs = res == null ? null : res.optJSONArray("songs");
        } catch (JSONException e) {
            return best;
        }
        if (songs == null) return best;
        String wantTitle = norm(cleanTitle);
        String wantArtist = norm(first);
        int tried = 0;
        for (int i = 0; i < songs.length() && tried < 3; i++) {
            JSONObject o = songs.optJSONObject(i);
            if (o == null) continue;
            String name = norm(cleanTitle(o.optString("name", "")));
            if (name.isEmpty() || !(name.contains(wantTitle) || wantTitle.contains(name))) continue;
            if (!wantArtist.isEmpty() && !artistMatches(o.optJSONArray("artists"), wantArtist)) continue;
            double diff = durationS > 0 ? Math.abs(o.optLong("duration", 0) / 1000.0 - durationS) : 0;
            tried++;
            HttpUrl lyric = HttpUrl.parse(NETEASE + "song/lyric").newBuilder()
                    .addQueryParameter("id", String.valueOf(o.optLong("id")))
                    .addQueryParameter("lv", "1")
                    .build();
            String lb = neteaseGet(lyric);
            if (lb == null) continue;
            String lrc;
            try {
                JSONObject j = new JSONObject(lb);
                if (j.optBoolean("nolyric", false) || j.optBoolean("uncollected", false)) continue;
                JSONObject l = j.optJSONObject("lrc");
                lrc = l == null ? "" : l.optString("lyric", "");
            } catch (JSONException e) {
                continue;
            }
            List<Line> lines = new ArrayList<>();
            for (Line line : parseLrc(lrc)) {
                if (line.timeMs < 40000 && CREDIT.matcher(line.text).matches()) continue;
                lines.add(line);
            }
            boolean hasWords = false;
            for (Line line : lines) hasWords |= !line.text.isEmpty();
            if (!hasWords) continue;
            Result r = new Result();
            r.lines = lines;
            r.source = "NetEase";
            r.synced = diff <= MAX_DURATION_DIFF_S;   // other length (live, radio edit): words only
            if (r.synced) return r;
            best = better(best, r);
        }
        return best;
    }

    private static boolean artistMatches(JSONArray artists, String want) {
        if (artists == null) return true;
        for (int i = 0; i < artists.length(); i++) {
            JSONObject a = artists.optJSONObject(i);
            String n = a == null ? "" : norm(a.optString("name", ""));
            if (!n.isEmpty() && (n.contains(want) || want.contains(n))) return true;
        }
        return false;
    }

    private String neteaseGet(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .header("Referer", "https://music.163.com/")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            ResponseBody b = resp.body();
            return b == null ? null : b.string();
        }
    }

    // ------------------------------------------------------------------ lyrics.ovh (words only)

    private Result lyricsOvh(String title, String artist) throws IOException {
        Result r = new Result();
        String first = firstArtist(artist);
        if (first.isEmpty()) return r;
        HttpUrl url = new HttpUrl.Builder().scheme("https").host("api.lyrics.ovh")
                .addPathSegment("v1").addPathSegment(first).addPathSegment(cleanTitle(title)).build();
        String body = get(url);
        if (body == null) return r;
        try {
            String text = new JSONObject(body).optString("lyrics", "").replace("\r", "");
            if (text.trim().isEmpty()) return r;
            List<Line> lines = new ArrayList<>();
            for (String l : text.split("\n")) lines.add(new Line(-1, l.trim()));
            // The first line is sometimes "Paroles de la chanson X par Y".
            if (!lines.isEmpty() && lines.get(0).text.startsWith("Paroles de la chanson")) lines.remove(0);
            r.lines = lines;
            r.source = "lyrics.ovh";
        } catch (JSONException ignored) {
        }
        return r;
    }

    // ------------------------------------------------------------------ LRCLIB

    private Result lrclib(String title, String artist, String album, double durationS) throws IOException {
        String firstArtist = firstArtist(artist);
        String cleanTitle = cleanTitle(title);
        Result best = new Result();

        // 1. Exact match on the song's own details.
        if (!artist.isEmpty()) {
            HttpUrl.Builder u = HttpUrl.parse(BASE + "get").newBuilder()
                    .addQueryParameter("track_name", title)
                    .addQueryParameter("artist_name", artist);
            if (!album.isEmpty()) u.addQueryParameter("album_name", album);
            if (durationS > 0) u.addQueryParameter("duration", String.valueOf(Math.round(durationS)));
            String body = get(u.build());
            if (body != null) {
                try {
                    best = better(best, toResult(new JSONObject(body)));
                } catch (JSONException ignored) {
                }
            }
        }
        if (best.synced) return best;

        // 2. Search by title + main artist (Spotify lists every featured artist; the database often has one).
        HttpUrl.Builder s = HttpUrl.parse(BASE + "search").newBuilder().addQueryParameter("track_name", cleanTitle);
        if (!firstArtist.isEmpty()) s.addQueryParameter("artist_name", firstArtist);
        best = better(best, pick(get(s.build()), cleanTitle, durationS));
        if (best.synced) return best;

        // 3. Loose keyword search.
        HttpUrl q = HttpUrl.parse(BASE + "search").newBuilder()
                .addQueryParameter("q", (cleanTitle + " " + firstArtist).trim())
                .build();
        return better(best, pick(get(q), cleanTitle, durationS));
    }

    /** @return the response body, or {@code null} for "not found". */
    private String get(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", "HAOverlay/1.1 (Android)").build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 404 || resp.code() == 400) return null;
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            ResponseBody b = resp.body();
            return b == null ? null : b.string();
        }
    }

    /** Best entry of a search response: timed lyrics whose length matches, else untimed lyrics. */
    private static Result pick(String body, String wantTitle, double durationS) {
        Result best = new Result();
        if (body == null) return best;
        JSONArray arr;
        try {
            arr = new JSONArray(body);
        } catch (JSONException e) {
            return best;
        }
        String want = norm(wantTitle);
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = norm(cleanTitle(o.optString("trackName", "")));
            if (name.isEmpty() || !(name.contains(want) || want.contains(name))) continue;
            double diff = durationS > 0 ? Math.abs(o.optDouble("duration", 0) - durationS) : 0;
            Result r = toResult(o);
            if (r.synced) {
                if (diff <= MAX_DURATION_DIFF_S && (!best.synced || diff < bestDiff)) {
                    best = r;
                    bestDiff = diff;
                } else if (!best.synced && best.isEmpty()) {
                    // Wrong length, so the timing can't be trusted: keep the words only.
                    r.synced = false;
                    best = r;
                }
            } else {
                best = better(best, r);
            }
        }
        return best;
    }

    private static Result better(Result a, Result b) {
        if (b == null) return a;
        if (a.synced) return a;
        if (b.synced) return b;
        if (a.isEmpty() && (!b.isEmpty() || b.instrumental)) return b;
        return a;
    }

    private static Result toResult(JSONObject o) {
        Result r = new Result();
        r.instrumental = o.optBoolean("instrumental", false);
        String synced = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        List<Line> timed = parseLrc(synced);
        if (!timed.isEmpty()) {
            r.lines = timed;
            r.synced = true;
            return r;
        }
        String plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        if (!plain.trim().isEmpty()) {
            List<Line> lines = new ArrayList<>();
            for (String l : plain.split("\n")) lines.add(new Line(-1, l.trim()));
            r.lines = lines;
        }
        return r;
    }

    /** Parses {@code [mm:ss.xx] words} lines; a line may carry several timestamps. */
    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return out;
        for (String raw : lrc.split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    long f = Long.parseLong(frac);
                    ms += frac.length() == 1 ? f * 100 : frac.length() == 2 ? f * 10 : f;
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    private static String firstArtist(String artist) {
        String a = artist;
        for (String sep : new String[]{",", ";", " & ", " feat", " ft.", " x "}) {
            int i = a.toLowerCase(Locale.ROOT).indexOf(sep);
            if (i > 0) a = a.substring(0, i);
        }
        return a.trim();
    }

    /** Drops "- Remastered 2011", "(feat. X)", "[Live]" and similar additions. */
    private static String cleanTitle(String title) {
        String t = title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        t = t.trim();
        return t.isEmpty() ? title.trim() : t;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
