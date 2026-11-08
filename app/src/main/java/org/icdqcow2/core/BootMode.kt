package org.icdqcow2.core

/**
 * 启动方式 —— **三态**，不是两态。
 *
 * 为什么单独拎出来：
 *   以前 App 用"分区表是不是 GPT"反推启动方式（`table == "GPT"` ⇒ UEFI），
 *   这在 32 位 UEFI 上直接错 —— GPT 盘可以是 UEFI 64，也可以是 UEFI 32，
 *   两者要的引导文件、BCD 位置、加载器全都不一样：
 *
 *     |            | BIOS            | UEFI 64                          | UEFI 32                          |
 *     |------------|-----------------|----------------------------------|----------------------------------|
 *     | 引导管理器 | \bootmgr        | \EFI\Microsoft\Boot\bootmgfw.efi | \EFI\Microsoft\Boot\bootia32.efi |
 *     | ESP 兜底   | —               | \EFI\BOOT\BOOTX64.EFI            | \EFI\BOOT\BOOTIA32.EFI           |
 *     | 加载器     | winload.exe     | winload.efi                      | winload.efi（32 位 PE）          |
 *
 * [wire] 是工具端 `boot.check --mode=` 与 `boot.populate` 分支用的原样字符串，
 * 不要自己改拼写。
 */
enum class BootMode(val wire: String) {
    BIOS("bios"),
    UEFI64("uefi"),
    UEFI32("uefi32"),
    ;

    val isUefi: Boolean get() = this != BIOS

    companion object {
        /** 工具端字符串 → 枚举；不认识的一律当 BIOS（保守：BIOS 路径不会动 ESP）。 */
        fun of(wire: String?): BootMode = when (wire?.lowercase()) {
            "uefi", "uefi64", "uefi-64" -> UEFI64
            "uefi32", "uefi-32" -> UEFI32
            else -> BIOS
        }
    }
}

/**
 * 判定启动方式时能拿到的**全部事实**。
 *
 * 这里不做任何猜测 —— 每一项都由工具端回答（`part.show` / `ntfs.exists` / `fat.exists`）。
 * 判不出来的项留 `false`，由 [BootModeDetect.detect] 按固定优先级兜底。
 */
data class BootProbe(
    /** 分区表："GPT" / "MBR" / ""（没有分区表）。 */
    val table: String = "",
    /** 有 ESP 分区（GPT 类型 GUID C12A7328 / MBR 0xEF）。 */
    val hasEsp: Boolean = false,
    /** ESP 里有 `\EFI\Microsoft\Boot\bootia32.efi`（32 位引导管理器）。 */
    val bootia32: Boolean = false,
    /** ESP 里有 `\EFI\Microsoft\Boot\bootmgfw.efi`。 */
    val bootmgfw: Boolean = false,
    /** ESP 里有 `\EFI\BOOT\BOOTX64.EFI`（默认/可移动路径）。 */
    val bootx64: Boolean = false,
    /** 系统分区有 `\Windows\System32\ntdll.dll`（确认这里面装的是 Windows）。 */
    val hasWindows: Boolean = false,
    /** 系统分区有 `\Windows\System32\winload.efi`。 */
    val winloadEfi: Boolean = false,
    /** 系统分区有 `\Windows\System32\winload.exe`。 */
    val winloadExe: Boolean = false,
    /**
     * 64 位系统（有 `\Windows\SysWOW64\ntdll.dll`）。
     *
     * **三态**：`null` = 判不出来（没有 Windows / 探测失败）。
     * `false` 只有在"确认装了 Windows 但没有 SysWOW64"时才成立 —— 这时它是**硬证据**：
     * 32 位 Windows 根本跑不了 64 位 UEFI，只能是 UEFI 32。
     */
    val is64BitOs: Boolean? = null,
) {
    val hasEfiLoader: Boolean get() = winloadEfi || winloadExe
}

object BootModeDetect {

    /**
     * 判定顺序：**系统位数 > 文件名**。
     *
     * 为什么不能只看文件名（2026-11-07 真机踩的坑）：
     *   一张 Win8 x86 的 UEFI32 盘，ESP 上是 `bootmgfw.efi` + `BOOTX64.EFI`，
     *   **根本没有 `bootia32.efi`** —— 因为 `boot.populate` 是从系统分区
     *   `\Windows\Boot\EFI\bootmgfw.efi` 原样拷过去的，32 位系统里那个文件本身
     *   就是 32 位 PE，只是名字还叫 bootmgfw / BOOTX64。**文件名在这件事上说谎。**
     *
     * 所以顺序是：
     * 1. 不是 GPT，或 GPT 但没有 ESP → BIOS
     * 2. **确认是 32 位 Windows**（有 Windows 但没有 SysWOW64）→ UEFI 32
     *    （32 位系统跑不了 64 位 UEFI，这是物理约束，不是偏好）
     * 3. 确认是 64 位 Windows → UEFI 64
     * 4. 位数判不出来时才看文件：有 `bootia32.efi` 且没有 `bootmgfw.efi` → UEFI 32
     * 5. 有 `bootmgfw.efi` / `BOOTX64.EFI` → UEFI 64
     * 6. 什么都没有（还没铺过引导）→ 默认 UEFI 64（绝大多数情况）
     */
    fun detect(p: BootProbe): BootMode {
        if (!p.table.equals("GPT", ignoreCase = true)) return BootMode.BIOS
        if (!p.hasEsp) return BootMode.BIOS
        when (p.is64BitOs) {
            false -> return BootMode.UEFI32
            true -> return BootMode.UEFI64
            null -> Unit
        }
        if (p.bootia32 && !p.bootmgfw) return BootMode.UEFI32
        if (p.bootmgfw || p.bootx64) return BootMode.UEFI64
        return BootMode.UEFI64
    }
}

/**
 * 现探一张盘：把 [BootProbe] 需要的事实问一遍工具，再交给 [BootModeDetect.detect]。
 *
 * 每个探测都是一次 JNI 调用（几十毫秒）。**任何一个探测失败都只是留 `false`**，
 * 不会抛 —— 分区损坏/文件系统不认识时，页面照样能开。
 *
 * @param sysLba 系统分区（`null` = 还没定位到，相关项一律留 `false`）
 * @param espLba ESP 分区（`null` = 没有 ESP）
 */
suspend fun probeBootMode(
    table: String,
    espLba: Long?,
    sysLba: Long?,
    img: String? = null,
): BootMode {
    var bootia32 = false
    var bootmgfw = false
    var bootx64 = false
    if (espLba != null) {
        bootia32 = ImageRepo.fatExists(espLba, "/EFI/Microsoft/Boot/bootia32.efi", img) ?: false
        bootmgfw = ImageRepo.fatExists(espLba, "/EFI/Microsoft/Boot/bootmgfw.efi", img) ?: false
        bootx64 = ImageRepo.fatExists(espLba, "/EFI/BOOT/BOOTX64.EFI", img) ?: false
    }
    var winloadEfi = false
    var winloadExe = false
    var hasWindows = false
    /** `null` = 判不出来；`false` = 确认 32 位（有 Windows 但没有 SysWOW64）。 */
    var is64: Boolean? = null
    if (sysLba != null) {
        winloadEfi = ImageRepo.ntfsExists(sysLba, "/Windows/System32/winload.efi", img) ?: false
        winloadExe = ImageRepo.ntfsExists(sysLba, "/Windows/System32/winload.exe", img) ?: false
        hasWindows = ImageRepo.ntfsExists(sysLba, "/Windows/System32/ntdll.dll", img) ?: false
        val wow64 = ImageRepo.ntfsExists(sysLba, "/Windows/SysWOW64/ntdll.dll", img)
        // 只有"确认是 Windows"且"探测本身有结果"时才敢下结论，否则留 null 交给文件兜底
        is64 = if (hasWindows && wow64 != null) wow64 else null
    }
    return BootModeDetect.detect(
        BootProbe(
            table = table,
            hasEsp = espLba != null,
            bootia32 = bootia32,
            bootmgfw = bootmgfw,
            bootx64 = bootx64,
            hasWindows = hasWindows,
            winloadEfi = winloadEfi,
            winloadExe = winloadExe,
            is64BitOs = is64,
        ),
    )
}