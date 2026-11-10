package org.icdqcow2.ui.pages

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.TempDirs
import org.icdqcow2.core.DiskLimits
import org.icdqcow2.core.ToolCaps
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import org.icdqcow2.ui.Strings
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 建盘表单的全部状态。
 *
 * 主页面和「分区设置」二级页各持一份：进二级页时拷贝一份草稿，点「完成」才写回，
 * 直接返回（或退出创建磁盘页）就丢掉——所以每次重开分区设置都是"上次确认过的值"或默认值。
 */
class DiskDraft(defaultName: String) {

    var name by mutableStateOf(defaultName)
    var sizeGb by mutableStateOf("64")

    /** 磁盘容器格式：qcow2（默认）/ raw / vmdk。VHD 建盘不支持，不提供选项。 */
    var format by mutableStateOf("qcow2")

    /** 0 = GPT / 1 = MBR / 2 = 空盘 / 3 = 应用分区表（表来自文件） */
    var table by mutableIntStateOf(0)

    /** 「应用分区表」选中的文件正文（`part.apply --in=` 用的就是它，保证看到的=用上的）。 */
    var ptRaw by mutableStateOf<String?>(null)
    var ptName by mutableStateOf<String?>(null)

    /** 文件里的表类型（`GPT` / `MBR`）、分区数、最小容量（GiB）。 */
    var ptTable by mutableStateOf<String?>(null)
    var ptParts by mutableIntStateOf(0)
    var ptNeedGb by mutableIntStateOf(0)

    /** 0 = Windows 布局 / 1 = 单分区 / 2 = 无分区 */
    var layout by mutableIntStateOf(0)

    var esp by mutableStateOf(DiskLimits.ESP_DEFAULT_MB.toString())
    var msr by mutableStateOf(DiskLimits.MSR_MB.toString())
    var rec by mutableStateOf("0")
    var win by mutableStateOf("0")
    var sysMb by mutableStateOf(DiskLimits.SYS_DEFAULT_MB.toString())

    /** 额外分区（MB），按顺序追加在必要分区之后。 */
    val extras = mutableStateListOf<String>()

    /**
     * MBR：允不允许创建**扩展分区**（默认关）。
     *
     * 关 = 只建主分区（系统保留 + Windows + 2 个额外，正好 4 个主表项）；
     * 开 = 「添加分区」时可以选择建主分区还是建扩展分区，扩展分区里再放逻辑卷。
     */
    var mbrLogical by mutableStateOf(false)

    /** 已经建了扩展分区（一个盘只能有一个）。 */
    var extended by mutableStateOf(false)

    /** 扩展分区里的逻辑卷（MB），按顺序追加。 */
    val logicals = mutableStateListOf<String>()

    /**
     * 建完盘就把分区格式化（默认开）。
     *
     * 口径（2026-10-26 定稿）：ESP → FAT32 卷标 `ESP`；Windows 系统分区 → NTFS 卷标
     * `Windows OS`；Windows 数据分区 / 额外分区 / 逻辑卷 → NTFS 不写卷标；
     * MSR 与扩展容器不碰。
     */
    var formatAfter by mutableStateOf(true)

    fun snapshot(): DiskDraft = DiskDraft(name).also { it.assignFrom(this) }

    fun assignFrom(o: DiskDraft) {
        name = o.name
        sizeGb = o.sizeGb
        table = o.table
        layout = o.layout
        esp = o.esp
        msr = o.msr
        rec = o.rec
        win = o.win
        sysMb = o.sysMb
        mbrLogical = o.mbrLogical
        extended = o.extended
        formatAfter = o.formatAfter
        ptRaw = o.ptRaw
        ptName = o.ptName
        ptTable = o.ptTable
        ptParts = o.ptParts
        ptNeedGb = o.ptNeedGb
        extras.clear()
        extras.addAll(o.extras)
        logicals.clear()
        logicals.addAll(o.logicals)
    }

    private fun mb(s: String) = s.toIntOrNull() ?: 0

    /**
     * 没有"分区计划"的两种：空盘、应用分区表。
     *
     * 这两种都不走 `img.create` 的布局参数 —— 空盘什么都不写；
     * 应用分区表先建 `table=none`，再把文件里的表 `part.apply` 上去。
     */
    val noParts: Boolean get() = table == BLANK || table == APPLY

    val tableKey: String get() = when (table) { 0 -> "gpt"; 1 -> "mbr"; else -> "none" }
    val layoutKey: String get() = when (layout) { 0 -> "windows"; 1 -> "single"; else -> "empty" }
    val diskMb: Int get() = mb(sizeGb) * 1024

    /**
     * 分区表本身要占掉的量（MB）。
     *
     * GPT 首部 1 MiB 对齐 + 尾部备份头/表；不给它留空间就会出现
     * "必要分区刚好等于容量 → PART-NOSPACE"。所以所有上限都按 [usableMb] 算。
     */
    val overheadMb: Int get() = (if (table == 0) 3 else 1) + extras.size + logicals.size

    /** 真正能分给分区的总量（扣掉分区表开销）。 */
    val usableMb: Int get() = (diskMb - overheadMb).coerceAtLeast(0)

    /**
     * 这个布局最少要多少 GB —— Windows 布局**不按 1 GB 算**。
     *
     * "能装下 Windows"的经验容量（ESP + MSR + 系统盘 + 余量），整取 20 GB；
     *   **和分区下限无关**：分区下限已经是 128 MB，但 1 GB 的盘做 Windows 布局没意义。
     * 单分区 / 空盘没这个包袱，还是 1 GB 起。
     */
    val minGb: Int
        get() = when {
            table == APPLY -> maxOf(ptNeedGb, DiskLimits.DISK_MIN_GB)
            noParts || layout != 0 -> DiskLimits.DISK_MIN_GB
            else -> maxOf(DiskLimits.WIN_LAYOUT_RECOMMEND_GB, DiskLimits.DISK_MIN_GB)
        }

    val windowsFillsRest: Boolean get() = table == 0 && layout == 0 && mb(win) == 0

    /** Windows 是否吃满剩余（GPT / MBR 都是 win=0 时吃满）。 */
    val winAutoFills: Boolean get() = table != 2 && layout == 0 && mb(win) == 0
    val canAddParts: Boolean get() = table != 2 && layout == 0

    /** 除 Windows 之外的固定占用（MB）。 */
    fun fixedUsed(): Int = when {
        noParts || layout != 0 -> 0
        table == 0 -> mb(esp) + mb(msr) + mb(rec) + extrasMb().sum()
        else -> mb(sysMb) + mb(win) + extrasMb().sum() + logicalsMb().sum()
    }

    /**
     * 除 `self` 之外已经占掉的量（MB）。
     * `self` 取值：`esp` / `msr` / `rec` / `win` / `sys` / `extra:<i>`。
     */
    fun allocatedExcept(self: String): Int {
        if (noParts || layout != 0) return 0
        var v = 0
        if (table == 0) {
            if (self != "esp") v += mb(esp)
            if (self != "msr") v += DiskLimits.MSR_MB
            if (self != "rec") v += mb(rec)
            if (self != "win") v += mb(win)
        } else if (self != "sys") {
            v += mb(sysMb)
        }
        extras.forEachIndexed { i, e -> if (self != "extra:$i") v += mb(e) }
        logicals.forEachIndexed { i, e -> if (self != "logical:$i") v += mb(e) }
        return v
    }

    /**
     * `self` 这一项最大能给多少（MB）——所有输入卡片的"最大"都走这里，
     * 保证和底部"未分配"永远一致。
     *
     * Windows 吃满剩余时，给别的分区留出 Windows 的下限；Windows 自己是显式值时
     * 不再额外扣一次（否则会白扣一次下限，出现"最大"比真实可用小很多/对不上的情况）。
     */
    fun maxMbFor(self: String): Int {
        val reserve = if (winAutoFills && self != "win") DiskLimits.WIN_MIN_MB else 0
        return (usableMb - allocatedExcept(self) - reserve).coerceAtLeast(0)
    }

    fun extrasMb(): List<Int> = extras.map(::mb)

    fun logicalsMb(): List<Int> = logicals.map(::mb)

    fun necessaryMb(): Int = when {
        noParts || layout != 0 -> 0
        table == 0 -> mb(esp) + mb(msr) + mb(rec) + mb(win)
        else -> mb(sysMb) + mb(win)
    }

    fun allocatedMb(): Int = necessaryMb() + extrasMb().sum() + logicalsMb().sum()

    /** 还需要多少空间（> 盘容量就放不下）。"吃满剩余"的 Windows 按最小值算。 */
    fun needMb(): Int = when {
        noParts || layout != 0 -> 0
        table == 0 -> fixedUsed() + if (windowsFillsRest) DiskLimits.WIN_MIN_MB else maxOf(mb(win), DiskLimits.WIN_MIN_MB)
        // MBR：系统保留 + Windows + 额外；Windows 吃满剩余时按下限预留，口径和 maxMbFor 一致
        else -> fixedUsed() +
            if (winAutoFills) DiskLimits.WIN_MIN_MB else maxOf(mb(win), DiskLimits.WIN_MIN_MB)
    }

    /** 主分区槽还剩几个（MBR 4 个主表项；扩展容器也占一个）。 */
    val mbrFreeSlots: Int
        get() = (4 - 2 - extras.size - (if (extended) 1 else 0)).coerceAtLeast(0)

    fun freeMb(): Int = (usableMb - needMb()).coerceAtLeast(0)

    /** Windows 实际能拿到多少：吃满剩余时 = 可用量 − 其它固定占用。 */
    fun winSizeMb(): Int = when {
        noParts || layout != 0 -> 0
        winAutoFills -> (usableMb - fixedUsed()).coerceAtLeast(0)
        else -> mb(win)
    }

    // ── 取值范围（口径见 DiskLimits） ──
    val espValid: Boolean get() = mb(esp) in DiskLimits.ESP_MIN_MB..DiskLimits.ESP_MAX_MB
    val recValid: Boolean get() = mb(rec) == 0 || mb(rec) in DiskLimits.REC_MIN_MB..DiskLimits.REC_MAX_MB
    val winValid: Boolean get() = mb(win) == 0 || mb(win) >= DiskLimits.WIN_MIN_MB
    val sysValid: Boolean get() = mb(sysMb) in DiskLimits.SYS_MIN_MB..DiskLimits.SYS_MAX_MB
    val extrasValid: Boolean get() = extrasMb().all { it >= DiskLimits.NTFS_MIN_MB }
    val fits: Boolean get() = needMb() <= usableMb

    val planValid: Boolean
        get() = when {
            noParts || layout != 0 -> true
            table == 0 -> espValid && recValid && winValid && extrasValid && fits
            else -> sysValid && winValid && extrasValid && fits
        }

    /** 建完之后分区表里应该有几块（核对额外分区到底写没写进去）。 */
    fun expectedParts(): Int {
        val needed = when {
            noParts || layout == 2 -> 0
            layout == 1 -> 1
            table == 0 -> 3 + if (mb(rec) > 0) 1 else 0
            else -> 2 + (if (extended) 1 else 0) + logicals.size   // MBR：保留 + Windows (+容器+逻辑卷)
        }
        return needed + extras.size
    }

    /**
     * 自动重分配：空间不够时按"额外分区 → 恢复 → ESP → Windows(压到最小)"的顺序压缩，
     * `protect` 指定的那一项最后才动（用户刚改的那一项要尽量保住）。
     *
     * 返回改动清单（给用户看的提示）和"压到最小也放不下"标记。
     */
    fun rebalance(s: Strings, protect: String = ""): Rebalance {
        val changes = mutableListOf<String>()
        if (noParts || layout != 0) return Rebalance(changes, false)

        // Windows 显式值小于下限：先抬到下限
        if (!windowsFillsRest && mb(win) in 1 until DiskLimits.WIN_MIN_MB) {
            win = DiskLimits.WIN_MIN_MB.toString()
            changes += "${s.winLabel} → ${fmtMb(DiskLimits.WIN_MIN_MB)}"
        }

        var guard = 0
        while (needMb() > usableMb && guard++ < 64) {
            if (!shrinkOne(s, protect, changes) && !shrinkOne(s, "", changes)) {
                return Rebalance(changes, true)
            }
        }
        return Rebalance(changes, needMb() > usableMb)
    }

    private fun shrinkOne(s: Strings, protect: String, changes: MutableList<String>): Boolean {
        val over = (needMb() - usableMb).coerceAtLeast(1)
        // 1. 额外分区：先压缩，压到 16 MB 就整块删掉
        for (i in extras.indices.reversed()) {
            if (protect == "extra:$i") continue
            val v = mb(extras[i])
            if (v > DiskLimits.NTFS_MIN_MB) {
                val nv = (v - over).coerceAtLeast(DiskLimits.NTFS_MIN_MB)
                extras[i] = nv.toString()
                changes += "${s.extraTitle(i + 1)} → ${fmtMb(nv)}"
                return true
            }
            extras.removeAt(i)
            changes += s.notCreateTarget(s.extraTitle(i + 1))
            return true
        }
        // 2. 恢复分区：压到 512 MB，再压不动就取消
        if (table == 0 && protect != "rec" && mb(rec) > 0) {
            val nv = if (mb(rec) - over >= DiskLimits.REC_MIN_MB) mb(rec) - over else 0
            rec = nv.toString()
            changes += if (nv == 0) s.notCreateTarget(s.recLabel) else "${s.recLabel} → ${fmtMb(nv)}"
            return true
        }
        // 3. ESP：压到 128 MB
        if (table == 0 && protect != "esp" && mb(esp) > DiskLimits.ESP_MIN_MB) {
            val nv = (mb(esp) - over).coerceAtLeast(DiskLimits.ESP_MIN_MB)
            esp = nv.toString()
            changes += "${s.espLabel} → ${fmtMb(nv)}"
            return true
        }
        // 4. 显式 Windows：压到下限（2026-10-26 起 = 128 MB，和 NTFS 一样）
        if (table == 0 && protect != "win" && !windowsFillsRest && mb(win) > DiskLimits.WIN_MIN_MB) {
            win = DiskLimits.WIN_MIN_MB.toString()
            changes += "${s.winLabel} → ${fmtMb(DiskLimits.WIN_MIN_MB)}"
            return true
        }
        // 5. MBR 系统分区：压到 128 MB
        if (table == 1 && protect != "sys" && mb(sysMb) > DiskLimits.SYS_MIN_MB) {
            val nv = (mb(sysMb) - over).coerceAtLeast(DiskLimits.SYS_MIN_MB)
            sysMb = nv.toString()
            changes += "${s.sysLabel} → ${fmtMb(nv)}"
            return true
        }
        return false
    }

    /**
     * MBR 主分区上限：系统保留 + Windows 占 2 个，剩 2 个；
     * 如果建了扩展分区，它还要占一个槽 → 主分区额外只剩 1 个。
     */
    val extraLimit: Int
        get() = when {
            table != MBR || layout != 0 -> Int.MAX_VALUE
            extended -> 1
            else -> 2
        }

    /** 往扩展分区里加一块逻辑卷（默认 4 GB，同上：不动 Windows 的地盘）。 */
    fun addLogical(): Boolean {
        if (freeMb() < DiskLimits.NTFS_MIN_MB) return false
        val take = if (freeMb() > 8192) 4096 else (freeMb() / 2).coerceAtLeast(DiskLimits.NTFS_MIN_MB)
        logicals += take.toString()
        return true
    }

    /**
     * 加一块额外分区：占剩余空间的一半或 4 GB。
     *
     * ★ **不抢 Windows 的地盘**：`freeMb()` 已经扣掉 Windows 的下限（现在 128 MB），
     *   这里只拿"额外分区能拿的那部分"，所以加一块额外分区**不会**把 Windows 压到下限。
     *   （"NTFS 默认吃满剩余"说的是 Windows 那块 —— 它默认 0 = 吃满；额外分区若也吃满，
     *    加第二块就等于把 Windows 压成 128 MB，那不是默认该发生的事。）
     */
    fun addExtra(): Boolean {
        if (!canAddParts) return false
        if (extras.size >= extraLimit) return false
        val free = freeMb()
        if (free < DiskLimits.NTFS_MIN_MB) return false
        val take = if (free > 8192) 4096 else (free / 2).coerceAtLeast(DiskLimits.NTFS_MIN_MB)
        extras += take.toString()
        return true
    }

    fun planSummary(s: Strings): String {
        if (noParts) return s.planBlank
        val base = when {
            layout == 2 -> s.planEmpty
            layout == 1 -> s.planSingle
            table == 0 -> s.planWindows(mb(esp), mb(msr), mb(win))
            else -> if (mb(win) > 0) s.planMbrWin(fmtMb(mb(sysMb)), fmtMb(mb(win))) else s.planMbr(mb(sysMb))
        }
        return if (extras.isEmpty()) base else s.planWithExtras(base, extras.size)
    }
}

data class Rebalance(val changes: List<String>, val tooSmall: Boolean)

/** MB → 好读的字符串（GB 保留一位小数）。 */
internal fun fmtMb(mb: Int): String =
    if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"

private const val GPT = 0
private const val MBR = 1
private const val BLANK = 2

/** 磁盘类型第 4 项：**按文件里的分区表建盘**（先建空盘再 `part.apply`）。 */
private const val APPLY = 3

/**
 * 创建磁盘。
 *
 * 结构：保存位置 → 镜像名 / 容量 → 磁盘类型 → 分区设置（二级页）→ 创建。
 */
@Composable
fun CreateDiskPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** 全流程装机带进来时显示（第 N 步 / 共 M 步），单独打开为 null。 */
    subtitle: String? = null,
    /** 向导模式：藏掉「磁盘类型」（空盘/仅写分区表），固定 GPT + Windows 标准。 */
    guided: Boolean = false,
    /** 只给向导用：把底部主按钮文案换成「下一步」；单独打开时保持「创建」。 */
    primaryLabel: String? = null,
    /** 只给向导用：建好之后把路径交出去（向导拿它设 current 并进下一步）。 */
    onCreated: ((String) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val draft = remember { DiskDraft(s.defaultImageName) }
    var planOpen by remember { mutableStateOf(false) }
    var planDraft by remember { mutableStateOf<DiskDraft?>(null) }
    /** 退场动画期间还要用它渲染分区设置页——closePlan() 会把 planDraft 置空，
     *  不留这一份的话旧内容立刻变成空 Box，页面是"瞬间消失"而不是滑出去。 */
    var lastPlanDraft by remember { mutableStateOf<DiskDraft?>(null) }
    if (planDraft != null) lastPlanDraft = planDraft

    fun closePlan() {
        planOpen = false
        planDraft = null
    }

    BackHandler(enabled = planOpen) { closePlan() }

    // ⛔ 定死（用户要求）：分区设置是**二级页**，和主页进二级页完全一致——
    //   子页从右往左滑入、返回往右滑出；母页左退 22% + 缩到 96% + 14% 遮罩。
    val planDepth by animateFloatAsState(
        if (planOpen) 1f else 0f,
        tween(230, easing = LinearOutSlowInEasing),
        label = "planDepth",
    )

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -planDepth * size.width * 0.22f
                    val k = 1f - planDepth * 0.04f
                    scaleX = k
                    scaleY = k
                },
        ) {
            CreateDiskForm(
                guided = guided,
                subtitle = subtitle,
                primaryLabel = primaryLabel,
                onCreated = onCreated,
                draft = draft,
                onBack = onBack,
                onToast = onToast,
                onOpenPlan = {
                    // 每次进分区设置都从"上次点完成的值"拷一份草稿，不改原值
                    planDraft = draft.snapshot()
                    planOpen = true
                },
        )
        }

        // 母页变暗（和主页一样压 14%）
        if (planDepth > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.14f * planDepth)))
        }

        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = planOpen,
            transitionSpec = {
                // 进入：从右往左；返回：往右滑出（不能是 ExitTransition.None，否则返回没动画）
                (
                    slideInHorizontally(tween(230, easing = LinearOutSlowInEasing)) { it } +
                        fadeIn(tween(120))
                    ).togetherWith(
                    slideOutHorizontally(tween(230, easing = LinearOutSlowInEasing)) { it },
                )
            },
            label = "plan",
        ) { open ->
            val working = planDraft ?: lastPlanDraft
            if (!open || working == null) {
                Box(Modifier.fillMaxSize())
            } else {
                PartPlanPage(
                    guided = guided,
                    draft = working,
                    onToast = onToast,
                    onDone = {
                        draft.assignFrom(working)   // 只有点「完成」才写回
                        closePlan()
                    },
                    onBack = { closePlan() },       // 直接返回 = 丢弃这次编辑
                )
            }
        }
    }
}

@Composable
private fun CreateDiskForm(
    guided: Boolean = false,
    subtitle: String? = null,
    primaryLabel: String? = null,
    onCreated: ((String) -> Unit)? = null,
    draft: DiskDraft,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onOpenPlan: () -> Unit,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val mode by ImageRepo.mode.collectAsState()
    val customDir by ImageRepo.customDir.collectAsState()
    val granted = rememberAllFilesGranted()

    var busy by remember { mutableStateOf(false) }
    // 建盘成功后的"按角色格式化"阶段：按钮文字换成「正在格式化…」，别让用户以为卡住
    var fmtNow by remember { mutableStateOf(false) }
    // 点过一次创建之后，「还没填完」这类提示才出现（进页面就飘红字太吵）
    var attempted by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // SAF 只用来拿"真实路径"，写盘仍走 File（需要「所有文件访问」）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (customDir == null) ImageRepo.setMode(ImageRepo.SaveMode.Default)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val path = StorageAccess.treeToPath(uri)
        when {
            path == null -> onToast(s.toastPickNoPath)
            !StorageAccess.canWrite(File(path)) -> onToast(s.toastPickUnwritable)
            else -> {
                ImageRepo.setCustomDir(path)
                onToast(s.toastPicked(path))
            }
        }
    }

    /**
     * 「应用分区表」选文件：读成文本 → 用工具端的格式校验（`ImageRepo.parseTableFile`）→
     * 成功才把磁盘类型切到 APPLY；失败保持原来的选择并说明原因。
     */
    val ptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val f = text?.let { ImageRepo.parseTableFile(it) }
            if (f == null) {
                onToast(s.ptBad)
                return@launch
            }
            draft.ptRaw = f.raw
            draft.ptName = StorageAccess.queryName(ctx, uri) ?: uri.lastPathSegment ?: "parttable.json"
            draft.ptTable = f.table
            draft.ptParts = f.parts
            draft.ptNeedGb = f.needGb
            draft.table = APPLY
            // 容量下限 = 文件里最后一块的结束位置；当前值不够就自动抬上去
            val gb = draft.sizeGb.toIntOrNull() ?: 0
            if (gb < f.needGb) {
                draft.sizeGb = f.needGb.toString()
                onToast(s.ptNeed(f.needGb))
            } else {
                onToast(s.ptInfo(f.table, f.parts))
            }
        }
    }

    fun pickDir() {
        if (!granted) {
            StorageAccess.requestAllFiles(ctx)
            onToast(s.toastNeedPermission)
        } else {
            picker.launch(null)
        }
    }

    val defaultPath = remember { ImageRepo.defaultDir().absolutePath }
    val custom = customDir?.takeIf { File(it).isDirectory }
    val targetDir = if (mode == ImageRepo.SaveMode.Custom && custom != null) File(custom) else ImageRepo.defaultDir()
    val dirWritable: Boolean = remember(mode, custom) {
        if (mode == ImageRepo.SaveMode.Custom) custom != null && StorageAccess.canWrite(File(custom))
        else StorageAccess.canWrite(ImageRepo.defaultDir())
    }

    // 默认名已被占用 → 自动顺延：`名称 （1）` / `名称 （2）`…（中文全角括号，其它语言半角）。
    // 只在进页面/换目录时算一次：用户之后手改的名字不会被抢回去。
    LaunchedEffect(targetDir) {
        val base = draft.name
        if (base.isBlank() || !File(targetDir, "$base.${draft.format}").exists()) return@LaunchedEffect
        val suffix = 4 // " （1）" / " (1)" 都是 4 个字符
        val keep = (DiskLimits.NAME_MAX - suffix - 1).coerceAtLeast(1)
        var n = 1
        while (n <= 99) {
            val candidate = s.nameDup(base.take(keep), n)
            if (!File(targetDir, "$candidate.${draft.format}").exists()) {
                draft.name = candidate
                return@LaunchedEffect
            }
            n++
        }
    }

    val sizeGb = draft.sizeGb.toIntOrNull() ?: 0
    val exists = draft.name.isNotBlank() && File(targetDir, "${draft.name}.${draft.format}").exists()
    val ok = !busy && draft.name.isNotBlank() &&
        sizeGb in draft.minGb..DiskLimits.DISK_MAX_GB &&
        !exists && draft.planValid && dirWritable &&
        // 应用分区表：必须真选到一个能解析的文件
        (draft.table != APPLY || draft.ptRaw != null)

    /**
     * 应用分区表：**两步**（REPLY-14 §3 的口径，没给 `img.create` 加参数）
     *
     * 1. `img.create --table=none`：只建 qcow2 头，一张表都不写；
     * 2. `part.apply --in=<缓存里的表文件>`：LBA / GUID / 名字全按文件，只写表区。
     *
     * 第 2 步失败就把刚建的文件**删掉**（别在用户目录里留一个半成品空盘）。
     */
    suspend fun createFromTable() {
        val raw = draft.ptRaw
        if (raw == null) {
            busy = false
            onToast(s.ptPickHint)
            return
        }
        val out = ImageRepo.create(
            name = draft.name,
            sizeGb = sizeGb,
            table = "none",
            layout = "empty",
            dir = targetDir,
            format = draft.format,
        )
        if (!out.result.ok) {
            busy = false
            err = toolErrorText(s, out.result)
            onToast(err!!)
            return
        }
        val created = File(targetDir, "${draft.name}.${draft.format}").absolutePath
        // 表文件写到缓存目录再交给工具端：绕开 SAF 的 uri（工具只认路径）
        val tmp = File(TempDirs.stagingDir(ctx), "pt-import.json")
        val wrote = withContext(Dispatchers.IO) { runCatching { tmp.writeText(raw) }.isSuccess }
        val ap = if (!wrote) null else ImageRepo.applyTable(tmp.absolutePath, path = created, force = true)
        if (ap == null || !ap.ok) {
            runCatching { File(created).delete() }
            ImageRepo.setCurrent(null)
            busy = false
            err = if (ap == null) s.ptBad else toolErrorText(s, ap)
            onToast(err!!)
            return
        }
        // 表落好了 → 按角色格式化（和普通建盘完全同一套：ESP→FAT32 卷标 ESP、
        // 第一块数据分区→NTFS 卷标 Windows OS、恢复→Recovery、MSR/容器跳过）
        if (draft.formatAfter) {
            fmtNow = true
            val parts = ImageRepo.parseParts(ImageRepo.parts(created))?.second.orEmpty()
            var bad: String? = null
            for (t in ImageRepo.formatPlan("windows", parts)) {
                val f = ImageRepo.format(lba = t.lba, fs = t.fs, label = t.label, path = created)
                if (!f.ok) {
                    bad = toolErrorText(s, f)
                    break
                }
            }
            fmtNow = false
            if (bad != null) {
                busy = false
                err = bad
                onToast(bad)
                onBack()
                return
            }
        }
        busy = false
        onToast(s.toastApplied(draft.name))
        if (onCreated != null) onCreated(created) else onBack()
    }

    SmallTitlePage(title = s.createTitle, onBack = onBack, subtitle = subtitle) {
        GroupTitle(s.secLocation)
        ChoiceCard(
            options = listOf(
                Choice(s.saveDefault, if (mode == ImageRepo.SaveMode.Default) defaultPath else null),
                Choice(s.saveCustom, if (mode == ImageRepo.SaveMode.Custom) custom ?: s.noFolder else null),
            ),
            selected = if (mode == ImageRepo.SaveMode.Custom) 1 else 0,
        ) { i ->
            if (i == 0) {
                ImageRepo.setMode(ImageRepo.SaveMode.Default)
            } else {
                ImageRepo.setMode(ImageRepo.SaveMode.Custom)
                pickDir()
            }
        }

        GroupTitle(s.secName)
        // 文件名 + 右侧容器格式（样式同容量框的 GB 单位，点击切换）
        val fmtItems = remember {
            listOf(
                Choice("qcow2", s.fmtQcow2Hint),
                Choice("raw", s.fmtRawHint),
                Choice("vmdk", s.fmtVmdkHint),
                // VHD 建盘：工具端 2026-11-09 已支持（HANDOFF-vhd-create-and-format-fields），
                // 此前按 REPLY-59 §5 标灰的限制已解除。
                Choice("vhd", s.fmtVhdHint),
            )
        }
        var fmtAsk by remember { mutableStateOf(false) }
        // ★ 弹窗必须和输入框装在同一个 Box 里：
        //   CardDropdown 用 PopupPositionProvider 定位，锚点是它在 Compose 树里的**父节点**。
        //   直接挂在页面 Column 下，锚点就变成整列 —— 菜单会飞到屏幕别处。
        //   设置页（SettingsScreen 的历史条数）也是 Box { 行; CardDropdown }，此处照抄。
        Box {
            TextField(
                value = draft.name,
                onValueChange = {
                    // 允许空格与括号：重名顺延会生成 `名称 （1）`，编辑时不能把它吃掉
                    draft.name = it.filter { c -> c.isLetterOrDigit() || c in "-_ ()（）" }
                        .take(DiskLimits.NAME_MAX)
                },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    // 与 GB/MB 完全一致的胶囊：圆角高亮 + ⌃⌄，点按有轻震（打开那次）
                    UnitPill("." + draft.format) { fmtAsk = !fmtAsk }
                },
            )
            CardDropdown(
                show = fmtAsk,
                items = fmtItems.map { it.title },
                selectedIndex = fmtItems.indexOfFirst { it.title == draft.format }.coerceAtLeast(0),
                onDismiss = { fmtAsk = false },
                onSelected = { i ->
                    draft.format = fmtItems[i].title
                    fmtAsk = false
                },
            )
        }
        Note(s.nameHint)

        GroupTitle(s.secCapacity)
        NumberField(value = draft.sizeGb, onValueChange = { draft.sizeGb = it }, unit = "GB", maxDigits = 4)
        if (sizeGb < draft.minGb) {
            Note(s.capacityMinShort(draft.minGb), warn = true)
        } else {
            // 下限跟着布局走（Windows 布局 20 GB 起），不再固定写"1–1024 GB"
            Note(s.capacityRange(draft.minGb, DiskLimits.DISK_MAX_GB))
        }

        // 磁盘类型卡：向导里也只藏「空盘」这一条，GPT / MBR / 导入分区表 都还在
        GroupTitle(s.secType)
        val kinds = buildList {
            add(0 to Choice(s.typeGpt, s.typeGptSummary))
            add(1 to Choice(s.typeMbr, s.typeMbrSummary))
            if (!guided) add(BLANK to Choice(s.typeBlank, s.typeBlankSummary))
            add(APPLY to Choice(s.typeApply, s.typeApplySummary))
        }
        ChoiceCard(
            options = kinds.map { it.second },
            selected = kinds.indexOfFirst { it.first == draft.table }.coerceAtLeast(0),
        ) { i ->
            // 「应用分区表」不直接切状态：先选文件，解析成功才真的切过去
            val kind = kinds[i].first
            if (kind == APPLY) ptPicker.launch(arrayOf("*/*")) else {
                draft.table = kind
                if (kind == BLANK) draft.extras.clear()
            }
        }

        // 应用分区表：文件信息 / 最少容量（容量下限跟着文件走，选完自动抬）
        if (draft.table == APPLY) {
            GroupCard {
                LinkItem(
                    s.ptRow,
                    draft.ptName ?: s.ptPickHint,
                    onClick = { ptPicker.launch(arrayOf("*/*")) },
                )
                if (draft.ptRaw != null) {
                    Item(s.ptxTable, s.ptInfo(draft.ptTable ?: "?", draft.ptParts))
                    Item(s.ptxNeed, s.ptNeed(draft.ptNeedGb))
                }
                // 和普通建盘同一个开关：分区表落好之后按角色格式化（ESP→FAT32、数据区→NTFS…）
                Item(
                    s.fmtAfterTitle,
                    end = {
                        Switch(
                            checked = draft.formatAfter,
                            onCheckedChange = { draft.formatAfter = it },
                        )
                    },
                )
            }
            Note(s.ptNote)
        }

        // 空盘 / 应用分区表都没有"分区计划"：整个「分区」分组不显示
        if (draft.table != BLANK && draft.table != APPLY) {
            GroupTitle(s.secPlan)
            GroupCard {
                LinkItem(title = s.planRow, summary = draft.planSummary(s), onClick = onOpenPlan)
            }
        }

        // 阻塞原因：底部红字常显（"冲突"类实时、"还没填"类点过创建后出现），
        // 点灰色按钮时同一条原因再从顶部弹一次——两个地方说的是同一句话。
        val reason = when {
            draft.table == APPLY && draft.ptRaw == null -> s.ptPickHint
            draft.name.isBlank() -> s.errNameEmpty
            sizeGb !in draft.minGb..DiskLimits.DISK_MAX_GB ->
                s.errSize("${draft.minGb}", "${DiskLimits.DISK_MAX_GB}")
            exists -> s.noteNameExists
            !draft.fits -> s.notePlanOver
            !draft.planValid -> s.outOfRange
            !dirWritable -> s.noteDirBad
            err != null -> s.noteFailed(err!!)
            else -> null
        }
        // 进页面时只显示"冲突"，不显示"还没填"
        val liveConflict = when {
            exists -> s.noteNameExists
            !draft.fits -> s.notePlanOver
            !draft.planValid -> s.outOfRange
            !dirWritable -> s.noteDirBad
            err != null -> s.noteFailed(err!!)
            else -> null
        }
        val noteText = if (attempted) reason else liveConflict
        if (noteText != null) Note(noteText, warn = true)

        Action(
            text = if (fmtNow) s.formatting else if (busy) s.creating else (primaryLabel ?: s.create),
            enabled = !busy,
            muted = !ok,
        ) {
            // 灰着也能点：点了把原因从顶部弹一次（底部红字同时也出现）
            attempted = true
            if (!ok) {
                reason?.let(onToast)
                return@Action
            }
            busy = true
            err = null
            val extras = draft.extrasMb()
            // MBR + 逻辑分区：`mbr-fill=auto` 一次建完（3 主 + 容器 + 逻辑卷）；
            // 老 so 不认 auto 时再退回"建完盘再追加容器 + 逻辑卷"两步走（REQ-08）
            val needExtended = draft.table == MBR && draft.layout == 0 && draft.extended
            val logicalsMb = draft.logicalsMb()
            val mbrFill = if (needExtended && ToolCaps.MBR_FILL) "auto" else null
            val extra = buildMap {
                when {
                    draft.table == GPT && draft.layout == 0 -> {
                        put("esp-mb", draft.esp)
                        put("msr-mb", draft.msr)
                        put("rec-mb", draft.rec)
                        // "吃满剩余" + 有额外分区时必须给出显式大小，否则额外分区没地方放
                        val winExplicit = when {
                            !draft.windowsFillsRest -> draft.win
                            extras.isNotEmpty() -> (draft.usableMb - draft.fixedUsed()).coerceAtLeast(0).toString()
                            else -> null
                        }
                        if (winExplicit != null) put("sys-mb", winExplicit)
                    }
                    draft.table == MBR && draft.layout == 0 -> {
                        put("sys-mb", draft.sysMb)
                        // 0 = 吃满剩余，不传（旧 so 也照常建盘）
                        val w = draft.win.toIntOrNull() ?: 0
                        if (w > 0) put("win-mb", draft.win)
                    }
                }
            }
            val wantParts = draft.expectedParts()

            scope.launch {
                if (draft.table == APPLY) {
                    createFromTable()
                    return@launch
                }
                // ★ GPT + 恢复分区 + 额外分区：**位置由 App 排**（ESP·MSR·Windows·Recovery·数据…），
                //   不能交给 `img.create --layout=windows`（它把恢复分区排到最后一块数据分区后面）。
                val recMb = draft.rec.toIntOrNull() ?: 0
                val espMb = draft.esp.toIntOrNull() ?: 0
                val appLayout = draft.table == GPT && draft.layout == 0 &&
                    recMb > 0 && extras.isNotEmpty()
                val out = if (appLayout) {
                    val winMb = if (draft.windowsFillsRest) {
                        draft.maxMbFor("win")
                    } else {
                        draft.win.toIntOrNull() ?: 0
                    }
                    val list = buildList {
                        add(ImageRepo.LayoutPart("efi", espMb, "ESP"))
                        if (DiskLimits.MSR_MB > 0) {
                            add(ImageRepo.LayoutPart("msr", DiskLimits.MSR_MB, "MSR"))
                        }
                        // GPT 分区名：`part.add --label` 限 11 字符（"Windows OS" 正好 10）
                        add(ImageRepo.LayoutPart("data", winMb, ImageRepo.LABEL_WIN))
                        add(ImageRepo.LayoutPart("recovery", recMb, "Recovery"))
                        extras.forEachIndexed { i, m ->
                            add(ImageRepo.LayoutPart("data", m, "Data${i + 1}"))
                        }
                    }
                    ImageRepo.createWithLayout(
                        name = draft.name,
                        sizeGb = sizeGb,
                        parts = list,
                        dir = targetDir,
                        format = draft.format,
                    )
                } else {
                    ImageRepo.create(
                        name = draft.name,
                        sizeGb = sizeGb,
                        table = draft.tableKey,
                        layout = draft.layoutKey,
                        dir = targetDir,
                        extra = extra,
                        extrasMb = extras,
                        mbrFill = mbrFill,
                        format = draft.format,
                    )
                }
                val r = out.result
                if (!r.ok) {
                    busy = false
                    err = toolErrorText(s, r)
                    onToast(err!!)
                    return@launch
                }
                // auto 没被认（老 so）或者根本没传 → 才需要补建容器 + 逻辑卷
                if (needExtended && (mbrFill == null || out.mbrFillDropped)) {
                    // 盘已经建好了；扩展容器/逻辑卷建不成要如实报，不能让用户以为全成了
                    val fail = ImageRepo.appendLogical(logicalsMb)
                    if (fail != null) {
                        busy = false
                        err = toolErrorText(s, fail)
                        onToast(err!!)
                        onBack()
                        return@launch
                    }
                }
                // 建完盘就按角色格式化（用**真实分区表**决定格式化谁，而不是草稿里的计划）
                val created = File(targetDir, "${draft.name}.${draft.format}").absolutePath
                val createdParts = ImageRepo.parseParts(ImageRepo.parts(created))?.second.orEmpty()
                if (draft.formatAfter) {
                    fmtNow = true
                    var bad: String? = null
                    for (t in ImageRepo.formatPlan(draft.layoutKey, createdParts)) {
                        val f = ImageRepo.format(lba = t.lba, fs = t.fs, label = t.label, path = created)
                        if (!f.ok) {
                            bad = s.purposeFormatFailed(toolErrorText(s, f))
                            break
                        }
                    }
                    fmtNow = false
                    if (bad != null) {
                        // 盘已经建好了，只是没格式化完：如实报，别让用户以为白建
                        busy = false
                        err = bad
                        onToast(bad)
                        onBack()
                        return@launch
                    }
                }
                busy = false
                val warn = when {
                    extras.isEmpty() -> null
                    out.extrasDropped -> s.toastExtraMissing
                    createdParts.size < wantParts -> s.toastExtraMissing
                    else -> null
                }
                onToast(
                    if (warn == null) {
                        // ★ 用工具返回的**实际**容量，不是 size-gb × 1024³：
                        //   VHD 会向上补齐到整柱面（工具端 HANDOFF-vhd-create-and-format-fields §1）。
                        val bytes = out.result.data?.optLong("size_bytes", 0L) ?: 0L
                        if (bytes > 0) s.toastCreatedSize(draft.name, fmtBytes(bytes))
                        else s.toastCreated(draft.name, sizeGb)
                    } else s.toastCreatedWarn(draft.name, warn),
                )
                // 向导里：建好就直接进下一步，不要走 onBack（那会触发"是否退出"）
                if (onCreated != null) onCreated(created) else onBack()
            }
        }
    }
}

/** 分区设置页里正在编辑的那一项。 */
private data class EditTarget(
    val protect: String,
    val title: String,
    val hint: String,
    val initial: Int,
    val min: Int,
    val max: Int,
    val allowZero: Boolean,
    val defaultUnit: SizeUnit = SizeUnit.MB,
    val quick: List<Pair<String, Int>>,
    /** 没动过输入框时落的值（Windows「剩余空间」用它保住 0，不写死一个满盘数）。 */
    val untouched: Int = initial,
    val apply: (Int) -> Unit,
    val delete: (() -> Unit)? = null,
)

/**
 * 分区设置（建盘页的二级页）。
 *
 * 尺寸不再用内联输入框：点一行 → 弹一张输入卡片（`Kit.NumberDialog`），
 * 卡片里带范围、快捷值和取消/确定；确定后放不下会自动压缩其它分区并给出提示。
 *
 * 本页改动的是 [draft] 的**副本**：点「完成」才写回建盘页，直接返回即丢弃。
 */
@Composable
fun PartPlanPage(
    draft: DiskDraft,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** 全流程装机里 = true：藏掉「布局」卡片，布局固定 Windows 标准。 */
    guided: Boolean = false,
) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    // 进页面先按当前容量归位一次（用户在上一页把容量改小过）
    LaunchedEffect(Unit) {
        toastFor(draft.rebalance(s), s)?.let(onToast)
    }

    SmallTitlePage(title = s.planTitle, onBack = onBack) {
        if (draft.table == BLANK) {
            EmptyCard(icon = MiuixIcons.Layers, title = s.blankTitle, desc = s.blankDesc)
        } else {
            PlanRows(draft = draft, onEdit = { editing = it }, onToast = onToast, guided = guided)
        }
        // 建完盘要不要顺便格式化（空盘没分区，不显示）。
        // 副标题只说"按类型自动来"，别去罗列每个角色的文件系统/卷标（列全太长，列一半误导）。
        if (draft.table != BLANK && draft.layout != 2) {
            GroupTitle(s.secFormat)
            GroupCard {
                Item(
                    s.fmtAfterTitle,
                    s.fmtAfterHint,
                    end = {
                        Switch(
                            checked = draft.formatAfter,
                            onCheckedChange = { draft.formatAfter = it },
                        )
                    },
                )
            }
        }
        Action(s.done) { onDone() }
    }

    editing?.let { target ->
        NumberDialog(
            title = target.title,
            hint = target.hint,
            initial = target.initial,
            min = target.min,
            max = target.max,
            allowZero = target.allowZero,
            defaultUnit = target.defaultUnit,
            untouchedValue = target.untouched,
            cancelText = s.cancel,
            confirmText = s.confirm,
            quick = target.quick,
            deleteText = if (target.delete != null) s.deletePart else null,
            onDelete = target.delete?.let {
                {
                    editing = null
                    it()
                    onToast(s.adjusted(target.title + " " + s.notCreated))
                }
            },
            onDismiss = { editing = null },
            onConfirm = { value, clamped ->
                editing = null
                target.apply(value)
                val r = draft.rebalance(s, protect = target.protect)
                if (r.tooSmall) {
                    onToast(s.capacityTooSmall)
                } else {
                    val parts = mutableListOf<String>()
                    if (clamped) parts += "${target.title} ${fmtMb(value)}"
                    if (r.changes.isNotEmpty()) parts += r.changes
                    if (parts.isNotEmpty()) onToast(s.adjusted(parts.joinToString(s.listSep)))
                }
            },
            onInvalid = { onToast(s.dialogInvalid(target.min, target.max)) },
        )
    }
}

/**
 * 「磁盘布局」长条：按 MB 比例把整盘切成几段画出来，下面一行图例。
 * 让人不用读数字就知道 ESP/Windows/恢复 各占多少。
 */
@Composable
private fun PlanBar(draft: DiskDraft) {
    val s = LocalStrings.current
    val colors = MiuixTheme.colorScheme
    val total = ((draft.sizeGb.toIntOrNull() ?: 0) * 1024).coerceAtLeast(1)
    fun mb(v: String) = v.toIntOrNull() ?: 0

    data class Seg(val label: String, val mb: Int, val color: Color)

    val segs = ArrayList<Seg>()
    when (draft.layout) {
        1 -> segs += Seg(s.layoutSingle, total, colors.primary)
        2 -> segs += Seg(s.layoutEmpty, total, colors.surfaceContainerHigh)
        else -> {
            if (draft.table == MBR) {
                val sr = mb(draft.sysMb).coerceAtLeast(0)
                segs += Seg(s.msrLabel, sr, colors.onSurfaceVariantSummary.copy(alpha = 0.55f))
            } else {
                segs += Seg(s.espLabel, mb(draft.esp), colors.primary.copy(alpha = 0.55f))
                segs += Seg(s.msrLabel, DiskLimits.MSR_MB, colors.onSurfaceVariantSummary.copy(alpha = 0.55f))
            }
            val rec = mb(draft.rec)
            val extras = draft.extras.map { mb(it) }
            val used = segs.sumOf { it.mb } + rec + extras.sum()
            val win = (total - used).coerceAtLeast(0)
            segs += Seg(s.layoutWindows, win, colors.primary)
            if (rec > 0) segs += Seg(s.recLabel, rec, colors.error.copy(alpha = 0.55f))
            extras.forEachIndexed { i, m ->
                if (m > 0) segs += Seg(s.extraTitle(i + 1), m, colors.primary.copy(alpha = 0.3f))
            }
        }
    }

    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        // 按比例切，但每段保底 14dp：MSR 只有 16 MB，纯按比例在 1200px 的屏上是 0.2px，等于看不见。
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gapPx = with(density) { 2.dp.toPx() }
            val minPx = with(density) { 14.dp.toPx() }
            val usable = (constraints.maxWidth - gapPx * (segs.size - 1).coerceAtLeast(0)).coerceAtLeast(1f)
            val widths = barWidths(segs.map { it.mb.toFloat().coerceAtLeast(1f) }, usable, minPx)

            Row(
                modifier = Modifier.fillMaxWidth().height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                segs.forEachIndexed { i, seg ->
                    Box(
                        Modifier
                            .width(with(density) { widths[i].toDp() })
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(seg.color),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            segs.joinToString("  ·  ") { "${it.label} ${fmtMb(it.mb)}" },
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/**
 * 长条分段宽度（px）：先按权重比例分；谁分到的比 [minPx] 还窄，就把它钉在 [minPx]，
 * 剩下的宽度再由其余段按比例重分，直到没有"太窄"的为止。这样 16 MB 的 MSR 也看得见，
 * 大块的相对关系也基本还在。
 */
private fun barWidths(weights: List<Float>, usablePx: Float, minPx: Float): List<Float> {
    val n = weights.size
    if (n == 0) return emptyList()
    if (minPx * n >= usablePx) return List(n) { usablePx / n }
    val out = FloatArray(n) { -1f }
    var remain = usablePx
    while (true) {
        val pool = out.indices.filter { out[it] < 0f }
        if (pool.isEmpty()) break
        val wsum = pool.sumOf { weights[it].toDouble() }.toFloat().coerceAtLeast(1e-6f)
        val small = pool.filter { remain * weights[it] / wsum < minPx }
        if (small.isEmpty()) {
            pool.forEach { out[it] = remain * weights[it] / wsum }
            break
        }
        small.forEach { out[it] = minPx; remain -= minPx }
        if (remain <= 0f) {
            pool.forEach { if (out[it] < 0f) out[it] = 0f }
            break
        }
    }
    return out.toList()
}

@Composable
private fun PlanRows(
    draft: DiskDraft,
    onEdit: (EditTarget) -> Unit,
    onToast: (String) -> Unit,
    guided: Boolean = false,
) {
    val s = LocalStrings.current
    // 「添加分区」先问：建主分区还是建扩展分区
    var addChoice by remember { mutableStateOf(false) }
    var extDelete by remember { mutableStateOf(false) }

    // 向导（装 Windows）里不给布局选项：装系统必须是 Windows 标准布局（ESP + MSR + Windows），
    // 选「单一分区」/「空布局」都装不起来，属于错误选项，直接不展示。
    if (!guided) {
    GroupTitle(s.secLayout)
    ChoiceCard(
        options = listOf(
            // 布局产物按分区表不同：GPT 是 ESP+MSR+Windows，MBR 是「系统保留(Bootmgr)+Windows」
            Choice(
                s.layoutWindows,
                if (draft.table == MBR) {
                    // 系统保留那块显示**当前值**（默认 128 MB，用户可在下面改）
                    s.layoutWindowsSummaryMbr(
                        fmtMb(draft.sysMb.toIntOrNull() ?: DiskLimits.SYS_DEFAULT_MB),
                    )
                } else {
                    s.layoutWindowsSummary
                },
            ),
            Choice(s.layoutSingle, if (draft.table == MBR) s.layoutSingleSummaryMbr else s.layoutSingleSummary),
            Choice(s.layoutEmpty, if (draft.table == MBR) s.layoutEmptySummaryMbr else s.layoutEmptySummary),
        ),
        selected = draft.layout,
    ) { i ->
        draft.layout = i
        if (i != 0) draft.extras.clear()
    }
    }


    // 「磁盘布局」长条：一眼看清 ESP / MSR / Windows / 恢复 / 附加区 怎么切
    // （全流程装机里上面那张「布局」卡片被藏掉，这条就是第一块内容）
    GroupTitle(s.planDiskLayout)
    PlanBar(draft)

    if (draft.layout == 0) {
        GroupTitle(s.secNecessary)
        GroupCard {
            if (draft.table == GPT) {
                LinkItem(s.espLabel, fmtMb(draft.esp.toIntOrNull() ?: 0)) { onEdit(espTarget(draft, s)) }
                Item(title = s.msrLabel, summary = fmtMb(DiskLimits.MSR_MB))
                LinkItem(
                    s.recLabel,
                    if ((draft.rec.toIntOrNull() ?: 0) == 0) s.notCreated else fmtMb(draft.rec.toIntOrNull() ?: 0),
                ) { onEdit(recTarget(draft, s)) }
                LinkItem(
                    s.winLabel,
                    if (draft.windowsFillsRest) s.winFillsRest(fmtMb(draft.winSizeMb()))
                    else fmtMb(draft.win.toIntOrNull() ?: 0),
                ) { onEdit(winTarget(draft, s)) }
            } else {
                LinkItem(s.sysLabel, fmtMb(draft.sysMb.toIntOrNull() ?: 0)) { onEdit(sysTarget(draft, s)) }
                // MBR 的 Windows：工具端支持 win-mb（REQ-05）后就能设大小，否则按吃满剩余只读显示
                if (DiskLimits.MBR_WIN_SUPPORTED) {
                    LinkItem(
                        s.winLabel,
                        if (draft.winAutoFills) s.winFillsRest(fmtMb(draft.winSizeMb()))
                        else fmtMb(draft.win.toIntOrNull() ?: 0),
                    ) { onEdit(winTarget(draft, s)) }
                } else {
                    Item(title = s.winLabel, summary = s.winFillsRest(fmtMb(draft.winSizeMb())))
                }
            }
        }
        // MBR 的 Windows 尺寸要靠工具端的 win-mb（REQ-05），没有就说明白，别让人以为坏了
        if (draft.table == MBR && !DiskLimits.MBR_WIN_SUPPORTED) Note(s.mbrWinUnsupported)
        if ((draft.sizeGb.toIntOrNull() ?: 0) < DiskLimits.WIN_LAYOUT_RECOMMEND_GB) {
            Note(s.noteWinDisk)
        }
    }

    if (draft.canAddParts) {
        GroupTitle(s.secExtra)
        GroupCard {
            // 这里只放**主分区**；逻辑卷在下面的「扩展分区」卡片里
            draft.extras.forEachIndexed { index, value ->
                val label = if (draft.extras.size > 1) s.extraTitle(index + 1) else s.extraLabel
                LinkItem(label, fmtMb(value.toIntOrNull() ?: 0)) {
                    onEdit(extraTarget(draft, s, index))
                }
            }
            // 扩展分区：在这里列一行当入口；点它 → 删除，下面那张大卡片跟着没（和额外分区一样）
            if (draft.extended) {
                Item(
                    title = s.planExtTitle,
                    summary = s.planExtRowSummary(draft.logicals.size),
                    onClick = { extDelete = true },
                )
            }
            Item(
                title = s.addPart,
                summary = when {
                    draft.freeMb() < DiskLimits.NTFS_MIN_MB -> s.addPartNone
                    draft.winAutoFills -> s.addPartFromWin(fmtMb(draft.freeMb()))
                    else -> s.addPartFree(fmtMb(draft.freeMb()))
                },
                enabled = draft.freeMb() >= DiskLimits.NTFS_MIN_MB,
                onClick = { addChoice = true },
            )
        }
        // MBR 最后一个主分区槽：提醒改用扩展分区，否则后面加不了
        if (draft.table == MBR && draft.mbrLogical && !draft.extended &&
            draft.extras.size == 1 && draft.freeMb() >= DiskLimits.NTFS_MIN_MB
        ) {
            Note(s.planSuggestExtended, warn = true)
        }
    }

    // 扩展分区（一个盘只能有一个）：放在「额外分区」卡片**下面**（列表里那行只是入口）
    if (draft.extended) {
        GroupTitle(s.planExtTitle)
        GroupCard {
            Item(title = s.planExtTitle, summary = s.planExtSummary)
            draft.logicals.forEachIndexed { index, value ->
                LinkItem(s.logicalTitle(index + 1), fmtMb(value.toIntOrNull() ?: 0)) {
                    onEdit(logicalTarget(draft, s, index))
                }
            }
            Item(
                title = s.planAddLogical,
                summary = if (draft.freeMb() >= DiskLimits.NTFS_MIN_MB) s.addPartFree(fmtMb(draft.freeMb()))
                else s.addPartNone,
                enabled = draft.freeMb() >= DiskLimits.NTFS_MIN_MB,
                onClick = {
                    if (draft.freeMb() < DiskLimits.NTFS_MIN_MB) {
                        onToast(s.addPartNone)
                    } else {
                        draft.addLogical()
                        onEdit(logicalTarget(draft, s, draft.logicals.lastIndex))
                    }
                },
            )
        }
    }

    // MBR：支不支持逻辑分区（开了就等于一定建扩展分区，分区都放进去）
    if (draft.table == MBR && draft.layout == 0) {
        GroupTitle(s.planLogical)
        GroupCard {
            Item(
                s.planLogicalSwitch,
                if (draft.mbrLogical) s.planLogicalOn else s.planLogicalOff,
                end = {
                    Switch(
                        checked = draft.mbrLogical,
                        onCheckedChange = { on ->
                            draft.mbrLogical = on
                            // 关掉就把扩展分区和里面的逻辑卷一起丢掉（主表项要还给主分区）
                            if (!on) {
                                draft.extended = false
                                draft.logicals.clear()
                                while (draft.extras.size > 2) draft.extras.removeAt(draft.extras.lastIndex)
                            }
                        },
                    )
                },
            )
        }
    }

    if (extDelete) {
        ConfirmDialog(
            title = s.planExtDeleteTitle,
            summary = s.planExtDeleteBody(draft.logicals.size),
            confirmText = s.partsDelete,
            cancelText = s.cancel,
            onDismiss = { extDelete = false },
            onConfirm = {
                extDelete = false
                draft.extended = false
                draft.logicals.clear()
            },
        )
    }

    if (addChoice) {
        val mbr = draft.table == MBR && draft.layout == 0
        if (mbr && draft.mbrLogical) {
            ChoiceDialog(
                title = s.addPart,
                summary = s.addPartFree(fmtMb(draft.freeMb())),
                items = listOf(s.planAddPrimary, s.planAddExtended),
                cancelText = s.cancel,
                onDismiss = { addChoice = false },
                onSelect = { which ->
                    addChoice = false
                    when {
                        which == 0 -> {
                            if (!draft.addExtra()) {
                                onToast(s.partsSlotsFull)
                            } else {
                                toastFor(draft.rebalance(s, protect = "extra:${draft.extras.lastIndex}"), s)
                                    ?.let(onToast)
                            }
                        }
                        draft.extended -> onToast(s.planExtSummary)
                        draft.mbrFreeSlots < 1 -> onToast(s.partsSlotsFull)
                        else -> {
                            draft.extended = true
                            // 扩展容器占一个主表项 → 主分区额外最多只剩 1 个
                            while (draft.extras.size > 1) draft.extras.removeAt(draft.extras.lastIndex)
                        }
                    }
                },
            )
        } else {
            addChoice = false
            if (!draft.addExtra()) {
                onToast(s.addPartNone)
            } else {
                toastFor(draft.rebalance(s, protect = "extra:${draft.extras.lastIndex}"), s)?.let(onToast)
            }
        }
    }

    // 未分配 = 可用量 − 已分配（GPT 表/对齐那几 MiB 不算"未分配"，否则选"最大"也永远差几 MB）
    val free = (draft.usableMb - draft.allocatedMb()).coerceAtLeast(0)
    Note(
        when {
            draft.winAutoFills -> s.filledLine(fmtMb(draft.diskMb), fmtMb(draft.winSizeMb()))
            draft.layout == 1 -> s.allocLine(fmtMb(draft.usableMb), fmtMb(draft.diskMb)) + " · " + s.unallocAll
            draft.layout == 2 -> s.allocLine(fmtMb(0), fmtMb(draft.diskMb)) + " · " + s.freeLine(fmtMb(draft.diskMb))
            free <= 0 -> s.allocLine(fmtMb(draft.allocatedMb()), fmtMb(draft.diskMb)) + " · " + s.unallocAll
            else -> s.allocLine(fmtMb(draft.allocatedMb()), fmtMb(draft.diskMb)) + " · " + s.freeLine(fmtMb(free))
        },
    )
}

private fun toastFor(r: Rebalance, s: Strings): String? = when {
    r.tooSmall -> s.capacityTooSmall
    r.changes.isNotEmpty() -> s.adjusted(r.changes.joinToString(s.listSep))
    else -> null
}

private fun espTarget(draft: DiskDraft, s: Strings): EditTarget {
    val min = DiskLimits.ESP_MIN_MB
    val limit = draft.maxMbFor("esp").coerceAtLeast(min)
    val max = minOf(limit, DiskLimits.ESP_MAX_MB)
    return EditTarget(
        protect = "esp",
        title = s.espLabel,
        hint = s.espHint,
        initial = draft.esp.toIntOrNull() ?: min,
        min = min,
        max = maxOf(min, max),
        allowZero = false,
        quick = listOf(
            s.dialogMin to min,
            s.dialogDefault to DiskLimits.ESP_DEFAULT_MB.coerceIn(min, maxOf(min, max)),
            s.dialogMax to maxOf(min, max),
        ),
        apply = { draft.esp = it.toString() },
    )
}

private fun recTarget(draft: DiskDraft, s: Strings): EditTarget {
    val min = DiskLimits.REC_MIN_MB
    val max = DiskLimits.REC_MAX_MB
    return EditTarget(
        protect = "rec",
        title = s.recLabel,
        hint = s.recHint,
        initial = draft.rec.toIntOrNull() ?: 0,
        min = min,
        max = max,
        allowZero = true,
        defaultUnit = SizeUnit.GB,
        quick = listOf(
            s.dialogMin to min,
            s.dialogDefault to 0,
            s.dialogMax to max,
        ),
        apply = { draft.rec = it.toString() },
    )
}

private fun winTarget(draft: DiskDraft, s: Strings): EditTarget {
    // MBR 用工具端给的 mbr-win 下限（REPLY-05），GPT 用 windows 的下限
    val min = if (draft.table == MBR) DiskLimits.MBR_WIN_MIN_MB else DiskLimits.WIN_MIN_MB
    val max = draft.maxMbFor("win").coerceAtLeast(min).let {
        if (DiskLimits.MBR_WIN_MAX_MB > 0) minOf(it, DiskLimits.MBR_WIN_MAX_MB).coerceAtLeast(min) else it
    }
    return EditTarget(
        protect = "win",
        title = s.winLabel,
        hint = s.winHint,
        initial = if (draft.windowsFillsRest) max else (draft.win.toIntOrNull() ?: min),
        min = min,
        max = max,
        allowZero = true,
        defaultUnit = SizeUnit.GB,
        untouched = if (draft.windowsFillsRest) 0 else (draft.win.toIntOrNull() ?: min),
        quick = listOf(
            s.dialogMin to min,
            s.dialogDefault to 0,
            s.dialogMax to max,
        ),
        apply = { draft.win = it.toString() },
    )
}

private fun sysTarget(draft: DiskDraft, s: Strings): EditTarget {
    val min = DiskLimits.SYS_MIN_MB
    val max = minOf(draft.maxMbFor("sys").coerceAtLeast(min), DiskLimits.SYS_MAX_MB)
    return EditTarget(
        protect = "sys",
        title = s.sysLabel,
        hint = s.sysHint,
        initial = draft.sysMb.toIntOrNull() ?: min,
        min = min,
        max = max,
        allowZero = false,
        quick = listOf(
            s.dialogMin to min,
            s.dialogDefault to DiskLimits.SYS_DEFAULT_MB,
            s.dialogMax to max,
        ),
        apply = { draft.sysMb = it.toString() },
    )
}

private fun extraTarget(draft: DiskDraft, s: Strings, index: Int): EditTarget {
    val current = draft.extras.getOrNull(index)?.toIntOrNull() ?: DiskLimits.NTFS_MIN_MB
    val max = draft.maxMbFor("extra:$index").coerceAtLeast(DiskLimits.NTFS_MIN_MB)
    return EditTarget(
        protect = "extra:$index",
        title = s.extraTitle(index + 1),
        hint = s.extraHint,
        initial = current,
        min = DiskLimits.NTFS_MIN_MB,
        max = max,
        allowZero = false,
        defaultUnit = SizeUnit.GB,
        quick = listOf(
            s.dialogMin to DiskLimits.NTFS_MIN_MB,
            s.dialogDefault to 4096.coerceIn(DiskLimits.NTFS_MIN_MB, maxOf(DiskLimits.NTFS_MIN_MB, max)),
            s.dialogMax to max,
        ),
        apply = { if (index in draft.extras.indices) draft.extras[index] = it.toString() },
        delete = {
            if (index in draft.extras.indices) draft.extras.removeAt(index)
        },
    )
}

/** 扩展分区里的一块逻辑卷（不占主表项，上限 = 剩余全部）。 */
private fun logicalTarget(draft: DiskDraft, s: Strings, index: Int): EditTarget {
    val current = draft.logicals.getOrNull(index)?.toIntOrNull() ?: DiskLimits.NTFS_MIN_MB
    val max = draft.maxMbFor("logical:$index").coerceAtLeast(DiskLimits.NTFS_MIN_MB)
    return EditTarget(
        protect = "logical:$index",
        title = s.logicalTitle(index + 1),
        hint = s.partsLogicalOn,
        initial = current,
        min = DiskLimits.NTFS_MIN_MB,
        max = max,
        allowZero = false,
        defaultUnit = SizeUnit.GB,
        quick = listOf(
            s.dialogMin to DiskLimits.NTFS_MIN_MB,
            s.dialogDefault to 4096.coerceIn(DiskLimits.NTFS_MIN_MB, max),
            s.dialogMax to max,
        ),
        apply = { if (index in draft.logicals.indices) draft.logicals[index] = it.toString() },
        delete = { if (index in draft.logicals.indices) draft.logicals.removeAt(index) },
    )
}

/** 观察「所有文件访问」开关：用户去设置里打开再回来，状态要立刻变。 */
@Composable
internal fun rememberAllFilesGranted(): Boolean {
    val granted = remember { mutableStateOf(StorageAccess.hasAllFiles()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted.value = StorageAccess.hasAllFiles()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted.value
}