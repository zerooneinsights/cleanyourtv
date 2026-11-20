package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.Calendar;

/**
 * Home's flip clock: the hours and the minutes each on a dark rounded card split across the middle, light digits;
 * when a value changes its top half folds down over the old one, as on a split-flap clock. Follows the TV's
 * 12/24-hour setting and checks the time every second while attached.
 */
public class FlipClockView extends View {
    private static final long FLIP_MS = 600;
    private static final int CARD = 0xFF2A2F37;
    private static final int CARD_DARK = 0xFF22262D;
    private static final int INK = 0xFFE6E8EB;
    private static final int SEAM = 0xFF111317;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private final String[] shown = {"", ""}, previous = {"", ""};
    private final float[] progress = {1f, 1f};
    private final ValueAnimator[] flips = new ValueAnimator[2];
    /** android:tag="seconds": one small card with the seconds instead of the hours and minutes. */
    private final boolean seconds;
    private float cardW, cardH, gap, radius;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update(true);
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    public FlipClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        seconds = "seconds".equals(getTag());
        setCardHeight((seconds ? 22 : 44) * d);
        Typeface face = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        try {
            face = getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The condensed system font is fine.
        }
        ink.setTypeface(face);
        ink.setColor(INK);
        ink.setTextAlign(Paint.Align.CENTER);
        seam.setColor(SEAM);
        update(false);
    }

    /** The cards' height in pixels; their width and the digits follow it. */
    void setCardHeight(float px) {
        cardH = px;
        cardW = px * 1.12f;
        gap = px * 0.12f;
        radius = px * 0.14f;
        ink.setTextSize(px * 0.78f);
        seam.setStrokeWidth(Math.max(1f, px * 0.025f));
        requestLayout();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update(false);
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        for (ValueAnimator a : flips) if (a != null) a.cancel();
        super.onDetachedFromWindow();
    }

    private void update(boolean animate) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        if (seconds) {
            set(0, String.format("%02d", now.get(Calendar.SECOND)), animate);
            return;
        }
        String hh = h24 ? String.format("%02d", hour) : String.valueOf(hour);
        String mm = String.format("%02d", now.get(Calendar.MINUTE));
        set(0, hh, animate);
        set(1, mm, animate);
    }

    private void set(int i, String value, boolean animate) {
        if (value.equals(shown[i])) return;
        previous[i] = shown[i].isEmpty() ? value : shown[i];
        shown[i] = value;
        if (flips[i] != null) flips[i].cancel();
        if (!animate || !isAttachedToWindow()) {
            progress[i] = 1f;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(seconds ? 420 : FLIP_MS);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> {
            progress[i] = (float) v.getAnimatedValue();
            invalidate();
        });
        flips[i] = a;
        a.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(seconds ? cardW : cardW * 2 + gap), Math.round(cardH));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < (seconds ? 1 : 2); i++) drawCard(canvas, i * (cardW + gap), i);
    }

    private void drawCard(Canvas c, float left, int i) {
        float mid = cardH / 2f;
        float p = progress[i];
        String now = shown[i], old = previous[i];
        // Top half: the new value; bottom half: the old one until the flap has come down past the middle.
        half(c, left, true, now, CARD, 1f);
        half(c, left, false, p >= 1f ? now : old, CARD_DARK, 1f);
        if (p < 1f) {
            if (p < 0.5f) half(c, left, true, old, CARD, 1f - p * 2f);          // the old top folding down
            else half(c, left, false, now, CARD_DARK, (p - 0.5f) * 2f);         // the new bottom unfolding
        }
        c.drawLine(left, mid, left + cardW, mid, seam);
    }

    /** One half card, squashed towards the middle by {@code scale} (1 = flat on its half). */
    private void half(Canvas c, float left, boolean top, String text, int color, float scale) {
        if (scale <= 0f) return;
        float mid = cardH / 2f;
        int save = c.save();
        c.translate(0, mid);
        c.scale(1f, scale);
        c.translate(0, -mid);
        if (top) c.clipRect(left, 0, left + cardW, mid);
        else c.clipRect(left, mid, left + cardW, cardH);
        card.setColor(color);
        rect.set(left, 0, left + cardW, cardH);
        c.drawRoundRect(rect, radius, radius, card);
        if (scale < 1f) {                                   // a little shade on the moving flap
            card.setColor(Color.argb(Math.round(90 * (1f - scale)), 0, 0, 0));
            c.drawRoundRect(rect, radius, radius, card);
        }
        Paint.FontMetrics fm = ink.getFontMetrics();
        float baseline = mid - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, left + cardW / 2f, baseline, ink);
        c.restoreToCount(save);
    }
}
