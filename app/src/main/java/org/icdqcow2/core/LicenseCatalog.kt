package org.icdqcow2.core

import android.content.Context

/**
 * 开源组件与许可证目录。
 *
 * 为什么要有这个：LGPL / GPL 要求**随分发提供许可证副本**。
 * 只列组件名不够，必须能看到全文；而且组件清单必须与实际链接进
 * `libimgtool.so` 的一致 —— 标错许可证是合规问题，不是文案问题。
 *
 * 清单来源：REQ-20261109-60 §1.5（工具端核实过链接情况）。
 * 全文来源：assets/licenses 目录下的 txt 文件，与仓库根 LICENSES/ 同源。
 */
object LicenseCatalog {

    /** 一个组件条目。 */
    data class Entry(
        /** 组件名（专有名词，不翻译）。 */
        val name: String,
        /** 许可证短名，如 `LGPL-2.1`、`Apache-2.0`。 */
        val license: String,
        /** 用途说明的 i18n key（见 Strings.kt 的 `licPurpose*`）。 */
        val purposeKey: String,
        /** assets 下对应的全文文件名。 */
        val assetFile: String,
        /** 是否随 APK 分发（静态链接进 .so）。false = App 框架层。 */
        val bundled: Boolean,
    )

    /**
     * 全部组件。
     *
     * **顺序 = 界面顺序**：先"随 APK 分发"的核心件，再框架层。
     * 新增/删除组件时只改这里，界面自动跟着变。
     */
    val entries: List<Entry> = listOf(
        // ── A. 静态链接进 libimgtool.so（随 APK 分发）──
        Entry("imgtool", "GPL-2.0-or-later", "licPurposeTool", "GPL-2.0.txt", true),
        Entry("zlib", "Zlib", "licPurposeZlib", "Zlib.txt", true),
        Entry("libntfs-3g", "LGPL-2.1-or-later", "licPurposeNtfs", "LGPL-2.1.txt", true),
        Entry("ntfsprogs / mkntfs", "GPL-2.0-or-later", "licPurposeMkntfs", "GPL-2.0.txt", true),
        Entry("wimlib", "LGPL-2.1-or-later", "licPurposeWimlib", "LGPL-2.1.txt", true),
        Entry("libarchive", "BSD-2-Clause / BSD-3-Clause", "licPurposeArchive", "BSD-3-Clause.txt", true),
        Entry("hivex", "LGPL-2.1-or-later", "licPurposeHivex", "LGPL-2.1.txt", true),
        Entry("libxml2", "MIT", "licPurposeXml2", "MIT.txt", true),
        Entry("libiconv", "LGPL-2.1-or-later", "licPurposeIconv", "LGPL-2.1.txt", true),
        Entry("UnRAR", "UnRAR License", "licPurposeUnrar", "UnRAR.txt", true),
        Entry("xz / liblzma", "0BSD", "licPurposeXz", "0BSD.txt", true),
        Entry("TianoCore edk2 (OVMF IA32)", "BSD-2-Clause-Patent", "licPurposeEdk2", "BSD-2-Clause-Patent.txt", true),

        // ── B. App 框架层 ──
        Entry("Miuix", "Apache-2.0", "licPurposeMiuix", "Apache-2.0.txt", false),
        Entry("sora-editor", "LGPL-2.1-or-later", "licPurposeSora", "LGPL-2.1.txt", false),
        Entry("Shizuku API", "Apache-2.0", "licPurposeShizuku", "Apache-2.0.txt", false),
        Entry("Jetpack Compose / AndroidX", "Apache-2.0", "licPurposeAndroidx", "Apache-2.0.txt", false),
        Entry("Kotlin / kotlinx", "Apache-2.0", "licPurposeKotlin", "Apache-2.0.txt", false),
    )

    /** 全文文件名去重后的清单（用于校验 assets 是否齐）。 */
    val textFiles: List<String>
        get() = entries.map { it.assetFile }.distinct().sorted()

    /** 读全文（IO 线程调用）。返回 null 表示读不到。 */
    fun readText(ctx: Context, file: String): String? = runCatching {
        ctx.assets.open("licenses/$file").bufferedReader().use { it.readText() }
    }.getOrNull()
}
