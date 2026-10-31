// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

// Full-screen WebView hosting the CPL tracker page. Student records live in the WebView's
// localStorage (and the shared database when connected); exports go to Downloads/CPL Tracker.
// Live updates: the newest published page is downloaded from the website in the background and
// used from the next start (or at once via "Restart now"). It is always shown with the same
// file:///android_asset base address, so saved student data stays where it is.
public class MainActivity extends Activity {
    private static final int PICK_FILE = 7;
    private static final String SITE = "https://dizneypls-dot.github.io/mas-cpl-tracker/index.html";
    private static final String BASE = "file:///android_asset/index.html";
    private static final Pattern BUILD = Pattern.compile("<meta name=\"cpl-build\" content=\"([0-9TZ:-]{20})\">");
    private WebView web;
    private ValueCallback<Uri[]> pendingPick;
    private String loadedBuild = "";
    private long lastCheck = 0;
    private volatile boolean checking = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("mailto".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_SENDTO, u)); } catch (Exception e) { }
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, PICK_FILE); } catch (Exception e) { pendingPick = null; return false; }
                return true;
            }
        });
        setContentView(web);
        loadPage();
    }

    private File liveFile() { return new File(getFilesDir(), "live/index.html"); }

    private static String buildOf(String html) {
        Matcher m = BUILD.matcher(html.length() > 4096 ? html.substring(0, 4096) : html);
        return m.find() ? m.group(1) : "";
    }

    private static String readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new Exception("too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // Newest of: the page inside the app, or the downloaded update.
    private void loadPage() {
        String bundled = "";
        try (InputStream in = getAssets().open("index.html")) { bundled = buildOf(readAll(in, 16 << 20)); } catch (Exception e) { }
        File live = liveFile();
        if (live.exists()) {
            try (InputStream in = new java.io.FileInputStream(live)) {
                String html = readAll(in, 16 << 20);
                String b = buildOf(html);
                if (!b.isEmpty() && b.compareTo(bundled) > 0) {
                    loadedBuild = b;
                    web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", BASE);
                    return;
                }
                live.delete();
            } catch (Exception e) { live.delete(); }
        }
        loadedBuild = bundled;
        web.loadUrl(BASE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (System.currentTimeMillis() - lastCheck > 30 * 60 * 1000L) checkForUpdate();
    }

    // Downloads the published page if it is newer, checks it looks complete, and keeps it for next time.
    private void checkForUpdate() {
        if (checking) return;
        checking = true;
        lastCheck = System.currentTimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SITE + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                String html;
                try (InputStream in = c.getInputStream()) { html = readAll(in, 16 << 20); }
                String b = buildOf(html);
                if (b.isEmpty() || b.compareTo(loadedBuild) <= 0) return;
                if (!html.contains("id=\"initial\"") || !html.contains("/*students:end*/") || !html.trim().endsWith("</html>")) return;
                File live = liveFile();
                live.getParentFile().mkdirs();
                File tmp = new File(live.getPath() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(live)) return;
                String quoted = JSONObject.quote(b);
                runOnUiThread(() -> web.evaluateJavascript("window.appUpdateReady&&window.appUpdateReady(" + quoted + ")", null));
            } catch (Exception e) {
                // Offline or the site is unavailable: keep the current version.
            } finally {
                checking = false;
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == PICK_FILE && pendingPick != null) {
            Uri[] picked = null;
            if (result == RESULT_OK && data != null && data.getClipData() != null) {
                ClipData cd = data.getClipData();
                picked = new Uri[cd.getItemCount()];
                for (int k = 0; k < picked.length; k++) picked[k] = cd.getItemAt(k).getUri();
            } else picked = WebChromeClient.FileChooserParams.parseResult(result, data);
            pendingPick.onReceiveValue(picked);
            pendingPick = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? window.appBack() : false", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        return n.isEmpty() ? "file" : n;
    }

    class Bridge {
        // "Restart now" after an update was downloaded.
        @JavascriptInterface
        public void restartApp() { runOnUiThread(MainActivity.this::loadPage); }

        // Saves an export (tracker HTML, Excel backup, report, .eml) into Downloads/CPL Tracker
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            String safe = safeName(name);
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CPL Tracker");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                    return "Saved to Downloads/CPL Tracker/" + safe;
                }
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File f = new File(dir, safe);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                return "Saved to " + f.getAbsolutePath();
            } catch (Exception e) {
                return "Could not save the file: " + e.getMessage();
            }
        }

        // Print report: the page's print layout through Android's print service (printer or Save as PDF)
        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                if (pm != null) pm.print("CPL progress report", web.createPrintDocumentAdapter("CPL progress report"),
                    new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            });
        }

        // {to, subject, body, files: [{name, mime, b64}]} -> email/share sheet with the report attached
        @JavascriptInterface
        public String shareEmail(String json) {
            try {
                JSONObject j = new JSONObject(json);
                File dir = ShareProvider.dir(MainActivity.this);
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                JSONArray files = j.getJSONArray("files");
                JSONObject f = files.getJSONObject(0);
                File out = new File(dir, safeName(f.getString("name")));
                try (FileOutputStream o = new FileOutputStream(out)) { o.write(Base64.decode(f.getString("b64"), Base64.DEFAULT)); }
                Uri uri = ShareProvider.uriFor(out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(f.optString("mime", "text/html"));
                String to = j.optString("to", "");
                if (!to.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[] { to });
                i.putExtra(Intent.EXTRA_SUBJECT, j.optString("subject"));
                i.putExtra(Intent.EXTRA_TEXT, j.optString("body"));
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.setClipData(ClipData.newRawUri("", uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(i, "Send progress email");
                runOnUiThread(() -> startActivity(chooser));
                return "ok";
            } catch (Exception e) {
                return "Could not prepare the email: " + e.getMessage();
            }
        }
    }
}
