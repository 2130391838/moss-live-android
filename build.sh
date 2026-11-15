#!/bin/bash
# 不依赖 Gradle / Android Studio：aapt2 + javac + d8 + apksigner 直接出 APK
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17.0.21+9/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] 链接资源与清单"
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "[3/6] 编译 Java"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
  -cp "$PLATFORM:libs/okhttp-3.14.9.jar:libs/okio-1.17.2.jar" \
  -d build/classes \
  $(find src build/gen -name '*.java')

echo "[4/6] 转 dex"
"$BT/d8" --min-api 26 --lib "$PLATFORM" \
  --output build/dex \
  $(find build/classes -name '*.class') \
  libs/okhttp-3.14.9.jar libs/okio-1.17.2.jar

echo "[5/6] 打包"
(cd build/dex && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

echo "[6/6] 签名"
if [ ! -f moss.keystore ]; then
  keytool -genkeypair -keystore moss.keystore -alias moss \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass mosslive -keypass mosslive \
    -dname "CN=MOSS Live,O=MossLocal,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks moss.keystore --ks-pass pass:mosslive --key-pass pass:mosslive \
  --out MOSS-Live.apk build/aligned.apk

"$BT/apksigner" verify --print-certs MOSS-Live.apk | head -3
ls -lh MOSS-Live.apk
echo "完成：$(pwd)/MOSS-Live.apk"
