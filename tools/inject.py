#!/usr/bin/env python3
"""Puts src/students.js and src/students.css into src/index.html and stamps the build time.
Safe to run again: replaces what it inserted before. Run before build.sh / android/build-apk.sh."""
import datetime
import os
import re

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
page = os.path.join(root, "src", "index.html")
s = open(page, encoding="utf-8").read()
js = open(os.path.join(root, "src", "students.js"), encoding="utf-8").read()
css = open(os.path.join(root, "src", "students.css"), encoding="utf-8").read()
assert "</script>" not in js and "</style>" not in css


def put(s, start, end, body, anchor):
    block = start + body + end
    if start in s:
        return re.sub(re.escape(start) + r".*?" + re.escape(end), lambda m: block, s, count=1, flags=re.S)
    assert s.count(anchor) == 1, anchor
    return s.replace(anchor, block + anchor, 1)


s = put(s, "/*students:start*/\n", "/*students:end*/\n", js, "// App shell: installable web app")
s = put(s, '<style id="students">', "</style>", css, '</head><body><header class="site">')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
meta = '<meta name="cpl-build" content="%s">' % stamp
if re.search(r'<meta name="cpl-build" content="\d[^"]*">', s):
    s = re.sub(r'<meta name="cpl-build" content="\d[^"]*">', meta, s, count=1)
else:
    s = s.replace("<meta charset", meta + "<meta charset", 1) if "<meta charset" in s else s.replace("<head>", "<head>" + meta, 1)
icon = " students:['Students','<circle cx=\"9\" cy=\"8\" r=\"3.5\"/><path d=\"M2.5 20c.6-3.6 3.2-5.5 6.5-5.5s5.9 1.9 6.5 5.5\"/><circle cx=\"17\" cy=\"9\" r=\"2.6\"/><path d=\"M16.5 14.6c2.7.1 4.5 1.8 5 4.6\"/>'],\n"
if "students:['Students'" not in s:
    s = s.replace("const icons={dashboard:", "const icons={\n" + icon + "dashboard:", 1)
strip = "#resetStudent,.apptoast,#installApp'"
if "#studentsPage,[data-page" not in s:
    assert s.count(strip) == 1
    s = s.replace(strip, "#resetStudent,.apptoast,#installApp,#studentsPage,[data-page=\"students\"],#stChip,.stupdate'", 1)
if "#attPanel" not in s:
    s = s.replace("#stChip,.stupdate'", "#stChip,.stupdate,#attPanel,#attCard'", 1)
open(page, "w", encoding="utf-8").write(s)
print("Injected students layer, build", stamp)
