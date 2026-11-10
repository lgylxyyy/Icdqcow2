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
# 产物可能不在仓库内（imgtool 的 scripts/env.sh 把 IMGTOOL_BUILD_ROOT 指到工作区外）
LIB="$(find build -path "*/$ABI/libimgtool.so" 2>/dev/null | head -1)"
if [ -z "$LIB" ] && [ -f "${IMGTOOL_BUILD_ROOT:-$IMGTOOL/build}/android/$ABI/libimgtool.so" ]; then
  LIB="${IMGTOOL_BUILD_ROOT:-$IMGTOOL/build}/android/$ABI/libimgtool.so"
fi
if [ -z "$LIB" ] || [ ! -f "$LIB" ]; then
  echo "没找到 libimgtool.so（找过 build/ 与 \${IMGTOOL_BUILD_ROOT:-<repo>/build}/android/$ABI）" >&2
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
  # ★ 16 KB 页：与 jni/CMakeLists.txt 里的 target_link_options 同一组参数
  clang --target="$TARGET" --sysroot="$SYS" -rtlib=compiler-rt -O2 -fPIC -shared \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -I jni -I "$IMGTOOL/include" \
    jni/imgapp_bridge.c jni/imgapp_jni.c jni/img_vfd_real.c \
    -L"$(dirname "$LIB")" -limgtool \
    -o "$DEST/libimgapp.so"
fi
md5sum "$DEST/libimgapp.so"

# ---- 16 KB 页对齐自检（Android 16 起是硬要求）----
# 判定：每个 PT_LOAD 的 p_align 必须是 0x4000；不是就当场失败，别等装到手机上才发现。
check_16k() {
  local f="$1"
  local aligns
  aligns="$(readelf -lW "$f" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
  if [ "$aligns" != "0x4000 " ]; then
    echo "  ❌ $(basename "$f") 的 LOAD 对齐是 [$aligns]（要求 0x4000）" >&2
    return 1
  fi
  echo "  ✅ $(basename "$f") LOAD 对齐 0x4000（16 KB）"
}
check_16k "$DEST/libimgtool.so" || exit 1
check_16k "$DEST/libimgapp.so" || exit 1
echo
echo "把下面两行同步进 tools/native-libs.md5（并更新「来源」里的体积/提交）："
md5sum "$DEST/libimgtool.so" "$DEST/libimgapp.so"

echo "==> 完成。请把新 md5 同步到 tools/native-libs.md5"
