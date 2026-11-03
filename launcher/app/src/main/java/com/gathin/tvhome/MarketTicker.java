package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
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
import java.text.NumberFormat;
import java.util.Locale;

/**
 * config.xml show_ticker: a rounded card on Home with one row per index (ticker_symbols): its name and an OPEN or
 * CLOSED badge, the price and the day's change, and the day's prices as a small line against the previous close.
 * Reads Yahoo Finance's chart endpoint only while Home is resumed: every 30 s while the market is open, every
 * 5 minutes while it is closed, and a minute after a failure, keeping the last values on screen.
 */
final class MarketTicker {
    private static final long OPEN_MS = 30_000L;
    private static final long CLOSED_MS = 300_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int UP = 0xFF4ADE80;
    private static final int DOWN = 0xFFF87171;

    private final LinearLayout card;
    private final Handler main;
    /** "symbol|label" pairs from config.xml ticker_symbols. */
    private final String[] symbols;
    private final Row[] rows;
    private HandlerThread thread;
    private volatile Handler bg;

    private static final class Quote {
        double price, change, percent, previous;
        double[] closes;
        boolean open;
    }

    private static final class Row {
        TextView badge, price, change;
        SparklineView chart;
    }

    MarketTicker(LinearLayout card, Handler main, String[] symbols) {
        this.card = card;
        this.main = main;
        this.symbols = symbols;
        this.rows = new Row[symbols.length];
        Context ctx = card.getContext();
        Typeface regular = null, medium = null;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < symbols.length; i++) {
            String[] parts = symbols[i].split("\\|", 2);
            Row r = new Row();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = Math.round(10 * d);
            card.addView(row, rowLp);

            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setMinimumWidth(Math.round(170 * d));
            row.addView(text, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout top = new LinearLayout(ctx);
            top.setGravity(Gravity.CENTER_VERTICAL);
            text.addView(top);
            TextView name = label(ctx, 11, medium, 0xCCFFFFFF);
            name.setLetterSpacing(0.1f);
            name.setText(parts.length > 1 ? parts[1] : parts[0]);
            top.addView(name);
            r.badge = label(ctx, 8, medium, 0xCCFFFFFF);
            r.badge.setLetterSpacing(0.08f);
            r.badge.setBackgroundResource(R.drawable.widget_badge);
            r.badge.setPadding(Math.round(5 * d), Math.round(1 * d), Math.round(5 * d), Math.round(1 * d));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(Math.round(8 * d));
            top.addView(r.badge, badgeLp);
            r.badge.setVisibility(View.GONE);
            LinearLayout bottom = new LinearLayout(ctx);
            bottom.setGravity(Gravity.BOTTOM);
            text.addView(bottom);
            r.price = label(ctx, 19, regular, 0xFFFFFFFF);
            bottom.addView(r.price);
            r.change = label(ctx, 10, regular, 0xCCFFFFFF);
            LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            changeLp.setMarginStart(Math.round(6 * d));
            changeLp.bottomMargin = Math.round(3 * d);
            bottom.addView(r.change, changeLp);

            r.chart = new SparklineView(ctx);
            LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(Math.round(80 * d), Math.round(32 * d));
            chartLp.setMarginStart(Math.round(10 * d));
            row.addView(r.chart, chartLp);
            rows[i] = r;
        }
    }

    private static TextView label(Context ctx, int sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        if (face != null) t.setTypeface(face);
        return t;
    }

    void start() {
        if (thread != null || symbols.length == 0) return;
        card.setVisibility(View.VISIBLE);
        thread = new HandlerThread("ticker");
        thread.start();
        // Same pattern as the stats reader: each run holds its own handler, so stop() can clear the field at any time.
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                Quote[] quotes = new Quote[symbols.length];
                boolean failed = false, anyOpen = false;
                for (int i = 0; i < symbols.length; i++) {
                    quotes[i] = fetch(symbols[i].split("\\|", 2)[0]);
                    if (quotes[i] == null) failed = true;
                    else anyOpen |= quotes[i].open;
                }
                if (!failed) main.post(() -> { if (bg == h) show(quotes); });
                if (bg == h) h.postDelayed(this, failed ? RETRY_MS : anyOpen ? OPEN_MS : CLOSED_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
        // Paused: the last values stay, so nothing on Home jumps.
    }

    private void show(Quote[] quotes) {
        NumberFormat num = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        num.setMinimumFractionDigits(2);
        num.setMaximumFractionDigits(2);
        for (int i = 0; i < quotes.length; i++) {
            Quote q = quotes[i];
            Row r = rows[i];
            int color = q.change >= 0 ? UP : DOWN;
            r.price.setText(num.format(q.price));
            r.change.setText((q.change >= 0 ? "▲ " : "▼ ") + num.format(Math.abs(q.change))
                    + String.format(Locale.US, " · %.2f%%", Math.abs(q.percent)));
            r.change.setTextColor(color);
            r.badge.setText(q.open ? "OPEN" : "CLOSED");
            r.badge.setVisibility(View.VISIBLE);
            r.chart.setData(q.closes, q.previous, color);
        }
    }

    private static Quote fetch(String symbol) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=5m&range=1d");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            // The default Java agent is refused with 429.
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONObject result = new JSONObject(out.toString("UTF-8"))
                    .getJSONObject("chart").getJSONArray("result").getJSONObject(0);
            JSONObject meta = result.getJSONObject("meta");
            Quote q = new Quote();
            q.price = meta.getDouble("regularMarketPrice");
            q.previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", q.price));
            q.change = q.price - q.previous;
            q.percent = q.previous == 0 ? 0 : q.change / q.previous * 100;
            JSONArray closes = result.optJSONObject("indicators") == null ? null
                    : result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
            int n = 0;
            double[] values = new double[closes == null ? 0 : closes.length()];
            for (int i = 0; i < values.length; i++) {
                if (!closes.isNull(i)) values[n++] = closes.getDouble(i);   // a missing 5-minute bar is skipped
            }
            q.closes = java.util.Arrays.copyOf(values, n);
            // A holiday or weekend leaves the last session's period here, so "now" falls outside it.
            JSONObject regular = meta.optJSONObject("currentTradingPeriod");
            regular = regular == null ? null : regular.optJSONObject("regular");
            long now = System.currentTimeMillis() / 1000;
            q.open = regular != null && now >= regular.optLong("start") && now < regular.optLong("end");
            return q;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
