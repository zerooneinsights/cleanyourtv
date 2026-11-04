package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * config.xml show_cricket: a live score card on Home, under the ticker, while a followed match is in progress
 * (cricket_teams by team name, cricket_leagues by league name). Reads ESPN's public cricket scoreboard only while
 * Home is resumed: every 30 s while a followed match is live, every 5 minutes otherwise, and a minute after a
 * failure. With no followed match live the card is gone.
 */
final class CricketCard {
    private static final String FEED =
            "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket&lang=en&region=in";
    private static final long LIVE_MS = 30_000L;
    private static final long IDLE_MS = 300_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int LIVE = 0xFFF87171;
    private static final int DIM = 0xB3FFFFFF;

    private final TextView view;
    private final Handler main;
    private final List<String> teams;
    private final List<String> leagues;
    private HandlerThread thread;
    private volatile Handler bg;
    /** Told on the main thread after each read whether a followed match is live (the scorecard button's colour). */
    private java.util.function.Consumer<Boolean> liveListener;

    void setLiveListener(java.util.function.Consumer<Boolean> listener) {
        liveListener = listener;
    }

    CricketCard(TextView view, Handler main, String[] teams, String[] leagues) {
        this.view = view;
        this.main = main;
        this.teams = Arrays.asList(teams);
        this.leagues = Arrays.asList(leagues);
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("cricket");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                List<JSONObject[]> live = fetch();
                if (live != null) {
                    CharSequence text = live.isEmpty() ? null : format(live);
                    main.post(() -> {
                        if (bg != h) return;
                        view.setVisibility(text == null ? View.GONE : View.VISIBLE);
                        if (text != null) view.setText(text);
                        if (liveListener != null) liveListener.accept(text != null);
                    });
                }
                if (bg == h) h.postDelayed(this, live == null ? RETRY_MS : live.isEmpty() ? IDLE_MS : LIVE_MS);
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

    /** Followed matches in progress as {league, event} pairs; null when the feed could not be read. */
    private List<JSONObject[]> fetch() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(FEED).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            List<JSONObject[]> live = new ArrayList<>();
            JSONArray sports = new JSONObject(out.toString("UTF-8")).optJSONArray("sports");
            for (int s = 0; sports != null && s < sports.length(); s++) {
                JSONArray lgs = sports.getJSONObject(s).optJSONArray("leagues");
                for (int l = 0; lgs != null && l < lgs.length(); l++) {
                    JSONObject league = lgs.getJSONObject(l);
                    boolean leagueFollowed = leagues.contains(league.optString("name"));
                    JSONArray events = league.optJSONArray("events");
                    for (int e = 0; events != null && e < events.length(); e++) {
                        JSONObject event = events.getJSONObject(e);
                        if (!"in".equals(event.optString("status"))) continue;
                        if (leagueFollowed || hasTeam(event)) live.add(new JSONObject[]{league, event});
                    }
                }
            }
            return live;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean hasTeam(JSONObject event) {
        JSONArray comps = event.optJSONArray("competitors");
        for (int i = 0; comps != null && i < comps.length(); i++) {
            if (teams.contains(comps.optJSONObject(i).optString("displayName"))) return true;
        }
        return false;
    }

    /** The first followed match in full; any others as a "+N more live" line, so the card stays above the shelf. */
    private static CharSequence format(List<JSONObject[]> live) {
        JSONObject league = live.get(0)[0], event = live.get(0)[1];
        SpannableStringBuilder s = new SpannableStringBuilder();
        int start = s.length();
        s.append("● LIVE");
        s.setSpan(new ForegroundColorSpan(LIVE), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = s.length();
        String title = event.optString("title");
        String abbr = league.optString("abbreviation");
        s.append("   ").append(abbr.isEmpty() ? league.optString("name") : abbr);
        if (!title.isEmpty()) s.append(" · ").append(title);
        String state = event.optString("summary");
        if (!state.isEmpty() && !"Live".equals(state)) s.append(" · ").append(state);
        s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        JSONArray comps = event.optJSONArray("competitors");
        for (int i = 0; comps != null && i < comps.length(); i++) {
            JSONObject team = comps.optJSONObject(i);
            String score = team.optString("score");
            s.append('\n').append(team.optString("displayName")).append("   ");
            start = s.length();
            s.append(score.isEmpty() ? "Yet to bat" : score);
            if (score.isEmpty()) {
                s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        JSONObject full = event.optJSONObject("fullStatus");
        String note = full == null ? "" : full.optString("longSummary", full.optString("summary"));
        if (!note.isEmpty()) {
            start = s.length();
            s.append('\n').append(note);
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (live.size() > 1) {
            start = s.length();
            s.append("\n+").append(String.valueOf(live.size() - 1)).append(" more live");
            s.setSpan(new ForegroundColorSpan(DIM), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
}
