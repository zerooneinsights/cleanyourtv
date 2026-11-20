package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * config.xml show_news: a card under the ticker with a tab per place and, under the tabs, the chosen place's newest
 * local headlines with source and age. Up from the apps reaches the tabs, Left/Right moves between places (the
 * headlines follow the focus), Down goes back to the apps; Home opens on the first place every time. From Google News'
 * search feeds (no account or key): read on every return to Home and every 10 minutes while it stays up, 5 minutes
 * after a failure.
 *
 * Places are "Label|search|en or hi|keyword,keyword" separated by ";" (a search starting with http is a local paper's own
 * RSS feed, read whole) (news_places, or news.places in
 * launcher/local.properties, which is never committed): a headline counts only when its title has one of the
 * keywords, and titles with any news_exclude phrase ("Gold Rate", weather pages) are dropped.
 */
final class NewsCard {
    private static final long REFRESH_MS = 600_000L;
    private static final long RETRY_MS = 300_000L;
    private static final int PER_PLACE = 3;
    /** Kept per place for the full list on the second page; Home shows the first PER_PLACE. */
    private static final int KEEP = 10;
    private static final int DIM = 0x99FFFFFF;
    private static final int TEXT = 0xF2FFFFFF;

    private static final class Place {
        String label, query, lang;
        String[] keywords;
        /** Its link answered the X posts script's JSON: posts, shown whole a page at a time. */
        volatile boolean posts;
        /** When it was last read successfully (posts are read hourly, not with every news refresh). */
        long readAt;
    }

    /** The X posts are checked once an hour; the news every REFRESH_MS. */
    private static final long POSTS_REFRESH_MS = 3_600_000L;

    /** A post is shown whole up to X's 280 characters; longer ones end in "…". */
    private static final int POST_MAX = 280;
    /** About how many characters fit a line of the card, and how many lines a page of posts may take. */
    private static final int POST_LINE_CHARS = 62;
    private static final int POST_PAGE_LINES = 6;
    /** A page of posts takes about as much room as the news: text lines plus one source line per post. */
    private static final int POST_PAGE_UNITS = 9;
    private static final long POST_PAGE_MS = 10_000L;
    private int postPage;
    private final Runnable postTick = new Runnable() {
        @Override
        public void run() {
            if (!places.isEmpty() && places.get(selected).posts) {
                postPage++;
                draw();
            }
            main.postDelayed(this, POST_PAGE_MS);
        }
    };

    private static final class Story {
        String title, source;
        long time;
    }

    private final LinearLayout card;
    private final Handler main;
    private final List<Place> places = new ArrayList<>();
    private final String[] exclude;
    private final List<List<Story>> stories = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private final TextView[] headlines = new TextView[PER_PLACE];
    private final TextView[] ages = new TextView[PER_PLACE];
    /** "4/5": which page of posts is up, in the card's bottom-right corner (like the calendar's "Updated"). */
    private TextView pageLabel;
    private final SharedPreferences prefs;
    private int selected;
    private HandlerThread thread;
    private volatile Handler bg;

    /** onUp / onDown: where focus goes from the tabs (the top bar, the apps). */
    NewsCard(LinearLayout card, Handler main, String placesSpec, String[] exclude, Runnable onUp, Runnable onDown) {
        this.card = card;
        this.main = main;
        this.exclude = exclude;
        for (String spec : placesSpec.split(";")) {
            String[] f = spec.trim().split("\\|");
            if (f.length < 2 || f[0].trim().isEmpty()) continue;
            Place p = new Place();
            p.label = f[0].trim();
            p.query = f[1].trim();
            p.lang = f.length > 2 && "hi".equalsIgnoreCase(f[2].trim()) ? "hi" : "en";
            p.keywords = (f.length > 3 ? f[3] : f[0]).split(",");
            places.add(p);
            stories.add(new ArrayList<>());
        }
        Context ctx = card.getContext();
        prefs = ctx.getSharedPreferences("news", Context.MODE_PRIVATE);
        // Home always opens on the first place (the X posts, when they're first), not the last one chosen.
        float d = ctx.getResources().getDisplayMetrics().density;
        Typeface regular = null, medium = null;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }

        // The tabs: dim at rest, a soft pill when chosen, white with dark text when focused.
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(tabRow);
        for (int i = 0; i < places.size(); i++) {
            TextView tab = text(ctx, places.get(i).label, 12, medium, 0);
            tab.setPadding(Math.round(12 * d), Math.round(5 * d), Math.round(12 * d), Math.round(5 * d));
            tab.setFocusable(true);
            tab.setTextColor(new ColorStateList(new int[][]{
                    {android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                    new int[]{0xFF111111, 0xFFFFFFFF, DIM}));
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, pill(0xFFFFFFFF, d));
            states.addState(new int[]{android.R.attr.state_selected}, pill(0x33FFFFFF, d));
            states.addState(new int[]{}, pill(0x00000000, d));
            tab.setBackground(states);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(4 * d));
            tabRow.addView(tab, lp);
            int index = i;
            tab.setOnFocusChangeListener((v, focused) -> { if (focused) select(index); });
            tab.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        onUp.run();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        onDown.run();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index == 0;                       // stay on the row at its ends
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return index == places.size() - 1;
                    default:
                        return false;
                }
            });
            tabs.add(tab);
        }

        // The chosen place's stories: the headline (two lines), its source and age under it, a hairline between.
        for (int i = 0; i < PER_PLACE; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Math.round(6 * d), Math.round((i == 0 ? 8 : 0) * d), Math.round(2 * d), Math.round(6 * d));
            if (i > 0) {
                View rule = new View(ctx);
                rule.setBackgroundColor(0x1FFFFFFF);
                LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.max(1, Math.round(d)));
                ruleLp.bottomMargin = Math.round(6 * d);
                row.addView(rule, ruleLp);
            }
            headlines[i] = text(ctx, i == 0 ? "…" : "", 12, regular, TEXT);
            headlines[i].setSingleLine(false);
            headlines[i].setMaxLines(2);
            headlines[i].setLineSpacing(1 * d, 1f);
            row.addView(headlines[i], new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            ages[i] = text(ctx, "", 10, regular, DIM);
            LinearLayout.LayoutParams ageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ageLp.topMargin = Math.round(4 * d);
            row.addView(ages[i], ageLp);
            ages[i].setVisibility(View.GONE);            // the source and age sit at the end of the headline
            card.addView(row);
        }
        // Push the page count to the bottom of the fixed-height card, at its right edge.
        card.addView(new View(ctx), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        pageLabel = text(ctx, "", 9, regular, 0x66FFFFFF);
        pageLabel.setGravity(Gravity.END);
        LinearLayout.LayoutParams pageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pageLp.setMarginEnd(Math.round(2 * d));
        card.addView(pageLabel, pageLp);
        select(selected);
    }

    private static GradientDrawable pill(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14 * d);
        return g;
    }

    private void select(int index) {
        if (index != selected) postPage = 0;
        selected = index;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(i == index);
        prefs.edit().putString("tab_label", places.get(index).label).apply();
        draw();
    }

    private static TextView text(Context ctx, String s, int sp, Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (face != null) t.setTypeface(face);
        return t;
    }

    void start() {
        if (thread != null || places.isEmpty()) return;
        card.setVisibility(View.VISIBLE);
        select(0);                                         // every return to Home starts on the first tab
        main.removeCallbacks(postTick);
        main.postDelayed(postTick, POST_PAGE_MS);
        thread = new HandlerThread("news");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                boolean failed = false;
                for (int i = 0; i < places.size(); i++) {
                    Place place = places.get(i);
                    if (place.posts && System.currentTimeMillis() - place.readAt < POSTS_REFRESH_MS) continue;
                    List<Story> found = fetch(place);
                    if (found == null) {
                        failed = true;
                        continue;
                    }
                    place.readAt = System.currentTimeMillis();
                    int at = i;
                    main.post(() -> {
                        if (bg != h) return;
                        stories.set(at, found);
                        if (at == selected) draw();
                        notifyFull();
                    });
                }
                if (bg == h) h.postDelayed(this, failed ? RETRY_MS : REFRESH_MS);
            }
        });
    }

    /**
     * Told on the main thread whenever a place's stories change: every place in order as {label, rows}, each row
     * {headline, "Source · 2h"}.
     */
    private java.util.function.Consumer<List<Object[]>> fullListener;

    void setFullListener(java.util.function.Consumer<List<Object[]>> listener) {
        fullListener = listener;
    }

    private void notifyFull() {
        if (fullListener == null) return;
        long now = System.currentTimeMillis();
        List<Object[]> all = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            List<String[]> rows = new ArrayList<>();
            for (Story s : stories.get(i)) {
                rows.add(new String[]{s.title, (s.source.isEmpty() ? "" : s.source + " · ") + age(now - s.time)});
            }
            all.add(new Object[]{places.get(i).label, rows});
        }
        fullListener.accept(all);
    }

    void stop() {
        if (thread == null) return;
        main.removeCallbacks(postTick);
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** The chosen place's stories, one per row; "No recent news" when it has none yet. */
    private void draw() {
        if (places.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Story> list = stories.get(selected);
        if (places.get(selected).posts && !list.isEmpty()) {
            drawPosts(list, now);
            return;
        }
        if (pageLabel != null) pageLabel.setText("");
        for (int i = 0; i < PER_PLACE; i++) {
            ((View) headlines[i].getParent()).setVisibility(i < Math.max(1, list.size()) ? View.VISIBLE : View.GONE);
            headlines[i].setMaxLines(2);
        }
        for (int i = 0; i < PER_PLACE; i++) {
            if (i < list.size()) {
                Story s = list.get(i);
                String meta = (s.source.isEmpty() ? "" : s.source + " · ") + age(now - s.time);
                headlines[i].setText(withMeta(s.title, meta, 2 * POST_LINE_CHARS - meta.length() - 4));
                headlines[i].setTextColor(TEXT);
                ages[i].setText("");
            } else {
                headlines[i].setText(i == 0 ? "No recent news" : "");
                headlines[i].setTextColor(DIM);
                ages[i].setText("");
            }
        }
    }

    /**
     * Posts whole (up to POST_MAX), the handle and age after each, as many to a page as fit POST_PAGE_LINES; the
     * pages turn every POST_PAGE_MS.
     */
    private void drawPosts(List<Story> list, long now) {
        List<int[]> pages = new ArrayList<>();          // {first, count}
        for (int i = 0; i < list.size(); ) {
            int lines = 0, count = 0;
            while (i + count < list.size() && count < PER_PLACE) {
                int need = (postText(list.get(i + count)).length() + 20) / POST_LINE_CHARS + 1;
                // Fits the card's height: about 16dp a line and 14dp a row's gap (8 and 7 here), 156dp in all.
                if (count > 0 && 8 * (lines + need) + 7 * (count + 1) > 78) break;
                lines += need;
                count++;
            }
            pages.add(new int[]{i, count});
            i += count;
        }
        int[] page = pages.get(Math.floorMod(postPage, pages.size()));
        if (pageLabel != null) {
            pageLabel.setText(pages.size() > 1 ? (Math.floorMod(postPage, pages.size()) + 1) + "/" + pages.size() : "");
        }
        for (int i = 0; i < PER_PLACE; i++) {
            View row = (View) headlines[i].getParent();
            if (i >= page[1]) {
                row.setVisibility(View.GONE);
                continue;
            }
            Story s = list.get(page[0] + i);
            row.setVisibility(View.VISIBLE);
            headlines[i].setMaxLines(POST_PAGE_LINES);
            headlines[i].setTextColor(TEXT);
            String meta = s.source + " · " + age(now - s.time);
            headlines[i].setText(withMeta(postText(s), meta, Integer.MAX_VALUE));
        }
    }

    /** The text (cut to maxChars with "…"), then its source and age, smaller and dimmer, on the same line. */
    private static CharSequence withMeta(String text, String meta, int maxChars) {
        String t = text.length() > maxChars ? text.substring(0, Math.max(1, maxChars - 1)).trim() + "…" : text;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(t);
        int start = b.length();
        b.append("   ").append(meta);
        b.setSpan(new android.text.style.ForegroundColorSpan(DIM), start, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.82f), start, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** Focus the chosen tab (Up from the apps under the card); false with no places. */
    boolean focusTab() {
        if (tabs.isEmpty() || card.getVisibility() != View.VISIBLE) return false;
        return tabs.get(selected).requestFocus();
    }

    private static String postText(Story s) {
        return s.title.length() <= POST_MAX ? s.title : s.title.substring(0, POST_MAX - 1).trim() + "…";
    }

    private static String age(long ms) {
        long min = Math.max(0, ms / 60_000L);
        if (min < 60) return min + "m";
        if (min < 48 * 60) return (min / 60) + "h";
        return (min / (24 * 60)) + "d";
    }

    /** The place's newest few matching stories, newest first; null when the feed could not be read. */
    private List<Story> fetch(Place p) {
        HttpURLConnection c = null;
        try {
            // A place's search is either a Google News search or, starting with http, a local paper's own RSS feed
            // (a Punekar News feed, say), read as it is: every story counts, the source is the feed's own name. An http
            // link answering JSON is the owner's X posts script (see posts()).
            boolean direct = p.query.startsWith("http");
            String hl = "hi".equals(p.lang) ? "hl=hi&gl=IN&ceid=IN:hi" : "hl=en-IN&gl=IN&ceid=IN:en";
            URL url = new URL(direct ? p.query : "https://news.google.com/rss/search?q="
                    + URLEncoder.encode(p.query + " when:3d", "UTF-8") + "&" + hl);
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
            }
            byte[] bytes = body.toByteArray();
            // The owner's X posts script answers JSON ({"posts":[..]}), not RSS: a post is a headline, its handle the source.
            int first = 0;
            while (first < bytes.length && Character.isWhitespace(bytes[first])) first++;
            if (first < bytes.length && bytes[first] == '{') {
                p.posts = true;
                return posts(new String(bytes, "UTF-8"));
            }
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(bytes));
            // Google News writes "GMT", WordPress feeds "+0000".
            SimpleDateFormat[] dates = {new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
                    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)};
            String feedName = "";
            NodeList channels = doc.getElementsByTagName("channel");
            if (direct && channels.getLength() > 0) {
                // "Punekar News - Pune's Daily News…" or, for a site search, "You searched for baner - Punekar News":
                // the paper's name either way.
                feedName = child((Element) channels.item(0), "title");
                boolean search = feedName.startsWith("You searched");
                int cut = search ? feedName.lastIndexOf(" - ") : feedName.indexOf(" - ");
                if (cut > 0) feedName = (search ? feedName.substring(cut + 3) : feedName.substring(0, cut)).trim();
            }
            List<Story> out = new ArrayList<>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = child(item, "title");
                if (title.isEmpty() || excluded(title) || !direct && !mentions(title, p.keywords)) continue;
                Story s = new Story();
                if (direct) {
                    s.title = title;
                    s.source = feedName;
                } else {
                    // Google News titles end in " - Source".
                    int dash = title.lastIndexOf(" - ");
                    s.title = dash > 0 ? title.substring(0, dash).trim() : title;
                    s.source = dash > 0 ? title.substring(dash + 3).trim() : child(item, "source");
                }
                String pub = child(item, "pubDate");
                for (SimpleDateFormat f : dates) {
                    try {
                        Date when = f.parse(pub);
                        if (when != null) {
                            s.time = when.getTime();
                            break;
                        }
                    } catch (Exception ignored) {
                        // try the next format
                    }
                }
                out.add(s);
            }
            out.sort((a, b) -> Long.compare(b.time, a.time));
            return out.size() > KEEP ? new ArrayList<>(out.subList(0, KEEP)) : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** {"posts":[{"handle","text","time"(ms),..}]}: newest first, excluded phrases dropped. */
    private List<Story> posts(String json) throws org.json.JSONException {
        org.json.JSONArray list = new org.json.JSONObject(json).optJSONArray("posts");
        List<Story> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            org.json.JSONObject post = list.optJSONObject(i);
            if (post == null) continue;
            String text = post.optString("text").trim();
            if (text.isEmpty() || excluded(text)) continue;
            Story s = new Story();
            s.title = text;
            s.source = post.optString("handle");
            s.time = post.optLong("time");
            out.add(s);
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out.size() > KEEP ? new ArrayList<>(out.subList(0, KEEP)) : out;
    }

    private static String child(Element item, String tag) {
        NodeList n = item.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }

    /** A keyword as a whole word (so "Goa" is not "Goals"), case-insensitive; non-Latin keywords as plain text. */
    private static boolean mentions(String title, String[] keywords) {
        for (String k : keywords) {
            String key = k.trim();
            if (key.isEmpty()) continue;
            boolean latin = key.chars().allMatch(ch -> ch < 128);
            if (latin ? java.util.regex.Pattern.compile("(?i)\\b" + java.util.regex.Pattern.quote(key) + "\\b")
                    .matcher(title).find() : title.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean excluded(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        for (String e : exclude) if (!e.isEmpty() && lower.contains(e.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
