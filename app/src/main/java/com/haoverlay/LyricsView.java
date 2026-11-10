package com.haoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * Shows lyrics with the line being sung highlighted and kept in the middle, scrolling smoothly as the
 * song plays. It runs off a playback clock (position at a known moment + speed), so it stays in step
 * between updates. Lyrics without timing scroll gradually over the length of the song instead.
 */
@SuppressLint("ViewConstructor")
final class LyricsView extends View {

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final int activeColor;
    private final int dimColor;
    private final int pad;
    private final int gap;

    private List<LrcLib.Line> lines = Collections.emptyList();
    private boolean synced;
    private StaticLayout[] layouts = new StaticLayout[0];
    private int[] tops = new int[0];
    private int contentHeight;

    private String message = "";
    private StaticLayout messageLayout;

    private long basePositionMs;
    private long baseRealtime;
    private float speed = 1f;
    private boolean playing;
    private long durationMs;
    private long offsetMs;

    private float scroll;
    private boolean scrollReady;
    private long lastFrame;

    LyricsView(Context context, int activeColor, int dimColor) {
        super(context);
        this.activeColor = activeColor;
        this.dimColor = dimColor;
        float d = context.getResources().getDisplayMetrics().density;
        pad = Math.round(16 * d);
        gap = Math.round(14 * d);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setTextSizeSp(26);
    }

    void setTextSizeSp(int sp) {
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        rebuild();
    }

    void setLyrics(List<LrcLib.Line> newLines, boolean isSynced) {
        lines = newLines == null ? Collections.<LrcLib.Line>emptyList() : newLines;
        synced = isSynced;
        message = "";
        scrollReady = false;
        rebuild();
    }

    /** Replaces the lyrics with a short centred note such as "Looking for lyrics…". */
    void setMessage(String text) {
        if (lines.isEmpty() && text.equals(message)) return;
        lines = Collections.emptyList();
        message = text;
        rebuild();
    }

    void setClock(long positionMs, long atRealtime, float playbackSpeed, boolean isPlaying, long trackDurationMs) {
        basePositionMs = positionMs;
        baseRealtime = atRealtime;
        speed = playbackSpeed;
        playing = isPlaying;
        durationMs = trackDurationMs;
        invalidate();
    }

    /** Positive values show each line earlier, negative later. */
    void setOffsetMs(long ms) {
        offsetMs = ms;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) rebuild();
    }

    private void rebuild() {
        int width = getWidth() - 2 * pad;
        if (width <= 0) return;
        messageLayout = message.isEmpty() ? null : layout(message, width);
        layouts = new StaticLayout[lines.size()];
        tops = new int[lines.size()];
        int y = 0;
        for (int i = 0; i < layouts.length; i++) {
            String text = lines.get(i).text;
            layouts[i] = layout(text.isEmpty() ? "♪" : text, width);
            tops[i] = y;
            y += layouts[i].getHeight() + gap;
        }
        contentHeight = Math.max(0, y - gap);
        scrollReady = false;
        invalidate();
    }

    private StaticLayout layout(String text, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.1f)
                .build();
    }

    private long position() {
        long p = basePositionMs;
        if (playing) p += (long) ((SystemClock.elapsedRealtime() - baseRealtime) * speed);
        return p + offsetMs;
    }

    /** Index of the last line that has started, or -1 before the first one. */
    private int lineAt(long positionMs) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    @Override
    protected void onDraw(Canvas c) {
        int h = getHeight();
        if (layouts.length == 0) {
            if (messageLayout != null) {
                paint.setColor(dimColor);
                c.save();
                c.translate(pad, (h - messageLayout.getHeight()) / 2f);
                messageLayout.draw(c);
                c.restore();
            }
            return;
        }

        long pos = position();
        int active = -1;
        float target;
        long nextChangeMs = 500;
        if (synced) {
            active = lineAt(pos);
            int focus = Math.max(active, 0);
            target = tops[focus] + layouts[focus].getHeight() / 2f - h / 2f;
            if (active + 1 < lines.size()) nextChangeMs = lines.get(active + 1).timeMs - pos;
        } else if (contentHeight <= h) {
            target = (contentHeight - h) / 2f;
        } else {
            float frac = durationMs > 0 ? Math.max(0f, Math.min(1f, pos / (float) durationMs)) : 0f;
            target = Math.max(0f, Math.min(contentHeight - h, frac * contentHeight - h / 2f));
        }

        long now = SystemClock.uptimeMillis();
        if (!scrollReady) {
            scroll = target;
            scrollReady = true;
        } else {
            float dt = Math.min(100, now - lastFrame);
            scroll += (target - scroll) * (1f - (float) Math.exp(-dt / 140f));
        }
        lastFrame = now;

        for (int i = 0; i < layouts.length; i++) {
            float y = tops[i] - scroll;
            if (y + layouts[i].getHeight() < 0) continue;
            if (y > h) break;
            paint.setColor(!synced || i == active ? activeColor : dimColor);
            c.save();
            c.translate(pad, y);
            layouts[i].draw(c);
            c.restore();
        }

        // Redraw every frame only while gliding to a new line; otherwise wake up for the next line.
        removeCallbacks(redraw);
        if (Math.abs(target - scroll) > 0.5f) {
            postInvalidateOnAnimation();
        } else if (playing) {
            long wait = (long) (nextChangeMs / Math.max(0.25f, speed));
            postDelayed(redraw, Math.max(16, Math.min(1000, wait)));
        }
    }

    private final Runnable redraw = this::invalidate;

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(redraw);
        super.onDetachedFromWindow();
    }
}
