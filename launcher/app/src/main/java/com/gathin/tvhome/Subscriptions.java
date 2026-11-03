package com.gathin.tvhome;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * config.xml show_subscriptions: the owner's app subscriptions and when each one expires, kept on this TV only
 * (SharedPreferences "subscriptions"), edited in SubscriptionsActivity and listed on Home's subscriptions card.
 */
final class Subscriptions {
    static final int[] CYCLES = {0, 1, 3, 6, 12};   // renews every N months; 0 = not set
    private static final int MAX_ON_HOME = 5;
    private static final int DIM = 0x99FFFFFF;
    private static final int RED = 0xFFF87171;
    private static final int AMBER = 0xFFFBBF24;

    static final class Item {
        String name = "";
        LocalDate expires = LocalDate.now().plusMonths(1);
        int cycleMonths = 1;
    }

    private Subscriptions() {
    }

    static List<Item> load(Context ctx) {
        List<Item> items = new ArrayList<>();
        String raw = ctx.getSharedPreferences("subscriptions", Context.MODE_PRIVATE).getString("items", "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.name = o.optString("name");
                it.expires = LocalDate.parse(o.getString("expires"));
                it.cycleMonths = o.optInt("cycle", 0);
                items.add(it);
            }
        } catch (Exception ignored) {
            // A damaged list reads as empty rather than taking Home down.
        }
        items.sort((x, y) -> x.expires.compareTo(y.expires));
        return items;
    }

    static void save(Context ctx, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("name", it.name).put("expires", it.expires.toString())
                        .put("cycle", it.cycleMonths));
            }
        } catch (Exception ignored) {
            return;
        }
        ctx.getSharedPreferences("subscriptions", Context.MODE_PRIVATE).edit().putString("items", a.toString()).apply();
    }

    static long daysLeft(Item it) {
        return ChronoUnit.DAYS.between(LocalDate.now(), it.expires);
    }

    /** "Today", "Tomorrow", "in 12 days", "Expired yesterday", "Expired 3 days ago". */
    static String describe(Item it) {
        long d = daysLeft(it);
        if (d == 0) return "Expires today";
        if (d == 1) return "Tomorrow";
        if (d > 1) return "in " + d + " days";
        if (d == -1) return "Expired yesterday";
        return "Expired " + (-d) + " days ago";
    }

    static int colour(Item it) {
        long d = daysLeft(it);
        return d <= 3 ? RED : d <= 7 ? AMBER : 0xE6FFFFFF;
    }

    static String cycleLabel(int months) {
        if (months == 0) return "Not set";
        if (months == 1) return "Every month";
        if (months == 12) return "Every year";
        return "Every " + months + " months";
    }

    /** Home's card text: a heading, then the soonest few as "Netflix   in 12 days · 15 Nov". */
    static CharSequence card(Context ctx) {
        List<Item> items = load(ctx);
        SpannableStringBuilder s = new SpannableStringBuilder();
        int start = s.length();
        s.append("SUBSCRIPTIONS");
        s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (items.isEmpty()) {
            start = s.length();
            s.append("\nNone yet. Press OK to add one.");
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }
        DateTimeFormatter date = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        for (int i = 0; i < Math.min(items.size(), MAX_ON_HOME); i++) {
            Item it = items.get(i);
            s.append('\n').append(it.name).append("   ");
            start = s.length();
            s.append(describe(it));
            s.setSpan(new ForegroundColorSpan(colour(it)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = s.length();
            s.append(" · ").append(date.format(it.expires));
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (items.size() > MAX_ON_HOME) {
            start = s.length();
            s.append("\n+").append(String.valueOf(items.size() - MAX_ON_HOME)).append(" more");
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
}
