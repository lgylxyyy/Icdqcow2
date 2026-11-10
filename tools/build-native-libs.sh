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
IMGTOOL="${1:-$(cd "$(dirname "$0")/../.." && pwd)/tools/imgtool}"
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
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  NDK="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
fi
[ -d "$NDK" ] || { echo "找不到 NDK：export ANDROID_NDK_HOME=<ndk 目录>" >&2; exit 1; }

# ★ 2026-11-10（REQ-70 Q1）：Google 的 NDK 只发 linux-x86_64 宿主二进制。
#   在 arm64 主机上（本工作区就是）那些 clang 直接 exec 不了，
#   CMake 的 android.toolchain.cmake 会报 "C compiler is not able to compile"。
#   这时退到"宿主 clang + NDK sysroot"（与 imgtool 的 cmake/android-arm64host.cmake 同一招）。
NDK_CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
if [ -x "$NDK_CLANG" ] && "$NDK_CLANG" --version >/dev/null 2>&1; then
  cmake -S jni -B build/jni -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-24 \
        -DIMGTOOL_ROOT="$IMGTOOL"
  cmake --build build/jni
  cp build/jni/libimgapp.so "$DEST/libimgapp.so"
else
  echo "    NDK 自带 clang 跑不了（$(uname -m) 宿主）→ 用宿主 clang + sysroot"
  SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  TARGET="aarch64-linux-android24"
  [ "$ABI" = "arm64-v8a" ] || { echo "该回退只覆盖 arm64-v8a" >&2; exit 1; }
  clang --target="$TARGET" --sysroot="$SYS" -rtlib=compiler-rt -O2 -fPIC -shared \
    -I jni -I "$IMGTOOL/include" \
    jni/imgapp_bridge.c jni/imgapp_jni.c jni/img_vfd_real.c \
    -L"$IMGTOOL/build/android/$ABI" -limgtool \
    -o "$DEST/libimgapp.so"
fi
md5sum "$DEST/libimgapp.so"
echo
echo "把下面两行同步进 tools/native-libs.md5（并更新「来源」里的体积/提交）："
md5sum "$DEST/libimgtool.so" "$DEST/libimgapp.so"

echo "==> 完成。请把新 md5 同步到 tools/native-libs.md5"
