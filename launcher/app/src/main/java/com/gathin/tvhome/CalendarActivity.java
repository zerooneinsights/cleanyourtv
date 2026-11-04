package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The full calendar, opened from Home's calendar button: a month grid to move through day by day with the D-pad
 * (it crosses into the next and previous months; Channel up/down jump a month), buttons above it for the previous
 * and next year and month and Today, and the selected day's events on the right. Same synced calendars as Home.
 */
public final class CalendarActivity extends Activity {
    private static final int DIM = 0x99FFFFFF;

    private MonthGridView grid;
    private TextView title;
    private TextView dayTitle;
    private TextView dayEvents;
    private TextView prevYear, nextYear, prevMonth, nextMonth;
    private Typeface regular, medium;
    private YearMonth loadedMonth;
    private LocalDate shown = LocalDate.now();
    private TextView refresh;
    private boolean refreshing;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private List<CalendarData.Event> events = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            regular = getResources().getFont(R.font.outfit_regular);
            medium = getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, dp(36), pad, dp(24));
        root.setBackgroundColor(0xFF0B0B0F);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // A fixed width that holds the whole year/month header ("« 2025 ‹ Oct November 2026 Dec › 2027 »"): left
        // to wrap its content, the column came out narrower than the header and cut its last button.
        root.addView(left, new LinearLayout.LayoutParams(dp(480),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(header);
        prevYear = button("«");
        prevMonth = button("‹");
        title = text(22, medium, 0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setMinWidth(dp(150));
        nextMonth = button("›");
        nextYear = button("»");
        TextView today = button("Today");
        header.addView(prevYear);
        header.addView(prevMonth);
        header.addView(title);
        header.addView(nextMonth);
        header.addView(nextYear);
        refresh = button("↻ Refresh");

        grid = new MonthGridView(this);
        grid.setCellSize(dp(50));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(18);
        left.addView(grid, gridLp);
        TextView hint = text(12, regular, 0x66FFFFFF);
        hint.setText("Arrows move the day  ·  Up from the first week reaches the buttons  ·  Back closes");
        hint.setSingleLine(false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        left.addView(hint, hintLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.setMarginStart(dp(40));
        root.addView(right, rightLp);
        // Today and Refresh above the day's events: in the header row they ran past the left column's edge.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(today);
        actions.addView(refresh);
        right.addView(actions);
        dayTitle = text(24, medium, 0xFFFFFFFF);
        LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dayLp.topMargin = dp(20);
        right.addView(dayTitle, dayLp);
        dayEvents = text(18, regular, 0xE6FFFFFF);
        dayEvents.setLineSpacing(dp(8), 1f);
        LinearLayout.LayoutParams eventsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        eventsLp.topMargin = dp(18);
        right.addView(dayEvents, eventsLp);
        setContentView(root);

        prevYear.setOnClickListener(v -> grid.select(grid.month().minusYears(1).atDay(clampDay(-12))));
        nextYear.setOnClickListener(v -> grid.select(grid.month().plusYears(1).atDay(clampDay(12))));
        prevMonth.setOnClickListener(v -> grid.select(grid.month().minusMonths(1).atDay(clampDay(-1))));
        nextMonth.setOnClickListener(v -> grid.select(grid.month().plusMonths(1).atDay(clampDay(1))));
        today.setOnClickListener(v -> grid.select(LocalDate.now()));
        refresh.setOnClickListener(v -> refresh());
        // Down from any header button goes back to the grid; Up from the grid's first week lands on "next month".
        grid.setId(View.generateViewId());
        for (View b : new View[]{prevYear, prevMonth, nextMonth, nextYear, today, refresh}) {
            b.setNextFocusDownId(grid.getId());
        }
        grid.setNextFocusUpId(nextMonth.getId());

        grid.setInteractive(LocalDate.now(), this::show);
        show(LocalDate.now());
        grid.requestFocus();
        AgendaPanel.requestSync(this);
    }

    private int selectedDay = LocalDate.now().getDayOfMonth();

    /** The selected day of month carried into a month `offset` months away, cut to that month's length. */
    private int clampDay(int offset) {
        return Math.min(selectedDay, grid.month().plusMonths(offset).lengthOfMonth());
    }

    /** Syncs now and, once Google's calendar sync is done (or after 30 seconds), reloads the month on screen. */
    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        refresh.setText("↻ Syncing…");
        AgendaPanel.requestSync(this);
        long until = android.os.SystemClock.elapsedRealtime() + 30_000L;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if (AgendaPanel.syncBusy(CalendarActivity.this)
                        && android.os.SystemClock.elapsedRealtime() < until) {
                    ui.postDelayed(this, 1_500L);
                    return;
                }
                refreshing = false;
                refresh.setText("↻ Refresh");
                loadedMonth = null;   // read the month again
                show(shown);
            }
        }, 1_500L);
    }

    private void show(LocalDate day) {
        shown = day;
        selectedDay = day.getDayOfMonth();
        YearMonth month = YearMonth.from(day);
        ZoneId zone = ZoneId.systemDefault();
        if (!month.equals(loadedMonth)) {
            List<CalendarData.Event> loaded = CalendarData.load(this, month.atDay(1), month.atEndOfMonth().plusDays(1));
            events = loaded == null ? Collections.emptyList() : loaded;
            loadedMonth = month;
            grid.setMonth(month, LocalDate.now(zone),
                    CalendarData.days(events, month.atDay(1), month.atEndOfMonth().plusDays(1), zone));
            title.setText(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(month));
            prevYear.setText("« " + (month.getYear() - 1));
            nextYear.setText((month.getYear() + 1) + " »");
            prevMonth.setText("‹ " + month.minusMonths(1).getMonth()
                    .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()));
            nextMonth.setText(month.plusMonths(1).getMonth()
                    .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " ›");
        }
        dayTitle.setText(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()).format(day));
        List<CalendarData.Event> on = CalendarData.on(events, day, zone);
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (CalendarData.Event e : on) {
            if (s.length() > 0) s.append('\n');
            int start = s.length();
            s.append(CalendarData.when(e, day, zone)).append("    ");
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(e.title);
        }
        if (on.isEmpty()) {
            s.append("No events");
            s.setSpan(new ForegroundColorSpan(DIM), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        dayEvents.setText(s);
    }

    private TextView text(int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    /** A pill button: translucent at rest, white with dark text when focused. */
    private TextView button(String label) {
        TextView b = text(16, medium, 0);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setMinWidth(dp(44));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setId(View.generateViewId());
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF111111, 0xE6FFFFFF}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(0xFFFFFFFF));
        bg.addState(new int[]{}, pill(0x26FFFFFF));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(22));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
