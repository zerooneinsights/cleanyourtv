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
 * config.xml show_news: a card under the ticker with one row per place, each showing that place's latest local
 * headline, its source and age, and moving on to the next of its newest three every 20 seconds. From Google News'
 * search feeds (no account or key), read only while Home is resumed, every 30 minutes, 5 minutes after a failure.
 *
 * Places are "Label|search|en or hi|keyword,keyword" separated by ";" (news_places, or news.places in
 * launcher/local.properties, which is never committed): a headline counts only when its title has one of the
 * keywords, and titles with any news_exclude phrase ("Gold Rate", weather pages) are dropped.
 */
final class NewsCard {
    private static final long REFRESH_MS = 1_800_000L;
    private static final long RETRY_MS = 300_000L;
    private static final long ROTATE_MS = 20_000L;
    private static final int PER_PLACE = 3;
    private static final int DIM = 0x99FFFFFF;
    private static final int LABEL = 0xFF60A5FA;

    private static final class Place {
        String label, query, lang;
        String[] keywords;
    }

    private static final class Story {
        String title, source;
        long time;
    }

    private final LinearLayout card;
    private final Handler main;
    private final List<Place> places = new ArrayList<>();
    private final String[] exclude;
    private final TextView[] headlines, ages;
    private final List<List<Story>> stories = new ArrayList<>();
    private int turn;
    private HandlerThread thread;
    private volatile Handler bg;
    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            turn++;
            draw();
            main.postDelayed(this, ROTATE_MS);
        }
    };

    NewsCard(LinearLayout card, Handler main, String placesSpec, String[] exclude) {
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
        float d = ctx.getResources().getDisplayMetrics().density;
        Typeface regular = null, medium = null;
        try {
            regular = ctx.getResources().getFont(R.font.outfit_regular);
            medium = ctx.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        TextView heading = text(ctx, "LOCAL NEWS", 10, medium, DIM);
        heading.setLetterSpacing(0.12f);
        card.addView(heading);
        headlines = new TextView[places.size()];
        ages = new TextView[places.size()];
        for (int i = 0; i < places.size(); i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Math.round(4 * d), 0, 0);
            TextView label = text(ctx, places.get(i).label.toUpperCase(Locale.getDefault()), 10, medium, LABEL);
            label.setLetterSpacing(0.06f);
            row.addView(label, new LinearLayout.LayoutParams(Math.round(92 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            headlines[i] = text(ctx, "…", 12, regular, 0xF2FFFFFF);
            row.addView(headlines[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ages[i] = text(ctx, "", 10, regular, DIM);
            LinearLayout.LayoutParams ageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ageLp.setMarginStart(Math.round(8 * d));
            row.addView(ages[i], ageLp);
            card.addView(row);
        }
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
                    List<Story> found = fetch(places.get(i));
                    if (found == null) {
                        failed = true;
                        continue;
                    }
                    int at = i;
                    main.post(() -> {
                        if (bg != h) return;
                        stories.set(at, found);
                        draw();
                    });
                }
                if (bg == h) h.postDelayed(this, failed ? RETRY_MS : REFRESH_MS);
            }
        });
        main.removeCallbacks(rotate);
        main.postDelayed(rotate, ROTATE_MS);
    }

    void stop() {
        main.removeCallbacks(rotate);
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    /** Each row shows the next of its stories in turn; a place with none says so. */
    private void draw() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < places.size(); i++) {
            List<Story> list = stories.get(i);
            if (list.isEmpty()) {
                headlines[i].setText("No recent news");
                headlines[i].setTextColor(DIM);
                ages[i].setText("");
                continue;
            }
            Story s = list.get(turn % list.size());
            headlines[i].setText(s.title);
            headlines[i].setTextColor(0xF2FFFFFF);
            ages[i].setText((s.source.isEmpty() ? "" : s.source + " · ") + age(now - s.time));
        }
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
            String hl = "hi".equals(p.lang) ? "hl=hi&gl=IN&ceid=IN:hi" : "hl=en-IN&gl=IN&ceid=IN:en";
            URL url = new URL("https://news.google.com/rss/search?q=" + URLEncoder.encode(p.query + " when:3d", "UTF-8")
                    + "&" + hl);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            Document doc;
            try (InputStream in = c.getInputStream()) {
                doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            }
            SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            List<Story> out = new ArrayList<>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = child(item, "title");
                if (title.isEmpty() || !mentions(title, p.keywords) || excluded(title)) continue;
                Story s = new Story();
                // Google News titles end in " - Source".
                int dash = title.lastIndexOf(" - ");
                s.title = dash > 0 ? title.substring(0, dash).trim() : title;
                s.source = dash > 0 ? title.substring(dash + 3).trim() : child(item, "source");
                try {
                    Date when = rfc.parse(child(item, "pubDate"));
                    s.time = when == null ? 0 : when.getTime();
                } catch (Exception e) {
                    s.time = 0;
                }
                out.add(s);
            }
            out.sort((a, b) -> Long.compare(b.time, a.time));
            return out.size() > PER_PLACE ? new ArrayList<>(out.subList(0, PER_PLACE)) : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
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
