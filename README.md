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
