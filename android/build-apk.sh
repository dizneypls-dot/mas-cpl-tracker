#!/bin/sh
# Builds the native CPL Tracker app (CPLTracker.apk). The syllabus comes from ../src/index.html.
# Needs: ~/.cache/chess-apk (android.jar + d8 as r8.jar), the SDK aapt (AAPT), zipalign, apksigner, keytool.
set -e
cd "$(dirname "$0")"
SDK=$HOME/.cache/chess-apk
AAPT=${AAPT:-/root/android-sdk/aapt2-qemu/aapt}
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/assets $OUT/dex $OUT/gen
python3 -I - ../src/index.html $OUT/assets/catalogue.json <<'PY'
import json, re, sys
s = open(sys.argv[1], encoding="utf-8").read()
cat = json.loads(re.search(r"const lessonCatalogue=(\{.*?\});\n", s, re.S).group(1))
ini = json.loads(re.search(r'<script id="initial" type="application/json">(.*?)</script>', s, re.S).group(1))
order = [str(l["id"]) for l in ini["lessons"]]
ini.update({"studentId": "", "instructor": "", "course": "", "start": "", "review": "", "attendance": [], "flights": [], "milestones": {}})
for e in ini["exams"]:
    if e.get("status") in ("Not started", "In progress"): e["status"] = "Not attempted"
json.dump({"order": order, "lessons": cat, "template": ini}, open(sys.argv[2], "w"))
PY
cp ../assets/mas-logo.png ../assets/anac-logo.png $OUT/assets/
cp report.html ../src/web/report.js $OUT/assets/
mkdir -p $OUT/assets/fonts && cp fonts/*.ttf $OUT/assets/fonts/
$AAPT package -f -m -J $OUT/gen -M AndroidManifest.xml -S res -I $SDK/android.jar
javac --release 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath $SDK/android.jar -d $OUT/classes $(find src $OUT/gen -name '*.java')
java -cp $SDK/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $SDK/android.jar \
  --output $OUT/dex $(find $OUT/classes -name '*.class')
$AAPT package -f -M AndroidManifest.xml -S res -A $OUT/assets -I $SDK/android.jar -F $OUT/unsigned.apk
(cd $OUT/dex && $AAPT add ../unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
# One signing key for every build, so new versions install as updates and keep the data on the phone.
if [ ! -f cpltracker.keystore ]; then
  PW=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  keytool -genkeypair -keystore cpltracker.keystore -storepass "$PW" -keypass "$PW" -alias cpltracker \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CPL Tracker" >/dev/null 2>&1
  echo "$PW" > keystore-password.txt
fi
PW=$(cat keystore-password.txt)
apksigner sign --ks cpltracker.keystore --ks-pass pass:$PW --ks-key-alias cpltracker --out $OUT/CPLTracker.apk $OUT/aligned.apk
rm -f $OUT/CPLTracker.apk.idsig
apksigner verify $OUT/CPLTracker.apk && echo "Built $(pwd)/$OUT/CPLTracker.apk"
