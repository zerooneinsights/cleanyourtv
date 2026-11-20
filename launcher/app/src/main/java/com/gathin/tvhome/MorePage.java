package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * A second page under Home: Down from the app row slides the whole screen up and this page in from below; Up at its
 * top, or Back, slides Home back. Up and Down scroll it while it's longer than the screen. It holds detail cards that
 * don't fit on Home, starting with the full commute both ways (the script's every field: drive time, traffic, arrival,
 * distance, road, best time to leave, slowest stretch, alternative road).
 */
final class MorePage {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int GREEN = 0xFF4ADE80;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;
    private static final long SLIDE_MS = 320;

    private final ScrollView page;
    private final LinearLayout content;
    /** Home's own views, slid up together as the page comes in. */
    private final List<View> home;
    private final View glass;
    private final Runnable onClosed;
    private final float d;
    private Typeface regular, medium;
    private LinearLayout commuteRow;
    private TextView commuteUpdated;
    /** Fixed sections in page order, filled as their data comes in; gone until then. */
    private final LinearLayout commuteSection;
    private final LinearLayout newsSection;
    private boolean open;
    private float glassAlpha = 1f;

    MorePage(ScrollView page, List<View> home, View glass, Runnable onClosed) {
        this.page = page;
        this.home = home;
        this.glass = glass;
        this.onClosed = onClosed;
        Context ctx = page.getContext();
        d = ctx.getResources().getDisplayMetrics().density;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(60), px(56), px(60), px(48));
        page.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.setFocusable(true);
        page.setVisibility(View.GONE);
        page.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return keyCode == KeyEvent.KEYCODE_BACK;
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    close(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (page.getScrollY() <= 0) close(true);
                    else page.smoothScrollBy(0, -px(220));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    page.smoothScrollBy(0, px(220));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
        });

        TextView hint = text("▲  Up or Back for Home", 12, regular, DIM);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        commuteSection = section();
        newsSection = section();
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(page.getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        s.setVisibility(View.GONE);
        content.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** A section's title and, when sub isn't null, the dim line under it (returned, for later updates). */
    private TextView heading(LinearLayout section, String title, String sub, int topMargin) {
        TextView t = text(title, 22, medium, TEXT);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = px(topMargin);
        section.addView(t, tlp);
        if (sub == null) return null;
        TextView u = text(sub, 12, regular, DIM);
        LinearLayout.LayoutParams ulp = wrap();
        ulp.topMargin = px(2);
        section.addView(u, ulp);
        return u;
    }

    // ---- news ---------------------------------------------------------------------------------

    /** Every place's headlines ({label, rows of {headline, meta}}), two places to a row of cards. */
    @SuppressWarnings("unchecked")
    void showNews(List<Object[]> places) {
        newsSection.removeAllViews();
        newsSection.setVisibility(View.VISIBLE);
        heading(newsSection, "News", null, 36);
        LinearLayout row = null;
        for (int i = 0; i < places.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(page.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = px(14);
                newsSection.addView(row, rlp);
            }
            LinearLayout card = card();
            card.addView(text((String) places.get(i)[0], 16, medium, DIM));
            List<String[]> rows = (List<String[]>) places.get(i)[1];
            if (rows.isEmpty()) {
                TextView none = text("No recent news", 15, regular, DIM);
                LinearLayout.LayoutParams nlp = wrap();
                nlp.topMargin = px(12);
                card.addView(none, nlp);
            }
            for (String[] r : rows) {
                TextView h = text(r[0], 15, regular, TEXT);
                h.setSingleLine(false);
                h.setMaxLines(2);
                LinearLayout.LayoutParams hlp = wrap();
                hlp.topMargin = px(12);
                card.addView(h, hlp);
                TextView m = text(r[1], 11, regular, DIM);
                LinearLayout.LayoutParams mlp = wrap();
                mlp.topMargin = px(3);
                card.addView(m, mlp);
            }
            row.addView(card, column(i % 2 == 1));
        }
        if (places.size() % 2 == 1 && row != null) {       // an odd last place keeps its half width
            row.addView(new View(page.getContext()), column(true));
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(page.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(28), px(22), px(28), px(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(22));
        bg.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        card.setBackground(bg);
        return card;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        open = true;
        float h = page.getRootView().getHeight();
        glassAlpha = glass.getAlpha();
        glass.animate().cancel();
        glass.animate().setStartDelay(0).alpha(0f).setDuration(120).start();
        for (View v : home) v.animate().translationY(-h).setDuration(SLIDE_MS).start();
        page.scrollTo(0, 0);
        page.setVisibility(View.VISIBLE);
        page.setTranslationY(h);
        page.animate().translationY(0).setDuration(SLIDE_MS).start();
        page.requestFocus();
    }

    /** animate false: at once, as when Home is left with the page up. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        float h = page.getRootView().getHeight();
        for (View v : home) {
            v.animate().cancel();
            if (animate) v.animate().translationY(0).setDuration(SLIDE_MS).start();
            else v.setTranslationY(0);
        }
        page.animate().cancel();
        if (animate) {
            page.animate().translationY(h).setDuration(SLIDE_MS).withEndAction(() -> {
                if (!open) page.setVisibility(View.GONE);
            }).start();
            glass.animate().alpha(glassAlpha).setStartDelay(SLIDE_MS / 2).setDuration(160)
                    .withEndAction(() -> glass.animate().setStartDelay(0)).start();
        } else {
            page.setVisibility(View.GONE);
            glass.setAlpha(glassAlpha);
        }
        onClosed.run();
    }

    // ---- commute ------------------------------------------------------------------------------

    /** The script's latest answer ({"toOffice":{..},"toHome":{..},"updatedAt"}), on the main thread. */
    void showCommute(JSONObject json, String toLabel, String backLabel) {
        if (commuteRow == null) {
            commuteSection.setVisibility(View.VISIBLE);
            commuteUpdated = heading(commuteSection, "Commute", "", 14);
            commuteRow = new LinearLayout(page.getContext());
            commuteRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = px(14);
            commuteSection.addView(commuteRow, rlp);
        }
        String updated = json.optString("updatedAt");
        commuteUpdated.setText(updated.isEmpty() ? "Google Maps, leaving now" : "Google Maps · updated " + updated);
        commuteRow.removeAllViews();
        JSONObject there = json.optJSONObject("toOffice");
        JSONObject back = json.optJSONObject("toHome");
        if (there == null && json.has("minutes")) {        // the one-direction shape: {minutes,..,"back":{..}}
            there = json;
            back = json.optJSONObject("back");
        }
        if (there != null) commuteRow.addView(trip(toLabel, there), column(false));
        if (back != null) commuteRow.addView(trip(backLabel, back), column(true));
    }

    private LinearLayout.LayoutParams column(boolean second) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (second) lp.setMarginStart(px(24));
        return lp;
    }

    private View trip(String label, JSONObject t) {
        LinearLayout card = card();
        card.addView(text(label, 16, medium, DIM));
        if (t.has("error")) {
            card.addView(text("No route right now", 18, regular, TEXT));
            return card;
        }
        int free = t.optInt("minutes");
        int minutes = t.isNull("trafficMinutes") ? free : t.optInt("trafficMinutes", free);
        int delay = t.has("delayMinutes") ? t.optInt("delayMinutes") : Math.max(0, minutes - free);
        float share = minutes <= delay ? 1f : delay / (float) Math.max(1, minutes - delay);
        int colour = share < 0.1f ? GREEN : share < 0.33f ? AMBER : RED;

        LinearLayout big = new LinearLayout(page.getContext());
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        big.addView(text(formatMinutes(minutes), 40, medium, colour));
        String eta = t.optString("eta");
        TextView reach = text((eta.isEmpty() ? "" : "reach " + eta)
                + (t.has("km") ? (eta.isEmpty() ? "" : " · ") + String.format(Locale.US, "%.1f km", t.optDouble("km")) : ""),
                16, regular, DIM);
        LinearLayout.LayoutParams rlp = wrap();
        rlp.setMarginStart(px(14));
        rlp.bottomMargin = px(7);
        big.addView(reach, rlp);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = px(4);
        card.addView(big, blp);

        row(card, "Traffic", delay > 0 ? "+" + formatMinutes(delay) + " slower than usual" : "No delays", delay > 0 ? colour : GREEN);
        String via = t.optString("via");
        if (!via.isEmpty()) row(card, "Route", "via " + via, TEXT);

        JSONObject best = t.optJSONObject("bestTimeToLeave");
        if (best != null && !best.optString("time").isEmpty()) {
            String day = best.optString("day");
            String when = ("Tomorrow".equalsIgnoreCase(day) ? "Tomorrow " : "") + best.optString("time");
            int bm = best.optInt("minutes");
            row(card, "Best time to leave", when + (bm > 0 ? " · " + formatMinutes(bm) : ""), GREEN);
        }

        JSONObject slow = t.optJSONObject("slowestSegment");
        if (slow != null) {
            String road = slow.optString("road");
            // The script reads the road from turn instructions; "Sharp left" is a turn, not a road.
            boolean isTurn = road.isEmpty() || road.matches("(?i)^(turn|sharp|slight|keep|head|make|continue|take|merge|exit)\\b.*");
            String s = (isTurn ? "" : road + " · ")
                    + slow.optInt("speedKmh") + " km/h for " + String.format(Locale.US, "%.1f km", slow.optDouble("km"))
                    + " (" + formatMinutes(Math.max(1, slow.optInt("minutes"))) + ")";
            row(card, "Slowest stretch", s, slow.optInt("speedKmh") < 15 ? AMBER : TEXT);
        }

        JSONObject alt = t.optJSONObject("alt");
        if (alt != null) {
            int af = alt.optInt("minutes");
            int am = alt.isNull("trafficMinutes") ? af : alt.optInt("trafficMinutes", af);
            String altVia = alt.optString("via");
            int diff = am - minutes;
            row(card, "Other route", (altVia.isEmpty() ? "" : "via " + altVia + " · ") + formatMinutes(am)
                    + String.format(Locale.US, " · %.1f km", alt.optDouble("km"))
                    + (diff > 0 ? "  (+" + formatMinutes(diff) + ")" : diff < 0 ? "  (" + formatMinutes(-diff) + " quicker)" : "  (same time)"),
                    TEXT);
        }
        return card;
    }

    private void row(LinearLayout card, String name, String value, int colour) {
        TextView n = text(name, 12, medium, DIM);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = px(14);
        card.addView(n, nlp);
        TextView v = text(value, 17, regular, colour);
        v.setSingleLine(false);
        v.setMaxLines(2);
        LinearLayout.LayoutParams vlp = wrap();
        vlp.topMargin = px(3);
        card.addView(v, vlp);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(page.getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }

    private static String formatMinutes(int m) {
        return m < 60 ? m + " min" : String.format(Locale.US, "%d h %02d min", m / 60, m % 60);
    }
}
