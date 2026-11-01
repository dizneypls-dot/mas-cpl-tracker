// Copyright (c) 2026 Moorabbin Aviation Services CPL Tracker. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

// The app shows the same screens as https://coursetracker.online, so both always look and work alike.
// Native parts: offline copy of the website (assets/web, used when there is no connection), sign-in kept
// from earlier versions, file picking for uploads, files shared to the app, Save PDF via Android's print
// service, app updates, crash reports.
public class MainActivity extends Activity {
    static final String SITE = "https://coursetracker.online/";
    static final String HOST = "coursetracker.online";
    private static final int PICK = 7401;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String[] pendingShare;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(R.style.AppTheme);
        super.onCreate(b);
        Diag.install(this);
        getWindow().setStatusBarColor(Ui.NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f4f6fa"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " CPLTrackerApp/" + Updater.versionName(this));
        web.addJavascriptInterface(new Bridge(), "CPLApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        setContentView(web);
        if (b != null) web.restoreState(b);
        else web.loadUrl(SITE);
        takeShare(getIntent());
        Updater.check(this, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); takeShare(i); }

    @Override
    public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override
    protected void onDestroy() { web.removeJavascriptInterface("CPLApp"); web.destroy(); super.onDestroy(); }

    // ---------- the website, with an offline copy ----------

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().endsWith(".apk")) { Updater.check(MainActivity.this, true); return true; }
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Ui.toast(MainActivity.this, "No app can open this link."); }
            return true;
        }

        // Without a connection the website's files come from the copy inside the app (same address, so the
        // records saved on the phone stay available). The shared database itself (/api) always needs a connection.
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!HOST.equals(u.getHost()) || !"GET".equals(r.getMethod())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            if (path.startsWith("/api/") || online()) return null;
            if (path.endsWith("/")) path += "index.html";
            try {
                InputStream in = getAssets().open("web" + path);
                String ext = MimeTypeMap.getFileExtensionFromUrl(path);
                String mime = "js".equals(ext) ? "text/javascript" : "css".equals(ext) ? "text/css" : "json".equals(ext) ? "application/json"
                        : "webmanifest".equals(ext) ? "application/manifest+json" : "woff2".equals(ext) ? "font/woff2" : "html".equals(ext) ? "text/html" : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, "UTF-8", 200, "OK", h, in);
            } catch (Exception e) { return null; }
        }

        @Override
        public void onPageFinished(WebView v, String url) { deliverShare(0); }
    }

    // ---------- choosing files for uploads ----------

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(Intent.createChooser(i, "Choose files"), PICK); }
            catch (Exception e) { fileCallback = null; cb.onReceiveValue(null); return false; }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) { out = new Uri[cd.getItemCount()]; for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri(); }
            else if (data.getData() != null) out = new Uri[] {data.getData()};
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    // ---------- files shared to the app (e.g. WhatsApp → Share → CPL Tracker) ----------

    private void takeShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        new Thread(() -> {
            try {
                String name = "shared-file";
                try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                }
                String mime = getContentResolver().getType(u);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) { bo.write(buf, 0, n); if (bo.size() > 15 * 1024 * 1024) throw new Exception("too large"); }
                }
                String[] p = {name, mime == null ? "application/octet-stream" : mime, Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)};
                main.post(() -> { pendingShare = p; deliverShare(0); });
            } catch (Exception e) { main.post(() -> Ui.toast(this, "Could not open the shared file (15 MB at most).")); }
        }).start();
    }

    // The page may still be loading or signing in: try again until the website is ready (about 30 s).
    private void deliverShare(int tries) {
        if (pendingShare == null || tries > 60) return;
        String[] p = pendingShare;
        web.evaluateJavascript("(function(){try{return typeof cplShared==='function'&&!!(JSON.parse(localStorage.getItem('mas-cpl-web')||'{}').code)}catch(e){return false}})()", ready -> {
            if (!"true".equals(ready)) { main.postDelayed(() -> deliverShare(tries + 1), 500); return; }
            if (pendingShare != p) return;
            pendingShare = null;
            web.evaluateJavascript("cplShared(" + JSONObject.quote(p[0]) + "," + JSONObject.quote(p[1]) + ",'" + p[2] + "')", null);
        });
    }

    // ---------- bridge for the website ----------

    private final class Bridge {
        private SharedPreferences prefs() { return getSharedPreferences("account", Context.MODE_PRIVATE); }

        // Sign-in from app versions before 3.0, handed to the website once.
        @JavascriptInterface public String signIn() {
            SharedPreferences p = prefs();
            String code = p.getString("code", "");
            if (code.isEmpty()) return "null";
            try { return new JSONObject().put("code", code).put("user", p.getString("user", "")).put("role", p.getString("role", "instructor")).toString(); }
            catch (Exception e) { return "null"; }
        }

        @JavascriptInterface public void signedIn(String json) {
            try { JSONObject j = new JSONObject(json); prefs().edit().putString("url", SITE).putString("code", j.optString("code")).putString("user", j.optString("user")).putString("role", j.optString("role")).apply(); }
            catch (Exception ignored) { }
        }

        @JavascriptInterface public void signedOut() { prefs().edit().clear().apply(); }

        @JavascriptInterface public String version() { return Updater.versionName(MainActivity.this); }

        @JavascriptInterface public void checkUpdate() { main.post(() -> Updater.check(MainActivity.this, true)); }

        // Save PDF / Print: Android's print service, A4 landscape (the report's page size).
        @JavascriptInterface public void print(String title) {
            main.post(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) { Ui.toast(MainActivity.this, "Printing is not available on this phone."); return; }
                String job = title == null || title.isEmpty() ? "CPL Tracker report" : title;
                pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build());
            });
        }
    }
}
