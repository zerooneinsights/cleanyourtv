package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.style.LeadingMarginSpan;
import android.text.style.ReplacementSpan;

/**
 * Small text pieces Home's cards share: a rounded pill behind a word (the AQI, the LIVE badge), a coloured bar down the
 * side of a paragraph (calendar events), and an icon centred on the text line.
 */
final class HomeSpans {
    private HomeSpans() {
    }

    /** The text in a rounded pill: a faint fill and a thin outline in {@code color}, the text itself in {@code ink}. */
    static final class Pill extends ReplacementSpan {
        private final int fill, stroke, ink;
        private final float padX, radius, strokeWidth, scale;

        Pill(Context ctx, int fill, int stroke, int ink, float scale) {
            float d = ctx.getResources().getDisplayMetrics().density;
            this.fill = fill;
            this.stroke = stroke;
            this.ink = ink;
            this.scale = scale;
            padX = 9 * d;
            radius = 100 * d;
            strokeWidth = 1.2f * d;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float old = paint.getTextSize();
            paint.setTextSize(old * scale);
            int w = Math.round(paint.measureText(text, start, end) + 2 * padX);
            paint.setTextSize(old);
            return w;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         Paint paint) {
            float old = paint.getTextSize();
            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            paint.setTextSize(old * scale);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float w = paint.measureText(text, start, end) + 2 * padX;
            float textH = fm.descent - fm.ascent;
            float h = textH * 1.25f;
            float centre = y + (fm.ascent + fm.descent) / 2f;
            RectF r = new RectF(x, centre - h / 2f, x + w, centre + h / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(r, radius, radius, paint);
            if (stroke != 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);
                paint.setColor(stroke);
                r.inset(strokeWidth / 2f, strokeWidth / 2f);
                canvas.drawRoundRect(r, radius, radius, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ink);
            canvas.drawText(text, start, end, x + padX, y, paint);
            paint.setTextSize(old);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
        }
    }

    /** A rounded bar down the paragraph's leading edge, then a gap before its text. */
    static final class Bar implements LeadingMarginSpan {
        private final int color;
        private final float width, gap;

        Bar(Context ctx, int color) {
            float d = ctx.getResources().getDisplayMetrics().density;
            this.color = color;
            width = 3 * d;
            gap = 10 * d;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return Math.round(width + gap);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            int oldColor = p.getColor();
            Paint.Style oldStyle = p.getStyle();
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            float inset = (bottom - top) * 0.12f;
            c.drawRoundRect(new RectF(x, top + inset, x + dir * width, bottom - inset), width, width, p);
            p.setColor(oldColor);
            p.setStyle(oldStyle);
        }
    }

    /** An icon (a white vector drawable, tinted) the height of the text times {@code scale}, centred on the line. */
    static final class Icon extends ReplacementSpan {
        private final Drawable icon;
        private final float scale, gap;

        Icon(Context ctx, int res, int tint, float scale) {
            icon = ctx.getResources().getDrawable(res, null).mutate();
            icon.setTint(tint);
            this.scale = scale;
            gap = 6 * ctx.getResources().getDisplayMetrics().density;
        }

        private int side(Paint paint) {
            return Math.round(paint.getTextSize() * scale);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(side(paint) + gap);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         Paint paint) {
            int s = side(paint);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float centre = y + (fm.ascent + fm.descent) / 2f;
            int t = Math.round(centre - s / 2f);
            icon.setBounds(Math.round(x), t, Math.round(x) + s, t + s);
            icon.draw(canvas);
        }
    }
}
