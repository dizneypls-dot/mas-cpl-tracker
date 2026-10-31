// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

// Smart flight activity reports and event cancellation reports, read on the phone with the same rules as the website:
// the student on each logbook row, flights counted once, lessons and hour reviews from the full flight history,
// and student-caused cancellations as attendance (sick only counts with a medical certificate).
final class Importer {
    static final int PICK = 41;
    private static final String[] HOURS = {"se_icus_day", "se_icus_night", "se_dual_day", "se_dual_night", "se_command_day", "se_command_night", "me_icus_day", "me_icus_night",
            "me_dual_day", "me_dual_night", "me_command_day", "me_command_night", "co_pilot_day", "co_pilot_night"};

    private Importer() {}

    static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        a.startActivityForResult(i, PICK);
    }

    static void result(Activity a, int req, int res, Intent data) {
        if (req != PICK || res != Activity.RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        LinearLayout c = Ui.col(a);
        ProgressBar pb = new ProgressBar(a);
        c.addView(pb);
        TextView t = Ui.text(a, "Reading " + uris.size() + " file" + (uris.size() > 1 ? "s" : "") + "…", 15, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        c.addView(t);
        Dialog d = Ui.sheet(a, "Import records", c);
        d.setCancelable(false);
        d.show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Plan p = new Plan(Store.get(a).all());
            for (Uri u : uris) {
                try (InputStream in = a.getContentResolver().openInputStream(u)) {
                    p.read(Store.readAll(in), name(a, u));
                } catch (Exception e) {
                    p.notes.add(name(a, u) + ": could not be read (" + e.getMessage() + ")");
                }
            }
            p.finish();
            main.post(() -> { d.dismiss(); review(a, p); });
        }, "import").start();
    }

    private static String name(Activity a, Uri u) {
        try (android.database.Cursor c = a.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) { }
        return u.getLastPathSegment();
    }

    private static void review(Activity a, Plan p) {
        LinearLayout c = Ui.col(a);
        int created = 0, updated = 0, flights = 0, att = 0;
        for (Change ch : p.changes.values()) {
            if (ch.uid == null) created++; else updated++;
            flights += ch.newFlights;
            att += ch.attendance.size();
        }
        LinearLayout tiles = Ui.row(a);
        tiles.addView(tile(a, String.valueOf(created), "New students"), Ui.weight(1));
        tiles.addView(tile(a, String.valueOf(updated), "Updated"), Ui.weight(1));
        tiles.addView(tile(a, String.valueOf(flights), "New flights"), Ui.weight(1));
        tiles.addView(tile(a, String.valueOf(att), "Attendance"), Ui.weight(1));
        c.addView(tiles);
        for (String f : p.files) c.addView(line(a, R.drawable.ic_file, f, Ui.TEXT));
        TextView rule = Ui.text(a, "Lessons count as Completed once a later lesson has been flown after them; completed lessons are never moved back. Sick counts as absent unless the cancellation mentions a medical certificate.", 13, Ui.MUTED, false);
        rule.setPadding(0, Ui.dp(a, 12), 0, 0);
        c.addView(rule);
        int shown = 0;
        for (Change ch : p.changes.values()) {
            if (shown++ >= 12) break;
            String what = (ch.uid == null ? "New · " : "") + ch.name + " — " + (ch.newFlights > 0 ? ch.newFlights + " flights" : "") + (ch.attendance.isEmpty() ? "" : (ch.newFlights > 0 ? ", " : "") + ch.attendance.size() + " attendance");
            c.addView(line(a, R.drawable.ic_person, what, Ui.TEXT));
        }
        if (p.changes.size() > 12) c.addView(line(a, R.drawable.ic_people, "…and " + (p.changes.size() - 12) + " more", Ui.MUTED));
        for (String n : p.notes) c.addView(line(a, R.drawable.ic_info, n, Ui.MUTED));
        Dialog d = Ui.sheet(a, p.changes.isEmpty() ? "Nothing new to import" : "Review import", c);
        if (!p.changes.isEmpty()) c.addView(Ui.button(a, "Apply to " + p.changes.size() + " student" + (p.changes.size() > 1 ? "s" : ""), true, v -> { d.dismiss(); apply(a, p); }), Ui.margins(a, 0, 16, 0, 0));
        c.addView(Ui.button(a, p.changes.isEmpty() ? "Close" : "Cancel", false, v -> d.dismiss()), Ui.margins(a, 0, 8, 0, 0));
        d.show();
    }

    private static View tile(Activity a, String v, String l) {
        LinearLayout t = Ui.col(a);
        t.addView(Ui.text(a, v, 22, Ui.NAVY, true));
        t.addView(Ui.text(a, l, 12, Ui.MUTED, false));
        return t;
    }

    private static View line(Activity a, int icon, String s, int color) {
        LinearLayout r = Ui.row(a);
        r.setPadding(0, Ui.dp(a, 8), 0, 0);
        r.addView(Ui.icon(a, icon, Ui.MUTED, 18));
        TextView t = Ui.text(a, s, 14, color, false);
        t.setPadding(Ui.dp(a, 10), 0, 0, 0);
        r.addView(t, Ui.weight(1));
        return r;
    }

    private static void apply(Activity a, Plan p) {
        Store st = Store.get(a);
        int n = 0;
        for (Change ch : p.changes.values()) {
            try {
                if (ch.uid == null) {
                    JSONObject s = Course.blank(ch.name);
                    s.put("course", ch.course);
                    s.put("instructor", ch.instructor);
                    applyTo(s, ch, true);
                    st.create(s);
                } else st.edit(ch.uid, s -> applyTo(s, ch, false));
                n++;
            } catch (Exception ignored) { }
        }
        Ui.toast(a, n + " student" + (n == 1 ? "" : "s") + " updated");
    }

    static void applyTo(JSONObject s, Change ch, boolean isNew) throws Exception {
        if (!isNew) {
            if (s.optString("course").isEmpty() && !ch.course.isEmpty()) s.put("course", ch.course);
            if (s.optString("instructor").isEmpty() && !ch.instructor.isEmpty()) s.put("instructor", ch.instructor);
        }
        if (!ch.flights.isEmpty()) {
            JSONArray merged = mergeFlights(s.optJSONArray("flights"), ch.flights);
            s.put("flights", merged);
            derive(s, merged);
        }
        if (!ch.attendance.isEmpty()) {
            JSONArray list = s.optJSONArray("attendance");
            if (list == null) { list = new JSONArray(); s.put("attendance", list); }
            for (JSONObject x : ch.attendance) {
                int at = -1;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject y = list.optJSONObject(i);
                    if (y != null && y.optString("date").equals(x.optString("date")) && norm(y.optString("session")).equals(norm(x.optString("session")))) at = i;
                }
                if (at >= 0) { JSONObject y = list.getJSONObject(at); y.put("status", x.optString("status")); if (y.optString("notes").isEmpty()) y.put("notes", x.optString("notes")); }
                else list.put(new JSONObject(x.toString()));
            }
        }
    }

    static JSONArray mergeFlights(JSONArray have, List<JSONObject> add) {
        LinkedHashMap<String, JSONObject> m = new LinkedHashMap<>();
        if (have != null) for (int i = 0; i < have.length(); i++) { JSONObject f = have.optJSONObject(i); if (f != null) m.put(key(f), f); }
        for (JSONObject f : add) if (!m.containsKey(key(f))) m.put(key(f), f);
        List<JSONObject> l = new ArrayList<>(m.values());
        l.sort((x, y) -> x.optString("date").compareTo(y.optString("date")));
        JSONArray a = new JSONArray();
        for (JSONObject f : l) a.put(f);
        return a;
    }

    private static String key(JSONObject f) { String id = f.optString("id"); return id.isEmpty() ? f.optString("date") + f.optString("lesson") + f.optDouble("hours", 0) : id; }

    // Lesson status, notes and hour reviews from the student's whole flight history.
    static void derive(JSONObject s, JSONArray flights) throws Exception {
        Map<String, List<JSONObject>> per = new LinkedHashMap<>();
        List<JSONObject> withLesson = new ArrayList<>();
        for (int i = 0; i < flights.length(); i++) {
            JSONObject f = flights.getJSONObject(i);
            String l = f.optString("lesson");
            if (l.isEmpty() || !Course.BY_ID.containsKey(l)) continue;
            withLesson.add(f);
            per.computeIfAbsent(l, k -> new ArrayList<>()).add(f);
        }
        JSONArray lessons = s.optJSONArray("lessons");
        for (Map.Entry<String, List<JSONObject>> e : per.entrySet()) {
            String id = e.getKey();
            List<JSONObject> fs = e.getValue();
            JSONObject last = fs.get(fs.size() - 1);
            boolean later = false;
            for (JSONObject f : withLesson) if (f.optString("date").compareTo(last.optString("date")) > 0 && Course.order(f.optString("lesson")) > Course.order(id)) later = true;
            double hrs = 0;
            Set<String> who = new java.util.LinkedHashSet<>();
            for (JSONObject f : fs) { hrs += f.optDouble("hours", 0); String w = f.optString("instructor"); if (!w.isEmpty()) who.add(w); else if (f.optBoolean("solo")) who.add("solo"); }
            String note = "Smart: flown " + fs.size() + "× (" + String.format(Locale.ROOT, "%.1f", hrs) + " h), last " + Ui.auDate(last.optString("date")) + (who.isEmpty() ? "" : " · " + join(", ", who));
            if (lessons == null) continue;
            for (int i = 0; i < lessons.length(); i++) {
                JSONObject v = lessons.getJSONObject(i);
                if (!Course.id(v.opt("id")).equals(id)) continue;
                String now = v.optString("status");
                String to = later ? "Completed" : "In progress";
                if (!("Completed".equals(now) && !"Completed".equals(to))) v.put("status", to);
                StringBuilder b = new StringBuilder();
                for (String line : v.optString("notes").split("\n")) if (!line.isEmpty() && !line.startsWith("Smart:")) b.append(line).append('\n');
                v.put("notes", b.append(note).toString());
            }
        }
        if (!withLesson.isEmpty() && Course.order(withLesson.get(0).optString("lesson")) == 0) {
            int furthest = 0;
            for (JSONObject f : withLesson) furthest = Math.max(furthest, Course.order(f.optString("lesson")));
            JSONObject ms = s.optJSONObject("milestones");
            if (ms == null) { ms = new JSONObject(); s.put("milestones", ms); }
            for (int m : Course.MILESTONES) {
                int mi = Course.order(String.valueOf(m));
                if (furthest < mi) continue;
                double h = 0;
                for (JSONObject f : withLesson) if (Course.order(f.optString("lesson")) <= mi) h += f.optDouble("hours", 0);
                JSONObject r = ms.optJSONObject(String.valueOf(m));
                if (r == null) { r = new JSONObject(); ms.put(String.valueOf(m), r); }
                r.put("accrued", String.format(Locale.ROOT, "%.1f", h));
            }
        }
    }

    // ---------- reading the files ----------
    static final class Change {
        String uid, name, course = "", courseDate = "", instructor = "", instructorDate = "";
        final List<JSONObject> flights = new ArrayList<>();
        final List<JSONObject> attendance = new ArrayList<>();
        int newFlights;
    }

    static final class Plan {
        final Map<String, Change> changes = new LinkedHashMap<>();
        final List<String> files = new ArrayList<>(), notes = new ArrayList<>();
        final Map<String, Integer> skipped = new HashMap<>();
        final Set<String> seen = new HashSet<>(), unknown = new HashSet<>();
        final Map<String, String> byName = new HashMap<>();
        final Map<String, Store.Rec> existing = new HashMap<>();

        Plan(List<Store.Rec> recs) {
            for (Store.Rec r : recs) { byName.put(nameKey(r.state.optString("name")), r.uid); existing.put(r.uid, r); }
        }

        void read(String text, String file) {
            List<String[]> rows = csv(text);
            if (rows.isEmpty()) { notes.add(file + ": empty"); return; }
            Map<String, Integer> h = new HashMap<>();
            String[] head = rows.get(0);
            for (int i = 0; i < head.length; i++) h.put(head[i].trim().toLowerCase(Locale.ROOT), i);
            if (h.containsKey("occurred_at") && h.containsKey("owner_name") && h.containsKey("lesson")) { flights(rows, h); files.add(file + " · flight report, " + (rows.size() - 1) + " rows"); }
            else if (h.containsKey("cancellation type") && h.containsKey("students") && h.containsKey("event start local")) { cancellations(rows, h); files.add(file + " · cancellation report, " + (rows.size() - 1) + " rows"); }
            else notes.add(file + ": not a Smart flight or cancellation report. Use the website for other files.");
        }

        private Change change(String name) {
            String k = nameKey(name);
            Change c = changes.get(k);
            if (c == null) { c = new Change(); c.name = name; c.uid = byName.get(k); changes.put(k, c); }
            return c;
        }

        private void flights(List<String[]> rows, Map<String, Integer> h) {
            for (int r = 1; r < rows.size(); r++) {
                String[] row = rows.get(r);
                String owner = g(row, h, "owner_name"), pic = g(row, h, "pilot_name"), other = g(row, h, "other_pilot_name"), type = g(row, h, "flight_type");
                boolean solo = type.toLowerCase(Locale.ROOT).contains("solo");
                String student, instructor;
                if (solo) { student = pic.isEmpty() ? owner : pic; instructor = ""; }
                else if (!other.isEmpty() && owner.equals(other)) { student = owner; instructor = pic; }
                else if (!other.isEmpty() && owner.equals(pic)) { student = other; instructor = pic; }
                else { student = owner; instructor = pic.equals(owner) ? "" : pic; }
                if (student.isEmpty()) continue;
                String course = g(row, h, "course"), lessonText = g(row, h, "lesson");
                if (course.isEmpty() && lessonText.isEmpty()) { skipped.merge("No course or lesson (private flights)", 1, Integer::sum); continue; }
                String fid = g(row, h, "flight_id");
                if (fid.isEmpty()) fid = g(row, h, "id");
                if (!seen.add(fid + "|" + student)) continue;
                double hrs = 0;
                for (String k : HOURS) hrs += num(g(row, h, k));
                try {
                    JSONObject f = new JSONObject();
                    String date = date(g(row, h, "occurred_at"));
                    String les = lesson(lessonText);
                    f.put("id", fid).put("date", date).put("lesson", les == null ? JSONObject.NULL : les).put("raw", lessonText).put("instructor", instructor).put("solo", solo)
                            .put("hours", Math.round(hrs * 100) / 100.0).put("ifr", num(g(row, h, "instrument_flight"))).put("reg", g(row, h, "aircraft_registration"));
                    Change c = change(student);
                    c.flights.add(f);
                    if (!course.isEmpty() && date.compareTo(c.courseDate) >= 0) { c.course = course; c.courseDate = date; }
                    if (!instructor.isEmpty() && date.compareTo(c.instructorDate) >= 0) { c.instructor = instructor; c.instructorDate = date; }
                } catch (Exception ignored) { }
            }
        }

        private void cancellations(List<String[]> rows, Map<String, Integer> h) {
            for (int r = 1; r < rows.size(); r++) {
                String[] row = rows.get(r);
                String type = g(row, h, "event type");
                String tl = type.toLowerCase(Locale.ROOT);
                if (!tl.matches(".*(flight booking|ground school|long briefing|progress meeting|simulator|sim session|exam|theory|class).*") || tl.matches(".*(invigilat|unavailable).*")) continue;
                String ct = g(row, h, "cancellation type"), reason = g(row, h, "cancellation reason");
                String status = cancelStatus(ct, reason);
                if (status == null) continue;
                String[] students = g(row, h, "students").split(",");
                if (students.length != 1 || students[0].trim().isEmpty()) continue;
                String name = students[0].trim();
                if (!byName.containsKey(nameKey(name)) && !changes.containsKey(nameKey(name))) { unknown.add(name); continue; }
                String when = g(row, h, "event start local");
                String[] parts = when.split(" ");
                String date = date(parts[0]);
                if (date.isEmpty()) continue;
                String session = (type.isEmpty() ? "Booking" : type) + " " + (parts.length > 1 ? parts[1] : "") + " · " + g(row, h, "event name");
                String by = g(row, h, "cancelled by");
                try {
                    change(name).attendance.add(new JSONObject().put("date", date).put("session", session).put("status", status).put("hours", "")
                            .put("notes", (ct + (reason.isEmpty() ? "" : ": " + reason)) + (by.isEmpty() ? "" : " (cancelled by " + by + ")")));
                } catch (Exception ignored) { }
            }
        }

        void finish() {
            // Count only flights the student does not have yet.
            for (Change c : new ArrayList<>(changes.values())) {
                Set<String> have = new HashSet<>();
                if (c.uid != null) {
                    Store.Rec r = existing.get(c.uid);
                    JSONArray fl = r == null ? null : r.state.optJSONArray("flights");
                    if (fl != null) for (int i = 0; i < fl.length(); i++) have.add(key(fl.optJSONObject(i)));
                }
                for (JSONObject f : c.flights) if (!have.contains(key(f))) c.newFlights++;
                if (c.newFlights == 0) c.flights.clear();
                if (c.flights.isEmpty() && c.attendance.isEmpty()) changes.remove(nameKey(c.name));
            }
            for (Map.Entry<String, Integer> e : skipped.entrySet()) notes.add(e.getKey() + ": " + e.getValue() + " rows skipped");
            if (!unknown.isEmpty()) notes.add(unknown.size() + " people in the cancellation report are not in the tracker yet (upload their flight reports first)");
        }
    }

    static String cancelStatus(String type, String reason) {
        String t = norm(type + " " + reason), r = norm(reason);
        boolean sick = t.matches(".*student\\s*(sick|unwell|ill).*") || (r.matches(".*\\b(sick|unwell|ill)\\b.*") && t.contains("student"));
        if (sick) return r.matches(".*(cert|certificate|doctor|\\bgp\\b|\\bmc\\b).*") ? Course.SICK : "Absent";
        if (t.matches(".*student\\s*late.*")) return "Late";
        if (t.matches(".*student\\s*(cancelled|canceled|unprepared|no show|did not attend|absent).*") || t.contains("no show")) return "Absent";
        return null;
    }

    private static final Pattern LESSON = Pattern.compile("^(\\d{1,2})(?:\\.?(a))?\\.?\\s+(.*?)\\s*(\\(.*)?$", Pattern.CASE_INSENSITIVE);

    static String lesson(String text) {
        String t = text.trim();
        boolean syllabus = t.matches("(?i)^(RPL|PPL|CPL)\\s*\\(A\\).*");
        t = t.replaceFirst("(?i)^(RPL|PPL|CPL)\\s*\\(A\\)\\s*", "").trim();
        if (!syllabus && !t.matches("^\\d.*")) return null;
        Matcher m = LESSON.matcher(t);
        String byName = null;
        if (m.find()) {
            String id = m.group(2) != null ? m.group(1) + ".a" : String.valueOf(Integer.parseInt(m.group(1)));
            String nm = norm(m.group(3));
            byName = byName(nm);
            Course.Lesson l = Course.BY_ID.get(id);
            if (l != null) {
                String a = norm(l.name);
                if (nm.isEmpty() || a.equals(nm) || a.contains(nm) || nm.contains(a) || a.split(" ")[0].equals(nm.split(" ")[0]) || byName == null) return id;
            }
            return byName;
        }
        return byName(norm(t));
    }

    private static String byName(String n) {
        if (n.length() < 4) return null;
        String best = null;
        for (Course.Lesson l : Course.LESSONS) {
            String a = norm(l.name);
            if (a.equals(n)) return l.id;
            if (a.length() > 5 && (" " + n + " ").contains(" " + a + " ") && (best == null || a.length() > norm(Course.BY_ID.get(best).name).length())) best = l.id;
        }
        return best;
    }

    static String date(String v) {
        Matcher m = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{2})\\b").matcher(v);
        if (m.find()) return "20" + m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        m = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})").matcher(v);
        if (m.find()) { String y = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3); return y + "-" + pad(m.group(2)) + "-" + pad(m.group(1)); }
        m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(v);
        if (m.find()) return m.group(1) + "-" + pad(m.group(2)) + "-" + pad(m.group(3));
        return "";
    }

    private static String pad(String s) { return s.length() == 1 ? "0" + s : s; }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim(); }

    static String nameKey(String s) { String[] p = norm(s).split(" "); Arrays.sort(p); return join(" ", Arrays.asList(p)); }

    static String join(String sep, Iterable<String> parts) { StringBuilder b = new StringBuilder(); for (String x : parts) { if (b.length() > 0) b.append(sep); b.append(x); } return b.toString(); }

    private static String g(String[] row, Map<String, Integer> h, String k) { Integer i = h.get(k); return i == null || i >= row.length ? "" : row[i].trim(); }

    private static double num(String s) { try { return s.isEmpty() ? 0 : Double.parseDouble(s); } catch (Exception e) { return 0; } }

    static List<String[]> csv(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false; }
                else f.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { row.add(f.toString()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                if (row.size() > 1 || !row.get(0).isEmpty()) rows.add(row.toArray(new String[0]));
                row = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row.toArray(new String[0])); }
        return rows;
    }
}
