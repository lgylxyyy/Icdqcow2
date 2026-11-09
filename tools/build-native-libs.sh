#!/bin/bash
# 重新生成 native 库（App 的 JNI 桥 + 工具端 C 库）。
#
# 何时需要：
#   · 工具端 imgtool 仓库更新了 → 重编 libimgtool.so
#   · 改了本仓库 jni/ 下的 C 代码 → 重编 libimgapp.so
#
# 用法：
#   tools/build-native-libs.sh [imgtool 仓库路径]
#
# 依赖：Android NDK（ndkVersion 见 app/build.gradle.kts）、cmake、git
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMGTOOL="${1:-/workspace/tools/imgtool}"
ABI="arm64-v8a"
DEST="$ROOT/app/src/main/jniLibs/$ABI"

if [ ! -d "$IMGTOOL" ]; then
  echo "找不到 imgtool 仓库：$IMGTOOL" >&2
  echo "先 clone https://github.com/lgylxyyy/imgtool" >&2
  exit 1
fi

echo "==> 构建工具端库（$IMGTOOL）"
cd "$IMGTOOL"
./android-deps.sh
./android-cross.sh "$ABI"
LIB="$(find build -path "*/$ABI/libimgtool.so" | head -1)"
if [ -z "$LIB" ]; then
  echo "没找到构建产物 build/android/$ABI/libimgtool.so" >&2
  exit 1
fi

echo "==> 复制到 $DEST"
mkdir -p "$DEST"
cp "$LIB" "$DEST/libimgtool.so"
md5sum "$DEST/libimgtool.so"

echo "==> 编译 App 的 JNI 桥 libimgapp.so"
cd "$ROOT"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/workspace/sdk/android-sdk}/ndk/$(ls "${ANDROID_HOME:-/workspace/sdk/android-sdk}/ndk" 2>/dev/null | sort -V | tail -1)}"
cmake -S jni -B build/jni -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-24 \
      -DIMGTOOL_ROOT="$IMGTOOL"
cmake --build build/jni
cp "build/jni/libimgapp.so" "$DEST/libimgapp.so"
md5sum "$DEST/libimgapp.so"

echo "==> 完成。请把新 md5 同步到 tools/native-libs.md5"
