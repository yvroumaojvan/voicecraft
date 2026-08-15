#!/usr/bin/env bash
# 妙音工坊 APK 构建脚本（Termux 与 GitHub Actions 通用）
# 环境变量: ANDROID_JAR, AAPT, D8, ZIPALIGN, APKSIGNER
set -euo pipefail
cd "$(dirname "$0")"

BUILD="$(pwd)/build"
OUT="$(pwd)/out"
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

# 定位 android.jar
: "${ANDROID_JAR:=$(ls -d /data/data/com.termux/files/usr/share/android-sdk/platforms/*/android.jar 2>/dev/null | head -1)}"
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "${ANDROID_JAR}" ]; then
    echo "错误: 找不到 android.jar，请设置 ANDROID_JAR 环境变量" >&2
    exit 1
fi
echo "==> 使用 android.jar: $ANDROID_JAR"

AAPT="${AAPT:-aapt}"
D8="${D8:-d8}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"

# 1. 编译资源，生成 R.java
echo "==> 编译资源..."
"$AAPT" package -f -m -J "$BUILD" -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F "$BUILD/resources.apk"

# 2. javac 编译 Java 源码
echo "==> 编译 Java..."
javac -classpath "$ANDROID_JAR" -d "$BUILD/classes" \
    "$BUILD"/com/yvroumaojvan/voicecraft/R.java \
    src/com/yvroumaojvan/voicecraft/*.java

# 3. d8 生成 dex
echo "==> 生成 dex..."
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --release --lib "$ANDROID_JAR" --output "$BUILD/dex" @"$BUILD/classes.txt"

# 4. 组装 APK（资源 + classes.dex）
echo "==> 组装 APK..."
cp "$BUILD/resources.apk" "$OUT/voicecraft-unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$OUT/voicecraft-unsigned.apk" classes.dex)

# 5. zipalign 对齐
echo "==> 对齐..."
"$ZIPALIGN" -f 4 "$OUT/voicecraft-unsigned.apk" "$OUT/voicecraft-aligned.apk"

# 6. 签名
echo "==> 签名..."
KS="$OUT/voicecraft.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -alias voicecraft -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass 123456 -keypass 123456 \
        -dname "CN=VoiceCraft" 2>/dev/null
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
    --out "$OUT/voicecraft.apk" "$OUT/voicecraft-aligned.apk"

echo "=========================================="
ls -lh "$OUT/voicecraft.apk"
echo "==> 构建完成: $OUT/voicecraft.apk"
