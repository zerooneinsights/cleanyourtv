package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
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
 * The detailed cricket scorecard Home drops down from the top bar's scorecard button. It picks, in order: a live
 * followed match (cricket_teams / cricket_leagues, as CricketCard), a live international from anyone else, the latest
 * followed result, the latest international result, then the next followed or international match.
 * Shows both sides' totals, the current innings' batting (dismissal, R, B, 4s, 6s, SR) and the bowling against it
 * (O, M, R, W, Econ), from ESPN's public scoreboard and match summary. Refreshed every 30 s while it is open.
 */
final class CricketScorecard {
    private static final String FEED =
            "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket&lang=en&region=in";
    private static final String SUMMARY = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/%s/summary?event=%s";
    private static final long REFRESH_MS = 30_000L;
    private static final int DIM = 0x99FFFFFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int LIVE = 0xFFF87171;
    private static final int ACCENT = 0xFF60A5FA;
    // Column widths in dp: name, dismissal, then the figures.
    private static final int[] BAT = {120, 180, 32, 32, 26, 26, 44};
    private static final int[] BOWL = {120, 40, 30, 34, 30, 44};

    private final LinearLayout card;
    private final Handler main;
    private final List<String> teams;
    private final List<String> leagues;
    private final float density;
    private Typeface regular, medium;
    private HandlerThread thread;
    private volatile Handler bg;

    /** One match as the card draws it, read off the main thread. */
    private static final class Match {
        String title, state, status, note;
        final List<String[]> totals = new ArrayList<>();     // {team, score}
        String battingTeam;
        final List<String[]> batting = new ArrayList<>();    // {name, dismissal, R, B, 4s, 6s, SR}
        final List<String[]> bowling = new ArrayList<>();    // {name, O, M, R, W, Econ}
    }

    CricketScorecard(LinearLayout card, Handler main, String[] teams, String[] leagues) {
        this.card = card;
        this.main = main;
        this.teams = Arrays.asList(teams);
        this.leagues = Arrays.asList(leagues);
        Context ctx = card.getContext();
        density = ctx.getResources().getDisplayMetrics().density;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
    }

    /** Starts reading while the card is open; the first draw says so until the scores arrive. */
    void open() {
        if (thread != null) return;
        if (card.getChildCount() == 0) {
            card.addView(cell("Loading scorecard…", 0, 13, regular, DIM, Gravity.START));
        }
        thread = new HandlerThread("scorecard");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                Match m = fetch();
                main.post(() -> { if (bg == h) draw(m); });
                if (bg == h) h.postDelayed(this, REFRESH_MS);
            }
        });
    }

    void close() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    // ---- reading ---------------------------------------------------------------------------------

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private boolean followed(JSONObject league, JSONObject event) {
        if (leagues.contains(league.optString("name"))) return true;
        JSONArray comps = event.optJSONArray("competitors");
        for (int i = 0; comps != null && i < comps.length(); i++) {
            if (teams.contains(comps.optJSONObject(i).optString("displayName"))) return true;
        }
        return false;
    }

    /** Tests, ODIs and T20Is between national sides: ESPN's international class, 0 for domestic and A-team games. */
    private static boolean international(JSONObject event) {
        JSONObject cls = event.optJSONObject("class");
        String id = cls == null ? "" : cls.optString("internationalClassId", "");
        return !id.isEmpty() && !"0".equals(id);
    }

    /** null when the feed could not be read; a Match with no title when no match qualifies. */
    private Match fetch() {
        try {
            JSONObject feed = get(FEED);
            if (feed == null) return null;
            JSONObject bestLeague = null, best = null;
            int bestRank = -1;
            String bestDate = "";
            JSONArray sports = feed.optJSONArray("sports");
            for (int s = 0; sports != null && s < sports.length(); s++) {
                JSONArray lgs = sports.getJSONObject(s).optJSONArray("leagues");
                for (int l = 0; lgs != null && l < lgs.length(); l++) {
                    JSONObject league = lgs.getJSONObject(l);
                    JSONArray events = league.optJSONArray("events");
                    for (int e = 0; events != null && e < events.length(); e++) {
                        JSONObject ev = events.getJSONObject(e);
                        boolean mine = followed(league, ev);
                        if (!mine && !international(ev)) continue;
                        // Live beats finished beats upcoming, a followed match beats anyone else's in the same state;
                        // among finished the latest, among upcoming the soonest.
                        String state = ev.optString("status");
                        int rank = ("in".equals(state) ? 3 : "post".equals(state) ? 2 : 1) * 2 + (mine ? 1 : 0);
                        String date = ev.optString("date");
                        boolean upcoming = rank <= 3;
                        boolean better = rank > bestRank || rank == bestRank
                                && (upcoming ? date.compareTo(bestDate) < 0 : date.compareTo(bestDate) > 0);
                        if (better) {
                            bestRank = rank;
                            bestDate = date;
                            best = ev;
                            bestLeague = league;
                        }
                    }
                }
            }
            Match m = new Match();
            if (best == null) return m;
            m.state = best.optString("status");
            String abbr = bestLeague.optString("abbreviation");
            m.title = best.optString("name") + "  ·  " + (abbr.isEmpty() ? bestLeague.optString("name") : abbr)
                    + (best.optString("title").isEmpty() ? "" : " · " + best.optString("title"));
            m.status = best.optString("summary");
            JSONObject full = best.optJSONObject("fullStatus");
            if (full != null) m.note = full.optString("longSummary", full.optString("summary"));
            JSONArray comps = best.optJSONArray("competitors");
            for (int i = 0; comps != null && i < comps.length(); i++) {
                JSONObject t = comps.getJSONObject(i);
                String score = t.optString("score");
                m.totals.add(new String[]{t.optString("displayName"), score.isEmpty() ? "Yet to bat" : score});
            }
            if (!"pre".equals(m.state)) readScorecard(m, get(String.format(SUMMARY, bestLeague.optString("id"),
                    best.optString("id"))));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stat(JSONObject stats, String name) {
        return stats.optString(name, "");
    }

    /** The latest innings' batting and the bowling against it, from the summary's rosters. */
    private void readScorecard(Match m, JSONObject summary) {
        if (summary == null) return;
        JSONArray rosters = summary.optJSONArray("rosters");
        if (rosters == null) return;
        // Per player and innings: the "general" stats as a name -> displayValue map, plus the batting details.
        int latest = 0;
        List<Object[]> rows = new ArrayList<>();   // {team, name, period, stats, battingDetails}
        for (int t = 0; t < rosters.length(); t++) {
            JSONObject team = rosters.optJSONObject(t);
            String teamName = team.optJSONObject("team") == null ? "" : team.optJSONObject("team").optString("displayName");
            JSONArray roster = team.optJSONArray("roster");
            for (int p = 0; roster != null && p < roster.length(); p++) {
                JSONObject player = roster.optJSONObject(p);
                JSONObject athlete = player.optJSONObject("athlete");
                String name = athlete == null ? "" : athlete.optString("displayName");
                JSONArray innings = player.optJSONArray("linescores");
                for (int i = 0; innings != null && i < innings.length(); i++) {
                    JSONObject inn = innings.optJSONObject(i);
                    JSONObject stats = flatten(inn.optJSONObject("statistics"));
                    if (stats.length() == 0) {
                        JSONArray inner = inn.optJSONArray("linescores");
                        if (inner != null && inner.length() > 0) stats = flatten(inner.optJSONObject(0).optJSONObject("statistics"));
                    }
                    int period = inn.optInt("period", stats.optInt("inningsNumber", 1));
                    boolean batted = "1".equals(stat(stats, "batted"));
                    String overs = stat(stats, "overs");
                    boolean bowled = !overs.isEmpty() && !"0".equals(overs) && !"-".equals(overs);
                    if (!batted && !bowled) continue;
                    latest = Math.max(latest, period);
                    // The dismissal and "at the crease" sit in statistics.batting (older feeds put them on the innings).
                    JSONObject batting = inn.optJSONObject("batting");
                    JSONObject statistics = inn.optJSONObject("statistics");
                    if (batting == null && statistics != null) batting = statistics.optJSONObject("batting");
                    rows.add(new Object[]{teamName, name, period, stats, batting, batted, bowled});
                }
            }
        }
        List<Object[]> batters = new ArrayList<>();
        for (Object[] r : rows) {
            if ((int) r[2] != latest) continue;
            JSONObject s = (JSONObject) r[3];
            if ((boolean) r[5]) {
                m.battingTeam = (String) r[0];
                batters.add(r);
            }
            if ((boolean) r[6]) {
                m.bowling.add(new String[]{(String) r[1], stat(s, "overs"), stat(s, "maidens"), stat(s, "conceded"),
                        stat(s, "wickets"), stat(s, "economyRate")});
            }
        }
        batters.sort((a, b) -> ((JSONObject) a[3]).optInt("battingPosition", 99)
                - ((JSONObject) b[3]).optInt("battingPosition", 99));
        for (Object[] r : batters) {
            JSONObject s = (JSONObject) r[3];
            JSONObject bat = (JSONObject) r[4];
            String how;
            if ("1".equals(stat(s, "outs"))) {
                JSONObject out = bat == null ? null : bat.optJSONObject("outDetails");
                // ESPN writes "c &amp; b" and the keeper's "&dagger;" as HTML entities.
                how = out == null ? "out" : android.text.Html.fromHtml(out.optString("shortText", "out"),
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
            } else {
                how = "not out";
            }
            boolean atCrease = bat != null && bat.optBoolean("active");
            m.batting.add(new String[]{r[1] + (atCrease ? " *" : ""), how, stat(s, "runs"), stat(s, "ballsFaced"),
                    stat(s, "fours"), stat(s, "sixes"), stat(s, "strikeRate")});
        }
    }

    /** {"categories":[{"stats":[{"name","displayValue"}]}]} -> {name: displayValue}. */
    private static JSONObject flatten(JSONObject statistics) {
        JSONObject out = new JSONObject();
        if (statistics == null) return out;
        JSONArray cats = statistics.optJSONArray("categories");
        for (int c = 0; cats != null && c < cats.length(); c++) {
            JSONArray stats = cats.optJSONObject(c).optJSONArray("stats");
            for (int i = 0; stats != null && i < stats.length(); i++) {
                JSONObject st = stats.optJSONObject(i);
                try {
                    out.put(st.optString("name"), st.optString("displayValue"));
                } catch (Exception ignored) {
                    // A malformed stat is left out.
                }
            }
        }
        return out;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    private void draw(Match m) {
        if (m == null && card.getChildCount() > 1) return;   // keep the last scorecard through a failed read
        card.removeAllViews();
        if (m == null) {
            card.addView(cell("Could not reach the scores. Trying again…", 0, 13, regular, DIM, Gravity.START));
            return;
        }
        if (m.title == null) {
            card.addView(cell("No international, IPL or WPL match right now", 0, 13, regular, DIM, Gravity.START));
            return;
        }
        LinearLayout head = row();
        String badge = "in".equals(m.state) ? "● LIVE" : "post".equals(m.state) ? "RESULT" : "UPCOMING";
        head.addView(cell(badge, 0, 12, medium, "in".equals(m.state) ? LIVE : ACCENT, Gravity.START));
        TextView title = cell("   " + m.title, 0, 12, regular, DIM, Gravity.START);
        head.addView(title);
        card.addView(head);
        for (String[] t : m.totals) {
            LinearLayout r = row();
            r.addView(cell(t[0], 150, 15, medium, TEXT, Gravity.START));
            r.addView(cell(t[1], 0, 15, regular, TEXT, Gravity.START));
            card.addView(r);
        }
        String note = !TextUtils.isEmpty(m.note) ? m.note : m.status;
        if (!TextUtils.isEmpty(note)) card.addView(cell(note, 0, 12, regular, DIM, Gravity.START));

        if (!m.batting.isEmpty()) {
            card.addView(spacer());
            card.addView(table(new String[]{(m.battingTeam == null ? "" : m.battingTeam.toUpperCase()) + " BATTING",
                    "", "R", "B", "4s", "6s", "SR"}, BAT, true));
            for (String[] r : m.batting) card.addView(table(r, BAT, false));
        }
        if (!m.bowling.isEmpty()) {
            card.addView(spacer());
            card.addView(table(new String[]{"BOWLING", "O", "M", "R", "W", "ECON"}, BOWL, true));
            for (String[] r : m.bowling) card.addView(table(r, BOWL, false));
        }
    }

    private LinearLayout table(String[] cells, int[] widths, boolean header) {
        LinearLayout r = row();
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            boolean name = i == 0;
            boolean dismissal = widths == BAT && i == 1;
            int color = header ? DIM : dismissal ? DIM : TEXT;
            int size = header ? 10 : dismissal ? 11 : 12;
            Typeface face = header || name ? medium : regular;
            r.addView(cell(cells[i], widths[i], size, face, color, name || dismissal ? Gravity.START : Gravity.END));
        }
        return r;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(card.getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Math.round(1.5f * density), 0, Math.round(1.5f * density));
        return r;
    }

    private TextView spacer() {
        TextView t = new TextView(card.getContext());
        t.setHeight(Math.round(6 * density));
        return t;
    }

    private TextView cell(String text, int widthDp, int sp, Typeface face, int color, int gravity) {
        TextView t = new TextView(card.getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setGravity(gravity);
        if (face != null) t.setTypeface(face);
        if (widthDp > 0) {
            t.setLayoutParams(new LinearLayout.LayoutParams(Math.round(widthDp * density),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return t;
    }
}
