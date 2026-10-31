// Copyright (c) 2026 Moorabbin Aviation Services CPL Tracker. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

// The attendance and progress report: the same report.js the website uses, shown in a WebView and
// printed with Android's print service (which offers "Save as PDF" as well as printers).
public class ReportActivity extends Activity {
    private WebView web;
    private String uid, file = "Report";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uid = getIntent().getStringExtra("uid");
        Store.Rec r = Store.get(this).find(uid);
        if (r == null || r.state == null) { finish(); return; }
        final String name = r.state.optString("name", "Student");
        getWindow().setStatusBarColor(Ui.NAVY);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Color.parseColor("#f2f4f7"));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.NAVY);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Back", v -> finish()));
        LinearLayout t = Ui.col(this);
        t.addView(Ui.text(this, name, 17, Color.WHITE, true));
        t.addView(Ui.text(this, "Attendance and progress report", 12, Color.parseColor("#c9d4e3"), false));
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView pdf = Ui.text(this, "Save PDF", 15, Ui.NAVY, true);
        pdf.setBackgroundColor(Color.parseColor("#d9bf8c"));
        pdf.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        pdf.setContentDescription("Save as PDF or print");
        pdf.setOnClickListener(v -> print());
        bar.addView(pdf);
        root.addView(bar);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        web.loadUrl("file:///android_asset/report.html");
    }

    private void print() {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) { Ui.toast(this, "Printing is not available on this phone."); return; }
        web.evaluateJavascript("window.beforePrint&&beforePrint()", v -> {
            String job = file;
            pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build());
        });
    }

    final class Bridge {
        private final Context c;
        Bridge(Context c) { this.c = c; }

        @JavascriptInterface public String state() {
            Store.Rec r = Store.get(c).find(uid);
            return r == null || r.state == null ? "null" : r.state.toString();
        }

        @JavascriptInterface public void title(String f) { file = f; }

        @JavascriptInterface public String catalogue() {
            try (java.io.InputStream in = c.getAssets().open("catalogue.json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) { return "{\"order\":[],\"lessons\":{},\"template\":{}}"; }
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
