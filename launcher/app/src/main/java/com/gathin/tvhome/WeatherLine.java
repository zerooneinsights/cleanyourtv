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
 * config.xml show_weather: one line beside the clock, e.g. "☀ 26° Clear · H 31° L 21° · AQI 76 Satisfactory · Pune",
 * from Open-Meteo (no account or key) for weather_latitude/weather_longitude. Read only while Home is resumed,
 * every 30 minutes, 5 minutes after a failure; the last line stays on screen meanwhile.
 *
 * The AQI is India's (CPCB) scale, worked out from Open-Meteo's modelled PM2.5 and PM10 averaged over the last 24
 * hours, as CPCB does for particulates; it is left out when the air-quality read fails. Open-Meteo has no Indian AQI of
 * its own, and its US AQI reads much higher for the same air.
 */
final class WeatherLine {
    private static final long REFRESH_MS = 1_800_000L;
    private static final long RETRY_MS = 300_000L;
    private static final int DIM = 0xA6FFFFFF;

    /** How long each place's line stays before the next. */
    private static final long TURN_MS = 5_000L;

    private static final class Place {
        String name, url, aqiUrl;
        CharSequence line;                     // the last line read for it; null until then
    }

    private final TextView view;
    private final Handler main;
    private final java.util.List<Place> places = new java.util.ArrayList<>();
    private int shown = -1;
    private HandlerThread thread;
    private volatile Handler bg;

    /** Shows the next place that has a line, every TURN_MS while Home is up. */
    private final Runnable turn = new Runnable() {
        @Override
        public void run() {
            for (int k = 1; k <= places.size(); k++) {
                int i = (shown + k) % places.size();
                if (places.get(i).line != null) {
                    boolean other = i != shown;
                    shown = i;
                    view.setVisibility(View.VISIBLE);
                    if (other) flipTo(places.get(i).line);
                    else view.setText(places.get(i).line);
                    break;
                }
            }
            if (places.size() > 1) main.postDelayed(this, TURN_MS);
        }
    };

    /**
     * The first place from weather_latitude/longitude/place; more from {@code others}, "Name|lat|lon" separated by ";"
     * (weather.places in launcher/local.properties, never committed), each shown in turn.
     */
    WeatherLine(TextView view, Handler main, String latitude, String longitude, String place, String others) {
        this.view = view;
        // One line, cut at its end rather than ellipsized: an ellipsis breaks the AQI pill and the pin.
        view.setSingleLine(false);
        view.setMaxLines(1);
        view.setEllipsize(null);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        this.main = main;
        add(place, latitude, longitude);
        for (String spec : others.split(";")) {
            String[] f = spec.trim().split("\\|");
            if (f.length == 3) add(f[0].trim(), f[1].trim(), f[2].trim());
        }
    }

    /** The old line folds away upwards and the new one flips up into place from below. */
    private void flipTo(CharSequence line) {
        float d = view.getResources().getDisplayMetrics().density;
        view.setCameraDistance(8000 * d);
        view.setPivotY(view.getHeight() / 2f);
        view.animate().cancel();
        view.animate().rotationX(-90f).translationY(-6 * d).alpha(0f).setDuration(170).withEndAction(() -> {
            view.setText(line);
            view.setRotationX(90f);
            view.setTranslationY(6 * d);
            view.animate().rotationX(0f).translationY(0f).alpha(1f).setDuration(240).withEndAction(null).start();
        }).start();
    }

    private void add(String name, String latitude, String longitude) {
        if (latitude.isEmpty() || longitude.isEmpty()) return;
        Place p = new Place();
        p.name = name;
        p.url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&forecast_days=1&timezone=auto";
        p.aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + latitude + "&longitude="
                + longitude + "&current=pm2_5,pm10&hourly=pm2_5,pm10&past_days=1&forecast_days=1&timezone=auto";
        places.add(p);
    }

    void start() {
        if (thread != null || places.isEmpty()) return;
        thread = new HandlerThread("weather");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                boolean failed = false;
                for (Place p : places) {
                    CharSequence line = fetch(p);
                    if (line == null) {
                        failed = true;
                        continue;
                    }
                    main.post(() -> {
                        if (bg != h) return;
                        p.line = line;
                        if (shown < 0 || places.get(shown) == p) {   // the first line, or a fresher one for the shown place
                            if (shown < 0) shown = places.indexOf(p);
                            view.setVisibility(View.VISIBLE);
                            view.setText(line);
                        }
                    });
                }
                if (bg == h) h.postDelayed(this, failed ? RETRY_MS : REFRESH_MS);
            }
        });
        main.removeCallbacks(turn);
        main.postDelayed(turn, TURN_MS);
    }

    void stop() {
        if (thread == null) return;
        main.removeCallbacks(turn);
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    private static JSONObject get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private CharSequence fetch(Place p) {
        try {
            JSONObject json = get(p.url);
            if (json == null) return null;
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            int code = now.getInt("weather_code");
            boolean day = now.optInt("is_day", 1) == 1;
            SpannableStringBuilder s = new SpannableStringBuilder();
            int start = s.length();
            s.append(icon(code, day));
            s.setSpan(new android.text.style.RelativeSizeSpan(1.4f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(" ");
            start = s.length();
            s.append(String.format(Locale.US, "%d°C", Math.round(now.getDouble("temperature_2m"))));
            s.setSpan(new android.text.style.RelativeSizeSpan(1.35f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(" ").append(describe(code));
            start = s.length();
            s.append(String.format(Locale.US, "  H %d° L %d°",
                    Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))));
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int aqi = aqi(p.aqiUrl);
            if (aqi >= 0) {
                s.append("  ");
                start = s.length();
                s.append("AQI ").append(String.valueOf(aqi)).append(" · ").append(aqiCategory(aqi));
                int c = aqiColour(aqi);
                s.setSpan(new HomeSpans.Pill(view.getContext(), (c & 0x00FFFFFF) | 0x2E000000, 0, c, 0.9f), start,
                        s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!p.name.isEmpty()) {
                s.append("  ");
                start = s.length();
                s.append("\u2009");
                s.setSpan(new HomeSpans.Icon(view.getContext(), R.drawable.ic_place, 0xFFFFFFFF, 1.15f), start,
                        s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.append(p.name);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** India's AQI from the last 24 hours' mean PM2.5 and PM10 (the larger sub-index); -1 when it can't be read. */
    private int aqi(String aqiUrl) {
        try {
            JSONObject json = get(aqiUrl);
            if (json == null) return -1;
            String now = json.getJSONObject("current").getString("time").substring(0, 13);
            JSONObject hourly = json.getJSONObject("hourly");
            org.json.JSONArray times = hourly.getJSONArray("time");
            org.json.JSONArray pm25 = hourly.getJSONArray("pm2_5");
            org.json.JSONArray pm10 = hourly.getJSONArray("pm10");
            int last = -1;
            for (int i = 0; i < times.length(); i++) {
                if (times.getString(i).substring(0, 13).compareTo(now) <= 0) last = i;
            }
            if (last < 0) return -1;
            double sum25 = 0, sum10 = 0;
            int n25 = 0, n10 = 0;
            for (int i = Math.max(0, last - 23); i <= last; i++) {
                if (!pm25.isNull(i)) { sum25 += pm25.getDouble(i); n25++; }
                if (!pm10.isNull(i)) { sum10 += pm10.getDouble(i); n10++; }
            }
            if (n25 == 0 && n10 == 0) return -1;
            int a25 = n25 == 0 ? 0 : subIndex(sum25 / n25, PM25_BREAKS);
            int a10 = n10 == 0 ? 0 : subIndex(sum10 / n10, PM10_BREAKS);
            return Math.max(a25, a10);
        } catch (Exception e) {
            return -1;
        }
    }

    // CPCB breakpoints: concentration bands (µg/m³) for the AQI bands 0-50, 51-100, 101-200, 201-300, 301-400, 401-500.
    private static final double[] PM25_BREAKS = {0, 30, 60, 90, 120, 250, 380};
    private static final double[] PM10_BREAKS = {0, 50, 100, 250, 350, 430, 510};
    private static final int[] AQI_BREAKS = {0, 50, 100, 200, 300, 400, 500};

    private static int subIndex(double c, double[] breaks) {
        for (int i = 1; i < breaks.length; i++) {
            if (c <= breaks[i]) {
                double lo = breaks[i - 1], hi = breaks[i];
                int aLo = i == 1 ? 0 : AQI_BREAKS[i - 1] + 1, aHi = AQI_BREAKS[i];
                return (int) Math.round(aLo + (c - lo) * (aHi - aLo) / (hi - lo));
            }
        }
        return 500;
    }

    private static String aqiCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very poor";
        return "Severe";
    }

    /** CPCB's colours for the bands, brightened a little to read on the dark Home. */
    private static int aqiColour(int aqi) {
        if (aqi <= 50) return 0xFF4ADE80;
        if (aqi <= 100) return 0xFFA3E635;
        if (aqi <= 200) return 0xFFFACC15;
        if (aqi <= 300) return 0xFFFB923C;
        if (aqi <= 400) return 0xFFF87171;
        return 0xFFDC2626;
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
