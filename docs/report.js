// Copyright (c) 2026 Moorabbin Aviation Services CPL Tracker. All rights reserved.
// Student attendance and progress report, in the MAS "Weekly Attendance Summary" format.
// Used by the website (#/report/<uid>) and the Android app (assets/report.html), so both print the same PDF.
// CPLReport.render(state, catalogue, {from, to, today, progress, logo}) -> {html, title, from, to}
(function () {
'use strict';
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTH = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const D = iso => new Date(iso + 'T00:00:00Z');
const I = d => d.toISOString().slice(0, 10);
const add = (iso, n) => { const d = D(iso); d.setUTCDate(d.getUTCDate() + n); return I(d); };
const dow = iso => (D(iso).getUTCDay() + 6) % 7; // Monday 0
const monday = iso => add(iso, -dow(iso));
const daysBetween = (a, b) => Math.round((D(b) - D(a)) / 86400000);
const dmy = iso => iso ? iso.slice(8, 10) + '/' + iso.slice(5, 7) + '/' + iso.slice(0, 4) : '';
const dMon = iso => iso ? Number(iso.slice(8, 10)) + ' ' + MON[+iso.slice(5, 7) - 1] : '';
const ddMonY = iso => iso ? iso.slice(8, 10) + ' ' + MON[+iso.slice(5, 7) - 1] + ' ' + iso.slice(0, 4) : '';
const ddMonYY = iso => iso ? iso.slice(8, 10) + ' ' + MON[+iso.slice(5, 7) - 1] + ' ' + iso.slice(2, 4) : '';
const longDate = iso => iso ? Number(iso.slice(8, 10)) + ' ' + MONTH[+iso.slice(5, 7) - 1] + ' ' + iso.slice(0, 4) : '';
const ldz = iso => iso ? iso.slice(8, 10) + ' ' + MONTH[+iso.slice(5, 7) - 1] + ' ' + iso.slice(0, 4) : '';
const hm = min => { const m = Math.round(min); return Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0'); };
const h1 = min => (Math.round(min / 6) / 10).toFixed(1);
const toMin = t => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
const plural = (n, w, p) => n + ' ' + (n === 1 ? w : (p || w + 's'));
const listDates = ds => { const p = ds.map(dMon); return p.length < 2 ? p.join('') : p.slice(0, -1).join(', ') + ' and ' + p[p.length - 1]; };
const andList = p => p.length < 2 ? p.join('') : p.slice(0, -1).join(', ') + ' and ' + p[p.length - 1];
const words = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve'];
const num = n => n < 13 ? words[n] : String(n);
const Num = n => { const s = num(n); return s[0].toUpperCase() + s.slice(1); };

// Victorian public holidays (Easter Saturday/Sunday left out, as in the MAS report).
const holidayCache = {};
function holidays(y) {
  if (holidayCache[y]) return holidayCache[y];
  const h = new Set(), iso = (m, d) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  const nth = (m, wd, n) => { let d = D(iso(m, 1)); while (d.getUTCDay() !== wd) d.setUTCDate(d.getUTCDate() + 1); d.setUTCDate(d.getUTCDate() + 7 * (n - 1)); return I(d); };
  const sub = x => { const w = D(x).getUTCDay(); return w === 6 ? add(x, 2) : w === 0 ? add(x, 1) : x; };
  h.add(sub(iso(1, 1))); h.add(sub(iso(1, 26))); h.add(nth(3, 1, 2));
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3),
    hh = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - hh - k) % 7, m = Math.floor((a + 11 * hh + 22 * l) / 451),
    em = Math.floor((hh + l - 7 * m + 114) / 31), ed = ((hh + l - 7 * m + 114) % 31) + 1, easter = iso(em, ed);
  h.add(add(easter, -2)); h.add(add(easter, 1)); h.add(iso(4, 25)); h.add(nth(6, 1, 2));
  let gf = D(iso(9, 30)); while (gf.getUTCDay() !== 6) gf.setUTCDate(gf.getUTCDate() - 1); h.add(add(I(gf), -1)); // Friday before the AFL Grand Final
  h.add(nth(11, 2, 1));
  const xmas = iso(12, 25), xw = D(xmas).getUTCDay();
  h.add(xmas); h.add(iso(12, 26));
  if (xw === 6) { h.add(iso(12, 27)); h.add(iso(12, 28)); } else if (xw === 0) h.add(iso(12, 27)); else if (xw === 5) h.add(iso(12, 28));
  return holidayCache[y] = h;
}
const isHoliday = iso => holidays(+iso.slice(0, 4)).has(iso);

function attribution(t) {
  const x = String(t || '').toLowerCase();
  if (/^student|no show|noshow/.test(x)) return 'Student';
  if (/weather|maintenance|unserviceab|instructor|aircraft|aeroplane|airport|atc/.test(x)) return 'Operational';
  return 'Administrative';
}

// ---- progress figures (same rules as the tracker's figures()) ----
function progress(s, cat) {
  const LESSONS = cat.lessons, ORDER = cat.order, label = id => LESSONS[id] ? id + '. ' + LESSONS[id].name : String(id), order = id => ORDER.indexOf(String(id));
  const f = {practical: 0, theory: 0, overall: 0, hours: 0, solo: 0, ifr: 0, done: 0, prog: 0, flights: 0, next: '', last: '', lastDate: '', gaps: 0};
  const ls = s.lessons || [];
  let total = 0, got = 0;
  for (const v of ls) { total += v.weight || 0; if (v.status === 'Completed') { got += v.weight || 0; f.done++; } else if (v.status === 'In progress') f.prog++; }
  f.practical = total ? got / total * 100 : 0;
  for (const v of s.exams || []) if (v.status === 'Passed') f.theory += v.weight || 0;
  const share = Number.isFinite(s.share) ? s.share : 60;
  f.overall = f.practical * share / 100 + f.theory * (1 - share / 100);
  let lastF = null, furthest = -1;
  for (const x of s.flights || []) {
    f.flights++; f.hours += x.hours || 0; if (x.solo) f.solo += x.hours || 0; f.ifr += x.ifr || 0;
    if (!lastF || (x.date || '') >= (lastF.date || '')) lastF = x;
    const o = x.lesson ? order(x.lesson) : -1; if (o > furthest) furthest = o;
  }
  f.last = lastF ? (LESSONS[lastF.lesson] ? label(lastF.lesson) : lastF.raw || 'Flight') : ''; f.lastDate = lastF ? lastF.date : '';
  const gap = ls.find(v => v.status !== 'Completed');
  f.next = gap ? label(String(gap.id)) : 'Course complete';
  if (furthest >= 0) {
    const nxt = ls.find(v => order(String(v.id)) > furthest && v.status !== 'Completed');
    f.next = nxt ? label(String(nxt.id)) : gap ? label(String(gap.id)) : 'Course complete';
    f.gaps = ls.filter(v => order(String(v.id)) < furthest && v.status !== 'Completed').length;
  }
  if (s.course && !/CPL|Commercial Pil/i.test(s.course)) { f.next = ''; f.gaps = 0; }
  f.share = share; f.total = ls.length; f.label = label;
  f.milestones = [10, 21, 30, 37].map(id => {
    const idx = order(String(id)); let allowance = 0;
    for (let i = 0; i <= idx; i++) { const l = LESSONS[ORDER[i]]; if (l && !/\bsim\b/i.test(l.name)) allowance += l.hours || 0; }
    const r = (s.milestones || {})[id] || {};
    const planned = r.allowance !== undefined && r.allowance !== '' ? Number(r.allowance) : Math.round(allowance * 10) / 10;
    const accrued = r.accrued !== undefined && r.accrued !== '' ? Number(r.accrued) : null;
    const over = accrued === null ? null : Math.max(0, accrued - planned);
    return {id, label: label(String(id)), planned, accrued, over, cost: over === null ? null : over * 500};
  });
  return f;
}

// The attendance figures: credited days, weeks against 20 h, the rate. Shared by the report and the tracker's tiles.
function core(s, opts) {
  opts = opts || {};
  const today = opts.today || new Date().toLocaleDateString('en-CA', {timeZone: 'Australia/Melbourne'});
  const R = s.records || {}, rec = R.oneIn60 || null;
  const flights = (s.flights || []).filter(x => x.date).slice().sort((a, b) => a.date.localeCompare(b.date) || String(a.solo).localeCompare(String(b.solo)));
  const flown = flights.filter(x => (x.hours || 0) > 0);
  const firstEvidence = [rec && rec.from, flights[0] && flights[0].date].filter(Boolean).sort()[0] || today;
  // By default the report runs to the end of the attendance record (later days have no record yet), or to today.
  const from = opts.from || (rec ? rec.from : firstEvidence), to = opts.to || (rec && rec.to < today ? rec.to : today);
  const ranges = rec ? rec.ranges || [[rec.from, rec.to]] : [];
  const covered = iso => ranges.some(([a, b]) => iso >= a && iso <= b);
  const scan = {}; for (const d of rec ? rec.days : []) scan[d.date] = d;
  const flyBy = {}; for (const x of flown) (flyBy[x.date] = flyBy[x.date] || []).push(x);
  const recDayHours = {}; for (const d of rec ? rec.days : []) recDayHours[d.date] = d.hours || 0;

  // ---- every day in the period ----
  const days = {};
  for (let iso = from; iso <= to; iso = add(iso, 1)) {
    const sc = scan[iso], fl = flyBy[iso];
    let min = 0, basis = '';
    if (sc && sc.in && sc.out) { min = Math.max(0, toMin(sc.out) - toMin(sc.in)); basis = 'scan'; }
    else if (sc && sc.in) { min = 180; basis = 'flat'; }
    else if (fl) { const h = fl.reduce((a, x) => a + (x.hours || 0), 0); min = Math.round(h * 60) + 180; basis = 'flown'; }
    days[iso] = {iso, min, basis, flew: !!fl, flyH: fl ? fl.reduce((a, x) => a + (x.hours || 0), 0) : 0, covered: covered(iso), holiday: isHoliday(iso)};
  }
  // Ground school the record credits to a week beyond its dated scans (its own weekly figure less its day hours).
  const ground = {};
  for (const w of rec ? rec.weeks : []) {
    if (/progress/i.test(w.status)) continue;
    let sum = 0; for (let i = 0; i < 7; i++) sum += recDayHours[add(w.week, i)] || 0;
    const extra = Math.round((w.attended - sum) * 10) / 10;
    if (extra >= 0.1 && w.week <= to && add(w.week, 6) >= from) ground[w.week] = Math.round(extra * 60);
  }

  // ---- weeks ----
  const weeks = [];
  for (let ws = monday(from); ws <= to; ws = add(ws, 7)) {
    const w = {start: ws, end: add(ws, 6), days: [], min: ground[ws] || 0, ground: !!ground[ws], n: 0, nr: false};
    for (let i = 0; i < 7; i++) {
      const iso = add(ws, i), d = days[iso];
      w.days.push(iso);
      if (!d) continue;
      if (d.min) { w.min += d.min; w.n++; }
      if (!d.covered) w.nr = true;
    }
    w.progress = w.end > to;
    w.status = w.progress ? 'In progress' : w.nr ? 'No record' : w.min >= 1200 ? 'Over' : 'Under';
    w.assess = !w.progress && !w.nr;
    w.met = w.assess && w.min >= 1200;
    weeks.push(w);
  }
  const assess = weeks.filter(w => w.assess);
  const att = Object.values(days).filter(d => d.min > 0);
  const totalMin = att.reduce((a, d) => a + d.min, 0) + Object.values(ground).reduce((a, m) => a + m, 0);
  const assessMin = assess.reduce((a, w) => a + w.min, 0), reqMin = assess.length * 1200;
  const rate = reqMin ? assessMin / reqMin * 100 : null, met = assess.filter(w => w.met).length;
  const by = b => att.filter(d => d.basis === b);
  const scanDays = by('scan'), flatDays = by('flat'), flownDays = by('flown');
  const progressWeek = weeks.find(w => w.progress);
  return {today, R, rec, flights, flown, from, to, covered, days, ground, weeks, assess, att, totalMin, assessMin, reqMin, rate, met, scanDays, flatDays, flownDays, progressWeek};
}
// Attendance % for the tracker: hours attended against 20 h a week, or -1 without an Aviation 1 in 60 record.
function rate(s, today) { if (!s || !s.records || !s.records.oneIn60) return -1; const c = core(s, {today}); return c.rate === null ? -1 : c.rate; }

function render(s, cat, opts) {
  opts = opts || {};
  const {today, R, rec, flights, flown, from, to, covered, days, ground, weeks, assess, att, totalMin, assessMin, reqMin, rate, met, scanDays, flatDays, flownDays, progressWeek} = core(s, opts);

  // ---- months ----
  const months = [];
  for (let m = from.slice(0, 7); m <= to.slice(0, 7);) {
    const ds = att.filter(d => d.iso.startsWith(m));
    const ws = weeks.filter(w => w.start.startsWith(m));
    const g = ws.reduce((a, w) => a + (ground[w.start] || 0), 0);
    months.push({m, days: ds.length, min: ds.reduce((a, d) => a + d.min, 0) + g, ground: g > 0, flat: ds.filter(d => d.basis !== 'scan').length,
      flying: Object.values(days).filter(d => d.iso.startsWith(m) && d.flew).length, met: ws.filter(w => w.met).length, of: ws.filter(w => w.assess).length});
    const [y, mo] = m.split('-').map(Number); m = mo === 12 ? `${y + 1}-01` : `${y}-${String(mo + 1).padStart(2, '0')}`;
  }
  const periodLabel = (a, b) => { const x = MON[+a.slice(5, 7) - 1] + ' ' + a.slice(0, 4), y = MON[+b.slice(5, 7) - 1] + ' ' + b.slice(0, 4); return x === y ? x : a.slice(0, 4) === b.slice(0, 4) ? MON[+a.slice(5, 7) - 1] + ' - ' + y : x + ' - ' + y; };

  // ---- logbook, sessions, cancellations, events ----
  const logbook = flights.filter(x => x.date >= from && x.date <= to);
  const firstSolo = flights.find(x => x.solo);
  const flyDaysP = [...new Set(logbook.filter(x => (x.hours || 0) > 0).map(x => x.date))];
  const noScanFly = flyDaysP.filter(d => days[d] && days[d].basis === 'flown' && days[d].covered);
  const sessions = (rec ? rec.sessions : []).filter(x => x.date >= from && x.date <= to).slice().sort((a, b) => a.date.localeCompare(b.date));
  const recAge = rec && rec.generated ? daysBetween(rec.generated.slice(0, 10), today) : 0;
  const missed = (rec ? rec.missed : []).filter(x => x.date >= from && x.date <= to).slice().sort((a, b) => b.date.localeCompare(a.date)).map(x => ({...x, pastDue: x.pastDue + Math.max(0, recAge)}));
  const canc = (R.cancellations || {}).events || [];
  // Cancellation counts: Smart's own statistics when a report printout supplied them (they cover every event,
  // even when only the first page was printed); otherwise counted from the events held.
  const CS = (() => {
    const C = R.cancellations || {}, sm = C.summary;
    if (sm && sm.total) return {total: sm.total, flight: sm.flight || 0, reasons: sm.reasons || {}, types: sm.types || {}, range: sm.range || C.range};
    const reasons = {}, types = {}; for (const c of canc) { const k = c.ctype || 'Not recorded'; reasons[k] = (reasons[k] || 0) + 1; types[c.type] = (types[c.type] || 0) + 1; }
    return {total: canc.length, flight: canc.filter(c => /flight booking/i.test(c.type)).length, reasons, types, range: C.range || ['', '']};
  })();
  const CA = {Student: 0, Operational: 0, Administrative: 0}; for (const [k, v] of Object.entries(CS.reasons)) CA[attribution(k)] += v;
  const evs = ((R.events || {}).events || []);

  const sec = [];
  let n = 0;
  const H = t => `<h2>${++n}. ${esc(t)}</h2>`;
  const note = t => `<p class="note">${t}</p>`;
  const table = (cls, head, rows, foot) => `<table class="t ${cls}"><thead><tr>${head.map(h => `<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody>${foot ? `<tfoot>${foot}</tfoot>` : ''}</table>`;

  const progressOn = opts.progress !== false;
  const P = progressOn ? progress(s, cat) : null;
  const arn = (rec && rec.arn) || s.studentId || '';
  const enrol = rec && rec.enrolments && rec.enrolments.length ? rec.enrolments.map(e => `${e.code} (from ${dmy(e.from)})`).join(', ') : '';
  const title = progressOn ? 'Attendance and Progress Report' : 'Weekly Attendance Summary';
  const meta = [esc(s.name || 'Student'), arn && 'ARN ' + esc(arn), s.course && !enrol && esc(s.course.replace(/^MAS\s+/, '')), enrol && 'Enrolled: ' + esc(enrol), 'Period: ' + dmy(from) + ' - ' + dmy(to), 'Generated ' + dmy(today)].filter(Boolean).join(' &nbsp;|&nbsp; ');
  const low = rate !== null && rate < 80;
  sec.push(`<h1>${title}</h1><div class="meta">${meta}</div>
   <table class="t kpi"><thead><tr><th>Period</th><th>Days attended</th><th>Hours credited</th><th>Hours required (20/wk)</th><th>Attendance rate</th><th>Weeks meeting 20h</th></tr></thead>
   <tbody><tr><td>${periodLabel(from, to)}</td><td>${att.length}</td><td>${h1(totalMin)} h</td><td>${reqMin ? Math.round(reqMin / 60) + ' h' : '-'}</td><td class="${low ? 'bad' : rate !== null ? 'good' : ''}">${rate === null ? '-' : Math.round(rate) + '%'}</td><td class="${assess.length && met < assess.length ? 'bad' : 'good'}">${assess.length ? met + ' of ' + assess.length : '-'}</td></tr></tbody></table>`);

  // 1. Basis of calculation
  {
    const parts = [];
    let hrs = `<b>Hours.</b> A day is credited on whichever of three bases applies. <b>Measured.</b> ${plural(scanDays.length, 'day')} ${scanDays.length === 1 ? 'is' : 'are'} counted at the span between the first and last fingerprint scan, totalling ${h1(scanDays.reduce((a, d) => a + d.min, 0))} hours.`;
    if (flownDays.length) { const fh = flownDays.reduce((a, d) => a + d.flyH, 0); hrs += ` <b>† Flown plus ground time.</b> On ${plural(flownDays.length, 'flying day')} that carry no scan, the logbook hours for the day are credited together with 3:00 of ground time for briefing, pre-flight and debrief - ${fh.toFixed(1)} hours flown plus ${(flownDays.length * 3).toFixed(1)} hours ground, ${(fh + flownDays.length * 3).toFixed(1)} in all.`; }
    if (flatDays.length) hrs += ` <b>† Three hours credited.</b> On ${plural(flatDays.length, 'day')} the student signed in but never signed out, and 3:00 is credited${flatDays.length > 1 ? ' for each' : ''}${rec ? '; the attendance record applies a 4:00 daily cap to the same day' + (flatDays.length > 1 ? 's' : '') : ''}.`;
    const gw = Object.keys(ground).sort();
    if (gw.length) { const gt = gw.reduce((a, w) => a + ground[w], 0); hrs += ` <b>‡ Ground school with no dated scan.</b> The attendance record credits ${h1(gt)} hours beyond the scan log - ${andList(gw.map(w => { const wk = weeks.find(x => x.start === w); return (wk && wk.n === 0 ? 'the whole of the week of ' : hm(ground[w]) + ' in the week of ') + longDate(w).replace(/ \d{4}$/, ''); }))}${gw.some(w => (weeks.find(x => x.start === w) || {}).n === 0) ? ', which have no days on campus' : ''}${sessions.length ? ' but do have attended course sessions' : ''}. These are the record's own weekly figures and are carried through unchanged.`; }
    hrs += ` Credited hours total ${h1(totalMin)}.`;
    parts.push(hrs);
    let req = `<b>The 20-hour requirement.</b> It is applied to every completed week in this report`;
    const nr = weeks.filter(w => !w.progress && w.nr);
    if (nr.length) req += ` except the ${plural(nr.length, 'week')} with no attendance record supplied (shown as 'No record')`;
    if (progressWeek) req += `. The week of ${longDate(progressWeek.start)} is still in progress and is excluded${progressWeek.min ? `, though it has already reached ${hm(progressWeek.min)}` : ''}`;
    req += `. On that basis ${hm(assessMin)} hours have been attended against ${hm(reqMin)} required across ${plural(assess.length, 'assessable week')}${reqMin ? ` - ${Math.round(rate)} per cent -` : ''} and ${met} of those weeks reached 20 hours.`;
    if (rec && rec.summary && rec.summary.requiredH !== undefined) {
      const firstReq = (rec.weeks || []).filter(w => w.required > 0).map(w => w.week).sort()[0];
      req += ` The Aviation 1 in 60 record ${firstReq ? `applies the requirement only from the week of ${longDate(firstReq)} and shows every earlier week as not started; on that narrower basis it reports` : 'reports'} ${rec.summary.attendedH} hours of ${rec.summary.requiredH} across ${plural(rec.summary.weeksTotal || 0, 'week')}, ${rec.summary.weeksMet ? rec.summary.weeksMet + ' met' : 'none met'}.${firstReq && firstReq > from ? ' Weeks before that date are shown here against 20:00 for comparison and no requirement was in force for them.' : ''}`;
    }
    for (const a of rec ? rec.adjustments || [] : []) req += ` The record carries a requirement adjustment: ${esc(a.type.toLowerCase())} from ${longDate(a.from)}${a.to ? ' to ' + longDate(a.to) : ', with no end date'} (${esc(a.detail || 'no detail')}${a.reason ? ', ' + esc(a.reason) : ', no reason recorded'}).`;
    parts.push(req);
    const src = [];
    if (rec) src.push(`the Aviation 1 in 60 student support record of ${longDate(rec.generated.slice(0, 10))}, which lists ${plural(rec.days.length, 'day')} on campus with sign-in and sign-out times, ${plural(rec.sessions.length, 'course session')} and the weekly hours against requirement`);
    if (flights.length) src.push(`the student's flight records from Smart Aviation, ${plural(logbook.length, 'entry', 'entries')} in this period${logbook.length ? ' to ' + longDate(logbook[logbook.length - 1].date) : ''}`);
    if (evs.length) src.push(`the Smart Aviation event report, whose detail covers ${longDate(evs[0].start.slice(0, 10))} to ${longDate(evs[evs.length - 1].start.slice(0, 10))}`);
    if (CS.total) src.push(`the Smart Aviation event cancellation report for ${longDate(CS.range[0])} to ${longDate(CS.range[1])}`);
    if (src.length) parts.push(`<b>Sources.</b> ${src.map((x, i) => i ? x : x[0].toUpperCase() + x.slice(1)).join('; ')}.`);
    sec.push(H('Basis of calculation') + parts.map(p => `<p>${p}</p>`).join(''));
  }

  // 2. Findings
  {
    const F = [];
    if (noScanFly.length) {
      const solo = firstSolo && noScanFly.includes(firstSolo.date);
      F.push(`<b>${Num(noScanFly.length)} flying day${noScanFly.length > 1 ? 's are' : ' is'} absent from the attendance record.</b> The flight records show ${plural(flyDaysP.length, 'flying day')}; ${noScanFly.length} of them - ${listDates(noScanFly)} - ${noScanFly.length > 1 ? 'have' : 'has'} no fingerprint scan and appear${noScanFly.length > 1 ? '' : 's'} as attendance only because of the flight records. They add ${h1(noScanFly.reduce((a, d) => a + days[d].min, 0))} hours.${solo ? ` The first solo, on ${ddMonY(firstSolo.date)}, is one of them: the record shows the student as not present on the day they first flew an aircraft alone.` : ''}`);
      F.push('The sign-in and sign-out process is the matter to address; the scan log alone understates attendance.');
    }
    if (flatDays.length) F.push(`${Num(flatDays.length)} day${flatDays.length > 1 ? 's have' : ', ' + longDate(flatDays[0].iso) + ', has'} a sign-in and no sign-out${flatDays.length > 1 ? ' - ' + listDates(flatDays.map(d => d.iso)) : ''}.`);
    const noNotify = sessions.filter(x => /absent no notify/.test(x.status));
    if (rec && rec.summary && rec.summary.absNoCert === 0 && noNotify.length) F.push(`<b>The record contradicts itself on absences.</b> Its summary states no absences without certificate, yet ${num(noNotify.length)} session${noNotify.length > 1 ? 's are' : ' is'} marked absent with no notification and no certificate - ${listDates(noNotify.map(x => x.date))}.`);
    if (missed.length) {
      const unrev = missed.filter(x => !x.reviewed), sickV = missed.filter(m => sessions.some(x => x.date === m.date && x.course === m.course && /sick|medical/.test(x.status) && x.cert === 'verified'));
      const pd = missed.map(x => x.pastDue);
      F.push(`${Num(missed.length)} session${missed.length > 1 ? 's sit' : ' sits'} on the record's own list of missed sessions with no reason given, ${Math.min(...pd) === Math.max(...pd) ? pd[0] : Math.min(...pd) + ' to ' + Math.max(...pd)} days past due, ${unrev.length === missed.length ? '<b>and none has been reviewed</b>' + (missed.length > 1 ? ' - the Reviewed column is empty for all ' + num(missed.length) : '') : unrev.length ? 'and ' + num(unrev.length) + ' of them ' + (unrev.length > 1 ? 'have' : 'has') + ' not been reviewed' : 'while each is signed off as reviewed - the reason field and the review are inconsistent'}.${sickV.length ? ` That list also includes ${listDates(sickV.map(x => x.date).sort())}, which the session table shows as sick with a verified certificate, so the two parts of the record disagree.` : ''}`);
    }
    const gOnly = weeks.filter(w => ground[w.start] && w.n === 0), empty = [];
    let run = [];
    for (const w of weeks) { if (w.assess && w.min === 0) run.push(w); else { if (run.length >= 2) empty.push(run); run = []; } }
    if (run.length >= 2) empty.push(run);
    if (gOnly.length || empty.length) F.push([gOnly.length ? `${Num(gOnly.length)} week${gOnly.length > 1 ? 's' : ''} ${gOnly.length > 1 ? 'have' : 'has'} no day on campus but carr${gOnly.length > 1 ? 'y' : 'ies'} ${gOnly.map(w => Math.round(ground[w.start] / 60)).join(', ').replace(/, (\d+)$/, ' and $1')} hours of credited ground school` : '', ...empty.map(r => `${num(r.length)} consecutive weeks from ${dMon(r[0].start)} have neither attendance nor flying`)].filter(Boolean).join(', and ') + '.');
    if (rec && flights.length) {
      const before = flights.filter(x => x.date < rec.from), evBefore = evs.filter(e => e.start.slice(0, 10) < rec.from);
      const startT = [before[0] && before[0].date, evBefore[0] && evBefore[0].start.slice(0, 10)].filter(Boolean).sort()[0];
      if (startT && daysBetween(startT, rec.from) > 28) {
        const bh = before.reduce((a, x) => a + (x.hours || 0), 0);
        F.push(`<b>No attendance record exists before ${longDate(rec.from)}.</b> The student has been in training since ${longDate(startT)}${before.length ? ` - ${plural(before.length, 'flight')}, ${bh.toFixed(1)} hours, appear in the flight records before the attendance record begins` : ''}. Nothing covers that period, so attendance cannot be evidenced for it${evBefore.length ? '; the last section sets out what the event report does show' : ''}.`);
      }
    }
    if (CS.total) {
      const att3 = CA, fl = CS.flight;
      F.push(`Across ${longDate(CS.range[0])} to ${longDate(CS.range[1])}, ${CS.total} of the student's events were cancelled - ${fl} flight events and ${CS.total - fl} non-flight. ${att3.Student} ${att3.Student === 1 ? 'is' : 'are'} attributable to the student and ${att3.Operational} to weather, instructor or aircraft unserviceability; the remaining ${att3.Administrative} carry an administrative reason - rescheduled, other, or created in error - that does not record who caused the cancellation.`);
    }
    if (P && P.gaps) F.push(`${Num(P.gaps)} syllabus lesson${P.gaps > 1 ? 's' : ''} before the furthest lesson flown ${P.gaps > 1 ? 'are' : 'is'} not marked complete.`);
    if (!F.length) F.push('Nothing in the records for this period calls for follow-up.');
    sec.push(H('Findings') + `<p>${F.map((x, i) => `<b>(${String.fromCharCode(97 + i)})</b> ${x}`).join(' ')}</p>`);
  }

  // 3. Monthly summary
  {
    const rows = months.map(m => `<tr><td>${MON[+m.m.slice(5) - 1]} ${m.m.slice(0, 4)}</td><td>${m.days}</td><td><b>${hm(m.min)}${m.ground ? '‡' : ''}</b></td><td>${m.flat || '-'}</td><td>${m.flying || '-'}</td><td>${m.of ? m.met + ' of ' + m.of : '-'}</td></tr>`);
    const foot = `<tr><td>TOTAL</td><td>${att.length}</td><td>${hm(totalMin)}</td><td>${flatDays.length + flownDays.length}</td><td>${Object.values(days).filter(d => d.flew).length}</td><td>${met} of ${assess.length}</td></tr>`;
    sec.push(H('Monthly summary') + table('months', ['Month', 'Days attended', 'Hours credited', 'Of which 3:00 credit', 'Flying days', 'Weeks meeting 20h'], rows, foot) + (Object.keys(ground).length ? note('‡ Includes ground-school hours the attendance record credits to a week with no dated scan.') : ''));
  }

  // 4. Hours per day, by week
  {
    const rows = weeks.map(w => {
      const cells = w.days.map(iso => {
        const d = days[iso], dn = Number(iso.slice(8, 10));
        if (!d) return `<td class="c na"><i>${dn}</i><small>n/a</small></td>`;
        if (d.min) return `<td class="c ${d.flew ? 'fl' : 'sc'}"><span><i>${dMon(iso)}</i> <b>${hm(d.min)}${d.basis !== 'scan' ? '†' : ''}</b></span>${d.flew ? '<small class="flew">FLEW</small>' : ''}</td>`;
        if (!d.covered) return `<td class="c nr"><i>${dn}</i><small>n/r</small></td>`;
        if (d.holiday) return `<td class="c hol"><i>${dn}</i><small>P/H</small></td>`;
        return `<td class="c no"><i>${dn}</i><small>-</small></td>`;
      }).join('');
      const diff = w.min - 1200;
      const st = w.progress ? '<td class="st prog">In progress</td>' : w.nr ? '<td class="st prog">No record</td>' : `<td class="st ${diff >= 0 ? 'ok' : 'under'}">${diff >= 0 ? 'Over +' + hm(diff) : 'Under -' + hm(-diff)}</td>`;
      return `<tr><td class="wk">${dMon(w.start).padStart(2, '0').replace(/^(\d) /, '0$1 ')} - ${ddMonY(w.end)}</td>${cells}<td>${w.n}</td><td class="tot"><b>${hm(w.min)}${w.ground ? '‡' : ''}</b></td><td>20:00</td>${st}</tr>`;
    });
    const foot = `<tr><td>TOTAL</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td>${att.length}</td><td>${hm(totalMin)}</td><td>${hm(reqMin)}</td><td>${met} of ${assess.length} met</td></tr>`;
    const legend = `<b>Green</b> Flew that day, flight record (FLEW) &nbsp; <b>Blue</b> Attended, fingerprint scan only &nbsp; <b>Red</b> No attendance recorded &nbsp; <b>Amber</b> P/H Victorian public holidays${weeks.some(w => w.nr) ? ', n/r no attendance record supplied' : ''} &nbsp; <b>Grey</b> n/a outside the reporting period &nbsp; † Includes the 3:00 credit${Object.keys(ground).length ? ' &nbsp; ‡ Week total includes ground school with no dated scan' : ''}`;
    sec.push(H('Hours per day, by week') + table('weeks', ['Week (Mon - Sun)', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun', 'Days', 'Total', 'Req', 'Status'], rows, foot) + note(legend));
  }

  // 5. Flight records (the pilot logbook)
  if (logbook.length) {
    let dual = 0, cmd = 0, ins = 0;
    const rows = logbook.map(x => {
      const h = x.hours || 0; if (x.solo) cmd += h; else dual += h; ins += x.ifr || 0;
      return `<tr class="${x.solo ? 'solo' : ''}"><td>${ddMonYY(x.date)}</td><td>${esc(x.reg || '')}</td><td>${esc(x.solo ? (s.name || 'Student') + ' (PIC)' : x.instructor || '')}</td><td class="det">${esc(x.details || x.raw || '')}</td><td>${!x.solo && h ? +h.toFixed(1) : ''}</td><td>${x.solo && h ? +h.toFixed(1) : ''}</td><td>${x.ifr ? +x.ifr.toFixed(1) : ''}</td></tr>`;
    });
    const foot = `<tr><td>TOTAL</td><td></td><td></td><td>Grand total flying hours ${(dual + cmd).toFixed(1)}</td><td>${dual.toFixed(1)}</td><td>${cmd.toFixed(1)}</td><td>${ins.toFixed(1)}</td></tr>`;
    const fs = firstSolo && firstSolo.date >= from && firstSolo.date <= to;
    sec.push(H('Pilot logbook') + table('log', ['Date', 'Aircraft', 'Pilot in command', 'Details', 'Dual', 'Command', 'Instr.'], rows, foot) +
      note(`${plural(logbook.length, 'entry', 'entries')} over ${plural(flyDaysP.length, 'flying day')}, ${(dual + cmd).toFixed(1)} hours, to ${longDate(logbook[logbook.length - 1].date)}.${fs ? ` First solo ${ddMonY(firstSolo.date)}, shaded green.` : ''}${rec ? ` ${noScanFly.length} of the ${flyDaysP.length} flying days carry no fingerprint scan and appear as attendance only because of these records.` : ''}`));
  }

  // 6. Course sessions
  if (sessions.length) {
    const cls = st => /attended|present/.test(st) ? 'good' : /sick|medical|excused/.test(st) ? 'blue' : /late/.test(st) ? 'amber' : 'bad';
    const rows = sessions.map(x => `<tr><td>${ddMonY(x.date)}</td><td>${esc(x.course)}</td><td>${esc(x.session)}</td><td class="s ${cls(x.status)}">${esc(x.status)}</td><td class="${x.cert === 'verified' ? 'blue' : ''}">${esc(x.cert || '-')}</td></tr>`);
    const c = k => sessions.filter(x => k.test(x.status)).length;
    const a = c(/attended|present/), nn = c(/absent no notify/), an = c(/absent notified/), sk = sessions.filter(x => /sick|medical/.test(x.status)), ex = c(/excused/), lt = c(/late/);
    const bits = [`${a} attended`, nn && `${nn} absent with no notification`, an && `${an} absent notified`, lt && `${lt} late`, sk.length && `${sk.length} sick${sk.every(x => x.cert === 'verified') ? ' with certificate verified' : ''}`, ex && `${ex} excused`].filter(Boolean);
    const rs = rec && rec.summary && rec.summary.sessions !== undefined ? ` · reported session attendance ${rec.summary.present} of ${rec.summary.sessions} (${rec.summary.rate}%)` : '';
    sec.push(H('Course sessions') + table('sess', ['Date', 'Course', 'Session', 'Status', 'Medical cert.'], rows) + note(bits.join(' · ') + rs + '.'));
  }

  // 7. Missed sessions
  if (missed.length) {
    const rows = missed.map(x => `<tr><td>${ddMonY(x.date)}</td><td>${esc(x.course)}</td><td>${esc(x.session)}</td><td>${esc(x.reason)} - ${x.pastDue} days past due</td><td class="${x.reviewed ? 'good' : 'bad'}">${x.reviewed ? 'Reviewed' + (x.reviewer ? ' · ' + esc(x.reviewer) : '') : 'Not reviewed'}</td></tr>`);
    const unrev = missed.filter(x => !x.reviewed).length;
    sec.push(H(unrev ? 'Missed sessions awaiting review' : 'Missed sessions with no reason given') + table('miss', ['Date', 'Course', 'Session', 'Status on the record', 'Reviewed'], rows) + note(`The attendance record's own list of sessions missed with no reason given. ${unrev === missed.length ? 'None has been reviewed.' : unrev ? unrev + ' not yet reviewed.' : 'All are marked reviewed.'}`));
  }

  // 8. Cancelled events
  if (CS.total) {
    const g = CS.reasons;
    const list = Object.entries(g).sort((a, b) => b[1] - a[1]);
    const rows = list.map(([k, v]) => { const a = attribution(k); return `<tr class="${a === 'Student' ? 'stu' : ''}"><td>${esc(k)}</td><td>${a}</td><td>${v}</td><td>${Math.round(v / CS.total * 100)}%</td></tr>`; });
    const types = CS.types;
    const tl = Object.entries(types).sort((a, b) => b[1] - a[1]).slice(0, 2);
    const att3 = CA;
    sec.push(H('Cancelled events') + table('canc', ['Cancellation reason recorded', 'Attributable to', 'Events', 'Share'], rows, `<tr><td>TOTAL</td><td></td><td>${CS.total}</td><td>100%</td></tr>`) +
      note(`From the Smart Aviation event cancellation report for ${longDate(CS.range[0])} to ${longDate(CS.range[1])}. Counts are of individual events, so a briefing and its flight count separately; by event type the largest groups are ${tl.map(([k, v]) => v + ' ' + k.toLowerCase().replace(/ (dual|solo)$/, '') + (v > 1 ? 's' : '')).join(' and ')}. ${att3.Student} are attributable to the student, ${att3.Operational} to weather, instructor or aircraft unserviceability, and ${att3.Administrative} to administrative reasons that do not record a cause.`));
  }

  // 9. Training events before the attendance record
  {
    const cut = rec ? rec.from : from;
    const pre = evs.filter(e => e.start.slice(0, 10) < cut);
    if (pre.length) {
      const generic = t => !t || /^post-flight de-?briefing$/i.test(t);
      const books = [];
      for (const e of pre) {
        const day = e.start.slice(0, 10);
        const b = generic(e.title) ? books.find(x => x.day === day && x.end === e.start) : books.find(x => x.day === day && x.key === norm(e.title));
        if (b) { b.ev.push(e); if (e.end > b.end) b.end = e.end; continue; }
        books.push({day, key: norm(e.title), end: e.end, ev: [e]});
      }
      const out = {}, order = [];
      const put = (k, b) => { if (!out[k]) { out[k] = {n: 0, h: 0, d: new Set()}; order.push(k); } out[k].n++; out[k].d.add(b.day); out[k].h += b.ev.reduce((a, e) => a + (e.hours || 0), 0); };
      for (const b of books) {
        if (b.ev.some(e => e.status === 'Completed')) put('Completed', b);
        else if (b.ev.every(e => e.status === 'Cancelled')) put('Cancelled - ' + (b.ev.find(e => /flight booking/i.test(e.type)) || b.ev[0]).cancel.toLowerCase().replace(/^./, c => c.toUpperCase()), b);
        else put('Scheduled, never completed', b);
      }
      const keys = ['Completed', ...order.filter(k => k.startsWith('Cancelled')).sort((a, b) => out[b].n - out[a].n), 'Scheduled, never completed'].filter(k => out[k]);
      const rows = keys.map(k => `<tr class="${k === 'Completed' ? 'ok' : 'stu'}"><td>${esc(k)}</td><td>${out[k].n}</td><td>${k === 'Completed' ? `${out[k].h.toFixed(1)} hours of ground school, briefings and flights across ${plural(out[k].d.size, 'date')}` : ''}</td></tr>`);
      const r0 = pre[0].start.slice(0, 10), r1 = pre[pre.length - 1].start.slice(0, 10);
      sec.push(H(`Training events, ${MONTH[+r0.slice(5, 7) - 1]}${r0.slice(0, 4) !== r1.slice(0, 4) ? ' ' + r0.slice(0, 4) : ''} to ${MONTH[+r1.slice(5, 7) - 1]} ${r1.slice(0, 4)}`) +
        table('events', ['Outcome recorded in Smart Aviation', 'Bookings', 'Detail'], rows, `<tr><td>TOTAL</td><td>${books.length}</td><td>${longDate(r0)} to ${longDate(r1)}</td></tr>`) +
        note(`From the Smart Aviation event report${R.events.range && R.events.range[0] ? ` run for ${longDate(R.events.range[0])} to ${longDate(R.events.range[1])}` : ''}. Briefing, flight and sign-in rows for the same lesson on the same day have been collapsed into one booking. This period sits before the attendance record${rec ? '' : ' (none has been loaded)'}, for which no fingerprint or session data exists, and is shown here as the evidence of training in that time.`));
    }
  }

  // Training progress
  if (P) {
    const pc = v => (v >= 10 || v === 0 ? v.toFixed(0) : v.toFixed(1)) + '%';
    sec.push(H('Training progress') + `<table class="t kpi"><thead><tr><th>Overall</th><th>Practical (${P.share}%)</th><th>Theory (${100 - P.share}%)</th><th>Lessons completed</th><th>Flights</th><th>Hours flown</th></tr></thead>
      <tbody><tr><td>${pc(P.overall)}</td><td>${pc(P.practical)}</td><td>${pc(P.theory)}</td><td>${P.done} of ${P.total}</td><td>${P.flights}</td><td>${P.hours.toFixed(1)} h</td></tr></tbody></table>
      <p>${[s.course && `<b>Course.</b> ${esc(s.course)}.`, s.instructor && `<b>Instructor.</b> ${esc(s.instructor)}.`, P.last && `<b>Last flown.</b> ${esc(P.last)} on ${longDate(P.lastDate)}.`, P.next && `<b>Next lesson.</b> ${esc(P.next)}${P.gaps ? ` - ${plural(P.gaps, 'earlier lesson')} not marked complete` : ''}.`, `<b>Flying.</b> ${P.hours.toFixed(1)} hours in ${plural(P.flights, 'flight')}, of which ${P.solo.toFixed(1)} solo and ${P.ifr.toFixed(1)} instrument.`, P.prog && `${plural(P.prog, 'lesson')} in progress.`].filter(Boolean).join(' ')}</p>`);
    const ex = (s.exams || []).map(v => `<tr><td>${esc(v.id)}</td><td class="s ${v.status === 'Passed' ? 'good' : v.status === 'Failed' ? 'bad' : ''}">${esc(v.status)}</td><td>${v.examDate ? ddMonY(v.examDate) : '-'}</td><td>${(v.attempts || []).length || '-'}</td><td>${v.weight}%</td><td class="det">${esc(v.notes || '')}</td></tr>`);
    sec.push(H('Theory exams') + table('exams', ['Exam', 'Result', 'Date', 'Attempts', 'Weight', 'Notes'], ex));
    const ms = P.milestones.map(m => `<tr><td>After ${esc(m.label)}</td><td>${m.planned.toFixed(1)} h</td><td>${m.accrued === null ? '-' : m.accrued.toFixed(1) + ' h'}</td><td class="${m.over ? 'bad' : ''}">${m.over === null ? '-' : m.over ? '+' + m.over.toFixed(1) + ' h' : 'Within allowance'}</td><td>${m.cost ? '$' + Math.round(m.cost).toLocaleString('en-AU') : '-'}</td></tr>`);
    sec.push(H('Hour reviews') + table('ms', ['Milestone', 'Syllabus allowance', 'Hours flown', 'Overfly', 'Estimated cost'], ms) + note('Aircraft hours at each milestone against the syllabus allowance; overfly estimated at $500 per hour.'));
    const st = v => v.status === 'Completed' ? 'good' : v.status === 'In progress' ? 'amber' : '';
    const ls = (s.lessons || []).map(v => { const l = cat.lessons[String(v.id)] || {}; const sm = (String(v.notes || '').split('\n').find(x => x.startsWith('Smart: ')) || '').slice(7); return `<tr><td>${esc(P.label(String(v.id)))}</td><td>${esc([l.duration, l.mode].filter(Boolean).join(' · '))}</td><td class="s ${st(v)}">${esc(v.status)}</td><td class="det">${esc(sm)}</td></tr>`; });
    sec.push(H('Practical lessons') + table('lessons', ['Lesson', 'Syllabus', 'Status', 'Flown (Smart)'], ls));
  }

  const logo = opts.logo || 'logos/mas.png';
  const html = `<div class="rpt"><table class="page"><thead><tr><td><div class="ph"><img src="${esc(logo)}" alt="Moorabbin Aviation Services"></div></td></tr></thead><tbody><tr><td>${sec.join('\n')}</td></tr></tbody></table></div>`;
  return {html, title: (s.name || 'Student').replace(/[^A-Za-z0-9 ]/g, '') + (progressOn ? ' attendance and progress' : ' attendance') + ' ' + to, from, to, file: (progressOn ? 'Attendance_Progress_Report_' : 'Attendance_Report_') + String(s.name || 'Student').toUpperCase().replace(/[^A-Z0-9]+/g, '_') + (arn ? '_ARN' + arn : '')};
}
const norm = v => String(v || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

const CSS = `@page{size:A4 landscape;margin:10mm 12mm 14mm 12mm;@bottom-left{content:"Moorabbin Aviation Services Pty Ltd  |  RTO 22495  |  CRICOS 02530B  |  Confidential - student training record";font:7pt Arial,Helvetica,sans-serif;color:#667085}@bottom-right{content:"Page " counter(page);font:7pt Arial,Helvetica,sans-serif;color:#667085}}
.rpt{font:9.5px/1.45 Arial,Helvetica,sans-serif;color:#1d2433;background:#fff;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.rpt table.page{width:100%;border-collapse:collapse}.rpt table.page>thead>tr>td,.rpt table.page>tbody>tr>td{padding:0}
.rpt .ph{display:flex;justify-content:flex-end;height:46px;margin-bottom:4px}.rpt .ph img{height:46px}
.rpt h1,.rpt h2{font-family:Arial,Helvetica,sans-serif}.rpt h1{font-size:23px;color:#1f3864;margin:0 0 2px}.rpt .meta{color:#475467;font-size:10.5px;margin-bottom:10px}
.rpt h2{font-size:13px;color:#1f3864;margin:14px 0 6px;break-after:avoid;page-break-after:avoid}
.rpt p{margin:0 0 7px;text-align:left}.rpt p.note{color:#667085;font-size:8.5px;margin-top:5px}
.rpt table.t{width:100%;border-collapse:collapse;font-size:9.5px}
.rpt table.t th{background:#203a5c;color:#fff;font-weight:700;text-align:center;padding:7px 6px;border:1px solid #203a5c}.rpt table.t th:first-child{text-align:left}
.rpt table.t td{border:1px solid #d5dbe5;padding:6px;text-align:center;vertical-align:middle}.rpt table.t td:first-child{text-align:left}
.rpt table.t tr{break-inside:avoid;page-break-inside:avoid}
.rpt table.t tfoot td{background:#203a5c;color:#fff;font-weight:700;border-color:#203a5c}
.rpt tfoot{display:table-row-group}
.rpt table.kpi th{background:#eef1f6;color:#475467;font-weight:400;border-color:#d5dbe5;text-align:center!important}.rpt table.kpi td{font-size:17px;font-weight:700;padding:10px;text-align:center!important}
.rpt .bad{color:#c62828;font-weight:700}.rpt .good{color:#1b7f3b;font-weight:700}.rpt td.blue{color:#1f3864;background:#e4ecf7}.rpt .amber{color:#9a6a00;font-weight:700}
.rpt td.s.bad{background:#fbeaea}.rpt td.s.blue{background:#e4ecf7;font-weight:700}
.rpt table.weeks td{padding:3px 2px;font-size:9px}.rpt table.weeks td.wk{white-space:nowrap;padding-left:6px;font-size:9.5px}
.rpt table.weeks td.c{width:6.6%;line-height:1.2}.rpt td.c i{font-style:normal;font-size:7.5px;color:inherit;opacity:.8}.rpt td.c small{display:block;font-size:7.5px}.rpt td.c b{font-size:11px}.rpt td.c span{white-space:nowrap}
.rpt td.c.no{background:#fbeaea;color:#c98a8a}.rpt td.c.sc{background:#e4ecf7}.rpt td.c.fl{background:#e5f3e8}.rpt td.c.hol,.rpt td.c.nr{background:#fdf3dc;color:#9a6a00;font-weight:700}.rpt td.c.na{background:#f1f3f6;color:#a0a7b4}
.rpt td.c small.flew{font-weight:700;color:#1d2433;font-size:7px}
.rpt td.tot{background:#eef1f6;font-size:10.5px}.rpt td.st{font-weight:700}.rpt td.st.under{background:#fbeaea;color:#c62828}.rpt td.st.ok{background:#e5f3e8;color:#1b7f3b}.rpt td.st.prog{background:#fdf3dc;color:#9a6a00}
.rpt table.log td{background:#e9f0fa;text-align:left}.rpt table.log tr.solo td{background:#e5f3e8}.rpt table.log td:nth-child(n+5){text-align:center;width:6%}.rpt td.det{font-size:8.5px;text-align:left!important}
.rpt tr.stu td{background:#fbeaea}.rpt tr.ok td{background:#e5f3e8}
.rpt table.lessons td{padding:3px 6px;font-size:8.5px}
@media screen{.rpt{width:273mm;margin:0 auto;padding:14mm 12mm;box-shadow:0 2px 18px rgba(16,24,40,.12);border-radius:4px}}`;

const api = {render, rate, core, css: CSS, holidays};
if (typeof window !== 'undefined') window.CPLReport = api;
if (typeof module !== 'undefined') module.exports = api;
})();
