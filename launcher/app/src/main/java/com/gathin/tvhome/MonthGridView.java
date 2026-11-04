package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * A month as a 7-column grid, weeks starting on Sunday: today in a filled circle, days with events marked by a dot.
 * On Home it only draws (not focusable). In CalendarActivity it is focusable and moves a selected day with the
 * D-pad, crossing into the next or previous month; Up from the first week leaves it so focus reaches the header.
 */
public final class MonthGridView extends View {
    interface Listener {
        void onSelected(LocalDate day);
    }

    private static final int TEXT = 0xE6FFFFFF;
    private static final int WEEKEND = 0x99FFFFFF;
    private static final int HEADER = 0x80FFFFFF;
    private static final int DOT = 0xFF60A5FA;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private YearMonth month = YearMonth.now();
    private LocalDate today = LocalDate.now();
    private LocalDate selected;
    private Set<LocalDate> eventDays = Collections.emptySet();
    private float cell;
    private Listener listener;

    public MonthGridView(Context ctx) {
        this(ctx, null);
    }

    public MonthGridView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        cell = 26 * getResources().getDisplayMetrics().density;
        Typeface face = null;
        try {
            face = getResources().getFont(R.font.outfit_regular);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        if (face != null) text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        setFocusable(false);
    }

    void setCellSize(float px) {
        cell = px;
        requestLayout();
        invalidate();
    }

    void setMonth(YearMonth month, LocalDate today, Set<LocalDate> eventDays) {
        this.month = month;
        this.today = today;
        this.eventDays = eventDays == null ? Collections.emptySet() : eventDays;
        requestLayout();
        invalidate();
    }

    YearMonth month() {
        return month;
    }

    /** Makes the grid focusable with a selected day; the month follows the selection. */
    void setInteractive(LocalDate selected, Listener listener) {
        this.selected = selected;
        this.listener = listener;
        this.month = YearMonth.from(selected);
        setFocusable(true);
        setFocusableInTouchMode(true);
        invalidate();
    }

    void select(LocalDate day) {
        selected = day;
        month = YearMonth.from(day);
        requestLayout();
        invalidate();
        if (listener != null) listener.onSelected(day);
    }

    /** Week rows this month needs: 4 to 6, so a short month leaves no empty row at the bottom. */
    private int weeks() {
        return (column(month.atDay(1).getDayOfWeek()) + month.lengthOfMonth() + 6) / 7;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(cell * 7), Math.round(cell * 0.8f + cell * weeks()));
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (selected == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                select(selected.minusDays(1));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                select(selected.plusDays(1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                select(selected.plusWeeks(1));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row(selected) == 0) return false;   // let focus move up to the month and year buttons
                select(selected.minusWeeks(1));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                select(selected.minusMonths(1));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                select(selected.plusMonths(1));
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Sunday-first column of a weekday: Sunday 0 .. Saturday 6. */
    private static int column(DayOfWeek d) {
        return d.getValue() % 7;
    }

    private int row(LocalDate day) {
        int offset = column(month.atDay(1).getDayOfWeek());
        return (offset + day.getDayOfMonth() - 1) / 7;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float header = cell * 0.8f;
        text.setTextSize(cell * 0.36f);
        text.setColor(HEADER);
        for (int i = 0; i < 7; i++) {
            DayOfWeek d = DayOfWeek.SUNDAY.plus(i);
            canvas.drawText(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    cell * (i + 0.5f), header * 0.6f, text);
        }
        int offset = column(month.atDay(1).getDayOfWeek());
        float radius = cell * 0.4f;
        ring.setStrokeWidth(Math.max(2f, cell * 0.06f));
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            int index = offset + day - 1;
            float cx = cell * (index % 7 + 0.5f);
            float cy = header + cell * (index / 7 + 0.5f);
            boolean isToday = date.equals(today);
            boolean isSelected = date.equals(selected);
            if (isSelected && hasFocus()) {
                fill.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx, cy, radius, fill);
            } else if (isToday) {
                fill.setColor(0xFFE11D48);
                canvas.drawCircle(cx, cy, radius, fill);
            }
            if (isSelected && !hasFocus()) canvas.drawCircle(cx, cy, radius, ring);
            text.setTextSize(cell * 0.4f);
            int column = index % 7;
            text.setColor(isSelected && hasFocus() ? 0xFF111111
                    : isToday ? 0xFFFFFFFF : column == 0 || column == 6 ? WEEKEND : TEXT);
            canvas.drawText(String.valueOf(day), cx, cy + cell * 0.14f, text);
            if (eventDays.contains(date)) {
                fill.setColor(isSelected && hasFocus() ? 0xFF111111 : isToday ? 0xFFFFFFFF : DOT);
                canvas.drawCircle(cx, cy + cell * 0.28f, Math.max(2f, cell * 0.05f), fill);
            }
        }
    }
}
