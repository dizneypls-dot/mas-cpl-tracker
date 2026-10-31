#!/bin/sh
# Copies the built app to the website (docs/app/) with version.json, so installed apps offer the update.
# Usage: sh tools/publish-apk.sh "What's new in this version"
set -e
cd "$(dirname "$0")/.."
APK=android/build/CPLTracker.apk
[ -f "$APK" ] || { echo "Build first: sh android/build-apk.sh"; exit 1; }
CODE=$(grep -o 'versionCode="[0-9]*"' android/AndroidManifest.xml | grep -o '[0-9]*')
NAME=$(grep -o 'versionName="[^"]*"' android/AndroidManifest.xml | cut -d'"' -f2)
mkdir -p docs/app
cp "$APK" docs/app/CPLTracker.apk
python3 -I -c 'import json,sys; json.dump({"versionCode":int(sys.argv[1]),"versionName":sys.argv[2],"apk":"CPLTracker.apk","notes":sys.argv[3]},open("docs/app/version.json","w"),indent=1)' "$CODE" "$NAME" "${1:-A new version of CPL Tracker is ready.}"
cp "$APK" /sdcard/Download/CPLTracker.apk 2>/dev/null || true
echo "Published app $NAME ($CODE) to docs/app/"
