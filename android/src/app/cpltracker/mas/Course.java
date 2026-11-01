// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.content.Context;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

// The CPL syllabus (lessons, durations, exams) and the progress figures, matching the website's calculations.
final class Course {
    static final class Lesson { String id, name, duration, mode; double hours; }

    static final List<Lesson> LESSONS = new ArrayList<>();
    static final Map<String, Lesson> BY_ID = new HashMap<>();
    static final Map<String, String> EXAM_NAMES = new HashMap<>();
    static final int[] MILESTONES = {10, 21, 30, 37};
    static final double OVERFLY_RATE = 500;
    static JSONObject template;
    static final String SICK = "Sick (certificate)";

    static {
        EXAM_NAMES.put("FROL", "Flight Radio Operator Licence");
        EXAM_NAMES.put("Pre-Solo", "Pre-solo exam");
        EXAM_NAMES.put("Pre-Area Solo", "Pre-area solo exam");
        EXAM_NAMES.put("CMET", "CPL Meteorology");
        EXAM_NAMES.put("CFPA", "CPL Flight Planning");
        EXAM_NAMES.put("CADA", "CPL Aerodynamics");
        EXAM_NAMES.put("CNAV", "CPL Navigation");
        EXAM_NAMES.put("CLWA", "CPL Air Law");
        EXAM_NAMES.put("CHUF", "CPL Human Factors");
        EXAM_NAMES.put("CSYA", "CPL Aircraft Systems");
    }

    private Course() {}

    static synchronized void load(Context c) {
        if (template != null) return;
        try (InputStream in = c.getAssets().open("catalogue.json")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            loadJson(o.toString("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Syllabus missing from the app", e);
        }
    }

    static synchronized void loadJson(String text) {
        if (template != null) return;
        try {
            JSONObject j = new JSONObject(text);
            JSONArray order = j.getJSONArray("order");
            JSONObject cat = j.getJSONObject("lessons");
            for (int i = 0; i < order.length(); i++) {
                String id = order.getString(i);
                JSONObject x = cat.getJSONObject(id);
                Lesson l = new Lesson();
                l.id = id; l.name = x.optString("name"); l.duration = x.optString("duration"); l.mode = x.optString("mode"); l.hours = x.optDouble("hours", 0);
                LESSONS.add(l);
                BY_ID.put(id, l);
            }
            template = j.getJSONObject("template");
        } catch (Exception e) {
            throw new RuntimeException("Syllabus missing from the app", e);
        }
    }

    static String id(Object o) {
        if (o instanceof Number) { double d = ((Number) o).doubleValue(); if (d == Math.floor(d)) return String.valueOf((long) d); }
        return String.valueOf(o);
    }

    static String label(String id) { Lesson l = BY_ID.get(id); return l == null ? id : id + ". " + l.name; }

    static int order(String id) { for (int i = 0; i < LESSONS.size(); i++) if (LESSONS.get(i).id.equals(id)) return i; return -1; }

    static String phase(String id) {
        int n = (int) Double.parseDouble(id.replace(".a", ""));
        return n <= 33 ? "RPL · Recreational Pilot Licence" : n <= 44 ? "PPL · Private Pilot Licence" : "CPL · Commercial Pilot Licence";
    }

    static JSONObject blank(String name) {
        try {
            JSONObject s = new JSONObject(template.toString());
            s.put("name", name);
            s.put("date", Store.today());
            return s;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- figures ----------
    static final class Figures {
        double practical, theory, overall, attendance = -1, hoursRate = -1, hours, solo, ifr;
        int done, flights, present, late, absent, sick, sessions;
        String next = "Course complete", lastFlight = "", last = "", lastDate = "";
        int gaps;
    }

    static Figures figures(JSONObject s) {
        Figures f = new Figures();
        JSONArray ls = s.optJSONArray("lessons"), ex = s.optJSONArray("exams");
        double total = 0, got = 0;
        if (ls != null) for (int i = 0; i < ls.length(); i++) {
            JSONObject v = ls.optJSONObject(i);
            if (v == null) continue;
            double w = v.optDouble("weight", 0);
            total += w;
            if ("Completed".equals(v.optString("status"))) { got += w; f.done++; }
            else if ("Course complete".equals(f.next)) f.next = label(id(v.opt("id")));
        }
        f.practical = total > 0 ? got / total * 100 : 0;
        if (ex != null) for (int i = 0; i < ex.length(); i++) {
            JSONObject v = ex.optJSONObject(i);
            if (v != null && "Passed".equals(v.optString("status"))) f.theory += v.optDouble("weight", 0);
        }
        double share = s.optDouble("share", 60);
        if (Double.isNaN(share)) share = 60;
        f.overall = f.practical * share / 100 + f.theory * (1 - share / 100);
        JSONArray fl = s.optJSONArray("flights");
        if (fl != null) for (int i = 0; i < fl.length(); i++) {
            JSONObject x = fl.optJSONObject(i);
            if (x == null) continue;
            f.flights++;
            double h = x.optDouble("hours", 0);
            f.hours += h;
            if (x.optBoolean("solo")) f.solo += h;
            f.ifr += x.optDouble("ifr", 0);
            String d = x.optString("date");
            if (d.compareTo(f.lastFlight) > 0) f.lastFlight = d;
        }
        // What they last flew, and the next lesson after the furthest one flown (earlier unmarked lessons are gaps).
        int furthest = -1;
        JSONObject lastF = null;
        if (fl != null) for (int i = 0; i < fl.length(); i++) {
            JSONObject x = fl.optJSONObject(i);
            if (x == null) continue;
            if (lastF == null || x.optString("date").compareTo(lastF.optString("date")) >= 0) lastF = x;
            String les = x.optString("lesson");
            if (BY_ID.containsKey(les)) furthest = Math.max(furthest, order(les));
        }
        if (lastF != null) {
            String les = lastF.optString("lesson");
            f.last = BY_ID.containsKey(les) ? label(les) : lastF.optString("raw", "Flight");
            f.lastDate = lastF.optString("date");
        }
        if (furthest >= 0 && ls != null) {
            f.next = "Course complete";
            for (int i = 0; i < ls.length(); i++) {
                JSONObject v = ls.optJSONObject(i);
                if (v == null) continue;
                String lid = id(v.opt("id"));
                int o = order(lid);
                boolean done = "Completed".equals(v.optString("status"));
                if (o < furthest && !done) f.gaps++;
                if (o > furthest && !done && "Course complete".equals(f.next)) f.next = label(lid);
            }
            if ("Course complete".equals(f.next)) for (int i = 0; i < ls.length(); i++) {
                JSONObject v = ls.optJSONObject(i);
                if (v != null && !"Completed".equals(v.optString("status"))) { f.next = label(id(v.opt("id"))); break; }
            }
        }
        String course = s.optString("course");
        if (!course.isEmpty() && !course.matches("(?i).*(CPL|Commercial Pil).*")) { f.next = ""; f.gaps = 0; }
        JSONArray at = s.optJSONArray("attendance");
        f.present = f.flights;
        if (at != null) for (int i = 0; i < at.length(); i++) {
            JSONObject a = at.optJSONObject(i);
            if (a == null) continue;
            String st = a.optString("status");
            if ("Present".equals(st)) f.present++;
            else if ("Late".equals(st)) f.late++;
            else if (SICK.equals(st)) f.sick++;
            else f.absent++;
        }
        f.sessions = f.present + f.late + f.absent + f.sick;
        int counted = f.sessions - f.sick;
        // Same rule as the website: flights alone never make an attendance figure; with an Aviation 1 in 60
        // record it is hours attended against 20 h a week (as in the report).
        f.hoursRate = hoursRate(s, today());
        if (f.hoursRate >= 0) f.attendance = f.hoursRate;
        else if (counted > 0 && counted > f.flights) f.attendance = (f.present + f.late) * 100.0 / counted;
        return f;
    }

    static String today() {
        java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        d.setTimeZone(java.util.TimeZone.getTimeZone("Australia/Melbourne"));
        return d.format(new java.util.Date());
    }

    // Days since 1970-01-01 and back (java.time needs Android 8).
    static long day(String iso) {
        int y = Integer.parseInt(iso.substring(0, 4)), m = Integer.parseInt(iso.substring(5, 7)), d = Integer.parseInt(iso.substring(8, 10));
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400, yoe = y - era * 400, doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1, doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    static String iso(long z) {
        z += 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097, doe = z - era * 146097, yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365, y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100), mp = (5 * doy + 2) / 153, d = doy - (153 * mp + 2) / 5 + 1, m = mp + (mp < 10 ? 3 : -9);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", y + (m <= 2 ? 1 : 0), m, d);
    }

    private static int minutes(String t) { return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5)); }

    // Hours attended against 20 h for every completed, recorded week (report.js core()), or -1 without a record.
    static double hoursRate(JSONObject s, String today) {
        try {
            JSONObject recs = s.optJSONObject("records"), rec = recs == null ? null : recs.optJSONObject("oneIn60");
            if (rec == null) return -1;
            java.util.Map<Long, Double> fly = new java.util.HashMap<>();
            JSONArray fl = s.optJSONArray("flights");
            if (fl != null) for (int i = 0; i < fl.length(); i++) {
                JSONObject x = fl.optJSONObject(i);
                if (x == null || x.optString("date").length() < 10 || x.optDouble("hours", 0) <= 0) continue;
                long k = day(x.optString("date"));
                fly.put(k, (fly.containsKey(k) ? fly.get(k) : 0) + x.optDouble("hours", 0));
            }
            long from = day(rec.getString("from")), to = Math.min(day(rec.getString("to")), day(today));
            java.util.List<long[]> ranges = new java.util.ArrayList<>();
            JSONArray rs = rec.optJSONArray("ranges");
            if (rs != null) for (int i = 0; i < rs.length(); i++) ranges.add(new long[] {day(rs.getJSONArray(i).getString(0)), day(rs.getJSONArray(i).getString(1))});
            else ranges.add(new long[] {from, day(rec.getString("to"))});
            java.util.Map<Long, JSONObject> scan = new java.util.HashMap<>();
            JSONArray ds = rec.optJSONArray("days");
            if (ds != null) for (int i = 0; i < ds.length(); i++) scan.put(day(ds.getJSONObject(i).getString("date")), ds.getJSONObject(i));
            java.util.Map<Long, Integer> ground = new java.util.HashMap<>();
            JSONArray ws = rec.optJSONArray("weeks");
            if (ws != null) for (int i = 0; i < ws.length(); i++) {
                JSONObject w = ws.getJSONObject(i);
                if (w.optString("status").toLowerCase(java.util.Locale.US).contains("progress")) continue;
                long st = day(w.getString("week"));
                double sum = 0;
                for (int k = 0; k < 7; k++) { JSONObject d = scan.get(st + k); if (d != null) sum += d.optDouble("hours", 0); }
                double extra = Math.round((w.optDouble("attended", 0) - sum) * 10) / 10.0;
                if (extra >= 0.1 && st <= to && st + 6 >= from) ground.put(st, (int) Math.round(extra * 60));
            }
            long assessMin = 0; int assess = 0;
            for (long wk = from - ((from + 3) % 7 + 7) % 7; wk <= to; wk += 7) {
                long min = ground.containsKey(wk) ? ground.get(wk) : 0;
                boolean nr = false;
                for (long d = wk; d < wk + 7; d++) {
                    if (d < from || d > to) continue;
                    boolean cov = false;
                    for (long[] r : ranges) if (d >= r[0] && d <= r[1]) cov = true;
                    if (!cov) nr = true;
                    JSONObject sc = scan.get(d);
                    String in = sc == null ? "" : sc.optString("in"), out = sc == null ? "" : sc.optString("out");
                    if (in.length() >= 5 && out.length() >= 5) min += Math.max(0, minutes(out) - minutes(in));
                    else if (in.length() >= 5) min += 180;
                    else if (fly.containsKey(d)) min += Math.round(fly.get(d) * 60) + 180;
                }
                if (wk + 6 > to || nr) continue;
                assess++;
                assessMin += min;
            }
            return assess == 0 ? -1 : assessMin * 100.0 / (assess * 1200.0);
        } catch (Exception e) { return -1; }
    }

    static final class Milestone { int id; double planned; Double accrued; Double overfly; Double cost; }

    static Milestone milestone(JSONObject s, int id) {
        Milestone m = new Milestone();
        m.id = id;
        int idx = order(String.valueOf(id));
        double allowance = 0;
        for (int i = 0; i <= idx && i < LESSONS.size(); i++) {
            Lesson l = LESSONS.get(i);
            if (!l.name.toLowerCase(Locale.ROOT).matches(".*\\bsim\\b.*")) allowance += l.hours;
        }
        JSONObject ms = s.optJSONObject("milestones");
        JSONObject r = ms == null ? null : ms.optJSONObject(String.valueOf(id));
        String a = r == null ? "" : r.optString("allowance", ""), acc = r == null ? "" : r.optString("accrued", "");
        m.planned = !a.isEmpty() ? parse(a, allowance) : Math.round(allowance * 10) / 10.0;
        if (!acc.isEmpty()) {
            m.accrued = parse(acc, 0);
            m.overfly = Math.max(0, m.accrued - m.planned);
            m.cost = m.overfly * OVERFLY_RATE;
        }
        return m;
    }

    private static double parse(String s, double d) { try { return Double.parseDouble(s); } catch (Exception e) { return d; } }

    static String pct(double v) { return String.format(Locale.ROOT, v >= 10 || v == 0 ? "%.0f%%" : "%.1f%%", v); }

    static String hours(double v) { return String.format(Locale.ROOT, "%.1f h", v); }

    static String money(double v) { return String.format(Locale.ROOT, "$%,.0f", v); }
}
