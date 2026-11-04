package com.gathin.tvhome;

import android.Manifest;
import android.accounts.Account;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * config.xml show_agenda: on the right of Home, this month as a small grid (today circled, dots on days with events)
 * and today's and tomorrow's events beside it, from the Google calendars the TV itself syncs (CalendarData). Needs
 * READ_CALENDAR, granted by pm grant over ADB. Android TV syncs calendars only once a day, so while Home is resumed
 * this asks Google's calendar sync for an update every time Home comes back (Home button, Back out of an app), and
 * every 15 minutes while Home stays up, and redraws as soon as the synced events change. It also sets the TV's
 * background calendar sync from once a day to every hour, so events stay fresh while an app is in front. Nothing
 * leaves the TV except those sync requests.
 */
final class AgendaPanel {
    private static final long REFRESH_MS = 900_000L;
    /** Only guards against a burst of Home presses: a resume this soon after the last request does not ask again. */
    private static final long SYNC_GAP_MS = 15_000L;
    private static long lastSyncRequest = -SYNC_GAP_MS;
    private static final int MAX_PER_DAY = 5;
    private static final int HEADING = 0x99FFFFFF;
    private static final int TIME = 0xB3FFFFFF;

    /** How long a manual refresh waits for the sync to finish before showing what the TV has. */
    private static final long MANUAL_WAIT_MS = 30_000L;
    private static final long MANUAL_POLL_MS = 1_500L;

    private final Context ctx;
    private final View card;
    private final TextView view;
    private final TextView status;
    private final MonthGridView grid;
    private final TextView monthTitle;
    private final Handler main;
    private HandlerThread thread;
    private volatile Handler bg;
    private ContentObserver observer;
    private Runnable redraw;
    /** A manual refresh is waiting for its sync: the status line says "Syncing…" until it ends. Background thread. */
    private boolean syncing;

    AgendaPanel(Context ctx, View card, TextView view, TextView status, MonthGridView grid, TextView monthTitle,
                Handler main) {
        this.ctx = ctx.getApplicationContext();
        this.card = card;
        this.view = view;
        this.status = status;
        this.grid = grid;
        this.monthTitle = monthTitle;
        this.main = main;
        grid.setCellSize(22 * ctx.getResources().getDisplayMetrics().density);
    }

    void start() {
        if (thread != null) return;
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return;
        card.setVisibility(View.VISIBLE);
        thread = new HandlerThread("agenda");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        Runnable redraw = () -> {
            if (bg != h) return;
            Update u = read();
            boolean busy = syncing;
            String time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(java.time.LocalTime.now());
            if (u != null) main.post(() -> {
                if (bg != h) return;
                view.setText(u.text);
                monthTitle.setText(u.title);
                grid.setMonth(u.month, u.today, u.days);
                if (!busy) status.setText("↻ Updated " + time);
            });
        };
        this.redraw = redraw;
        // A finished sync writes the provider: redraw then, a moment later so a burst of writes draws once.
        observer = new ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange) {
                h.removeCallbacks(redraw);
                h.postDelayed(redraw, 1_000L);
            }
        };
        ctx.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
        h.post(new Runnable() {
            boolean first = true;

            @Override
            public void run() {
                if (bg != h) return;
                redraw.run();
                long now = SystemClock.elapsedRealtime();
                if (!first || now - lastSyncRequest >= SYNC_GAP_MS) {
                    lastSyncRequest = now;
                    requestSync(ctx);
                }
                first = false;
                if (bg == h) h.postDelayed(this, REFRESH_MS);
            }
        });
    }

    /**
     * OK on the card or the calendar screen's Refresh: syncs now (no 15-second guard), shows "Syncing…", and redraws
     * once Google's calendar sync has finished, or after 30 seconds with whatever the TV has by then.
     */
    void refreshNow() {
        Handler h = bg;
        if (h == null) return;
        status.setText("↻ Syncing…");
        h.post(() -> {
            syncing = true;
            lastSyncRequest = SystemClock.elapsedRealtime();
            requestSync(ctx);
            long until = SystemClock.elapsedRealtime() + MANUAL_WAIT_MS;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (bg != h) return;
                    if (syncBusy(ctx) && SystemClock.elapsedRealtime() < until) {
                        h.postDelayed(this, MANUAL_POLL_MS);
                        return;
                    }
                    syncing = false;
                    redraw.run();
                }
            }, MANUAL_POLL_MS);
        });
    }

    /** Whether Google's calendar sync is running or queued for any of the TV's calendar accounts. */
    static boolean syncBusy(Context ctx) {
        for (Account a : accounts(ctx)) {
            try {
                if (ContentResolver.isSyncActive(a, CalendarContract.AUTHORITY)
                        || ContentResolver.isSyncPending(a, CalendarContract.AUTHORITY)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Without READ_SYNC_STATS the answer is "not busy": the redraw just comes sooner.
            }
        }
        return false;
    }

    void stop() {
        if (thread == null) return;
        ctx.getContentResolver().unregisterContentObserver(observer);
        observer = null;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /**
     * Asks Google's calendar sync adapter to sync now, for each account that owns a visible calendar. The accounts
     * are remembered, so an emptied calendar store (cleared to force a full download) still gets its sync request.
     */
    static void requestSync(Context ctx) {
        Set<Account> accounts = accounts(ctx);
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        for (Account a : accounts) {
            try {
                ContentResolver.requestSync(a, CalendarContract.AUTHORITY, extras);
                // The TV's own background sync runs once a day; every hour keeps events fresh while an app is
                // in front and Home is never resumed. Re-adding the same period is a no-op.
                ContentResolver.setSyncAutomatically(a, CalendarContract.AUTHORITY, true);
                ContentResolver.addPeriodicSync(a, CalendarContract.AUTHORITY, Bundle.EMPTY, HOURLY_SYNC_S);
            } catch (RuntimeException ignored) {
                // A firmware that refuses the request keeps the TV's own daily sync.
            }
        }
    }

    /** The accounts owning a visible, synced calendar, plus every one seen before (kept for an emptied store). */
    static Set<Account> accounts(Context ctx) {
        Set<Account> accounts = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(Calendars.CONTENT_URI,
                new String[]{Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE},
                Calendars.VISIBLE + "=1 AND " + Calendars.SYNC_EVENTS + "=1", null, null)) {
            while (c != null && c.moveToNext()) {
                if (!c.isNull(0) && !c.isNull(1)) accounts.add(new Account(c.getString(0), c.getString(1)));
            }
        } catch (RuntimeException ignored) {
            // An unreadable store still leaves the remembered accounts below.
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("agenda", Context.MODE_PRIVATE);
        Set<String> known = new HashSet<>(prefs.getStringSet("accounts", new HashSet<>()));
        for (Account a : accounts) known.add(a.type + "|" + a.name);
        prefs.edit().putStringSet("accounts", known).apply();
        for (String k : known) {
            String[] parts = k.split("\\|", 2);
            if (parts.length == 2) accounts.add(new Account(parts[1], parts[0]));
        }
        return accounts;
    }

    /** The background calendar sync period set on the TV's accounts, in seconds. */
    private static final long HOURLY_SYNC_S = 3600L;

    private static final class Update {
        CharSequence text;
        String title;
        YearMonth month;
        LocalDate today;
        Set<LocalDate> days;
    }

    private Update read() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth().plusDays(1);
        if (to.isBefore(today.plusDays(2))) to = today.plusDays(2);
        List<CalendarData.Event> events = CalendarData.load(ctx, from, to);
        if (events == null) return null;
        Update u = new Update();
        u.today = today;
        u.month = month;
        u.days = CalendarData.days(events, from, month.atEndOfMonth().plusDays(1), zone);
        u.title = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(today)
                .toUpperCase(Locale.getDefault());
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault());
        SpannableStringBuilder s = new SpannableStringBuilder();
        appendDay(s, "TODAY · " + day.format(today).toUpperCase(Locale.getDefault()), events, today, zone);
        s.append("\n\n");
        appendDay(s, "TOMORROW · " + day.format(today.plusDays(1)).toUpperCase(Locale.getDefault()), events,
                today.plusDays(1), zone);
        u.text = s;
        return u;
    }

    private static void appendDay(SpannableStringBuilder s, String heading, List<CalendarData.Event> events,
                                  LocalDate date, ZoneId zone) {
        int start = s.length();
        s.append(heading);
        s.setSpan(new ForegroundColorSpan(HEADING), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        List<CalendarData.Event> on = CalendarData.on(events, date, zone);
        for (int i = 0; i < Math.min(on.size(), MAX_PER_DAY); i++) {
            CalendarData.Event e = on.get(i);
            s.append('\n');
            start = s.length();
            s.append(CalendarData.when(e, date, zone)).append("   ");
            s.setSpan(new ForegroundColorSpan(TIME), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(e.title);
        }
        if (on.isEmpty()) dim(s, "\nNo events");
        if (on.size() > MAX_PER_DAY) dim(s, "\n+" + (on.size() - MAX_PER_DAY) + " more");
    }

    private static void dim(SpannableStringBuilder s, String text) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(HEADING), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
