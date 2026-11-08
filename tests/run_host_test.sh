#!/bin/bash
# run_host_test.sh — App/JNI 层的主机回归（不需要 Android SDK）
#
# 覆盖：
#   1. 文案表与 imgtool 真实命令表/错误码对账（check_i18n.py）
#   2. C 桥：命令表 / 真实镜像 / SAF fd（/proc/self/fd）/ 事件 / 取消复位 / lastError
#   3. JNI：编译 libimgapp.so，用 Java 调同一组接口（有 JDK 才跑）
#
# 用法：bash app/Icdqcow2/tests/run_host_test.sh
set -u
APP=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$APP/.." && pwd)                       # /workspace/app
IT="${IMGTOOL_DIR:-$ROOT/../tools/imgtool}"       # /workspace/tools/imgtool
T="$IT/build/tools"
W=$(mktemp -d /tmp/apptestXXXXXX)
pass=0; fail=0
ok(){ echo "  ✅ $1"; pass=$((pass+1)); }
ng(){ echo "  ❌ $1"; fail=$((fail+1)); }

echo "════ 0. 前置检查 ════"
[ -x "$T/imglayout" ] && [ -x "$T/imgmkfs" ] || {
  echo "❌ 缺 imgtool 构建产物：$T（先按 docs/工具总览 构建）"; exit 2; }
[ -f "$IT/build/libimgtool.so" ] || { echo "❌ 缺 libimgtool.so"; exit 2; }
ok "imgtool 构建产物齐全"

echo "════ 1. 文案表对账 ════"
if python3 "$APP/i18n/check_i18n.py"; then ok "文案表完整"; else ng "文案表缺项"; fi

echo "════ 2. 造测试盘（2GiB GPT + 64MiB ESP）════"
$T/imglayout mk "$W/t.qcow2" $((2*1024*1024*1024)) gpt --esp 64 --msr 16 >/dev/null 2>&1 || { echo "建盘失败"; exit 2; }
ESP=$($T/imgpart show "$W/t.qcow2" | python3 -c "import sys,json;d=json.load(sys.stdin);print([p['first'] for p in d['parts'] if p['typeGuid'].startswith('c12a7328')][0])")
$T/imgmkfs "$W/t.qcow2" --mkfs "$ESP" TEST >/dev/null 2>&1
echo "  ESP LBA=$ESP"
echo "jni bridge test" > "$W/a.txt"

echo "════ 3. C 桥 ════"
if gcc -O2 -Wall -Wextra -I "$IT/include" -I "$APP/jni" \
      "$APP/jni/imgapp_bridge.c" "$APP/jni/test_bridge.c" "$IT/build/libimgtool.a" \
      -lntfs-3g -lwim -lz -lhivex -lpthread -o "$W/test_bridge" 2>"$W/build.log"; then
  ok "C 桥编译零错误"
else
  ng "C 桥编译失败"; tail -10 "$W/build.log"
fi
if [ -x "$W/test_bridge" ]; then
  if "$W/test_bridge" "$W/t.qcow2" "$ESP" "$W/a.txt"; then ok "C 桥用例全过"
  else ng "C 桥用例失败"; fi
fi

echo "════ 4. JNI（有 JDK 才跑）════"
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  JH=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
  if gcc -shared -fPIC -O2 -Wall -Wextra -I "$IT/include" -I "$APP/jni" \
        -I "$JH/include" -I "$JH/include/linux" \
        "$APP/jni/imgapp_bridge.c" "$APP/jni/imgapp_jni.c" \
        -L "$IT/build" -limgtool -Wl,-rpath,"$IT/build" -lpthread \
        -o "$W/libimgapp.so" 2>"$W/jni.log"; then
    ok "libimgapp.so 编译零错误"
  else
    ng "libimgapp.so 编译失败"; tail -10 "$W/jni.log"
  fi
  mkdir -p "$W/classes"
  if javac -d "$W/classes" "$APP/jni/java/org/icdqcow2/imgtool/Native.java" \
        "$APP/jni/java/TestMain.java" 2>"$W/javac.log"; then
    ok "Java 类编译通过"
    if java -Djava.library.path="$W" -cp "$W/classes" TestMain "$W/t.qcow2" "$ESP" "$W/a.txt"; then
      ok "JNI 端到端用例全过"
    else ng "JNI 用例失败"; fi
  else
    ng "Java 类编译失败"; tail -10 "$W/javac.log"
  fi
else
  echo "  ⏭ 跳过 JNI（没有 javac/java；apt install openjdk-17-jdk-headless）"
fi

echo
echo "════ 结果：通过 $pass · 失败 $fail ════"
if [ $fail -eq 0 ]; then echo "✅ App/JNI 层主机回归通过"; rm -rf "$W"
else echo "❌ 有失败（调试目录 $W 保留）"; fi
[ $fail -eq 0 ]