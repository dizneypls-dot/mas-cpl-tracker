// MAS CPL Tracker — website. Same records and rules as the Android app, synced live with the shared database.
'use strict';
(async function () {
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const norm = v => String(v ?? '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const clone = x => JSON.parse(JSON.stringify(x));
const canon = o => JSON.stringify(o, (k, v) => v && typeof v === 'object' && !Array.isArray(v) ? Object.keys(v).sort().reduce((a, x) => (a[x] = v[x], a), {}) : v);
const same = (a, b) => canon(a) === canon(b);
const today = () => new Date().toLocaleDateString('en-CA', {timeZone: 'Australia/Melbourne'});
const auDate = iso => iso && iso.length >= 10 ? iso.slice(8, 10) + '/' + iso.slice(5, 7) + '/' + iso.slice(0, 4) : (iso || '');
const pct = v => (v >= 10 || v === 0 ? v.toFixed(0) : v.toFixed(1)) + '%';
const hrs = v => (v || 0).toFixed(1) + ' h';
const money = v => '$' + Math.round(v).toLocaleString('en-AU');
const newId = () => crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
const SERVER = 'https://coursetracker.online';
const OLD_SERVER = 'https://179-237-123-196.sslip.io'; // still answers; saved sign-ins move to the new address
const SICK = 'Sick (certificate)';

const ICON = {
  people: 'M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z',
  dashboard: 'M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z',
  upload: 'M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z',
  person: 'M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z',
  search: 'M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z',
  add: 'M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  back: 'M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z',
  check: 'M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z',
  close: 'M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z',
  flight: 'M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z',
  edit: 'M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z',
  logout: 'M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z',
  delete: 'M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z',
  open: 'M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z',
  file: 'M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z',
  chevron: 'M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z',
  sort: 'M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z',
  school: 'M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z',
  report: 'M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM9,17H7v-7h2v7zM13,17h-2V7h2v10zM17,17h-2v-4h2v4z',
  android: 'M17.6,9.48l1.84,-3.18c0.16,-0.31 0.04,-0.69 -0.26,-0.85 -0.29,-0.15 -0.65,-0.06 -0.83,0.22l-1.88,3.24c-2.86,-1.21 -6.08,-1.21 -8.94,0L5.65,5.67c-0.19,-0.29 -0.58,-0.38 -0.87,-0.2 -0.28,0.18 -0.37,0.54 -0.22,0.83L6.4,9.48C3.3,11.25 1.28,14.44 1,18h22C22.72,14.44 20.7,11.25 17.6,9.48zM7,15.25c-0.69,0 -1.25,-0.56 -1.25,-1.25S6.31,12.75 7,12.75 8.25,13.31 8.25,14 7.69,15.25 7,15.25zM17,15.25c-0.69,0 -1.25,-0.56 -1.25,-1.25s0.56,-1.25 1.25,-1.25 1.25,0.56 1.25,1.25S17.69,15.25 17,15.25z',
  spark: 'M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z',
  sync: 'M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z',
};
const icon = (n, cls = 'i') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON[n]}"/></svg>`;
const AV = ['#16627a', '#0b2a4f', '#7a5a20', '#5b3e8e', '#2e6b3f', '#8e3e4e', '#3e5b8e', '#6b5b2e'];
function hash(s) { let h = 0; for (const c of String(s || '')) h = (h * 31 + c.charCodeAt(0)) | 0; return Math.abs(h); }
function initials(n) { const p = String(n || '').replace(/\(.*?\)/g, '').trim().split(/\s+/).filter(Boolean); return p.length ? (p[0][0] + (p.length > 1 ? p[p.length - 1][0] : '')).toUpperCase() : '?'; }
const avatar = (n, size = 44) => `<span class="avatar" style="width:${size}px;height:${size}px;background:${AV[hash(n) % AV.length]};font-size:${Math.round(size * .36)}px">${esc(initials(n))}</span>`;
const tone = v => v >= 80 ? '#1f7a4d' : v >= 50 ? '#16627a' : v >= 20 ? '#b7791f' : '#123b6b';
function ring(v, size = 52, color = tone(v), track = '#e8edf3') {
  const r = size / 2 - size * .07, c = 2 * Math.PI * r, d = Math.max(0, Math.min(100, v)) / 100 * c;
  return `<svg class="ring" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" role="img" aria-label="${pct(v)} complete"><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${track}" stroke-width="${size * .11}"/><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${color}" stroke-width="${size * .11}" stroke-linecap="round" stroke-dasharray="${d} ${c}" transform="rotate(-90 ${size / 2} ${size / 2})"/><text x="50%" y="50%" text-anchor="middle" dominant-baseline="central" style="font-size:${size * .25}px">${pct(v)}</text></svg>`;
}
const bar = (v, color = '#16627a') => `<div class="bar"><i style="width:${Math.max(0, Math.min(100, v))}%;background:${color}"></i></div>`;
const shortCourse = c => { if (!c) return 'No course'; const s = c.replace(/^MAS\s+/, '').replace('Pliot', 'Pilot'); if (s.includes('CPL (150hr integrated)')) return 'CPL 150hr integrated'; if (s.includes('CPL (200hr non-integrated)')) return 'CPL 200hr' + (s.endsWith('2026') ? ' (2026)' : ''); return s.replace(/^Commercial Pilot Licence - /, ''); };
function ago(ms) { if (!ms) return '—'; const m = Math.round((Date.now() - ms) / 60000); return m < 1 ? 'just now' : m < 60 ? m + ' min ago' : m < 1440 ? Math.round(m / 60) + ' h ago' : m < 43200 ? Math.round(m / 1440) + ' d ago' : new Date(ms).toLocaleDateString('en-AU', {day: 'numeric', month: 'short', year: 'numeric'}); }
function toast(msg) { let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; t.setAttribute('role', 'status'); document.body.append(t); } t.textContent = msg; t.classList.add('show'); clearTimeout(t.timer); t.timer = setTimeout(() => t.classList.remove('show'), 3200); }

// ---------- syllabus and figures (same rules as the app) ----------
const CAT = await (await fetch('catalogue.json', {cache: 'no-cache'})).json();
const ORDER = CAT.order, LESSONS = CAT.lessons, TEMPLATE = CAT.template;
const EXAM_NAMES = {FROL: 'Flight Radio Operator Licence', 'Pre-Solo': 'Pre-solo exam', 'Pre-Area Solo': 'Pre-area solo exam', CMET: 'CPL Meteorology', CFPA: 'CPL Flight Planning', CADA: 'CPL Aerodynamics', CNAV: 'CPL Navigation', CLWA: 'CPL Air Law', CHUF: 'CPL Human Factors', CSYA: 'CPL Aircraft Systems'};
const label = id => LESSONS[id] ? id + '. ' + LESSONS[id].name : String(id);
const order = id => ORDER.indexOf(String(id));
const phase = id => { const n = parseFloat(id); return n <= 33 ? 'RPL · Recreational Pilot Licence' : n <= 44 ? 'PPL · Private Pilot Licence' : 'CPL · Commercial Pilot Licence'; };
function blank(name) { const s = clone(TEMPLATE); s.name = name || ''; s.date = today(); return s; }
function figures(s) {
  const f = {practical: 0, theory: 0, overall: 0, attendance: -1, hours: 0, solo: 0, ifr: 0, done: 0, flights: 0, present: 0, late: 0, absent: 0, sick: 0, sessions: 0, next: 'Course complete', lastFlight: ''};
  const ls = Array.isArray(s.lessons) ? s.lessons : [];
  let total = 0, got = 0;
  for (const v of ls) { total += v.weight || 0; if (v.status === 'Completed') { got += v.weight || 0; f.done++; } else if (f.next === 'Course complete') f.next = label(String(v.id)); }
  f.practical = total ? got / total * 100 : 0;
  for (const v of s.exams || []) if (v.status === 'Passed') f.theory += v.weight || 0;
  const share = Number.isFinite(s.share) ? s.share : 60;
  f.overall = f.practical * share / 100 + f.theory * (1 - share / 100);
  for (const x of s.flights || []) { f.flights++; f.hours += x.hours || 0; if (x.solo) f.solo += x.hours || 0; f.ifr += x.ifr || 0; if ((x.date || '') > f.lastFlight) f.lastFlight = x.date; }
  // What they last flew, and the next lesson after the furthest one flown (earlier unmarked lessons are gaps).
  let lastF = null, furthest = -1;
  for (const x of s.flights || []) { if (!lastF || (x.date || '') >= (lastF.date || '')) lastF = x; const o = x.lesson ? order(x.lesson) : -1; if (o > furthest) furthest = o; }
  f.last = lastF ? (LESSONS[lastF.lesson] ? label(lastF.lesson) : lastF.raw || 'Flight') : ''; f.lastDate = lastF ? lastF.date : '';
  f.gaps = 0;
  if (furthest >= 0) {
    const nxt = ls.find(v => order(String(v.id)) > furthest && v.status !== 'Completed');
    const gap = ls.find(v => v.status !== 'Completed');
    f.next = nxt ? label(String(nxt.id)) : gap ? label(String(gap.id)) : 'Course complete';
    f.gaps = ls.filter(v => order(String(v.id)) < furthest && v.status !== 'Completed').length;
  }
  if (s.course && !/CPL|Commercial Pil/i.test(s.course)) { f.next = ''; f.gaps = 0; }
  f.present = f.flights;
  for (const a of s.attendance || []) { if (a.status === 'Present') f.present++; else if (a.status === 'Late') f.late++; else if (a.status === SICK) f.sick++; else f.absent++; }
  f.sessions = f.present + f.late + f.absent + f.sick;
  const counted = f.sessions - f.sick;
  // Flights alone never make an attendance figure (every one would be "present", giving 100%).
  // With an Aviation 1 in 60 record the figure is hours attended against 20 h a week, as in the report;
  // otherwise it needs recorded sessions with at least one that was not a flight.
  f.hoursRate = CPLReport.rate(s, today());
  if (f.hoursRate >= 0) f.attendance = f.hoursRate;
  else if (counted > 0 && counted > f.flights) f.attendance = (f.present + f.late) * 100 / counted;
  return f;
}
const MILESTONES = [10, 21, 30, 37];
function milestone(s, id) {
  const idx = order(id); let allowance = 0;
  for (let i = 0; i <= idx; i++) { const l = LESSONS[ORDER[i]]; if (l && !/\bsim\b/i.test(l.name)) allowance += l.hours || 0; }
  const r = (s.milestones || {})[id] || {};
  const planned = r.allowance !== undefined && r.allowance !== '' ? Number(r.allowance) : Math.round(allowance * 10) / 10;
  const accrued = r.accrued !== undefined && r.accrued !== '' ? Number(r.accrued) : null;
  const overfly = accrued === null ? null : Math.max(0, accrued - planned);
  return {id, planned, accrued, overfly, cost: overfly === null ? null : overfly * 500};
}
function setExamResult(v, result) {
  if (v.status === result) return;
  if (!Array.isArray(v.attempts)) v.attempts = [];
  if (['Passed', 'Failed'].includes(v.status) && !v.attempts.some(a => a.result === v.status)) v.attempts.push({result: v.status, date: v.examDate || ''});
  v.status = result;
  if (['Passed', 'Failed'].includes(result)) v.attempts.push({result, date: v.examDate || ''});
}
// Records from backups or other devices get the fields this version expects.
function prepare(x) {
  try { x = clone(x); } catch (e) { return null; }
  if (!x || !Array.isArray(x.lessons) || typeof x.name !== 'string') return null;
  if (x.lessons.length === 81 && x.lessons.every((v, i) => v.id === i + 1)) x.lessons.splice(48, 0, {id: '48.a', weight: 0.5, status: 'Not started', notes: ''});
  if (x.lessons.length === 82 && !x.lessons.some(v => v.id === '16.a')) x.lessons.splice(16, 0, {id: '16.a', weight: TEMPLATE.lessons[16].weight, status: 'Not started', notes: ''});
  for (const k of ['studentId', 'instructor', 'course', 'start', 'review']) if (x[k] === undefined) x[k] = '';
  if (!Array.isArray(x.exams)) x.exams = clone(TEMPLATE.exams);
  for (const v of x.exams) { if (v.status === 'Not started' || v.status === 'In progress') v.status = 'Not attempted'; if (!Array.isArray(v.attempts)) v.attempts = []; }
  if (!Array.isArray(x.attendance)) x.attendance = [];
  for (const a of x.attendance) if (a.status === 'Excused') a.status = /cert|certificate|doctor|\bmc\b/i.test(a.notes || '') ? SICK : 'Absent';
  if (!x.milestones || typeof x.milestones !== 'object') x.milestones = {};
  if (!Number.isFinite(x.share)) x.share = 60;
  if (!x.date) x.date = today();
  if (!x.version) x.version = 1;
  return x;
}

// ---------- the shared database ----------
const Store = {
  meta: (() => { try { const m = JSON.parse(localStorage.getItem('mas-cpl-web') || 'null') || {}; if (m.url === OLD_SERVER) m.url = SERVER; return m; } catch (e) { return {}; } })(),
  recs: new Map(), listeners: new Set(), status: 'Offline', lastSync: 0, ready: false, gen: 0, fails: 0, db: null, writes: new Set(), pushing: false,
  signedIn() { return !!this.meta.code; },
  saveMeta() { try { localStorage.setItem('mas-cpl-web', JSON.stringify(this.meta)); } catch (e) { } },
  async api(method, path, body, code = this.meta.code, url = this.meta.url || SERVER) {
    const res = await fetch(url.replace(/\/+$/, '') + path, {method, headers: {Authorization: 'Bearer ' + code, ...(body ? {'Content-Type': 'application/json'} : {})}, body: body ? JSON.stringify(body) : undefined, cache: 'no-store'});
    let j = {}; try { j = await res.json(); } catch (e) { }
    if (res.status === 409) return {conflict: j.student};
    if (!res.ok) throw Object.assign(Error(j.error || 'Server error ' + res.status), {status: res.status});
    return j;
  },
  async openDb() {
    if (this.db || typeof indexedDB === 'undefined') return;
    this.db = await new Promise((res, rej) => { const q = indexedDB.open('mas-cpl-web', 1); q.onupgradeneeded = () => q.result.createObjectStore('students', {keyPath: 'uid'}); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); }).catch(() => null);
  },
  async load() {
    await this.openDb();
    if (this.db) {
      const all = await new Promise(res => { const q = this.db.transaction('students').objectStore('students').getAll(); q.onsuccess = () => res(q.result); q.onerror = () => res([]); });
      for (const r of all) this.recs.set(r.uid, r);
    }
    this.ready = true;
    this.emit();
  },
  flushSoon() { clearTimeout(this.ft); this.ft = setTimeout(() => this.flush(), 300); },
  async flush() {
    if (!this.db || !this.writes.size) return;
    const uids = [...this.writes]; this.writes.clear();
    await new Promise(res => { const tx = this.db.transaction('students', 'readwrite'), st = tx.objectStore('students'); for (const u of uids) { const r = this.recs.get(u); if (r) st.put(r); else st.delete(u); } tx.oncomplete = res; tx.onerror = res; tx.onabort = res; });
  },
  touch(uid) { this.writes.add(uid); this.flushSoon(); },
  emit() { for (const l of this.listeners) try { l(); } catch (e) { console.error(e); } },
  setStatus(s) { if (s !== this.status) { this.status = s; this.emit(); } },
  all() { return [...this.recs.values()].filter(r => r.state && Array.isArray(r.state.lessons)); },
  get(uid) { return this.recs.get(uid); },
  edit(uid, fn) {
    const r = this.recs.get(uid); if (!r) return;
    if (!r.dirty) r.base = clone(r.state);
    fn(r.state);
    r.dirty = true; r.version = (r.version || 0) + 1; r.updated = Date.now(); r.by = this.meta.user || '';
    this.touch(uid); this.schedule(); this.emit();
  },
  replace(uid, state) { this.edit(uid, s => { for (const k of Object.keys(s)) delete s[k]; Object.assign(s, state); }); },
  create(state) {
    const uid = newId();
    this.recs.set(uid, {uid, state, rev: 0, updated: Date.now(), by: this.meta.user || '', base: null, dirty: true, version: 1});
    this.touch(uid); this.schedule(); this.emit();
    return uid;
  },
  async remove(uid) {
    const r = this.recs.get(uid); if (!r) return;
    if (r.rev) await this.api('DELETE', '/api/students/' + encodeURIComponent(uid));
    this.recs.delete(uid); this.touch(uid); this.emit();
  },
  schedule() { clearTimeout(this.pt); this.pt = setTimeout(() => this.push(), 500); },
  pending() { let n = 0; for (const r of this.recs.values()) if (r.dirty) n++; return n; },
  async push() {
    if (!this.signedIn() || this.pushing) return;
    this.pushing = true;
    try {
      for (const r of [...this.recs.values()]) {
        if (!r.dirty) continue;
        const sent = clone(r.state), version = r.version;
        let res;
        try { res = await this.api('PUT', '/api/students/' + encodeURIComponent(r.uid), {state: sent, baseRev: r.rev || 0}); }
        catch (e) { this.setStatus(navigator.onLine === false ? 'Offline · changes are kept on this computer' : "Can't save to the server · retrying"); setTimeout(() => this.schedule(), 15000); return; }
        if (res.conflict) {
          const t = res.conflict;
          if (t.deleted) { this.recs.delete(r.uid); this.touch(r.uid); continue; }
          r.state = merge3(r.base, r.state, t.state); r.base = clone(t.state); r.rev = t.rev; r.by = t.by;
          this.touch(r.uid); this.emit(); this.schedule(); continue;
        }
        r.rev = res.rev;
        if (r.version === version) { r.dirty = false; r.base = null; } else r.base = sent;
        this.touch(r.uid);
      }
    } finally { this.pushing = false; }
    this.emit();
  },
  applyRemote(list) {
    let any = false;
    for (const s of list) {
      const r = this.recs.get(s.uid);
      if (s.deleted) { if (r) { this.recs.delete(s.uid); this.touch(s.uid); any = true; } continue; }
      if (r && (r.rev || 0) >= s.rev) continue;
      if (!r) this.recs.set(s.uid, {uid: s.uid, state: s.state, rev: s.rev, updated: s.updated, by: s.by, base: null, dirty: false, version: 0});
      else { r.state = r.dirty ? merge3(r.base, r.state, s.state) : s.state; if (r.dirty) r.base = clone(s.state); r.rev = s.rev; r.updated = s.updated; r.by = s.by; }
      this.touch(s.uid); any = true;
    }
    if (any) this.emit();
  },
  async loop() {
    const gen = ++this.gen;
    while (this.signedIn() && gen === this.gen) {
      try {
        const res = await this.api('GET', '/api/students?since=' + (this.meta.rev || 0) + '&wait=25');
        if (gen !== this.gen) return;
        this.applyRemote(res.students || []);
        this.meta.rev = res.rev; this.saveMeta();
        this.fails = 0; this.lastSync = Date.now(); this.setStatus('Live');
        if (this.pending()) this.schedule();
      } catch (e) {
        if (gen !== this.gen) return;
        if (e.status === 401) { this.setStatus('Access code no longer accepted'); return; }
        this.setStatus(navigator.onLine === false ? 'Offline · changes are kept on this computer' : "Can't reach the server · retrying");
        await new Promise(r => setTimeout(r, Math.min(30000, 2000 * 2 ** this.fails++)));
      }
    }
  },
  async signIn(code, url) {
    const me = await this.api('GET', '/api/me', null, code, url);
    this.meta = {code, url, user: me.name, role: me.role, rev: 0};
    this.saveMeta();
    this.loop();
  },
  async signOut() {
    this.gen++; this.meta = {}; this.saveMeta(); this.recs.clear();
    if (this.db) await new Promise(res => { const tx = this.db.transaction('students', 'readwrite'); tx.objectStore('students').clear(); tx.oncomplete = res; tx.onerror = res; });
    this.emit();
  },
};
function merge3(base, mine, theirs) {
  if (!base) return clone(mine);
  const out = clone(theirs);
  for (const k of Object.keys(mine)) {
    if (k === 'attendance') {
      const key = a => a.date + '|' + norm(a.session);
      const bm = new Map((base.attendance || []).map(a => [key(a), a])), mm = new Map((mine.attendance || []).map(a => [key(a), a]));
      let list = (out.attendance || []).slice();
      for (const [kk, a] of mm) if (!bm.has(kk) || !same(bm.get(kk), a)) { list = list.filter(x => key(x) !== kk); list.push(clone(a)); }
      for (const kk of bm.keys()) if (!mm.has(kk)) list = list.filter(x => key(x) !== kk);
      out.attendance = list;
    } else if (k === 'lessons' || k === 'exams') {
      for (const v of mine[k]) { const b = (base[k] || []).find(x => String(x.id) === String(v.id)); if (!b || !same(b, v)) { const i = (out[k] || []).findIndex(x => String(x.id) === String(v.id)); if (i >= 0) out[k][i] = clone(v); } }
    } else if (k === 'meeting' || k === 'milestones') {
      out[k] = {...(out[k] || {})};
      for (const [kk, vv] of Object.entries(mine[k] || {})) if (!same((base[k] || {})[kk], vv)) out[k][kk] = clone(vv);
    } else if (!same(base[k], mine[k])) out[k] = clone(mine[k]);
  }
  return out;
}

// ---------- sheets ----------
function sheet(title, body, foot, mount) {
  const scrim = document.createElement('div'); scrim.className = 'scrim';
  const el = document.createElement('section'); el.className = 'sheet'; el.setAttribute('role', 'dialog'); el.setAttribute('aria-modal', 'true'); el.setAttribute('aria-label', title);
  el.innerHTML = `<header><h3>${esc(title)}</h3><button class="icon-btn" data-close aria-label="Close">${icon('close')}</button></header><div class="body">${body}</div>${foot ? `<div class="foot">${foot}</div>` : ''}`;
  document.body.append(scrim, el);
  const prev = document.activeElement;
  const close = () => { el.classList.remove('show'); scrim.classList.remove('show'); setTimeout(() => { el.remove(); scrim.remove(); }, 250); document.removeEventListener('keydown', key); if (prev && prev.focus) prev.focus(); };
  const key = e => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', key);
  scrim.onclick = close;
  el.querySelector('[data-close]').onclick = close;
  requestAnimationFrame(() => { el.classList.add('show'); scrim.classList.add('show'); const f = el.querySelector('input,select,textarea,.seg button.on,button.primary'); if (f && matchMedia('(min-width:761px)').matches) f.focus(); });
  if (mount) mount(el, close);
  return close;
}
function seg(options, current, name) {
  return `<div class="seg" data-seg="${name}">${options.map(o => `<button type="button" data-v="${esc(o)}" class="${o === current ? 'on' : ''}">${esc(o)}${icon('check')}</button>`).join('')}</div>`;
}
function wireSeg(el) {
  for (const g of el.querySelectorAll('[data-seg]')) g.onclick = e => { const b = e.target.closest('button'); if (!b) return; for (const x of g.children) x.classList.toggle('on', x === b); };
}
const segValue = (el, name) => el.querySelector(`[data-seg="${name}"] .on`)?.dataset.v;

// ---------- app frame ----------
const app = $('#app');
const ui = {query: '', course: '', sort: 'name', limit: 150, lessonFilter: 'all', searching: false};
const route = () => { const h = location.hash.replace(/^#\/?/, '').split('/'); return {page: h[0] || 'students', uid: h[1], tab: h[2] || 'overview'}; };
const go = h => { if (location.hash !== '#/' + h) location.hash = '#/' + h; else render(); };

function frame(title, sub, actions, content) {
  const r = route();
  const nav = [['students', 'Students', 'people'], ['overview', 'Overview', 'dashboard'], ['uploads', 'Uploads', 'upload'], ['account', 'Account', 'person']];
  const live = Store.status === 'Live';
  return `<div class="shell"><aside class="side"><div class="brand"><img src="logos/mas.png" alt="Moorabbin Aviation Services"><span class="sep"></span><img src="logos/anac.png" alt="Australian National Airline College"></div>
  <div class="kicker">CPL Progress Tracker</div><nav>${nav.map(([k, l, i]) => `<a href="#/${k}" class="${(r.page === k || (k === 'students' && r.page === 'student') || (k === 'uploads' && r.page === 'scan')) ? 'on' : ''}">${icon(i)}<span>${l}</span></a>`).join('')}</nav><div class="grow"></div>
  <div class="me">${avatar(Store.meta.user, 36)}<div><b>${esc(Store.meta.user || '')}</b><small>${live ? '● Live' : esc(Store.status)}</small></div></div></aside>
  <div class="main"><header class="top ${ui.searching ? 'searching' : ''}">${title}${sub ? `<span class="sub">${sub}</span>` : ''}${actions || ''}</header><main class="content" id="content">${content}</main></div></div>
  <nav class="mnav">${nav.map(([k, l, i]) => `<a href="#/${k}" class="${(r.page === k || (k === 'students' && r.page === 'student') || (k === 'uploads' && r.page === 'scan')) ? 'on' : ''}"><span>${icon(i)}</span>${l}</a>`).join('')}</nav>`;
}

function render() {
  if (!Store.signedIn()) return login();
  const r = route();
  const y = window.scrollY, same = render.last === location.hash;
  render.last = location.hash;
  if (r.page === 'student') studentPage(r.uid, r.tab);
  else if (r.page === 'overview') overview();
  else if (r.page === 'uploads') uploads();
  else if (r.page === 'scan') scanPage(r.uid);
  else if (r.page === 'report') reportPage(r.uid);
  else if (r.page === 'account') account();
  else students();
  if (same) window.scrollTo(0, y); else window.scrollTo(0, 0);
}

// ---------- sign in ----------
function login() {
  document.title = 'Sign in · CPL Tracker';
  app.innerHTML = `<div class="login"><div class="box"><div class="logos"><img src="logos/mas.png" alt="Moorabbin Aviation Services"><span class="sep"></span><img src="logos/anac.png" alt="Australian National Airline College"></div>
  <div class="kicker">Integrated Commercial Pilot Licence</div><h1>CPL Student Progress</h1><p>Training records, attendance and flight history for every student — live across the school.</p>
  <form class="card" id="loginForm"><h2>Sign in</h2><p class="muted" style="margin:4px 0 16px;color:var(--muted)">Enter the personal access code from your administrator.</p>
  <label class="field"><span>Access code</span><input id="code" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="XXXX-XXXX-XXXX-XXXX" style="letter-spacing:.08em"></label>
  <div class="err" id="err" hidden></div><button class="btn primary" style="width:100%" id="go">Sign in</button><button type="button" class="linkbtn" id="srv">Server settings</button></form>
  <div class="foot">Moorabbin Aviation Services · Australian National Airline College<br><a href="app/CPLTracker.apk" style="color:#ffffffc0">Get the Android app</a></div></div></div>`;
  let url = Store.meta.url || SERVER;
  $('#srv').onclick = () => sheet('Server', `<label class="field"><span>Server address</span><input id="srvUrl" value="${esc(url)}"></label>`, `<button class="btn primary" id="srvSave">Save</button>`, (el, close) => { el.querySelector('#srvSave').onclick = () => { url = el.querySelector('#srvUrl').value.trim(); close(); }; });
  $('#loginForm').onsubmit = async e => {
    e.preventDefault();
    const code = $('#code').value.trim();
    if (!code) { $('#err').textContent = 'Enter your access code.'; $('#err').hidden = false; return; }
    $('#go').disabled = true; $('#go').textContent = 'Signing in…';
    try { await Store.signIn(code, url); go('students'); }
    catch (err) { $('#err').textContent = err.status === 401 ? 'That access code was not accepted.' : 'Could not reach the server. Check the internet connection.'; $('#err').hidden = false; }
    finally { const g = $('#go'); if (g) { g.disabled = false; g.textContent = 'Sign in'; } }
  };
  $('#code').focus();
}

// ---------- students ----------
function students() {
  document.title = 'Students · CPL Tracker';
  const all = Store.all();
  const courses = new Map();
  const ck = r => r.state.course || '__none__';
  for (const r of all) courses.set(ck(r), (courses.get(ck(r)) || 0) + 1);
  const q = norm(ui.query);
  let rows = all.filter(r => (!ui.course || ck(r) === ui.course) && (!q || norm([r.state.name, r.state.studentId, r.state.instructor, r.state.course].join(' ')).includes(q)));
  const figs = new Map(rows.map(r => [r.uid, figures(r.state)]));
  const by = {name: (a, b) => (a.state.name || '~').localeCompare(b.state.name || '~'), updated: (a, b) => (b.updated || 0) - (a.updated || 0), progress: (a, b) => figs.get(b.uid).overall - figs.get(a.uid).overall,
    attendance: (a, b) => { const x = figs.get(a.uid).attendance, y = figs.get(b.uid).attendance; return (x < 0 ? 999 : x) - (y < 0 ? 999 : y); }, flight: (a, b) => figs.get(b.uid).lastFlight.localeCompare(figs.get(a.uid).lastFlight)};
  rows.sort(by[ui.sort] || by.name);
  const shown = rows.slice(0, ui.limit);
  const chips = `<div class="chips"><button class="chip ${ui.course ? '' : 'on'}" data-course="">All<small>${all.length}</small></button>${[...courses].sort((a, b) => b[1] - a[1]).map(([c, n]) => `<button class="chip ${c === ui.course ? 'on' : ''}" data-course="${esc(c)}">${esc(shortCourse(c === '__none__' ? '' : c))}<small>${n}</small></button>`).join('')}</div>`;
  const st = Store.status === 'Live' ? (Store.pending() ? `Saving ${Store.pending()} change${Store.pending() > 1 ? 's' : ''}…` : '') : Store.status;
  const table = `<table class="table"><thead><tr><th>Student</th><th class="c-course">Course</th><th>Progress</th><th>Last flown</th><th>Next lesson</th><th>Attendance</th></tr></thead><tbody>${shown.map(r => { const s = r.state, f = figs.get(r.uid); return `<tr data-open="${r.uid}"><td><div class="who">${avatar(s.name, 38)}<div style="min-width:0"><b>${esc(s.name || 'Unnamed student')}</b><small>${esc([s.studentId && 'ID ' + s.studentId, s.instructor].filter(Boolean).join(' · ') || '—')}</small></div></div></td><td class="c-course"><span class="muted">${esc(shortCourse(s.course))}</span></td><td><div class="prog">${bar(f.overall, tone(f.overall))}<b>${pct(f.overall)}</b></div></td><td>${f.last ? `<div class="lastf"><b>${esc(f.last)}</b><small>${auDate(f.lastDate)}</small></div>` : '<span class="muted">—</span>'}</td><td><div class="next">${f.next ? esc(f.next) : '<span class="muted">—</span>'}</div></td><td>${f.attendance < 0 ? '<span class="muted">—</span>' : `<span class="pill ${f.attendance < 80 ? 'red' : 'green'}">${pct(f.attendance)}</span>`}</td></tr>`; }).join('')}</tbody></table>`;
  const cards = `<div class="cards">${shown.map(r => { const s = r.state, f = figs.get(r.uid); return `<div class="scard" data-open="${r.uid}">${avatar(s.name, 46)}<div class="mid"><b>${esc(s.name || 'Unnamed student')}</b><small>${esc(shortCourse(s.course) + (s.instructor ? ' · ' + s.instructor : ''))}</small><div class="row"><span class="next" style="flex:1">${f.last ? 'Last: ' + esc(f.last) : 'Next: ' + esc(f.next)}</span>${f.attendance >= 0 && f.attendance < 80 ? `<span class="pill red">${pct(f.attendance)} attend.</span>` : ''}${r.dirty ? '<span class="pill amber">Saving</span>' : ''}</div></div>${ring(f.overall, 52)}</div>`; }).join('')}</div>`;
  const empty = !Store.ready || (!all.length && Store.status !== 'Live') ? `<div class="empty">${icon('people')}Loading students…</div>` : !rows.length ? `<div class="empty">${icon('search')}No students match.</div>` : '';
  const actions = `<label class="search">${icon('search')}<input id="q" type="search" placeholder="Search name, ID or instructor" value="${esc(ui.query)}" aria-label="Search students"></label>
    <button class="icon-btn" id="searchBtn" aria-label="Search" style="${ui.searching ? 'display:none' : ''}">${icon('search')}</button>
    <button class="btn gold fab" id="newBtn">${icon('add')}<span>New student</span></button>`;
  const sortSel = `<select id="sort" class="sortsel" aria-label="Sort students"><option value="name">Sort: Name</option><option value="updated">Sort: Recently updated</option><option value="progress">Sort: Progress</option><option value="attendance">Sort: Lowest attendance</option><option value="flight">Sort: Last flight</option></select>`;
  app.innerHTML = frame('<h1>Students</h1>', `${rows.length}${rows.length !== all.length ? ' of ' + all.length : ''} students`, actions,
    `<div class="toolbar">${chips}${sortSel}</div>${st ? `<div class="status">${esc(st)}</div>` : ''}${empty || table + cards}${rows.length > shown.length ? `<div class="more"><button class="btn" id="moreBtn">Show all ${rows.length}</button></div>` : ''}`);
  $('#sort').value = ui.sort;
  const qi = $('#q');
  qi.oninput = () => { ui.query = qi.value; ui.limit = 150; const pos = qi.selectionStart; students(); const n = $('#q'); n.focus(); n.setSelectionRange(pos, pos); };
  $('#searchBtn').onclick = () => { ui.searching = true; students(); $('#q').focus(); };
  if (ui.searching && matchMedia('(max-width:760px)').matches) qi.onblur = () => { if (!qi.value) { ui.searching = false; setTimeout(students, 100); } };
  $('#sort').onchange = e => { ui.sort = e.target.value; students(); };
  $('#newBtn').onclick = newStudent;
  if ($('#moreBtn')) $('#moreBtn').onclick = () => { ui.limit = 1e9; students(); };
  app.querySelector('.chips').onclick = e => { const b = e.target.closest('[data-course]'); if (b) { ui.course = b.dataset.course; ui.limit = 150; students(); } };
  app.querySelector('#content').onclick = e => { const o = e.target.closest('[data-open]'); if (o) go('student/' + o.dataset.open + '/overview'); };
}

function newStudent() {
  sheet('New student', `<label class="field"><span>Student name</span><input id="nName" autocomplete="off" placeholder="Full name"></label><label class="field"><span>Student ID / ARN</span><input id="nId" placeholder="Optional"></label><label class="field"><span>Instructor</span><input id="nIns" placeholder="Optional"></label><label class="field"><span>Course</span><input id="nCourse" value="MAS Commercial Pilot Licence - CPL (150hr integrated)"></label>`,
    `<button class="btn" data-x>Cancel</button><button class="btn primary" id="nGo">Create student</button>`, (el, close) => {
      el.querySelector('[data-x]').onclick = close;
      el.querySelector('#nGo').onclick = () => {
        const n = el.querySelector('#nName').value.trim();
        if (!n) { el.querySelector('#nName').focus(); toast('Enter the student’s name.'); return; }
        const s = blank(n); s.studentId = el.querySelector('#nId').value.trim(); s.instructor = el.querySelector('#nIns').value.trim(); s.course = el.querySelector('#nCourse').value.trim();
        const uid = Store.create(s); close(); go('student/' + uid + '/overview');
      };
    });
}

// ---------- one student ----------
function studentPage(uid, tab) {
  const r = Store.get(uid);
  if (!r) { if (!Store.ready || Store.status !== 'Live') { app.innerHTML = frame('<h1>Student</h1>', '', '', `<div class="empty">${icon('people')}Loading…</div>`); return; } go('students'); return; }
  const s = r.state, f = figures(s);
  document.title = (s.name || 'Student') + ' · CPL Tracker';
  const tabs = [['overview', 'Overview'], ['lessons', 'Lessons'], ['exams', 'Exams'], ['attendance', 'Attendance'], ['flights', 'Flights']];
  const low = f.attendance >= 0 && f.attendance < 80;
  const hero = `<section class="hero"><div class="head">${avatar(s.name, 64)}<div style="min-width:0"><h2>${esc(s.name || 'Unnamed student')}</h2><div class="meta">${esc([shortCourse(s.course), s.studentId && 'ID ' + s.studentId].filter(Boolean).join(' · '))}</div>${s.instructor ? `<div class="meta">Instructor ${esc(s.instructor)}</div>` : ''}</div>
    <div style="margin-left:auto">${ring(f.overall, 76, '#d9bf8c', '#ffffff30')}</div></div>
    <div class="tiles"><div class="tile"><b>${pct(f.practical)}</b><small>Practical</small></div><div class="tile"><b>${pct(f.theory)}</b><small>Theory</small></div><div class="tile ${low ? 'warn' : ''}"><b>${f.attendance < 0 ? '—' : pct(f.attendance)}</b><small>Attendance</small></div><div class="tile"><b>${f.hours.toFixed(0)}</b><small>Hours flown</small></div></div></section>
    <nav class="tabs">${tabs.map(([k, l]) => `<a href="#/student/${uid}/${k}" class="${k === tab ? 'on' : ''}">${l}</a>`).join('')}</nav>`;
  const body = tab === 'lessons' ? lessonsTab(s) : tab === 'exams' ? examsTab(s) : tab === 'attendance' ? attendanceTab(s, f) : tab === 'flights' ? flightsTab(s) : overviewTab(r, s, f);
  app.innerHTML = frame(`<button class="icon-btn" onclick="history.length>1?history.back():location.hash='#/students'" aria-label="Back" style="margin-left:-8px">${icon('back')}</button><h1>${esc(s.name || 'Student')}</h1>`, r.dirty ? 'Saving…' : 'Updated ' + ago(r.updated) + (r.by ? ' by ' + esc(r.by) : ''), `<button class="btn ghost" id="editBtn" title="Edit details">${icon('edit')}<span class="wide">Edit</span></button><button class="btn ghost" id="reportBtn" title="Attendance and progress report (PDF)">${icon('report')}<span class="wide">Report</span></button>${Store.meta.role === 'admin' ? `<button class="icon-btn" id="delBtn" aria-label="Remove student" title="Remove student">${icon('delete')}</button>` : ''}`, hero + body);
  $('#editBtn').onclick = () => editDetails(uid);
  $('#reportBtn').onclick = () => go('report/' + uid);
  if ($('#delBtn')) $('#delBtn').onclick = async () => { if (!confirm('Remove ' + (s.name || 'this student') + ' from the shared database for everyone?')) return; try { await Store.remove(uid); toast('Student removed.'); go('students'); } catch (e) { toast(e.message); } };
  const c = $('#content');
  c.onclick = e => {
    const t = e.target.closest('[data-lesson],[data-exam],[data-att],[data-ms],[data-filter],[data-addatt],[data-gotab]');
    if (!t) return;
    if (t.dataset.lesson) editLesson(uid, t.dataset.lesson);
    else if (t.dataset.exam) editExam(uid, t.dataset.exam);
    else if (t.dataset.att !== undefined) editAttendance(uid, Number(t.dataset.att));
    else if (t.dataset.addatt !== undefined) editAttendance(uid, -1);
    else if (t.dataset.ms) editMilestone(uid, Number(t.dataset.ms));
    else if (t.dataset.filter) { ui.lessonFilter = t.dataset.filter; render(); }
    else if (t.dataset.gotab) { if (t.dataset.gotab === 'lessons') ui.lessonFilter = 'todo'; go('student/' + uid + '/' + t.dataset.gotab); }
  };
}

function overviewTab(r, s, f) {
  const share = Number.isFinite(s.share) ? s.share : 60;
  const nextId = ORDER.find(id => f.next.startsWith(id + '. '));
  const meter = (l, v, c) => `<div class="meter"><div class="t"><span>${l}</span><b>${pct(v)}</b></div>${bar(v, c)}</div>`;
  const ms = MILESTONES.map(m => { const x = milestone(s, m); return `<div class="ms" data-ms="${m}"><div class="t"><b>After ${esc(label(String(m)))}</b><small>Allowance ${hrs(x.planned)}${x.accrued === null ? ' · hours not entered' : ' · flown ' + hrs(x.accrued)}</small></div>${x.accrued === null ? '' : x.overfly > 0 ? `<span class="pill amber">+${hrs(x.overfly)} · ${money(x.cost)}</span>` : '<span class="pill green">Within allowance</span>'}${icon('chevron')}</div>`; }).join('');
  return `<div class="grid g2">
   <div class="card"><h2>Course progress</h2><div class="sub">${share.toFixed(0)}% practical + ${(100 - share).toFixed(0)}% theory</div>${meter('Overall', f.overall, '#0b2a4f')}${meter('Practical · ' + f.done + ' of ' + ORDER.length + ' lessons', f.practical, '#16627a')}${meter('Theory · exams passed', f.theory, '#b8955a')}</div>
   <div class="grid">${f.last ? `<div class="card nextcard" data-gotab="flights">${icon('flight')}<div style="flex:1;min-width:0"><div class="label">Last flown · ${auDate(f.lastDate)}</div><b style="font-size:17px">${esc(f.last)}</b><div class="muted" style="font-size:13px">${f.flights} flights · ${hrs(f.hours)} in total</div></div>${icon('chevron')}</div>` : ''}
   ${f.next ? '' : '<!--'}<div class="card nextcard" data-gotab="lessons">${icon('school')}<div style="flex:1;min-width:0"><div class="label">Next lesson</div><b style="font-size:17px">${esc(f.next)}</b>${nextId ? `<div class="muted" style="font-size:13px">${esc(LESSONS[nextId].duration + ' · ' + LESSONS[nextId].mode)}</div>` : ''}${f.gaps ? `<div style="font-size:13px;color:var(--amber);font-weight:600;margin-top:2px">${f.gaps} earlier lesson${f.gaps > 1 ? 's' : ''} not marked complete</div>` : ''}</div>${icon('chevron')}</div>${f.next ? '' : '-->'}
   ${f.flights ? `<div class="card"><h2>Flying</h2><div class="minis"><div><b>${f.flights}</b><small>Flights</small></div><div><b>${hrs(f.hours)}</b><small>Total</small></div><div><b>${hrs(f.solo)}</b><small>Solo</small></div><div><b>${hrs(f.ifr)}</b><small>Instrument</small></div></div><div class="kv" style="margin-top:10px;border-top:1px solid var(--line2)"><span>Last flight</span><b>${auDate(f.lastFlight)}</b></div></div>` : ''}</div>
   <div class="card"><h2>Hour reviews</h2><div class="sub">Aircraft hours at each milestone against the syllabus allowance. Overfly is estimated at $500/h.</div><div style="margin-top:6px">${ms}</div></div>
   <div class="card"><h2>Details</h2><div style="margin-top:8px">${[['Student ID / ARN', s.studentId], ['Course', s.course], ['Instructor', s.instructor], ['Course start', auDate(s.start)], ['Last updated', ago(r.updated) + (r.by ? ' by ' + r.by : '')]].map(([k, v]) => `<div class="kv"><span>${k}</span><b>${esc(v || '—')}</b></div>`).join('')}</div></div>
  </div>`;
}

const dot = st => `<span class="dot ${st === 'Completed' || st === 'Passed' ? 'done' : st === 'In progress' ? 'prog' : st === 'Failed' ? 'fail' : ''}">${st === 'Completed' || st === 'Passed' ? `<svg viewBox="0 0 24 24"><path d="${ICON.check}"/></svg>` : st === 'Failed' ? `<svg viewBox="0 0 24 24"><path d="${ICON.close}"/></svg>` : ''}</span>`;

function lessonsTab(s) {
  let todo = 0, prog = 0, done = 0;
  for (const v of s.lessons) v.status === 'Completed' ? done++ : v.status === 'In progress' ? prog++ : todo++;
  const fl = ui.lessonFilter;
  const show = v => fl === 'all' || (fl === 'todo' && v.status !== 'Completed') || (fl === 'progress' && v.status === 'In progress') || (fl === 'done' && v.status === 'Completed');
  let last = '', out = '';
  for (const v of s.lessons) {
    if (!show(v)) continue;
    const id = String(v.id), l = LESSONS[id], ph = phase(id);
    if (ph !== last) { out += `<div class="phase label">${ph}</div>`; last = ph; }
    const notes = String(v.notes || ''), smart = (notes.split('\n').find(x => x.startsWith('Smart: ')) || '').slice(7), other = notes.replace(/^Smart: .*$/gm, '').trim();
    out += `<div class="li" data-lesson="${esc(id)}">${dot(v.status)}<div class="t"><b>${esc(label(id))}</b><small>${esc((l ? l.duration + ' · ' + l.mode : '') + (smart ? ' · ' + smart : ''))}</small>${other ? `<em>${esc(other.replace(/\n/g, ' '))}</em>` : ''}</div>${v.status === 'In progress' ? '<span class="pill amber">In progress</span>' : ''}${icon('chevron')}</div>`;
  }
  return `<div class="chips">${[['all', 'All', todo + prog + done], ['todo', 'To do', todo + prog], ['progress', 'In progress', prog], ['done', 'Completed', done]].map(([k, l, n]) => `<button class="chip ${k === fl ? 'on' : ''}" data-filter="${k}">${l}<small>${n}</small></button>`).join('')}</div><div class="list">${out || '<div class="empty">Nothing here.</div>'}</div>`;
}

function examsTab(s) {
  return `<div class="list">${(s.exams || []).map(v => `<div class="li" data-exam="${esc(v.id)}">${dot(v.status)}<div class="t"><b>${esc(v.id + (EXAM_NAMES[v.id] ? ' · ' + EXAM_NAMES[v.id] : ''))}</b><small>Weight ${v.weight}%${v.examDate ? ' · ' + auDate(v.examDate) : ''}${(v.attempts || []).length > 1 ? ' · ' + v.attempts.length + ' attempts' : ''}</small>${v.notes ? `<em>${esc(v.notes)}</em>` : ''}</div>${v.status === 'Passed' ? '<span class="pill green">Passed</span>' : v.status === 'Failed' ? '<span class="pill red">Failed</span>' : ''}${icon('chevron')}</div>`).join('')}</div>`;
}

function attendanceTab(s, f) {
  const items = (s.attendance || []).map((a, i) => ({...a, i})).concat((s.flights || []).map(x => ({date: x.date, session: 'Flight · ' + (LESSONS[x.lesson] ? label(x.lesson) : x.raw || 'Flight'), status: 'Present', notes: x.instructor || (x.solo ? 'Solo' : ''), flight: true})));
  items.sort((a, b) => (b.date || '').localeCompare(a.date || ''));
  const low = f.attendance >= 0 && f.attendance < 80;
  const cls = st => st === 'Present' ? 'green' : st === 'Late' ? 'amber' : st === SICK ? '' : 'red';
  return `<div class="card attsum">${f.attendance < 0 ? ring(0, 84).replace(/>0%</, '>—<') : ring(f.attendance, 84, low ? '#b42318' : '#1f7a4d')}<div style="flex:1"><b style="font-size:18px">${f.sessions} sessions</b><div class="pills"><span class="pill green">${f.present} present</span><span class="pill amber">${f.late} late</span><span class="pill red">${f.absent} absent</span>${f.sick ? `<span class="pill">${f.sick} sick with certificate</span>` : ''}</div>${low ? '<div class="warnline">Below 80% — follow up with the student</div>' : ''}<div class="muted" style="font-size:12.5px;margin-top:8px">${f.hoursRate >= 0 ? 'Attendance is hours on campus and flying against 20 hours a week, from the Aviation 1 in 60 record and flights (see the report). ' : f.attendance < 0 ? 'No attendance record yet: upload the student’s Aviation 1 in 60 report to get their attendance. ' : ''}Sessions below: flights count as attended; sick counts only with a medical certificate; sick without one, excused and no-shows count as absent.</div></div><button class="btn primary" data-addatt>${icon('add')}<span>Record attendance</span></button></div>
  <div class="list" style="margin-top:14px">${items.length ? items.map(a => `<div class="li ${a.flight ? 'static' : ''}" ${a.flight ? '' : `data-att="${a.i}"`}><div class="date"><b>${(a.date || '').slice(8, 10)}</b><small>${monthOf(a.date)}</small></div><div class="t"><b style="font-weight:${a.flight ? 500 : 600}">${esc(a.session)}</b>${a.notes ? `<small>${esc(a.notes)}</small>` : ''}</div><span class="pill ${cls(a.status)}">${esc(a.status === SICK ? 'Sick · certificate' : a.status === 'Excused' ? 'Absent' : a.status)}</span></div>`).join('') : '<div class="empty">No attendance recorded yet.</div>'}</div>`;
}
const monthOf = iso => { const m = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'][Number((iso || '').slice(5, 7)) - 1]; return m ? m + ' ' + iso.slice(2, 4) : ''; };

function flightsTab(s) {
  const items = (s.flights || []).slice().sort((a, b) => (b.date || '').localeCompare(a.date || ''));
  if (!items.length) return `<div class="empty">${icon('flight')}No flights yet. Upload a Smart flight activity report to fill in this student’s flying.</div>`;
  return `<div class="list">${items.map(x => `<div class="li static"><div class="date"><b>${(x.date || '').slice(8, 10)}</b><small>${monthOf(x.date)}</small></div><div class="t"><b>${esc(LESSONS[x.lesson] ? label(x.lesson) : x.raw || 'Flight')}</b><small>${esc([x.solo ? 'Solo' : x.instructor, x.reg].filter(Boolean).join(' · '))}</small></div><span class="pill ${x.solo ? '' : 'navy'}">${hrs(x.hours)}</span></div>`).join('')}</div>`;
}

// ---------- editing ----------
function editLesson(uid, id) {
  const v = Store.get(uid).state.lessons.find(x => String(x.id) === id), l = LESSONS[id];
  sheet(label(id), `${l ? `<p class="muted" style="margin:0 0 14px">${esc(l.duration + ' · ' + l.mode)}</p>` : ''}${seg(['Not started', 'In progress', 'Completed'], v.status, 'st')}<label class="field" style="margin-top:14px"><span>Notes</span><textarea id="lNotes">${esc(v.notes || '')}</textarea></label>`,
    `<button class="btn" data-x>Cancel</button><button class="btn primary" id="lSave">Save</button>`, (el, close) => {
      wireSeg(el); el.querySelector('[data-x]').onclick = close;
      el.querySelector('#lSave').onclick = () => { const st = segValue(el, 'st'), n = el.querySelector('#lNotes').value; Store.edit(uid, s => { const t = s.lessons.find(x => String(x.id) === id); t.status = st; t.notes = n; }); close(); toast('Saved'); };
    });
}
function editExam(uid, id) {
  const v = Store.get(uid).state.exams.find(x => x.id === id);
  sheet(id + (EXAM_NAMES[id] ? ' · ' + EXAM_NAMES[id] : ''), `${seg(['Not attempted', 'Passed', 'Failed'], v.status, 'st')}<label class="field" style="margin-top:14px"><span>Exam date</span><input type="date" id="eDate" value="${esc(v.examDate || '')}"></label><label class="field"><span>Notes</span><textarea id="eNotes">${esc(v.notes || '')}</textarea></label>${(v.attempts || []).length ? `<div class="label" style="margin-top:4px">Attempts</div>${v.attempts.map(a => `<div class="kv"><span>${auDate(a.date) || 'No date'}</span><b>${esc(a.result)}</b></div>`).join('')}` : ''}`,
    `<button class="btn" data-x>Cancel</button><button class="btn primary" id="eSave">Save</button>`, (el, close) => {
      wireSeg(el); el.querySelector('[data-x]').onclick = close;
      el.querySelector('#eSave').onclick = () => { const st = segValue(el, 'st'), d = el.querySelector('#eDate').value, n = el.querySelector('#eNotes').value; Store.edit(uid, s => { const t = s.exams.find(x => x.id === id); t.examDate = d; t.notes = n; setExamResult(t, st); }); close(); toast('Saved'); };
    });
}
function editAttendance(uid, i) {
  const a = i >= 0 ? Store.get(uid).state.attendance[i] : null;
  const st = a ? (a.status === 'Excused' ? 'Absent' : a.status) : 'Present';
  sheet(a ? 'Attendance' : 'Record attendance', `<label class="field"><span>Date</span><input type="date" id="aDate" value="${esc(a ? a.date : today())}"></label><label class="field"><span>Session</span><input id="aSes" list="sesList" value="${esc(a ? a.session : 'Theory class')}"></label><datalist id="sesList"><option>Theory class</option><option>Ground school</option><option>Briefing</option><option>Flight</option><option>Simulator</option><option>Exam</option></datalist>${seg(['Present', 'Late', 'Absent', SICK], st, 'st')}<label class="field" style="margin-top:14px"><span>Notes</span><input id="aNotes" value="${esc(a ? a.notes || '' : '')}" placeholder="Reason, certificate details…"></label>`,
    `${a ? '<button class="btn danger" id="aDel">Delete</button>' : '<button class="btn" data-x>Cancel</button>'}<button class="btn primary" id="aSave">Save</button>`, (el, close) => {
      wireSeg(el); if (el.querySelector('[data-x]')) el.querySelector('[data-x]').onclick = close;
      el.querySelector('#aSave').onclick = () => {
        const v = {date: el.querySelector('#aDate').value || today(), session: el.querySelector('#aSes').value.trim() || 'Theory class', status: segValue(el, 'st'), notes: el.querySelector('#aNotes').value.trim()};
        Store.edit(uid, s => { if (!Array.isArray(s.attendance)) s.attendance = []; if (i >= 0) s.attendance[i] = {...s.attendance[i], ...v}; else s.attendance.push({...v, hours: ''}); });
        close(); toast('Saved');
      };
      if (el.querySelector('#aDel')) el.querySelector('#aDel').onclick = () => { if (confirm('Delete this attendance record?')) { Store.edit(uid, s => s.attendance.splice(i, 1)); close(); } };
    });
}
function editMilestone(uid, id) {
  const m = milestone(Store.get(uid).state, id);
  sheet('Hour review · lesson ' + id, `<p class="muted" style="margin:0 0 14px">Cumulative aircraft hours (no simulator) by the review after ${esc(label(String(id)))}.</p><label class="field"><span>Syllabus allowance (h)</span><input id="mA" inputmode="decimal" value="${m.planned}"></label><label class="field"><span>Hours flown (h)</span><input id="mH" inputmode="decimal" value="${m.accrued ?? ''}"></label>`,
    `<button class="btn" data-x>Cancel</button><button class="btn primary" id="mSave">Save</button>`, (el, close) => {
      el.querySelector('[data-x]').onclick = close;
      el.querySelector('#mSave').onclick = () => { const a = el.querySelector('#mA').value.trim(), h = el.querySelector('#mH').value.trim(); Store.edit(uid, s => { s.milestones = s.milestones || {}; s.milestones[id] = {...(s.milestones[id] || {}), allowance: a, accrued: h}; }); close(); toast('Saved'); };
    });
}
function editDetails(uid) {
  const s = Store.get(uid).state;
  sheet('Student details', `<label class="field"><span>Student name</span><input id="dName" value="${esc(s.name)}"></label><label class="field"><span>Student ID / ARN</span><input id="dId" value="${esc(s.studentId)}"></label><label class="field"><span>Instructor</span><input id="dIns" value="${esc(s.instructor)}"></label><label class="field"><span>Course</span><input id="dCourse" value="${esc(s.course)}"></label><label class="field"><span>Course start</span><input type="date" id="dStart" value="${esc(s.start)}"></label>`,
    `<button class="btn" data-x>Cancel</button><button class="btn primary" id="dSave">Save</button>`, (el, close) => {
      el.querySelector('[data-x]').onclick = close;
      el.querySelector('#dSave').onclick = () => {
        const n = el.querySelector('#dName').value.trim(); if (!n) { toast('Enter the name.'); return; }
        Store.edit(uid, x => { x.name = n; x.studentId = el.querySelector('#dId').value.trim(); x.instructor = el.querySelector('#dIns').value.trim(); x.course = el.querySelector('#dCourse').value.trim(); x.start = el.querySelector('#dStart').value; });
        close(); toast('Saved');
      };
    });
}
// Reports, Excel and email still live in the classic tracker; it signs in with the same code and opens this student.
function openClassic(uid) {
  try {
    const m = JSON.parse(localStorage.getItem('mas-cpl-roster-meta') || 'null') || {};
    m.current = uid;
    if (!m.server || m.server.code !== Store.meta.code) m.server = {url: Store.meta.url || SERVER, code: Store.meta.code, user: Store.meta.user, role: Store.meta.role, rev: 0};
    localStorage.setItem('mas-cpl-roster-meta', JSON.stringify(m));
  } catch (e) { }
  window.open('classic/#student=' + uid, '_blank');
}

// ---------- attendance and progress report (report.js, shared with the Android app) ----------
function reportPage(uid) {
  const r = Store.get(uid);
  if (!r) { if (!Store.ready || Store.status !== 'Live') { app.innerHTML = frame('<h1>Report</h1>', '', '', `<div class="empty">Loading…</div>`); return; } go('students'); return; }
  if (!document.getElementById('reportCss')) { const st = document.createElement('style'); st.id = 'reportCss'; st.textContent = CPLReport.css + '\n@media print{body>*:not(#reportPrint){display:none!important}#reportPrint{display:block!important}#reportPrint .rpt{width:auto;margin:0;padding:0;box-shadow:none}}#reportPrint{display:none}'; document.head.append(st); }
  const o = reportPage.opts = reportPage.uid === uid ? reportPage.opts : {progress: true};
  reportPage.uid = uid;
  const s = r.state, rep = CPLReport.render(s, CAT, {...o, today: today()});
  document.title = rep.file;
  const back = `<button class="icon-btn" onclick="history.length > 1 ? history.back() : location.hash = '#/student/${uid}'" aria-label="Back" style="margin-left:-8px">${icon('back')}</button>`;
  const rec = (s.records || {}).oneIn60;
  app.innerHTML = frame(back + `<h1>${esc(s.name || 'Student')}</h1>`, 'Attendance and progress report', `<button class="btn ghost" id="classicBtn" title="Classic progress report, Excel and email">${icon('file')}<span class="wide">Classic</span></button><button class="btn primary" id="printBtn">${icon('report')}<span>Save PDF / Print</span></button>`, `
    <div class="card rtools"><label class="field"><span>From</span><input type="date" id="rFrom" value="${rep.from}"></label><label class="field"><span>To</span><input type="date" id="rTo" value="${rep.to}"></label>
     <label class="check"><input type="checkbox" id="rProg" ${o.progress ? 'checked' : ''}> Include training progress</label>
     <div class="muted" style="font-size:12.5px;flex-basis:100%">${rec ? `Attendance record loaded: ${esc(auDate(rec.from))} to ${esc(auDate(rec.to))}.` : 'No Aviation 1 in 60 attendance record is loaded for this student yet, so weeks show as “No record”. Upload it on the Uploads page.'} Flights come from Smart flight reports; upload the Smart event report and cancellation report to add those sections.</div></div>
    <div class="rwrap"><div class="rscale">${rep.html}</div></div>`);
  const upd = () => { o.from = $('#rFrom').value || undefined; o.to = $('#rTo').value || undefined; if (o.from && o.to && o.from > o.to) return toast('“From” must be before “To”.'); o.progress = $('#rProg').checked; reportPage(uid); };
  $('#rFrom').onchange = upd; $('#rTo').onchange = upd; $('#rProg').onchange = upd;
  $('#classicBtn').onclick = () => openClassic(uid);
  $('#printBtn').onclick = () => { let p = document.getElementById('reportPrint'); if (!p) { p = document.createElement('div'); p.id = 'reportPrint'; document.body.append(p); } p.innerHTML = rep.html; document.title = rep.file; setTimeout(() => window.print(), 50); };
  const fit = () => { const w = $('.rwrap'), sc = $('.rscale'); if (!w || !sc) return; const k = Math.min(1, w.clientWidth / sc.scrollWidth); sc.style.transform = k < 1 ? `scale(${k})` : ''; w.style.height = k < 1 ? sc.scrollHeight * k + 'px' : ''; };
  requestAnimationFrame(fit); window.onresize = fit;
}

// ---------- overview ----------
function overview() {
  document.title = 'Overview · CPL Tracker';
  const all = Store.all(), t = today(), month = t.slice(0, 8) + '01';
  const d90 = new Date(Date.now() - 90 * 864e5).toISOString().slice(0, 10);
  let active = 0, mf = 0, mh = 0;
  const low = [], over = [], courses = new Map();
  for (const r of all) {
    const f = figures(r.state), isActive = f.lastFlight >= d90;
    if (isActive) active++;
    for (const x of r.state.flights || []) if ((x.date || '') >= month) { mf++; mh += x.hours || 0; }
    if (isActive && f.attendance >= 0 && f.attendance < 80) low.push([r, f.attendance]);
    if (isActive) { let c = 0; for (const m of MILESTONES) { const x = milestone(r.state, m); if (x.cost) c = Math.max(c, x.cost); } if (c > 0) over.push([r, c]); }
    const c = shortCourse(r.state.course); courses.set(c, (courses.get(c) || 0) + 1);
  }
  low.sort((a, b) => a[1] - b[1]); over.sort((a, b) => b[1] - a[1]);
  const recent = all.slice().sort((a, b) => (b.updated || 0) - (a.updated || 0)).slice(0, 6).map(r => [r, r]);
  const h = new Date().getHours(), first = (Store.meta.user || '').split(' ')[0];
  const sect = (title, sub, rows, fmt, cls) => `<div class="card"><div style="display:flex;align-items:center;gap:8px"><h2 style="flex:1">${title}</h2>${rows.length && cls ? `<span class="pill ${cls}">${rows.length}</span>` : ''}</div>${sub ? `<div class="sub">${sub}</div>` : ''}<div style="margin-top:8px">${rows.length ? rows.slice(0, 6).map(([r, v]) => `<div class="prow" data-open="${r.uid}">${avatar(r.state.name, 36)}<div class="t"><b>${esc(r.state.name)}</b><small>${esc(shortCourse(r.state.course) + (!cls && r.by ? ' · by ' + r.by : ''))}</small></div><span class="pill ${cls || ''}">${fmt(v)}</span></div>`).join('') : '<div class="muted" style="padding:8px 0">Nothing to show.</div>'}</div></div>`;
  const cs = [...courses].sort((a, b) => b[1] - a[1]).slice(0, 8), max = cs.length ? cs[0][1] : 1;
  app.innerHTML = frame('<h1>Overview</h1>', 'Across all students', '', `
   <section class="welcome"><h2>${h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'}${first ? ', ' + esc(first) : ''}</h2><p>Here’s where the school stands today.</p>
   <div class="stats"><div><b>${all.length}</b><small>Students</small></div><div><b>${active}</b><small>Active (90 days)</small></div><div><b>${mf}</b><small>Flights this month</small></div><div class="gold"><b>${mh.toFixed(0)} h</b><small>Flown this month</small></div></div></section>
   <div class="grid g3" style="margin-top:16px">${sect('Attendance below 80%', 'Active students only', low, pct, 'red')}${sect('Over syllabus hours', 'Highest estimated overfly at an hour review', over, money, 'amber')}${sect('Recently updated', '', recent, r => ago(r.updated), '')}</div>
   <div class="card" style="margin-top:16px"><h2>Courses</h2>${cs.map(([c, n]) => `<div class="cbar"><div class="t"><span>${esc(c)}</span><b>${n}</b></div>${bar(n * 100 / max)}</div>`).join('')}</div>`);
  $('#content').onclick = e => { const o = e.target.closest('[data-open]'); if (o) go('student/' + o.dataset.open + '/overview'); };
}

// ---------- uploads ----------
const importer = makeImporter({
  records: () => Store.all().map(r => ({uid: r.uid, state: clone(r.state)})),
  current: () => null,
  course: {CAT, label, figures, prepare, blank, setExamResult},
  toast,
  done(R, touched) {
    let first = null;
    for (const uid of touched) {
      const r = R.students[uid];
      if (r.isNew) { const id = Store.create(r.state); first = first || id; } else Store.replace(uid, r.state);
    }
  },
});
function uploads() {
  document.title = 'Uploads · CPL Tracker';
  app.innerHTML = frame('<h1>Uploads</h1>', 'Fill in records from your files', '', `
   <div class="drop" id="drop" tabindex="0" role="button" aria-label="Choose files to upload">${icon('upload')}<h2>Drop files here, or click to choose</h2><div class="muted">Everything is shown for review first. Then it’s saved to the shared database for everyone.</div><input type="file" id="files" multiple hidden></div>
   <div id="reviewSlot"></div>
   <div class="card claude" style="margin-top:16px"><div class="ch">${icon('spark')}<div><h2>Read with Claude</h2><div class="sub">Any other file: a screenshot, photo, PDF, Excel or CSV. Claude reads it, matches each person to a student and lists the changes for you to approve. Nothing is saved until you approve it.</div></div><button class="btn gold" id="scanBtn">${icon('upload')}<span>Choose a file</span></button><input type="file" id="scanFile" accept="image/*,.pdf,.csv,.txt,.tsv,.xlsx,.json" hidden></div>
    <div id="scans" class="scans"></div></div>
   <div class="card" style="margin-top:16px"><h2>What you can upload</h2><div class="kinds">
    ${[['Aviation 1 in 60 attendance report (.pdf)', 'Fingerprint days, weekly hours, course sessions and missed sessions, for the attendance report. Read on the server in seconds.'], ['Smart event report (.pdf) and cancellation report (.csv)', 'Bookings before the attendance record, and every cancellation with its reason.'], ['Smart flight activity report (.csv)', 'Every flight: lessons, hours, instructors and hour reviews. Any date range; flights already imported are skipped.'], ['Smart event cancellation report (.csv)', 'Student-caused cancellations become attendance. Sick counts only with a medical certificate.'], ['Smart exports and your own spreadsheets', 'CSV or Excel. Columns are matched automatically and you can change them before applying.'], ['Tracker backups and PDF records', 'Excel/JSON/HTML backups from the tracker, and text PDF training records (not scans).']].map(([b, s]) => `<div class="kind">${icon('file')}<div><b>${b}</b><small>${s}</small></div></div>`).join('')}</div></div>`);
  $('#reviewSlot').append(importer.reviewEl);
  const drop = $('#drop'), files = $('#files');
  drop.onclick = () => files.click();
  drop.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); files.click(); } };
  files.onchange = () => { const f = [...files.files]; files.value = ''; routeUpload(f); };
  for (const ev of ['dragenter', 'dragover']) drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add('over'); });
  for (const ev of ['dragleave', 'drop']) drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove('over'); });
  drop.addEventListener('drop', e => routeUpload([...e.dataTransfer.files]));
  const sf = $('#scanFile');
  $('#scanBtn').onclick = () => sf.click();
  sf.onchange = () => { const f = sf.files[0]; sf.value = ''; if (f) Scans.upload(f); };
  Scans.list();
}

// PDFs (Aviation 1 in 60 reports, Smart event reports) are read on the server; Smart cancellation reports go to both:
// here they become attendance, on the server their reasons are kept for the attendance report.
async function routeUpload(list) {
  const local = [];
  for (const f of list) {
    const ext = (f.name.split('.').pop() || '').toLowerCase();
    if (ext === 'pdf') { Scans.upload(f); continue; }
    if (ext === 'csv') { try { const head = (await f.slice(0, 800).text()).toLowerCase(); if (head.includes('cancellation type') && head.includes('event type')) Scans.upload(f); } catch (e) { } }
    local.push(f);
  }
  if (local.length) importer.upload(local);
}

// ---------- Claude scans ----------
const MIME = {png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif', pdf: 'application/pdf', csv: 'text/csv', txt: 'text/plain', tsv: 'text/tab-separated-values', json: 'application/json', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'};
const Scans = {
  items: [], timer: null,
  async upload(f) {
    const mime = MIME[(f.name.split('.').pop() || '').toLowerCase()] || f.type;
    if (!Object.values(MIME).includes(mime)) return toast('Claude can read photos, screenshots, PDFs, Excel (.xlsx) and CSV files.');
    if (f.size > 15 * 1024 * 1024) return toast('That file is over 15 MB.');
    toast('Uploading ' + f.name + '…');
    try {
      const data = await new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(',')[1] || ''); r.onerror = () => rej(r.error); r.readAsDataURL(f); });
      await Store.api('POST', '/api/scan', {name: f.name, mime, data});
      toast('Reading ' + f.name + '…');
      this.list();
    } catch (e) { toast(e.message || 'Upload failed.'); }
  },
  async list() {
    try { this.items = (await Store.api('GET', '/api/scans')).scans || []; } catch (e) { }
    const box = $('#scans'); if (!box) return;
    box.innerHTML = this.items.length ? this.items.map(j => {
      const st = j.status === 'reading' ? '<span class="pill navy"><span class="spin"></span>Reading…</span>' : j.status === 'ready' ? `<span class="pill amber">Review ${j.count} change${j.count === 1 ? '' : 's'}</span>` : j.status === 'applied' ? `<span class="pill green">Saved ${j.applied || 0}</span>` : j.status === 'failed' ? '<span class="pill red">Could not read</span>' : '<span class="pill">Discarded</span>';
      return `<div class="li ${j.status === 'ready' || j.status === 'applied' ? '' : 'static'}" data-scan="${j.id}">${icon('file')}<div class="t"><b>${esc(j.name)}</b><small>${esc(j.error || j.summary || '')}</small><small>${esc(j.by)} · ${ago(j.created)}</small></div>${st}</div>`;
    }).join('') : '<div class="muted" style="padding:12px 2px 0">No files read yet.</div>';
    box.onclick = e => { const x = e.target.closest('[data-scan]'); const j = x && this.items.find(i => i.id === x.dataset.scan); if (j && (j.status === 'ready' || j.status === 'applied')) go('scan/' + j.id); };
    clearTimeout(this.timer);
    if (this.items.some(j => j.status === 'reading')) this.timer = setTimeout(() => { if (route().page === 'uploads') this.list(); }, 3000);
  },
};

async function scanPage(id) {
  document.title = 'Review · CPL Tracker';
  const back = `<button class="icon-btn" onclick="history.length > 1 ? history.back() : location.hash = '#/uploads'" aria-label="Back">${icon('back')}</button>`;
  if (scanPage.job?.id !== id) {
    app.innerHTML = frame(back + '<h1>Review</h1>', '', '', '<div class="empty"><span class="spin big"></span><div>Loading…</div></div>');
    try { scanPage.job = await Store.api('GET', '/api/scan/' + id); scanPage.pick = null; } catch (e) { app.innerHTML = frame(back + '<h1>Review</h1>', '', '', `<div class="empty">${esc(e.message)}</div>`); return; }
    if (route().page !== 'scan') return;
  }
  const j = scanPage.job, done = j.status !== 'ready';
  const names = new Map(Store.all().map(r => [r.uid, r.state.name]));
  const opts = [...names].sort((a, b) => a[1].localeCompare(b[1]));
  if (!scanPage.pick) {
    scanPage.pick = new Set(j.changes.filter(c => !c.problem && c.confidence !== 'low' && c.uid).map(c => c.i));
    scanPage.assign = {};
  }
  const pick = scanPage.pick, assign = scanPage.assign;
  const groups = new Map();
  for (const c of j.changes) { const k = c.uid || 'name:' + norm(c.name_in_document); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(c); }
  const row = c => {
    const bad = c.problem && c.problem !== 'already recorded';
    const dis = done || !!c.problem;
    if (done && c.saved) c.confidence = 'saved';
    const when = c.date ? auDate(c.date) : '';
    return `<label class="chg ${c.problem ? 'off' : ''}"><input type="checkbox" data-i="${c.i}" ${(done ? c.saved : pick.has(c.i)) ? 'checked' : ''} ${dis ? 'disabled' : ''}>
     <div class="t"><b>${esc(c.label)}</b><div class="ba">${c.kind === 'attendance' ? esc(c.before) + ' · ' : c.before ? `<s>${esc(c.before)}</s> → ` : ''}<span class="to">${esc(c.after)}</span>${when ? ' · ' + when : ''}${c.attempts > 1 && c.kind === 'exam' ? ' · ' + c.attempts + ' attempts' : ''}</div>
     ${c.note ? `<small>${esc(c.note)}</small>` : ''}<small class="why">${esc(c.reason)}</small>${c.problem ? `<small class="${bad ? 'bad' : ''}">${esc(bad ? c.problem : 'Already recorded')}</small>` : ''}</div>
     ${c.confidence === 'saved' ? '<span class="pill green">Saved</span>' : c.confidence !== 'high' ? `<span class="pill ${c.confidence === 'low' ? 'red' : 'amber'}">${c.confidence === 'low' ? 'Unsure' : 'Check'}</span>` : ''}</label>`;
  };
  const head = (k, cs) => {
    const c = cs[0];
    if (c.uid) return `<div class="gh">${avatar(c.studentName, 34)}<div><b>${esc(c.studentName)}</b>${norm(c.name_in_document) !== norm(c.studentName) ? `<small>“${esc(c.name_in_document)}” in the file</small>` : ''}</div></div>`;
    const v = assign[k] || '';
    return `<div class="gh">${avatar(c.name_in_document, 34)}<div><b>${esc(c.name_in_document || 'Unknown person')}</b><small class="bad">Not matched to a student</small></div>
      <select data-assign="${esc(k)}" ${done ? 'disabled' : ''}><option value="">Skip</option><option value="new" ${v === 'new' ? 'selected' : ''}>Add as a new student</option>${opts.map(([u, n]) => `<option value="${u}" ${v === u ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select></div>`;
  };
  const usable = j.changes.filter(c => !c.problem).length;
  const status = j.status === 'applied' ? `<div class="note green">${icon('check')}Saved ${j.applied} change${j.applied === 1 ? '' : 's'} · approved by ${esc(j.appliedBy)}</div>${(j.skipped || []).length ? `<div class="note">${j.skipped.map(esc).join('<br>')}</div>` : ''}` : j.status === 'discarded' ? '<div class="note">Discarded. Nothing was saved.</div>' : '';
  app.innerHTML = frame(back + '<h1>Review</h1>', esc(j.name), '', `
   <div class="card claude"><div class="ch">${icon('spark')}<div><h2>${esc(j.documentType || 'Document')}</h2><div class="sub">${esc(j.summary || '')}</div></div></div>
    ${(j.warnings || []).map(w => `<div class="note amber">${esc(w)}</div>`).join('')}${status}
    <div class="muted" style="margin-top:10px;font-size:13px">${j.changes.length} change${j.changes.length === 1 ? '' : 's'} found · ${usable} new · ${j.rules ? 'read' : 'read by Claude'} for ${esc(j.by)} ${ago(j.created)}. Unsure items are not ticked; check them against the file.</div></div>
   ${[...groups].map(([k, cs]) => { const known = cs.filter(x => x.problem === 'already recorded'), rest = cs.filter(x => x.problem !== 'already recorded'); return `<div class="card grp">${head(k, cs)}${rest.map(row).join('')}${known.length ? `<details class="known"><summary>${known.length} already recorded</summary>${known.map(row).join('')}</details>` : ''}</div>`; }).join('') || '<div class="empty">Claude found nothing to change in this file.</div>'}
   ${done ? '' : `<div class="approve"><span id="cnt"></span><button class="btn" id="disc">Discard</button><button class="btn" id="all">Tick all</button><button class="btn primary" id="ok">${icon('check')}<span>Approve</span></button></div>`}`);
  const c = $('#content');
  const count = () => { const n = [...pick].filter(i => { const x = j.changes[i]; return x.uid || assign[x.uid || 'name:' + norm(x.name_in_document)]; }).length; const e = $('#cnt'); if (e) e.textContent = n + ' selected'; const ok = $('#ok'); if (ok) { ok.disabled = !n; ok.querySelector('span').textContent = 'Approve ' + n; } return n; };
  c.onchange = e => {
    const t = e.target;
    if (t.dataset.i) { t.checked ? pick.add(+t.dataset.i) : pick.delete(+t.dataset.i); }
    if (t.dataset.assign !== undefined) { assign[t.dataset.assign] = t.value; for (const x of groups.get(t.dataset.assign)) { if (t.value && !x.problem) pick.add(x.i); else pick.delete(x.i); } scanPage(id); return; }
    count();
  };
  if (done) return;
  count();
  $('#all').onclick = () => { for (const x of j.changes) if (!x.problem && (x.uid || assign['name:' + norm(x.name_in_document)])) pick.add(x.i); scanPage(id); };
  $('#disc').onclick = async () => { if (!confirm('Discard this file? Nothing from it will be saved.')) return; try { await Store.api('POST', '/api/scan/' + id + '/discard', {}); scanPage.job = null; toast('Discarded'); go('uploads'); } catch (e) { toast(e.message); } };
  $('#ok').onclick = async () => {
    const accept = [...pick].filter(i => { const x = j.changes[i]; return x.uid || assign['name:' + norm(x.name_in_document)]; });
    const asg = {}; for (const i of accept) { const x = j.changes[i]; if (!x.uid) asg[i] = assign['name:' + norm(x.name_in_document)]; }
    $('#ok').disabled = true;
    try { const r = await Store.api('POST', '/api/scan/' + id + '/apply', {accept, assign: asg}); toast('Saved ' + r.applied + ' change' + (r.applied === 1 ? '' : 's')); scanPage.job = await Store.api('GET', '/api/scan/' + id); scanPage(id); }
    catch (e) { toast(e.message); $('#ok').disabled = false; }
  };
}

// ---------- account ----------
function account() {
  document.title = 'Account · CPL Tracker';
  const live = Store.status === 'Live';
  app.innerHTML = frame('<h1>Account</h1>', '', '', `<div class="grid g2">
   <div class="card"><div style="display:flex;align-items:center;gap:14px">${avatar(Store.meta.user, 56)}<div><b style="font-size:19px">${esc(Store.meta.user || '')}</b><div style="margin-top:4px"><span class="pill navy">${Store.meta.role === 'admin' ? 'Administrator' : 'Instructor'}</span></div></div></div></div>
   <div class="card"><h2>Shared database</h2><div style="display:flex;align-items:center;gap:10px;margin:10px 0 4px"><span class="pill ${live ? 'green' : 'amber'}">${live ? 'LIVE' : 'OFFLINE'}</span><span class="muted" style="font-size:13.5px">${live ? 'Changes appear on every device within seconds.' : esc(Store.status)}</span></div>
    ${[['Students', Store.all().length], ['Waiting to save', Store.pending()], ['Last update', Store.lastSync ? ago(Store.lastSync) : '—'], ['Server', (Store.meta.url || SERVER).replace('https://', '')]].map(([k, v]) => `<div class="kv"><span>${k}</span><b>${esc(v)}</b></div>`).join('')}</div>
   <div class="card"><h2>More</h2><div style="display:grid;gap:10px;margin-top:12px"><a class="btn" href="app/CPLTracker.apk">${icon('android')}<span>Get the Android app</span></a><a class="btn" href="classic/" target="_blank" rel="noopener">${icon('report')}<span>Classic tracker · reports, Excel, email</span></a><button class="btn danger" id="out">${icon('logout')}<span>Sign out</span></button></div></div>
  </div>`);
  $('#out').onclick = async () => { if (!confirm(Store.pending() ? Store.pending() + ' change(s) have not reached the server and will be lost. Sign out?' : 'Sign out of this computer?')) return; await Store.signOut(); render(); };
}

// ---------- start ----------
let renderTimer = null;
Store.listeners.add(() => { if (document.querySelector('.sheet') || (document.activeElement && document.activeElement.id === 'q')) { clearTimeout(renderTimer); renderTimer = setTimeout(() => { if (!document.querySelector('.sheet')) render(); }, 800); return; } clearTimeout(renderTimer); renderTimer = setTimeout(render, 60); });
window.addEventListener('hashchange', render);
window.addEventListener('online', () => { if (Store.signedIn()) { Store.fails = 0; Store.loop(); Store.schedule(); } });
window.addEventListener('scroll', () => { const t = $('.top'); if (t) t.classList.toggle('scrolled', window.scrollY > 4); }, {passive: true});
render();
await Store.load();
if (Store.signedIn()) { Store.loop(); if (Store.pending()) Store.schedule(); }
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => { });
// A newer website version: offer to reload.
const build = document.querySelector('meta[name="cpl-build"]')?.content || '';
setInterval(async () => { try { const t = await (await fetch('index.html?v=' + Date.now(), {cache: 'no-store'})).text(); const v = (t.match(/name="cpl-build" content="([^"]+)"/) || [])[1]; if (v && build && v > build && !$('.update')) { const u = document.createElement('div'); u.className = 'update'; u.innerHTML = '<span>A new version is ready.</span><button class="btn gold">Reload</button>'; u.querySelector('button').onclick = () => location.reload(); document.body.append(u); } } catch (e) { } }, 15 * 60000);
})();
