#!/usr/bin/env bash
# Builds dexsearch.apk without Gradle. Needs: Android SDK (platform + build-tools), JDK 17+, Kotlin 2.x compiler
# (kotlinc on PATH, or KOTLINC=/path/to/kotlinc).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="${BUILD_TOOLS:-36.0.0}"
PLATFORM="${PLATFORM:-android-36}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
TOOLS="$SDK/build-tools/$BT"
KOTLINC="${KOTLINC:-$(command -v kotlinc || echo "$HOME/.local/opt/kotlinc/bin/kotlinc")}"
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
cd "$(dirname "$0")"

rm -rf build && mkdir -p build/classes build/dex
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android \
    -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=debug" >/dev/null

"$TOOLS/aapt2" compile --dir res -o build/res.zip
"$TOOLS/aapt2" link --manifest AndroidManifest.xml -I "$JAR" -A assets build/res.zip \
    --min-sdk-version 31 --target-sdk-version 35 -o build/base.apk --proguard build/rules.pro
"$KOTLINC" -cp "$JAR" -jvm-target 17 -d build/classes src/demo/dex/*.kt
java -cp "build/classes:$STDLIB" demo.dex.Dex assets
# R8 drops the unused part of the Kotlin stdlib (2.8 MB of dex without it). Activities are kept by the rules aapt2
# derives from the manifest. R8 8.10 reads Kotlin metadata only up to 2.2 and prints "malformed kotlin.Metadata"
# for newer compilers; the app does no Kotlin reflection, so those lines are noise.
printf -- '-dontobfuscate\n-keepattributes SourceFile,LineNumberTable\n-dontwarn org.jetbrains.annotations.**\n' \
    > build/app.pro
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.R8 --release --lib "$JAR" --min-api 31 --output build/dex \
    --pg-conf build/rules.pro --pg-conf build/app.pro $(find build/classes -name '*.class') "$STDLIB" > build/r8.log 2>&1 \
    || { cat build/r8.log; exit 1; }
grep -E '^(Warning|Error)' build/r8.log | grep -v 'kotlin metadata' || true
(cd build/dex && zip -q ../base.apk classes.dex)
"$TOOLS/zipalign" -f 4 build/base.apk build/aligned.apk
"$TOOLS/apksigner" sign --ks debug.keystore --ks-pass pass:android \
    --out dexsearch.apk build/aligned.apk
echo "built: $PWD/dexsearch.apk"
