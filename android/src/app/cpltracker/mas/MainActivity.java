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
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

// Full-screen WebView hosting the CPL tracker page from assets. Student records live in the
// WebView's localStorage on this device; exports go to Downloads/CPL Tracker.
public class MainActivity extends Activity {
    private static final int PICK_FILE = 7;
    private WebView web;
    private ValueCallback<Uri[]> pendingPick;

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
                try { startActivityForResult(i, PICK_FILE); } catch (Exception e) { pendingPick = null; return false; }
                return true;
            }
        });
        setContentView(web);
        if (state == null || web.restoreState(state) == null)
            web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == PICK_FILE && pendingPick != null) {
            pendingPick.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            pendingPick = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack ? window.appBack() : false", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        return n.isEmpty() ? "file" : n;
    }

    class Bridge {
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
