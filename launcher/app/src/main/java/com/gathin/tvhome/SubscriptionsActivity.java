package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Edits the subscriptions shown on Home's subscriptions card: the list on the left, and for the one picked (or a new
 * one) its name (typed, or picked from the TV's apps), expiry date, how often it renews, and Save, Mark renewed
 * (moves the date on by one cycle), Delete and Cancel. Everything is buttons, so the remote's OK does it all.
 */
public final class SubscriptionsActivity extends Activity {
    private List<Subscriptions.Item> items;
    private Subscriptions.Item draft;
    private int editing = -1;   // index in items, or -1 for a new one
    private List<String> appNames;
    private int appPick = -1;

    private LinearLayout list;
    private LinearLayout editor;
    private EditText name;
    private TextView date, cycle, renewed, delete;
    private Typeface regular, medium;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            regular = getResources().getFont(R.font.outfit_regular);
            medium = getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException ignored) {
            // The system font is fine.
        }
        items = Subscriptions.load(this);
        appNames = installedAppNames();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(56), dp(40), dp(56), dp(24));
        root.setBackgroundColor(0xFF0B0B0F);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = text(26, medium, 0xFFFFFFFF);
        title.setText("Subscriptions");
        left.addView(title);
        TextView sub = text(13, regular, 0x99FFFFFF);
        sub.setText("Pick one to edit, or add a new one. Back closes.");
        left.addView(sub);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(16);
        left.addView(scroll, scrollLp);

        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setVisibility(View.GONE);
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        editorLp.setMarginStart(dp(48));
        root.addView(editor, editorLp);
        buildEditor();
        setContentView(root);
        rebuildList(0);
    }

    private void buildEditor() {
        editor.addView(label("Name"));
        LinearLayout nameRow = row();
        name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTextColor(0xFFFFFFFF);
        name.setHintTextColor(0x66FFFFFF);
        name.setHint("e.g. Netflix");
        if (regular != null) name.setTypeface(regular);
        name.setPadding(dp(16), dp(10), dp(16), dp(10));
        name.setBackground(states(0x26FFFFFF, 0x40FFFFFF, true));
        nameRow.addView(name, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView pick = button("Pick app ›");
        pick.setOnClickListener(v -> {
            if (appNames.isEmpty()) return;
            appPick = (appPick + 1) % appNames.size();
            name.setText(appNames.get(appPick));
        });
        nameRow.addView(pick);
        editor.addView(nameRow);

        editor.addView(label("Expires on"));
        date = text(22, medium, 0xFFFFFFFF);
        editor.addView(date);
        // Two lines: six steps in one row run past the screen's edge.
        LinearLayout dateRow = row();
        addStep(dateRow, "− Day", 0, -1);
        addStep(dateRow, "+ Day", 0, 1);
        addStep(dateRow, "− Month", 1, -1);
        addStep(dateRow, "+ Month", 1, 1);
        editor.addView(dateRow);
        LinearLayout yearRow = row();
        ((LinearLayout.LayoutParams) yearRow.getLayoutParams()).topMargin = dp(8);
        addStep(yearRow, "− Year", 2, -1);
        addStep(yearRow, "+ Year", 2, 1);
        editor.addView(yearRow);

        editor.addView(label("Renews"));
        LinearLayout cycleRow = row();
        cycle = button("");
        cycle.setOnClickListener(v -> {
            int i = 0;
            while (i < Subscriptions.CYCLES.length && Subscriptions.CYCLES[i] != draft.cycleMonths) i++;
            draft.cycleMonths = Subscriptions.CYCLES[(i + 1) % Subscriptions.CYCLES.length];
            showDraft();
        });
        cycleRow.addView(cycle);
        editor.addView(cycleRow);

        LinearLayout actions = row();
        ((LinearLayout.LayoutParams) actions.getLayoutParams()).topMargin = dp(28);
        TextView save = button("Save");
        save.setOnClickListener(v -> save());
        renewed = button("Mark renewed");
        renewed.setOnClickListener(v -> {
            draft.expires = draft.expires.plusMonths(draft.cycleMonths == 0 ? 1 : draft.cycleMonths);
            save();
        });
        delete = button("Delete");
        delete.setOnClickListener(v -> {
            if (editing >= 0) items.remove(editing);
            Subscriptions.save(this, items);
            closeEditor(Math.max(0, editing - 1));
        });
        TextView cancel = button("Cancel");
        cancel.setOnClickListener(v -> closeEditor(Math.max(0, editing)));
        actions.addView(save);
        actions.addView(renewed);
        actions.addView(delete);
        actions.addView(cancel);
        editor.addView(actions);
    }

    /** A date step button: field 0 = day, 1 = month, 2 = year. */
    private void addStep(LinearLayout row, String label, int field, int by) {
        TextView b = button(label);
        b.setOnClickListener(v -> {
            draft.expires = field == 0 ? draft.expires.plusDays(by)
                    : field == 1 ? draft.expires.plusMonths(by) : draft.expires.plusYears(by);
            showDraft();
        });
        row.addView(b);
    }

    private void rebuildList(int focus) {
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            Subscriptions.Item it = items.get(i);
            TextView b = button(it.name + "  ·  " + Subscriptions.describe(it));
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            int index = i;
            b.setOnClickListener(v -> openEditor(index));
            list.addView(b, fullWidth());
        }
        TextView add = button("＋  Add subscription");
        add.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        add.setOnClickListener(v -> openEditor(-1));
        list.addView(add, fullWidth());
        list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
    }

    private void openEditor(int index) {
        editing = index;
        draft = new Subscriptions.Item();
        if (index >= 0) {
            Subscriptions.Item it = items.get(index);
            draft.name = it.name;
            draft.expires = it.expires;
            draft.cycleMonths = it.cycleMonths;
        }
        name.setText(draft.name);
        appPick = -1;
        renewed.setVisibility(index >= 0 ? View.VISIBLE : View.GONE);
        delete.setVisibility(index >= 0 ? View.VISIBLE : View.GONE);
        editor.setVisibility(View.VISIBLE);
        showDraft();
        name.requestFocus();
    }

    private void showDraft() {
        date.setText(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()).format(draft.expires)
                + "   ·   " + Subscriptions.describe(draft));
        cycle.setText(Subscriptions.cycleLabel(draft.cycleMonths) + "  ›");
    }

    private void save() {
        draft.name = name.getText().toString().trim();
        if (draft.name.isEmpty()) {
            Toast.makeText(this, "Add a name first", Toast.LENGTH_SHORT).show();
            name.requestFocus();
            return;
        }
        if (editing >= 0) items.set(editing, draft);
        else items.add(draft);
        items.sort((x, y) -> x.expires.compareTo(y.expires));
        Subscriptions.save(this, items);
        closeEditor(items.indexOf(draft));
    }

    private void closeEditor(int focus) {
        editor.setVisibility(View.GONE);
        editing = -1;
        rebuildList(focus);
    }

    @Override
    public void onBackPressed() {
        if (editor.getVisibility() == View.VISIBLE) closeEditor(Math.max(0, editing));
        else super.onBackPressed();
    }

    /** The labels of the TV's launchable apps, for "Pick app". */
    private List<String> installedAppNames() {
        PackageManager pm = getPackageManager();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                if (getPackageName().equals(r.activityInfo.packageName)) continue;
                CharSequence l = r.loadLabel(pm);
                if (l != null && l.length() > 0) names.add(l.toString());
            }
        }
        return new ArrayList<>(names);
    }

    private TextView label(String s) {
        TextView t = text(13, medium, 0x99FFFFFF);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private TextView text(int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    /** A pill button: translucent at rest, white with dark text when focused. */
    private TextView button(String label) {
        TextView b = text(15, medium, 0);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setSingleLine(true);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF111111, 0xE6FFFFFF}));
        b.setBackground(states(0x26FFFFFF, 0xFFFFFFFF, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    /** Rest and focused fills; outline adds a white ring when focused instead (for the text field). */
    private StateListDrawable states(int rest, int focused, boolean outline) {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable f = pill(focused);
        if (outline) f.setStroke(dp(2), 0xFFFFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, f);
        bg.addState(new int[]{}, pill(rest));
        return bg;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(22));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
