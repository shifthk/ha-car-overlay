package com.haoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Round floating button showing a card's MDI icon; colour reflects whether the card's entities are on. */
@SuppressLint("ViewConstructor")
final class FloatingCardButton extends View {

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String glyph = "";
    private boolean offline;

    FloatingCardButton(Context context) {
        super(context);
        shadow.setColor(0x40000000);
        ring.setStyle(Paint.Style.STROKE);
        glyphPaint.setTypeface(MdiIcons.typeface(context));
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        dot.setColor(0xFFB3261E);
        setClickable(true);
    }

    void setGlyph(String g) {
        if (g == null) g = "";
        if (!g.equals(glyph)) {
            glyph = g;
            invalidate();
        }
    }

    void setColors(int background, int foreground, int outline) {
        fill.setColor(background);
        glyphPaint.setColor(foreground);
        ring.setColor(outline);
        invalidate();
    }

    /** Small red dot when Home Assistant can't be reached. */
    void setOffline(boolean off) {
        if (off != offline) {
            offline = off;
            invalidate();
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        animate().scaleX(pressed ? 0.92f : 1f).scaleY(pressed ? 0.92f : 1f).setDuration(90).start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - Math.max(2f, w * 0.05f);
        float off = Math.max(1.5f, w * 0.03f);

        c.drawCircle(cx + off * 0.3f, cy + off, r, shadow);
        c.drawCircle(cx, cy, r, fill);
        ring.setStrokeWidth(Math.max(1f, w * 0.02f));
        c.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);

        glyphPaint.setTextSize(r * 1.05f);
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        c.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, glyphPaint);

        if (offline) {
            float dr = r * 0.2f;
            c.drawCircle(cx + r * 0.68f, cy - r * 0.68f, dr, dot);
        }
    }
}
