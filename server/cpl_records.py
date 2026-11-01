# Copyright (c) 2026 Moorabbin Aviation Services CPL Tracker. All rights reserved.
"""Reads the attendance files the student report is built from, without Claude:

  * Aviation 1 in 60 - Student Support attendance report (PDF): fingerprint days, weekly hours,
    course sessions, missed sessions and requirement adjustments for one student;
  * Smart Aviation event report (PDF): every booking for one student, with status and duration;
  * Smart Aviation event cancellation report (CSV): cancelled events with the reason, many students.

Run as  cpl_records.py <job dir>  (same contract as cpl_scan.py): reads job.json/context.json and the upload,
writes result.json with proposed changes and exits 0; exits 10 when the file is none of these.
"""
import csv
import io
import json
import os
import re
import sys
from datetime import date, datetime

MONTHS = {m: i for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}
DATE = r"\d{1,2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \d{4}"
CODE = r"[A-Z][A-Z0-9]{1,7}-[A-Z]{3}\d{2}"


def iso(text):
    """'02 June 2026' / '28 Sept 2026' -> '2026-06-02'."""
    d, m, y = text.split()
    return f"{int(y):04d}-{MONTHS[m[:3].lower()]:02d}-{int(d):02d}"


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", str(s or "").lower()).strip()


def joined(lines):
    """Table cells wrap across lines in the PDF text; join them, keeping 'CFPA-' + 'JUN26' together."""
    out = ""
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if out.endswith("-") and not out.endswith(" -"):
            out += line
        else:
            out += (" " if out else "") + line
    return out


# ---------- Aviation 1 in 60 ----------

def one_in_60(text):
    lines = [l.strip() for l in text.split("\n")]
    if not any("Aviation 1 in 60" in l for l in lines[:12]) or "ATTENDANCE REPORT" not in lines[0]:
        return None
    r = {"source": "Aviation 1 in 60 - Student Support", "name": lines[1]}
    m = re.match(r"ARN\s*(\d+)\s*(?:·\s*(\S+@\S+))?", lines[2])
    if m:
        r["arn"], r["email"] = m.group(1), m.group(2) or ""
    m = re.match(rf"({DATE})\s*[–-]\s*({DATE})", lines[3])
    if not m:
        return None
    r["from"], r["to"] = iso(m.group(1)), iso(m.group(2))
    m = re.match(rf"Generated ({DATE}), (\d\d:\d\d)", lines[4])
    r["generated"] = iso(m.group(1)) + "T" + m.group(2) if m else r["to"] + "T00:00"
    head = joined(lines[5:60])
    summ = {}
    for key, rx in [("rate", r"(\d+)%\s*ATTENDANCE RATE"), ("present", r"(\d+)/\d+ sessions"), ("sessions", r"\d+/(\d+) sessions"),
                    ("hours", r"([\d.]+)h\s*HOURS IN SCHOOL"), ("days", r"HOURS IN SCHOOL\s*(\d+) days"),
                    ("weeksMet", r"(\d+)/\d+\s*WEEKS MET"), ("weeksTotal", r"\d+/(\d+)\s*WEEKS MET"),
                    ("attendedH", r"WEEKS MET\s*([\d.]+)h of"), ("requiredH", r"WEEKS MET\s*[\d.]+h of ([\d.]+)h"),
                    ("absNoCert", r"(\d+)\s*ABSENCES\s*WITHOUT CERT"), ("absent", r"WITHOUT CERT\.?\s*(\d+) absent"),
                    ("notMarked", r"(\d+) not\s*marked")]:
        m = re.search(rx, head)
        if m:
            summ[key] = float(m.group(1)) if "." in m.group(1) else int(m.group(1))
    r["summary"] = summ
    m = re.search(r"Enrolled:\s*(.*?)(?:Attendance counts|Weekly hours|$)", head)
    r["enrolments"] = [{"code": c, "from": f} for c, f in re.findall(rf"({CODE}) \(from (\d{{4}}-\d\d-\d\d)\)", m.group(1) if m else "")]

    # Split into sections by their headings: "Days on campus(35)".
    heads = {"Weekly hours against requirement": "weeks", "Days on campus": "days", "Course sessions": "sessions",
             "Missed sessions with no reason given": "missed", "Requirement adjustments": "adjustments"}
    sections, cur = {}, None
    for line in lines:
        hm = re.match(r"(.+?)\s*\((\d+)\)$", line)
        if hm and hm.group(1) in heads:
            cur = heads[hm.group(1)]
            sections[cur] = []
            continue
        if line.startswith("Aviation 1 in 60 · Student Support · confidential"):
            cur = None
        if cur:
            sections[cur].append(line)
    body = {k: joined(v) for k, v in sections.items()}

    r["weeks"] = [{"week": iso(d), "attended": float(a), "required": float(q), "status": s.strip()}
                  for d, a, q, s in re.findall(rf"({DATE}) ([\d.]+) h ([\d.]+) h (.+?)(?= {DATE} [\d.]+ h|$)", body.get("weeks", ""))]
    r["days"] = []
    for d, tin, tout, h in re.findall(rf"({DATE}) (\d\d:\d\d|no sign-in) (\d\d:\d\d|no sign-out) ([\d.]+) h", body.get("days", "")):
        r["days"].append({"date": iso(d), "in": "" if tin.startswith("no") else tin, "out": "" if tout.startswith("no") else tout, "hours": float(h)})
    status = r"attended|present|late|absent no notify|absent notified|absent|excused|sick|medical|not marked|unmarked|pending"
    cert = r"verified|missing|pending|uploaded|rejected|not required|—|-"
    r["sessions"] = [{"date": iso(d), "course": c, "session": s.strip(), "status": st, "cert": "" if ce in "—-" else ce}
                     for d, c, s, st, ce in re.findall(rf"({DATE}) ({CODE}) (.+?) ({status}) ({cert})(?= {DATE}|\s*$)", body.get("sessions", ""))]
    r["missed"] = []
    for d, c, s, reason, days, rev in re.findall(rf"({DATE}) ({CODE}) (.+?) (no reason|[a-z ]+?) · (\d+)d past due (—|✓.*?)(?= {DATE}|\s*$)", body.get("missed", "")):
        r["missed"].append({"date": iso(d), "course": c, "session": s.strip(), "reason": reason, "pastDue": int(days),
                            "reviewed": rev.startswith("✓"), "reviewer": rev.lstrip("✓ ").strip() if rev.startswith("✓") else ""})
    r["adjustments"] = []
    adj = body.get("adjustments", "").replace("TYPE FROM TO DETAIL REASON", "").strip()
    for t, f, to, rest in re.findall(rf"(Pause|Reduction|Increase|Exemption|Adjustment|Leave) ({DATE}) ({DATE}|ongoing) (.*?)(?= (?:Pause|Reduction|Increase|Exemption|Adjustment|Leave) {DATE}|$)", adj):
        parts = rest.rsplit(" ", 1)
        reason = parts[1] if len(parts) == 2 and parts[1] in ("—", "-") else ""
        detail = parts[0] if reason else rest
        r["adjustments"].append({"type": t, "from": iso(f), "to": "" if to == "ongoing" else iso(to), "detail": detail.strip(), "reason": "" if reason else ""})
    r["ranges"] = [[r["from"], r["to"]]]
    counts = {"days": len(r["days"]), "sessions": len(r["sessions"]), "weeks": len(r["weeks"]), "missed": len(r["missed"])}
    declared = {heads[h]: int(n) for h, n in ((m.group(1), m.group(2)) for m in (re.match(r"(.+?)\s*\((\d+)\)$", l) for l in lines) if m) if h in heads}
    r["_check"] = [f"{k}: read {counts.get(k, len(r.get(k, [])))} of {n}" for k, n in declared.items() if counts.get(k, len(r.get(k, []))) != n]
    return r


# ---------- Smart event report ----------

EVENT_TYPES = ["GROUND SCHOOL", "LONG BRIEFING", "PRE-FLIGHT BRIEFING", "FLIGHT BOOKING DUAL", "FLIGHT BOOKING SOLO", "FLIGHT BOOKING",
               "POST-FLIGHT ADMINISTRATION & DE-BRIEFING / SIGN IN", "POST-FLIGHT ADMINISTRATION & DE-BRIEFING / SIGN", "STUDENT PROGRESS MEETING",
               "SIMULATOR", "SIM BOOKING", "EXAM", "THEORY EXAM", "FLIGHT TEST", "PROGRESS MEETING", "MEETING"]
STATUSES = ["COMPLETED", "SCHEDULED", "CANCELLED", "IN PROGRESS", "NO SHOW", "CONFIRMED"]


REASONS = ["Student cancelled", "Student sick", "Student late", "Student unprepared", "No Show", "Weather unsuitable", "Instructor sick",
           "Instructor unavailable", "Aeroplane in unscheduled maintenance", "Aeroplane in scheduled maintenance", "Rescheduled", "Created in error", "Other"]


def full_reason(t):
    """The PDF cuts the cancellation type short ("Student cancelle"); give back the whole name."""
    t = t.strip()
    hits = [r for r in REASONS if r.lower().startswith(t.lower())]
    return hits[0] if len(hits) == 1 else t


def smart_events(text):
    if "Event Report" not in text[:200] or "smartaviation" not in text:
        return None
    lines = []
    for line in text.split("\n"):
        s = line.strip()
        if not s or s == "TIME DETAILS STATUS" or "Reporting - Moorabbin Aviation Services - Smart" in s or s.startswith("https://mas.smartaviation") or s.startswith("©"):
            continue
        lines.append(s)
    m = re.search(r"From (\d\d-\d\d-\d{4}) .*? until (\d\d-\d\d-\d{4})", text)
    dmy = lambda t: f"{t[6:10]}-{t[3:5]}-{t[0:2]}"
    rng = [dmy(m.group(1)), dmy(m.group(2))] if m else ["", ""]
    # An event starts at "Start" (sometimes "Start LONG BRIEFING COMPLETED" when a page break splits it).
    starts = [i for i, l in enumerate(lines) if l == "Start" or l.startswith("Start ")]
    events, others = [], {}
    for n, i in enumerate(starts):
        block = lines[i:starts[n + 1] if n + 1 < len(starts) else len(lines)]
        flat = " ".join(block)
        dts = re.findall(r"(\d\d-\d\d-\d{4}) (\d\d:\d\d)", flat)
        if not dts:
            continue
        typ = next((t for t in EVENT_TYPES if t in flat), "")
        st = next((s for s in STATUSES if re.search(r"\b" + s + r"\b", flat)), "")
        # Title: the lines after the type line, up to "Summary".
        title = ""
        for k, l in enumerate(block):
            if typ and (l == typ or l.endswith(typ) or (typ.startswith(l) and len(l) > 10)):
                rest = []
                for x in block[k + 1:]:
                    if x in ("Summary", "IN") or re.fullmatch(r"\d\d-\d\d-\d{4} \d\d:\d\d", x) or x == "End":
                        if x == "IN":
                            continue
                        break
                    if x in STATUSES or x == "MOORABBIN":
                        continue
                    rest.append(x)
                title = " ".join(rest)
                break
        if not title:
            # Page break: "Start LONG BRIEFING COMPLETED" then the dates, then the title lines before "Summary".
            last = max((k for k, l in enumerate(block) if re.fullmatch(r"\d\d-\d\d-\d{4} \d\d:\d\d", l)), default=0)
            rest = []
            for x in block[last + 1:]:
                if x == "Summary":
                    break
                if x not in STATUSES and x != "MOORABBIN" and x not in EVENT_TYPES:
                    rest.append(x)
            title = " ".join(rest)
        dur = re.search(r"Actual Duration (\d\d):(\d\d):\d\d", flat)
        logb = re.search(r"Logbook Time (\d+(?:\.\d+)?)", flat)
        ctype = re.search(r"Cancellation Typ\S* (.+?)(?: Cancellation Rea|$)", flat)
        for p in re.findall(r"Other Pilot (.+?)(?: Lesson| Course|$)", flat):
            others[p.strip()] = others.get(p.strip(), 0) + 1
        events.append({"start": dmy(dts[0][0]) + "T" + dts[0][1], "end": dmy(dts[1][0]) + "T" + dts[1][1] if len(dts) > 1 else "",
                       "type": typ.title().replace("/ Sign", "/ Sign").replace("& De-Briefing", "& De-briefing"), "title": title,
                       "status": st.title(), "hours": round(int(dur.group(1)) + int(dur.group(2)) / 60, 2) if dur else 0,
                       "logbook": float(logb.group(1)) if logb else 0, "cancel": full_reason(ctype.group(1)) if ctype else ""})
    if not events:
        return None
    who = max(others, key=others.get) if others else ""
    return {"source": "Smart Aviation event report", "range": rng, "events": events, "name": who}


# ---------- Smart cancellation report (PDF printout of the report page) ----------

def cancellations_pdf(text):
    """The printed page lists only the first results (e.g. 50 of 233), but its Statistics block covers them all."""
    if "Event Cancellation Report" not in text[:300] or "TOTAL EVENT CANCELLATIONS" not in text:
        return None
    lines = [l.strip() for l in text.split("\n") if l.strip()]
    m = re.search(r"From (\d\d-\d\d-\d{4}) .*? until (\d\d-\d\d-\d{4})", text)
    dmy = lambda t: f"{t[6:10]}-{t[3:5]}-{t[0:2]}"
    rng = [dmy(m.group(1)), dmy(m.group(2))] if m else ["", ""]
    num = lambda label: int(re.search(label + r"\s*\n\s*(\d+)", text).group(1)) if re.search(label + r"\s*\n\s*(\d+)", text) else 0

    def block(head, ends):
        try:
            i = lines.index(head)
        except ValueError:
            return {}
        out, buf = {}, ""
        for l in lines[i + 1:]:
            if l in ends:
                break
            buf = (buf + ("" if buf.endswith("-") and not buf.endswith(" -") else " ") + l).strip() if buf else l
            mm = re.fullmatch(r"(.+?) - (\d+)", buf)
            if mm:
                out[mm.group(1).strip()] = int(mm.group(2))
                buf = ""
        return out
    heads = ["Top 15 People Involved", "Top 15 Aircraft Involved", "Event Types", "Cancellation Reasons", "Top 15 Cancellers", "TIME DETAILS RESOURCESREASON CANCELLED"]
    people = block("Top 15 People Involved", heads)
    total = num("TOTAL EVENT CANCELLATIONS")
    who = next((n for n, c in people.items() if c == total), "") if total else ""
    summary = {"total": total, "flight": num("FLIGHT EVENTS CANCELLED"), "nonflight": num("NON-FLIGHT EVENTS CANCELLED"),
               "types": block("Event Types", heads), "reasons": {full_reason(k): v for k, v in block("Cancellation Reasons", heads).items()}}
    # The detailed rows that were printed.
    events = []
    starts = [i for i, l in enumerate(lines) if l == "Start" and i + 1 < len(lines) and re.fullmatch(r"\d\d-\d\d-\d{4} \d\d:\d\d", lines[i + 1])]
    page = re.compile(r"^(\d\d?/\d\d?/\d\d, .*Smart|https://mas\.smartaviation|TIME DETAILS RESOURCES|Showing\s*\d|© \d{4} Smart|[\d ]+$|\d+ (?:year|month|day|week|hour|minute)s? ago$)")
    for n, i in enumerate(starts):
        blk = [l for l in lines[i:starts[n + 1] if n + 1 < len(starts) else len(lines)] if not page.match(l)]
        flat = " ".join(blk)
        typ = next((t for t in EVENT_TYPES if t in re.sub(r"\s+", " ", flat.upper())), "")
        ct = re.search(r"Cancellation Type (.+?) Cancellation Reason", flat)
        # Who cancelled: the line before "2025-09-17 14:52 AEST"; the reason is between "Cancellation Reason" and that name.
        when = next((k for k, l in enumerate(blk) if re.match(r"\d{4}-\d\d-\d\d \d\d:\d\d A[EC]", l)), -1)
        byname = blk[when - 1] if when > 0 else ""
        cr = blk.index("Cancellation Reason") if "Cancellation Reason" in blk else -1
        reason = " ".join(blk[cr + 1:when - 1]) if 0 <= cr < when - 1 else ""
        if reason in ("--",) or len(reason) > 300:
            reason = ""
        rs = type("R", (), {"group": lambda self, i: reason})()
        by = type("B", (), {"group": lambda self, i: byname})() if byname else None
        d = blk[1]
        events.append({"start": f"{d[6:10]}-{d[3:5]}-{d[0:2]}T{d[11:16]}", "type": typ.title().replace("& De-Briefing", "& De-briefing"), "name": "",
                       "ctype": full_reason(ct.group(1)) if ct else "", "reason": (rs.group(1).strip() if rs and rs.group(1).strip() != "--" else "")[:300],
                       "by": by.group(1) if by else "", "instructor": ""})
    shown = re.search(r"Showing\s*(\d+)\s*to\s*(\d+)\s*of\s*(\d+)\s*results", text)
    return {"source": "Smart Aviation event cancellation report", "range": rng, "name": who, "summary": summary, "events": events,
            "shown": [int(shown.group(2)), int(shown.group(3))] if shown else [len(events), total]}


# ---------- Smart cancellation report ----------

def cancellations(raw):
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode("latin-1")
    rows = list(csv.DictReader(io.StringIO(text)))
    if not rows or not {"event start local", "event type", "cancellation type", "Students"} <= set(rows[0].keys()):
        return None
    by = {}

    def local(t):
        m = re.match(r"(\d{1,2})/(\d{1,2})/(\d{4}) (\d{1,2}):(\d\d)", t or "")
        return f"{m.group(3)}-{int(m.group(2)):02d}-{int(m.group(1)):02d}T{int(m.group(4)):02d}:{m.group(5)}" if m else ""
    dates = []
    for row in rows:
        start = local(row.get("event start local"))
        if not start:
            continue
        dates.append(start[:10])
        ev = {"start": start, "type": (row.get("event type") or "").strip(), "name": (row.get("event name") or "").strip(),
              "ctype": (row.get("cancellation type") or "").strip(), "reason": (row.get("cancellation reason") or "").strip()[:300],
              "by": (row.get("cancelled by") or "").strip(), "instructor": (row.get("Instructors") or "").strip()}
        for s in re.split(r"\s*[,;]\s*", row.get("Students") or ""):
            if s.strip():
                by.setdefault(s.strip(), []).append(ev)
    if not by:
        return None
    return {"source": "Smart Aviation event cancellation report", "range": [min(dates), max(dates)], "students": by}


# ---------- proposed changes ----------

def match(roster, name, arn=""):
    if arn:
        for r in roster:
            if re.sub(r"\D", "", r.get("studentId") or "") == arn:
                return r["ref"]
    n = norm(name)
    hits = [r["ref"] for r in roster if norm(r["name"]) == n]
    if len(hits) == 1:
        return hits[0]
    # "Becky Hol" vs "Becky HOL (CPL)": compare the first and last words.
    w = n.split()
    if len(w) >= 2:
        hits = [r["ref"] for r in roster if (lambda x: len(x) >= 2 and x[0] == w[0] and x[-1] == w[-1])(norm(re.sub(r"\(.*?\)", "", r["name"])).split())]
        if len(hits) == 1:
            return hits[0]
    return ""


def fmt(d):
    return datetime.strptime(d, "%Y-%m-%d").strftime("%d %b %Y") if d else ""


def changes_for(path, mime, name, roster):
    raw = open(path, "rb").read()
    if mime == "application/pdf" or raw[:4] == b"%PDF":
        try:
            from pypdf import PdfReader
        except ImportError:
            return None
        try:
            text = "\n".join((p.extract_text() or "") for p in PdfReader(io.BytesIO(raw)).pages)
        except Exception:
            return None
        rec = one_in_60(text)
        if rec:
            check = rec.pop("_check")
            ref = match(roster, rec["name"], rec.get("arn", ""))
            value = f"{len(rec['days'])} days on campus, {len(rec['sessions'])} course sessions, {len(rec['weeks'])} weeks, {fmt(rec['from'])} to {fmt(rec['to'])}"
            ch = [{"student": ref, "name_in_document": rec["name"], "kind": "records", "item": "oneIn60", "value": value, "data": rec,
                   "confidence": "high", "reason": f"Aviation 1 in 60 attendance report generated {rec['generated'].replace('T', ' ')}."}]
            if rec.get("arn"):
                st = next((r for r in roster if r["ref"] == ref), None)
                if st is None or not st.get("studentId"):
                    ch.append({"student": ref, "name_in_document": rec["name"], "kind": "detail", "item": "studentId", "value": rec["arn"],
                               "confidence": "high", "reason": "ARN on the Aviation 1 in 60 report."})
            return {"summary": f"Aviation 1 in 60 attendance report for {rec['name']}: {value}.", "document_type": "Aviation 1 in 60 attendance report (read without Claude)",
                    "warnings": ["Some rows could not be read: " + "; ".join(check) + ". Check the file."] if check else [], "unmatched": [], "changes": ch}
        cp = cancellations_pdf(text)
        if cp:
            ref = match(roster, cp["name"]) if cp["name"] else ""
            sm = cp["summary"]
            value = f"{sm['total']} cancelled events ({sm['flight']} flight, {sm['nonflight']} other), {fmt(cp['range'][0])} to {fmt(cp['range'][1])}"
            warn = [] if cp["name"] else ["The report does not say which student it is for. Choose who it belongs to."]
            if cp["shown"][0] < cp["shown"][1]:
                warn.append(f"The printout lists {cp['shown'][0]} of {cp['shown'][1]} events in detail; the totals by reason and type cover all {cp['shown'][1]}. "
                            "For every event in detail, export the report as CSV from Smart instead.")
            return {"summary": f"Smart cancellation report{' for ' + cp['name'] if cp['name'] else ''}: {value}.", "document_type": "Smart Aviation cancellation report, PDF (read without Claude)",
                    "warnings": warn, "unmatched": [],
                    "changes": [{"student": ref, "name_in_document": cp["name"] or "Student on this report", "kind": "records", "item": "cancellations", "value": value,
                                 "data": {"source": cp["source"], "range": cp["range"], "events": cp["events"], "summary": dict(sm, range=cp["range"])},
                                 "confidence": "high" if ref else "medium", "reason": "Statistics from the Smart event cancellation report."}]}
        ev = smart_events(text)
        if ev:
            n = len(ev["events"])
            first, last = min(e["start"] for e in ev["events"])[:10], max(e["start"] for e in ev["events"])[:10]
            ref = match(roster, ev["name"]) if ev["name"] else ""
            value = f"{n} events, {fmt(first)} to {fmt(last)}"
            warn = [] if ev["name"] else ["The report does not name the student. Choose who it belongs to."]
            return {"summary": f"Smart event report{' for ' + ev['name'] if ev['name'] else ''}: {value}.", "document_type": "Smart Aviation event report (read without Claude)",
                    "warnings": warn, "unmatched": [],
                    "changes": [{"student": ref, "name_in_document": ev["name"] or "Student on this report", "kind": "records", "item": "events", "value": value,
                                 "data": {"source": ev["source"], "range": ev["range"], "events": ev["events"]}, "confidence": "high" if ref else "medium",
                                 "reason": f"Event report run for {fmt(ev['range'][0])} to {fmt(ev['range'][1])}."}]}
        return None
    if mime in ("text/csv", "text/plain") or name.lower().endswith(".csv"):
        c = cancellations(raw)
        if not c:
            return None
        ch = []
        for who, evs in sorted(c["students"].items()):
            ch.append({"student": match(roster, who), "name_in_document": who, "kind": "records", "item": "cancellations",
                       "value": f"{len(evs)} cancelled event{'s' if len(evs) != 1 else ''}", "data": {"source": c["source"], "range": c["range"], "events": evs},
                       "confidence": "high", "reason": f"Cancellation report for {fmt(c['range'][0])} to {fmt(c['range'][1])}."})
        return {"summary": f"Smart cancellation report: {sum(len(v) for v in c['students'].values())} cancelled events for {len(c['students'])} student(s), kept with their reasons for the attendance report.",
                "document_type": "Smart Aviation cancellation report (read without Claude)", "warnings": [], "unmatched": [], "changes": ch}
    return None


def main():
    job = sys.argv[1]
    meta = json.load(open(os.path.join(job, "job.json")))
    ctx = json.load(open(os.path.join(job, "context.json")))
    result = changes_for(os.path.join(job, meta["file"]), meta["mime"], meta["name"], ctx["roster"])
    if not result:
        sys.exit(10)
    result["usage"] = {"model": "rules", "input": 0, "output": 0}
    with open(os.path.join(job, "result.json.tmp"), "w") as f:
        json.dump(result, f)
    os.replace(os.path.join(job, "result.json.tmp"), os.path.join(job, "result.json"))


# ---------- merging into a student's record (used by cpl_server.py, stdlib only) ----------

def merge_records(old, item, new):
    """Returns the merged value for st['records'][item]; newer information wins, nothing already held is lost."""
    old = old or {}
    if item == "oneIn60":
        newer = (new.get("generated") or "") >= (old.get("generated") or "")
        base, top = (old, new) if newer else (new, old)
        out = dict(base)
        out.update({k: v for k, v in top.items() if k not in ("days", "weeks", "sessions", "missed", "ranges", "adjustments")})
        for key, idf in (("days", lambda x: x["date"]), ("weeks", lambda x: x["week"]),
                         ("sessions", lambda x: (x["date"], x["course"], x["session"])), ("missed", lambda x: (x["date"], x["course"], x["session"]))):
            m = {idf(x): x for x in base.get(key, [])}
            m.update({idf(x): x for x in top.get(key, [])})
            # A missed session the newer record no longer lists has been resolved; drop it.
            if key == "missed" and top.get("generated"):
                tf = min((r[0] for r in top.get("ranges", [])), default="")
                m = {k: v for k, v in m.items() if v in top.get(key, []) or v["date"] < tf}
            out[key] = sorted(m.values(), key=lambda x: str(idf(x)))
        out["adjustments"] = top.get("adjustments", base.get("adjustments", []))
        rs = sorted([list(r) for r in old.get("ranges", []) + new.get("ranges", [])])
        merged = []
        for a, b in rs:
            if merged and a <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], b)
            else:
                merged.append([a, b])
        out["ranges"] = merged
        out["from"], out["to"] = merged[0][0], merged[-1][1]
        return out
    if item in ("events", "cancellations"):
        key = (lambda e: (e["start"], e["type"], e.get("title", e.get("name", ""))))
        m = {key(e): e for e in old.get("events", [])}
        m.update({key(e): e for e in new.get("events", [])})
        rs = [r for r in (old.get("range"), new.get("range")) if r and r[0]]
        out = {"source": new.get("source") or old.get("source"), "range": [min(r[0] for r in rs), max(r[1] for r in rs)] if rs else ["", ""],
               "events": sorted(m.values(), key=lambda e: e["start"])}
        sums = [x["summary"] for x in (old, new) if x.get("summary")]
        if sums:  # totals from Smart's own statistics; the widest (then newest) report wins
            out["summary"] = max(sums, key=lambda x: (x.get("total", 0), (x.get("range") or ["", ""])[1]))
        return out
    return new


if __name__ == "__main__":
    main()
