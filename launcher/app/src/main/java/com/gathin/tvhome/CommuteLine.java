package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * config.xml show_commute: two lines above the ticker, like the weather, with the drive time each way between two
 * places in today's traffic, e.g. "To Office  48 min · +14 min traffic · reach 9:48 AM · best 8:30 AM (36 min)" and
 * "To Home ...", the time green, amber or red by how much traffic adds; the arrival time is redrawn every minute. Either from the owner's own Google Apps Script (Google Maps traffic
 * under their Google account, commute.url: the script's /exec link, answering {"minutes","trafficMinutes","km","via",
 * "back":{the same for the return trip}}) or from TomTom's routing API with their own free key (commute.key,
 * commute.from and commute.to as "lat,lon"); all in launcher/local.properties, never committed. With neither the lines
 * stay hidden; the return line shows only once there is an answer for it. Read on every return to Home and every
 * 10 minutes while it stays up, 2 minutes after a failure.
 */
final class CommuteLine {
    private static final long REFRESH_MS = 600_000L;
    private static final long RETRY_MS = 120_000L;
    private static final int DIM = 0xA6FFFFFF;
    private static final int GREEN = 0xFF4ADE80;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;

    private final TextView view;
    /** The return trip, on its own line under the first; gone until there is an answer for it. */
    private final TextView backView;
    private final Handler main;
    private final String url;
    /** TomTom only: the same route the other way; the script answers both directions at once. */
    private final String backUrl;
    /** url is the owner's Apps Script rather than TomTom. */
    private final boolean script;
    private final String label;
    private final String backLabel;
    private HandlerThread thread;
    private volatile Handler bg;
    /** Told on the main thread with the script's whole answer after each read, for the details page. */
    private java.util.function.Consumer<JSONObject> detailsListener;
    private volatile JSONObject lastJson;

    void setDetailsListener(java.util.function.Consumer<JSONObject> listener) {
        detailsListener = listener;
    }
    /** The latest answer, {there, back}, kept on the main thread so the arrival times move on every minute. */
    private Trip[] trips;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            draw();
            main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };

    private static final class Trip {
        String label, via;
        int minutes, delay;
        /** When leaving later is quicker (the script's "later"): then, and the drive time then; 0 otherwise. */
        long leaveAt;
        int laterMinutes;
        /** The script's bestTimeToLeave: "9:30 AM", its drive time, and whether it's tomorrow; null without one. */
        String bestTime;
        int bestMinutes;
        boolean bestTomorrow;
    }

    CommuteLine(TextView view, TextView backView, Handler main, String scriptUrl, String key, String from, String to,
                String label, String backLabel) {
        this.view = view;
        this.backView = backView;
        this.main = main;
        this.label = label;
        this.backLabel = backLabel;
        this.script = !scriptUrl.isEmpty();
        boolean tomtom = !script && !key.isEmpty() && !from.isEmpty() && !to.isEmpty();
        this.url = script ? scriptUrl : tomtom ? tomtom(from, to, key) : null;
        this.backUrl = tomtom ? tomtom(to, from, key) : null;
    }

    private static String tomtom(String from, String to, String key) {
        return "https://api.tomtom.com/routing/1/calculateRoute/" + from.replace(" ", "") + ":" + to.replace(" ", "")
                + "/json?traffic=true&travelMode=car&routeType=fastest&departAt=now&key=" + key;
    }

    void start() {
        if (thread != null || url == null) return;
        thread = new HandlerThread("commute");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                Trip[] found = fetch();
                JSONObject raw = lastJson;
                if (found != null) {
                    main.post(() -> {
                        if (bg != h) return;
                        if (detailsListener != null && raw != null) detailsListener.accept(raw);
                        trips = found;
                        main.removeCallbacks(tick);
                        tick.run();
                    });
                }
                if (bg == h) h.postDelayed(this, found == null ? RETRY_MS : REFRESH_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        main.removeCallbacks(tick);
        thread.quit();
        thread = null;
        bg = null;
    }

    private void draw() {
        if (trips == null) return;
        view.setVisibility(View.VISIBLE);
        view.setText(line(trips[0]));
        if (backView != null) backView.setText(trips[1] == null ? "—" : line(trips[1]));
    }

    /** {there, back}; back is null when there's no answer for the return trip. Null when the first read failed. */
    private Trip[] fetch() {
        JSONObject json = read(url);
        if (json == null) return null;
        lastJson = script ? json : null;
        if (script) {
            if (json.has("toOffice")) {                    // the other script's shape: {"toOffice":{..},"toHome":{..}}
                Trip there = fromScript(json.optJSONObject("toOffice"), label);
                if (there == null) return null;
                JSONObject home = json.optJSONObject("toHome");
                return new Trip[]{there, home == null ? null : fromScript(home, backLabel)};
            }
            Trip there = fromScript(json, label);
            if (there == null) return null;
            JSONObject back = json.optJSONObject("back");   // older scripts answer one direction only
            return new Trip[]{there, back == null ? null : fromScript(back, backLabel)};
        }
        Trip there = fromTomTom(json, label);
        if (there == null) return null;
        JSONObject back = backUrl == null ? null : read(backUrl);
        return new Trip[]{there, back == null ? null : fromTomTom(back, backLabel)};
    }

    private static JSONObject read(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setInstanceFollowRedirects(true);   // Apps Script answers /exec with a redirect
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Trip fromScript(JSONObject json, String label) {
        if (json == null || json.has("error") || !json.has("minutes")) return null;
        Trip t = new Trip();
        t.label = label;
        int free = json.optInt("minutes");
        t.minutes = json.isNull("trafficMinutes") ? free : json.optInt("trafficMinutes", free);
        t.delay = Math.max(0, t.minutes - free);
        t.via = json.optString("via", "");
        JSONObject best = json.optJSONObject("bestTimeToLeave");
        if (best != null && !best.optString("time").isEmpty()) {
            t.bestTime = best.optString("time");
            t.bestMinutes = best.optInt("minutes");
            t.bestTomorrow = "Tomorrow".equalsIgnoreCase(best.optString("day"));
        }
        JSONObject later = json.optJSONObject("later");
        if (later != null) {
            t.leaveAt = later.optLong("leaveAt");
            t.laterMinutes = later.optInt("minutes");
        }
        return t;
    }

    private static Trip fromTomTom(JSONObject json, String label) {
        try {
            JSONObject summary = json.getJSONArray("routes").getJSONObject(0).getJSONObject("summary");
            Trip t = new Trip();
            t.label = label;
            t.minutes = Math.round(summary.getInt("travelTimeInSeconds") / 60f);
            t.delay = Math.round(summary.optInt("trafficDelayInSeconds", 0) / 60f);
            t.via = "";                                    // TomTom's summary names no roads
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** "To Office  42 min · reach 1:34 PM · best 8:30 AM (36 min)", the time coloured by how much traffic adds. */
    private static CharSequence line(Trip t) {
        // Green while traffic adds under a tenth, amber under a third, red beyond.
        float share = t.minutes <= t.delay ? 1f : t.delay / (float) (t.minutes - t.delay);
        int colour = share < 0.1f ? GREEN : share < 0.33f ? AMBER : RED;
        long now = System.currentTimeMillis();
        SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);

        // The commute card's column: the drive time large, then "by 6:50 PM" (and the delay), then the best time.
        SpannableStringBuilder s = new SpannableStringBuilder();
        int start = s.length();
        s.append(formatMinutes(t.minutes));
        s.setSpan(new ForegroundColorSpan(colour), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.RelativeSizeSpan(1.75f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append("\nby ").append(clock.format(new Date(now + t.minutes * 60_000L)));
        if (t.delay > 0) {
            start = s.length();
            s.append(" · +").append(formatMinutes(t.delay));
            s.setSpan(new ForegroundColorSpan(colour), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String best = null;
        int saves = t.minutes - t.laterMinutes;
        if (t.leaveAt > now && saves >= 5) best = "leave " + clock.format(new Date(t.leaveAt));
        else if (t.bestTime != null) best = "best " + t.bestTime;
        if (best != null) {
            s.append('\n');
            start = s.length();
            s.append(best);
            s.setSpan(new ForegroundColorSpan(GREEN), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.92f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** "48 min", "1 h 05 min". */
    private static String formatMinutes(int m) {
        return m < 60 ? m + " min" : String.format(Locale.US, "%d h %02d min", m / 60, m % 60);
    }
}
