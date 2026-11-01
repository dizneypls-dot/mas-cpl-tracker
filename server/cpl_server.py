#!/usr/bin/env python3
"""Shared student database for the MAS CPL Progress Tracker.

Standard-library only (Python 3.10+). Listens on 127.0.0.1 by default; put Caddy or another
HTTPS proxy in front of it. Every request needs an access code (one per person), made with:

    python3 cpl_server.py add-user "Jane Smith" --role admin      # prints the code once
    python3 cpl_server.py list-users
    python3 cpl_server.py remove-user "Jane Smith"
    python3 cpl_server.py serve --port 8787

API (JSON, header  Authorization: Bearer <code>):
    GET    /api/health                      -> {"ok": true}            (no code needed)
    GET    /api/me                          -> {"name", "role"}
    GET    /api/students?since=REV&wait=25  -> {"rev", "students": [...changed since REV]}
                                               waits up to `wait` seconds for a change (live updates)
    PUT    /api/students/<uid>              body {"state", "baseRev"}
                                            -> {"rev"}  or 409 {"student": current} when someone
                                               else saved first (the app merges and retries)
    DELETE /api/students/<uid>              (admin)
    GET    /api/audit?limit=200             (admin) who changed what, newest first
    GET    /api/users                       (admin) people who can sign in
    POST   /api/users                       (admin) body {"name", "role"} -> {"code"} shown once
    POST   /api/users/<id>/code             (admin) a new code for that person; the old one stops working
    DELETE /api/users/<id>                  (admin)
    POST   /api/scan                        body {"name", "mime", "data" (base64)} -> {"id"}
                                               Claude reads the file and proposes record changes
    GET    /api/scans                       recent scans (yours; an admin sees everyone's)
    GET    /api/scan/<id>                   status and the proposed changes
    POST   /api/scan/<id>/apply             body {"accept": [index...], "assign": {"<index>": uid | "new"}}
                                               saves only the approved changes
    POST   /api/scan/<id>/discard

The Claude key lives in DATA/claude.key (set with:  cpl_server.py set-claude-key  and paste it).
"""
import argparse
import base64
import copy
import hashlib
import json
import os
import re
import secrets
import signal
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cpl_records import merge_records  # noqa: E402  (stdlib only; the PDF reading itself runs in the venv)

DATA = os.environ.get("CPL_DATA", "/var/lib/cpl-tracker")
MAX_BODY = 2 * 1024 * 1024
MAX_UPLOAD = 15 * 1024 * 1024
HERE = os.path.dirname(os.path.abspath(__file__))
SCAN_PY = os.environ.get("CPL_SCAN_PYTHON", os.path.join(HERE, "venv", "bin", "python"))
SCAN_TYPES = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif", "application/pdf": ".pdf",
              "text/csv": ".csv", "text/plain": ".txt", "text/tab-separated-values": ".tsv", "application/json": ".json",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx"}
EXAM_NAMES = {"FROL": "Flight Radio Operator Licence", "Pre-Solo": "Pre-solo exam", "Pre-Area Solo": "Pre-area solo exam",
              "RPCT": "RPL conversion theory", "RPLA": "RPL (Aeroplane) theory exam", "CMET": "CPL Meteorology",
              "CFPA": "CPL Flight Planning", "CADA": "CPL Aerodynamics", "CNAV": "CPL Navigation", "CLWA": "CPL Air Law",
              "CHUF": "CPL Human Factors", "CSYA": "CPL Aircraft Systems"}
ATT_VALUES = ("Present", "Late", "Absent", "Sick (certificate)")
DETAIL_FIELDS = {"studentId": "Student ID", "instructor": "Instructor", "course": "Course", "start": "Course start"}
scan_slots = threading.Semaphore(2)
UID = re.compile(r"^[A-Za-z0-9-]{8,64}$")

lock = threading.Lock()
changed = threading.Condition(lock)
db = {"rev": 0, "students": {}}
failures = {}


dirty = threading.Event()


def mark_dirty():
    dirty.set()


def flusher():
    """Writes the database at most every 2 seconds, so a burst of saves is one disk write."""
    while True:
        dirty.wait()
        time.sleep(2)
        with lock:
            dirty.clear()
            snapshot = json.dumps(db, separators=(",", ":"))
        tmp = path("db.json.tmp")
        with open(tmp, "w") as f:
            f.write(snapshot)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path("db.json"))


def path(name):
    return os.path.join(DATA, name)


def write_json(name, value):
    tmp = path(name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(value, f, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path(name))


def read_json(name, default):
    try:
        with open(path(name)) as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def canon_code(code):
    """Codes are four groups of four hex digits. Accept them however they are typed: lower case, spaces,
    no dashes, or O/I/L typed for 0/1 (none of those letters can be in a code)."""
    c = re.sub(r"[^0-9A-Za-z]", "", str(code or "")).upper().translate(str.maketrans("OIL", "011"))
    if re.fullmatch(r"[0-9A-F]{16}", c):
        return "-".join(c[i:i + 4] for i in range(0, 16, 4))
    return str(code or "").strip()


def code_hash(code):
    return hashlib.sha256(canon_code(code).encode()).hexdigest()


def users():
    return read_json("users.json", {})


users_lock = threading.Lock()


def new_code():
    return "-".join(secrets.token_hex(2).upper() for _ in range(4))


def save_users(us):
    write_json("users.json", us)
    os.chmod(path("users.json"), 0o600)


def user_list(me):
    """People who can sign in (admin only). The id is the start of the code's hash; codes themselves are never stored."""
    return sorted(({"id": v.get("id") or k[:12], "name": v["name"], "role": v["role"], "created": v.get("created", 0), "by": v.get("by", ""),
                    "you": v["name"] == me["name"] and v["role"] == me["role"]} for k, v in users().items()), key=lambda x: x["name"].lower())


def add_user(me, name, role):
    name = re.sub(r"\s+", " ", str(name or "")).strip()[:60]
    if not name:
        raise ValueError("Enter the person's name.")
    if role not in ("admin", "instructor"):
        raise ValueError("Choose Administrator or Instructor.")
    with users_lock:
        us = users()
        if any(v["name"].lower() == name.lower() for v in us.values()):
            raise ValueError(f"{name} already has a code. Give them a new code instead, or use a different name.")
        code = new_code()
        us[code_hash(code)] = {"id": secrets.token_hex(6), "name": name, "role": role, "created": int(time.time() * 1000), "by": me["name"]}
        save_users(us)
    audit(me, "user-add", "", "", f"{name} ({role})")
    return code


def find_user(us, uid):
    hits = [k for k, v in us.items() if (v.get("id") or k[:12]) == uid]
    return hits[0] if len(hits) == 1 else None


def remove_user(me, uid):
    with users_lock:
        us = users()
        k = find_user(us, uid)
        if not k:
            raise LookupError("That person is no longer on the list.")
        v = us[k]
        if v["name"] == me["name"] and v["role"] == me["role"]:
            raise ValueError("You can't remove your own access.")
        if v["role"] == "admin" and sum(1 for x in us.values() if x["role"] == "admin") <= 1:
            raise ValueError("There must always be at least one administrator.")
        del us[k]
        save_users(us)
    audit(me, "user-remove", "", "", f"{v['name']} ({v['role']})")


def reset_user(me, uid):
    """A new code for someone who lost theirs; the old one stops working at once."""
    with users_lock:
        us = users()
        k = find_user(us, uid)
        if not k:
            raise LookupError("That person is no longer on the list.")
        v = us.pop(k)
        code = new_code()
        us[code_hash(code)] = dict(v, id=v.get("id") or k[:12], reset=int(time.time() * 1000))
        save_users(us)
    audit(me, "user-newcode", "", "", f"{v['name']} ({v['role']})")
    return code, v


def audit(user, action, uid, name, detail=""):
    line = json.dumps({"t": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "user": user["name"],
                       "action": action, "uid": uid, "student": name, "detail": detail})
    with open(path("audit.log"), "a") as f:
        f.write(line + "\n")


def describe(old, new):
    """Short summary of what changed, for the audit log."""
    if not old:
        return "created"
    parts = []
    for key in ("lessons", "exams"):
        before = {str(v.get("id")): v.get("status") for v in old.get(key, []) if isinstance(v, dict)}
        for v in new.get(key, []):
            if isinstance(v, dict) and before.get(str(v.get("id"))) != v.get("status"):
                parts.append(f"{v.get('id')}: {before.get(str(v.get('id')))} -> {v.get('status')}")
    other = [k for k in new if k not in ("lessons", "exams") and old.get(k) != new.get(k)]
    if other:
        parts.append("edited " + ", ".join(sorted(other)))
    return "; ".join(parts)[:600] or "no visible change"


# ---- Claude document scans -------------------------------------------------------------------------------
# An upload is read by Claude in a separate process (cpl_scan.py, in its own virtual environment). Claude only
# proposes changes; they are saved when a person approves them with /apply, under that person's name.

_catalogue = None


def catalogue():
    global _catalogue
    if _catalogue is None:
        _catalogue = read_json_abs(os.path.join(HERE, "catalogue.json"), {"order": [], "lessons": {}, "template": {}})
    return _catalogue


def read_json_abs(p, default):
    try:
        with open(p) as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return default


def scan_dir(sid):
    return path(os.path.join("scans", sid))


def load_job(sid):
    if not re.fullmatch(r"[a-f0-9]{24}", sid or ""):
        return None
    return read_json_abs(os.path.join(scan_dir(sid), "job.json"), None)


def save_job(job):
    d = scan_dir(job["id"])
    tmp = os.path.join(d, "job.json.tmp")
    with open(tmp, "w") as f:
        json.dump(job, f, separators=(",", ":"))
    os.replace(tmp, os.path.join(d, "job.json"))


def claude_key():
    try:
        with open(path("claude.key")) as f:
            return f.read().strip()
    except FileNotFoundError:
        return os.environ.get("ANTHROPIC_API_KEY", "")


def lesson_label(lid):
    v = catalogue()["lessons"].get(str(lid))
    return f"{lid}. {v['name']}" if v else f"Lesson {lid}"


def start_scan(u, name, mime, data):
    sid = secrets.token_hex(12)
    d = scan_dir(sid)
    os.makedirs(path("scans"), mode=0o700, exist_ok=True)
    os.makedirs(d, mode=0o700)
    fname = "upload" + SCAN_TYPES[mime]
    with open(os.path.join(d, fname), "wb") as f:
        f.write(data)
    with lock:
        live = [(k, r["state"]) for k, r in db["students"].items() if not r.get("deleted")]
    live.sort(key=lambda x: (x[1].get("name") or "").lower())
    refs, roster = {}, []
    for i, (uid, st) in enumerate(live, 1):
        ref = f"S{i}"
        refs[ref] = uid
        roster.append({"ref": ref, "name": st.get("name", ""), "studentId": st.get("studentId", ""), "course": st.get("course", ""), "instructor": st.get("instructor", "")})
    cat = catalogue()
    with open(os.path.join(d, "context.json"), "w") as f:
        json.dump({"roster": roster, "lessons": {k: v["name"] for k, v in cat["lessons"].items()}, "exams": EXAM_NAMES}, f)
    job = {"id": sid, "name": name[:200], "mime": mime, "file": fname, "size": len(data), "by": u["name"], "role": u["role"],
           "created": int(time.time() * 1000), "today": time.strftime("%Y-%m-%d"), "status": "reading", "refs": refs}
    save_job(job)
    audit(u, "scan", "", "", f"uploaded {job['name']} for Claude to read")
    threading.Thread(target=run_scan, args=(sid,), daemon=True, name="scan-" + sid).start()
    return sid


def run_scan(sid):
    job = load_job(sid)
    d = scan_dir(sid)
    with scan_slots:
        try:
            # Known attendance files (1 in 60 report, Smart event and cancellation reports) are read by fixed rules.
            p = subprocess.run([SCAN_PY, os.path.join(HERE, "cpl_records.py"), d], env={"PATH": "/usr/bin:/bin", "HOME": d},
                               capture_output=True, text=True, timeout=300)
            if p.returncode == 0:
                result = read_json_abs(os.path.join(d, "result.json"), None)
                if result:
                    job.update(status="ready", summary=result.get("summary", ""), documentType=result.get("document_type", ""),
                               warnings=result.get("warnings", []), unmatched=result.get("unmatched", []), usage=result.get("usage"), rules=True,
                               changes=[dict(c, i=i) for i, c in enumerate(result.get("changes", []))])
                    job["finished"] = int(time.time() * 1000)
                    save_job(job)
                    return
            elif p.returncode != 10:
                sys.stderr.write(f"scan {sid}: records reader failed: {(p.stderr or '')[-500:]}\n")
            key = claude_key()
            if not key:
                raise RuntimeError("Claude is not set up on the server yet (no key). Ask the admin to run cpl-set-claude-key.")
            env = {"PATH": "/usr/bin:/bin", "ANTHROPIC_API_KEY": key, "HOME": d}
            p = subprocess.run([SCAN_PY, os.path.join(HERE, "cpl_scan.py"), d], env=env, capture_output=True, text=True, timeout=1200)
            if p.returncode != 0:
                msg = (p.stderr or "").strip().splitlines()
                raise RuntimeError(msg[-1] if msg and p.returncode in (2, 3, 4, 5) else "Claude could not read this file.")
            result = read_json_abs(os.path.join(d, "result.json"), None)
            if not result:
                raise RuntimeError("Claude could not read this file.")
            if result.get("error"):
                raise RuntimeError(result["error"])
            job.update(status="ready", summary=result.get("summary", ""), documentType=result.get("document_type", ""),
                       warnings=result.get("warnings", []), unmatched=result.get("unmatched", []), usage=result.get("usage"),
                       changes=[dict(c, i=i) for i, c in enumerate(result.get("changes", []))])
        except subprocess.TimeoutExpired:
            job.update(status="failed", error="Reading the file took too long. Try a smaller file.")
        except Exception as e:  # shown to the person who uploaded
            job.update(status="failed", error=str(e)[:300] if isinstance(e, RuntimeError) else "Something went wrong reading the file.")
            if not isinstance(e, RuntimeError):
                sys.stderr.write(f"scan {sid}: {e!r}\n")
        job["finished"] = int(time.time() * 1000)
        save_job(job)


def find_exam(st, item):
    for e in st.get("exams", []):
        if str(e.get("id", "")).lower() == item.strip().lower():
            return e
    return None


def find_lesson(st, item):
    item = item.strip().lower().removeprefix("lesson").strip().rstrip(".")
    for v in st.get("lessons", []):
        if str(v.get("id")).lower() == item:
            return v
    return None


def check_change(st, c):
    """(label, before, after, problem) for one proposed change against a record (st may be None for a new student)."""
    kind, item, value = c.get("kind"), str(c.get("item", "")), str(c.get("value", ""))
    st = st or catalogue()["template"]
    if kind == "exam":
        e = find_exam(st, item)
        if not e:
            return item, "", value, f"There is no exam called {item} in the tracker."
        if value not in ("Passed", "Failed"):
            return e["id"], e.get("status", ""), value, "Exam result must be Passed or Failed."
        label = e["id"] + (" · " + EXAM_NAMES[e["id"]] if e["id"] in EXAM_NAMES else "")
        return label, e.get("status", ""), value, "already recorded" if e.get("status") == value else ""
    if kind == "lesson":
        v = find_lesson(st, item)
        if not v:
            return item, "", value, f"There is no lesson {item} in the syllabus."
        if value not in ("Completed", "In progress", "Not started"):
            return lesson_label(v["id"]), v.get("status", ""), value, "Lesson status must be Completed, In progress or Not started."
        if v.get("status") == "Completed" and value != "Completed":
            return lesson_label(v["id"]), v["status"], value, "The lesson is already Completed; it is never moved back."
        return lesson_label(v["id"]), v.get("status", ""), value, "already recorded" if v.get("status") == value else ""
    if kind == "attendance":
        if value not in ATT_VALUES:
            return item, "", value, "Attendance must be Present, Late, Absent or Sick (certificate)."
        date = c.get("date", "")
        dup = any(a.get("date") == date and a.get("session") == item for a in st.get("attendance", []))
        return "Attendance", item, value, "already recorded" if dup else ""
    if kind == "detail":
        if item not in DETAIL_FIELDS:
            return item, "", value, "Only student ID, instructor, course and start date can be changed this way."
        return DETAIL_FIELDS[item], str(st.get(item, "")), value, "already recorded" if str(st.get(item, "")) == value else ""
    if kind == "note":
        return "Progress note", "", value, "already recorded" if value and value in (st.get("review") or "") else ""
    if kind == "records":
        if item not in RECORD_KINDS or not isinstance(c.get("data"), dict):
            return item, "", value, "Unknown kind of record."
        have = (st.get("records") or {}).get(item)
        merged = merge_records(have, item, c["data"])
        return RECORD_KINDS[item], records_summary(item, have), value, "already recorded" if have and merged == have else ""
    return item, "", value, "Unknown kind of change."


RECORD_KINDS = {"oneIn60": "Attendance record · Aviation 1 in 60", "events": "Smart event history", "cancellations": "Cancellation history"}


def records_summary(item, r):
    if not r:
        return ""
    if item == "oneIn60":
        return f"{len(r.get('days', []))} days, {len(r.get('sessions', []))} sessions to {r.get('to', '')}"
    return f"{len(r.get('events', []))} events to {(r.get('range') or ['', ''])[1]}"


def apply_change(st, c, source):
    kind, item, value, date = c["kind"], str(c.get("item", "")), str(c.get("value", "")), str(c.get("date", "") or "")
    note = (str(c.get("note") or "").strip() + " · " if c.get("note") else "") + source

    def add_note(obj, key="notes"):
        obj[key] = (obj.get(key) + "\n" if obj.get(key) else "") + note
    if kind == "exam":
        e = find_exam(st, item)
        n = max(1, min(int(c.get("attempts") or 1), 9))
        if value == "Passed" and n > 1 and not e.get("attempts"):
            e["attempts"] = [{"result": "Failed", "date": ""} for _ in range(n - 1)]
        e.setdefault("attempts", []).append({"result": value, "date": date})
        e["status"] = value
        if date:
            e["examDate"] = date
        add_note(e)
    elif kind == "lesson":
        v = find_lesson(st, item)
        v["status"] = value
        add_note(v)
        if date:
            v["notes"] += f" ({date})"
    elif kind == "attendance":
        st.setdefault("attendance", []).append({"date": date, "session": item, "status": value, "hours": "", "notes": note})
    elif kind == "detail":
        st[item] = value
    elif kind == "note":
        st["review"] = (st.get("review") + "\n" if st.get("review") else "") + value + f" ({source})"
    elif kind == "records":
        recs = st.setdefault("records", {})
        recs[item] = merge_records(recs.get(item), item, c["data"])


def scan_view(job):
    """The job with each change checked against the records as they are now."""
    out = {k: v for k, v in job.items() if k not in ("refs", "file")}
    with lock:
        changes = []
        for c in job.get("changes", []):
            uid = job["refs"].get(c.get("student", ""))
            rec = db["students"].get(uid) if uid else None
            st = rec["state"] if rec and not rec.get("deleted") else None
            label, before, after, problem = check_change(st, c)
            if c.get("saved"):
                before, problem = c.get("before", before), ""
            changes.append(dict(c, uid=uid if st else "", studentName=st.get("name", "") if st else "", label=label,
                                before=before, after=after, problem=problem))
    out["changes"] = changes
    return out


def apply_scan(job, u, accept, assign):
    source = f"{'Read' if job.get('rules') else 'Read by Claude'} from {job['name']}, approved by {u['name']}"
    groups = {}
    for i in accept:
        if not isinstance(i, int) or not 0 <= i < len(job.get("changes", [])):
            continue
        c = job["changes"][i]
        target = assign.get(str(i)) or job["refs"].get(c.get("student", ""))
        if target == "new":
            target = "new:" + (c.get("name_in_document") or "").strip()
            if target == "new:":
                continue
        if target:
            groups.setdefault(target, []).append(c)
    applied, skipped = 0, []
    with changed:
        for target, cs in groups.items():
            if target.startswith("new:"):
                uid = str(uuid.uuid4())
                st = copy.deepcopy(catalogue()["template"])
                st.update(name=target[4:], date=time.strftime("%Y-%m-%d"))
                cur = None
            else:
                uid = target
                cur = db["students"].get(uid)
                if not cur or cur.get("deleted"):
                    skipped.append(f"{len(cs)} change(s): student no longer exists")
                    continue
                st = copy.deepcopy(cur["state"])
            n = 0
            for c in cs:
                _, c["before"], _, problem = check_change(st, c)
                if problem:
                    skipped.append(f"{st.get('name')}: {problem}")
                    continue
                apply_change(st, c, source)
                c["saved"] = True
                n += 1
            if not n:
                continue
            db["rev"] += 1
            db["students"][uid] = {"state": st, "rev": db["rev"], "updated": int(time.time() * 1000), "by": u["name"]}
            audit(u, "scan-apply", uid, st.get("name", ""), f"from {job['name']}: " + describe(cur and cur["state"], st))
            applied += n
        if applied:
            mark_dirty()
            changed.notify_all()
    job.update(status="applied", appliedBy=u["name"], applied=applied, skipped=skipped, finished=int(time.time() * 1000))
    drop_upload(job)
    save_job(job)
    return applied, skipped


def drop_upload(job):
    """The uploaded file is only kept until the scan is approved or discarded."""
    try:
        os.remove(os.path.join(scan_dir(job["id"]), job["file"]))
    except (FileNotFoundError, KeyError):
        pass


def list_scans(u):
    root = path("scans")
    out = []
    cutoff = (time.time() - 30 * 86400) * 1000
    for sid in sorted(os.listdir(root) if os.path.isdir(root) else [], reverse=True):
        job = load_job(sid)
        if not job:
            continue
        if job["created"] < cutoff:
            subprocess.run(["rm", "-rf", scan_dir(sid)])
            continue
        if u["role"] == "admin" or job["by"] == u["name"]:
            out.append({k: job.get(k) for k in ("id", "name", "status", "by", "created", "finished", "summary", "error", "applied")}
                       | {"count": len(job.get("changes", []))})
    out.sort(key=lambda j: j["created"], reverse=True)
    return out[:40]


class Handler(BaseHTTPRequestHandler):
    server_version = "CPLTracker/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def client_ip(self):
        return self.headers.get("X-Forwarded-For", self.client_address[0]).split(",")[0].strip()

    def send(self, status, body):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.cors()
        self.end_headers()
        self.wfile.write(data)

    def cors(self):
        # Access is by bearer code, never cookies, so any origin (the web app, the Android app) may call.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Max-Age", "86400")

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def user(self):
        ip = self.client_ip()
        count, until = failures.get(ip, (0, 0))
        if until > time.time():
            self.send(429, {"error": "Too many wrong codes. Try again in a few minutes."})
            return None
        auth = self.headers.get("Authorization", "")
        code = auth[7:] if auth.startswith("Bearer ") else ""
        u = users().get(code_hash(code)) if code else None
        if not u:
            count += 1
            failures[ip] = (count, time.time() + 300 if count >= 10 else 0)
            self.send(401, {"error": "Access code not recognised."})
            return None
        failures.pop(ip, None)
        return u

    def body(self, limit=MAX_BODY):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("Too large." if limit == MAX_BODY else "The file is too large (15 MB at most).")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        if url.path == "/api/health":
            return self.send(200, {"ok": True})
        u = self.user()
        if not u:
            return
        if url.path == "/api/me":
            return self.send(200, {"name": u["name"], "role": u["role"]})
        if url.path == "/api/students":
            since = int((q.get("since") or ["0"])[0] or 0)
            wait = min(max(float((q.get("wait") or ["0"])[0] or 0), 0), 30)
            deadline = time.time() + wait
            with changed:
                while db["rev"] <= since and time.time() < deadline:
                    changed.wait(deadline - time.time())
                out = [dict(s, uid=k) for k, s in db["students"].items() if s["rev"] > since]
                rev = db["rev"]
            return self.send(200, {"rev": rev, "students": out})
        if url.path == "/api/scans":
            return self.send(200, {"scans": list_scans(u)})
        if url.path == "/api/users":
            if u["role"] != "admin":
                return self.send(403, {"error": "Only an admin can see who has access."})
            return self.send(200, {"users": user_list(u)})
        m = re.fullmatch(r"/api/scan/([a-f0-9]{24})", url.path)
        if m:
            job = load_job(m.group(1))
            if not job or (u["role"] != "admin" and job["by"] != u["name"]):
                return self.send(404, {"error": "Not found"})
            return self.send(200, scan_view(job))
        if url.path == "/api/audit":
            if u["role"] != "admin":
                return self.send(403, {"error": "Only an admin can read the audit log."})
            limit = min(int((q.get("limit") or ["200"])[0]), 2000)
            try:
                with open(path("audit.log")) as f:
                    lines = f.readlines()[-limit:]
            except FileNotFoundError:
                lines = []
            return self.send(200, {"entries": [json.loads(x) for x in reversed(lines)]})
        self.send(404, {"error": "Not found"})

    def do_POST(self):
        """Problem reports from the apps (crash details), so they can be fixed without screenshots."""
        u = self.user()
        if not u:
            return
        p = urlparse(self.path).path
        if p == "/api/users" or re.fullmatch(r"/api/users/[0-9a-f]{12}/code", p):
            if u["role"] != "admin":
                return self.send(403, {"error": "Only an admin can give out access codes."})
            try:
                if p == "/api/users":
                    b = self.body()
                    code = add_user(u, b.get("name"), b.get("role", "instructor"))
                    return self.send(200, {"code": code, "users": user_list(u)})
                code, v = reset_user(u, p.split("/")[3])
                return self.send(200, {"code": code, "name": v["name"], "users": user_list(u)})
            except LookupError as e:
                return self.send(404, {"error": str(e)})
            except ValueError as e:
                return self.send(400, {"error": str(e)})
        if p == "/api/scan":
            try:
                b = self.body(limit=MAX_UPLOAD * 4 // 3 + 4096)
                mime = str(b.get("mime", "")).lower()
                name = str(b.get("name", "upload"))
                if mime not in SCAN_TYPES and name.lower().endswith(".xlsx"):
                    mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                if mime not in SCAN_TYPES and name.lower().endswith((".csv", ".txt", ".tsv")):
                    mime = "text/csv"
                if mime not in SCAN_TYPES:
                    raise ValueError("Claude can read photos, screenshots, PDFs, Excel (.xlsx) and CSV/text files.")
                data = base64.b64decode(b.get("data", ""), validate=False)
                if not data or len(data) > MAX_UPLOAD:
                    raise ValueError("The file is empty or larger than 15 MB.")
            except (ValueError, TypeError) as e:
                return self.send(400, {"error": str(e)})
            return self.send(200, {"id": start_scan(u, name, mime, data)})
        m = re.fullmatch(r"/api/scan/([a-f0-9]{24})/(apply|discard)", p)
        if m:
            job = load_job(m.group(1))
            if not job or (u["role"] != "admin" and job["by"] != u["name"]):
                return self.send(404, {"error": "Not found"})
            if job["status"] != "ready":
                return self.send(409, {"error": "This scan has already been " + ("approved." if job["status"] == "applied" else job["status"] + ".")})
            if m.group(2) == "discard":
                job.update(status="discarded", finished=int(time.time() * 1000))
                drop_upload(job)
                save_job(job)
                audit(u, "scan-discard", "", "", job["name"])
                return self.send(200, {"ok": True})
            try:
                b = self.body()
                accept = list(b.get("accept") or [])
                assign = dict(b.get("assign") or {})
            except (ValueError, TypeError) as e:
                return self.send(400, {"error": str(e)})
            applied, skipped = apply_scan(job, u, accept, assign)
            return self.send(200, {"applied": applied, "skipped": skipped})
        if p != "/api/diag":
            return self.send(404, {"error": "Not found"})
        try:
            b = self.body()
        except ValueError as e:
            return self.send(400, {"error": str(e)})
        line = json.dumps({"t": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "user": u["name"], "report": str(b.get("report", ""))[:20000],
                           "app": str(b.get("app", ""))[:100], "device": str(b.get("device", ""))[:200]})
        with open(path("diag.log"), "a") as f:
            f.write(line + "\n")
        self.send(200, {"ok": True})

    def do_PUT(self):
        u = self.user()
        if not u:
            return
        m = re.fullmatch(r"/api/students/([^/]+)", urlparse(self.path).path)
        if not m or not UID.match(m.group(1)):
            return self.send(404, {"error": "Not found"})
        uid = m.group(1)
        try:
            b = self.body()
            state = b["state"]
            base = int(b.get("baseRev") or 0)
            if not isinstance(state, dict) or not isinstance(state.get("lessons"), list) or not isinstance(state.get("name", ""), str):
                raise ValueError("Not a tracker record.")
        except (ValueError, KeyError, TypeError) as e:
            return self.send(400, {"error": str(e) or "Bad request"})
        with changed:
            cur = db["students"].get(uid)
            if cur and cur["rev"] != base:
                return self.send(409, {"student": dict(cur, uid=uid)})
            db["rev"] += 1
            db["students"][uid] = {"state": state, "rev": db["rev"], "updated": int(time.time() * 1000), "by": u["name"]}
            mark_dirty()
            audit(u, "save", uid, state.get("name", ""), describe(cur and not cur.get("deleted") and cur["state"], state))
            changed.notify_all()
            rev = db["rev"]
        self.send(200, {"rev": rev})

    def do_DELETE(self):
        u = self.user()
        if not u:
            return
        mu = re.fullmatch(r"/api/users/([0-9a-f]{12})", urlparse(self.path).path)
        if mu:
            if u["role"] != "admin":
                return self.send(403, {"error": "Only an admin can remove access."})
            try:
                remove_user(u, mu.group(1))
            except LookupError as e:
                return self.send(404, {"error": str(e)})
            except ValueError as e:
                return self.send(400, {"error": str(e)})
            return self.send(200, {"users": user_list(u)})
        if u["role"] != "admin":
            return self.send(403, {"error": "Only an admin can remove a student."})
        m = re.fullmatch(r"/api/students/([^/]+)", urlparse(self.path).path)
        if not m or m.group(1) not in db["students"]:
            return self.send(404, {"error": "Not found"})
        uid = m.group(1)
        with changed:
            name = db["students"][uid]["state"].get("name", "")
            db["rev"] += 1
            # Keep a tombstone so every device removes it too.
            db["students"][uid] = {"state": {"name": name, "lessons": []}, "rev": db["rev"], "deleted": True,
                                   "updated": int(time.time() * 1000), "by": u["name"]}
            mark_dirty()
            audit(u, "remove", uid, name)
            changed.notify_all()
        self.send(200, {"ok": True})


def serve(host, port):
    global db
    os.makedirs(DATA, exist_ok=True)
    db = read_json("db.json", db)
    threading.Thread(target=flusher, daemon=True).start()

    def stop(*_):
        with lock:
            write_json("db.json", db)
        os._exit(0)
    signal.signal(signal.SIGTERM, stop)
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    print(f"CPL tracker database on http://{host}:{port}  data: {DATA}", flush=True)
    httpd.serve_forever()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8787)
    a = sub.add_parser("add-user")
    a.add_argument("name")
    a.add_argument("--role", choices=["admin", "instructor"], default="instructor")
    r = sub.add_parser("remove-user")
    r.add_argument("name")
    sub.add_parser("list-users")
    sub.add_parser("set-claude-key", help="paste the Anthropic API key used to read uploads")
    args = p.parse_args()
    os.makedirs(DATA, exist_ok=True)
    if args.cmd == "serve":
        return serve(args.host, args.port)
    if args.cmd == "set-claude-key":
        raw = sys.stdin.readline() if not sys.stdin.isatty() else __import__("getpass").getpass("Anthropic API key (hidden, paste once): ")
        keys = re.findall(r"sk-ant-[A-Za-z0-9_\-]+?(?=sk-ant-|[^A-Za-z0-9_\-]|$)", raw)
        if not keys:
            sys.exit("That does not look like an Anthropic API key (it starts with sk-ant-).")
        if len(set(keys)) > 1:
            sys.exit("Two different keys were pasted. Run it again and paste just one.")
        key = keys[0]
        if len(keys) > 1:
            print("The key was pasted more than once; using it once.")
        import urllib.request, urllib.error
        req = urllib.request.Request("https://api.anthropic.com/v1/models?limit=1",
                                     headers={"x-api-key": key, "anthropic-version": "2023-06-01"})
        try:
            urllib.request.urlopen(req, timeout=20).read()
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                sys.exit("Anthropic says this key is not valid (wrong, revoked or disabled). Nothing was saved. "
                         "Copy a fresh key from console.anthropic.com > API keys and try again.")
            print(f"Could not fully check the key (Anthropic answered {e.code}); saving it anyway.")
        except OSError:
            print("Could not reach Anthropic to check the key; saving it anyway.")
        fd = os.open(path("claude.key"), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(key + "\n")
        return print("Saved. Uploads will now be read by Claude.")
    us = users()
    if args.cmd == "add-user":
        code = new_code()
        us[code_hash(code)] = {"id": secrets.token_hex(6), "name": args.name, "role": args.role, "created": int(time.time() * 1000), "by": "server"}
        save_users(us)
        print(f"{args.name} ({args.role}) access code: {code}\nGive it to them privately; it is not shown again.")
    elif args.cmd == "remove-user":
        left = {k: v for k, v in us.items() if v["name"] != args.name}
        save_users(left)
        print(f"Removed {len(us) - len(left)} code(s) for {args.name}.")
    else:
        for v in us.values():
            print(f"{v['name']}  ({v['role']})")


if __name__ == "__main__":
    main()
