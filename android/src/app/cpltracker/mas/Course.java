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
        double practical, theory, overall, attendance = -1, hours, solo, ifr;
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
        if (counted > 0) f.attendance = (f.present + f.late) * 100.0 / counted;
        return f;
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
