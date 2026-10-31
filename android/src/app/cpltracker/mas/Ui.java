// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

// Design system for the app: colours, type, cards, chips, buttons, rings and bottom sheets, all built in code.
final class Ui {
    static final int NAVY = 0xFF0B2A4F, NAVY2 = 0xFF123B6B, TEAL = 0xFF16627A, GOLD = 0xFFB8955A,
            BG = 0xFFF3F5F8, SURFACE = 0xFFFFFFFF, LINE = 0xFFE3E8EF, TEXT = 0xFF0F1C2E, MUTED = 0xFF5B6B80,
            GREEN = 0xFF1F7A4D, GREEN_BG = 0xFFE3F2EA, AMBER = 0xFFB7791F, AMBER_BG = 0xFFFDF1DC,
            RED = 0xFFB42318, RED_BG = 0xFFFBE5E5, CHIP_BG = 0xFFE8EDF3, NAVY_TINT = 0xFFDFE8F3;
    static final int[] AVATAR = {0xFF16627A, 0xFF0B2A4F, 0xFF7A5A20, 0xFF5B3E8E, 0xFF2E6B3F, 0xFF8E3E4E, 0xFF3E5B8E, 0xFF6B5B2E};

    private Ui() {}

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setIncludeFontPadding(true);
        return t;
    }

    static TextView title(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, MUTED, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    static TextView ellipsize(TextView t) { t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); return t; }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable stroke(int fill, int line, int widthPx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(widthPx, line);
        return g;
    }

    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(0x220B2A4F), content, mask);
    }

    static void clickable(View v, float radiusPx, int fill) {
        v.setBackground(ripple(round(fill, radiusPx), round(0xFF000000, radiusPx)));
        v.setClickable(true);
        v.setFocusable(true);
    }

    static LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }

    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(SURFACE, dp(c, 16)));
        l.setElevation(dp(c, 1.5f));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return l;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    static ImageView iconButton(Context c, int res, int color, String desc, View.OnClickListener l) {
        ImageView i = icon(c, res, color, 44);
        int p = dp(c, 10);
        i.setPadding(p, p, p, p);
        i.setBackground(new RippleDrawable(ColorStateList.valueOf(color == Color.WHITE ? 0x33FFFFFF : 0x220B2A4F), null, null));
        i.setContentDescription(desc);
        i.setOnClickListener(l);
        return i;
    }

    static TextView button(Context c, String s, boolean primary, View.OnClickListener l) {
        TextView b = text(c, s, 15, primary ? Color.WHITE : NAVY, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setMinHeight(dp(c, 48));
        b.setBackground(ripple(primary ? round(NAVY, dp(c, 12)) : stroke(SURFACE, LINE, dp(c, 1), dp(c, 12)), round(0xFF000000, dp(c, 12))));
        b.setOnClickListener(l);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView pill(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 11, fg, true);
        t.setLetterSpacing(0.03f);
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        t.setBackground(round(bg, dp(c, 99)));
        return t;
    }

    static TextView chip(Context c, String s, boolean on, View.OnClickListener l) {
        TextView t = text(c, s, 13, on ? Color.WHITE : TEXT, true);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(ripple(on ? round(NAVY, dp(c, 99)) : stroke(SURFACE, LINE, dp(c, 1), dp(c, 99)), round(0xFF000000, dp(c, 99))));
        t.setOnClickListener(l);
        t.setSingleLine(true);
        return t;
    }

    static HorizontalScrollView chipRow(Context c, LinearLayout inner) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        inner.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 8));
        h.addView(inner);
        return h;
    }

    static void gap(LinearLayout row, View v, int dpGap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) p.leftMargin = dp(row.getContext(), dpGap);
        row.addView(v, p);
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    static EditText input(Context c, String hint, String value, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setInputType(type == 0 ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : type);
        e.setTextSize(16);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF98A4B3);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(stroke(0xFFF8FAFC, LINE, dp(c, 1), dp(c, 12)));
        return e;
    }

    static LinearLayout field(Context c, String label, View input) {
        LinearLayout l = col(c);
        TextView t = text(c, label, 13, MUTED, true);
        t.setPadding(dp(c, 2), 0, 0, dp(c, 6));
        l.addView(t);
        l.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.setPadding(0, dp(c, 8), 0, dp(c, 8));
        return l;
    }

    static int avatarColor(String name) { return AVATAR[Math.abs((name == null ? "" : name).hashCode()) % AVATAR.length]; }

    static String initials(String name) {
        String n = name == null ? "" : name.replaceAll("\\(.*?\\)", "").trim();
        if (n.isEmpty()) return "?";
        String[] p = n.split("\\s+");
        String a = p[0].substring(0, 1), b = p.length > 1 ? p[p.length - 1].substring(0, 1) : "";
        return (a + b).toUpperCase();
    }

    static TextView avatar(Context c, String name, int sizeDp) {
        TextView t = text(c, initials(name), sizeDp * 0.36f, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(avatarColor(name));
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    static int statusColor(double pct) { return pct >= 80 ? GREEN : pct >= 50 ? TEAL : pct >= 20 ? AMBER : NAVY2; }

    // Circular progress with the percentage in the middle.
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private double value;
        private String text = "";

        Ring(Context c, int trackColor, int textColor) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(trackColor);
            label.setColor(textColor);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(double pct, int color) {
            value = Math.max(0, Math.min(100, pct));
            arc.setColor(color);
            text = (pct >= 99.95 ? "100" : String.format(java.util.Locale.ROOT, pct >= 10 ? "%.0f" : "%.1f", pct)) + "%";
            setContentDescription(text + " complete");
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), stroke = s * 0.11f;
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            box.set((w - s) / 2 + stroke / 2, (h - s) / 2 + stroke / 2, (w + s) / 2 - stroke / 2, (h + s) / 2 - stroke / 2);
            cv.drawArc(box, 0, 360, false, track);
            cv.drawArc(box, -90, (float) (value * 3.6), false, arc);
            label.setTextSize(s * 0.25f);
            cv.drawText(text, w / 2, h / 2 - (label.descent() + label.ascent()) / 2, label);
        }
    }

    // Thin horizontal bar.
    static View bar(Context c, double pct, int color) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(round(CHIP_BG, dp(c, 4)));
        View fill = new View(c);
        fill.setBackground(round(color, dp(c, 4)));
        f.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8)));
        f.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = (int) Math.round((r - l) * Math.max(0, Math.min(100, pct)) / 100.0);
            if (fill.getLayoutParams().width != w) { fill.getLayoutParams().width = w; fill.post(fill::requestLayout); }
        });
        return f;
    }

    // Bottom sheet: rounded panel that slides up from the bottom, with a title and scrolling content.
    static Dialog sheet(Activity a, String title, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = col(a);
        box.setBackground(roundTop(SURFACE, dp(a, 22)));
        View handle = new View(a);
        handle.setBackground(round(LINE, dp(a, 3)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(a, 10);
        box.addView(handle, hp);
        if (title != null) {
            TextView t = title(a, title, 20, NAVY);
            t.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4));
            box.addView(t);
        }
        ScrollView sv = new ScrollView(a);
        content.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 24));
        sv.addView(content);
        box.addView(sv);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.4f);
        }
        return d;
    }

    static GradientDrawable roundTop(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    // Segmented choice used in sheets (status pickers).
    interface Pick { void picked(String v); }

    static LinearLayout segmented(Context c, String[] options, String current, Pick p) {
        LinearLayout r = col(c);
        for (String o : options) {
            boolean on = o.equals(current);
            LinearLayout item = row(c);
            item.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            item.setBackground(ripple(on ? stroke(NAVY_TINT, NAVY, dp(c, 1.5f), dp(c, 12)) : stroke(SURFACE, LINE, dp(c, 1), dp(c, 12)), round(0xFF000000, dp(c, 12))));
            TextView t = text(c, o, 15, on ? NAVY : TEXT, on);
            item.addView(t, weight(1));
            if (on) item.addView(icon(c, R.drawable.ic_check, NAVY, 20));
            item.setOnClickListener(v -> p.picked(o));
            r.addView(item, margins(c, 0, 0, 0, 8));
        }
        return r;
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    static String auDate(String iso) {
        if (iso == null || iso.length() < 10) return iso == null ? "" : iso;
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    static String shortCourse(String c) {
        if (c == null || c.isEmpty()) return "No course";
        String s = c.replaceFirst("^MAS\\s+", "").replace("Pliot", "Pilot");
        if (s.contains("CPL (150hr integrated)")) return "CPL 150hr integrated";
        if (s.contains("CPL (200hr non-integrated)")) return "CPL 200hr" + (s.endsWith("2026") ? " (2026)" : "");
        return s.replaceFirst("^Commercial Pilot Licence - ", "");
    }
}
