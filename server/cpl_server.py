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
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DATA = os.environ.get("CPL_DATA", "/var/lib/cpl-tracker")
MAX_BODY = 2 * 1024 * 1024
UID = re.compile(r"^[A-Za-z0-9-]{8,64}$")

lock = threading.Lock()
changed = threading.Condition(lock)
db = {"rev": 0, "students": {}}
failures = {}


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


def code_hash(code):
    return hashlib.sha256(code.strip().encode()).hexdigest()


def users():
    return read_json("users.json", {})


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
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, DELETE, OPTIONS")
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

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            raise ValueError("Record too large.")
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
            write_json("db.json", db)
            audit(u, "save", uid, state.get("name", ""), describe(cur and not cur.get("deleted") and cur["state"], state))
            changed.notify_all()
            rev = db["rev"]
        self.send(200, {"rev": rev})

    def do_DELETE(self):
        u = self.user()
        if not u:
            return
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
            write_json("db.json", db)
            audit(u, "remove", uid, name)
            changed.notify_all()
        self.send(200, {"ok": True})


def serve(host, port):
    global db
    os.makedirs(DATA, exist_ok=True)
    db = read_json("db.json", db)
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
    args = p.parse_args()
    os.makedirs(DATA, exist_ok=True)
    if args.cmd == "serve":
        return serve(args.host, args.port)
    us = users()
    if args.cmd == "add-user":
        code = "-".join(secrets.token_hex(2).upper() for _ in range(4))
        us[code_hash(code)] = {"name": args.name, "role": args.role}
        write_json("users.json", us)
        os.chmod(path("users.json"), 0o600)
        print(f"{args.name} ({args.role}) access code: {code}\nGive it to them privately; it is not shown again.")
    elif args.cmd == "remove-user":
        left = {k: v for k, v in us.items() if v["name"] != args.name}
        write_json("users.json", left)
        print(f"Removed {len(us) - len(left)} code(s) for {args.name}.")
    else:
        for v in us.values():
            print(f"{v['name']}  ({v['role']})")


if __name__ == "__main__":
    main()
