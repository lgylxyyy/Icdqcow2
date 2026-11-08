# 第三方组件与版权声明 / Third-Party Notices

本文件列出 Icdqcow2（Android App）直接依赖的开源项目、对应许可证，
以及随 App 分发的原生库（`libimgtool.so`）里包含的第三方代码。
许可证全文在 `LICENSES/`。

最后核对：2026-11-06（`app/build.gradle.kts` 的 dependencies 全量）

---

## 一、App 直接依赖（Kotlin / Java）

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | 基础扩展 |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | 单 Activity + Compose 入口 |
| AndroidX Lifecycle Runtime Compose | 2.8.6 | Apache-2.0 | 生命周期与状态收集 |
| Jetpack Compose（BOM 2025.09.00）<br>ui / ui-graphics / foundation / material3 / material-icons-extended | — | Apache-2.0 | 整个 UI |
| kotlinx-coroutines-android | 1.8.1 | Apache-2.0 | 协程、后台任务 |
| Miuix：`miuix-ui` / `miuix-icons` / `miuix-blur` / `miuix-preference` | 0.9.4 | Apache-2.0 | 小米 HyperOS 风格组件、图标、毛玻璃、偏好设置 |
| **sora-editor**（`io.github.Rosemoe.sora-editor:editor`） | 0.23.6 | **LGPL-2.1** | 代码编辑器内核（canvas 绘制、按行虚拟化） |
| AndroidLiquidGlass：`io.github.kyant0:backdrop` / `shapes` | 2.0.1 / 1.2.1 | Apache-2.0 | 底部导航栏的液态玻璃折射 |
| Shizuku `api` / `provider` | 13.1.5 | Apache-2.0 | 可选：借 shell 权限访问受限目录 |
| NavigationEvent Compose | 1.1.2 | Apache-2.0 | 返回事件分发（Miuix 的 `WindowDialog` 依赖它） |
| Kotlin 标准库 | 2.4.20 | Apache-2.0 | 语言运行时 |
| JSON（`org.json`，Android 平台内置） | — | Apache-2.0（Android） | 解析工具返回的 JSON |

> **sora-editor 是 LGPL-2.1**：本 App 以未修改的二进制依赖方式使用它
> （Maven Central 原版），未做任何 patch，可随时替换为你自己编译的版本。

## 二、随 App 分发的原生库 `libimgtool.so`

`libimgtool.so` 来自 [imgtool-android](https://github.com/lgylxyyy/imgtool-android)，
其中包含：

| 组件 | 许可证 | 用途 |
|---|---|---|
| [libntfs-3g](https://github.com/tuxera/ntfs-3g) | LGPL-2.1+ | NTFS 读写 |
| [wimlib](https://wimlib.net) | LGPL-2.1+ | WIM/ESD 解压 |
| [hivex](https://github.com/libguestfs/hivex) | LGPL-2.1+ | 注册表 hive（BCD） |
| [libarchive](https://libarchive.org) | BSD-2/3-Clause | zip / 7z / tar |
| [zlib](https://zlib.net) | Zlib | qcow2 压缩簇 |
| [UnRAR](https://www.rarlab.com/rar_add.htm) | UnRAR 自由软件许可 | 只读解 RAR |
| [mkntfs / ntfsprogs](https://github.com/tuxera/ntfs-3g/tree/master/ntfsprogs) | **GPL-2.0+** | NTFS 格式化（可选） |

**LGPL 合规说明**：上述 LGPL 组件是静态编进 `libimgtool.so` 的。
对应源码与完整构建脚本（`android-deps.sh` / `android-cross.sh`）全部公开，
任何人可用它们在本地重新生成同样功能的 `.so` 并替换到 APK 里 —— 满足 LGPL 的
"可重新链接"要求。本 App 没有修改这些库的源码。

## 三、素材

| 来源 | 许可证 | 用途 |
|---|---|---|
| [Microsoft Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons) | MIT | 文件类型图标 |
| Miuix 图标集（随 `miuix-icons`） | Apache-2.0 | 界面图标 |
| [TianoCore EDK II / OVMF](https://github.com/tianocore/edk2)（IA32 版） | BSD-2-Clause-Patent | `assets/firmware/` 里的 32 位 UEFI 固件 |
| [Vectras VM](https://github.com/vectras-vm/Vectras-VM-Android) 内置的 `edk2-i386-*.fd` | BSD-2-Clause-Patent | 固件二进制来源（与本项目所附一字不差） |

**关于固件**：`edk2-i386-code.fd` / `edk2-i386-vars.fd` 是 IA32 版 OVMF，
用于让 32 位 Windows 在 UEFI 下启动。TianoCore 上游已移除 `OvmfPkgIa32` 构建目标，
本项目所附二进制取自 Vectras VM 随包发布的一份，与 edk2 的 BSD-2-Clause-Patent
许可一致 —— 允许随二进制再分发，只需保留版权与免责声明（见
`LICENSES/BSD-2-Clause-Patent.txt`）。固件在首次启动时释放到
`Documents/Icdqcow2/Firmware/`，App 不修改系统中任何既有固件。


## 四、开发期工具（不进 APK）

| 组件 | 许可证 | 用途 |
|---|---|---|
| Android Gradle Plugin | Apache-2.0 | 构建 |
| Gradle | Apache-2.0 | 构建 |
| Android SDK / Build-Tools | Apache-2.0 等 | 编译与打包 |
| Python 3 | PSF-2.0 | `tools/check_strings.py` 文案校验 |

## 五、许可证汇总

| 许可证 | 涉及 | 义务 |
|---|---|---|
| **GPL-2.0+** | `libimgtool.so` 内的 mkntfs | 分发二进制须提供完整对应源码 |
| **LGPL-2.1+** | sora-editor、libntfs-3g、wimlib、hivex | 保留声明；允许替换/重新链接 |
| **Apache-2.0** | AndroidX、Compose、Miuix、Kotlin、Shizuku、backdrop | 保留 NOTICE 与许可声明 |
| **MIT** | Fluent 图标 | 保留版权声明 |
| **BSD-2/3** | libarchive | 保留版权声明 |
| **BSD-2-Clause-Patent** | edk2 / OVMF IA32 固件 | 保留版权与免责声明 |
| **Zlib** | zlib | 保留声明，不得谎称原创 |
| **UnRAR** | RAR 解码器 | 不得用于开发 RAR 压缩器；保留声明 |

许可证全文：`LICENSES/`（`GPL-2.0.txt` / `LGPL-2.1.txt` / `Apache-2.0.txt` /
`BSD-3-Clause.txt` / `BSD-2-Clause-Patent.txt` / `MIT.txt` / `Zlib.txt` / `UnRAR.txt`）。
