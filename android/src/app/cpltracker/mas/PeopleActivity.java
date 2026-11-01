// Copyright (c) 2026 Moorabbin Aviation Services CPL Tracker. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

// People (admins only): everyone who can sign in, add a person with their own access code,
// give someone a new code, remove access. Codes work on the website and in the app, from anywhere.
public class PeopleActivity extends Activity {
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        getWindow().setStatusBarColor(Ui.NAVY);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Color.parseColor("#f2f4f7"));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.NAVY);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Back", v -> finish()));
        bar.addView(Ui.text(this, "People", 18, Color.WHITE, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(Ui.iconButton(this, R.drawable.ic_add, Color.WHITE, "Add a person", v -> add()));
        root.addView(bar);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 24));
        box.addView(Ui.text(this, "Everyone who can sign in. Each person gets their own access code, which works on coursetracker.online and in this app, from anywhere.", 14, Ui.MUTED, false));
        box.addView(Ui.button(this, "Add a person", true, v -> add()), Ui.margins(this, 0, 12, 0, 12));
        list = Ui.card(this);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(list);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        call("GET", "/api/users", null, this::show);
    }

    interface Done { void ok(JSONObject r) throws Exception; }

    private void call(String method, String path, JSONObject body, Done done) {
        NET.execute(() -> {
            try {
                JSONObject r = new JSONObject(store.api(method, path, body == null ? null : body.toString()));
                main.post(() -> { if (isFinishing()) return; try { done.ok(r); } catch (Exception e) { Ui.toast(this, "Something went wrong."); } });
            } catch (Exception e) {
                String m = e.getMessage() == null ? "Could not reach the server." : e.getMessage();
                main.post(() -> { if (!isFinishing()) Ui.toast(this, m); });
            }
        });
    }

    private void show(JSONObject r) {
        list.removeAllViews();
        JSONArray us = r.optJSONArray("users");
        if (us == null) return;
        for (int i = 0; i < us.length(); i++) {
            JSONObject u = us.optJSONObject(i);
            if (u == null) continue;
            if (i > 0) list.addView(Ui.divider(this));
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12));
            row.addView(Ui.avatar(this, u.optString("name"), 40));
            LinearLayout m = Ui.col(this);
            m.setPadding(Ui.dp(this, 12), 0, 0, 0);
            m.addView(Ui.text(this, u.optString("name") + (u.optBoolean("you") ? " (you)" : ""), 15, Ui.TEXT, true));
            m.addView(Ui.text(this, "admin".equals(u.optString("role")) ? "Administrator" : "Instructor", 13, Ui.MUTED, false));
            row.addView(m, Ui.weight(1));
            if (!u.optBoolean("you")) {
                row.addView(Ui.iconButton(this, R.drawable.ic_sync, Ui.NAVY, "New code for " + u.optString("name"), v -> newCode(u)));
                row.addView(Ui.iconButton(this, R.drawable.ic_delete, Ui.RED, "Remove " + u.optString("name"), v -> remove(u)));
            }
            list.addView(row);
        }
    }

    private void add() {
        LinearLayout f = Ui.col(this);
        f.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        EditText name = new EditText(this);
        name.setHint("Name, e.g. Jane Smith");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(name);
        RadioGroup rg = new RadioGroup(this);
        RadioButton ins = new RadioButton(this), adm = new RadioButton(this);
        ins.setText("Instructor: sees and updates every student, uploads files");
        adm.setText("Administrator: also removes students and manages people");
        ins.setId(1); adm.setId(2);
        rg.addView(ins); rg.addView(adm);
        rg.check(1);
        f.addView(rg, Ui.margins(this, 0, 10, 0, 0));
        new AlertDialog.Builder(this).setTitle("Add a person").setView(f)
                .setPositiveButton("Create code", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Enter the person's name."); return; }
                    try {
                        call("POST", "/api/users", new JSONObject().put("name", n).put("role", rg.getCheckedRadioButtonId() == 2 ? "admin" : "instructor"), r -> { show(r); code(n, r.getString("code")); });
                    } catch (Exception e) { Ui.toast(this, "Something went wrong."); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void newCode(JSONObject u) {
        String n = u.optString("name");
        new AlertDialog.Builder(this).setTitle("New code for " + n + "?").setMessage("Their current code stops working straight away.")
                .setPositiveButton("New code", (d, w) -> call("POST", "/api/users/" + u.optString("id") + "/code", new JSONObject(), r -> { show(r); code(n, r.getString("code")); }))
                .setNegativeButton("Cancel", null).show();
    }

    private void remove(JSONObject u) {
        String n = u.optString("name");
        new AlertDialog.Builder(this).setTitle("Remove " + n + "?").setMessage("Their code stops working on every device.")
                .setPositiveButton("Remove", (d, w) -> call("DELETE", "/api/users/" + u.optString("id"), null, r -> { show(r); Ui.toast(this, n + " removed."); }))
                .setNegativeButton("Cancel", null).show();
    }

    private void code(String name, String code) {
        String msg = "CPL Tracker access for " + name + "\nSign in at https://coursetracker.online or in the Android app (https://coursetracker.online/app/CPLTracker.apk)\nAccess code: " + code;
        LinearLayout f = Ui.col(this);
        f.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        f.addView(Ui.text(this, "Give this code to " + name + " privately. It is shown only now; if it's lost, give them a new code.", 14, Ui.TEXT, false));
        TextView c = Ui.text(this, code, 26, Ui.NAVY, true);
        c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        c.setGravity(Gravity.CENTER);
        c.setTextIsSelectable(true);
        c.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        f.addView(c);
        new AlertDialog.Builder(this).setTitle("Access code for " + name).setView(f).setCancelable(false)
                .setPositiveButton("Share", (d, w) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Send the code privately")))
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("Access code", msg)); Ui.toast(this, "Copied. Paste it into a private message."); }
                })
                .setNegativeButton("Done", null).show();
    }
}
