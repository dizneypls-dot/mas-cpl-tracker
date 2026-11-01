// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

// Every student record, kept on the phone (works offline) and synced live with the shared database:
// changes are sent at once, and changes from other devices arrive within seconds (long polling).
// When two people edit the same student, changes are merged lesson by lesson.
final class Store {
    static final String DEFAULT_SERVER = "https://coursetracker.online";
    static final String OLD_SERVER = "https://179-237-123-196.sslip.io"; // still answers; saved sign-ins move to the new address

    static final class Rec {
        String uid, by = "";
        JSONObject state, base;
        long rev, updated;
        boolean dirty;
        int version;
    }

    interface Listener { void changed(); }

    private static Store instance;
    private final Context app;
    private final SharedPreferences prefs;
    private final Map<String, Rec> recs = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private long rev;
    private volatile boolean loaded, running;
    private volatile int loopGen;
    volatile String status = "Offline";
    volatile long lastSync;
    private final Runnable saveTask = () -> io.execute(this::writeFile);
    private final Runnable pushTask = () -> net.execute(this::push);

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private Store(Context c) {
        app = c;
        prefs = c.getSharedPreferences("account", Context.MODE_PRIVATE);
        Course.load(c);
    }

    // ---------- account ----------
    boolean signedIn() { return !prefs.getString("code", "").isEmpty(); }
    String user() { return prefs.getString("user", ""); }
    String role() { return prefs.getString("role", ""); }
    boolean admin() { return "admin".equals(role()); }
    String server() { String u = prefs.getString("url", DEFAULT_SERVER); return OLD_SERVER.equals(u) ? DEFAULT_SERVER : u; }

    interface Done { void done(String error); }

    void signIn(String url, String code, Done d) {
        net.execute(() -> {
            try {
                JSONObject me = new JSONObject(http("GET", url, "/api/me", code.trim(), null));
                prefs.edit().putString("url", url).putString("code", code.trim()).putString("user", me.optString("name"))
                        .putString("role", me.optString("role")).apply();
                main.post(() -> { d.done(null); start(); });
            } catch (HttpError e) {
                main.post(() -> d.done(e.code == 401 ? "That access code was not accepted." : e.getMessage()));
            } catch (Exception e) {
                main.post(() -> d.done("Could not reach the server. Check the internet connection."));
            }
        });
    }

    void signOut() {
        running = false;
        loopGen++;
        synchronized (this) { recs.clear(); rev = 0; }
        prefs.edit().clear().apply();
        io.execute(() -> new File(app.getFilesDir(), "students.json").delete());
        notifyChanged();
    }

    // ---------- loading and saving on the phone ----------
    void start() {
        if (!loaded) {
            io.execute(() -> { readFile(); loaded = true; main.post(() -> { notifyChanged(); startSync(); }); });
        } else startSync();
    }

    boolean loaded() { return loaded; }

    private void startSync() {
        if (!signedIn() || running) return;
        running = true;
        int gen = ++loopGen;
        new Thread(() -> loop(gen), "sync").start();
        main.post(pushTask);
    }

    void resume() { if (signedIn() && !running && loaded) startSync(); else if (signedIn()) main.post(pushTask); }

    private void readFile() {
        File f = new File(app.getFilesDir(), "students.json");
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject j = new JSONObject(readAll(in));
            synchronized (this) {
                rev = j.optLong("rev");
                JSONArray a = j.getJSONArray("students");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    Rec r = new Rec();
                    r.uid = x.getString("uid");
                    r.state = x.getJSONObject("state");
                    r.base = x.optJSONObject("base");
                    r.rev = x.optLong("rev");
                    r.updated = x.optLong("updated");
                    r.by = x.optString("by");
                    r.dirty = x.optBoolean("dirty");
                    recs.put(r.uid, r);
                }
            }
        } catch (Exception e) {
            // A damaged copy is replaced from the server on the next sync.
            synchronized (this) { recs.clear(); rev = 0; }
        }
    }

    private void writeFile() {
        String out;
        synchronized (this) {
            try {
                JSONObject j = new JSONObject();
                j.put("rev", rev);
                JSONArray a = new JSONArray();
                for (Rec r : recs.values()) {
                    JSONObject x = new JSONObject();
                    x.put("uid", r.uid).put("state", r.state).put("rev", r.rev).put("updated", r.updated).put("by", r.by).put("dirty", r.dirty);
                    if (r.base != null) x.put("base", r.base);
                    a.put(x);
                }
                j.put("students", a);
                out = j.toString();
            } catch (Exception e) { return; }
        }
        File f = new File(app.getFilesDir(), "students.json"), tmp = new File(app.getFilesDir(), "students.json.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(out.getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (Exception e) { return; }
        tmp.renameTo(f);
    }

    private void scheduleSave() { main.removeCallbacks(saveTask); main.postDelayed(saveTask, 800); }

    // ---------- reading ----------
    synchronized List<Rec> all() { return new ArrayList<>(recs.values()); }
    synchronized Rec find(String uid) { return recs.get(uid); }

    // ---------- editing ----------
    interface Edit { void apply(JSONObject state) throws Exception; }

    void edit(String uid, Edit e) {
        synchronized (this) {
            Rec r = recs.get(uid);
            if (r == null) return;
            try {
                if (!r.dirty) r.base = copy(r.state);
                e.apply(r.state);
            } catch (Exception ex) { return; }
            r.dirty = true;
            r.version++;
            r.updated = System.currentTimeMillis();
            r.by = user();
        }
        scheduleSave();
        main.removeCallbacks(pushTask);
        main.postDelayed(pushTask, 600);
        notifyChanged();
    }

    String create(JSONObject state) {
        Rec r = new Rec();
        r.uid = UUID.randomUUID().toString();
        r.state = state;
        r.dirty = true;
        r.updated = System.currentTimeMillis();
        r.by = user();
        synchronized (this) { recs.put(r.uid, r); }
        scheduleSave();
        main.post(pushTask);
        notifyChanged();
        return r.uid;
    }

    void remove(String uid, Done d) {
        net.execute(() -> {
            try {
                Rec r = find(uid);
                if (r != null && r.rev > 0) http("DELETE", server(), "/api/students/" + uid, code(), null);
                synchronized (this) { recs.remove(uid); }
                scheduleSaveFromAny();
                main.post(() -> { notifyChanged(); d.done(null); });
            } catch (HttpError e) {
                main.post(() -> d.done(e.getMessage()));
            } catch (Exception e) {
                main.post(() -> d.done("Could not reach the server."));
            }
        });
    }

    private void scheduleSaveFromAny() { main.post(this::scheduleSave); }

    // ---------- sync ----------
    private String code() { return prefs.getString("code", ""); }

    private void loop(int gen) {
        int fails = 0;
        while (running && gen == loopGen) {
            try {
                long since;
                synchronized (this) { since = rev; }
                JSONObject res = new JSONObject(http("GET", server(), "/api/students?since=" + since + "&wait=25", code(), null));
                if (gen != loopGen) return;
                applyRemote(res);
                fails = 0;
                setStatus("Live");
            } catch (HttpError e) {
                if (e.code == 401) { setStatus("Access code no longer accepted"); running = false; return; }
                fails++;
                setStatus("Can't reach the server · retrying");
                sleep(Math.min(30000, 2000L << Math.min(fails, 4)));
            } catch (Exception e) {
                fails++;
                setStatus("Offline · changes are kept on this phone");
                sleep(Math.min(30000, 2000L << Math.min(fails, 4)));
            }
        }
    }

    private void applyRemote(JSONObject res) throws Exception {
        JSONArray list = res.getJSONArray("students");
        boolean any = false;
        synchronized (this) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                String uid = s.getString("uid");
                Rec r = recs.get(uid);
                if (s.optBoolean("deleted")) { if (r != null) { recs.remove(uid); any = true; } continue; }
                long srev = s.getLong("rev");
                JSONObject st = s.getJSONObject("state");
                if (r == null) {
                    r = new Rec();
                    r.uid = uid;
                    recs.put(uid, r);
                } else if (r.rev >= srev) continue;
                if (r.dirty && r.state != null) r.state = merge(r.base, r.state, st);
                else r.state = st;
                if (r.dirty) r.base = copy(st);
                r.rev = srev;
                r.updated = s.optLong("updated");
                r.by = s.optString("by");
                any = true;
            }
            rev = Math.max(rev, res.getLong("rev"));
            lastSync = System.currentTimeMillis();
        }
        if (any) { main.post(this::scheduleSave); notifyChanged(); }
        else main.post(this::scheduleSave);
    }

    private void push() {
        if (!signedIn()) return;
        for (int round = 0; round < 3; round++) {
            List<Rec> dirty = new ArrayList<>();
            synchronized (this) { for (Rec r : recs.values()) if (r.dirty) dirty.add(r); }
            if (dirty.isEmpty()) return;
            for (Rec r : dirty) {
                String body;
                int version;
                long base;
                synchronized (this) {
                    if (!recs.containsKey(r.uid)) continue;
                    version = r.version;
                    base = r.rev;
                    try { body = new JSONObject().put("state", r.state).put("baseRev", base).toString(); } catch (Exception e) { continue; }
                }
                try {
                    JSONObject res = new JSONObject(http("PUT", server(), "/api/students/" + r.uid, code(), body));
                    synchronized (this) {
                        r.rev = res.getLong("rev");
                        if (r.version == version) { r.dirty = false; r.base = null; }
                        else r.base = new JSONObject(body).getJSONObject("state");
                    }
                } catch (HttpError e) {
                    if (e.code == 409 && e.body != null) {
                        try {
                            JSONObject theirs = new JSONObject(e.body).getJSONObject("student");
                            synchronized (this) {
                                if (theirs.optBoolean("deleted")) { recs.remove(r.uid); continue; }
                                r.state = merge(r.base, r.state, theirs.getJSONObject("state"));
                                r.base = copy(theirs.getJSONObject("state"));
                                r.rev = theirs.getLong("rev");
                                r.by = theirs.optString("by");
                            }
                            notifyChanged();
                        } catch (Exception ignored) { }
                    } else { setStatus("Can't save to the server · will retry"); main.postDelayed(pushTask, 15000); return; }
                } catch (Exception e) {
                    setStatus("Offline · changes are kept on this phone");
                    main.postDelayed(pushTask, 15000);
                    return;
                }
            }
            main.post(this::scheduleSave);
        }
    }

    void post(String path, String body) throws Exception { http("POST", server(), path, code(), body); }

    /** A call to the tracker server with this phone's access code (used by the Claude review screens). */
    String api(String method, String path, String body) throws Exception { return http(method, server(), path, code(), body); }

    synchronized int pending() { int n = 0; for (Rec r : recs.values()) if (r.dirty) n++; return n; }

    private void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        notifyChanged();
    }

    // ---------- merging (same rules as the website) ----------
    static JSONObject merge(JSONObject base, JSONObject mine, JSONObject theirs) {
        try {
            if (base == null) return copy(mine);
            JSONObject out = copy(theirs);
            Iterator<String> keys = mine.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object m = mine.opt(k), b = base.opt(k);
                if ("lessons".equals(k) || "exams".equals(k)) {
                    JSONArray ma = mine.optJSONArray(k), ba = base.optJSONArray(k), oa = out.optJSONArray(k);
                    if (ma == null || oa == null) continue;
                    for (int i = 0; i < ma.length(); i++) {
                        JSONObject v = ma.getJSONObject(i);
                        JSONObject bv = byId(ba, v.opt("id"));
                        if (bv == null || !canon(bv).equals(canon(v))) {
                            for (int j = 0; j < oa.length(); j++)
                                if (Course.id(oa.getJSONObject(j).opt("id")).equals(Course.id(v.opt("id")))) oa.put(j, copy(v));
                        }
                    }
                } else if ("attendance".equals(k)) {
                    Map<String, JSONObject> bm = keyed(base.optJSONArray(k)), mm = keyed(mine.optJSONArray(k)), om = keyed(out.optJSONArray(k));
                    for (Map.Entry<String, JSONObject> e : mm.entrySet())
                        if (!bm.containsKey(e.getKey()) || !canon(bm.get(e.getKey())).equals(canon(e.getValue()))) om.put(e.getKey(), e.getValue());
                    for (String key : bm.keySet()) if (!mm.containsKey(key)) om.remove(key);
                    JSONArray a = new JSONArray();
                    for (JSONObject v : om.values()) a.put(v);
                    out.put(k, a);
                } else if (("meeting".equals(k) || "milestones".equals(k)) && m instanceof JSONObject) {
                    JSONObject o = out.optJSONObject(k) == null ? new JSONObject() : out.getJSONObject(k);
                    JSONObject bo = b instanceof JSONObject ? (JSONObject) b : new JSONObject();
                    Iterator<String> kk = ((JSONObject) m).keys();
                    while (kk.hasNext()) {
                        String x = kk.next();
                        Object mv = ((JSONObject) m).opt(x);
                        if (!canon(bo.opt(x)).equals(canon(mv))) o.put(x, mv);
                    }
                    out.put(k, o);
                } else if (!canon(b).equals(canon(m))) out.put(k, m);
            }
            return out;
        } catch (Exception e) { return mine; }
    }

    private static JSONObject byId(JSONArray a, Object id) {
        if (a == null) return null;
        String s = Course.id(id);
        for (int i = 0; i < a.length(); i++) { JSONObject v = a.optJSONObject(i); if (v != null && Course.id(v.opt("id")).equals(s)) return v; }
        return null;
    }

    private static Map<String, JSONObject> keyed(JSONArray a) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject v = a.optJSONObject(i);
            if (v != null) m.put(v.optString("date") + "|" + v.optString("session").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim(), v);
        }
        return m;
    }

    // Order-independent text form, for comparing records.
    static String canon(Object o) {
        if (o == null || o == JSONObject.NULL) return "null";
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            TreeMap<String, String> t = new TreeMap<>();
            Iterator<String> k = j.keys();
            while (k.hasNext()) { String x = k.next(); t.put(x, canon(j.opt(x))); }
            return t.toString();
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) b.append(canon(a.opt(i))).append(',');
            return b.append(']').toString();
        }
        if (o instanceof Number) return Course.id(o);
        return String.valueOf(o);
    }

    static JSONObject copy(JSONObject o) {
        try { return o == null ? null : new JSONObject(o.toString()); } catch (Exception e) { return o; }
    }

    // ---------- listeners ----------
    void listen(Listener l) { synchronized (listeners) { listeners.add(l); } }
    void unlisten(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    private void notifyChanged() {
        main.post(() -> {
            List<Listener> ls;
            synchronized (listeners) { ls = new ArrayList<>(listeners); }
            for (Listener l : ls) l.changed();
        });
    }

    // ---------- http ----------
    static final class HttpError extends Exception {
        final int code; final String body;
        HttpError(int code, String msg, String body) { super(msg); this.code = code; this.body = body; }
    }

    static String http(String method, String base, String path, String code, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + code);
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) in = new java.util.zip.GZIPInputStream(in);
        String text = in == null ? "" : readAll(in);
        if (status >= 400) {
            String msg;
            try { msg = new JSONObject(text).optString("error", "Server error " + status); } catch (Exception e) { msg = "Server error " + status; }
            throw new HttpError(status, msg, text);
        }
        return text;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        in.close();
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Australia/Melbourne"));
        return f.format(new Date());
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static List<Rec> sorted(List<Rec> l) {
        Collections.sort(l, (a, b) -> a.state.optString("name").compareToIgnoreCase(b.state.optString("name")));
        return l;
    }
}
