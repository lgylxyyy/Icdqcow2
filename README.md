<div align="center">

<img src="assets/icon.png" width="140" height="140" alt="Icdqcow2 应用图标" />

# Icdqcow2

**在 Android 设备上离线管理 qcow2 虚拟机磁盘：建盘、分区、装系统、改文件、修引导。**

[English](README_EN.md) | [**中文**](README.md)

[![License](https://img.shields.io/badge/license-GPL--2.0--or--later-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)](https://kotlinlang.org)

</div>

不启动虚拟机，不依赖 root，不联网。产出的镜像可直接交给 VectrasVM / Limbo / QEMU 启动。

> 底层库：[imgtool](https://github.com/lgylxyyy/imgtool)（C11）。
> Android 版由同一仓库的 `./android-cross.sh` 交叉编译为 `libimgtool.so`。

---

## 快速开始

| 步骤 | 操作 |
|---|---|
| 1 | 从 [Releases](https://github.com/lgylxyyy/Icdqcow2/releases) 下载 APK |
| 2 | 安装后授予「管理所有文件」权限（Android 11+ 必需） |
| 3 | 首页 → 「全流程装机」→ 按向导选择 ISO、建盘、释放、自检 |

| 页面 | 内容 |
|---|---|
| [功能](#功能) | 磁盘、部署、引导、文件、自检 |
| [界面](#界面) | 三个主标签与操作手势 |
| [构建](#构建) | 从源码构建 APK |
| [许可证](#许可证) | 本项目与全部第三方组件 |

---

## 功能

> 版本变更见 [CHANGELOG.md](CHANGELOG.md)。

| 类别 | 能力 |
|---|---|
| **磁盘** | 建盘（MBR/BIOS 与 GPT/UEFI 自动布局）；分区查看、新建、删除、调整、属性修改、导出/导入整表；压缩回收空洞 |
| **文件系统** | 格式化 FAT32 / NTFS；浏览与读写盘内文件；复制、移动、改名、删除（入回收站）、压缩为 zip |
| **系统部署** | `install.wim` / `.esd` / ISO 直接灌入分区；非格式化安装（旧系统移入 `Windows.old`） |
| **引导** | 一键铺设 `bootmgr` / `bootmgfw.efi` / `boot.sdi` / 字体并生成 BCD；支持 BIOS、UEFI 64、UEFI 32 |
| **引导自检** | 按启动方式逐项检查引导管理器、ESP 兜底入口、BCD、加载器、分区表；失败项可一键修复 |
| **恢复环境** | 将 `winre.wim` 装入恢复分区或系统分区，生成 `ReAgent.xml` 与恢复脚本 |
| **容器浏览** | 直接浏览 ISO、WIM/ESD 与 zip / 7z / tar / tgz / RAR 压缩包；解压、打包、跨容器复制 |
| **全量自检** | 一次检查分区表、文件系统、引导链，给出结论与修复建议 |
| **文本编辑** | 编辑盘内配置文件，支持行号、查找替换 |

**格式支持**

| 格式 | 读取 | 写入 | 建盘 |
|---|---|---|---|
| qcow2 | ✅ | ✅ | ✅ |
| raw（.img / .raw） | ✅ | ✅ | ✅ |
| VHD | ✅ | ✅ | ⚠️ 需由 raw 转换 |
| VMDK | ✅ | ✅ | ✅ |

**设计边界**

- 不运行虚拟机，仅准备与修改镜像；
- 镜像必须**关机**：App 与虚拟机不得同时访问同一镜像；
- 全程离线，无网络请求，无遥测。

---

## 界面

底部三个主标签，所有操作进入二级页（右滑返回）。

| 标签 | 内容 |
|---|---|
| **主页** | 顶部「全流程装机」引导卡（选镜像 → 建盘 → 释放 → 自检，可保存进度）；下方按组排列磁盘、系统部署、引导、恢复、自检入口 |
| **文件** | 双栏独立浏览，可同时打开镜像 A 的 NTFS 与镜像 B 的 FAT32 |
| **设置** | 主题、语言、特效开关、错误码手册、运行日志、开源许可 |

**文件页手势**

| 操作 | 手势 |
|---|---|
| 进入目录 / 打开文件 | 单击 |
| 复制 / 移动 / 改名 / 删除 / 压缩 | 长按弹出菜单（不可用项直接隐藏） |
| 多选 | 长按 → 「多选」；选集固定在发起栏，另一栏可继续浏览 |
| 复制到对面 | 多选后选「复制」，目标为对面栏当前目录 |

长任务（复制、移动、压缩、删除、灌系统）弹出阻塞式进度卡：进度条、`已完成 / 总数`、
当前文件名，仅保留「取消」。

---

## 构建

### 前置

- JDK 17、Android SDK（compileSdk 37、targetSdk 35、minSdk 33）
- Android NDK r28c（编译 `libimgtool.so`）
- CMake ≥ 3.16、宿主 clang

### 步骤

```bash
# 1) 取得 C 库（本仓库不含预编译 .so）
git clone https://github.com/lgylxyyy/imgtool
cd imgtool && ./android-deps.sh && ./android-cross.sh arm64-v8a

# 2) 放入 App
cp build/android/arm64-v8a/libimgtool.so \
   ../Icdqcow2/app/src/main/jniLibs/arm64-v8a/

# 3) 构建 APK
cd ../Icdqcow2 && ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`.so` 为构建产物，不入库。更换后须重新安装 APK。核对是否生效：

```bash
md5sum build/android/arm64-v8a/libimgtool.so
adb shell "md5sum /data/app/*/org.icdqcow2-*/lib/arm64/libimgtool.so"
```

---

## 签名

发布签名密钥**不在本仓库**，也不在任何提交历史里：`*.jks` / `keystore.properties` 由 `.gitignore` 与 `tools/hooks/pre-commit` 双重拦截（连改名成 `.bin` 的密钥库、藏在 `.txt` 里的 PEM 私钥也拦）。

构建时从**仓库根**的 `keystore.properties` 读取（格式见 `keystore.properties.example`）：

```properties
storeFile=$HOME/keys/release.jks          # 仓库外的绝对路径
storePassword=****
keyAlias=****
keyPassword=****
```

**没有这个文件就自动回落 debug 签名**——clone 下来无需任何密钥即可编译、安装。

### 发布证书（公开信息，可用于校验 APK 是否正品）

| 项 | 值 |
|---|---|
| Subject | `CN=Icdqcow2, OU=App, O=icdqcow2, C=CN` |
| 密钥算法 | RSA 4096 / SHA384withRSA |
| SHA-256 指纹 | `82:3F:40:BA:39:91:28:55:D7:AC:5E:C9:EB:3B:1F:18:D8:B7:E0:40:43:D3:90:BA:B5:EA:58:00:AB:A1:FB:E3` |
| 有效期 | 2026-11-10 → 2054-03-28（10000 天） |

```bash
# 校验手上的 APK 是不是本项目的正式签名
apksigner verify --print-certs your.apk
# 输出中的 certificate SHA-256 digest 应与上表完全一致
```

> 指纹是证书摘要，公开无害；**私钥与密码永不入库**。密钥文件放在仓库外（目录权限 700、文件 600），并另存两份加密离线备份——密钥丢失意味着此后无法再更新此应用。

---

## 技术栈

| 层 | 实现 |
|---|---|
| UI | Kotlin + Jetpack Compose（Material 3） |
| JNI | 自研薄层（`jni/`），调用 `libimgtool.so` |
| 核心 | C11 静态库 [`imgtool`](https://github.com/lgylxyyy/imgtool)，NDK 交叉编译 |
| 存储 | 通过 SAF / 直接路径访问用户目录 |

---

## 许可证

本项目为 **GPL-2.0-or-later**。

### 本项目

| 范围 | 许可证 | 全文 |
|---|---|---|
| 全部 Kotlin / JNI 代码 | GPL-2.0-or-later | [GPL-2.0.txt](LICENSES/GPL-2.0.txt) |

### 随 APK 分发的第三方组件

`libimgtool.so` 内静态链接以下组件：

| 组件 | 许可证 | 用途 | 全文 |
|---|---|---|---|
| [zlib](https://zlib.net) | Zlib | qcow2 压缩簇 | [Zlib.txt](LICENSES/Zlib.txt) |
| [libntfs-3g](https://github.com/tuxera/ntfs-3g) | LGPL-2.1-or-later | NTFS 读写 | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [ntfsprogs / mkntfs](https://github.com/tuxera/ntfs-3g/tree/master/ntfsprogs) | GPL-2.0-or-later | NTFS 格式化 | [GPL-2.0.txt](LICENSES/GPL-2.0.txt) |
| [wimlib](https://wimlib.net) | LGPL-2.1-or-later | WIM / ESD | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [libarchive](https://libarchive.org) | BSD-2-Clause / BSD-3-Clause | zip / 7z / tar | [BSD-3-Clause.txt](LICENSES/BSD-3-Clause.txt) |
| [hivex](https://github.com/libguestfs/hivex) | LGPL-2.1-or-later | 注册表 hive（BCD） | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [libxml2](https://gitlab.gnome.org/GNOME/libxml2) | MIT | hivex 依赖 | [MIT.txt](LICENSES/MIT.txt) |
| [libiconv](https://www.gnu.org/software/libiconv/) | LGPL-2.1-or-later | 字符集转换 | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [UnRAR](https://www.rarlab.com/rar_add.htm) | UnRAR 自由软件许可 | 只读解 RAR | [UnRAR.txt](LICENSES/UnRAR.txt) |
| [xz / liblzma](https://tukaani.org/xz/) | 0BSD（及个别 PD 文件） | libarchive 依赖 | [xz 上游声明](https://git.tukaani.org/?p=xz.git;a=blob_plain;f=COPYING;hb=HEAD) |
| AndroidX / Jetpack Compose | Apache-2.0 | UI 框架 | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| [Kotlin](https://kotlinlang.org) | Apache-2.0 | 语言与运行时 | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| Android NDK（sysroot） | Apache-2.0 + 各组件自带 | 交叉编译目标环境 | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |

### 合规说明

- **LGPL 义务**：`libntfs-3g`、`wimlib`、`hivex`、`libiconv` 静态编入 `libimgtool.so`。
  对应源码与构建脚本在 [imgtool](https://github.com/lgylxyyy/imgtool) 公开提供，
  可据此重新生成 `.so`。
- **GPL 义务**：本项目整体按 GPL-2.0-or-later 分发；
  如需非 GPL 版本，可用 `-DIMG_WITH_NTFS=OFF` 重新构建 `libimgtool.so`。
- **UnRAR 限制**：仅用于解压，不得用于开发 RAR 兼容压缩器或重建 RAR 压缩算法。
- 逐项版权与修改说明见 imgtool 仓库的 `THIRD_PARTY.md`。

---

## 隐私与安全

| 项 | 说明 |
|---|---|
| 网络权限 | 未申请 |
| 数据上传 | 无 |
| 遥测 / 统计 | 无 |
| 存储访问 | 仅访问用户显式选择或授权的路径 |
| 权限 | 「管理所有文件」（Android 11+ 读写任意路径的磁盘镜像所需） |

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 镜像必须关机 | 与虚拟机同时写入会损坏元数据 |
| VHD 建盘 | 暂不支持，需由 raw 镜像转换 |
| 无在线挂载 | 不提供 FUSE / 挂载点，全部离线读写 |
| 大镜像耗时 | 数十 GB 的镜像在移动设备上操作可能需数分钟 |
| 临时目录空间 | 从容器解压、WIM 捕获等操作需与目标体积相当的临时空间 |
