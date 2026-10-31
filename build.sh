#!/bin/sh
# Builds the website (docs/, served by GitHub Pages):
#   docs/          the new website (src/web) — same records and design as the Android app
#   docs/classic/  the classic tracker (src/index.html) — progress report, Excel, email
# Android: android/build-apk.sh, then tools/publish-apk.sh to offer the update to installed apps.
set -e
cd "$(dirname "$0")"
python3 -I tools/inject.py >/dev/null
STAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
mkdir -p docs/classic docs/logos
cp src/index.html docs/classic/index.html
sed "s/BUILD/$STAMP/g" src/web/index.html > docs/index.html
cp src/web/app.js src/web/app.css src/web/report.js docs/
cp assets/mas-logo.png docs/logos/mas.png
cp assets/anac-logo.png docs/logos/anac.png
python3 -I tools/build-web.py docs >/dev/null
cat > docs/manifest.webmanifest <<JSON
{
  "name": "MAS CPL Progress Tracker",
  "short_name": "CPL Tracker",
  "description": "CPL student progress: training records, attendance and flight history, live across the school.",
  "id": "./",
  "start_url": "./",
  "scope": "./",
  "display": "standalone",
  "orientation": "any",
  "background_color": "#0b2a4f",
  "theme_color": "#0b2a4f",
  "icons": [
    {"src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
    {"src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
    {"src": "icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}
  ]
}
JSON
cat > docs/sw.js <<JS
// Offline support: always try the network first (so updates show at once), fall back to the last copy offline.
const CACHE='cpl-web-$STAMP';
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(['./','app.js','app.css','importer.js','report.js','catalogue.json','logos/mas.png','logos/anac.png'])).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(n=>n!==CACHE).map(n=>caches.delete(n)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request,u=new URL(r.url);
  if(r.method!=='GET'||u.origin!==location.origin||u.pathname.includes('/app/'))return;
  e.respondWith(fetch(r).then(res=>{if(res.ok){const copy=res.clone();caches.open(CACHE).then(c=>c.put(r,copy))}return res}).catch(()=>caches.match(r,{ignoreSearch:true}).then(h=>h||(r.mode==='navigate'?caches.match('./'):undefined))));
});
JS
touch docs/.nojekyll
echo "Built docs/ ($STAMP)"
