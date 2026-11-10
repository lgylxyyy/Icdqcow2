package org.icdqcow2.ui.pages

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.TempDirs
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import org.icdqcow2.ui.Strings
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/* ───────────────────────── 数据口径 ───────────────────────── */

internal enum class FcLevel { Info, Ok, Warn, Bad }

/** 自检里的一行。`jump` 非空 = 这一行能点，跳到对应的修复页（"<" 卡片）。 */
internal data class FcItem(
    val title: String,
    val summary: String? = null,
    val level: FcLevel = FcLevel.Info,
    val jump: String? = null,
)

internal data class FcSection(val title: String, val items: List<FcItem>)

/** 一条可自动修复的操作。`risky` 的默认不勾选（会重写分区表那种）。 */
internal data class FcFix(
    val title: String,
    val detail: String,
    val risky: Boolean = false,
    val run: suspend () -> ToolRunner.Result,
)

internal data class FcReport(
    val sections: List<FcSection> = emptyList(),
    val fixes: List<FcFix> = emptyList(),
    val bad: Int = 0,
    val warn: Int = 0,
    /** 镜像都打不开：只显示这句话，不给修复。 */
    val fatal: String? = null,
    /** 磁盘布局长条用：分区表 + 每块分区的扫描结果。 */
    val pt: ImageRepo.PtInfo? = null,
    val scans: Map<Long, ImageRepo.Scan> = emptyMap(),
)

/* ───────────────────────── 角色判定 ───────────────────────── */

private fun fcIsEsp(p: ImageRepo.Part) =
    p.typeGuid.startsWith("C12A7328", true) || p.mbri == 0xEF

private fun fcIsMsr(p: ImageRepo.Part) = p.typeGuid.startsWith("E3C9E316", true)

/** MBR 里 0x27 = Windows RE；0x12 = OEM 恢复。 */
private fun fcIsRec(p: ImageRepo.Part) =
    p.typeGuid.startsWith("DE94BBA4", true) || p.mbri == 0x27 || p.mbri == 0x12

private fun roleOf(p: ImageRepo.Part, winLba: Long, s: Strings): String = when {
    p.isContainer() -> s.fcRoleExt
    p.logical -> s.fcRoleLogical
    fcIsEsp(p) -> s.fcRoleEsp
    fcIsMsr(p) -> s.fcRoleMsr
    fcIsRec(p) -> s.fcRoleRec
    p.lba == winLba -> s.fcRoleWin
    else -> s.fcRoleData
}

/* ───────────────────────── 自检本体 ───────────────────────── */

/**
 * 把一张盘"现在长什么样"一次查完：镜像 → 分区表 → 每块分区 → Windows → 恢复环境 → 引导。
 *
 * 全部是只读命令（`img.info` / `part.show` / `part.scan` / `ntfs.exists` / `boot.check`），
 * 一条都不写盘；同时把"能自动修的"整理成 [FcFix]（写操作只在用户点「一键修复」后发生）。
 */
internal suspend fun buildFcReport(
    img: String,
    s: Strings,
    ctx: Context,
    onStage: (String) -> Unit,
): FcReport = withContext(Dispatchers.IO) {
    onStage(s.fcStageInfo)
    val info = ImageRepo.info(img)
    if (!info.ok) return@withContext FcReport(fatal = toolErrorText(s, info))
    val virtualBytes = info.data?.optLong("size_bytes", 0L) ?: 0L
    val fileBytes = runCatching { File(img).length() }.getOrDefault(0L)

    onStage(s.fcStageParts)
    val pt = ImageRepo.parsePt(ImageRepo.parts(img))
    val table = pt?.table?.takeIf { it.isNotBlank() && it != "?" } ?: "—"
    val diskSectors = pt?.diskSectors ?: 0L
    val parts = pt?.parts?.sortedBy { it.lba } ?: emptyList()

    // 每块分区扫一遍（MSR / 扩展容器没有文件系统，跳过省时间）
    val scans = HashMap<Long, ImageRepo.Scan>()
    val targets = parts.filter { !it.isContainer() && !fcIsMsr(it) }
    targets.forEachIndexed { i, p ->
        onStage(s.fcStageScan(i + 1, targets.size))
        ImageRepo.parseScan(ImageRepo.scan(p.lba, img))?.let { scans[p.lba] = it }
    }

    val esp = parts.firstOrNull { fcIsEsp(it) }
    val rec = parts.firstOrNull { fcIsRec(it) }
    val sr = parts.firstOrNull { it.active && it.mbri == 0x07 && !fcIsEsp(it) && !fcIsRec(it) }
    val dataParts = parts.filter {
        !it.isContainer() && !it.logical && !fcIsEsp(it) && !fcIsMsr(it) && !fcIsRec(it)
    }
    // 系统分区：先看「根目录有 Windows」，再退到"最大的 NTFS"
    var win = dataParts.firstOrNull { scans[it.lba]?.looksLikeWindows == true }
    if (win == null) {
        win = dataParts
            .filter { scans[it.lba]?.fs?.equals("NTFS", true) == true }
            .maxByOrNull { it.bytes }
    }

    // ── 结构：重叠 / 越界 ──
    var overlap = false
    var oob = false
    var cursor = 0L
    for (p in parts) {
        if (p.lba < cursor) overlap = true
        if (diskSectors > 0L && p.lba + p.sectors > diskSectors) oob = true
        if (p.lba + p.sectors > cursor) cursor = p.lba + p.sectors
    }
    val structure = when {
        overlap && oob -> s.fcStructBoth
        overlap -> s.fcStructOverlap
        oob -> s.fcStructOob
        else -> s.fcStructOk
    }
    val structLevel = if (overlap || oob) FcLevel.Bad else FcLevel.Ok

    // ── Windows 关键文件 ──
    var loader = false
    var kernel = false
    var bootmgr = false
    if (win != null) {
        onStage(s.fcStageWin)
        val lba = win.lba
        val uefi = table.equals("GPT", true)
        loader = ImageRepo.ntfsExists(
            lba,
            if (uefi) "/Windows/System32/winload.efi" else "/Windows/System32/winload.exe",
            img,
        ) == true || ImageRepo.ntfsExists(lba, "/Windows/System32/winload.exe", img) == true
        kernel = ImageRepo.ntfsExists(lba, "/Windows/System32/ntoskrnl.exe", img) == true
        bootmgr = ImageRepo.ntfsExists(lba, "/bootmgr", img) == true
    }

    // ── 恢复环境：四种口径（重点：有分区但没 wim 才算异常）──
    val recWim = rec != null &&
        ImageRepo.ntfsExists(rec.lba, "/Recovery/WindowsRE/winre.wim", img) == true
    val sysRecWim = win != null &&
        ImageRepo.ntfsExists(win.lba, "/Recovery/WindowsRE/winre.wim", img) == true
    val sysReWim2 = win != null &&
        ImageRepo.ntfsExists(win.lba, "/Windows/System32/Recovery/WinRE/WinRE.wim", img) == true
    val sdiOk = rec != null &&
        ImageRepo.ntfsExists(rec.lba, "/Recovery/WindowsRE/boot.sdi", img) == true
    val agentOk = rec != null &&
        ImageRepo.ntfsExists(rec.lba, "/Recovery/WindowsRE/ReAgent.xml", img) == true

    // ── 引导链 ──
    onStage(s.fcStageBoot)
    val boot = ImageRepo.bootCheck(expectRecovery = recWim, path = img)
    val bootRows = ArrayList<FcItem>()
    var bootBad = 0
    if (!boot.ok) {
        bootRows += FcItem(s.fcBoot, toolErrorText(s, boot), FcLevel.Bad, jump = "bootrepair")
        bootBad = 1
    } else {
        val rows = bootCheckRows(s, boot.data, table.equals("GPT", true))
        if (rows.isEmpty()) {
            bootRows += FcItem(s.fcBoot, s.fcBootNone, FcLevel.Info)
        } else {
            rows.forEach { (label, detail, pass) ->
                if (!pass) bootBad += 1
                bootRows += FcItem(label, detail, if (pass) FcLevel.Ok else FcLevel.Bad)
            }
        }
    }

    // ── 组装 ──
    val sections = ArrayList<FcSection>()
    var bad = 0
    var warn = 0
    fun tally(vararg levels: FcLevel) {
        levels.forEach { if (it == FcLevel.Bad) bad++ else if (it == FcLevel.Warn) warn++ }
    }

    sections += FcSection(
        s.fcSecDisk,
        listOf(
            FcItem(s.fcItemFile, File(img).name),
            FcItem(s.fcItemSize, fmtBytes(fileBytes)),
            FcItem(s.fcItemVirtual, fmtBytes(virtualBytes)),
            FcItem(
                s.fcItemTable,
                if (parts.isEmpty()) s.fcNoTable else table,
                if (parts.isEmpty()) FcLevel.Warn else FcLevel.Ok,
                jump = if (parts.isEmpty()) "parts" else null,
            ),
        ),
    )
    if (parts.isEmpty()) warn++

    val lastEnd = parts.maxOfOrNull { it.lba + it.sectors } ?: 0L
    val tail = (diskSectors - lastEnd).coerceAtLeast(0L)
    sections += FcSection(
        s.fcSecTable,
        listOf(
            FcItem(s.fcItemTableType, table, FcLevel.Info),
            FcItem(s.fcItemPartCount, parts.count { !it.isContainer() }.toString(), FcLevel.Info),
            FcItem(s.fcItemTail, fmtBytes(tail * 512), FcLevel.Info),
            FcItem(s.fcItemStruct, structure, structLevel, jump = if (overlap || oob) "parts" else null),
        ),
    )
    tally(structLevel)

    if (parts.isNotEmpty()) {
        val rows = ArrayList<FcItem>()
        parts.forEachIndexed { i, p ->
            val scan = scans[p.lba]
            val fs = scan?.fs?.takeIf { it.isNotBlank() && it != "none" } ?: s.fcNoFs
            val label = scan?.label?.takeIf { it.isNotBlank() }
            val used = scan?.let {
                s.fcPartSub(fs, label ?: "—", fmtBytes(it.usedBytes), fmtBytes(it.usedBytes + it.freeBytes))
            } ?: fmtBytes(p.bytes)
            rows += FcItem(
                s.fcPartTitle(i + 1, roleOf(p, win?.lba ?: -1L, s)),
                used,
                if (scan?.hasFs == true) FcLevel.Ok else FcLevel.Info,
            )
        }
        sections += FcSection(s.fcSecParts, rows)
    }

    val winRows = ArrayList<FcItem>()
    if (win == null) {
        winRows += FcItem(s.fcWinNone, null, FcLevel.Bad, jump = "deploy")
        bad++
    } else {
        winRows += FcItem(s.fcWinSys(parts.indexOfFirst { it.lba == win.lba } + 1), fmtBytes(win.bytes), FcLevel.Ok)
        winRows += FcItem(s.fcWinLoader, null, if (loader) FcLevel.Ok else FcLevel.Bad, jump = if (loader) null else "deploy")
        winRows += FcItem(s.fcWinKernel, null, if (kernel) FcLevel.Ok else FcLevel.Bad, jump = if (kernel) null else "deploy")
        if (!table.equals("GPT", true)) {
            winRows += FcItem(s.fcWinBootmgr, null, if (bootmgr) FcLevel.Ok else FcLevel.Bad, jump = if (bootmgr) null else "bootrepair")
        }
        if (!loader) bad++
        if (!kernel) bad++
        if (!table.equals("GPT", true) && !bootmgr) bad++
    }
    sections += FcSection(s.fcSecWin, winRows)

    val reRows = ArrayList<FcItem>()
    if (rec == null) {
        // 没有恢复分区：**不算错误**，灰字说明而已
        reRows += FcItem(s.fcReNone, s.fcReNoneSummary, FcLevel.Info)
        if (sysRecWim || sysReWim2) {
            reRows += FcItem(s.fcReWim, s.fcReInSys, FcLevel.Warn)
            warn++
        }
    } else {
        reRows += FcItem(s.fcRePart, s.fcRePartValue(parts.indexOfFirst { it.lba == rec.lba } + 1, fmtBytes(rec.bytes)), FcLevel.Ok)
        when {
            recWim -> reRows += FcItem(s.fcReWim, s.fcReWimOk, FcLevel.Ok)
            sysRecWim || sysReWim2 -> {
                reRows += FcItem(s.fcReWim, s.fcReWimInSys, FcLevel.Bad, jump = "winre")
                bad++
            }
            else -> {
                reRows += FcItem(s.fcReWim, s.fcReWimNoBoth, FcLevel.Bad, jump = "winre")
                bad++
            }
        }
        reRows += FcItem(s.fcReSdi, if (sdiOk) s.fcOk else s.fcMissing, if (sdiOk) FcLevel.Ok else FcLevel.Warn, jump = if (sdiOk) null else "winre")
        reRows += FcItem(s.fcReAgent, if (agentOk) s.fcOk else s.fcMissing, if (agentOk) FcLevel.Ok else FcLevel.Warn, jump = if (agentOk) null else "winre")
        if (!sdiOk) warn++
        if (!agentOk) warn++
    }
    sections += FcSection(s.fcSecRe, reRows)

    if (bootBad > 0) {
        bootRows += FcItem(s.fcJumpBoot, null, FcLevel.Bad, jump = "bootrepair")
        bad += bootBad
    }
    sections += FcSection(s.fcSecBoot, bootRows)

    // ── 可自动修复项（"该跳转的"不在这里）──
    val fixes = ArrayList<FcFix>()
    if (rec != null && !recWim && (sysRecWim || sysReWim2) && win != null) {
        val sysWimPath = if (sysRecWim) {
            "/Recovery/WindowsRE/winre.wim"
        } else {
            "/Windows/System32/Recovery/WinRE/WinRE.wim"
        }
        val recLba = rec.lba
        val winLba = win.lba
        fixes += FcFix(
            title = s.fxReFill,
            detail = s.fxReFillDetail,
            run = {
                val dir = File(TempDirs.stagingDir(ctx), "fcwinre").apply { mkdirs() }
                val local = File(dir, "winre.wim")
                val ex = ImageRepo.ntfsExport(winLba, sysWimPath, local.absolutePath, img)
                if (!ex.ok) {
                    ex
                } else {
                    // boot.sdi 也从系统盘抽一份（工具端只吃本地文件）
                    // boot.sdi 也从系统盘抽一份（工具端只吃本地文件）。
                    // ★ 以前写死 `/Windows/Boot/Disk/boot.sdi` —— Windows 里没有这个目录，
                    //   所以永远取不到，`recovery.populate` 之后恢复分区就少了 boot.sdi。
                    var sdiLocal: File? = null
                    if (!sdiOk) {
                        val sysSdi = listOf(
                            "/Windows/Boot/DVD/EFI/boot.sdi",
                            "/Windows/Boot/DVD/PCAT/boot.sdi",
                            "/Windows/Boot/Disk/boot.sdi",
                        ).firstOrNull { ImageRepo.ntfsExists(winLba, it, img) == true }
                        if (sysSdi != null) {
                            val f = File(dir, "boot.sdi")
                            if (ImageRepo.ntfsExport(winLba, sysSdi, f.absolutePath, img).ok) sdiLocal = f
                        }
                    }
                    ImageRepo.recoveryPopulate(
                        lba = recLba,
                        wim = local.absolutePath,
                        bootsdi = sdiLocal?.absolutePath,
                        // 独立恢复分区：先格式化再填（工具端 format 默认 false）
                        format = true,
                        path = img,
                    )
                }
            },
        )
    }
    if (win != null && (bootBad > 0 || !loader || !kernel || (!table.equals("GPT", true) && !bootmgr))) {
        val winLba = win.lba
        val espLba = esp?.lba ?: 0L
        val srLba = sr?.lba ?: 0L
        val uefi = table.equals("GPT", true)
        fixes += FcFix(
            title = if (uefi) s.fxBootUefi else s.fxBootBios,
            detail = if (uefi) s.fxBootUefiDetail else s.fxBootBiosDetail,
            run = { runBootFix(img, winLba, espLba, srLba, uefi) },
        )
    }
    // GPT 恢复分区属性：老盘建的时候只置了 bit63，缺 bit0 必需位 → 一条幂等修复
    if (rec != null && table.equals("GPT", true)) {
        val attrNow = rec.attributes.orEmpty()
        if (attrNow.isNotEmpty() && !attrNow.equals("0x8000000000000001", true)) {
            val recLba = rec.lba
            fixes += FcFix(
                title = s.fxRecAttr,
                detail = s.fxRecAttrDetail,
                run = { ImageRepo.setPartAttr(recLba, "0x8000000000000001", img) },
            )
        }
    }
    if (overlap || oob) {
        val diskEnd = diskSectors
        fixes += FcFix(
            title = s.fxTable,
            detail = s.fxTableDetail,
            risky = true,
            run = { rebuildTable(img, ctx, diskEnd) },
        )
    }

    FcReport(
        sections = sections,
        fixes = fixes,
        bad = bad,
        warn = warn,
        pt = pt,
        scans = scans,
    )
}

/**
 * 一键修复引导：UEFI = 重建 BCD + 重铺 ESP 文件；BIOS = 整铺（MBR/VBR + bootmgr + BCD）。
 * 失败时再退一步用 `boot.repair` 补 VBR。
 */
internal suspend fun runBootFix(
    img: String,
    winLba: Long,
    espLba: Long,
    srLba: Long,
    uefi: Boolean,
): ToolRunner.Result = withContext(Dispatchers.IO) {
    if (uefi) {
        val bcd = ImageRepo.bcdCreate(winLba, espLba = espLba, keepRecovery = true, path = img)
        if (!bcd.ok) return@withContext bcd
        ImageRepo.bootPopulate(winLba, espLba = espLba, fonts = true, force = true, path = img)
    } else {
        val pop = ImageRepo.bootPopulate(winLba, srLba = srLba, fonts = true, force = true, path = img)
        if (pop.ok) pop else ImageRepo.bootRepair(winLba, srLba = srLba, path = img)
    }
}

/**
 * 重排分区表：`part.export` 导一份 → 本地按 LBA 排序、丢重叠、裁越界 → `part.apply --force`。
 *
 * ★ 只动表、不动数据；被裁掉/丢掉的那块分区里的文件会变得不可见。
 */
internal suspend fun rebuildTable(
    img: String,
    ctx: Context,
    diskSectors: Long,
): ToolRunner.Result = withContext(Dispatchers.IO) {
    val dir = File(TempDirs.stagingDir(ctx), "fcpt").apply { mkdirs() }
    val tmp = File(dir, "table.json")
    val ex = ImageRepo.exportTable(out = tmp.absolutePath, path = img)
    if (!ex.ok) return@withContext ex
    val obj = runCatching { JSONObject(tmp.readText()) }.getOrNull()
        ?: return@withContext ToolRunner.Result(cmd = "part.export", ok = false, code = "PT-FILE-BAD")
    val arr = obj.optJSONArray("parts") ?: JSONArray()
    val list = ArrayList<JSONObject>()
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list += it }
    list.sortBy { it.optLong("first", it.optLong("start", 0L)) }
    val out = JSONArray()
    var cursor = 0L
    for (o in list) {
        val first = o.optLong("first", o.optLong("start", 0L))
        val sec = o.optLong("sectors", 0L)
        if (first <= 0L || sec <= 0L) continue
        if (first < cursor) continue
        val end = if (diskSectors > 0L) minOf(first + sec, diskSectors) else first + sec
        if (end <= first) continue
        o.put("first", first)
        o.put("sectors", end - first)
        out.put(o)
        cursor = end
    }
    obj.put("parts", out)
    tmp.writeText(obj.toString())
    ImageRepo.applyTable(tmp.absolutePath, path = img, force = true)
}


/* ───────────────────────── 引导链检查项 ───────────────────────── */

/**
 * `boot.check` 的一条检查项 → 界面文案。
 *
 * 只保留本磁盘类型用得上的项（UEFI 不看 MBR/VBR，BIOS 不看 ESP），
 * 描述一律用我们自己的短句——工具端返回的 `detail` 是给开发者看的，界面上是废话。
 */
private fun bootCheckLabel(s: Strings, id: String): String? = when (id) {
    "mode" -> null // 页面上已经有「引导方式」一行
    "gpt.read" -> s.bkGptRead
    "gpt.present" -> s.bkGptPresent
    "gpt.esp" -> s.bkEsp
    "gpt.esp_type" -> s.bkEspType
    "esp.fat32" -> s.bkEspFat
    "esp.bootmgfw" -> s.bkBootmgfw
    "esp.fallback" -> s.bkFallback
    "esp.bcd" -> s.bkBcd
    "mbr.present" -> s.bkMbrPresent
    "mbr.active" -> s.bkActive
    "mbr.ntfs" -> s.bkNtfsPart
    "mbr.chs" -> s.bkChs
    "bios.bootmgr" -> s.bkBootmgr
    "bios.bootmgr_bcd" -> s.bkBootBcd
    "ntfs.boot" -> s.bkNtfsBoot
    "ntfs.code" -> s.bkCode
    "ntfs.winload" -> s.bkWinload
    "ntfs.winload_efi" -> s.bkWinloadEfi
    else -> id
}

/** 按磁盘格式挑出该显示的检查项：`(文案, null, 是否通过)`。 */
internal fun bootCheckRows(
    s: Strings,
    data: JSONObject?,
    uefi: Boolean,
    /** 引导修复页要连恢复分区那几项一起看（那页会顺手补 RE）；自检页不混。 */
    withRecovery: Boolean = false,
): List<Triple<String, String?, Boolean>> {
    val arr = data?.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<Triple<String, String?, Boolean>>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id")
        // 恢复分区那几项（winre.wim / boot.sdi / 属性…）只在引导修复页显示，
        // 自检页有独立的「恢复环境」卡片，不重复
        if (!withRecovery && id.startsWith("rec.")) continue
        if (uefi) {
            // MBR / BIOS 专属 + UEFI 下只是"参考"的 $Boot 代码
            if (id.startsWith("mbr.") || id.startsWith("bios.") || id == "ntfs.code") continue
        } else {
            // GPT / ESP 专属 + GPT 才有的恢复分区类型/属性 + UEFI 才需要的 winload.efi
            if (id.startsWith("gpt.") || id.startsWith("esp.")) continue
            if (id == "ntfs.winload_efi" || id == "rec.type" || id == "rec.attr") continue
        }
        val label = bootCheckLabel(s, id) ?: continue
        // REPLY-54 §1：UEFI 检查项现在带只读的 PE 位数（`arch`），
        // 32 位盘的 bootmgfw / winload.efi 会报 "x86" —— 这是判 UEFI 32 的唯一硬证据。
        // 键不存在 = 文件不在或读不出，**不显示位数结论**（不猜）。
        val arch = o.optString("arch").takeIf { it.isNotBlank() }
        val detail = when (arch?.lowercase()) {
            "x86" -> s.fcArch32
            "x64" -> s.fcArch64
            "arm64", "arm32", "arm" -> s.fcArchArm
            else -> null
        }
        out += Triple(label, detail, o.optBoolean("pass", true))
    }
    return out
}

/* ───────────────────────── 页面 ───────────────────────── */

/**
 * 全量自检（原「系统自检」）。
 *
 * 结构：选镜像 → 磁盘布局长条 → 分类卡片（`SmallTitle` 分隔）→ 底部三颗按钮。
 * 异常行自带 `>` 跳到对应的修复页（分区管理 / 释放镜像 / 恢复环境 / 引导修复）；
 * 从修复页返回时**自动重跑一遍**，状态立刻更新。
 */
@Composable
fun FullCheckPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    subtitle: String? = null,
    initialImage: String? = null,
    /** 全流程装机的第 3 步：底部主按钮换成「完成」。 */
    onDone: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ★ 不默认吃全局「当前映像」：和分区管理一样，进来先手动选（只有带参跳转才预置）
    var image by remember { mutableStateOf(initialImage) }
    var report by remember { mutableStateOf<FcReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf("main") }
    var askFix by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }
    var fixStage by remember { mutableStateOf("") }

    LaunchedEffect(image, tick) {
        val img = image ?: run {
            report = null
            return@LaunchedEffect
        }
        running = true
        report = null
        report = buildFcReport(img, s, ctx) { stageText = it }
        running = false
    }

    // 从修复页回来：回自检 + 立即重跑
    val backFromSub: () -> Unit = {
        sub = "main"
        tick += 1
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = sub,
            transitionSpec = {
                // 进场：新页从右边推进来（二级页面）
                // 退场：旧页往**左**滑走。以前两个方向的位移都是 `it`（往右），
                // 看起来就像"先关掉自检页再打开新页"，而不是压栈（用户 2026-11-11）。
                (
                    slideInHorizontally(tween(230, easing = LinearOutSlowInEasing)) { it } +
                        fadeIn(tween(120))
                    ).togetherWith(
                    slideOutHorizontally(tween(230, easing = LinearOutSlowInEasing)) { -it / 3 } +
                        fadeOut(tween(160)),
                )
            },
            label = "fcSub",
        ) { target ->
            when (target) {
                "deploy" -> DeployPage(onBack = backFromSub, onToast = onToast, initialImage = image)
                "parts" -> PartsPage(onBack = backFromSub, onToast = onToast, initialImage = image)
                "winre" -> WinRePage(
                    onBack = backFromSub,
                    onToast = onToast,
                    initialImage = image,
                    // 「去分区管理」在这一层换成兄弟页，不用退回主页
                    onOpenParts = { sub = "parts" },
                    // 写完回首页（和单独打开这一页时一致）
                    onFinish = onBack,
                )
                "bootrepair" -> BootRepairPage(onBack = backFromSub, onToast = onToast, initialImage = image)
                else -> SmallTitlePage(title = s.fcTitle, onBack = onBack, subtitle = subtitle) {
                    val img = image
                    if (img == null) {
                        BlockEnter(0) { Note(s.fcNeedImage) }
                        BlockEnter(1) {
                            PickImagePage(onPicked = { path, uri ->
                                image = path ?: uri
                                tick += 1
                            })
                        }
                    } else {
                        BlockEnter(0) {
                            CurrentFileCard(name = pickName(img), path = img, onToast = onToast)
                        }
                        val r = report
                        when {
                            r == null -> BlockEnter(1) {
                                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                                    Text(
                                        stageText.ifBlank { s.fcRunning },
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }

                            r.fatal != null -> BlockEnter(1) { Note(r.fatal, warn = true) }

                            else -> {
                                BlockEnter(1) { FcSummary(r) }
                                val layoutPt = r.pt
                                if (layoutPt != null && layoutPt.parts.isNotEmpty() && layoutPt.diskSectors > 0L) {
                                    BlockEnter(2) {
                                        GroupTitle(s.partsLayoutTitle)
                                        val segs = buildSegments(layoutPt, s, r.scans)
                                        DiskBar(segs, layoutPt.diskSectors) { seg ->
                                            if (seg.part != null) sub = "parts"
                                        }
                                        Note(s.ptTableValue(layoutPt.table, fmtBytes(layoutPt.diskBytes)))
                                        Note(s.partsBarHint)
                                    }
                                }
                                r.sections.forEachIndexed { i, sec ->
                                    val idx = (2 + i).coerceAtMost(6)
                                    BlockEnter(idx) {
                                        GroupTitle(sec.title)
                                        GroupCard {
                                            sec.items.forEach { item ->
                                                FcRow(item) { route -> sub = route }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 底部三颗：一键修复 / 重新检查 / 返回（异常不拦截，随时能走）
                    if (image != null) {
                        val fixes = report?.fixes.orEmpty()
                        BlockEnter(7) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { askFix = true },
                                    enabled = fixes.isNotEmpty() && !fixing,
                                    modifier = Modifier.weight(1f),
                                    colors = if (fixes.isNotEmpty()) {
                                        ButtonDefaults.buttonColorsPrimary()
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                ) {
                                    Text(s.fcFix, style = MiuixTheme.textStyles.button)
                                }
                                Button(
                                    onClick = { tick += 1 },
                                    enabled = !fixing,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(s.fcReload, style = MiuixTheme.textStyles.button)
                                }
                            }
                            if (fixing) {
                                Spacer(Modifier.height(10.dp))
                                Note(fixStage.ifBlank { s.fcFixing })
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { (onDone ?: onBack)() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                // 主色（蓝）：这颗是「离开这一页」的唯一出口，和上面两颗灰按钮区分开
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(
                                    if (onDone != null) s.fcDone else s.fcBack,
                                    style = MiuixTheme.textStyles.button,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (askFix) {
        val fixes = report?.fixes.orEmpty()
        FixDialog(
            s = s,
            fixes = fixes,
            onDismiss = { askFix = false },
            onRun = { picked ->
                askFix = false
                if (picked.isEmpty()) return@FixDialog
                scope.launch {
                    fixing = true
                    var okCount = 0
                    var failMsg: String? = null
                    picked.forEachIndexed { i, fx ->
                        fixStage = s.fcFixingItem(i + 1, picked.size, fx.title)
                        // ★ 一律挪到 IO：这些命令是同步 JNI，留在主线程动画会一帧不动
                        val r = runCatching { withContext(Dispatchers.IO) { fx.run() } }.getOrNull()
                        if (r != null && r.ok) okCount++ else if (failMsg == null) {
                            failMsg = if (r == null) s.fcFixFail else toolErrorText(s, r)
                        }
                    }
                    fixing = false
                    fixStage = ""
                    onToast(
                        if (failMsg == null) s.fcFixDone(okCount)
                        else s.fcFixPartial(okCount, failMsg!!),
                    )
                    tick += 1
                }
            },
        )
    }
}

@Composable
private fun FcSummary(r: FcReport) {
    val colors = MiuixTheme.colorScheme
    val text = when {
        r.bad > 0 -> if (r.warn > 0) LocalStrings.current.fcSumBadWarn(r.bad, r.warn) else LocalStrings.current.fcSumBad(r.bad)
        r.warn > 0 -> LocalStrings.current.fcSumWarn(r.warn)
        else -> LocalStrings.current.fcSumOk
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Text(
            text,
            style = MiuixTheme.textStyles.main,
            color = when {
                r.bad > 0 -> colors.error
                r.warn > 0 -> colors.primary
                else -> colors.primary
            },
        )
    }
}

/** 一行：有 `jump` 就是带右箭头的可点卡片，否则是普通行 + 右侧状态字。 */
@Composable
private fun FcRow(item: FcItem, onJump: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val route = item.jump
    if (route != null) {
        LinkItem(item.title, item.summary) { onJump(route) }
    } else {
        Item(
            title = item.title,
            summary = item.summary,
            titleColor = if (item.level == FcLevel.Bad) colors.error else null,
            end = {
                Text(
                    when (item.level) {
                        FcLevel.Ok -> "✓"
                        FcLevel.Bad -> "!"
                        FcLevel.Warn -> "!"
                        FcLevel.Info -> "—"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = when (item.level) {
                        FcLevel.Ok -> colors.primary
                        FcLevel.Bad -> colors.error
                        FcLevel.Warn -> colors.primary
                        FcLevel.Info -> colors.onSurfaceVariantSummary
                    },
                )
            },
        )
    }
}

/* ───────────────────────── 一键修复弹窗 ───────────────────────── */

@Composable
private fun FixDialog(
    s: Strings,
    fixes: List<FcFix>,
    onDismiss: () -> Unit,
    onRun: (List<FcFix>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val picks = remember(fixes) {
        mutableStateListOf<Boolean>().apply { fixes.forEach { add(!it.risky) } }
    }
    LaunchedEffect(Unit) { show = true }

    WindowDialog(
        show = show,
        title = s.fcFixTitle,
        summary = if (fixes.isEmpty()) s.fcFixNone else s.fcFixIntro,
        onDismissRequest = { show = false },
        onDismissFinished = {
            if (accepted) onRun(fixes.filterIndexed { i, _ -> picks.getOrElse(i) { false } })
            else onDismiss()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            fixes.forEachIndexed { i, fx ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picks[i] = !picks.getOrElse(i) { false } }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (picks.getOrElse(i) { false }) ToggleableState.On else ToggleableState.Off,
                        onClick = { picks[i] = !picks.getOrElse(i) { false } },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            fx.title,
                            style = MiuixTheme.textStyles.body2,
                            color = if (fx.risky) colors.error else colors.onSurface,
                        )
                        Text(
                            fx.detail + if (fx.risky) "  ·  " + s.fcFixRisky else "",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { show = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(s.cancel, style = MiuixTheme.textStyles.button)
                }
                Button(
                    onClick = { accepted = true; show = false },
                    enabled = fixes.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(s.fcFixRun, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}