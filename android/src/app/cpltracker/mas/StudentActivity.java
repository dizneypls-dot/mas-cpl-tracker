// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

// One student: header with progress, then Overview, Lessons, Exams, Attendance and Flights tabs.
// Every change is saved at once and sent to the shared database.
public class StudentActivity extends Activity implements Store.Listener {
    private Store store;
    private String uid, tab = "overview", lessonFilter = "all";
    private FrameLayout body;
    private LinearLayout header, tabs;
    private int scrollY;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(R.style.AppTheme);
        super.onCreate(b);
        store = Store.get(this);
        uid = getIntent().getStringExtra("uid");
        if (b != null) tab = b.getString("tab", tab);
        getWindow().setStatusBarColor(Ui.NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        header = Ui.col(this);
        root.addView(header);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Ui.NAVY);
        tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        hs.addView(tabs);
        root.addView(hs);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); o.putString("tab", tab); }
    @Override protected void onResume() { super.onResume(); store.listen(this); }
    @Override protected void onPause() { super.onPause(); store.unlisten(this); }
    @Override public void changed() { render(); }

    @Override public void finish() { super.finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); }

    private JSONObject state() { Store.Rec r = store.find(uid); return r == null ? null : r.state; }

    private void render() {
        JSONObject s = state();
        if (s == null) { finish(); return; }
        Course.Figures f = Course.figures(s);
        renderHeader(s, f);
        renderTabs();
        View keep = body.getChildCount() > 0 ? body.getChildAt(0) : null;
        if (keep instanceof ScrollView) scrollY = keep.getScrollY();
        int listPos = keep instanceof ListView ? ((ListView) keep).getFirstVisiblePosition() : -1;
        int listTop = keep instanceof ListView && ((ListView) keep).getChildCount() > 0 ? ((ListView) keep).getChildAt(0).getTop() : 0;
        body.removeAllViews();
        View v;
        switch (tab) {
            case "lessons": v = lessons(s); break;
            case "exams": v = exams(s); break;
            case "attendance": v = attendance(s, f); break;
            case "flights": v = flights(s); break;
            default: v = overview(s, f);
        }
        body.addView(v);
        if (v instanceof ScrollView && keep instanceof ScrollView) { final int y = scrollY; v.post(() -> v.scrollTo(0, y)); }
        if (v instanceof ListView && listPos >= 0) ((ListView) v).setSelectionFromTop(listPos, listTop);
    }

    private void renderHeader(JSONObject s, Course.Figures f) {
        header.removeAllViews();
        header.setBackgroundColor(Ui.NAVY);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Back", v -> finish()));
        View sp = new View(this);
        bar.addView(sp, Ui.weight(1));
        bar.addView(Ui.iconButton(this, R.drawable.ic_edit, Color.WHITE, "Edit details", v -> editDetails()));
        ImageView more = Ui.iconButton(this, R.drawable.ic_more, Color.WHITE, "More", null);
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add(0, 1, 0, "Edit details");
            if (store.admin()) pm.getMenu().add(0, 2, 1, "Remove student");
            pm.setOnMenuItemClickListener(m -> { if (m.getItemId() == 1) editDetails(); else removeStudent(); return true; });
            pm.show();
        });
        bar.addView(more);
        header.addView(bar);
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 14));
        TextView av = Ui.avatar(this, s.optString("name"), 58);
        av.setBackground(new GradientDrawable() {{ setShape(OVAL); setColor(Ui.avatarColor(s.optString("name"))); setStroke(Ui.dp(StudentActivity.this, 2), 0x55FFFFFF); }});
        top.addView(av);
        LinearLayout mid = Ui.col(this);
        mid.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 10), 0);
        TextView nm = Ui.title(this, s.optString("name").isEmpty() ? "Unnamed student" : s.optString("name"), 22, Color.WHITE);
        nm.setMaxLines(2);
        mid.addView(nm);
        String sub = Ui.shortCourse(s.optString("course")) + (s.optString("studentId").isEmpty() ? "" : " · ID " + s.optString("studentId"));
        mid.addView(Ui.text(this, sub, 13, 0xCCFFFFFF, false));
        if (!s.optString("instructor").isEmpty()) mid.addView(Ui.text(this, "Instructor " + s.optString("instructor"), 13, 0xCCFFFFFF, false));
        top.addView(mid, Ui.weight(1));
        Ui.Ring ring = new Ui.Ring(this, 0x33FFFFFF, Color.WHITE);
        ring.set(f.overall, Ui.GOLD);
        top.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        header.addView(top);
        LinearLayout tiles = Ui.row(this);
        tiles.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 12));
        tiles.addView(tile(Course.pct(f.practical), "Practical"), Ui.weight(1));
        tiles.addView(tile(Course.pct(f.theory), "Theory"), Ui.weight(1));
        tiles.addView(tile(f.attendance < 0 ? "—" : Course.pct(f.attendance), "Attendance", f.attendance >= 0 && f.attendance < 80), Ui.weight(1));
        tiles.addView(tile(String.format(Locale.ROOT, "%.0f", f.hours), "Hours flown"), Ui.weight(1));
        header.addView(tiles);
    }

    private View tile(String v, String l) { return tile(v, l, false); }

    private View tile(String v, String l, boolean warn) {
        LinearLayout t = Ui.col(this);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout.LayoutParams p = Ui.weight(1);
        t.setBackground(Ui.round(warn ? 0x40B42318 : 0x1AFFFFFF, Ui.dp(this, 12)));
        t.addView(Ui.text(this, v, 17, warn ? 0xFFFFB4AB : Color.WHITE, true));
        t.addView(Ui.text(this, l, 11, 0xB3FFFFFF, false));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        wrap.addView(t);
        return wrap;
    }

    private void renderTabs() {
        tabs.removeAllViews();
        String[][] items = {{"overview", "Overview"}, {"lessons", "Lessons"}, {"exams", "Exams"}, {"attendance", "Attendance"}, {"flights", "Flights"}};
        for (String[] it : items) {
            boolean on = it[0].equals(tab);
            LinearLayout c = Ui.col(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = Ui.text(this, it[1], 14, on ? Color.WHITE : 0x99FFFFFF, true);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 9));
            c.addView(t);
            View ind = new View(this);
            ind.setBackground(Ui.round(on ? Ui.GOLD : Color.TRANSPARENT, Ui.dp(this, 2)));
            c.addView(ind, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 3)));
            c.setOnClickListener(v -> { if (!it[0].equals(tab)) { tab = it[0]; scrollY = 0; body.removeAllViews(); render(); } });
            tabs.addView(c);
        }
    }

    private ScrollView scroller(LinearLayout box) {
        ScrollView sv = new ScrollView(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 32));
        sv.addView(box);
        return sv;
    }

    // ---------- overview ----------
    private View overview(JSONObject s, Course.Figures f) {
        LinearLayout box = Ui.col(this);
        LinearLayout prog = Ui.card(this);
        prog.addView(Ui.title(this, "Course progress", 17, Ui.NAVY));
        double share = s.optDouble("share", 60);
        prog.addView(Ui.text(this, String.format(Locale.ROOT, "%.0f%% practical + %.0f%% theory", share, 100 - share), 13, Ui.MUTED, false));
        prog.addView(meter("Overall", f.overall, Ui.NAVY));
        prog.addView(meter("Practical · " + f.done + " of " + Course.LESSONS.size() + " lessons", f.practical, Ui.TEAL));
        prog.addView(meter("Theory · exams passed", f.theory, Ui.GOLD));
        box.addView(prog, Ui.margins(this, 0, 0, 0, 12));
        LinearLayout next = Ui.card(this);
        next.setBackground(Ui.ripple(Ui.round(Color.WHITE, Ui.dp(this, 16)), Ui.round(0xFF000000, Ui.dp(this, 16))));
        LinearLayout nr = Ui.row(this);
        nr.addView(Ui.icon(this, R.drawable.ic_flight, Ui.TEAL, 26));
        LinearLayout nc = Ui.col(this);
        nc.setPadding(Ui.dp(this, 14), 0, 0, 0);
        nc.addView(Ui.label(this, "Next lesson"));
        nc.addView(Ui.text(this, f.next, 16, Ui.TEXT, true));
        Course.Lesson nl = null;
        for (Course.Lesson l : Course.LESSONS) if (f.next.startsWith(l.id + ". ")) { nl = l; break; }
        if (nl != null) nc.addView(Ui.text(this, nl.duration + " · " + nl.mode, 13, Ui.MUTED, false));
        nr.addView(nc, Ui.weight(1));
        nr.addView(Ui.icon(this, R.drawable.ic_chevron, Ui.MUTED, 20));
        next.addView(nr);
        next.setOnClickListener(v -> { tab = "lessons"; lessonFilter = "todo"; body.removeAllViews(); render(); });
        box.addView(next, Ui.margins(this, 0, 0, 0, 12));
        if (f.flights > 0) {
            LinearLayout fl = Ui.card(this);
            fl.addView(Ui.title(this, "Flying", 17, Ui.NAVY));
            LinearLayout g = Ui.row(this);
            g.setPadding(0, Ui.dp(this, 10), 0, 0);
            g.addView(mini(String.valueOf(f.flights), "Flights"), Ui.weight(1));
            g.addView(mini(Course.hours(f.hours), "Total"), Ui.weight(1));
            g.addView(mini(Course.hours(f.solo), "Solo"), Ui.weight(1));
            g.addView(mini(Course.hours(f.ifr), "Instrument"), Ui.weight(1));
            fl.addView(g);
            fl.addView(kv("Last flight", Ui.auDate(f.lastFlight)));
            box.addView(fl, Ui.margins(this, 0, 0, 0, 12));
        }
        LinearLayout hr = Ui.card(this);
        hr.addView(Ui.title(this, "Hour reviews", 17, Ui.NAVY));
        hr.addView(Ui.text(this, "Aircraft hours at each milestone against the syllabus allowance. Overfly is estimated at $500/h. Tap to edit.", 13, Ui.MUTED, false));
        for (int m : Course.MILESTONES) {
            Course.Milestone ms = Course.milestone(s, m);
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
            LinearLayout c = Ui.col(this);
            c.addView(Ui.ellipsize(Ui.text(this, "After " + Course.label(String.valueOf(m)), 14, Ui.TEXT, true)));
            c.addView(Ui.text(this, "Allowance " + Course.hours(ms.planned) + (ms.accrued == null ? " · hours not entered" : " · flown " + Course.hours(ms.accrued)), 12, Ui.MUTED, false));
            r.addView(c, Ui.weight(1));
            if (ms.accrued != null) r.addView(ms.overfly > 0 ? Ui.pill(this, "+" + Course.hours(ms.overfly) + " · " + Course.money(ms.cost), Ui.AMBER, Ui.AMBER_BG) : Ui.pill(this, "Within allowance", Ui.GREEN, Ui.GREEN_BG));
            r.setOnClickListener(v -> editMilestone(m));
            r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220B2A4F), null, null));
            hr.addView(r);
        }
        box.addView(hr, Ui.margins(this, 0, 0, 0, 12));
        LinearLayout det = Ui.card(this);
        det.addView(Ui.title(this, "Details", 17, Ui.NAVY));
        det.addView(kv("Student ID / ARN", s.optString("studentId", "—")));
        det.addView(kv("Course", s.optString("course", "—")));
        det.addView(kv("Instructor", s.optString("instructor", "—")));
        det.addView(kv("Course start", Ui.auDate(s.optString("start"))));
        Store.Rec r = store.find(uid);
        if (r != null) det.addView(kv("Last updated", MainActivity.ago(r.updated) + (r.by.isEmpty() ? "" : " by " + r.by)));
        det.addView(Ui.button(this, "Edit details", false, v -> editDetails()), Ui.margins(this, 0, 14, 0, 0));
        box.addView(det);
        return scroller(box);
    }

    private View meter(String label, double v, int color) {
        LinearLayout l = Ui.col(this);
        l.setPadding(0, Ui.dp(this, 12), 0, 0);
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, label, 14, Ui.TEXT, false), Ui.weight(1));
        r.addView(Ui.text(this, Course.pct(v), 14, Ui.NAVY, true));
        l.addView(r);
        View b = Ui.bar(this, v, color);
        ((LinearLayout.LayoutParams) b.getLayoutParams()).topMargin = Ui.dp(this, 6);
        l.addView(b);
        return l;
    }

    private View mini(String v, String l) {
        LinearLayout c = Ui.col(this);
        c.addView(Ui.text(this, v, 16, Ui.NAVY, true));
        c.addView(Ui.text(this, l, 12, Ui.MUTED, false));
        return c;
    }

    private View kv(String k, String v) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, Ui.dp(this, 10), 0, 0);
        r.addView(Ui.text(this, k, 14, Ui.MUTED, false), Ui.weight(1));
        TextView t = Ui.text(this, v == null || v.isEmpty() ? "—" : v, 14, Ui.TEXT, true);
        t.setGravity(Gravity.END);
        r.addView(t, Ui.weight(1.4f));
        return r;
    }

    // ---------- lessons ----------
    private View lessons(JSONObject s) {
        JSONArray ls = s.optJSONArray("lessons");
        List<Object> rows = new ArrayList<>();
        String lastPhase = "";
        int todo = 0, prog = 0, done = 0;
        if (ls != null) for (int i = 0; i < ls.length(); i++) {
            JSONObject v = ls.optJSONObject(i);
            if (v == null) continue;
            String st = v.optString("status");
            if ("Completed".equals(st)) done++; else if ("In progress".equals(st)) prog++; else todo++;
            boolean show = "all".equals(lessonFilter) || ("todo".equals(lessonFilter) && !"Completed".equals(st)) || ("progress".equals(lessonFilter) && "In progress".equals(st)) || ("done".equals(lessonFilter) && "Completed".equals(st));
            if (!show) continue;
            String id = Course.id(v.opt("id"));
            String ph = Course.phase(id);
            if (!ph.equals(lastPhase)) { rows.add(ph); lastPhase = ph; }
            rows.add(v);
        }
        LinearLayout chips = Ui.row(this);
        String[][] fs = {{"all", "All " + (todo + prog + done)}, {"todo", "To do " + (todo + prog)}, {"progress", "In progress " + prog}, {"done", "Completed " + done}};
        for (String[] x : fs) Ui.gap(chips, Ui.chip(this, x[1], x[0].equals(lessonFilter), v -> { lessonFilter = x[0]; body.removeAllViews(); render(); }), 8);
        ListView lv = list();
        lv.addHeaderView(Ui.chipRow(this, chips), null, false);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int i) { return rows.get(i); }
            public long getItemId(int i) { return i; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int i) { return rows.get(i) instanceof String ? 1 : 0; }
            public boolean isEnabled(int i) { return !(rows.get(i) instanceof String); }
            public View getView(int i, View old, ViewGroup p) {
                Object o = rows.get(i);
                if (o instanceof String) {
                    TextView t = Ui.label(StudentActivity.this, (String) o);
                    t.setPadding(Ui.dp(StudentActivity.this, 20), Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 20), Ui.dp(StudentActivity.this, 6));
                    return t;
                }
                return lessonRow((JSONObject) o);
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> { Object o = p.getItemAtPosition(pos); if (o instanceof JSONObject) editLesson(Course.id(((JSONObject) o).opt("id"))); });
        return lv;
    }

    private ListView list() {
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        lv.setClipToPadding(false);
        lv.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 32));
        return lv;
    }

    private View lessonRow(JSONObject v) {
        String id = Course.id(v.opt("id")), st = v.optString("status");
        Course.Lesson l = Course.BY_ID.get(id);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 14), Ui.dp(this, 3), Ui.dp(this, 14), Ui.dp(this, 3));
        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.round(Color.WHITE, Ui.dp(this, 14)));
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        r.addView(statusDot(st));
        LinearLayout c = Ui.col(this);
        c.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        c.addView(Ui.ellipsize(Ui.text(this, Course.label(id), 15, Ui.TEXT, true)));
        String meta = l == null ? "" : l.duration + " · " + l.mode;
        String notes = v.optString("notes").trim();
        String smart = "";
        for (String line : notes.split("\n")) if (line.startsWith("Smart: ")) smart = line.substring(7);
        c.addView(Ui.ellipsize(Ui.text(this, meta + (smart.isEmpty() ? "" : " · " + smart), 12, Ui.MUTED, false)));
        String other = notes.replaceAll("(?m)^Smart: .*$", "").trim();
        if (!other.isEmpty()) c.addView(Ui.ellipsize(Ui.text(this, other.replace('\n', ' '), 12, Ui.TEAL, false)));
        r.addView(c, Ui.weight(1));
        if ("In progress".equals(st)) r.addView(Ui.pill(this, "In progress", Ui.AMBER, Ui.AMBER_BG));
        wrap.addView(r);
        return wrap;
    }

    private View statusDot(String st) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        boolean done = "Completed".equals(st) || "Passed".equals(st), prog = "In progress".equals(st), fail = "Failed".equals(st);
        if (done) g.setColor(Ui.GREEN);
        else if (fail) g.setColor(Ui.RED);
        else if (prog) { g.setColor(Ui.AMBER_BG); g.setStroke(Ui.dp(this, 2), Ui.AMBER); }
        else { g.setColor(Color.WHITE); g.setStroke(Ui.dp(this, 2), Ui.LINE); }
        f.setBackground(g);
        if (done) f.addView(Ui.icon(this, R.drawable.ic_check, Color.WHITE, 16), new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16), Gravity.CENTER));
        if (fail) f.addView(Ui.icon(this, R.drawable.ic_close, Color.WHITE, 16), new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        return f;
    }

    private JSONObject find(JSONArray a, String id) {
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) { JSONObject v = a.optJSONObject(i); if (v != null && Course.id(v.opt("id")).equals(id)) return v; }
        return null;
    }

    private void editLesson(String id) {
        JSONObject v = find(state().optJSONArray("lessons"), id);
        if (v == null) return;
        Course.Lesson l = Course.BY_ID.get(id);
        LinearLayout c = Ui.col(this);
        if (l != null) {
            TextView meta = Ui.text(this, l.duration + " · " + l.mode, 14, Ui.MUTED, false);
            meta.setPadding(0, 0, 0, Ui.dp(this, 12));
            c.addView(meta);
        }
        final String[] status = {v.optString("status", "Not started")};
        LinearLayout seg = Ui.col(this);
        c.addView(seg);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> { seg.removeAllViews(); seg.addView(Ui.segmented(this, new String[] {"Not started", "In progress", "Completed"}, status[0], p -> { status[0] = p; draw[0].run(); })); };
        draw[0].run();
        EditText notes = Ui.input(this, "Debrief, items to revise…", v.optString("notes"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(3);
        notes.setGravity(Gravity.TOP);
        c.addView(Ui.field(this, "Notes", notes));
        Dialog d = Ui.sheet(this, Course.label(id), c);
        c.addView(Ui.button(this, "Save", true, x -> {
            String n = notes.getText().toString();
            store.edit(uid, s -> { JSONObject t = find(s.optJSONArray("lessons"), id); if (t != null) { t.put("status", status[0]); t.put("notes", n); } });
            d.dismiss();
        }), Ui.margins(this, 0, 8, 0, 0));
        d.show();
    }

    // ---------- exams ----------
    private View exams(JSONObject s) {
        JSONArray ex = s.optJSONArray("exams");
        LinearLayout box = Ui.col(this);
        if (ex != null) for (int i = 0; i < ex.length(); i++) {
            JSONObject v = ex.optJSONObject(i);
            if (v == null) continue;
            String id = v.optString("id"), st = v.optString("status");
            LinearLayout r = Ui.row(this);
            r.setBackground(Ui.ripple(Ui.round(Color.WHITE, Ui.dp(this, 14)), Ui.round(0xFF000000, Ui.dp(this, 14))));
            r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            r.addView(statusDot(st));
            LinearLayout c = Ui.col(this);
            c.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
            String nm = Course.EXAM_NAMES.get(id);
            c.addView(Ui.text(this, id + (nm == null ? "" : " · " + nm), 15, Ui.TEXT, true));
            JSONArray at = v.optJSONArray("attempts");
            String meta = "Weight " + Course.id(v.opt("weight")) + "%" + (v.optString("examDate").isEmpty() ? "" : " · " + Ui.auDate(v.optString("examDate"))) + (at != null && at.length() > 1 ? " · " + at.length() + " attempts" : "");
            c.addView(Ui.text(this, meta, 12, Ui.MUTED, false));
            r.addView(c, Ui.weight(1));
            if ("Passed".equals(st)) r.addView(Ui.pill(this, "Passed", Ui.GREEN, Ui.GREEN_BG));
            else if ("Failed".equals(st)) r.addView(Ui.pill(this, "Failed", Ui.RED, Ui.RED_BG));
            r.setOnClickListener(x -> editExam(id));
            box.addView(r, Ui.margins(this, 0, 0, 0, 6));
        }
        return scroller(box);
    }

    private void editExam(String id) {
        JSONObject v = find(state().optJSONArray("exams"), id);
        if (v == null) return;
        LinearLayout c = Ui.col(this);
        final String[] status = {v.optString("status", "Not attempted")};
        final String[] date = {v.optString("examDate")};
        LinearLayout seg = Ui.col(this);
        c.addView(seg);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> { seg.removeAllViews(); seg.addView(Ui.segmented(this, new String[] {"Not attempted", "Passed", "Failed"}, status[0], p -> { status[0] = p; draw[0].run(); })); };
        draw[0].run();
        TextView dateBtn = Ui.button(this, date[0].isEmpty() ? "Choose exam date" : "Exam date · " + Ui.auDate(date[0]), false, null);
        dateBtn.setOnClickListener(x -> pickDate(date[0], d -> { date[0] = d; dateBtn.setText("Exam date · " + Ui.auDate(d)); }));
        c.addView(dateBtn, Ui.margins(this, 0, 4, 0, 4));
        EditText notes = Ui.input(this, "Notes", v.optString("notes"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        c.addView(Ui.field(this, "Notes", notes));
        String nm = Course.EXAM_NAMES.get(id);
        Dialog d = Ui.sheet(this, id + (nm == null ? "" : " · " + nm), c);
        c.addView(Ui.button(this, "Save", true, x -> {
            String n = notes.getText().toString();
            store.edit(uid, s -> {
                JSONObject t = find(s.optJSONArray("exams"), id);
                if (t == null) return;
                t.put("examDate", date[0]);
                t.put("notes", n);
                setExamResult(t, status[0]);
            });
            d.dismiss();
        }), Ui.margins(this, 0, 8, 0, 0));
        d.show();
    }

    // Same rule as the website: every pass or fail is kept as an attempt.
    static void setExamResult(JSONObject v, String result) throws Exception {
        String old = v.optString("status");
        if (old.equals(result)) return;
        JSONArray at = v.optJSONArray("attempts");
        if (at == null) { at = new JSONArray(); v.put("attempts", at); }
        if (("Passed".equals(old) || "Failed".equals(old))) {
            boolean has = false;
            for (int i = 0; i < at.length(); i++) if (old.equals(at.optJSONObject(i) == null ? null : at.optJSONObject(i).optString("result"))) has = true;
            if (!has) at.put(new JSONObject().put("result", old).put("date", v.optString("examDate")));
        }
        v.put("status", result);
        if ("Passed".equals(result) || "Failed".equals(result)) at.put(new JSONObject().put("result", result).put("date", v.optString("examDate")));
    }

    // ---------- attendance ----------
    private View attendance(JSONObject s, Course.Figures f) {
        List<JSONObject> items = new ArrayList<>();
        JSONArray at = s.optJSONArray("attendance");
        if (at != null) for (int i = 0; i < at.length(); i++) { JSONObject a = at.optJSONObject(i); if (a != null) { try { items.add(new JSONObject(a.toString()).put("_i", i)); } catch (Exception ignored) { } } }
        JSONArray fl = s.optJSONArray("flights");
        if (fl != null) for (int i = 0; i < fl.length(); i++) {
            JSONObject x = fl.optJSONObject(i);
            if (x == null) continue;
            try {
                String les = x.optString("lesson");
                items.add(new JSONObject().put("date", x.optString("date")).put("session", "Flight · " + (Course.BY_ID.containsKey(les) ? Course.label(les) : x.optString("raw", "Flight")))
                        .put("status", "Present").put("hours", x.optDouble("hours", 0)).put("notes", x.optString("instructor").isEmpty() ? (x.optBoolean("solo") ? "Solo" : "") : x.optString("instructor")).put("flight", true));
            } catch (Exception ignored) { }
        }
        Collections.sort(items, (a, b) -> b.optString("date").compareTo(a.optString("date")));
        ListView lv = list();
        LinearLayout head = Ui.col(this);
        head.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 8));
        LinearLayout sum = Ui.card(this);
        LinearLayout sr = Ui.row(this);
        Ui.Ring ring = new Ui.Ring(this, Ui.CHIP_BG, Ui.NAVY);
        boolean low = f.attendance >= 0 && f.attendance < 80;
        ring.set(Math.max(0, f.attendance), low ? Ui.RED : Ui.GREEN);
        sr.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        LinearLayout sc = Ui.col(this);
        sc.setPadding(Ui.dp(this, 16), 0, 0, 0);
        sc.addView(Ui.text(this, f.sessions + " sessions", 17, Ui.TEXT, true));
        LinearLayout pills = Ui.row(this);
        pills.setPadding(0, Ui.dp(this, 6), 0, 0);
        Ui.gap(pills, Ui.pill(this, f.present + " present", Ui.GREEN, Ui.GREEN_BG), 4);
        Ui.gap(pills, Ui.pill(this, f.late + " late", Ui.AMBER, Ui.AMBER_BG), 4);
        Ui.gap(pills, Ui.pill(this, f.absent + " absent", Ui.RED, Ui.RED_BG), 4);
        sc.addView(pills);
        if (f.sick > 0) sc.addView(Ui.text(this, f.sick + " sick with certificate (not counted)", 12, Ui.MUTED, false));
        if (low) { TextView w = Ui.text(this, "Below 80% — follow up with the student", 13, Ui.RED, true); w.setPadding(0, Ui.dp(this, 6), 0, 0); sc.addView(w); }
        sr.addView(sc, Ui.weight(1));
        sum.addView(sr);
        TextView rule = Ui.text(this, "Flights count as attended. Sick counts only with a medical certificate; sick without one, excused and no-shows count as absent.", 12, Ui.MUTED, false);
        rule.setPadding(0, Ui.dp(this, 10), 0, 0);
        sum.addView(rule);
        sum.addView(Ui.button(this, "Record attendance", true, v -> editAttendance(-1)), Ui.margins(this, 0, 12, 0, 0));
        head.addView(sum);
        lv.addHeaderView(head, null, false);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup p) {
                JSONObject a = items.get(i);
                FrameLayout wrap = new FrameLayout(StudentActivity.this);
                wrap.setPadding(Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 3), Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 3));
                LinearLayout r = Ui.row(StudentActivity.this);
                r.setBackground(Ui.round(Color.WHITE, Ui.dp(StudentActivity.this, 14)));
                r.setPadding(Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 11), Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 11));
                LinearLayout c = Ui.col(StudentActivity.this);
                c.addView(Ui.text(StudentActivity.this, Ui.auDate(a.optString("date")), 13, Ui.MUTED, true));
                c.addView(Ui.ellipsize(Ui.text(StudentActivity.this, a.optString("session"), 15, Ui.TEXT, a.optBoolean("flight") ? false : true)));
                String n = a.optString("notes");
                if (!n.isEmpty()) c.addView(Ui.ellipsize(Ui.text(StudentActivity.this, n, 12, Ui.MUTED, false)));
                r.addView(c, Ui.weight(1));
                String st = a.optString("status");
                int fg = "Present".equals(st) ? Ui.GREEN : "Late".equals(st) ? Ui.AMBER : Course.SICK.equals(st) ? Ui.MUTED : Ui.RED;
                int bg = "Present".equals(st) ? Ui.GREEN_BG : "Late".equals(st) ? Ui.AMBER_BG : Course.SICK.equals(st) ? Ui.CHIP_BG : Ui.RED_BG;
                r.addView(Ui.pill(StudentActivity.this, "Excused".equals(st) ? "Absent" : Course.SICK.equals(st) ? "Sick · cert." : st, fg, bg));
                wrap.addView(r);
                return wrap;
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject a = (JSONObject) p.getItemAtPosition(pos);
            if (a == null) return;
            if (a.optBoolean("flight")) { Ui.toast(this, "Flights come from Smart flight reports."); return; }
            editAttendance(a.optInt("_i", -1));
        });
        return lv;
    }

    private void editAttendance(int index) {
        JSONArray at = state().optJSONArray("attendance");
        JSONObject a = index >= 0 && at != null ? at.optJSONObject(index) : null;
        LinearLayout c = Ui.col(this);
        final String[] date = {a == null ? Store.today() : a.optString("date")};
        final String[] status = {a == null ? "Present" : ("Excused".equals(a.optString("status")) ? "Absent" : a.optString("status"))};
        TextView dateBtn = Ui.button(this, "Date · " + Ui.auDate(date[0]), false, null);
        dateBtn.setOnClickListener(x -> pickDate(date[0], d -> { date[0] = d; dateBtn.setText("Date · " + Ui.auDate(d)); }));
        c.addView(dateBtn, Ui.margins(this, 0, 0, 0, 4));
        EditText session = Ui.input(this, "Theory class, briefing, flight…", a == null ? "Theory class" : a.optString("session"), 0);
        c.addView(Ui.field(this, "Session", session));
        LinearLayout seg = Ui.col(this);
        c.addView(seg);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> { seg.removeAllViews(); seg.addView(Ui.segmented(this, new String[] {"Present", "Late", "Absent", Course.SICK}, status[0], p -> { status[0] = p; draw[0].run(); })); };
        draw[0].run();
        EditText notes = Ui.input(this, "Reason, certificate details…", a == null ? "" : a.optString("notes"), 0);
        c.addView(Ui.field(this, "Notes", notes));
        Dialog d = Ui.sheet(this, a == null ? "Record attendance" : "Attendance", c);
        c.addView(Ui.button(this, "Save", true, x -> {
            String ses = session.getText().toString().trim().isEmpty() ? "Theory class" : session.getText().toString().trim(), n = notes.getText().toString().trim();
            store.edit(uid, s -> {
                JSONArray list = s.optJSONArray("attendance");
                if (list == null) { list = new JSONArray(); s.put("attendance", list); }
                JSONObject item = index >= 0 ? list.optJSONObject(index) : null;
                if (item == null) { item = new JSONObject(); list.put(item); }
                item.put("date", date[0]).put("session", ses).put("status", status[0]).put("notes", n);
                if (!item.has("hours")) item.put("hours", "");
            });
            d.dismiss();
        }), Ui.margins(this, 0, 8, 0, 0));
        if (a != null) {
            TextView del = Ui.button(this, "Delete", false, x -> {
                store.edit(uid, s -> { JSONArray list = s.optJSONArray("attendance"); if (list != null && index < list.length()) list.remove(index); });
                d.dismiss();
            });
            del.setTextColor(Ui.RED);
            c.addView(del, Ui.margins(this, 0, 8, 0, 0));
        }
        d.show();
    }

    // ---------- flights ----------
    private View flights(JSONObject s) {
        JSONArray fl = s.optJSONArray("flights");
        List<JSONObject> items = new ArrayList<>();
        if (fl != null) for (int i = 0; i < fl.length(); i++) { JSONObject x = fl.optJSONObject(i); if (x != null) items.add(x); }
        Collections.sort(items, (a, b) -> b.optString("date").compareTo(a.optString("date")));
        if (items.isEmpty()) {
            LinearLayout box = Ui.col(this);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(Ui.icon(this, R.drawable.ic_flight, Ui.LINE, 56));
            TextView t = Ui.text(this, "No flights yet. Upload a Smart flight activity report to fill in this student's flying.", 15, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), 0);
            box.addView(t);
            box.setPadding(0, Ui.dp(this, 60), 0, 0);
            return box;
        }
        ListView lv = list();
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public boolean isEnabled(int i) { return false; }
            public View getView(int i, View old, ViewGroup p) {
                JSONObject x = items.get(i);
                FrameLayout wrap = new FrameLayout(StudentActivity.this);
                wrap.setPadding(Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 3), Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 3));
                LinearLayout r = Ui.row(StudentActivity.this);
                r.setBackground(Ui.round(Color.WHITE, Ui.dp(StudentActivity.this, 14)));
                r.setPadding(Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 11), Ui.dp(StudentActivity.this, 14), Ui.dp(StudentActivity.this, 11));
                LinearLayout dbox = Ui.col(StudentActivity.this);
                dbox.setGravity(Gravity.CENTER);
                String d = x.optString("date");
                dbox.addView(Ui.text(StudentActivity.this, d.length() >= 10 ? d.substring(8, 10) : "", 18, Ui.NAVY, true));
                dbox.addView(Ui.text(StudentActivity.this, month(d), 11, Ui.MUTED, true));
                r.addView(dbox, new LinearLayout.LayoutParams(Ui.dp(StudentActivity.this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout c = Ui.col(StudentActivity.this);
                c.setPadding(Ui.dp(StudentActivity.this, 10), 0, Ui.dp(StudentActivity.this, 8), 0);
                String les = x.optString("lesson");
                c.addView(Ui.ellipsize(Ui.text(StudentActivity.this, Course.BY_ID.containsKey(les) ? Course.label(les) : x.optString("raw", "Flight"), 15, Ui.TEXT, true)));
                String who = x.optBoolean("solo") ? "Solo" : x.optString("instructor");
                c.addView(Ui.ellipsize(Ui.text(StudentActivity.this, (who.isEmpty() ? "" : who + " · ") + x.optString("reg"), 12, Ui.MUTED, false)));
                r.addView(c, Ui.weight(1));
                r.addView(Ui.pill(StudentActivity.this, Course.hours(x.optDouble("hours", 0)), x.optBoolean("solo") ? Ui.TEAL : Ui.NAVY, Ui.NAVY_TINT));
                wrap.addView(r);
                return wrap;
            }
        });
        return lv;
    }

    private static String month(String iso) {
        if (iso == null || iso.length() < 10) return "";
        String[] m = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        try { return m[Integer.parseInt(iso.substring(5, 7)) - 1] + " " + iso.substring(2, 4); } catch (Exception e) { return ""; }
    }

    // ---------- details, milestones, removal ----------
    interface DatePicked { void picked(String iso); }

    private void pickDate(String iso, DatePicked p) {
        Calendar c = Calendar.getInstance();
        try { if (iso != null && iso.length() >= 10) c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10))); } catch (Exception ignored) { }
        new DatePickerDialog(this, (v, y, m, d) -> p.picked(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)), c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void editDetails() {
        JSONObject s = state();
        LinearLayout c = Ui.col(this);
        EditText name = Ui.input(this, "Full name", s.optString("name"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText sid = Ui.input(this, "Student ID / ARN", s.optString("studentId"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText instr = Ui.input(this, "Instructor", s.optString("instructor"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText course = Ui.input(this, "Course", s.optString("course"), 0);
        c.addView(Ui.field(this, "Student name", name));
        c.addView(Ui.field(this, "Student ID / ARN", sid));
        c.addView(Ui.field(this, "Instructor", instr));
        c.addView(Ui.field(this, "Course", course));
        final String[] start = {s.optString("start")};
        TextView sb = Ui.button(this, start[0].isEmpty() ? "Choose course start date" : "Course start · " + Ui.auDate(start[0]), false, null);
        sb.setOnClickListener(x -> pickDate(start[0], d -> { start[0] = d; sb.setText("Course start · " + Ui.auDate(d)); }));
        c.addView(sb, Ui.margins(this, 0, 8, 0, 4));
        Dialog d = Ui.sheet(this, "Student details", c);
        c.addView(Ui.button(this, "Save", true, x -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { name.setError("Enter the name"); return; }
            store.edit(uid, st -> st.put("name", n).put("studentId", sid.getText().toString().trim()).put("instructor", instr.getText().toString().trim()).put("course", course.getText().toString().trim()).put("start", start[0]));
            d.dismiss();
        }), Ui.margins(this, 0, 10, 0, 0));
        d.show();
    }

    private void editMilestone(int id) {
        Course.Milestone m = Course.milestone(state(), id);
        LinearLayout c = Ui.col(this);
        TextView t = Ui.text(this, "Cumulative aircraft hours (no simulator) by the review after " + Course.label(String.valueOf(id)) + ".", 14, Ui.MUTED, false);
        c.addView(t);
        EditText allow = Ui.input(this, "Syllabus allowance", String.valueOf(m.planned), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText acc = Ui.input(this, "Hours flown", m.accrued == null ? "" : String.valueOf(m.accrued), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        c.addView(Ui.field(this, "Allowance (h)", allow));
        c.addView(Ui.field(this, "Hours flown (h)", acc));
        Dialog d = Ui.sheet(this, "Hour review · lesson " + id, c);
        c.addView(Ui.button(this, "Save", true, x -> {
            String a = allow.getText().toString().trim(), h = acc.getText().toString().trim();
            store.edit(uid, s -> {
                JSONObject ms = s.optJSONObject("milestones");
                if (ms == null) { ms = new JSONObject(); s.put("milestones", ms); }
                JSONObject r = ms.optJSONObject(String.valueOf(id));
                if (r == null) { r = new JSONObject(); ms.put(String.valueOf(id), r); }
                r.put("allowance", a).put("accrued", h);
            });
            d.dismiss();
        }), Ui.margins(this, 0, 10, 0, 0));
        d.show();
    }

    private void removeStudent() {
        JSONObject s = state();
        new AlertDialog.Builder(this).setTitle("Remove " + s.optString("name") + "?").setMessage("This removes the student from the shared database for everyone.")
                .setPositiveButton("Remove", (dlg, w) -> store.remove(uid, e -> { if (e != null) Ui.toast(this, e); else finish(); }))
                .setNegativeButton("Cancel", null).show();
    }
}
