package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * config.xml show_returns: a card under the calendar with each of returns_symbols' total return over each of
 * returns_periods ("1D", "1W", "3M", "52W"...): green up, red down. Daily closes for the last year from Yahoo
 * Finance's chart endpoint, read only while Home is resumed, every 30 minutes, 5 minutes after a failure.
 * 1D is the latest price against the previous session's close; a longer period compares with the last close on or
 * before the date that far back.
 */
final class ReturnsCard {
    private static final long REFRESH_MS = 1_800_000L;
    private static final long RETRY_MS = 300_000L;
    private static final int UP = 0xFF4ADE80;
    private static final int DOWN = 0xFFF87171;
    private static final int DIM = 0x99FFFFFF;
    private static final int NAME_DP = 78;
    private static final int COL_DP = 42;
    private static final int PRICE_DP = 64;

    private final LinearLayout card;
    private final Handler main;
    private final String[] symbols;   // "symbol|label"
    private final String[] periods;
    private final TextView[][] cells;
    private final TextView[] prices;
    private final float density;
    private HandlerThread thread;
    private volatile Handler bg;

    ReturnsCard(LinearLayout card, Handler main, String[] symbols, String[] periods) {
        this.card = card;
        this.main = main;
        this.symbols = symbols;
        this.periods = periods;
        Context ctx = card.getContext();
        density = ctx.getResources().getDisplayMetrics().density;
        Typeface regular = null, medium = null;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        LinearLayout head = row();
        TextView title = cell("RETURNS", NAME_DP, 10, medium, DIM, Gravity.START);
        android.text.SpannableStringBuilder mark = new android.text.SpannableStringBuilder("\u2009RETURNS");
        mark.setSpan(new HomeSpans.Icon(ctx, R.drawable.ic_bars, 0xCCFFFFFF, 1.4f), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(mark);
        head.addView(title);
        head.addView(cell("PRICE", PRICE_DP, 10, medium, DIM, Gravity.END));
        for (String p : periods) head.addView(cell(p, COL_DP, 10, medium, DIM, Gravity.END));
        card.addView(head);
        cells = new TextView[symbols.length][periods.length];
        prices = new TextView[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            String[] parts = symbols[i].split("\\|", 2);
            LinearLayout r = row();
            r.addView(cell(parts.length > 1 ? parts[1].split("\\|")[0] : parts[0], NAME_DP, 11, medium, 0xF2FFFFFF,
                    Gravity.START));
            prices[i] = cell("–", PRICE_DP, 11, regular, 0xF2FFFFFF, Gravity.END);
            r.addView(prices[i]);
            for (int j = 0; j < periods.length; j++) {
                cells[i][j] = cell("–", COL_DP, 11, regular, DIM, Gravity.END);
                r.addView(cells[i][j]);
            }
            card.addView(r);
        }
    }

    void start() {
        if (thread != null || symbols.length == 0) return;
        card.setVisibility(View.VISIBLE);
        thread = new HandlerThread("returns");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                boolean failed = false;
                for (int i = 0; i < symbols.length; i++) {
                    String[] spec = symbols[i].split("\\|");
                    Double[] r = returns(spec[0]);
                    if (r == null) {
                        failed = true;
                        continue;
                    }
                    // "gold10g" / "silverkg": the price column shows the metal itself in rupees, not the ETF's unit.
                    if (spec.length > 2) r[periods.length] = metalInr(spec[2].trim());
                    int row = i;
                    main.post(() -> { if (bg == h) show(row, r); });
                }
                if (bg == h) h.postDelayed(this, failed ? RETRY_MS : REFRESH_MS);
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

    private void show(int row, Double[] r) {
        Double price = r[periods.length];
        if (price != null) {
            boolean metal = symbols[row].split("\\|").length > 2;
            java.text.NumberFormat num = java.text.NumberFormat.getNumberInstance(new Locale("en", "IN"));
            num.setMinimumFractionDigits(metal ? 0 : 2);
            num.setMaximumFractionDigits(metal ? 0 : 2);
            prices[row].setText((metal ? "₹" : "") + num.format(price));
        }
        for (int j = 0; j < periods.length; j++) {
            TextView t = cells[row][j];
            if (r[j] == null) {
                t.setText("–");
                t.setTextColor(DIM);
                continue;
            }
            double pct = r[j] * 100;
            if (Math.abs(pct) < 0.05) {   // rounds to zero: "0.0%" in white, not "-0.0%" in red
                t.setText("0.0%");
                t.setTextColor(0xF2FFFFFF);
                continue;
            }
            t.setText(String.format(Locale.US, Math.abs(pct) >= 100 ? "%+.0f%%" : "%+.1f%%", pct));
            t.setTextColor(pct >= 0 ? UP : DOWN);
        }
    }

    /**
     * The return over each period as a fraction (0.05 = 5%), null where the history is too short, then the current
     * price as the last element.
     */
    private Double[] returns(String symbol) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1y");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            // The default Java agent is refused with 429.
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONObject result = new JSONObject(out.toString("UTF-8"))
                    .getJSONObject("chart").getJSONArray("result").getJSONObject(0);
            JSONObject meta = result.getJSONObject("meta");
            JSONArray times = result.getJSONArray("timestamp");
            JSONArray closes = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                    .getJSONArray("close");
            ZoneOffset zone = ZoneOffset.ofTotalSeconds(meta.optInt("gmtoffset", 19800));
            int n = times.length();
            LocalDate[] dates = new LocalDate[n];
            double[] values = new double[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                if (closes.isNull(i)) continue;   // a missing day is skipped
                dates[count] = Instant.ofEpochSecond(times.getLong(i)).atOffset(zone).toLocalDate();
                values[count++] = closes.getDouble(i);
            }
            if (count < 2) return null;
            double price = meta.optDouble("regularMarketPrice", values[count - 1]);
            LocalDate last = dates[count - 1];
            Double[] out2 = new Double[periods.length + 1];
            out2[periods.length] = price;
            for (int j = 0; j < periods.length; j++) {
                LocalDate target = back(last, periods[j]);
                if (target == null) continue;
                // 1D: the last close before the latest session's day; longer: the last close on or before the date.
                boolean day = target.equals(last.minusDays(1));
                int base = -1;
                for (int i = count - 1; i >= 0; i--) {
                    if (day ? dates[i].isBefore(last) : !dates[i].isAfter(target)) {
                        base = i;
                        break;
                    }
                }
                // A year of daily closes can start a day or two after 52 weeks back: use the oldest one then.
                if (base < 0 && !day) base = 0;
                if (base >= 0 && values[base] > 0) out2[j] = price / values[base] - 1;
            }
            return out2;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Gold per 10 g or silver per kg in rupees: the world price (COMEX, dollars a troy ounce) at today's dollar rate.
     * India's own price adds import duty and GST on top. Null when either read fails.
     */
    private static Double metalInr(String kind) {
        String symbol = "gold10g".equals(kind) ? "GC=F" : "silverkg".equals(kind) ? "SI=F" : null;
        if (symbol == null) return null;
        Double local = puneRate(kind);
        if (local != null) return local;
        Double usd = lastPrice(symbol), inr = lastPrice("USDINR=X");
        if (usd == null || inr == null) return null;
        double grams = "gold10g".equals(kind) ? 10 : 1000;
        return usd * inr / 31.1034768 * grams;
    }

    /**
     * Today's Pune retail rate from GoodReturns' Pune pages: 24-carat gold per gram (times 10), silver per kilogram.
     * Null when the page can't be read or its wording changes; the world price is used then.
     */
    private static Double puneRate(String kind) {
        boolean gold = "gold10g".equals(kind);
        HttpURLConnection c = null;
        try {
            URL url = new URL(gold ? "https://www.goodreturns.in/gold-rates/pune.html"
                    : "https://www.goodreturns.in/silver-rates/pune.html");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9)");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            String text = out.toString("UTF-8").replaceAll("<[^>]+>", " ")
                    .replace("&#x20b9;", "₹").replace("&#8377;", "₹").replaceAll("\\s+", " ");
            // "…stands at ₹12,557 per gram for 24 karat gold…" / "…₹175 per gram and ₹1,75,000 per kilogram"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(gold
                    ? "₹\\s?([\\d,]+) per gram for 24 karat" : "₹\\s?([\\d,]+) per kilogram").matcher(text);
            if (!m.find()) return null;
            double value = Double.parseDouble(m.group(1).replace(",", ""));
            return gold ? value * 10 : value;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Double lastPrice(String symbol) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONObject meta = new JSONObject(out.toString("UTF-8")).getJSONObject("chart").getJSONArray("result")
                    .getJSONObject(0).getJSONObject("meta");
            return meta.has("regularMarketPrice") ? meta.getDouble("regularMarketPrice") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "1D" one day back, "1W" a week, "3M" three months, "52W" fifty-two weeks; null for anything else. */
    private static LocalDate back(LocalDate from, String period) {
        if (TextUtils.isEmpty(period) || period.length() < 2) return null;
        int n;
        try {
            n = Integer.parseInt(period.substring(0, period.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        switch (Character.toUpperCase(period.charAt(period.length() - 1))) {
            case 'D':
                return from.minusDays(n);
            case 'W':
                return from.minusWeeks(n);
            case 'M':
                return from.minusMonths(n);
            case 'Y':
                return from.minusYears(n);
            default:
                return null;
        }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(card.getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Math.round(1 * density), 0, Math.round(1 * density));
        return r;
    }

    private TextView cell(String text, int widthDp, int sp, Typeface face, int color, int gravity) {
        TextView t = new TextView(card.getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setGravity(gravity);
        if (face != null) t.setTypeface(face);
        t.setLayoutParams(new LinearLayout.LayoutParams(Math.round(widthDp * density),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }
}
