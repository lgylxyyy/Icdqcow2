# 功能测试台（testbench）

给 imgtool 安卓端用的**临时测试形态**：一页铺开 38 条命令，先确认功能可用，再谈正式 UI。

## 两种用法

### 1. 命令行（推荐，最快）
页面上半部有个输入框，直接敲：

```
iso.ls    --iso=<工作目录>/21h2_x64.iso
wim.size  --iso=<工作目录>/21h2_x64.iso
boot.plan --iso=<工作目录>/21h2_x64.iso
img.create --out=<工作目录>/test.qcow2 --size-gb=20 --table=gpt --esp-mb=200 --force=true
fs.format  --img=<工作目录>/test.qcow2 --lba=2048 --fs=fat32 --label=SYSTEM
img.info   --img=<工作目录>/test.qcow2
bcd.create --img=<工作目录>/test.qcow2 --desc="Windows 10"
boot.check --img=<工作目录>/test.qcow2 --lba=444416
qc.verify  --img=<工作目录>/test.qcow2
```

工作目录 = `/storage/emulated/0/Android/data/org.icdqcow2.imgtool.bench/files`
（页面上「插入工作目录」按钮会帮你把这段路径打进输入框）。

规则：`命令名 --参数=值`；`--参数 值`（空格分隔）也认；裸 `--flag` 当 true；
路径含空格就用双引号包住整个值。`--writable=false` 是测试台保留字（强制只读打开），
不会传给库。给了 `--img=` 就以它为准，不给则用上方「当前镜像」那一栏。

### 2. 复选框
每条命令一个复选框，勾开就露出参数（默认值按命令表预填）与「运行」，结果就地显示。
参数表由 `Native.list()` 自动生成 —— 所以命令表本身也是被测对象。

## 文件必须放哪儿

**放测试台自己的工作目录**，否则读不到（Android 分区存储，实测）：

```bash
adb push 你的.iso /storage/emulated/0/Android/data/org.icdqcow2.imgtool.bench/files/
```

## 已知环境事实（真机 Redmi / Android 17 实测）

- `ACTION_OPEN_DOCUMENT` 对第三方 App 抛 `ActivityNotFoundException`（即使 manifest 声明了
  `<queries>`，Google DocumentsUI 仍不响应）；`ACTION_GET_CONTENT` 可用（落到 MIUI 文件管理器）。
  测试台顺序：OPEN_DOCUMENT → GET_CONTENT → 内置工作目录列表。
  **正式 App 选文件不能只押 SAF OPEN_DOCUMENT。**
- 直接填 `/storage/emulated/0/xxx.iso` 这类根目录路径：App 身份 `Permission denied`。
  要么放工作目录，要么走 SAF。

## 重新构建

```bash
# 先定义三个位置，后面都用变量（本机的例子写在注释里）
REPO=$(cd "$(dirname "$0")" && pwd)                 # 本仓库检出
IMGTOOL=${IMGTOOL:-../imgtool}                      # imgtool 检出（含 android-cross.sh）
BUILD=${BUILD:-../build-android}                    # 交叉编译产物目录
NDK=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/<版本>}   # NDK 根目录

# 1) 原生库（细节见 imgtool 的 ANDROID-BUILD.md）
cd "$IMGTOOL" && API=28 ./android-deps.sh && API=28 ./android-cross.sh
for abi in arm64-v8a armeabi-v7a x86_64; do
  cmake -S "$REPO/jni" -B "$BUILD/jni-$abi" \
    -DCMAKE_TOOLCHAIN_FILE="$IMGTOOL/cmake/android-arm64host.cmake" \
    -DNDK_ROOT="$NDK" -DIMG_ANDROID_ABI=$abi -DIMG_ANDROID_API=28 \
    -DIMG_ANDROID_STAGE="$BUILD/stage/$abi" \
    -DIMGTOOL_ROOT="$IMGTOOL" -DIMGTOOL_LIB="$BUILD/$abi/libimgtool.so" \
    -DIMGAPP_BUILD_TEST=OFF && cmake --build "$BUILD/jni-$abi" -j8
  cp "$BUILD/$abi/libimgtool.so"        "$REPO/testbench/app/src/main/jniLibs/$abi/"
  cp "$BUILD/jni-$abi/libimgapp.so"     "$REPO/testbench/app/src/main/jniLibs/$abi/"
done
# 2) 打包（Gradle 版本见仓库根的 wrapper）
cd "$REPO/testbench" && ../gradlew :app:assembleDebug --no-daemon
# 3) 装（HyperOS 必须带 -i，否则被判“用户取消”）
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/bench.apk
adb shell pm install -r -t -i com.google.android.packageinstaller /data/local/tmp/bench.apk
```

看日志：`adb logcat -s BENCH:I`（每条命令与结果都会打到这里，我这边也能读）。
