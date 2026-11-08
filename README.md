# Icdqcow2

**不用电脑、不用 root，在手机上直接管理你的 qcow2 虚拟机磁盘。**

建盘、分区、格式化、把 Windows 装进去、改盘里的文件、修引导 —— 全部在一个 App 里完成。
它不启动虚拟机（那是 VectrasVM / Limbo 的事），只负责**把盘做好、改好**，
做完的镜像直接丢给虚拟机启动。

> 底层是自研的 C 库 [imgtool](https://github.com/lgylxyyy/imgtool)，
> Android 交叉编译在同一仓库（[imgtool](https://github.com/lgylxyyy/imgtool)）里，用 `./android-cross.sh`。
> 完全离线，不联网、不上传任何东西。

---

## 它能做什么

| 功能 | 说明 |
|---|---|
| **建盘** | 选容量，自动按 MBR（BIOS）或 GPT（UEFI）排好 ESP / MSR / 系统 / 恢复分区 |
| **分区管理** | 查看、新建、删除、调整大小、改分区属性、导出/导入整张分区表 |
| **格式化** | FAT32 / NTFS / exFAT |
| **释放映像** | 把官方 Windows 的 `install.wim` / `.esd` / ISO 直接灌进分区（三步向导，带进度） |
| **不格式化安装** | 保留旧系统：自动把根目录搬进 `Windows.old`，不弹错误、不丢数据 |
| **引导** | 一键铺 `bootmgr` / `bootmgfw.efi` / `boot.sdi` / 字体 + 生成 BCD；支持 **BIOS / UEFI 64 / UEFI 32** |
| **引导自检** | 按启动方式逐项检查（引导管理器、ESP 兜底、BCD、加载器、分区表），红叉点进去自动修复 |
| **WinRE** | 把 `winre.wim` 装进恢复分区或系统分区，生成 `ReAgent.xml` 与恢复脚本 |
| **文件管理** | 双栏浏览盘内 FAT32 / NTFS，也支持直接浏览 ISO、WIM 与 zip/7z/tar/RAR 压缩包 |
| **文件操作** | 复制、移动、改名、删除（进回收站）、压缩成 zip、跨栏拖拽，多选批量 |
| **压缩回收** | 把镜像里空洞压实，体积能小几个 GB |
| **文本编辑器** | 内置编辑器，能直接改盘里的 `boot.ini`、`unattend.xml`、`BCD` 之外的配置文件；行号、查找替换、语法高亮 |
| **全量自检** | 一次检查分区表、文件系统、引导链，给出结论说明 |

## 界面怎么用

底部三个标签：**主页 / 文件 / 设置**。所有操作都进二级页（从右往左推入）。

**主页** —— 顶部是「全流程装机」大卡，它会按 选镜像 → 建盘/选盘 → 释放 → 全量自检
的顺序带你走完一次安装，中途退出会问你保不保存进度。下面按组排列：

```
磁盘      建新盘 · 分区管理 · 导出分区表 · 压缩镜像
系统部署  释放映像 · 引导修复 · 恢复环境(WinRE) · 全量自检
```

**文件** —— 左右两栏各自独立浏览，可以一边开镜像 A 的 NTFS、一边开镜像 B 的 FAT32：

| 操作 | 手势 |
|---|---|
| 进目录 / 打开文件 | 单击（回收站里的文件是"是否还原"） |
| 复制 / 移动 / 改名 / 删除 / 压缩 | 长按弹菜单（只读位置会把不能做的项直接藏起来，不是灰） |
| 多选 | 长按 → 选「多选」，选集钉在发起的那一栏，切到对面照常浏览 |
| 复制到对面 | 多选后点「复制」，产物落在**对面那栏的当前目录** |

长任务（复制/移动/压缩/删除/灌系统）会弹**阻塞式进度卡**：进度条 +「N / M 项 · 已用/总量」+
当前文件名，挡住触摸和返回键，只留「取消」。

**设置** —— 主题（跟随系统/浅色/深色）、语言（中/英）、渐变与液态玻璃特效开关、
错误码手册、运行日志、开源许可。

## 安装

- **要求**：Android 13（API 33）及以上，arm64-v8a。
- **权限**：首次启动会申请"所有文件访问权限"（`MANAGE_EXTERNAL_STORAGE`），
  因为要直接读写 `/sdcard` 下的 `.qcow2` / `.iso` / `.wim`。不给就只能在 App 私有目录里操作。
- 装好后的包名：`org.icdqcow2`。

> ⚠️ **操作前请在虚拟机里关机**。App 直接改镜像字节，虚拟机运行时改会损坏文件系统。

## 自己构建

前置：JDK 17、Android SDK（platform 37、build-tools 37.0.0）、Gradle 9.8。

```bash
# 1) 先拿到 C 库（本仓库不含 .so）
git clone https://github.com/lgylxyyy/imgtool
cd imgtool && ./android-deps.sh && ./android-cross.sh arm64-v8a

# 2) 放进 App 的 jniLibs
cp build/android/arm64-v8a/libimgtool.so \
   ../Icdqcow2/app/src/main/jniLibs/arm64-v8a/libimgtool.so

# 3) 构建
cd ../Icdqcow2
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

装机与核对：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package org.icdqcow2 | grep lastUpdateTime
```

**文案表校验**（改了任何界面文案都要跑）：

```bash
python3 tools/check_strings.py     # 缺 key / 多 key / 占位符不一致都会失败
```

## 技术栈

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin / Kotlin | 9.4.1 / 2.4.20 |
| Compose BOM | 2025.09.00（只用了 foundation/ui，不用 Material 主题） |
| Miuix（小米 HyperOS 风格组件库） | 0.9.4 |
| 编辑器内核 sora-editor | 0.23.6 |
| 液态玻璃 | `io.github.kyant0:backdrop` 2.0.1 / `shapes` 1.2.1 |
| JNI | 自研薄层（`jni/`），`.so` 由 imgtool 的 android-cross.sh 产出 |

## 用到的开源项目

| 组件 | 许可证 | 用途 |
|---|---|---|
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | UI 框架 |
| [Kotlin / kotlinx.coroutines](https://kotlinlang.org) | Apache-2.0 | 语言与协程 |
| [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) | Apache-2.0 | 组件、图标、毛玻璃、偏好设置 |
| [sora-editor](https://github.com/Rosemoe/sora-editor) | LGPL-2.1 | 代码编辑器内核（按行虚拟化，能开 19 KB 一行的 XML） |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 底部导航的折射背景 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | 可选：借 shell 权限访问普通应用进不去的目录 |
| [libntfs-3g](https://github.com/tuxera/ntfs-3g) | LGPL-2.1+ | NTFS 读写（在 `libimgtool.so` 内） |
| [wimlib](https://wimlib.net) | LGPL-2.1+ | WIM/ESD 解压（同上） |
| [zlib](https://zlib.net) | Zlib | qcow2 压缩簇（同上） |
| [UnRAR](https://www.rarlab.com/rar_add.htm) | UnRAR 自由软件许可 | 只读解 RAR（同上） |
| [mkntfs](https://github.com/tuxera/ntfs-3g/tree/master/ntfsprogs) | **GPL-2.0+** | NTFS 格式化（同上，可选） |
| [Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons) | MIT | 文件类型图标 |

## 许可

- App 自有代码：**GPL-2.0-or-later**。
  原因：随 App 分发的 `libimgtool.so` 默认带 `mkntfs`（GPL-2.0+），
  整个二进制按 GPL 分发是最省事的合规口径。
- 许可证全文在 `LICENSES/`：GPL-2.0 / LGPL-2.1 / Apache-2.0 / BSD-3-Clause / MIT / Zlib / UnRAR。
- 逐项版权与修改说明见 `THIRD_PARTY.md`。
- **LGPL 组件**（sora-editor、libntfs-3g、wimlib）的源码与构建脚本都在 GitHub 上，
  你可以自行替换/重新链接。

## 隐私与安全

- **不联网**：没有网络权限，没有统计、没有账号、没有云同步。
- **不上传**：所有读写都在本机。
- **不含签名**：仓库里没有任何 `.jks` / `.keystore` / `*.p12`（见 `.gitignore`），
  正式签名请自己生成并放在仓库外。
- **改盘有风险**：修改前请关机、备份重要镜像。App 会在写前检查宿主剩余空间，
  但它不是文件系统驱动，做不到断电保护。

## 已知限制

- 只支持 qcow2；VMDK / VHDX / raw 请先转换。
- 压缩包只读（能浏览、解压，不能新建）；RAR 不支持写。
- 32 位 UEFI 只支持 Windows 8 x86 及以上；Vista / Win7 的 x86 版本物理上没有 EFI 引导文件。
- 加密的 WIM / ESD 不支持。
- 编辑盘内文本文件后写回容器：目前支持"保存到对面一栏"，直接写回原容器在计划中。