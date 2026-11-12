package org.icdqcow2.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.TempDirs
import org.icdqcow2.core.BootMode
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.core.probeBootMode
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ───────────────────────── 共用 ───────────────────────── */

/** 一张盘的只读快照：分区表 + 每块（非容器 / 非 MSR）分区的扫描结论。 */
internal data class DiskSnap(
    val pt: ImageRepo.PtInfo?,
    val scans: Map<Long, ImageRepo.Scan>,
) {
    val parts: List<ImageRepo.Part> get() = pt?.parts?.sortedBy { it.lba }.orEmpty()
    val table: String get() = pt?.table ?: "—"
    val uefi: Boolean get() = table.equals("GPT", true)

    fun esp(): ImageRepo.Part? = parts.firstOrNull {
        it.typeGuid.startsWith("C12A7328", true) || it.mbri == 0xEF
    }

    /** 恢复分区：GPT 类型 GUID / MBR 0x27（Windows RE）、0x12（OEM 恢复）。 */
    fun rec(): ImageRepo.Part? = parts.firstOrNull {
        it.typeGuid.startsWith("DE94BBA4", true) || it.mbri == 0x27 || it.mbri == 0x12
    }

    /** 系统保留（MBR 的活动主分区，不是 ESP / RE）。 */
    fun sr(): ImageRepo.Part? = parts.firstOrNull {
        it.active && it.mbri == 0x07 && !it.logical &&
            !it.typeGuid.startsWith("C12A7328", true) && !it.typeGuid.startsWith("DE94BBA4", true)
    }

    /** Windows 分区：根目录有 Windows，否则最大的 NTFS。 */
    fun windows(): ImageRepo.Part? {
        val data = parts.filter {
            !it.isContainer() && !it.logical &&
                !it.typeGuid.startsWith("C12A7328", true) &&
                !it.typeGuid.startsWith("E3C9E316", true) &&
                !it.typeGuid.startsWith("DE94BBA4", true) && it.mbri != 0x27
        }
        return data.firstOrNull { scans[it.lba]?.looksLikeWindows == true }
            ?: data.filter { scans[it.lba]?.fs?.equals("NTFS", true) == true }.maxByOrNull { it.bytes }
    }
}

internal suspend fun loadSnap(img: String): DiskSnap = withContext(Dispatchers.IO) {
    val pt = ImageRepo.parsePt(ImageRepo.parts(img))
    val list = pt?.parts?.sortedBy { it.lba }.orEmpty()
    val scans = HashMap<Long, ImageRepo.Scan>()
    for (p in list) {
        if (p.isContainer() || p.typeGuid.startsWith("E3C9E316", true)) continue
        ImageRepo.parseScan(ImageRepo.scan(p.lba, img))?.let { scans[p.lba] = it }
    }
    DiskSnap(pt, scans)
}

/* ───────────────────────── 引导修复 ───────────────────────── */

/**
 * 引导修复：**先诊断（`boot.check`）再动手**。
 *
 * - UEFI：`bcd.create` 重建 BCD → `boot.populate` 重铺 ESP 上的引导文件；
 * - BIOS：`boot.populate` 整铺（MBR/VBR + bootmgr + BCD），失败退 `boot.repair` 补 VBR；
 * - 修完自动复检一次，把 `boot.check` 的新结论贴在下面。
 */
@Composable
fun BootRepairPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialImage: String? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    // ★ 和别的页面一致：默认不预选，先手动挑镜像（带参跳转才预置）
    var image by remember { mutableStateOf(initialImage) }
    var snap by remember { mutableStateOf<DiskSnap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<Triple<String, String?, Boolean>>>(emptyList()) }
    /** boot.check 本身没跑成时的原因；非空就直接报错，不伪装成「没有检查项」。 */
    var checkErr by remember { mutableStateOf<String?>(null) }
    var bootOk by remember { mutableStateOf<Boolean?>(null) }
    /** 检测出来的启动方式（BIOS / UEFI 64 / UEFI 32）—— 见 core/BootMode.kt。 */
    var mode by remember { mutableStateOf<BootMode?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    /** 修复结果：每一步一行（重建引导 / 补引导文件 / 补恢复环境）。 */
    var results by remember { mutableStateOf<List<Pair<String, ToolRunner.Result>>>(emptyList()) }
    // ── UEFI 32 位固件卡 ──
    // 固件在公共目录的状态（缺不缺 / 能不能写）；UEFI32 的盘才显示这张卡。
    var fwState by remember { mutableStateOf(org.icdqcow2.core.FirmwareStore.State()) }
    var fwBusy by remember { mutableStateOf(false) }
    // ── 引导没问题时的二次确认 ──
    // boot.check 全过 → 点"开始修复"先弹这张卡，确认真的要重写才动。
    var askHealthy by remember { mutableStateOf(false) }

    LaunchedEffect(image, tick) {
        val img = image ?: run {
            snap = null
            checks = emptyList()
            checkErr = null
            bootOk = null
            mode = null
            return@LaunchedEffect
        }
        loading = true
        err = null
        val r = runCatching { loadSnap(img) }.getOrNull()
        snap = r
        if (r?.pt == null) err = s.brNoTable
        val win = r?.windows()
        if (win != null) {
            // 先探"这盘到底是怎么启动的"（BIOS / UEFI 64 / UEFI 32），
            // 再把这个结论原样交给 boot.check —— 不再拿"是不是 GPT"瞎猜。
            val table = r.table
            val espLba = r.esp()?.lba
            val sysLba = win.lba
            val m = withContext(Dispatchers.IO) {
                probeBootMode(table = table, espLba = espLba, sysLba = sysLba, img = img)
            }
            mode = m
            var bc = withContext(Dispatchers.IO) { ImageRepo.bootCheck(path = img, mode = m.wire) }
            // 库 2026.11.10 起 --mode 认 auto | uefi | uefi32 | bios；更早的库传 uefi32
            // 会被判「参数不合法」，那就退回 uefi 再跑一次（检查项两套一致）。
            if (!bc.ok && m == BootMode.UEFI32) {
                bc = withContext(Dispatchers.IO) { ImageRepo.bootCheck(path = img, mode = "uefi") }
            }
            bootOk = bc.ok && (bc.data?.optInt("required_fail", 0) ?: 0) == 0
            checkErr = if (bc.ok) null else toolErrorText(s, bc)
            checks = bootCheckRows(s, bc.data, m.isUefi, withRecovery = true)
            // UEFI 32 的盘顺手看一眼固件状态（不在这里释放，用户点卡片里的按钮才做）
            if (m == BootMode.UEFI32) {
                fwState = org.icdqcow2.core.FirmwareStore.state()
            }
        } else {
            bootOk = null
            checks = emptyList()
            checkErr = null
            mode = null
        }
        loading = false
    }

    val win = snap?.windows()
    val uefi = mode?.isUefi ?: (snap?.uefi ?: false)

    SmallTitlePage(title = s.brTitle, onBack = onBack) {
        BlockEnter(0) {
            if (image == null) {
                Note(s.brNeedImage)
                PickImagePage(onPicked = { path, uri ->
                    image = path ?: uri
                    tick += 1
                })
            } else {
                CurrentFileCard(name = pickName(image!!), path = image, onToast = onToast, writable = true)
            }
        }

        if (image != null) {
            when {
                loading -> BlockEnter(1) { Note(s.partsLoading) }
                err != null -> BlockEnter(1) { Note(err!!, warn = true) }
                win == null -> BlockEnter(1) { Note(s.brNoSys, warn = true) }
                else -> {
                    BlockEnter(1) {
                        GroupTitle(s.brDiag)
                        GroupCard {
                            Item(s.brMode, when (mode) {
                                BootMode.UEFI32 -> s.brModeUefi32
                                BootMode.UEFI64 -> s.brModeUefi
                                BootMode.BIOS -> s.brModeBios
                                null -> if (uefi) s.brModeUefi else s.brModeBios
                            })
                            Item(
                                s.brSysPart,
                                s.brPartValue(
                                    snap!!.parts.indexOfFirst { it.lba == win.lba } + 1,
                                    fmtBytes(win.bytes),
                                ),
                            )
                            snap!!.esp()?.let { e ->
                                Item(
                                    s.brEspPart,
                                    s.brPartValue(snap!!.parts.indexOfFirst { it.lba == e.lba } + 1, fmtBytes(e.bytes)),
                                )
                            }
                            snap!!.sr()?.let { e ->
                                Item(
                                    s.brSrPart,
                                    s.brPartValue(snap!!.parts.indexOfFirst { it.lba == e.lba } + 1, fmtBytes(e.bytes)),
                                )
                            }
                        }
                    }
                    BlockEnter(2) {
                        GroupTitle(s.brChecks)
                        GroupCard {
                            if (checkErr != null) {
                                // 命令失败：报出来在哪儿失败的，别显示成「没有检查项」
                                Item(s.brCheckFail, checkErr, titleColor = MiuixTheme.colorScheme.error)
                            } else if (checks.isEmpty()) {
                                // 确实没有可显示的项：只留一行，不复读上面的小标题
                                Item(s.fcBootNone)
                            } else {
                                checks.forEach { (name, detail, pass) ->
                                    Item(
                                        name,
                                        detail,
                                        titleColor = if (pass) null else MiuixTheme.colorScheme.error,
                                        end = {
                                            Text(
                                                if (pass) "✓" else "!",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = if (pass) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.error,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (bootOk == true) BlockEnter(3) { Note(s.brDiagOk) }
                    if (results.isNotEmpty()) {
                        BlockEnter(3) {
                            GroupTitle(s.brResult)
                            GroupCard {
                                results.forEach { (label, r) ->
                                    Item(
                                        label,
                                        if (r.ok) null else toolErrorText(s, r),
                                        titleColor = if (r.ok) null else MiuixTheme.colorScheme.error,
                                        end = {
                                            Text(
                                                if (r.ok) "✓" else "!",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = if (r.ok) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.error,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        if (bootOk == false) BlockEnter(3) { Note(s.brStillBad, warn = true) }
                    }
                    // ── UEFI 32 位：固件 + 注入参数 ────────────────────────────────────────
                    // 32 位 UEFI 不是默认能力，得给 VM 换 32 位固件（pflash 挂两颗芯片）。
                    // 这张卡只在检测到 UEFI 32 时出现，别的盘一眼都看不到。
                    if (mode == BootMode.UEFI32 && win != null) {
                        BlockEnter(4) {
                            GroupTitle(s.fw32Title)
                            GroupCard {
                                Item(
                                    s.fw32State,
                                    if (fwState.ready) s.fw32Ready else s.fw32Missing,
                                    titleColor = if (fwState.ready) null else MiuixTheme.colorScheme.error,
                                )
                                Item(s.fw32Where, org.icdqcow2.core.FirmwareStore.DIR)
                                if (!fwState.ready) {
                                    Item(
                                        s.fw32Install,
                                        s.fw32InstallHint,
                                        onClick = {
                                            if (fwBusy) return@Item
                                            fwBusy = true
                                            scope.launch {
                                                val e = withContext(Dispatchers.IO) {
                                                    org.icdqcow2.core.FirmwareStore.ensure(ctx)
                                                }
                                                fwBusy = false
                                                fwState = org.icdqcow2.core.FirmwareStore.state()
                                                onToast(
                                                    when (e) {
                                                        null -> s.fw32Done
                                                        "no-access" -> s.fw32NoAccess
                                                        else -> s.fw32Fail
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                                Item(
                                    s.fw32Copy,
                                    s.fw32CopyHint,
                                    onClick = {
                                        val text = org.icdqcow2.core.FirmwareStore.qemuArgs()
                                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as? android.content.ClipboardManager
                                        if (cm == null) onToast(s.fw32Fail) else {
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("qemu", text))
                                            onToast(s.fw32Copied)
                                        }
                                    },
                                )
                                // 使用方法：只读说明，点不动
                                Item(s.fw32HowTitle, s.fw32How)
                            }
                        }
                    }

                    BlockEnter(6) {
                        Action(text = s.brAction, enabled = !running, danger = true) {
                            // 引导全过 → 先问一句"真的要重写？"；有问题就直接走原有确认卡
                            if (bootOk == true) askHealthy = true else ask = true
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ── 引导没问题的二次确认 ──────────────────────────────────────────────
    if (askHealthy) {
        ConfirmDialog(
            title = s.brHealthyTitle,
            summary = s.brHealthyBody,
            confirmText = s.brHealthyGo,
            cancelText = s.cancel,
            danger = true,
            onDismiss = { askHealthy = false },
            onConfirm = {
                askHealthy = false
                ask = true
            },
        )
    }

    if (ask && win != null && snap != null) {
        // 目标分区：UEFI 写 ESP，BIOS 写系统分区
        val tgt = (if (uefi) snap!!.esp()?.lba ?: win.lba else win.lba)
        val tgtNo = snap!!.parts.indexOfFirst { it.lba == tgt } + 1
        ConfirmDialog(
            title = s.brConfirmTitle,
            summary = s.brConfirmBody(tgtNo),
            confirmText = s.brAction,
            cancelText = s.cancel,
            danger = true,
            onDismiss = { ask = false },
            onConfirm = {
                ask = false
                val img = image
                val cur = snap
                if (img != null && cur != null) scope.launch {
                    val esp = cur.esp()?.lba ?: 0L
                    val sr = cur.sr()?.lba ?: 0L
                    val recLba = cur.rec()?.lba ?: 0L
                    running = true
                    results = emptyList()
                    val steps = ArrayList<Pair<String, ToolRunner.Result>>()

                    // ① 重建引导（UEFI: bcd.create + boot.populate；BIOS: 整铺，失败退 boot.repair）
                    val boot = withContext(Dispatchers.IO) { runBootFix(img, win.lba, esp, sr, uefi) }
                    steps += s.brStepBoot to boot

                    // ② 还是不行 → 从系统分区手工取引导文件再放一遍
                    if (!boot.ok) {
                        val fb = withContext(Dispatchers.IO) { runBootFallback(ctx, img, win.lba, esp, sr, uefi) }
                        steps += s.brStepFiles to (fb ?: ToolRunner.Result(
                            cmd = "ntfs.exists", ok = false, code = "SRC-NOTFOUND",
                        ))
                    }

                    // ③ 恢复分区（交叉内容）：文件不全就顺手补上——**不写开启脚本**
                    if (recLba > 0L && withContext(Dispatchers.IO) { recNeedsFill(img, recLba) }) {
                        val rf = withContext(Dispatchers.IO) { fillRecFromSys(ctx, img, win.lba, recLba) }
                        steps += s.brStepRe to rf
                    }

                    running = false
                    results = steps
                    onToast(if (steps.all { it.second.ok }) s.brRunOk else s.brRunFail)
                    tick += 1
                }
            },
        )
    }
}


/** 恢复分区里的 WinRE 三件套是否齐全。 */
private suspend fun recNeedsFill(img: String, recLba: Long): Boolean = withContext(Dispatchers.IO) {
    val w = ImageRepo.ntfsExists(recLba, WINRE_P1, img) == true
    val sd = ImageRepo.ntfsExists(recLba, "/Recovery/WindowsRE/boot.sdi", img) == true
    val ag = ImageRepo.ntfsExists(recLba, "/Recovery/WindowsRE/ReAgent.xml", img) == true
    !(w && sd && ag)
}

/**
 * 用**系统分区里那份** WinRE 把恢复分区补齐。
 *
 * 恢复分区里已经有 WinRE、只是缺小件 → 走原地修复（不格式化）；
 * 连 WinRE 都没有 → 才真的 `recovery.populate`（那一步必须格式化）。
 *
 * 不写开启脚本（`enable-script`）：引导修复页只负责"能启动/能进恢复环境"。
 */
private suspend fun fillRecFromSys(
    ctx: android.content.Context,
    img: String,
    sysLba: Long,
    recLba: Long,
): ToolRunner.Result = withContext(Dispatchers.IO) {
    val recHasWim = ImageRepo.ntfsExists(recLba, WINRE_P1, img) == true
    if (recHasWim) {
        // ★ 有 WinRE 就只补小件：以前这里格式化了整块恢复分区，
        //   修完反而把 boot.sdi / ReAgent.xml 弄丢（用户 2026-11-11 实拍）。
        val rep = repairRecInPlace(
            ctx = ctx, img = img, sysLba = sysLba, recLba = recLba,
            needSdi = ImageRepo.ntfsExists(recLba, SDI_REC, img) != true,
            needAgent = ImageRepo.ntfsExists(recLba, AGENT_REC, img) != true,
            withScript = false,
        )
        return@withContext ToolRunner.Result(
            cmd = "copy",
            ok = rep.ok,
            code = if (rep.ok) null else rep.err,
        )
    }
    val src = when {
        ImageRepo.ntfsExists(sysLba, WINRE_P1, img) == true -> WINRE_P1
        ImageRepo.ntfsExists(sysLba, WINRE_P2, img) == true -> WINRE_P2
        else -> null
    } ?: return@withContext ToolRunner.Result(cmd = "ntfs.exists", ok = false, code = "WINRE-NOTFOUND")
    val dir = File(TempDirs.stagingDir(ctx), "brre").apply { mkdirs() }
    val local = File(dir, "winre.wim")
    val ex = ImageRepo.ntfsExport(sysLba, src, local.absolutePath, img)
    if (!ex.ok) return@withContext ex
    var sdi: File? = null
    val sysSdi = SDI_SOURCES.firstOrNull { ImageRepo.ntfsExists(sysLba, it, img) == true }
    if (sysSdi != null) {
        val f = File(dir, "boot.sdi")
        if (ImageRepo.ntfsExport(sysLba, sysSdi, f.absolutePath, img).ok) sdi = f
    }
    ImageRepo.recoveryPopulate(
        lba = recLba,
        wim = local.absolutePath,
        bootsdi = sdi?.absolutePath,
        // 独立恢复分区且里面没有 WinRE：先格式化再填（工具端 format 默认 false）
        format = true,
        path = img,
    )
}

/**
 * `boot.populate` 失败后的兜底：**从系统分区**取引导文件手工铺一遍。
 *
 * UEFI：`\Windows\Boot\EFI\bootmgfw.efi` → ESP 的 `\EFI\Microsoft\Boot\bootmgfw.efi`
 * （再复制一份到回退入口 `\EFI\Boot\bootx64.efi`）→ `bcd.create`。
 * BIOS：`\Windows\Boot\PCAT\bootmgr` → 活动分区/SR 的 `\bootmgr` → `bcd.create`。
 *
 * ★ 版本天然一致：文件就是从**同一套 Windows** 的 `\Windows\Boot` 拿的，
 *   不存在"bootmgr 和系统版本对不上"的问题（跨映像取才需要校验版本）。
 */
private suspend fun runBootFallback(
    ctx: android.content.Context,
    img: String,
    sysLba: Long,
    espLba: Long,
    srLba: Long,
    uefi: Boolean,
): ToolRunner.Result? = withContext(Dispatchers.IO) {
    val dir = File(TempDirs.stagingDir(ctx), "brfb").apply { mkdirs() }
    if (uefi) {
        if (espLba <= 0L) return@withContext null
        val src = "/Windows/Boot/EFI/bootmgfw.efi"
        if (ImageRepo.ntfsExists(sysLba, src, img) != true) return@withContext null
        val f = File(dir, "bootmgfw.efi")
        val ex = ImageRepo.ntfsExport(sysLba, src, f.absolutePath, img)
        if (!ex.ok) return@withContext ex
        val put = ImageRepo.fatPut(espLba, f.absolutePath, "/EFI/Microsoft/Boot/bootmgfw.efi", img)
        if (!put.ok) return@withContext put
        ImageRepo.fatPut(espLba, f.absolutePath, "/EFI/Boot/bootx64.efi", img)
        ImageRepo.bcdCreate(sysLba, espLba = espLba, keepRecovery = true, path = img)
    } else {
        val target = if (srLba > 0L) srLba else sysLba
        val src = "/Windows/Boot/PCAT/bootmgr"
        if (ImageRepo.ntfsExists(sysLba, src, img) != true) return@withContext null
        val f = File(dir, "bootmgr")
        val ex = ImageRepo.ntfsExport(sysLba, src, f.absolutePath, img)
        if (!ex.ok) return@withContext ex
        val put = ImageRepo.ntfsPut(target, f.absolutePath, "/bootmgr", img)
        if (!put.ok) return@withContext put
        ImageRepo.bcdCreate(sysLba, srLba = srLba, keepRecovery = true, path = img)
    }
}

/* ───────────────────────── 恢复环境：原地修复 ───────────────────────── */

/** Windows 里 boot.sdi 的三个标准位置（UEFI 那份最常见；`Disk` 是历史误写，留着兜底）。 */
private val SDI_SOURCES = listOf(
    "/Windows/Boot/DVD/EFI/boot.sdi",
    "/Windows/Boot/DVD/PCAT/boot.sdi",
    "/Windows/Boot/Disk/boot.sdi",
)

private const val SDI_REC = "/Recovery/WindowsRE/boot.sdi"
private const val AGENT_REC = "/Recovery/WindowsRE/ReAgent.xml"
private const val AGENT_SYS = "/Windows/System32/Recovery/ReAgent.xml"

/**
 * 修复结果：`lines` 直接拿去显示（`文件名 to 结果码`），`err` 是失败的那一步。
 *
 * 结果码：`OK` 写入成功、`CP` 从系统盘复制、`GEN` 按官方格式生成、`NO-SDI` 找不到源、
 * `EXP-*` 导出失败、`PUT-*` 写入失败、`SCRIPT` 脚本失败。
 */
internal data class RecFixReport(
    val ok: Boolean,
    val lines: List<Pair<String, String>>,
    val err: String? = null,
)

/**
 * **原地**补齐恢复分区里的 WinRE 小件（用户 2026-11-11 实拍的那串坑）。
 *
 * 老实现（`recovery.populate` + `format=true`）做的是：**先格式化恢复分区**，再写回 winre.wim。
 * 两个后果：
 * 1. `boot.sdi` 取源写的是 `/Windows/Boot/Disk/boot.sdi` —— Windows 里**没有这个目录**，
 *    所以从来没写进去过；格式化之后原来那份也没了；
 * 2. `ReAgent.xml` 工具端有生成函数（`img_ntfs_write_reagent`）但**没有任何命令调用它**，
 *    所以永远缺。
 *
 * 于是"修复"一按就变成缺两个文件。这里改成只在原地补：
 * - 绝不格式化、绝不重写 winre.wim；
 * - `boot.sdi` 从系统盘三个标准位置取一份 → `copy` 进恢复分区；
 * - `ReAgent.xml` 优先从系统盘复制（真实 Windows 那份），没有才按官方格式生成；
 * - 「创建开启 WinRE 脚本」走 `winre.auto`（和 populate 的 enable-script 是同一份脚本）。
 */
internal suspend fun repairRecInPlace(
    ctx: android.content.Context,
    img: String,
    sysLba: Long,
    recLba: Long,
    needSdi: Boolean,
    needAgent: Boolean,
    withScript: Boolean,
): RecFixReport = withContext(Dispatchers.IO) {
    val lines = ArrayList<Pair<String, String>>()
    if (recLba <= 0L) return@withContext RecFixReport(false, lines, "NO-REC")
    val dir = File(TempDirs.stagingDir(ctx), "recfix").apply { mkdirs() }

    if (needSdi) {
        val src = if (sysLba > 0L) SDI_SOURCES.firstOrNull {
            ImageRepo.ntfsExists(sysLba, it, img) == true
        } else null
        if (src == null) {
            return@withContext RecFixReport(false, lines + ("boot.sdi" to "NO-SDI"), "NO-SDI")
        }
        val local = File(dir, "boot.sdi")
        if (!ImageRepo.ntfsExport(sysLba, src, local.absolutePath, img).ok) {
            return@withContext RecFixReport(false, lines + ("boot.sdi" to "EXP-FAIL"), "EXP-SDI")
        }
        val put = ImageRepo.ntfsPut(recLba, local.absolutePath, SDI_REC, img)
        lines += "boot.sdi" to if (put.ok) "OK" else "PUT-FAIL"
        if (!put.ok) return@withContext RecFixReport(false, lines, put.code ?: "PUT-SDI")
    } else {
        lines += "boot.sdi" to "SKIP"
    }

    if (needAgent) {
        val local = File(dir, "ReAgent.xml")
        val copied = sysLba > 0L &&
            ImageRepo.ntfsExists(sysLba, AGENT_SYS, img) == true &&
            ImageRepo.ntfsExport(sysLba, AGENT_SYS, local.absolutePath, img).ok
        if (!copied) local.writeText(reagentXml(recLba))
        val put = ImageRepo.ntfsPut(recLba, local.absolutePath, AGENT_REC, img)
        lines += "ReAgent.xml" to if (!put.ok) "PUT-FAIL" else if (copied) "CP" else "GEN"
        if (!put.ok) return@withContext RecFixReport(false, lines, put.code ?: "PUT-AGENT")
    } else {
        lines += "ReAgent.xml" to "SKIP"
    }

    if (withScript) {
        val r = ImageRepo.winreAuto(sysLba, true, img)
        lines += "SetupComplete.cmd" to if (r.ok) "OK" else "FAIL"
        if (!r.ok) return@withContext RecFixReport(false, lines, r.code ?: "SCRIPT")
    }
    RecFixReport(true, lines)
}

/**
 * 按微软官方格式生成 `ReAgent.xml`（与工具端 `img_ntfs_write_reagent()` 里的字面量一致）。
 *
 * `offset` 是恢复分区在磁盘上的**字节偏移**（reagentc 用它定位分区）；BCD id 给全零 GUID，
 * 首次开机 `reagentc /enable` 会自己写进去。
 */
private fun reagentXml(recLba: Long): String {
    val zero = "{00000000-0000-0000-0000-000000000000}"
    val off = recLba * 512L
    return buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes'?>\r\n")
        append("<WindowsRE version=\"2.0\">\r\n")
        append("\t<WinreBCD id=\"$zero\"></WinreBCD>\r\n")
        append("\t<WinreLocation path=\"\\Recovery\\WindowsRE\" id=\"0\" offset=\"$off\" guid=\"$zero\"></WinreLocation>\r\n")
        append("\t<ImageLocation path=\"\" id=\"0\" offset=\"0\" guid=\"$zero\"></ImageLocation>\r\n")
        append("\t<PBRImageLocation path=\"\" id=\"0\" offset=\"0\" guid=\"$zero\" index=\"0\"></PBRImageLocation>\r\n")
        append("\t<PBRCustomImageLocation path=\"\" id=\"0\" offset=\"0\" guid=\"$zero\" index=\"0\"></PBRCustomImageLocation>\r\n")
        append("\t<InstallState state=\"1\"></InstallState>\r\n")
        append("\t<OsInstallAvailable state=\"0\"></OsInstallAvailable>\r\n")
        append("\t<CustomImageAvailable state=\"0\"></CustomImageAvailable>\r\n")
        append("\t<WinREStaged state=\"0\"></WinREStaged>\r\n")
        append("\t<ScheduledOperation state=\"4\"></ScheduledOperation>\r\n")
        append("\t<OperationParam path=\"\"></OperationParam>\r\n")
        append("\t<OperationPermanent state=\"0\"></OperationPermanent>\r\n")
        append("\t<OsBuildVersion path=\"\"></OsBuildVersion>\r\n")
        append("\t<OemTool state=\"0\"></OemTool>\r\n")
        append("</WindowsRE>\r\n")
    }
}


/* ───────────────────────── WinRE ───────────────────────── */

/** GPT 恢复分区的官方属性：必需位 + 不分配盘符。 */
private const val PART_ATTR_RECOVERY = "0x8000000000000001"

private const val WINRE_P1 = "/Recovery/WindowsRE/winre.wim"
private const val WINRE_P2 = "/Windows/System32/Recovery/WinRE/WinRE.wim"

/**
 * 恢复环境（WinRE）。
 *
 * 检测口径（用户 2026-10-28 定）：
 * 1. 没有恢复分区 → **不算错误**，只显示「未配置恢复环境」；
 * 2. 有恢复分区但分区里没有 `winre.wim` → **算异常**，提示修复；
 * 3. 系统盘里有 WinRE → 默认就用系统盘那份；
 * 4. 都正常 → 正常。
 *
 * 写入目标（用户 2026-10-29 定）：**GPT 可以写恢复分区或系统分区；MBR 只能写系统分区**
 * （MBR 走 `recovery.populate-system`，GPT 的独立恢复分区走 `recovery.populate`）。
 *
 * 磁盘布局长条在这页**只读**：只把要写入的那块框起来，点不动。
 */
@Composable
fun WinRePage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialImage: String? = null,
    /** 自检页带进来时用来跳「分区管理」；单独打开时为 null。 */
    onOpenParts: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // ★ 和别的页面一致：默认不预选，先手动挑镜像（带参跳转才预置）
    var image by remember { mutableStateOf(initialImage) }
    var snap by remember { mutableStateOf<DiskSnap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    // ── 扫描结果 ──
    /** 恢复分区里有没有 winre.wim；null = 没有恢复分区。 */
    var recWim by remember { mutableStateOf<Boolean?>(null) }
    /** 系统盘里有没有 WinRE（两个标准位置之一）。 */
    var sysWim by remember { mutableStateOf(false) }
    var sysWimPath by remember { mutableStateOf<String?>(null) }
    var sdiOk by remember { mutableStateOf(false) }
    var agentOk by remember { mutableStateOf(false) }

    // ── 来源：null = 用系统盘那份；非空 = 用户从别的盘选的 ──
    var srcFile by remember { mutableStateOf<String?>(null) }
    var probeBusy by remember { mutableStateOf(false) }
    var probeHit by remember { mutableStateOf<ImageRepo.WinreHit?>(null) }

    // ── 写入选项 ──
    /** true = 写进系统分区；false = 写进恢复分区。MBR 恒为 true。 */
    var toSys by remember { mutableStateOf(false) }
    /** 写 cmd 脚本（首次开机自动启用 WinRE）。 */
    var script by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    /** 已经就绪时默认收起，"重新写入"才展开下面那一堆卡片。 */
    var expanded by remember { mutableStateOf(false) }
    /** `null` = 还没选；`"write"` = 重新写入（展开完整写入设置）；`"fix"` = 原地修复。 */
    var mode by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageAccess.documentToPath(uri)
        if (path == null) {
            onToast(s.depWimBad)
            return@rememberLauncherForActivityResult
        }
        srcFile = path
        scope.launch {
            probeBusy = true
            val probe = ImageRepo.winreProbe(path)
            probeBusy = false
            probeHit = probe.hit
            if (!probe.found) {
                onToast(if (probe.empty) s.wrNotFound else s.wrProbeFail)
            }
        }
    }

    // 换镜像：收起展开、清掉上次选的来源
    LaunchedEffect(image) {
        expanded = false
        srcFile = null
        probeHit = null
        script = false
        mode = null
    }

    LaunchedEffect(image, tick) {
        val img = image ?: run {
            snap = null
            recWim = null
            sysWim = false
            sysWimPath = null
            return@LaunchedEffect
        }
        loading = true
        result = null
        val r = runCatching { loadSnap(img) }.getOrNull()
        snap = r
        val rec = r?.rec()
        val win = r?.windows()
        if (rec == null) {
            recWim = null
            sdiOk = false
            agentOk = false
        } else {
            recWim = withContext(Dispatchers.IO) {
                ImageRepo.ntfsExists(rec.lba, WINRE_P1, img)
            } == true
            sdiOk = withContext(Dispatchers.IO) {
                ImageRepo.ntfsExists(rec.lba, "/Recovery/WindowsRE/boot.sdi", img)
            } == true
            agentOk = withContext(Dispatchers.IO) {
                ImageRepo.ntfsExists(rec.lba, "/Recovery/WindowsRE/ReAgent.xml", img)
            } == true
        }
        sysWimPath = if (win == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                when {
                    ImageRepo.ntfsExists(win.lba, WINRE_P1, img) == true -> WINRE_P1
                    ImageRepo.ntfsExists(win.lba, WINRE_P2, img) == true -> WINRE_P2
                    else -> null
                }
            }
        }
        sysWim = sysWimPath != null
        // 恢复环境在、但缺 boot.sdi / ReAgent.xml → 「创建开启 WinRE 脚本」默认开
        script = rec != null && recWim == true && (!sdiOk || !agentOk)
        // MBR 只有"写系统分区"这一条路；GPT 有恢复分区时默认写恢复分区
        toSys = r == null || !r.uefi || rec == null
        loading = false
    }

    val rec = snap?.rec()
    val win = snap?.windows()
    val uefi = snap?.uefi ?: false
    val winIndex = snap?.parts?.indexOfFirst { it.lba == win?.lba } ?: -1
    val recIndex = snap?.parts?.indexOfFirst { it.lba == rec?.lba } ?: -1
    /** 最终目标：false = 恢复分区，true = 系统分区。 */
    val useSys = !uefi || rec == null || toSys
    val targetLba = (if (useSys) win else rec)?.lba ?: 0L
    val hasSource = srcFile != null || sysWim
    /** 恢复分区里 WinRE + boot.sdi + ReAgent.xml 全齐：只显示「重新写入」。 */
    val reReady = recWim?.let { it && sdiOk && agentOk } ?: false
    /** 有 WinRE 但缺 boot.sdi / ReAgent.xml：多给一条「修复」。 */
    val reBroken = recWim == true && (!sdiOk || !agentOk)
    /** 修复模式：只补缺的小件，不格式化、不重写 winre.wim（用户 2026-11-11 要求单独一张卡）。 */
    val fixMode = mode == "fix" && reBroken
    /** 要不要展示布局条 / 来源 / 目标 / 写入这一整套（点「重新写入」才出）。 */
    val showAll = mode == "write" || expanded || !(reReady || reBroken)

    /** 真正写盘：来源 = 用户选的（若有），否则系统盘那份（先抽出来）。 */
    suspend fun writeNow(
        img: String,
        useSystem: Boolean,
        lba: Long,
        withScript: Boolean,
    ): ToolRunner.Result = withContext(Dispatchers.IO) {
        val sysLba = win?.lba ?: 0L
        val fromFile = srcFile
        val hit = probeHit

        // ★ ramdisk 要的 `boot.sdi`：工具端只吃本地文件，先从系统盘三个标准位置取一份。
        //   用户 2026-11-11：重写 WinRE 之后 boot.sdi 反而没了 —— 因为以前这里**根本没传**
        //   `bootsdi-src`，而 `recovery.populate` 不传就不写它。
        val sdiLocal: File? = if (sysLba > 0L) {
            val src = SDI_SOURCES.firstOrNull { ImageRepo.ntfsExists(sysLba, it, img) == true }
            if (src != null) {
                val dir = File(TempDirs.stagingDir(ctx), "wr").apply { mkdirs() }
                val f = File(dir, "boot.sdi")
                if (ImageRepo.ntfsExport(sysLba, src, f.absolutePath, img).ok) f else null
            } else null
        } else null

        val r = if (fromFile != null) {
            ImageRepo.recoveryPopulate(
                lba = lba,
                wim = fromFile,
                index = hit?.index ?: 1,
                inner = hit?.inner,
                winrePath = hit?.internalPath,
                enableScript = withScript,
                bootsdi = sdiLocal?.absolutePath,
                osLba = sysLba,
                system = useSystem,
                // 独立恢复分区才格式化；系统分区那条（useSystem）绝不能格
                format = !useSystem,
                path = img,
            )
        } else {
            // 系统盘那份：先抽到本地再灌（工具端只吃本地文件）
            val dir = File(TempDirs.stagingDir(ctx), "wr").apply { mkdirs() }
            val local = File(dir, "winre.wim")
            val ex = ImageRepo.ntfsExport(sysLba, sysWimPath!!, local.absolutePath, img)
            if (!ex.ok) {
                ex
            } else {
                ImageRepo.recoveryPopulate(
                    lba = lba,
                    wim = local.absolutePath,
                    enableScript = withScript,
                    bootsdi = sdiLocal?.absolutePath,
                    osLba = sysLba,
                    system = useSystem,
                    format = !useSystem,
                    path = img,
                )
            }
        }

        // ★ 工具端 `recovery.populate` **不写 ReAgent.xml**（`img_ntfs_write_reagent`
        //   有实现但没有调用者），写完 WinRE 得自己补一份，否则自检照样报缺失。
        if (r.ok && !useSystem) {
            repairRecInPlace(
                ctx = ctx, img = img, sysLba = sysLba, recLba = lba,
                needSdi = false, needAgent = true, withScript = false,
            )
        }
        r
    }

    SmallTitlePage(title = s.wrTitle, onBack = onBack) {
        BlockEnter(0) {
            if (image == null) {
                Note(s.wrNeedImage)
                PickImagePage(onPicked = { path, uri ->
                    image = path ?: uri
                    tick += 1
                })
            } else {
                CurrentFileCard(name = pickName(image!!), path = image, onToast = onToast, writable = true)
            }
        }

        if (image != null) {
            when {
                loading -> BlockEnter(1) { Note(s.partsLoading) }
                snap?.pt == null -> BlockEnter(1) { Note(s.brNoTable, warn = true) }
                else -> {
                    // ① 现状
                    BlockEnter(1) {
                        GroupTitle(s.wrSecNow)
                        GroupCard {
                            if (rec == null) {
                                // 没有恢复分区：不算错误
                                Item(s.wrPart, s.wrPartNone)
                            } else {
                                Item(
                                    s.wrPart,
                                    s.wrPartValue(recIndex + 1, fmtBytes(rec.bytes)),
                                )
                            }
                            // 只有"有恢复分区、但里面没 winre.wim"才算缺失
                            val wimText = when {
                                rec == null && !sysWim -> s.wrWimNone
                                rec == null -> s.wrWimSysOnly
                                recWim == true -> s.fcOk
                                sysWim -> s.wrWimMissSys
                                else -> s.wrWimMiss
                            }
                            Item(
                                s.wrWim,
                                wimText,
                                titleColor = if (rec != null && recWim == false) MiuixTheme.colorScheme.error else null,
                            )
                            if (rec != null) {
                                Item(s.wrSdi, if (sdiOk) s.fcOk else s.fcMissing)
                                Item(s.wrAgent, if (agentOk) s.fcOk else s.fcMissing)
                                // GPT 恢复分区要带 0x8000000000000001（必需位 + 不分配盘符），
                                // 老盘建的时候只置了 bit63，所以单独看一行
                                if (uefi) {
                                    val attrNow = rec.attributes.orEmpty()
                                    val attrOk = attrNow.equals(PART_ATTR_RECOVERY, true)
                                    Item(
                                        s.wrPartAttr,
                                        when {
                                            // 0x8000000000000001 = bit0「必需分区」+ bit63「不分配盘符」
                                            attrOk -> s.wrPartAttrOk
                                            attrNow.isEmpty() -> s.wrPartAttrBad
                                            else -> "$attrNow · " + s.wrPartAttrBad
                                        },
                                        titleColor = if (attrOk) null else MiuixTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        if (rec != null && uefi && !rec.attributes.orEmpty().equals(PART_ATTR_RECOVERY, true)) {
                            LinkItem(s.wrPartAttrFix, s.wrPartAttrFixSummary) {
                                scope.launch {
                                    running = true
                                    val r = ImageRepo.setPartAttr(rec.lba, PART_ATTR_RECOVERY, image)
                                    running = false
                                    onToast(if (r.ok) s.wrOk else toolErrorText(s, r))
                                    if (r.ok) tick++
                                }
                            }
                        }
                    }

                    if (result != null) {
                        BlockEnter(2) {
                            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                                Text(
                                    result!!,
                                    style = MiuixTheme.textStyles.body2,
                                    color = if (result!!.startsWith(s.wrOk)) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    // ② 恢复环境已就位 / 只差小件：只给入口，不摊开全部设置
                    AnimatedVisibility(
                        visible = !showAll && !fixMode,
                        enter = fadeIn(tween(180)) +
                            slideInVertically(tween(260, easing = LinearOutSlowInEasing)) { it / 6 },
                        exit = fadeOut(tween(160)) +
                            shrinkVertically(tween(220, easing = LinearOutSlowInEasing)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BlockEnter(3) {
                                GroupCard {
                                    LinkItem(s.wrRewrite, s.wrRewriteSummary) {
                                        mode = "write"
                                        expanded = true
                                    }
                                    // ★ 只有恢复分区里**已经有** WinRE 才给「修复」：
                                    //   连 WinRE 都没有的时候没什么可修，只能重新写入（用户 2026-11-11）。
                                    if (reBroken) LinkItem(s.wrFixTitle, s.wrFixSummary) {
                                        mode = "fix"
                                        result = null
                                    }
                                    // ★ 这里不再放「创建开启 WinRE 脚本」开关：
                                    //   它和上面两条（重新写入 / 修复）不沾边，脚本只在
                                    //   「重新写入」和「修复」各自的卡片里出现（用户 2026-11-11）。
                                }
                            }
                        }
                    }

                    // ②b 修复模式：**只补缺的文件** —— 不选来源、不选目标、不格式化
                    AnimatedVisibility(
                        visible = fixMode,
                        enter = fadeIn(tween(180)) +
                            slideInVertically(tween(260, easing = LinearOutSlowInEasing)) { it / 6 },
                        exit = fadeOut(tween(160)) +
                            shrinkVertically(tween(220, easing = LinearOutSlowInEasing)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BlockEnter(3) {
                                GroupTitle(s.wrFixTitle)
                                GroupCard {
                                    Item(s.wrWim, s.wrFixKeep)
                                    Item(s.wrSdi, if (sdiOk) s.wrFixSkip else s.wrFixFromSys)
                                    Item(s.wrAgent, if (agentOk) s.wrFixSkip else s.wrFixAgent)
                                    Item(
                                        s.wrScript,
                                        if (!script) s.depRecScriptHint
                                        else s.depScriptWhere("Windows/System32/Recovery"),
                                        end = { Switch(checked = script, onCheckedChange = { script = it }) },
                                    )
                                }
                                Note(s.wrFixBody)
                                LinkItem(s.wrFixBack, s.wrFixSummary) { mode = null }
                            }
                            BlockEnter(4) {
                                if (win == null) Note(s.wrFixNeedSys) else Note(s.wrBody)
                                Action(
                                    text = if (running) s.wrWriting else s.wrFix,
                                    enabled = !running && !loading && win != null && rec != null,
                                ) {
                                    val img = image
                                    if (img != null && rec != null) scope.launch {
                                        running = true
                                        val rep = repairRecInPlace(
                                            ctx = ctx,
                                            img = img,
                                            sysLba = win?.lba ?: 0L,
                                            recLba = rec.lba,
                                            needSdi = !sdiOk,
                                            needAgent = !agentOk,
                                            withScript = script,
                                        )
                                        running = false
                                        result = if (rep.ok) s.wrFixDone
                                        else s.wrFail + "：" + when (rep.err) {
                                            "NO-SDI", "EXP-SDI" -> s.wrFixNoSdi
                                            "NO-REC" -> s.wrPartNone
                                            else -> rep.err.orEmpty()
                                        }
                                        onToast(result!!)
                                        mode = if (rep.ok) null else "fix"
                                        tick += 1
                                    }
                                }
                            }
                        }
                    }

                    // ③ 展开：布局条 / 来源 / 目标 / 写入（点「重新写入」才出来）
                    AnimatedVisibility(
                        visible = showAll && !fixMode,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(140)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // 磁盘布局：只读，只把要写入的那块框起来
                            BlockEnter(2) {
                                GroupTitle(s.partsLayoutTitle)
                                val ptNow = snap!!.pt
                                if (ptNow != null && ptNow.diskSectors > 0L) {
                                    val segs = buildSegments(ptNow, s, snap!!.scans)
                                    DiskBar(segs, ptNow.diskSectors, selectedLba = targetLba) { }
                                    Note(if (useSys) s.wrFrameSys else s.wrFrameRec)
                                }
                            }

                            // 来源：当前用的哪份 + 「从其他盘选取」（和释放映像页同一款卡片）
                            BlockEnter(3) {
                                GroupTitle(s.wrSecSrc)
                                GroupCard {
                                    Item(
                                        s.wrSrcCur,
                                        when {
                                            srcFile != null -> srcFile!!.substringAfterLast('/')
                                            sysWim -> s.wrUseSys
                                            else -> s.wrNoSource
                                        },
                                        titleColor = if (!hasSource) MiuixTheme.colorScheme.error else null,
                                    )
                                    LinkItem(
                                        s.depWinrePick,
                                        when {
                                            probeBusy -> s.depWinreChecking
                                            probeHit != null -> buildString {
                                                append(srcFile.orEmpty())
                                                if (probeHit!!.sizeBytes > 0L) {
                                                    append(" · ")
                                                    append(fmtBytes(probeHit!!.sizeBytes))
                                                }
                                            }
                                            srcFile != null -> s.depWinreScope
                                            sysWim -> s.wrUseSys
                                            else -> s.depWinreScope
                                        },
                                    ) { picker.launch(arrayOf("*/*")) }
                                }
                            }

                            // 写入到哪里 + cmd 开关
                            BlockEnter(4) {
                                GroupTitle(s.wrSecWrite)
                                if (uefi && rec != null) {
                                    // GPT 有恢复分区：二选一（默认恢复分区）
                                    ChoiceCard(
                                        options = listOf(
                                            Choice(s.wrToRec, s.wrTargetRec(recIndex + 1, fmtBytes(rec.bytes))),
                                            Choice(s.wrToSys, s.wrTargetSys(winIndex + 1)),
                                        ),
                                        selected = if (toSys) 1 else 0,
                                        onSelect = { toSys = it == 1 },
                                    )
                                } else {
                                    GroupCard {
                                        Item(
                                            s.wrTarget,
                                            if (useSys) s.wrTargetSys(winIndex + 1)
                                            else s.wrTargetRec(recIndex + 1, fmtBytes(rec?.bytes ?: 0L)),
                                        )
                                    }
                                    if (!uefi) Note(s.wrMbrOnlySys)
                                }
                                GroupCard {
                                    Item(
                                        s.wrScript,
                                        if (!script) s.depRecScriptHint
                                        else s.depScriptWhere("Windows/System32/Recovery"),
                                        end = { Switch(checked = script, onCheckedChange = { script = it }) },
                                    )
                                }
                            }

                            BlockEnter(5) {
                                Note(s.wrBody)
                                Action(
                                    text = if (running) s.wrWriting else s.wrWrite,
                                    enabled = !running && !loading && !probeBusy && hasSource && targetLba > 0L,
                                ) {
                                    val img = image
                                    if (img != null) scope.launch {
                                        running = true
                                        val r = writeNow(img, useSys, targetLba, script)
                                        running = false
                                        if (r.ok) {
                                            // 统一：写盘成功**不自动跳页**，留在本页显示结果，
                                            // 由用户点返回（返回 = 上一级，不是首页）
                                            onToast(s.wrOk)
                                            result = s.wrOk
                                            mode = null
                                            expanded = false
                                            tick += 1
                                        } else {
                                            result = s.wrFail + "：" + toolErrorText(s, r)
                                            onToast(result!!)
                                            tick += 1
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}