package com.gen5w.launcher.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Modular circular radial gauge rendered cleanly on Canvas for EV/12V % telemetry tiles.
 */
public final class CircularRingGaugeView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float progressPct = 0f;
    private String centerText = "--%";
    private int accentColor;
    private float textSizeOverridePx = 0f;

    public CircularRingGaugeView(Context context, int accentColor) {
        super(context);
        this.accentColor = accentColor;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0x44334155);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(accentColor);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setStyleOptions(Typeface typeface, int textColor, float textSizePx, int trackColor) {
        if (typeface != null) {
            valuePaint.setTypeface(typeface);
        }
        if (textColor != 0) {
            valuePaint.setColor(textColor);
        }
        if (trackColor != 0) {
            trackPaint.setColor(trackColor);
        }
        textSizeOverridePx = textSizePx;
        invalidate();
    }

    public void setTelemetry(float pct, String displayText, boolean charging) {
        float nextPct = pct < 0f ? 0f : Math.max(0f, Math.min(100f, pct));
        String nextText = displayText == null ? "--" : displayText;
        int nextColor = charging ? 0xFF10B981 : accentColor;
        if (Math.abs(nextPct - this.progressPct) < 0.01f
                && nextText.equals(this.centerText)
                && nextColor == arcPaint.getColor()) {
            return;
        }
        this.progressPct = nextPct;
        this.centerText = nextText;
        arcPaint.setColor(nextColor);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        if (size <= 12) {
            return;
        }
        float stroke = Math.max(6f, size * 0.11f);
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        float pad = stroke * 0.6f;
        float left = (w - size) * 0.5f + pad;
        float top = (h - size) * 0.5f + pad;
        oval.set(left, top, left + size - pad * 2f, top + size - pad * 2f);

        canvas.drawArc(oval, -90f, 360f, false, trackPaint);
        float sweep = (progressPct / 100f) * 360f;
        if (sweep > 1f) {
            canvas.drawArc(oval, -90f, sweep, false, arcPaint);
        }

        valuePaint.setTextSize(textSizeOverridePx > 0f ? textSizeOverridePx : Math.max(14f, size * 0.24f));
        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        float textY = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(centerText, w * 0.5f, textY, valuePaint);
    }
}
