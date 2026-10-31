// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

// "Read with Claude": a file is sent to the tracker server, Claude reads it and proposes record changes,
// and this screen shows them for review. Nothing is saved until the person taps Approve.
public class ScanActivity extends Activity {
    static final int PICK = 7302;
    static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("png", "image/png"); TYPES.put("jpg", "image/jpeg"); TYPES.put("jpeg", "image/jpeg"); TYPES.put("webp", "image/webp");
        TYPES.put("gif", "image/gif"); TYPES.put("pdf", "application/pdf"); TYPES.put("csv", "text/csv"); TYPES.put("txt", "text/plain");
        TYPES.put("tsv", "text/tab-separated-values"); TYPES.put("json", "application/json");
        TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private String id;
    private JSONObject job;
    private final Set<Integer> pick = new HashSet<>();
    private final Map<String, String> assign = new HashMap<>();
    private boolean picked;
    private FrameLayout body;
    private TextView subtitle;
    private int scrollY;
    private ScrollView scroller;
    private boolean alive;

    // ---------- choosing and uploading ----------

    static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf", "text/csv", "text/comma-separated-values", "text/plain",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/json", "application/octet-stream"});
        a.startActivityForResult(i, PICK);
    }

    /** Sends a file (picked, or shared from another app) to be read, then opens its review screen. */
    static void upload(Activity a, Uri uri) {
        Store st = Store.get(a);
        if (!st.signedIn()) { Ui.toast(a, "Sign in first, then share the file again."); return; }
        AlertDialog wait = new AlertDialog.Builder(a).setMessage("Uploading…").setCancelable(false).show();
        NET.execute(() -> {
            String err = null, sid = null;
            try {
                ContentResolver cr = a.getContentResolver();
                String name = "upload";
                try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                } catch (Exception ignored) { }
                String mime = cr.getType(uri);
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (TYPES.containsKey(ext)) mime = TYPES.get(ext);
                if (mime == null || !TYPES.containsValue(mime)) throw new IllegalArgumentException("Claude can read photos, screenshots, PDFs, Excel (.xlsx) and CSV files.");
                if (!name.contains(".")) for (Map.Entry<String, String> e : TYPES.entrySet()) if (e.getValue().equals(mime)) { name += "." + e.getKey(); break; }
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                try (InputStream in = cr.openInputStream(uri)) {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); if (o.size() > 15 * 1024 * 1024) throw new IllegalArgumentException("That file is over 15 MB."); }
                }
                String body = new JSONObject().put("name", name).put("mime", mime).put("data", Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)).toString();
                sid = new JSONObject(st.api("POST", "/api/scan", body)).getString("id");
            } catch (IllegalArgumentException e) {
                err = e.getMessage();
            } catch (Store.HttpError e) {
                err = e.getMessage();
            } catch (Exception e) {
                err = "Could not upload the file. Check the internet connection.";
            }
            final String fe = err, fid = sid;
            a.runOnUiThread(() -> {
                try { wait.dismiss(); } catch (Exception ignored) { }
                if (fe != null) { new AlertDialog.Builder(a).setTitle("Not uploaded").setMessage(fe).setPositiveButton("OK", null).show(); return; }
                a.startActivity(new Intent(a, ScanActivity.class).putExtra("id", fid));
            });
        });
    }

    // ---------- review screen ----------

    @Override
    protected void onCreate(Bundle b) {
        setTheme(R.style.AppTheme);
        super.onCreate(b);
        Diag.install(this);
        store = Store.get(this);
        id = getIntent().getStringExtra("id");
        getWindow().setStatusBarColor(Ui.NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.NAVY);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Back", v -> finish()));
        LinearLayout tc = Ui.col(this);
        tc.addView(Ui.title(this, "Review", 22, Color.WHITE));
        subtitle = Ui.ellipsize(Ui.text(this, "Read with Claude", 13, 0xB3FFFFFF, false));
        tc.addView(subtitle);
        bar.addView(tc, Ui.weight(1));
        root.addView(bar);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        showWaiting("Loading…");
    }

    @Override protected void onResume() { super.onResume(); alive = true; load(); }
    @Override protected void onPause() { super.onPause(); alive = false; main.removeCallbacksAndMessages(null); }

    private void load() {
        NET.execute(() -> {
            try {
                JSONObject j = new JSONObject(store.api("GET", "/api/scan/" + id, null));
                main.post(() -> { job = j; render(); if ("reading".equals(j.optString("status")) && alive) main.postDelayed(this::load, 3000); });
            } catch (Exception e) {
                main.post(() -> { if (job == null) showMessage("Could not load this file’s review. " + (e instanceof Store.HttpError ? e.getMessage() : "Check the internet connection.")); else if (alive) main.postDelayed(this::load, 5000); });
            }
        });
    }

    private void showWaiting(String msg) {
        body.removeAllViews();
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        c.addView(new ProgressBar(this));
        TextView t = Ui.text(this, msg, 15, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        c.addView(t);
        body.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showMessage(String msg) {
        body.removeAllViews();
        TextView t = Ui.text(this, msg, 15, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        body.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static String groupKey(JSONObject c) {
        String uid = c.optString("uid");
        return uid.isEmpty() ? "name:" + c.optString("name_in_document").trim().toLowerCase(Locale.ROOT) : uid;
    }

    private boolean assigned(JSONObject c) { return !c.optString("uid").isEmpty() || !TextUtils.isEmpty(assign.get(groupKey(c))); }

    private void render() {
        subtitle.setText(job.optString("name"));
        String status = job.optString("status");
        if ("reading".equals(status)) { showWaiting("Claude is reading " + job.optString("name") + ".\nThis usually takes under a minute. You can leave this screen; it carries on."); return; }
        if ("failed".equals(status)) { showMessage(job.optString("error", "Claude could not read this file.")); return; }
        boolean done = !"ready".equals(status);
        JSONArray changes = job.optJSONArray("changes");
        if (changes == null) changes = new JSONArray();
        if (!picked && !done) {
            for (int i = 0; i < changes.length(); i++) {
                JSONObject c = changes.optJSONObject(i);
                if (c.optString("problem").isEmpty() && !"low".equals(c.optString("confidence")) && !c.optString("uid").isEmpty()) pick.add(c.optInt("i"));
            }
            picked = true;
        }
        if (scroller != null && scroller.isAttachedToWindow()) scrollY = scroller.getScrollY();
        body.removeAllViews();
        FrameLayout frame = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        scroller = sv;
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, done ? 24 : 96));

        LinearLayout head = Ui.card(this);
        LinearLayout hr = Ui.row(this);
        hr.setGravity(Gravity.TOP);
        hr.addView(Ui.icon(this, R.drawable.ic_spark, Ui.GOLD, 28));
        LinearLayout hc = Ui.col(this);
        hc.setPadding(Ui.dp(this, 12), 0, 0, 0);
        hc.addView(Ui.title(this, job.optString("documentType", "Document").isEmpty() ? "Document" : job.optString("documentType"), 18, Ui.NAVY));
        hc.addView(Ui.text(this, job.optString("summary"), 14, Ui.MUTED, false));
        hr.addView(hc, Ui.weight(1));
        head.addView(hr);
        JSONArray warn = job.optJSONArray("warnings");
        for (int i = 0; warn != null && i < warn.length(); i++) head.addView(note(warn.optString(i), Ui.AMBER, Ui.AMBER_BG));
        if ("applied".equals(status)) {
            int n = job.optInt("applied");
            head.addView(note("Saved " + n + " change" + (n == 1 ? "" : "s") + " · approved by " + job.optString("appliedBy"), Ui.GREEN, Ui.GREEN_BG));
            JSONArray sk = job.optJSONArray("skipped");
            if (sk != null && sk.length() > 0) { List<String> l = new ArrayList<>(); for (int i = 0; i < sk.length(); i++) l.add(sk.optString(i)); head.addView(note(TextUtils.join("\n", l), Ui.MUTED, Ui.CHIP_BG)); }
        } else if ("discarded".equals(status)) head.addView(note("Discarded. Nothing was saved.", Ui.MUTED, Ui.CHIP_BG));
        int fresh = 0;
        for (int i = 0; i < changes.length(); i++) if (changes.optJSONObject(i).optString("problem").isEmpty()) fresh++;
        TextView meta = Ui.text(this, changes.length() + " change" + (changes.length() == 1 ? "" : "s") + " found · " + fresh + " new · read for " + job.optString("by") + " " + MainActivity.ago(job.optLong("created"))
                + (done ? "" : ". Unsure items are not ticked; check them against the file."), 13, Ui.MUTED, false);
        meta.setPadding(0, Ui.dp(this, 10), 0, 0);
        head.addView(meta);
        box.addView(head);

        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < changes.length(); i++) {
            JSONObject c = changes.optJSONObject(i);
            String k = groupKey(c);
            if (!groups.containsKey(k)) groups.put(k, new ArrayList<>());
            groups.get(k).add(c);
        }
        for (Map.Entry<String, List<JSONObject>> g : groups.entrySet()) box.addView(group(g.getKey(), g.getValue(), done), Ui.margins(this, 0, 14, 0, 0));
        if (groups.isEmpty()) {
            TextView t = Ui.text(this, "Claude found nothing to change in this file.", 15, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 40), 0, 0);
            box.addView(t);
        }
        sv.addView(box);
        frame.addView(sv);
        if (!done) {
            LinearLayout ab = Ui.row(this);
            ab.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 16)));
            ab.setElevation(Ui.dp(this, 10));
            ab.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            int n = selected().size();
            ab.addView(Ui.text(this, n + " selected", 14, Ui.MUTED, true), Ui.weight(1));
            ab.addView(Ui.button(this, "Discard", false, v -> discard()));
            TextView ok = Ui.button(this, "Approve " + n, true, v -> approve());
            ok.setEnabled(n > 0);
            ok.setAlpha(n > 0 ? 1f : .5f);
            ab.addView(ok, Ui.margins(this, 8, 0, 0, 0));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            lp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
            frame.addView(ab, lp);
        }
        body.addView(frame);
        final int y = scrollY;
        sv.post(() -> sv.scrollTo(0, y));
    }

    private View note(String s, int fg, int bg) {
        TextView t = Ui.text(this, s, 13, fg, true);
        t.setBackground(Ui.round(bg, Ui.dp(this, 12)));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        LinearLayout.LayoutParams lp = Ui.margins(this, 0, 10, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private View group(String key, List<JSONObject> cs, boolean done) {
        LinearLayout card = Ui.card(this);
        card.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        JSONObject first = cs.get(0);
        LinearLayout gh = Ui.row(this);
        gh.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 10));
        boolean matched = !first.optString("uid").isEmpty();
        String shown = matched ? first.optString("studentName") : first.optString("name_in_document", "Unknown person");
        gh.addView(Ui.avatar(this, shown, 34));
        LinearLayout gc = Ui.col(this);
        gc.setPadding(Ui.dp(this, 12), 0, 0, 0);
        gc.addView(Ui.text(this, shown, 15, Ui.TEXT, true));
        if (matched) {
            String inDoc = first.optString("name_in_document");
            if (!inDoc.trim().equalsIgnoreCase(shown.trim()) && !inDoc.isEmpty()) gc.addView(Ui.text(this, "“" + inDoc + "” in the file", 12, Ui.MUTED, false));
        } else {
            String a = assign.get(key);
            String who = TextUtils.isEmpty(a) ? "Not matched to a student · tap to choose" : "new".equals(a) ? "Will be added as a new student" : "Saving to " + nameOf(a);
            gc.addView(Ui.text(this, who, 12, TextUtils.isEmpty(a) ? Ui.RED : Ui.TEAL, true));
            if (!done) {
                Ui.clickable(gh, 0, Ui.SURFACE);
                gh.setOnClickListener(v -> choosePerson(key, cs));
            }
        }
        gh.addView(gc, Ui.weight(1));
        if (!matched && !done) gh.addView(Ui.icon(this, R.drawable.ic_chevron, Ui.MUTED, 20));
        card.addView(gh);
        List<JSONObject> known = new ArrayList<>();
        for (JSONObject c : cs) {
            if ("already recorded".equals(c.optString("problem"))) { known.add(c); continue; }
            card.addView(Ui.divider(this));
            card.addView(row(c, done));
        }
        if (!known.isEmpty()) {
            card.addView(Ui.divider(this));
            LinearLayout more = Ui.col(this);
            TextView t = Ui.text(this, "▸ " + known.size() + " already recorded", 13, Ui.MUTED, true);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            more.addView(t);
            t.setOnClickListener(v -> {
                if (more.getChildCount() > 1) { while (more.getChildCount() > 1) more.removeViewAt(1); t.setText("▸ " + known.size() + " already recorded"); }
                else { for (JSONObject c : known) { more.addView(Ui.divider(this)); more.addView(row(c, done)); } t.setText("▾ " + known.size() + " already recorded"); }
            });
            card.addView(more);
        }
        return card;
    }

    private View row(JSONObject c, boolean done) {
        int i = c.optInt("i");
        String problem = c.optString("problem");
        boolean bad = !problem.isEmpty() && !"already recorded".equals(problem);
        boolean saved = done && c.optBoolean("saved");
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 10));
        CheckBox cb = new CheckBox(this);
        cb.setChecked(done ? saved : pick.contains(i));
        cb.setEnabled(!done && problem.isEmpty());
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.NAVY));
        r.addView(cb);
        LinearLayout t = Ui.col(this);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, 0);
        t.addView(Ui.text(this, c.optString("label"), 15, problem.isEmpty() ? Ui.TEXT : Ui.MUTED, true));
        String before = c.optString("before"), after = c.optString("after"), date = c.optString("date");
        StringBuilder ba = new StringBuilder();
        if ("attendance".equals(c.optString("kind"))) ba.append(before).append(" · ");
        else if (!before.isEmpty()) ba.append(before).append(" → ");
        ba.append(after);
        if (!date.isEmpty()) ba.append(" · ").append(Ui.auDate(date));
        if ("exam".equals(c.optString("kind")) && c.optInt("attempts") > 1) ba.append(" · ").append(c.optInt("attempts")).append(" attempts");
        t.addView(Ui.text(this, ba.toString(), 14, Ui.NAVY, false));
        if (!c.optString("note").isEmpty()) t.addView(Ui.text(this, c.optString("note"), 12.5f, Ui.MUTED, false));
        TextView why = Ui.text(this, c.optString("reason"), 12.5f, Ui.MUTED, false);
        why.setTypeface(why.getTypeface(), android.graphics.Typeface.ITALIC);
        t.addView(why);
        if (!problem.isEmpty()) t.addView(Ui.text(this, bad ? problem : "Already recorded", 12.5f, bad ? Ui.RED : Ui.MUTED, bad));
        r.addView(t, Ui.weight(1));
        String conf = c.optString("confidence");
        if (saved) r.addView(Ui.pill(this, "Saved", Ui.GREEN, Ui.GREEN_BG));
        else if ("low".equals(conf)) r.addView(Ui.pill(this, "Unsure", Ui.RED, Ui.RED_BG));
        else if ("medium".equals(conf)) r.addView(Ui.pill(this, "Check", Ui.AMBER, Ui.AMBER_BG));
        if (!done && problem.isEmpty()) {
            View.OnClickListener toggle = v -> {
                if (!assigned(c)) { Ui.toast(this, "First choose who this is (tap the name above)."); cb.setChecked(false); return; }
                if (pick.contains(i)) pick.remove(i); else pick.add(i);
                render();
            };
            cb.setOnClickListener(toggle);
            r.setOnClickListener(v -> { cb.toggle(); toggle.onClick(v); });
        }
        return r;
    }

    private String nameOf(String uid) {
        Store.Rec r = store.find(uid);
        return r == null ? "a student" : r.state.optString("name");
    }

    private void choosePerson(String key, List<JSONObject> cs) {
        List<Store.Rec> all = store.all();
        List<String[]> people = new ArrayList<>();
        for (Store.Rec r : all) people.add(new String[] {r.uid, r.state.optString("name")});
        Collections.sort(people, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        String[] labels = new String[people.size() + 2];
        labels[0] = "Skip this person";
        labels[1] = "Add as a new student";
        for (int i = 0; i < people.size(); i++) labels[i + 2] = people.get(i)[1];
        new AlertDialog.Builder(this).setTitle(cs.get(0).optString("name_in_document", "Who is this?")).setItems(labels, (d, w) -> {
            String v = w == 0 ? "" : w == 1 ? "new" : people.get(w - 2)[0];
            assign.put(key, v);
            for (JSONObject c : cs) if (c.optString("problem").isEmpty()) { if (v.isEmpty()) pick.remove(c.optInt("i")); else pick.add(c.optInt("i")); }
            render();
        }).show();
    }

    private List<Integer> selected() {
        List<Integer> out = new ArrayList<>();
        JSONArray changes = job.optJSONArray("changes");
        for (int i = 0; changes != null && i < changes.length(); i++) {
            JSONObject c = changes.optJSONObject(i);
            if (pick.contains(c.optInt("i")) && assigned(c)) out.add(c.optInt("i"));
        }
        return out;
    }

    private void approve() {
        List<Integer> sel = selected();
        if (sel.isEmpty()) return;
        try {
            JSONArray acc = new JSONArray();
            JSONObject asg = new JSONObject();
            JSONArray changes = job.optJSONArray("changes");
            for (int i : sel) {
                acc.put(i);
                JSONObject c = changes.optJSONObject(i);
                if (c.optString("uid").isEmpty()) asg.put(String.valueOf(i), assign.get(groupKey(c)));
            }
            String body = new JSONObject().put("accept", acc).put("assign", asg).toString();
            showWaiting("Saving…");
            NET.execute(() -> {
                try {
                    JSONObject r = new JSONObject(store.api("POST", "/api/scan/" + id + "/apply", body));
                    main.post(() -> { int n = r.optInt("applied"); Ui.toast(this, "Saved " + n + " change" + (n == 1 ? "" : "s")); load(); store.resume(); });
                } catch (Exception e) {
                    main.post(() -> { render(); new AlertDialog.Builder(this).setTitle("Not saved").setMessage(e instanceof Store.HttpError ? e.getMessage() : "Could not reach the server. Try again.").setPositiveButton("OK", null).show(); });
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "Could not prepare the changes.");
        }
    }

    private void discard() {
        new AlertDialog.Builder(this).setTitle("Discard this file?").setMessage("Nothing from it will be saved.").setPositiveButton("Discard", (d, w) -> NET.execute(() -> {
            try {
                store.api("POST", "/api/scan/" + id + "/discard", "{}");
                main.post(() -> { Ui.toast(this, "Discarded"); finish(); });
            } catch (Exception e) {
                main.post(() -> Ui.toast(this, e instanceof Store.HttpError ? e.getMessage() : "Could not reach the server."));
            }
        })).setNegativeButton("Cancel", null).show();
    }
}
