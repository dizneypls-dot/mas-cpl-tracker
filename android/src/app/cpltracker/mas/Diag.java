// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

// Problem reports: crashes are saved on the phone and sent to the shared database's problem log on the next start,
// and screens that fail to draw send their details, so problems can be fixed without needing screenshots.
final class Diag {
    private Diag() {}

    static void install(Context c) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Catcher) return;
        Thread.setDefaultUncaughtExceptionHandler(new Catcher(c.getApplicationContext(), prev));
    }

    private static final class Catcher implements Thread.UncaughtExceptionHandler {
        private final Context c;
        private final Thread.UncaughtExceptionHandler prev;
        Catcher(Context c, Thread.UncaughtExceptionHandler prev) { this.c = c; this.prev = prev; }

        @Override public void uncaughtException(Thread t, Throwable e) {
            try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "crash.txt"))) {
                o.write(("Crash in thread " + t.getName() + "\n" + stack(e)).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    static String stack(Throwable e) { StringWriter w = new StringWriter(); e.printStackTrace(new PrintWriter(w)); return w.toString(); }

    static void sendPending(Activity a) {
        File f = new File(a.getFilesDir(), "crash.txt");
        if (!f.exists()) return;
        String text;
        try (FileInputStream in = new FileInputStream(f)) { text = Store.readAll(in); } catch (Exception e) { return; }
        f.delete();
        report(a, text);
        new AlertDialog.Builder(a).setTitle("Sorry — something went wrong").setMessage("The app closed because of a problem. The details have been sent so it can be fixed.\n\n" + text.split("\n")[Math.min(1, text.split("\n").length - 1)])
                .setPositiveButton("OK", null).show();
    }

    static void report(Context c, String text) {
        Store st = Store.get(c);
        if (!st.signedIn()) return;
        String version;
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { version = "?"; }
        final String v = version;
        new Thread(() -> {
            try {
                String body = new JSONObject().put("report", text).put("app", "android " + v)
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")").toString();
                st.post("/api/diag", body);
            } catch (Exception ignored) { }
        }, "diag").start();
    }

    // Sizes of every view on a screen, to find layout problems on devices we cannot see.
    static String tree(View v, int depth) {
        StringBuilder b = new StringBuilder();
        tree(v, depth, 0, b);
        return b.toString();
    }

    private static void tree(View v, int max, int d, StringBuilder b) {
        if (d > max || b.length() > 15000) return;
        for (int i = 0; i < d; i++) b.append("  ");
        b.append(v.getClass().getSimpleName()).append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(v.getVisibility() == View.VISIBLE ? "" : " hidden");
        if (v instanceof TextView) { String t = String.valueOf(((TextView) v).getText()); b.append(" \"").append(t.length() > 24 ? t.substring(0, 24) + "…" : t).append('"'); }
        b.append('\n');
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount() && i < 12; i++) tree(g.getChildAt(i), max, d + 1, b); }
    }
}
