<div align="center">

# Icdqcow2

**Manage qcow2 VM disks on an Android device, offline: create, partition, install, edit, repair.**

[**English**](README.md) | [中文 🇨🇳](README_ZH.md)

[![License](https://img.shields.io/badge/license-GPL--2.0--or--later-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)](https://kotlinlang.org)

</div>

It does not boot a VM, requires no root, and never uses the network.
The produced images can be handed straight to VectrasVM / Limbo / QEMU.

> Core library: [imgtool](https://github.com/lgylxyyy/imgtool) (C11),
> cross-compiled by that repository's `./android-cross.sh` into `libimgtool.so`.

---

## Quick start

| Step | Action |
|---|---|
| 1 | Download the APK from [Releases](https://github.com/lgylxyyy/Icdqcow2/releases) |
| 2 | Install and grant **All files access** (required on Android 11+) |
| 3 | Home → **Full Setup** → follow the wizard: pick ISO, create disk, apply, self-check |

| Section | Contents |
|---|---|
| [Features](#features) | Disks, deployment, boot, files, self-check |
| [Interface](#interface) | Three tabs and gestures |
| [Building](#building) | Build the APK from source |
| [License](#license) | This project and all third-party components |

---

## Features

| Category | Capability |
|---|---|
| **Disks** | Create (MBR/BIOS and GPT/UEFI auto layout); partition list, create, delete, resize, attribute bits, whole-table export/import; compact free space |
| **Filesystems** | Format FAT32 / NTFS; browse and edit files inside images; copy, move, rename, delete (to recycle bin), pack to zip |
| **Deployment** | Apply `install.wim` / `.esd` / ISO directly to a partition; non-formatting install (old system moved to `Windows.old`) |
| **Boot** | One-click layout of `bootmgr` / `bootmgfw.efi` / `boot.sdi` / fonts and BCD generation; BIOS, UEFI 64, and UEFI 32 |
| **Boot self-check** | Per-boot-mode inspection of boot manager, ESP fallback, BCD, loader, partition table; failed items offer one-tap repair |
| **Recovery** | Install `winre.wim` into a recovery or system partition; write `ReAgent.xml` and recovery scripts |
| **Container browsing** | Browse ISO, WIM/ESD, and zip / 7z / tar / tgz / RAR archives directly; extract, pack, and copy across containers |
| **Full self-check** | Partition table, filesystem, and boot chain in one pass, with conclusions and fix suggestions |
| **Text editor** | Edit config files inside images with line numbers and find/replace |

**Format support**

| Format | Read | Write | Create |
|---|---|---|---|
| qcow2 | ✅ | ✅ | ✅ |
| raw (.img / .raw) | ✅ | ✅ | ✅ |
| VHD | ✅ | ✅ | ⚠️ convert from raw |
| VMDK | ✅ | ✅ | ✅ |

**Design boundaries**

- Does not run a VM; it prepares and edits images only;
- Images must be **powered off**: the app and a VM must never write the same image concurrently;
- Fully offline: no network requests and no telemetry.

---

## Interface

Three bottom tabs; every operation opens a secondary page (swipe right to go back).

| Tab | Contents |
|---|---|
| **Home** | A "Full Setup" wizard card (pick image → create disk → apply → self-check, progress is preserved); grouped entries for disks, deployment, boot, recovery, and self-check |
| **Files** | Two independent panes; pane A can browse NTFS in image 1 while pane B browses FAT32 in image 2 |
| **Settings** | Theme, language, visual effects, error-code handbook, run log, open-source licenses |

**Files-page gestures**

| Action | Gesture |
|---|---|
| Enter directory / open file | Tap |
| Copy / move / rename / delete / pack | Long-press menu (unavailable items are hidden, not greyed out) |
| Multi-select | Long-press → "Multi-select"; the selection sticks to the originating pane while the other keeps browsing |
| Copy to the other pane | Multi-select → "Copy"; the destination is the other pane's current directory |

Long tasks (copy, move, pack, delete, image apply) raise a blocking progress card:
progress bar, `done / total`, current file name — only "Cancel" is available.

---

## Building

### Requirements

- JDK 17, Android SDK (compileSdk 37, targetSdk 35, minSdk 33)
- Android NDK r28c (builds `libimgtool.so`)
- CMake ≥ 3.16, host clang

### Steps

```bash
# 1) Obtain the C library (this repo does not ship a prebuilt .so)
git clone https://github.com/lgylxyyy/imgtool
cd imgtool && ./android-deps.sh && ./android-cross.sh arm64-v8a

# 2) Place it into the app
cp build/android/arm64-v8a/libimgtool.so \
   ../Icdqcow2/app/src/main/jniLibs/arm64-v8a/

# 3) Build the APK
cd ../Icdqcow2 && ./gradlew :app:assembleDebug
# Artifact: app/build/outputs/apk/debug/app-debug.apk
```

The `.so` is a build product and is not tracked in git. Reinstall the APK after
replacing it. Verify the swap:

```bash
md5sum build/android/arm64-v8a/libimgtool.so
adb shell "md5sum /data/app/*/org.icdqcow2-*/lib/arm64/libimgtool.so"
```

---

## Tech stack

| Layer | Implementation |
|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) |
| JNI | Thin self-written layer (`jni/`) calling `libimgtool.so` |
| Core | C11 static library [imgtool](https://github.com/lgylxyyy/imgtool), built with the NDK |
| Storage | SAF / direct paths to user-selected locations |

---

## License

This project is **GPL-2.0-or-later**.

### This project

| Scope | License | Text |
|---|---|---|
| All Kotlin / JNI code | GPL-2.0-or-later | [GPL-2.0.txt](LICENSES/GPL-2.0.txt) |

### Third-party components shipped with the APK

`libimgtool.so` statically links the following:

| Component | License | Purpose | Text |
|---|---|---|---|
| [zlib](https://zlib.net) | Zlib | qcow2 compressed clusters | [Zlib.txt](LICENSES/Zlib.txt) |
| [libntfs-3g](https://github.com/tuxera/ntfs-3g) | LGPL-2.1-or-later | NTFS read/write | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [ntfsprogs / mkntfs](https://github.com/tuxera/ntfs-3g/tree/master/ntfsprogs) | GPL-2.0-or-later | NTFS formatting | [GPL-2.0.txt](LICENSES/GPL-2.0.txt) |
| [wimlib](https://wimlib.net) | LGPL-2.1-or-later | WIM / ESD | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [libarchive](https://libarchive.org) | BSD-2-Clause / BSD-3-Clause | zip / 7z / tar | [BSD-3-Clause.txt](LICENSES/BSD-3-Clause.txt) |
| [hivex](https://github.com/libguestfs/hivex) | LGPL-2.1-or-later | Registry hives (BCD) | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [libxml2](https://gitlab.gnome.org/GNOME/libxml2) | MIT | hivex dependency | [MIT.txt](LICENSES/MIT.txt) |
| [libiconv](https://www.gnu.org/software/libiconv/) | LGPL-2.1-or-later | Charset conversion | [LGPL-2.1.txt](LICENSES/LGPL-2.1.txt) |
| [UnRAR](https://www.rarlab.com/rar_add.htm) | UnRAR freeware license | RAR extraction only | [UnRAR.txt](LICENSES/UnRAR.txt) |
| [xz / liblzma](https://tukaani.org/xz/) | 0BSD (some files public domain) | libarchive dependency | [xz upstream](https://git.tukaani.org/?p=xz.git;a=blob_plain;f=COPYING;hb=HEAD) |
| AndroidX / Jetpack Compose | Apache-2.0 | UI framework | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| [Kotlin](https://kotlinlang.org) | Apache-2.0 | Language and runtime | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| Android NDK (sysroot) | Apache-2.0 + component licenses | Cross-compile target | [Apache-2.0.txt](LICENSES/Apache-2.0.txt) |

### Compliance notes

- **LGPL obligations**: `libntfs-3g`, `wimlib`, `hivex`, and `libiconv` are statically linked
  into `libimgtool.so`. The corresponding sources and build scripts are published in
  [imgtool](https://github.com/lgylxyyy/imgtool) so the `.so` can be regenerated.
- **GPL obligations**: this project is distributed as GPL-2.0-or-later;
  rebuild `libimgtool.so` with `-DIMG_WITH_NTFS=OFF` for a non-GPL variant.
- **UnRAR restriction**: used for decompression only; must not be used to develop a
  RAR-compatible compressor or to reverse-engineer the RAR compression algorithm.
- Per-component copyright and modification notes: `THIRD_PARTY.md` in the imgtool repository.

---

## Privacy and security

| Item | Statement |
|---|---|
| Network permission | Not requested |
| Data upload | None |
| Telemetry / analytics | None |
| Storage access | Only paths explicitly chosen or authorized by the user |
| Permissions | "All files access" (required on Android 11+ to read and write disk images anywhere) |

---

## Known limitations

| Limitation | Description |
|---|---|
| Images must be powered off | Concurrent writes with a VM corrupt metadata |
| VHD creation | Not supported; convert from a raw image |
| No online mounting | No FUSE / mount points; everything is offline read/write |
| Large images are slow | Multi-GB images can take minutes on a mobile device |
| Temporary space | Container extraction and WIM capture need temporary space comparable to the target size |
