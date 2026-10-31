#!/bin/sh
# Builds the web app (docs/, served by GitHub Pages) from src/index.html.
# Android: android/build-apk.sh (uses the same src/index.html).
set -e
cd "$(dirname "$0")"
cp src/index.html docs/index.html
VERSION=$(sha256sum src/index.html | cut -c1-12)
cat > docs/manifest.webmanifest <<JSON
{
  "name": "MAS CPL Progress Tracker",
  "short_name": "CPL Tracker",
  "description": "CPL student progress: practical lessons, theory exams, course timeline and progress reports.",
  "id": "./",
  "start_url": "./",
  "scope": "./",
  "display": "standalone",
  "orientation": "any",
  "background_color": "#f3f5f8",
  "theme_color": "#0b2a4f",
  "icons": [
    {"src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
    {"src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
    {"src": "icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}
  ]
}
JSON
cat > docs/sw.js <<JS
// Offline support. The page is fetched fresh when online (so updates show at once) and served from cache offline.
const CACHE='cpl-tracker-$VERSION';
const FILES=['./','index.html','manifest.webmanifest','icons/icon-192.png','icons/icon-512.png','icons/icon-maskable-512.png','icons/apple-touch-icon.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(n=>n!==CACHE).map(n=>caches.delete(n)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request;
  if(r.method!=='GET'||new URL(r.url).origin!==location.origin)return;
  if(r.mode==='navigate'){
    e.respondWith(fetch(r).then(res=>{const copy=res.clone();caches.open(CACHE).then(c=>c.put('index.html',copy));return res}).catch(()=>caches.match('index.html')));
    return;
  }
  e.respondWith(caches.match(r).then(hit=>hit||fetch(r)));
});
JS
touch docs/.nojekyll
echo "Built docs/ (version $VERSION)"
