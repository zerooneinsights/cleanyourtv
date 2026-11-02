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
import java.text.NumberFormat;
import java.util.Locale;

/**
 * config.xml show_ticker: one line under the clock with index quotes, e.g.
 * "NIFTY 50  23,317.95  ▲ 264.85 (1.15%)  ·  MIDCAP 150  21,269.35  ▲ 221.85 (1.05%)".
 * Reads Yahoo Finance's chart endpoint only while Home is resumed: every 30 s while the market is open,
 * every 5 minutes while it is closed, and a minute after a failure, keeping the last line on screen.
 */
final class MarketTicker {
    private static final long OPEN_MS = 30_000L;
    private static final long CLOSED_MS = 300_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int UP = 0xFF4ADE80;
    private static final int DOWN = 0xFFF87171;
    private static final int DIM = 0x99FFFFFF;

    private final TextView view;
    private final Handler main;
    /** "symbol|label" pairs from config.xml ticker_symbols. */
    private final String[] symbols;
    private HandlerThread thread;
    private volatile Handler bg;

    private static final class Quote {
        String label;
        double price, change, percent;
        boolean open;
    }

    MarketTicker(TextView view, Handler main, String[] symbols) {
        this.view = view;
        this.main = main;
        this.symbols = symbols;
    }

    void start() {
        if (thread != null || symbols.length == 0) return;
        view.setVisibility(View.VISIBLE);
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
                    String[] parts = symbols[i].split("\\|", 2);
                    quotes[i] = fetch(parts[0], parts.length > 1 ? parts[1] : parts[0]);
                    if (quotes[i] == null) failed = true;
                    else anyOpen |= quotes[i].open;
                }
                if (!failed) {
                    CharSequence line = format(quotes, anyOpen);
                    main.post(() -> { if (bg == h) view.setText(line); });
                }
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
        // Paused: the last line stays, so the grid does not jump up under the glass highlight.
    }

    private static Quote fetch(String symbol, String label) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d");
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
            JSONObject meta = new JSONObject(out.toString("UTF-8"))
                    .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
            Quote q = new Quote();
            q.label = label;
            q.price = meta.getDouble("regularMarketPrice");
            double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", q.price));
            q.change = q.price - prev;
            q.percent = prev == 0 ? 0 : q.change / prev * 100;
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

    private static CharSequence format(Quote[] quotes, boolean open) {
        NumberFormat num = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        num.setMinimumFractionDigits(2);
        num.setMaximumFractionDigits(2);
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Quote q : quotes) {
            if (s.length() > 0) s.append("   ·   ");
            s.append(q.label).append("  ").append(num.format(q.price)).append("  ");
            int start = s.length();
            s.append(q.change >= 0 ? "▲ " : "▼ ")
                    .append(num.format(Math.abs(q.change)))
                    .append(String.format(Locale.US, " (%.2f%%)", Math.abs(q.percent)));
            s.setSpan(new ForegroundColorSpan(q.change >= 0 ? UP : DOWN), start, s.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!open) {
            int start = s.length();
            s.append("   ·   Market closed");
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
}
