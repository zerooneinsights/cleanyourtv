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
import java.util.Locale;

/**
 * config.xml show_weather: one line beside the clock, e.g. "☀ 26°  Clear  ·  H 31°  L 21°  ·  Pune", from
 * Open-Meteo (no account or key) for weather_latitude/weather_longitude. Read only while Home is resumed, every
 * 30 minutes, 5 minutes after a failure; the last line stays on screen meanwhile.
 */
final class WeatherLine {
    private static final long REFRESH_MS = 1_800_000L;
    private static final long RETRY_MS = 300_000L;
    private static final int DIM = 0xA6FFFFFF;

    private final TextView view;
    private final Handler main;
    private final String url;
    private final String place;
    private HandlerThread thread;
    private volatile Handler bg;

    WeatherLine(TextView view, Handler main, String latitude, String longitude, String place) {
        this.view = view;
        this.main = main;
        this.place = place;
        this.url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&forecast_days=1&timezone=auto";
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("weather");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                CharSequence line = fetch();
                if (line != null) {
                    main.post(() -> {
                        if (bg != h) return;
                        view.setVisibility(View.VISIBLE);
                        view.setText(line);
                    });
                }
                if (bg == h) h.postDelayed(this, line == null ? RETRY_MS : REFRESH_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    private CharSequence fetch() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            int code = now.getInt("weather_code");
            boolean day = now.optInt("is_day", 1) == 1;
            SpannableStringBuilder s = new SpannableStringBuilder();
            s.append(icon(code, day)).append(' ')
                    .append(String.format(Locale.US, "%d°", Math.round(now.getDouble("temperature_2m"))))
                    .append("  ").append(describe(code));
            int start = s.length();
            s.append(String.format(Locale.US, "  ·  H %d°  L %d°",
                    Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))));
            if (!place.isEmpty()) s.append("  ·  ").append(place);
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo returns them. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";
        if (code <= 2) return day ? "⛅" : "☁";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 95) return "⛈";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        return "🌧";
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "Rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
