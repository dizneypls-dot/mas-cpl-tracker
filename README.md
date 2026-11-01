# MAS CPL Progress Tracker

Student progress tracker for the MAS Integrated CPL (150 hr): 81 practical lessons with syllabus names and durations, 12 theory exams, course timeline and flying forecast, meeting notes, progress email and printable report.

Student data never leaves the device: it is kept in the browser/app storage, with Excel backups and a downloadable tracker file for sharing.

## Layout
- `src/index.html` – the app (one self-contained page, works offline). Edit this.
- `docs/` – web app for GitHub Pages (installable: manifest, service worker, icons). Rebuild with `./build.sh`.
- `android/` – Android app (WebView wrapper). Build with `android/build-apk.sh` → `android/build/CPLTracker.apk`.
  The signing key (`android/cpltracker.keystore` + `keystore-password.txt`) is not in git; keep a safe copy, every update must be signed with it.
- `assets/` – logos and the icon source (`icon.html`).

## Release
1. Edit `src/index.html`.
2. `./build.sh` and, for Android, raise `versionCode`/`versionName` in `android/AndroidManifest.xml` then run `android/build-apk.sh`.
3. Commit and push; GitHub Pages serves `docs/`.

## Students, uploads and live sync

- **Students** page: every student on the device, search/sort, open one to fill the tracker.
- **Upload records**: Smart exports and own spreadsheets (CSV/.xlsx, columns matched automatically and adjustable),
  tracker backups (.xlsx/.json/.html, or "Export all"), and PDF training records (text PDFs; scanned ones need typing).
  Every upload is shown for review before anything changes; completed lessons are never downgraded.
- **Shared database** (optional): `server/cpl_server.py`, Python standard library only. Run it behind an HTTPS proxy
  (e.g. Caddy) and give each instructor an access code (`add-user "Name" --role instructor`). Changes appear on every
  connected device within about a second; edits made at the same time on different lessons are merged; admins can read
  `/api/audit` and remove students.
- Source for this layer: `src/students.js` + `src/students.css`; run `python3 tools/inject.py` to put them into
  `src/index.html` (also stamps the build time), then `sh build.sh` and `sh android/build-apk.sh`.

## Live app updates

The Android app (1.2.0+) checks the website for a newer build on start (at most every 30 minutes), downloads it in
the background and offers **Restart now**; it falls back to the version inside the APK when offline. The installed web app
checks every 15 minutes. Publishing = push to `main` (GitHub Pages).

## Server (deployed 2026-10-31)

`https://coursetracker.online` (website + `/api`; the old `https://179-237-123-196.sslip.io` still answers) on the spare Infomaniak VPS (`ssh aurisse-mail`): `/opt/cpl-tracker/cpl_server.py`
as systemd `cpl-tracker` (user `cpltracker`, data `/var/lib/cpl-tracker`, daily backups in `backups/`, 30 days),
Caddy for HTTPS. Access codes: `cpl-users add "Name"` / `cpl-users add "Name" --role admin` / `cpl-users list` /
`cpl-users remove "Name"`.
