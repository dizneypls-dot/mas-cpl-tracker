// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

// New versions of the app are published on the website (app/version.json + the APK). The app checks once a day
// (or when asked), downloads the update and hands it to Android's installer; Android only accepts it if it is
// signed with the same key, so student data on the phone is kept.
final class Updater {
    static final String BASE = "https://dizneypls-dot.github.io/mas-cpl-tracker/app/";

    private Updater() {}

    static int versionCode(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    static String versionName(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static void check(Activity a, boolean manual) {
        SharedPreferences p = a.getSharedPreferences("updates", 0);
        if (!manual && System.currentTimeMillis() - p.getLong("checked", 0) < 6 * 3600 * 1000L) return;
        p.edit().putLong("checked", System.currentTimeMillis()).apply();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "version.json?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setUseCaches(false);
                JSONObject j = new JSONObject(Store.readAll(c.getInputStream()));
                int code = j.getInt("versionCode");
                if (code <= versionCode(a)) { if (manual) main.post(() -> Ui.toast(a, "You have the latest version (" + versionName(a) + ").")); return; }
                main.post(() -> {
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a).setTitle("Update available · " + j.optString("versionName"))
                            .setMessage(j.optString("notes", "A new version of CPL Tracker is ready."))
                            .setPositiveButton("Update", (d, w) -> download(a, j.optString("apk", "CPLTracker.apk")))
                            .setNegativeButton("Later", null).show();
                });
            } catch (Exception e) {
                if (manual) main.post(() -> Ui.toast(a, "Could not check for updates. Check the internet connection."));
            }
        }, "update-check").start();
    }

    private static void download(Activity a, String apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("Allow updates").setMessage("To install updates, allow CPL Tracker to install apps, then tap Update again.")
                    .setPositiveButton("Open settings", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        Ui.toast(a, "Downloading the update…");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File out = new File(ShareProvider.dir(a), "CPLTracker-update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + apk + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                }
                main.post(() -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(ShareProvider.uriFor(out), "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                });
            } catch (Exception e) {
                main.post(() -> Ui.toast(a, "The update could not be downloaded."));
            }
        }, "update-download").start();
    }
}
