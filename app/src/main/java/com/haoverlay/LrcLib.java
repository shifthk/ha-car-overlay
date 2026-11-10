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
 * Looks up lyrics on LRCLIB (lrclib.net), a free community lyrics database that has
 * line-by-line timestamps for most popular songs. No account or key is needed.
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
