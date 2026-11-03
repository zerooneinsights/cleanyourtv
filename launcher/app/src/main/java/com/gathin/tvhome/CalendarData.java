package com.gathin.tvhome;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Instances;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Events from the calendars the TV syncs (visible ones only, declined invitations left out). Needs READ_CALENDAR. */
final class CalendarData {
    private static final String[] PROJECTION = {
            Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.SELF_ATTENDEE_STATUS};

    static final class Event {
        String title;
        long begin, end;
        boolean allDay;
    }

    private CalendarData() {
    }

    /** Events overlapping [from, toExclusive), all-day first, then by start; null when the provider can't be read. */
    static List<Event> load(Context ctx, LocalDate from, LocalDate toExclusive) {
        ZoneId zone = ZoneId.systemDefault();
        Uri.Builder uri = Instances.CONTENT_URI.buildUpon();
        // A day of slack each side: all-day events are stored as UTC midnights, which sit hours off local midnight.
        ContentUris.appendId(uri, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        ContentUris.appendId(uri, toExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        List<Event> events = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(uri.build(), PROJECTION,
                Instances.VISIBLE + "=1", null, Instances.ALL_DAY + " DESC, " + Instances.BEGIN + " ASC")) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (c.getInt(4) == Attendees.ATTENDEE_STATUS_DECLINED) continue;
                Event e = new Event();
                e.title = c.isNull(0) || c.getString(0).isEmpty() ? "(No title)" : c.getString(0);
                e.begin = c.getLong(1);
                e.end = c.isNull(2) ? e.begin : c.getLong(2);
                e.allDay = c.getInt(3) != 0;
                events.add(e);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return events;
    }

    static boolean occursOn(Event e, LocalDate date, ZoneId zone) {
        if (e.allDay) {
            // All-day events are UTC midnights: compare dates in UTC, or they slip a day east of UTC.
            LocalDate first = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate last = Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate();
            return !date.isBefore(first) && (date.isBefore(last) || first.equals(last) && date.equals(first));
        }
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return e.begin < dayEnd && (e.end > dayStart || e.begin >= dayStart);
    }

    /** "All day", "Ongoing" (began on an earlier day) or the start time, e.g. "11:00 AM". */
    static String when(Event e, LocalDate date, ZoneId zone) {
        if (e.allDay) return "All day";
        if (e.begin < date.atStartOfDay(zone).toInstant().toEpochMilli()) return "Ongoing";
        return DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
                .format(Instant.ofEpochMilli(e.begin).atZone(zone));
    }

    static List<Event> on(List<Event> events, LocalDate date, ZoneId zone) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (occursOn(e, date, zone)) out.add(e);
        return out;
    }

    /** The days in [from, toExclusive) that have at least one event, for the month grid's dots. */
    static Set<LocalDate> days(List<Event> events, LocalDate from, LocalDate toExclusive, ZoneId zone) {
        Set<LocalDate> out = new HashSet<>();
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            for (Event e : events) {
                if (occursOn(e, d, zone)) {
                    out.add(d);
                    break;
                }
            }
        }
        return out;
    }
}
