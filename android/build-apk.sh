#!/bin/sh
# Builds CPLTracker.apk from ../src/index.html. Re-run after editing the page.
# Needs: ~/.cache/chess-apk (android.jar + d8 as r8.jar), the SDK aapt (AAPT), zipalign, apksigner, keytool.
set -e
cd "$(dirname "$0")"
SDK=$HOME/.cache/chess-apk
AAPT=${AAPT:-/root/android-sdk/aapt2-qemu/aapt}
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/assets $OUT/dex
cp ../src/index.html $OUT/assets/index.html
javac --release 8 -nowarn -Xlint:-options -classpath $SDK/android.jar -d $OUT/classes src/app/cpltracker/mas/*.java
java -cp $SDK/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $SDK/android.jar \
  --output $OUT/dex $(find $OUT/classes -name '*.class')
$AAPT package -f -M AndroidManifest.xml -S res -A $OUT/assets -I $SDK/android.jar -F $OUT/unsigned.apk
(cd $OUT/dex && $AAPT add ../unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
# One signing key for every build, so new versions install as updates and keep the student data.
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
