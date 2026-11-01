// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

// Home of the app: students list, overview dashboard, uploads and account, with a bottom navigation bar.
public class MainActivity extends Activity implements Store.Listener {
    static final String WEBSITE = "https://coursetracker.online/";
    private Store store;
    private FrameLayout content;
    private LinearLayout nav;
    private String tab = "students";
    // students tab state
    private String query = "", course = "", sort = "name";
    private boolean searching;
    private final List<Store.Rec> shown = new ArrayList<>();
    private final Map<String, Course.Figures> figs = new HashMap<>();
    private StudentAdapter adapter;
    private TextView countLine, syncLine;
    private LinearLayout chips;
    private ListView list;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(R.style.AppTheme);
        super.onCreate(b);
        Diag.install(this);
        store = Store.get(this);
        if (store.signedIn()) { showMain(); store.start(); Updater.check(this, false); Diag.sendPending(this); }
        else showLogin();
        shared(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); shared(i); }

    // A file shared to the app from WhatsApp, Photos, Files or email goes straight to Claude to read.
    private void shared(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        i.setAction(Intent.ACTION_MAIN);
        if (u == null) { Ui.toast(this, "Share a file (a screenshot, photo, PDF or spreadsheet)."); return; }
        if (!store.signedIn()) { Ui.toast(this, "Sign in first, then share the file again."); return; }
        tab = "upload";
        if (content != null) render();
        ScanActivity.upload(this, u);
    }

    @Override protected void onResume() { super.onResume(); store.listen(this); store.resume(); if (store.signedIn() && content != null) refresh(); }
    @Override protected void onPause() { super.onPause(); store.unlisten(this); }

    @Override public void changed() { if (content != null) refresh(); }

    @Override
    public void onBackPressed() {
        if (searching) { searching = false; query = ""; render(); return; }
        if (content != null && !"students".equals(tab)) { tab = "students"; render(); return; }
        super.onBackPressed();
    }

    // ---------- sign in ----------
    private void showLogin() {
        content = null;
        getWindow().setStatusBarColor(Ui.NAVY);
        getWindow().setNavigationBarColor(Ui.NAVY);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF0B2A4F, 0xFF16627A});
        sv.setBackground(g);
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 56), Ui.dp(this, 24), Ui.dp(this, 32));
        LinearLayout logos = Ui.row(this);
        logos.setGravity(Gravity.CENTER);
        logos.setBackground(Ui.round(Color.WHITE, Ui.dp(this, 18)));
        logos.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        logos.addView(logo("mas-logo.png", 54), Ui.weight(1));
        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        logos.addView(sep, new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 40)));
        logos.addView(logo("anac-logo.png", 54), Ui.weight(1));
        box.addView(logos, Ui.margins(this, 0, 0, 0, 36));
        TextView kicker = Ui.label(this, "Integrated Commercial Pilot Licence");
        kicker.setTextColor(Ui.GOLD);
        box.addView(kicker);
        TextView t = Ui.title(this, "CPL Student Progress", 30, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        box.addView(t);
        TextView sub = Ui.text(this, "Training records, attendance and flight history for every student — live across the school.", 15, 0xCCFFFFFF, false);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, Ui.margins(this, 8, 0, 8, 28));
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        card.addView(Ui.title(this, "Sign in", 20, Ui.NAVY));
        TextView hint = Ui.text(this, "Enter the personal access code from your administrator.", 14, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        card.addView(hint);
        EditText code = Ui.input(this, "XXXX-XXXX-XXXX-XXXX", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setLetterSpacing(0.08f);
        card.addView(Ui.field(this, "Access code", code));
        final String[] server = {store.server()};
        TextView err = Ui.text(this, "", 13, Ui.RED, false);
        err.setVisibility(View.GONE);
        card.addView(err);
        TextView go = Ui.button(this, "Sign in", true, null);
        go.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (c.isEmpty()) { err.setText("Enter your access code."); err.setVisibility(View.VISIBLE); return; }
            go.setEnabled(false);
            go.setText("Signing in…");
            err.setVisibility(View.GONE);
            store.signIn(server[0], c, e -> {
                go.setEnabled(true);
                go.setText("Sign in");
                if (e != null) { err.setText(e); err.setVisibility(View.VISIBLE); return; }
                hideKeyboard(code);
                showMain();
                Updater.check(this, false);
            });
        });
        code.setOnEditorActionListener((v, id, ev) -> { if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) { go.performClick(); return true; } return false; });
        card.addView(go, Ui.margins(this, 0, 12, 0, 0));
        TextView srv = Ui.text(this, "Server settings", 13, Ui.TEAL, true);
        srv.setPadding(0, Ui.dp(this, 14), 0, 0);
        srv.setGravity(Gravity.CENTER);
        srv.setOnClickListener(v -> {
            LinearLayout c = Ui.col(this);
            EditText u = Ui.input(this, "https://…", server[0], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            c.addView(Ui.field(this, "Server address", u));
            Dialog d = Ui.sheet(this, "Server", c);
            c.addView(Ui.button(this, "Save", true, x -> { server[0] = u.getText().toString().trim(); d.dismiss(); }), Ui.margins(this, 0, 12, 0, 0));
            d.show();
        });
        card.addView(srv);
        box.addView(card, Ui.margins(this, 0, 0, 0, 0));
        TextView foot = Ui.text(this, "Moorabbin Aviation Services · Australian National Airline College", 12, 0x99FFFFFF, false);
        foot.setGravity(Gravity.CENTER);
        box.addView(foot, Ui.margins(this, 0, 28, 0, 0));
        sv.addView(box);
        setContentView(sv);
    }

    private ImageView logo(String asset, int heightDp) {
        ImageView i = new ImageView(this);
        try { i.setImageBitmap(BitmapFactory.decodeStream(getAssets().open(asset))); } catch (Exception ignored) { }
        i.setAdjustViewBounds(true);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, heightDp)));
        return i;
    }

    private void hideKeyboard(View v) {
        InputMethodManager m = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---------- main frame ----------
    private void showMain() {
        getWindow().setStatusBarColor(Ui.NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        nav = Ui.row(this);
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(Ui.dp(this, 8));
        nav.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        render();
    }

    private void buildNav() {
        nav.removeAllViews();
        String[][] items = {{"students", "Students"}, {"overview", "Overview"}, {"upload", "Uploads"}, {"account", "Account"}};
        int[] icons = {R.drawable.ic_people, R.drawable.ic_dashboard, R.drawable.ic_upload, R.drawable.ic_person};
        for (int i = 0; i < items.length; i++) {
            String key = items[i][0];
            boolean on = key.equals(tab);
            LinearLayout it = Ui.col(this);
            it.setGravity(Gravity.CENTER);
            FrameLayout pillBox = new FrameLayout(this);
            pillBox.setBackground(on ? Ui.round(Ui.NAVY_TINT, Ui.dp(this, 16)) : null);
            ImageView ic = Ui.icon(this, icons[i], on ? Ui.NAVY : Ui.MUTED, 22);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER);
            pillBox.addView(ic, ip);
            it.addView(pillBox, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 32)));
            TextView t = Ui.text(this, items[i][1], 12, on ? Ui.NAVY : Ui.MUTED, on);
            t.setPadding(0, Ui.dp(this, 3), 0, 0);
            it.addView(t);
            it.setContentDescription(items[i][1]);
            it.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220B2A4F), null, null));
            it.setOnClickListener(v -> { if (!key.equals(tab)) { tab = key; searching = false; render(); } });
            nav.addView(it, Ui.weight(1));
        }
    }

    private void render() {
        if (content == null) return;
        buildNav();
        content.removeAllViews();
        adapter = null;
        switch (tab) {
            case "overview": content.addView(overview()); break;
            case "upload": content.addView(uploads()); break;
            case "account": content.addView(account()); break;
            default: content.addView(students());
        }
    }

    private void refresh() {
        if ("students".equals(tab) && adapter != null) { filter(); return; }
        if (!"students".equals(tab)) render();
    }

    private LinearLayout appBar(String title, String subtitle, View... actions) {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.NAVY);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
        LinearLayout tc = Ui.col(this);
        tc.addView(Ui.title(this, title, 22, Color.WHITE));
        if (subtitle != null) {
            TextView s = Ui.text(this, subtitle, 13, 0xB3FFFFFF, false);
            tc.addView(s);
            if (title.equals("Students")) countLine = s;
        }
        bar.addView(tc, Ui.weight(1));
        for (View a : actions) bar.addView(a);
        return bar;
    }

    // ---------- students ----------
    private View students() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout page = Ui.col(this);
        if (searching) {
            LinearLayout bar = Ui.row(this);
            bar.setBackgroundColor(Ui.NAVY);
            bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            bar.addView(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Close search", v -> { searching = false; query = ""; render(); }));
            EditText q = Ui.input(this, "Search name, ID, instructor", query, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            q.setBackground(Ui.round(0x26FFFFFF, Ui.dp(this, 12)));
            q.setTextColor(Color.WHITE);
            q.setHintTextColor(0x99FFFFFF);
            q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            q.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) { query = s.toString(); filter(); }
            });
            bar.addView(q, Ui.weight(1));
            page.addView(bar);
            q.requestFocus();
            q.postDelayed(() -> { InputMethodManager m = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (m != null) m.showSoftInput(q, 0); }, 150);
        } else {
            ImageView sortBtn = Ui.iconButton(this, R.drawable.ic_sort, Color.WHITE, "Sort", null);
            sortBtn.setOnClickListener(v -> {
                PopupMenu pm = new PopupMenu(this, v);
                String[] opts = {"Name", "Recently updated", "Progress", "Attendance (lowest first)", "Last flight"};
                String[] keys = {"name", "updated", "progress", "attendance", "flight"};
                for (int i = 0; i < opts.length; i++) pm.getMenu().add(0, i, i, (keys[i].equals(sort) ? "✓  " : "     ") + opts[i]);
                pm.setOnMenuItemClickListener(m -> { sort = keys[m.getItemId()]; filter(); return true; });
                pm.show();
            });
            page.addView(appBar("Students", " ", Ui.iconButton(this, R.drawable.ic_search, Color.WHITE, "Search", v -> { searching = true; render(); }), sortBtn));
        }
        chips = Ui.row(this);
        LinearLayout chipWrap = Ui.col(this);
        chipWrap.setBackgroundColor(Ui.BG);
        chipWrap.setPadding(0, Ui.dp(this, 10), 0, 0);
        chipWrap.addView(Ui.chipRow(this, chips));
        syncLine = Ui.text(this, "", 12, Ui.AMBER, true);
        syncLine.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 4));
        chipWrap.addView(syncLine);
        page.addView(chipWrap);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 96));
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        adapter = new StudentAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> openStudent(shown.get(pos).uid));
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        frame.addView(page);
        FrameLayout fabBox = new FrameLayout(this);
        fabBox.setBackground(Ui.ripple(Ui.round(Ui.GOLD, Ui.dp(this, 18)), Ui.round(0xFF000000, Ui.dp(this, 18))));
        fabBox.setElevation(Ui.dp(this, 6));
        LinearLayout fr = Ui.row(this);
        fr.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 20), 0);
        fr.addView(Ui.icon(this, R.drawable.ic_add, Color.WHITE, 22));
        TextView fl = Ui.text(this, "New student", 15, Color.WHITE, true);
        fl.setPadding(Ui.dp(this, 8), 0, 0, 0);
        fr.addView(fl);
        fabBox.addView(fr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fabBox.setOnClickListener(v -> newStudent());
        fabBox.setContentDescription("New student");
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
        frame.addView(fabBox, fp);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            int last;
            public void onScrollStateChanged(AbsListView v, int s) {}
            public void onScroll(AbsListView v, int first, int vis, int total) {
                if (first > last && fl.getVisibility() == View.VISIBLE) fl.setVisibility(View.GONE);
                else if (first < last && fl.getVisibility() == View.GONE) fl.setVisibility(View.VISIBLE);
                last = first;
            }
        });
        filter();
        return frame;
    }

    private void filter() {
        if (adapter == null) return;
        List<Store.Rec> all = store.all();
        Map<String, Integer> courses = new HashMap<>();
        for (Store.Rec r : all) { String c = r.state.optString("course"); courses.put(c, courses.getOrDefault(c, 0) + 1); }
        // course chips, most students first
        chips.removeAllViews();
        Ui.gap(chips, Ui.chip(this, "All  " + all.size(), course.isEmpty(), v -> { course = ""; filter(); }), 8);
        List<Map.Entry<String, Integer>> cs = new ArrayList<>(courses.entrySet());
        Collections.sort(cs, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : cs) {
            String key = e.getKey();
            Ui.gap(chips, Ui.chip(this, Ui.shortCourse(key) + "  " + e.getValue(), key.equals(course), v -> { course = key; filter(); }), 8);
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        shown.clear();
        figs.clear();
        for (Store.Rec r : all) {
            JSONObject s = r.state;
            if (!course.isEmpty() && !course.equals(s.optString("course"))) continue;
            if (!q.isEmpty() && !(s.optString("name") + " " + s.optString("studentId") + " " + s.optString("instructor") + " " + s.optString("course")).toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(r);
            figs.put(r.uid, Course.figures(s));
        }
        Collections.sort(shown, (a, b) -> {
            Course.Figures fa = figs.get(a.uid), fb = figs.get(b.uid);
            switch (sort) {
                case "updated": return Long.compare(b.updated, a.updated);
                case "progress": return Double.compare(fb.overall, fa.overall);
                case "attendance": return Double.compare(fa.attendance < 0 ? 999 : fa.attendance, fb.attendance < 0 ? 999 : fb.attendance);
                case "flight": return fb.lastFlight.compareTo(fa.lastFlight);
                default: return a.state.optString("name").compareToIgnoreCase(b.state.optString("name"));
            }
        });
        if (countLine != null) countLine.setText(!store.loaded() ? "Loading…" : shown.size() + (shown.size() == all.size() ? "" : " of " + all.size()) + " students");
        if (syncLine != null) {
            String st = store.status;
            boolean live = "Live".equals(st);
            int pend = store.pending();
            syncLine.setText(live ? (pend > 0 ? "Saving " + pend + " change" + (pend > 1 ? "s" : "") + "…" : "") : st);
            syncLine.setVisibility(syncLine.getText().length() == 0 ? View.GONE : View.VISIBLE);
        }
        adapter.notifyDataSetChanged();
    }

    private final class StudentAdapter extends BaseAdapter {
        public int getCount() { return shown.size() == 0 ? 1 : shown.size(); }
        public Object getItem(int i) { return null; }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int i) { return shown.isEmpty() ? 1 : 0; }
        public boolean isEnabled(int i) { return !shown.isEmpty(); }

        public View getView(int i, View old, ViewGroup parent) {
            if (shown.isEmpty()) {
                TextView t = Ui.text(MainActivity.this, !store.loaded() || store.all().isEmpty() && !"Live".equals(store.status) ? "Loading students…" : "No students match.", 15, Ui.MUTED, false);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, Ui.dp(MainActivity.this, 60), 0, 0);
                return t;
            }
            Store.Rec r = shown.get(i);
            Course.Figures f = figs.get(r.uid);
            JSONObject s = r.state;
            Holder h = old == null ? null : (Holder) old.getTag();
            if (h == null) h = new Holder();
            h.bind(s, f, r);
            return h.root;
        }
    }

    private final class Holder {
        final FrameLayout root = new FrameLayout(MainActivity.this);
        final LinearLayout card = Ui.row(MainActivity.this);
        final LinearLayout mid = Ui.col(MainActivity.this);
        final TextView name = Ui.ellipsize(Ui.text(MainActivity.this, "", 16, Ui.TEXT, true));
        final TextView sub = Ui.ellipsize(Ui.text(MainActivity.this, "", 13, Ui.MUTED, false));
        final TextView next = Ui.ellipsize(Ui.text(MainActivity.this, "", 12, Ui.TEAL, true));
        final LinearLayout pills = Ui.row(MainActivity.this);
        final Ui.Ring ring = new Ui.Ring(MainActivity.this, Ui.CHIP_BG, Ui.NAVY);
        final FrameLayout avatarBox = new FrameLayout(MainActivity.this);

        Holder() {
            Activity a = MainActivity.this;
            root.setTag(this);
            root.setPadding(Ui.dp(a, 14), Ui.dp(a, 5), Ui.dp(a, 14), Ui.dp(a, 5));
            card.setBackground(Ui.ripple(Ui.round(Color.WHITE, Ui.dp(a, 16)), Ui.round(0xFF000000, Ui.dp(a, 16))));
            card.setElevation(Ui.dp(a, 1));
            card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            card.setDuplicateParentStateEnabled(true);
            card.addView(avatarBox, new LinearLayout.LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46)));
            mid.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
            mid.addView(name);
            mid.addView(sub);
            pills.setPadding(0, Ui.dp(a, 6), 0, 0);
            mid.addView(pills);
            card.addView(mid, Ui.weight(1));
            card.addView(ring, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
            root.addView(card);
        }

        void bind(JSONObject s, Course.Figures f, Store.Rec r) {
            Activity a = MainActivity.this;
            String nm = s.optString("name");
            name.setText(nm.isEmpty() ? "Unnamed student" : nm);
            avatarBox.removeAllViews();
            TextView av = Ui.avatar(a, nm, 46);
            avatarBox.addView(av, new FrameLayout.LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46)));
            String instr = s.optString("instructor");
            sub.setText(Ui.shortCourse(s.optString("course")) + (instr.isEmpty() ? "" : " · " + instr));
            pills.removeAllViews();
            TextView nx = Ui.ellipsize(Ui.text(a, f.last.isEmpty() ? (f.next.isEmpty() ? "No flights yet" : "Next: " + f.next) : "Last: " + f.last + " · " + Ui.auDate(f.lastDate), 12, Ui.TEAL, true));
            pills.addView(nx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (f.attendance >= 0 && f.attendance < 80) Ui.gap(pills, Ui.pill(a, Course.pct(f.attendance) + " attend.", Ui.RED, Ui.RED_BG), 6);
            if (r.dirty) Ui.gap(pills, Ui.pill(a, "Saving", Ui.AMBER, Ui.AMBER_BG), 6);
            ring.set(f.overall, Ui.statusColor(f.overall));
            root.setContentDescription(nm + ", " + Course.pct(f.overall) + " complete");
        }
    }

    private void openStudent(String uid) {
        startActivity(new Intent(this, StudentActivity.class).putExtra("uid", uid));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void newStudent() {
        LinearLayout c = Ui.col(this);
        EditText name = Ui.input(this, "Full name", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText sid = Ui.input(this, "Optional", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText instr = Ui.input(this, "Optional", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        c.addView(Ui.field(this, "Student name", name));
        c.addView(Ui.field(this, "Student ID / ARN", sid));
        c.addView(Ui.field(this, "Instructor", instr));
        Dialog d = Ui.sheet(this, "New student", c);
        c.addView(Ui.button(this, "Create student", true, v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { name.setError("Enter the student's name"); return; }
            JSONObject s = Course.blank(n);
            try {
                s.put("studentId", sid.getText().toString().trim());
                s.put("instructor", instr.getText().toString().trim());
                s.put("course", "MAS Commercial Pilot Licence - CPL (150hr integrated)");
            } catch (Exception ignored) { }
            String uid = store.create(s);
            d.dismiss();
            openStudent(uid);
        }), Ui.margins(this, 0, 14, 0, 0));
        d.show();
    }

    // ---------- overview ----------
    private View overview() {
        LinearLayout page = Ui.col(this);
        page.addView(appBar("Overview", "Across all students"));
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 24));
        List<Store.Rec> all = store.all();
        String today = Store.today(), monthStart = today.substring(0, 8) + "01";
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -90);
        String recent = String.format(Locale.ROOT, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        int active = 0, monthFlights = 0;
        double monthHours = 0;
        List<Object[]> lowAtt = new ArrayList<>(), over = new ArrayList<>();
        Map<String, Integer> courseCount = new HashMap<>();
        for (Store.Rec r : all) {
            Course.Figures f = Course.figures(r.state);
            boolean isActive = f.lastFlight.compareTo(recent) >= 0;
            if (isActive) active++;
            org.json.JSONArray fl = r.state.optJSONArray("flights");
            if (fl != null) for (int i = 0; i < fl.length(); i++) {
                JSONObject x = fl.optJSONObject(i);
                if (x != null && x.optString("date").compareTo(monthStart) >= 0) { monthFlights++; monthHours += x.optDouble("hours", 0); }
            }
            if (isActive && f.attendance >= 0 && f.attendance < 80) lowAtt.add(new Object[] {r, f.attendance});
            if (isActive) {
                double cost = 0;
                for (int m : Course.MILESTONES) { Course.Milestone ms = Course.milestone(r.state, m); if (ms.cost != null) cost = Math.max(cost, ms.cost); }
                if (cost > 0) over.add(new Object[] {r, cost});
            }
            String c = Ui.shortCourse(r.state.optString("course"));
            courseCount.put(c, courseCount.getOrDefault(c, 0) + 1);
        }
        // hero
        LinearLayout hero = Ui.col(this);
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF0B2A4F, 0xFF16627A}) {{ setCornerRadius(Ui.dp(MainActivity.this, 18)); }});
        hero.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String first = store.user().split(" ")[0];
        hero.addView(Ui.text(this, (hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening") + (first.isEmpty() ? "" : ", " + first), 20, Color.WHITE, true));
        TextView hs = Ui.text(this, "Here's where the school stands today.", 14, 0xCCFFFFFF, false);
        hs.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 14));
        hero.addView(hs);
        LinearLayout tiles = Ui.row(this);
        tiles.addView(stat(String.valueOf(all.size()), "Students"), Ui.weight(1));
        tiles.addView(stat(String.valueOf(active), "Active (90 days)"), Ui.weight(1));
        tiles.addView(stat(String.valueOf(monthFlights), "Flights this month"), Ui.weight(1));
        hero.addView(tiles);
        TextView mh = Ui.text(this, Course.hours(monthHours) + " flown this month", 13, Ui.GOLD, true);
        mh.setPadding(0, Ui.dp(this, 12), 0, 0);
        hero.addView(mh);
        box.addView(hero, Ui.margins(this, 0, 0, 0, 14));
        Collections.sort(lowAtt, (a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        box.addView(section("Attendance below 80%", "Active students only", lowAtt, x -> Course.pct((Double) x), Ui.RED, Ui.RED_BG), Ui.margins(this, 0, 0, 0, 14));
        Collections.sort(over, (a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        box.addView(section("Over syllabus hours", "Highest estimated overfly at an hour review", over, x -> Course.money((Double) x), Ui.AMBER, Ui.AMBER_BG), Ui.margins(this, 0, 0, 0, 14));
        List<Object[]> upd = new ArrayList<>();
        List<Store.Rec> byUpd = new ArrayList<>(all);
        Collections.sort(byUpd, (a, b) -> Long.compare(b.updated, a.updated));
        for (int i = 0; i < Math.min(6, byUpd.size()); i++) upd.add(new Object[] {byUpd.get(i), byUpd.get(i)});
        box.addView(section("Recently updated", null, upd, x -> ago(((Store.Rec) x).updated), Ui.MUTED, Ui.CHIP_BG), Ui.margins(this, 0, 0, 0, 14));
        LinearLayout cc = Ui.card(this);
        cc.addView(Ui.title(this, "Courses", 17, Ui.NAVY));
        List<Map.Entry<String, Integer>> ce = new ArrayList<>(courseCount.entrySet());
        Collections.sort(ce, (a, b) -> b.getValue() - a.getValue());
        int max = ce.isEmpty() ? 1 : ce.get(0).getValue();
        for (int i = 0; i < Math.min(8, ce.size()); i++) {
            LinearLayout r = Ui.row(this);
            r.addView(Ui.ellipsize(Ui.text(this, ce.get(i).getKey(), 14, Ui.TEXT, false)), Ui.weight(1));
            r.addView(Ui.text(this, String.valueOf(ce.get(i).getValue()), 14, Ui.NAVY, true));
            cc.addView(r, Ui.margins(this, 0, 10, 0, 4));
            cc.addView(Ui.bar(this, ce.get(i).getValue() * 100.0 / max, Ui.TEAL));
        }
        box.addView(cc);
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View stat(String value, String label) {
        LinearLayout l = Ui.col(this);
        l.addView(Ui.text(this, value, 26, Color.WHITE, true));
        TextView t = Ui.text(this, label, 12, 0xB3FFFFFF, false);
        l.addView(t);
        return l;
    }

    interface Fmt { String f(Object o); }

    private View section(String title, String sub, List<Object[]> rows, Fmt fmt, int fg, int bg) {
        LinearLayout c = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.title(this, title, 17, Ui.NAVY), Ui.weight(1));
        if (!rows.isEmpty() && fg != Ui.MUTED) head.addView(Ui.pill(this, String.valueOf(rows.size()), fg, bg));
        c.addView(head);
        if (sub != null) c.addView(Ui.text(this, sub, 13, Ui.MUTED, false));
        if (rows.isEmpty()) {
            TextView t = Ui.text(this, "Nothing to show.", 14, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(this, 10), 0, 0);
            c.addView(t);
        }
        for (int i = 0; i < Math.min(6, rows.size()); i++) {
            Store.Rec r = (Store.Rec) rows.get(i)[0];
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
            row.addView(Ui.avatar(this, r.state.optString("name"), 34));
            LinearLayout mid = Ui.col(this);
            mid.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
            mid.addView(Ui.ellipsize(Ui.text(this, r.state.optString("name"), 15, Ui.TEXT, true)));
            mid.addView(Ui.ellipsize(Ui.text(this, Ui.shortCourse(r.state.optString("course")) + (r.by.isEmpty() || fg != Ui.MUTED ? "" : " · by " + r.by), 12, Ui.MUTED, false)));
            row.addView(mid, Ui.weight(1));
            row.addView(Ui.pill(this, fmt.f(rows.get(i)[1]), fg, bg));
            row.setOnClickListener(v -> openStudent(r.uid));
            row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220B2A4F), null, null));
            c.addView(row);
        }
        return c;
    }

    static String ago(long ms) {
        long m = (System.currentTimeMillis() - ms) / 60000;
        if (ms <= 0) return "—";
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 1440) return (m / 60) + " h ago";
        if (m < 1440 * 30) return (m / 1440) + " d ago";
        return new java.text.SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new java.util.Date(ms));
    }

    // ---------- uploads ----------
    private View uploads() {
        LinearLayout page = Ui.col(this);
        page.addView(appBar("Uploads", "Fill in records from your files"));
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 24));
        LinearLayout c = Ui.card(this);
        c.addView(Ui.icon(this, R.drawable.ic_upload, Ui.TEAL, 32));
        TextView t = Ui.title(this, "Import records", 19, Ui.NAVY);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        c.addView(t);
        c.addView(Ui.text(this, "Smart flight activity reports and cancellation reports fill in lessons, flights, hour reviews and attendance for every student. Everything you import is shown for review first, then saved to the shared database for everyone.", 14, Ui.MUTED, false));
        c.addView(Ui.button(this, "Choose files", true, v -> Importer.pick(this)), Ui.margins(this, 0, 16, 0, 0));
        box.addView(c, Ui.margins(this, 0, 0, 0, 14));
        box.addView(claudeCard(), Ui.margins(this, 0, 0, 0, 14));
        LinearLayout k = Ui.card(this);
        k.addView(Ui.title(this, "What you can upload", 17, Ui.NAVY));
        String[][] kinds = {
                {"Smart flight activity report (.csv)", "Every flight: lessons, hours, instructors. Upload any date range; flights already imported are skipped."},
                {"Smart event cancellation report (.csv)", "Student-caused cancellations become attendance (sick without a certificate counts as absent)."},
                {"Anything else, read by Claude", "Screenshots, photos of paper forms, PDFs, Excel and CSV files: exam results, attendance sheets, training records. Tip: share a file to CPL Tracker from WhatsApp, Photos or Files."}};
        for (String[] x : kinds) {
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, Ui.dp(this, 12), 0, 0);
            r.addView(Ui.icon(this, R.drawable.ic_file, Ui.TEAL, 20));
            LinearLayout m = Ui.col(this);
            m.setPadding(Ui.dp(this, 12), 0, 0, 0);
            m.addView(Ui.text(this, x[0], 15, Ui.TEXT, true));
            m.addView(Ui.text(this, x[1], 13, Ui.MUTED, false));
            r.addView(m, Ui.weight(1));
            k.addView(r);
        }
        box.addView(k);
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == ScanActivity.PICK) { if (res == RESULT_OK && data != null && data.getData() != null) ScanActivity.upload(this, data.getData()); return; }
        Importer.result(this, req, res, data);
    }

    // ---------- Claude ----------
    private static JSONArray scans;
    private static long scansAt;
    private LinearLayout scanList;

    private View claudeCard() {
        LinearLayout c = Ui.card(this);
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        r.addView(Ui.icon(this, R.drawable.ic_spark, Ui.GOLD, 30));
        LinearLayout m = Ui.col(this);
        m.setPadding(Ui.dp(this, 12), 0, 0, 0);
        m.addView(Ui.title(this, "Read with Claude", 19, Ui.NAVY));
        m.addView(Ui.text(this, "Any other file: a screenshot, photo, PDF, Excel or CSV. Claude reads it, matches each person to a student and lists the changes for you to approve. Nothing is saved until you approve it.", 14, Ui.MUTED, false));
        r.addView(m, Ui.weight(1));
        c.addView(r);
        TextView b = Ui.button(this, "Choose a file", false, v -> ScanActivity.pick(this));
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.ripple(Ui.round(Ui.GOLD, Ui.dp(this, 12)), Ui.round(0xFF000000, Ui.dp(this, 12))));
        c.addView(b, Ui.margins(this, 0, 14, 0, 4));
        scanList = Ui.col(this);
        c.addView(scanList);
        showScans();
        if (scans == null || System.currentTimeMillis() - scansAt > 4000) loadScans();
        return c;
    }

    private void loadScans() {
        scansAt = System.currentTimeMillis();
        ScanActivity.NET.execute(() -> {
            try {
                JSONArray a = new JSONObject(store.api("GET", "/api/scans", null)).getJSONArray("scans");
                runOnUiThread(() -> { scans = a; showScans(); });
            } catch (Exception ignored) { }
        });
    }

    private void showScans() {
        if (scanList == null) return;
        scanList.removeAllViews();
        if (scans == null) return;
        boolean reading = false;
        for (int i = 0; i < scans.length(); i++) {
            JSONObject j = scans.optJSONObject(i);
            String st = j.optString("status");
            reading |= "reading".equals(st);
            scanList.addView(Ui.divider(this), Ui.margins(this, 0, i == 0 ? 10 : 0, 0, 0));
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            row.addView(Ui.icon(this, R.drawable.ic_file, Ui.TEAL, 22));
            LinearLayout t = Ui.col(this);
            t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
            t.addView(Ui.ellipsize(Ui.text(this, j.optString("name"), 15, Ui.TEXT, true)));
            String sub = !j.optString("error").isEmpty() && !"null".equals(j.optString("error")) ? j.optString("error") : j.optString("summary");
            if (!sub.isEmpty() && !"null".equals(sub)) { TextView s2 = Ui.text(this, sub, 12.5f, Ui.MUTED, false); s2.setMaxLines(2); s2.setEllipsize(android.text.TextUtils.TruncateAt.END); t.addView(s2); }
            t.addView(Ui.text(this, j.optString("by") + " · " + ago(j.optLong("created")), 12, Ui.MUTED, false));
            row.addView(t, Ui.weight(1));
            int n = j.optInt("count");
            if ("reading".equals(st)) row.addView(Ui.pill(this, "Reading…", Ui.NAVY, Ui.NAVY_TINT));
            else if ("ready".equals(st)) row.addView(Ui.pill(this, "Review " + n, Ui.AMBER, Ui.AMBER_BG));
            else if ("applied".equals(st)) row.addView(Ui.pill(this, "Saved " + j.optInt("applied"), Ui.GREEN, Ui.GREEN_BG));
            else if ("failed".equals(st)) row.addView(Ui.pill(this, "Could not read", Ui.RED, Ui.RED_BG));
            else row.addView(Ui.pill(this, "Discarded", Ui.MUTED, Ui.CHIP_BG));
            if (!"failed".equals(st) && !"discarded".equals(st)) {
                String sid = j.optString("id");
                row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220B2A4F), null, null));
                row.setOnClickListener(v -> startActivity(new Intent(this, ScanActivity.class).putExtra("id", sid)));
            }
            scanList.addView(row);
        }
        if (reading) scanList.postDelayed(() -> { if ("upload".equals(tab) && scanList != null && scanList.isAttachedToWindow()) loadScans(); }, 3000);
    }

    // ---------- account ----------
    private View account() {
        LinearLayout page = Ui.col(this);
        page.addView(appBar("Account", null));
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 24));
        LinearLayout me = Ui.card(this);
        LinearLayout mr = Ui.row(this);
        mr.addView(Ui.avatar(this, store.user(), 54));
        LinearLayout mc = Ui.col(this);
        mc.setPadding(Ui.dp(this, 14), 0, 0, 0);
        mc.addView(Ui.text(this, store.user(), 19, Ui.TEXT, true));
        LinearLayout rp = Ui.row(this);
        rp.setPadding(0, Ui.dp(this, 4), 0, 0);
        rp.addView(Ui.pill(this, store.admin() ? "Administrator" : "Instructor", Ui.NAVY, Ui.NAVY_TINT));
        mc.addView(rp);
        mr.addView(mc, Ui.weight(1));
        me.addView(mr);
        box.addView(me, Ui.margins(this, 0, 0, 0, 14));
        LinearLayout sync = Ui.card(this);
        sync.addView(Ui.title(this, "Shared database", 17, Ui.NAVY));
        boolean live = "Live".equals(store.status);
        LinearLayout sr = Ui.row(this);
        sr.setPadding(0, Ui.dp(this, 10), 0, 0);
        sr.addView(Ui.pill(this, live ? "LIVE" : "OFFLINE", live ? Ui.GREEN : Ui.AMBER, live ? Ui.GREEN_BG : Ui.AMBER_BG));
        TextView st = Ui.text(this, live ? "Changes appear on every device within seconds." : store.status, 13, Ui.MUTED, false);
        st.setPadding(Ui.dp(this, 10), 0, 0, 0);
        sr.addView(st, Ui.weight(1));
        sync.addView(sr);
        sync.addView(info("Students on this phone", String.valueOf(store.all().size())));
        sync.addView(info("Waiting to save", String.valueOf(store.pending())));
        sync.addView(info("Last update", store.lastSync == 0 ? "—" : ago(store.lastSync)));
        sync.addView(info("Server", store.server().replace("https://", "")));
        box.addView(sync, Ui.margins(this, 0, 0, 0, 14));
        LinearLayout acts = Ui.card(this);
        acts.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        if (store.admin()) {
            acts.addView(action(R.drawable.ic_people, "People", "Add people and give out access codes", v -> startActivity(new Intent(this, PeopleActivity.class))));
            acts.addView(Ui.divider(this));
        }
        acts.addView(action(R.drawable.ic_open, "Open the website", "Same records on any computer", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))));
        acts.addView(Ui.divider(this));
        acts.addView(action(R.drawable.ic_sync, "Check for app updates", "Version " + Updater.versionName(this), v -> Updater.check(this, true)));
        acts.addView(Ui.divider(this));
        acts.addView(action(R.drawable.ic_logout, "Sign out", "Removes the records from this phone", v -> {
            new android.app.AlertDialog.Builder(this).setTitle("Sign out?").setMessage(store.pending() > 0 ? store.pending() + " change(s) have not reached the server yet and will be lost." : "You can sign in again with your access code.")
                    .setPositiveButton("Sign out", (d, w) -> { store.signOut(); showLogin(); }).setNegativeButton("Cancel", null).show();
        }));
        box.addView(acts);
        TextView foot = Ui.text(this, "Moorabbin Aviation Services · Australian National Airline College", 12, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        box.addView(foot, Ui.margins(this, 0, 20, 0, 0));
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View info(String k, String v) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 10), 0, 0);
        r.addView(Ui.text(this, k, 14, Ui.MUTED, false), Ui.weight(1));
        r.addView(Ui.text(this, v, 14, Ui.TEXT, true));
        return r;
    }

    private View action(int icon, String title, String sub, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        r.addView(Ui.icon(this, icon, Ui.NAVY, 22));
        LinearLayout m = Ui.col(this);
        m.setPadding(Ui.dp(this, 14), 0, 0, 0);
        m.addView(Ui.text(this, title, 15, Ui.TEXT, true));
        m.addView(Ui.text(this, sub, 13, Ui.MUTED, false));
        r.addView(m, Ui.weight(1));
        r.addView(Ui.icon(this, R.drawable.ic_chevron, Ui.MUTED, 20));
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220B2A4F), null, null));
        r.setOnClickListener(l);
        return r;
    }
}
