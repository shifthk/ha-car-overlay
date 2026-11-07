package com.haoverlay;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Design Icons — the same icon set Home Assistant uses ({@code mdi:lightbulb} etc.).
 * The font and a name→codepoint table are bundled in assets.
 */
final class MdiIcons {

    private static final String TAG = "MdiIcons";
    private static Typeface typeface;
    private static Map<String, Integer> codepoints;

    private MdiIcons() {
    }

    static synchronized Typeface typeface(Context c) {
        load(c);
        return typeface;
    }

    /**
     * @param name {@code "mdi:lightbulb"} or {@code "lightbulb"}
     * @return the glyph as a string, or {@code null} for unknown names / non-mdi icon sets
     */
    static synchronized String glyph(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        load(c);
        String key = name;
        int colon = key.indexOf(':');
        if (colon >= 0) {
            if (!key.startsWith("mdi:")) return null;
            key = key.substring(colon + 1);
        }
        Integer cp = codepoints.get(key);
        return cp == null ? null : new String(Character.toChars(cp));
    }

    /** First icon name that exists, falling back to the Home Assistant logo. */
    static String glyphOf(Context c, String... names) {
        for (String n : names) {
            String g = glyph(c, n);
            if (g != null) return g;
        }
        String g = glyph(c, "home-assistant");
        return g == null ? "?" : g;
    }

    private static void load(Context c) {
        if (typeface != null) return;
        Context app = c.getApplicationContext();
        typeface = Typeface.createFromAsset(app.getAssets(), "mdi.ttf");
        codepoints = new HashMap<>(9000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                app.getAssets().open("mdi_map.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                try {
                    codepoints.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1).trim(), 16));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Icon table missing", e);
        }
    }
}
