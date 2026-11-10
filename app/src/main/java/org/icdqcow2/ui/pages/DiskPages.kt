package org.icdqcow2.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import org.icdqcow2.core.StorageAccess
import java.io.File
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.icdqcow2.core.DiskLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.ToolCaps
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import org.icdqcow2.ui.Strings
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 创建磁盘（含"分区设置"二级页）在 CreateDisk.kt；本文件放分区管理 / 格式化 / 克隆 / 自检。

/** MBR 的 0x0F 扩展容器（里面装的才是逻辑分区）。 */
internal fun ImageRepo.Part.isContainer(): Boolean =
    typeGuid.uppercase() == "0X0F" || mbri == 0x0F

/** 横向长条里的一段：`part == null` = 未分配区。 */
internal data class Segment(
    val part: ImageRepo.Part?,
    val startLba: Long,
    val sectors: Long,
    val label: String,
    /** 已格式化（有文件系统）。空分区在长条上画成半透明。 */
    val formatted: Boolean = false,
    /** 这段是草稿里的新分区（还没落盘）：半透明 + 虚线描边 + `＋` 前缀。 */
    val pending: Boolean = false,
    /** 草稿下标（点 pending 的段 = 撤销这条新建）。 */
    val draftIndex: Int = -1,
    /** 这段有未保存的格式化（`→NTFS`）。 */
)

/* ───────────────────────── 草稿 ───────────────────────── */

/**
 * 草稿里的一条待保存操作。
 *
 * 为什么要有草稿：分区表是一串互相牵制的表项（MBR 只有 4 个主槽、扩展容器里才有逻辑卷、
 * EBR 还要排位置），一条一条立刻落盘的话，删错一步就得从头来，也没法"先看看会变成什么样"。
 * 所以新建 / 删除 / 格式化**只入队**：长条直接画"保存后长什么样"，
 * 按底部「保存修改」才真的动表；「恢复原布局」= 清空队列，一个字节都没写。
 */
private sealed interface DraftOp {

    /**
     * 新建：`type` 直接给 `part.add --type`；`formatFs` 非空 = 建完顺手格式化；
     * `label` 只在那时有意义（跟着 `fs.format --label=` 一起落盘）。
     */
    data class Add(
        val mb: Int,
        val type: String,
        val formatFs: String?,
        val label: String? = null,
        val logical: Boolean = false,
        /** `part.add --where`：`head` / `tail` / `max-gap`；null = 只靠 [atLba] 指定那块。 */
        val where: String? = "max-gap",
        /** 点的是哪块未分配区（起始 LBA）：有值就要求落在这里，**不许跑到别处**。 */
        val atLba: Long = 0L,
    ) : DraftOp

    /**
     * GPT 的 ESP + Windows 保留分区（MSR）：**一条草稿、不可拆**。
     *
     * 位置固定在**磁盘最前方**（`where=head`）：ESP 在前，MSR 紧跟着；
     * 撤销/删除任何一半都是两条一起走（Windows 那边也是成对认的）。
     */
    data class Esp(
        val espMb: Int,
        val atLba: Long = 0L,
        /** ESP 的卷标；null = 用默认的 `ESP`（保存时跟着 `fs.format --label=` 落盘）。 */
        val label: String? = null,
    ) : DraftOp

    /** 建扩展容器（MBR）：`mb == 0` = 吃满点中的那块（工具端行为）。 */
    data class Extended(
        val mb: Int = 0,
        val where: String? = "max-gap",
        val atLba: Long = 0L,
    ) : DraftOp

    /** 删除一块。容器要连里面的逻辑卷一起删，所以记下它是不是容器。 */
    data class Remove(
        val lba: Long,
        val container: Boolean = false,
        val logical: Boolean = false,
        /** 显示用编号（`#2` / `L1`）。 */
        val name: String = "",
        val bytes: Long = 0L,
    ) : DraftOp

    /** 格式化**已有**分区（新建的格式化跟着 [Add] 走）。卷标按角色走，`null` = 不写。 */
    data class Format(
        val lba: Long,
        val fs: String,
        val name: String = "",
        val label: String? = null,
    ) : DraftOp
}

/**
 * 卷标输入框的目标。
 *
 * `queueIndex >= 0` = 这块的**格式化还排在队列里没执行**：这时不跑 `ntfs.label`
 * （盘上还没有文件系统，跑了必失败），只把卷标写进那条待保存的操作 ——
 * 落盘时 `fs.format --label=` 一条指令一起带走，不多跑一次。
 */
private data class LabelJob(
    val part: ImageRepo.Part?,
    val bytes: Long,
    val fs: String,
    val initial: String,
    val queueIndex: Int = -1,
)

/**
 * 操作菜单里的一行：`run = null` = 这件事现在做不了（**灰着但能点**），
 * 点它只把 `why`（为什么做不了）弹成提示。
 */
private data class ActionRow(
    val label: String,
    val run: (() -> Unit)? = null,
    val why: String? = null,
) {
    val enabled: Boolean get() = run != null
}

/**
 * 「新建分区」第一步：选类型。
 *
 * 顺序是**先类型、后大小**（大小输入框和建盘页同一套：MB/GB 单位 + 最小/默认/最大）。
 * 有些类型不用问大小：[fixedMb] 固定（MSR 16 MB）、[extended] 直接吃满未分配区。
 */
private data class TypeOpt(
    val label: String,
    val hint: String,
    val type: String,
    /** 建完格式化成的文件系统；null = 不格式化。 */
    val fs: String? = null,
    val fixedMb: Int? = null,
    val logical: Boolean = false,
    val extended: Boolean = false,
    val minMb: Int = DiskLimits.NTFS_MIN_MB,
    val maxMb: Int = 1 shl 22,
    val defaultMb: Int = 4096,
    /** true = 输入框默认 0（吃满剩余）；MBR 的第二块（Windows）就是这种。 */
    val fillDefault: Boolean = false,
    val unit: SizeUnit = SizeUnit.GB,
    /** 落位：点中的那块未分配区起点 / `part.add --where`。 */
    val atLba: Long = 0L,
    val where: String? = "max-gap",
    /** ESP + 16 MB 保留分区一起建（一条草稿）。 */
    val espPair: Boolean = false,
)

/**
 * 镜像里**最后一个能放分区的 LBA**（工具端 `img_parttable_usable_last()` 的同一口径）。
 *
 * GPT：盘末 1 扇区是备份头、紧挨着 32 扇区是备份分区表（128×128 B / 512）→ 末尾 34 扇区不能用。
 * 少了这一步，长条上"盘尾那块未分配"会比真实可用多十几 KB，选"最大"就正好差一点 →
 * 保存时 `PART-NOSPACE`。MBR 没有备份表，减 1 就行。
 */
internal fun usableLast(diskSectors: Long, isMbr: Boolean): Long = when {
    diskSectors <= 0 -> 0
    isMbr -> diskSectors - 1
    else -> (diskSectors - 34).coerceAtLeast(0)
}

/** 1 MiB 对齐（工具端 `part.add` 也是这个口径）。 */
private const val ALIGN_SECTORS = 2048L

private fun alignUpLba(v: Long): Long = (v + ALIGN_SECTORS - 1) / ALIGN_SECTORS * ALIGN_SECTORS

/**
 * 草稿里的新分区也要画进长条：照磁盘类型造一个"像真的"的 [ImageRepo.Part]。
 *
 * 类型号必须写对——长条的颜色和角色名都是按 `typeGuid` / `mbri` 认的。
 */
private fun draftPart(lba: Long, sectors: Long, type: String, isMbr: Boolean): ImageRepo.Part {
    val gpt = when (type) {
        "efi" -> "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
        "msr" -> "E3C9E316-0B5C-4DB8-817D-F92DF00215AE"
        "recovery" -> "DE94BBA4-06D1-4D40-A16A-BFD50179D6AC"
        "extended" -> "0x0F"
        else -> "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
    }
    val mbr = when (type) {
        "efi" -> 0xEF
        "msr" -> 0x00
        "recovery" -> 0x27
        "extended" -> 0x0F
        else -> 0x07
    }
    return ImageRepo.Part(
        lba = lba,
        sectors = sectors,
        typeGuid = if (isMbr) "0x%02X".format(mbr) else gpt,
        active = false,
        logical = type == "logical",
        mbri = if (isMbr) mbr else -1,
    )
}

/** 未分配区间（含两端，LBA）。 */
private fun gapsOf(parts: List<ImageRepo.Part>, diskEnd: Long): List<LongRange> {
    val out = ArrayList<LongRange>()
    var cursor = 0L
    for (p in parts.sortedBy { it.lba }) {
        if (p.lba > cursor) out += cursor until p.lba
        cursor = maxOf(cursor, p.lba + p.sectors)
    }
    if (cursor < diskEnd) out += cursor until diskEnd
    return out
}

/**
 * 磁盘最前方那块**够放 ESP + MSR** 的未分配区（从 1 MiB 之后算起）。
 *
 * 为什么不直接取第一个 gap：GPT 开头那 1 MiB（LBA 0–2047）永远是"空隙"，
 * 真正的"最前方"是它后面那块；ESP 必须顶在这里，Windows 才认。
 */
private fun frontGapOf(parts: List<ImageRepo.Part>, diskEnd: Long): LongRange? {
    val g = gapsOf(parts, diskEnd).firstOrNull() ?: return null
    // 前面已经有分区 → 这不是"磁盘最前方"，ESP 不能放这儿
    if (g.first > ALIGN_SECTORS) return null
    val usable = g.last + 1 - maxOf(g.first, ALIGN_SECTORS)
    return if (usable >= (DiskLimits.ESP_MIN_MB + DiskLimits.MSR_MB) * ALIGN_SECTORS) g else null
}

/** 最大的一块未分配区（`part.add --where=max-gap` 选的就是它）。 */
private fun largestGap(parts: List<ImageRepo.Part>, diskEnd: Long): LongRange? =
    gapsOf(parts, diskEnd).maxByOrNull { it.last - it.first }

/**
 * 按落位要求挑一块未分配区：**点哪块就建哪块**。
 *
 * - `atLba > 0`：必须落在这块里（找不到就不建，绝不"跑到最大空闲区去"）；
 * - `head` / `tail`：最前 / 最后那块；
 * - `max-gap`（默认）：最大那块。
 */
private fun gapFor(
    parts: List<ImageRepo.Part>,
    diskEnd: Long,
    where: String?,
    atLba: Long,
): LongRange? {
    val gaps = gapsOf(parts, diskEnd)
    if (atLba > 0) return gaps.firstOrNull { atLba >= it.first && atLba <= it.last }
    return when (where) {
        "head" -> gaps.firstOrNull()
        "tail" -> gaps.lastOrNull()
        else -> gaps.maxByOrNull { it.last - it.first }
    }
}

/** 预演结果：保存后各分区的位置 + 「哪些是新加的」（lba → 草稿下标）。 */
private data class Preview(
    val parts: List<ImageRepo.Part>,
    val pending: Map<Long, Int>,
)

/**
 * 把草稿"预演"成保存后的分区表——只算位置，一个字节都不写。
 *
 * 摆放规则照工具端来（不然保存完长条会跳一下）：
 * - 普通分区 `where=max-gap`：放进**最大的未分配区**，1 MiB 对齐；
 * - 逻辑卷追加在扩展容器末尾（容器没有就先不放，等用户建容器）；
 * - 越界就按剩余空间截断，宁可画小也不画出盘外。
 */
private fun previewOf(pt: ImageRepo.PtInfo, draft: List<DraftOp>, isMbr: Boolean): Preview {
    val removed = draft.filterIsInstance<DraftOp.Remove>()
    val removedLba = removed.map { it.lba }.toSet()
    // 删容器 = 里面的逻辑卷一起没了
    val containerGone = removed.any { it.container }
    var kept = pt.parts.filter { p ->
        p.lba !in removedLba && !(containerGone && p.logical)
    }
    val pending = LinkedHashMap<Long, Int>()
    val diskEnd = pt.diskSectors.takeIf { it > 0 }
        ?: (pt.parts.maxOfOrNull { it.lba + it.sectors } ?: Long.MAX_VALUE)

    draft.forEachIndexed { i, op ->
        when (op) {
            is DraftOp.Extended -> {
                // 扩展容器：吃满**点中的那块**未分配区
                val gap = gapFor(kept, diskEnd, op.where, op.atLba) ?: return@forEachIndexed
                val start = alignUpLba(if (op.atLba > 0) op.atLba else gap.first)
                    .coerceAtLeast(ALIGN_SECTORS)
                if (start > gap.last) return@forEachIndexed
                val room = (gap.last + 1 - start).coerceAtLeast(0L)
                val sec = if (op.mb > 0) (op.mb * ALIGN_SECTORS).coerceAtMost(room) else room
                if (sec > 0) {
                    kept = kept + draftPart(start, sec, "extended", isMbr)
                    pending[start] = i
                }
            }

            is DraftOp.Esp -> {
                // ESP + MSR 只放磁盘最前方：ESP 顶到最前，MSR 紧跟着
                val gap = gapFor(kept, diskEnd, "head", op.atLba) ?: return@forEachIndexed
                val start = alignUpLba(if (op.atLba > 0) op.atLba else gap.first)
                    .coerceAtLeast(ALIGN_SECTORS)
                if (start > gap.last) return@forEachIndexed
                val room = (gap.last + 1 - start).coerceAtLeast(0L)
                val espSec = (op.espMb * ALIGN_SECTORS).coerceAtMost(room)
                if (espSec > 0) {
                    kept = kept + draftPart(start, espSec, "efi", isMbr)
                    pending[start] = i
                    val msrStart = alignUpLba(start + espSec)
                    val msrSec = (DiskLimits.MSR_MB * ALIGN_SECTORS)
                        .coerceAtMost((gap.last + 1 - msrStart).coerceAtLeast(0L))
                    if (msrSec > 0) {
                        kept = kept + draftPart(msrStart, msrSec, "msr", isMbr)
                        pending[msrStart] = i
                    }
                }
            }

            is DraftOp.Add -> if (op.logical) {
                val cont = kept.filter { it.typeGuid.uppercase() == "0X0F" || it.mbri == 0x0F }
                    .maxByOrNull { it.lba }
                if (cont != null) {
                    val base = kept
                        .filter { it.logical && it.lba >= cont.lba && it.lba < cont.lba + cont.sectors }
                        .maxOfOrNull { it.lba + it.sectors } ?: cont.lba
                    val start = alignUpLba(base)
                    val room = (cont.lba + cont.sectors - start).coerceAtLeast(0L)
                    val sec = (op.mb * ALIGN_SECTORS).coerceAtMost(room)
                    if (sec > 0) {
                        kept = kept + draftPart(start, sec, "logical", isMbr)
                        pending[start] = i
                    }
                }
            } else {
                val gap = gapFor(kept, diskEnd, op.where, op.atLba) ?: return@forEachIndexed
                // ★ 起点是"这块自己算好的 LBA"（保存时也传这个），不能每次都从空闲区开头摆：
                //   删掉前面那块待保存分区后，后面的应该**原地不动**、前面留出未分配
                val start = alignUpLba(if (op.atLba > 0) op.atLba else gap.first)
                    .coerceAtLeast(ALIGN_SECTORS)
                if (start > gap.last) return@forEachIndexed
                val room = (gap.last + 1 - start).coerceAtLeast(0L)
                val sec = (op.mb * ALIGN_SECTORS).coerceAtMost(room)
                if (sec > 0) {
                    kept = kept + draftPart(start, sec, op.type, isMbr)
                    pending[start] = i
                }
            }

            else -> {}
        }
    }
    return Preview(kept.sortedBy { it.lba }, pending)
}

internal fun buildSegments(
    pt: ImageRepo.PtInfo,
    s: Strings,
    scans: Map<Long, ImageRepo.Scan> = emptyMap(),
    pendingLba: Map<Long, Int> = emptyMap(),
    pendingFormats: Map<Long, String> = emptyMap(),
): List<Segment> {
    val sorted = pt.parts.sortedBy { it.lba }
    val total = pt.diskSectors.takeIf { it > 0 } ?: (sorted.maxOfOrNull { it.lba + it.sectors } ?: 0L)
    val primaries = sorted.filter { !it.logical && !it.isContainer() }
    val logicals = sorted.filter { it.logical }
    val out = ArrayList<Segment>(sorted.size + 2)
    var cursor = 0L
    for (p in sorted) {
        if (p.lba > cursor) {
            // 太小的间隙（对齐/表头留白）建不了分区，渲染出来也点不动 → 丢掉
            val gapMb = (p.lba - cursor) * 512 / (1024 * 1024)
            if (gapMb >= DiskLimits.FREE_SHOW_MIN_MB) {
                out += Segment(null, cursor, p.lba - cursor, s.partsFree)
            }
        }
        val label = when {
            p.isContainer() -> "EXT"
            p.logical -> "L${logicals.indexOfFirst { it.lba == p.lba } + 1}"
            else -> "#${primaries.indexOfFirst { it.lba == p.lba } + 1}"
        }
        val di = pendingLba[p.lba] ?: -1
        val fmt = pendingFormats[p.lba]
        out += Segment(
            part = p,
            startLba = p.lba,
            sectors = p.sectors,
            label = if (di >= 0) "＋$label" else label,
            formatted = scans[p.lba]?.hasFs == true || fmt != null,
            pending = di >= 0,
            draftIndex = di,
        )
        cursor = maxOf(cursor, p.lba + p.sectors)
    }
    if (cursor < total) {
        val tailMb = (total - cursor) * 512 / (1024 * 1024)
        if (tailMb >= DiskLimits.FREE_SHOW_MIN_MB) out += Segment(null, cursor, total - cursor, s.partsFree, false)
    }
    return out
}

/**
 * 磁盘布局长条。
 *
 * ★ 每段有**最小宽度**（30dp）：512 MB / 16 MB 这种小块也得能看见文字、能点到，
 *   不能只按比例分配（否则被挤成 1px 根本点不着）。
 * ★ 配色用柔和中频色，亮色/暗色主题下都能看清（不用主题里那些明度差太大的色）。
 * ★ 草稿里的新分区画成半透明 + 白色虚线描边：一眼能分清"已经是这样"和"保存后会这样"。
 */
@Composable
internal fun DiskBar(
    segments: List<Segment>,
    total: Long,
    /** 选中的那块（释放镜像页用来标"装到这里"）；null = 不高亮。 */
    selectedLba: Long? = null,
    onTap: (Segment) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 柔和配色（亮暗通吃）：主分区 柔蓝 / 逻辑卷 柔橙 / ESP 青绿 / 系统保留 石板蓝 / MSR 灰 / 恢复 柔紫
    val cPrimary = Color(0xFF5B8DEF)
    val cLogical = Color(0xFFF2A65A)
    val cEsp = Color(0xFF4DB6AC)
    val cSys = Color(0xFF8D9BB5)
    val cMsr = Color(0xFFA8B2C1)
    val cRec = Color(0xFFB39DDB)

    // 选中框：暗色用白框（和深底色对比强）；亮色用纯黑框（灰框在浅底上不够醒目）
    val selStroke = if (colors.surface.luminance() > 0.5f) Color.Black else Color.White

    val sum = segments.sumOf { it.sectors }.coerceAtLeast(1L)
    val gap = 2.dp
    val minW = 30.dp

    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp)) {
        val gaps = gap * (segments.size - 1).coerceAtLeast(0)
        val free = (maxWidth - gaps - minW * segments.size).coerceAtLeast(0.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            segments.forEach { seg ->
                val frac = seg.sectors.toFloat() / sum.toFloat()
                val w = minW + free * frac
                val isFree = seg.part == null
                val base = when {
                    isFree -> colors.surfaceContainerHigh
                    seg.part!!.isContainer() -> colors.onSurfaceVariantSummary.copy(alpha = 0.22f)
                    seg.part.logical -> cLogical
                    seg.part.typeGuid.startsWith("C12A7328", true) || seg.part.mbri == 0xEF -> cEsp
                    seg.part.mbri == 0x27 -> cSys
                    seg.part.typeGuid.startsWith("DE94BBA4", true) || seg.part.mbri == 0x12 -> cRec
                    seg.part.typeGuid.startsWith("E3C9E316", true) -> cMsr
                    else -> cPrimary
                }
                val color = when {
                    seg.pending -> base.copy(alpha = 0.55f)
                    !isFree && !seg.formatted -> base.copy(alpha = 0.45f)
                    else -> base
                }
                Box(
                    modifier = Modifier
                        .width(w)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(color)
                        .then(
                            if (selectedLba != null && seg.part != null && seg.part.lba == selectedLba) {
                                Modifier.drawBehind {
                                    drawRoundRect(
                                        color = selStroke,
                                        cornerRadius = CornerRadius(6.dp.toPx()),
                                        style = Stroke(width = 3.dp.toPx()),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (seg.pending) {
                                Modifier.drawBehind {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.9f),
                                        cornerRadius = CornerRadius(6.dp.toPx()),
                                        style = Stroke(
                                            width = 1.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onTap(seg) },
                    contentAlignment = Alignment.Center,
                ) {
                    // 未分配区不写字：留一块灰就行（写"未分配"在窄段里挤成两行，也吵）
                    if (!isFree && w >= 26.dp) {
                        Text(
                            seg.label,
                            style = MiuixTheme.textStyles.footnote2,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分区管理：`part.show` 读表，新建 / 删除 / 格式化**先入草稿**，底部「保存修改」才落盘。
 *
 * 界面四块（从上到下）：
 * - **磁盘布局长条**（像 Windows 磁盘管理）：颜色区分主分区 / 逻辑卷 / 扩展容器 / 未分配，
 *   草稿里的新块用虚线画在同一张图上 —— 点下去看到的就是"保存后会变成什么"；
 * - **待保存**：草稿队列（新增绿、删除红），点任意一条问一句再撤销，
 *   最后一行是「恢复原布局」（清空队列，镜像一个字节都没写过）；
 * - **分区列表**：卷标、文件系统、已用量（来自 `part.scan`），不显示 LBA；
 * - **底部「保存修改」**：一次按固定顺序把队列做完。
 *
 * 为什么整个页面是草稿制：一步一格地落盘时，"删掉 #2 再在空出来的地方建一个" 这种
 * 最常见的操作要分两个事务，中间任何一步失败都会留下半成品；草稿排好序一次做完，
 * 失败就把没做的那几条留在队列里，盘上不会出现"删了没建"。
 *
 * 新建分区只有一条路：**点长条上的未分配区** → 选类型 → 填大小。
 *
 * 镜像**默认不选**：选盘走通用 [PickImagePage]（含最近使用），选完 `img.info` 验一遍。
 *
 * MBR 的槽位是 4 个主表项，扩展容器**占一个**；容器里的逻辑卷不占主槽。
 */
@Composable
fun PartsPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** 自检页跳进来时带着镜像，直接落到"已选中"的状态（不再弹选择器）。 */
    initialImage: String? = null,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val colors = MiuixTheme.colorScheme
    // 「管理所有文件」：别的应用（虚拟机）建的镜像在它自己的属主下，
    // 没这个权限时 C 层 open() 直接失败 → IMG-OPEN-FAIL
    val granted = rememberAllFilesGranted()
    // 刚授予：系统还没给新进程挂载权，重启一次才真的能读别的 App 的文件
    var justGranted by remember { mutableStateOf(false) }
    var seenGranted by remember { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        if (!seenGranted && granted) justGranted = true
        seenGranted = granted
    }

    var image by remember { mutableStateOf<String?>(initialImage) }
    var loading by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf("—") }
    var parts by remember { mutableStateOf<List<ImageRepo.Part>>(emptyList()) }
    var diskSectors by remember { mutableStateOf(0L) }
    var scans by remember { mutableStateOf<Map<Long, ImageRepo.Scan>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    // 空盘（table=none）：part.show 会报 PART-READ-FAIL，但这是**正常状态**，不是错误
    var noTable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // ── 草稿：改动先排队，按「保存修改」才写入镜像 ──
    var draft by remember { mutableStateOf<List<DraftOp>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var saveAt by remember { mutableStateOf(0 to 0) }
    // 对话框
    var typeMenu by remember { mutableStateOf(false) }
    /** 有待保存的改动时点返回 → 先确认（用户 2026-11-11） */
    var askLeave by remember { mutableStateOf(false) }
    // 这一次新建**只能落在点中的那块**未分配区：起点 / 大小 / 落位
    var newAt by remember { mutableStateOf(0L) }
    var newRoom by remember { mutableStateOf(0) }
    var newWhere by remember { mutableStateOf<String?>("max-gap") }
    var editing by remember { mutableStateOf<TypeOpt?>(null) }
    var actionTarget by remember { mutableStateOf<ImageRepo.Part?>(null) }
    var deleting by remember { mutableStateOf<ImageRepo.Part?>(null) }
    var draftAction by remember { mutableStateOf<Int?>(null) }
    var draftResize by remember { mutableStateOf<Int?>(null) }
    var discardConfirm by remember { mutableStateOf(false) }
    var labelJob by remember { mutableStateOf<LabelJob?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        val path = image
        if (path == null && !ImageRepo.hasFd()) {
            loading = false
            parts = emptyList()
            scans = emptyMap()
            err = null
            noTable = false
            return
        }
        loading = true
        err = null
        noTable = false
        val r = ImageRepo.parts(path)
        val info = ImageRepo.parsePt(r)
        if (info == null) {
            parts = emptyList()
            scans = emptyMap()
            if (r.code == "PART-READ-FAIL") {
                noTable = true
                table = "—"
            } else {
                err = s.partsBadImage(toolErrorText(s, r)) + " · " + diag(path)
            }
        } else {
            table = info.table
            parts = info.parts
            diskSectors = info.diskSectors
        }
        loading = false

        // 卷标 / 文件系统 / 已用量：part.scan 要挂卷，逐个读、边读边刷新
        if (parts.isNotEmpty()) {
            val acc = LinkedHashMap<Long, ImageRepo.Scan>()
            for (p in parts) {
                if (p.isContainer()) continue
                ImageRepo.parseScan(ImageRepo.scan(p.lba, path))?.let { acc[p.lba] = it }
                scans = acc.toMap()
            }
        }
    }

    // 读盘是同步 JNI（img.info / part.show / stat）：必须离开主线程，
    // 否则选完盘那 1 秒里动画帧推不动（表现就是"块没有动画"）。
    LaunchedEffect(image, reload) { withContext(Dispatchers.IO) { load() } }

    // 分配边界：到"可用末尾"为止（详见 usableLast 的注释）
    val usableEnd = usableLast(diskSectors, table.contains("MBR", true) ||
        parts.any { it.logical } || parts.any { it.typeGuid.startsWith("0x", true) }) + 1

    val isMbr = table.contains("MBR", true) ||
        parts.any { it.logical } ||
        parts.any { it.typeGuid.startsWith("0x", true) }

    // 保存后的样子（只算位置）：长条和列表都按这个画
    val pre = remember(parts, draft, isMbr, table, diskSectors) {
        previewOf(ImageRepo.PtInfo(table, usableEnd, parts), draft, isMbr)
    }
    val preParts = pre.parts
    val prePrimaries = preParts.filter { !it.logical && !it.isContainer() }
    val preLogicals = preParts.filter { it.logical }
    val preContainer = preParts.firstOrNull { it.isContainer() }
    val slotsUsed = prePrimaries.size + if (preContainer != null) 1 else 0

    // 结构体检：GPT 要 ESP；MBR 要有系统保留 + 第二块是 Windows（NTFS）主分区。
    // ★ 2026-10-26：**不再看大小** —— "Windows 系统分区必须 >16 GB"这条校验关了，
    //   分区下限和普通 NTFS 一样是 128 MB（DiskLimits.WIN_MIN_MB）。
    val hasEsp = preParts.any { it.typeGuid.startsWith("C12A7328", true) || it.mbri == 0xEF }
    val hasBoot = preParts.any { it.mbri == 0x27 }
    /** 哪一块是 Windows 系统分区（第一块 NTFS/基本数据主分区）；-1 = 没有。 */
    val winPos = prePrimaries.indexOfFirst {
        it.typeGuid.startsWith("EBD0A0A2", true) || it.mbri == 0x07
    }
    /** MBR 的**顺序**规矩：第二块应该是它（第一块让给 0x27 系统保留）。 */
    val mbrWinBad = isMbr && prePrimaries.size >= 2 && winPos != 1

    // 还能建多大：普通分区看**最大未分配区**（part.add 放最大空洞），逻辑卷看容器里剩的
    val maxGapMb = ((((largestGap(preParts, usableEnd)?.let { it.last - it.first + 1 } ?: 0L) * 512) /
        (1024 * 1024)) - 2).toInt().coerceAtLeast(0)
    val containerFreeMb = preContainer?.let { c ->
        val used = preLogicals
            .filter { it.lba >= c.lba && it.lba < c.lba + c.sectors }
            .maxOfOrNull { it.lba + it.sectors } ?: c.lba
        ((((c.lba + c.sectors - used) * 512) / (1024 * 1024)) - 2).toInt().coerceAtLeast(0)
    } ?: 0

    /** 这种类型最小能给多少（调整大小时也要守住同一条线）。 */
    fun minMbFor(type: String): Int = when (type) {
        "efi" -> DiskLimits.ESP_MIN_MB
        "msr" -> DiskLimits.MSR_MB
        "recovery" -> if (isMbr) DiskLimits.SYS_MIN_MB else DiskLimits.REC_MIN_MB
        else -> DiskLimits.NTFS_MIN_MB
    }

    fun nameOf(p: ImageRepo.Part): String = when {
        p.isContainer() -> s.partsContainer
        p.logical -> "L" + (preLogicals.indexOfFirst { it.lba == p.lba } + 1)
        else -> "#" + (prePrimaries.indexOfFirst { it.lba == p.lba } + 1)
    }

    /**
     * 文件系统列的口径：**只写真检测到的**。
     *
     * `part.scan` 说没卷（level 0）就是「未分配」——ESP 也一样：没格式化的 ESP 写 FAT32
     * 是骗人（点了改卷标只会失败）。"将会格式化成什么"只写在草稿队列那条卡上，
     * 因为那条卡本身就是待执行的操作。
     */
    fun fsTextOf(part: ImageRepo.Part?, scan: ImageRepo.Scan?, planned: String? = null): String = when {
        planned != null -> planned.uppercase()
        part == null -> s.partsUnformatted
        // 16 MB 的 MSR 没有文件系统，但它在 Windows 里是有身份的（"保留"），不能叫未分配
        part.typeGuid.startsWith("E3C9E316", true) -> s.partsWinReserved
        scan?.hasFs == true -> scan.fs.uppercase()
        else -> s.partsUnformatted
    }

    /**
     * 这块新建的 NTFS 分区是不是"Windows 系统分区"（决定默认吃满 / 标签口径）。
     *
     * - MBR：第一块（系统保留）已存在 → 第二块就是它；
     * - GPT：盘上还没有任何数据分区（0x07 / Basic）→ 这块就是它。
     *
     * ★ 这就是"NTFS 默认吃满剩余"的落点：**Windows 那块才吃满**（恢复分区 512、
     *   MBR 系统保留 512、额外分区/逻辑卷 4 GB，都各有各的默认）。
     */
    val winRole = if (isMbr) prePrimaries.size == 1
    else preParts.none { it.typeGuid.startsWith("EBD0A0A2", true) || it.mbri == 0x07 }

    fun enqueue(op: DraftOp) {
        draft = draft + op
        onToast(s.draftQueued)
    }

    fun dropDraft(i: Int) {
        draft = draft.filterIndexed { k, _ -> k != i }
        onToast(s.draftDropped)
    }

    /**
     * 格式化一块分区：**不问文件系统、不再弹二次确认**。
     *
     * 口径按分区类型自动定（`ImageRepo.formatTargetOf`）：只有 **ESP 是 FAT32**，
     * 其余一律 NTFS；卷标跟着角色走（ESP / Windows OS / Recovery，数据分区不写）。
     * 点一下就进草稿队列，底部「保存修改」才真写盘 —— 落盘前随时能撤销。
     */
    fun doFormat(p: ImageRepo.Part) {
        val di = pre.pending[p.lba]
        if (di != null) {
            // 还没落盘的新分区：改那条 Add 的 formatFs（保存时跟着一起格式化）
            draft = draft.toMutableList().also { list ->
                val op = list[di]
                if (op is DraftOp.Add) {
                    list[di] = op.copy(formatFs = if (op.type == "efi") "fat32" else "ntfs")
                }
            }
            onToast(s.draftQueued)
            return
        }
        val t = ImageRepo.formatTargetOf(parts, p.lba) ?: return
        // 卷标：用户自己起过名字就保住，别格式化一次就被改成角色名
        val label = scans[p.lba]?.label ?: t.label
        // 同一块只留一条（重复点就是重排一次）
        draft = draft.filterNot { it is DraftOp.Format && it.lba == p.lba } +
            DraftOp.Format(t.lba, t.fs, nameOf(p), label)
        onToast(s.draftQueued)
    }

    /**
     * 「新建分区」第一步：选类型（先类型、后大小）。
     *
     * 传入的是**用户点中的那段未分配区**：之后的尺寸上限、落位全按它算，
     * 绝不"跑到最大空闲区去建"（[Segment.startLba] → `newAt`）。
     */
    fun openTypeMenu(seg: Segment) {
        if (isMbr && slotsUsed >= 4 && preContainer == null) {
            onToast(s.partsSlotsFull)
            return
        }
        // 起点至少 1 MiB（LBA 2048）之后 —— 从"真正能用的起点"算这块还剩多少：
        // 盘头那块是从 LBA 0 开始的，直接按 seg.sectors 算会多报 1 MiB，
        // 用户点"最大"就会撞 PART-NOSPACE。
        val startAt = maxOf(seg.startLba, ALIGN_SECTORS)
        val roomMb = ((seg.startLba + seg.sectors - startAt) * 512 / (1024 * 1024)).toInt()
            .coerceAtLeast(0)
        // 这块确实空着，但连 128 MB 都不到 → 直说这一块有多大（有容器时还能往容器里加）
        if (roomMb < DiskLimits.NTFS_MIN_MB && containerFreeMb < DiskLimits.NTFS_MIN_MB) {
            onToast(s.partsRegionSmall(fmtBytes(seg.sectors * 512)))
            return
        }
        val gaps = gapsOf(preParts, usableEnd)
        val head = gaps.firstOrNull()
        val tail = gaps.lastOrNull()
        val big = gaps.maxByOrNull { it.last - it.first }
        // LBA 0 是分区表头，起点至少从 1 MiB 之后算（工具端也是这么对齐的）
        newAt = startAt
        newRoom = roomMb
        // 起点始终有（用户点的那段），落位统一交给 `part.add --lba`（REQ-10）
        newWhere = when {
            head != null && head.first == seg.startLba -> "head"
            tail != null && tail.first == seg.startLba -> "tail"
            big != null && big.first == seg.startLba -> "max-gap"
            else -> null
        }
        typeMenu = true
    }

    /** 类型表：只列出「这张盘上还能建、且建了有用」的；尺寸上限 = **点中的那块**未分配区。 */
    fun typeOptions(): List<TypeOpt> {
        val avail = if (newRoom > 0) newRoom else maxGapMb
        val hasEsp = preParts.any { it.typeGuid.startsWith("C12A7328", true) || it.mbri == 0xEF }
        // ★ GPT 的 `mbri` 恒为 0（那不是 MBR 类型号），判 MSR 只能看 typeGuid
        val hasMsr = preParts.any { it.typeGuid.startsWith("E3C9E316", true) }
        val hasSys = preParts.any { it.mbri == 0x27 }
        val hasRec = preParts.any { it.typeGuid.startsWith("DE94BBA4", true) || it.mbri == 0x12 }
        val logicalFree = if (preContainer != null) containerFreeMb else 0
        // ESP + MSR 只能放磁盘最前方：先看最前面那块够不够
        val front = frontGapOf(preParts, usableEnd)
        val frontStart = front?.let { maxOf(it.first, ALIGN_SECTORS) } ?: 0L
        val frontMb = front?.let { ((it.last + 1 - frontStart) * 512 / (1024 * 1024)).toInt() } ?: 0
        val espMaxMb = (frontMb - DiskLimits.MSR_MB).coerceAtMost(DiskLimits.ESP_MAX_MB)
        return buildList {
            if (isMbr) {
                if (avail >= DiskLimits.NTFS_MIN_MB) add(
                    TypeOpt(
                        label = if (winRole) s.purposeWinPrimary else s.purposePrimary,
                        hint = if (winRole) s.winHint else s.partsNewHint,
                        type = "data",
                        fs = "ntfs",
                        minMb = DiskLimits.NTFS_MIN_MB,
                        maxMb = avail,
                        // Windows 那块默认吃满（0 = 吃满），普通数据分区默认 4 GB
                        defaultMb = if (winRole) 0 else minOf(avail, 4096).coerceAtLeast(DiskLimits.NTFS_MIN_MB),
                        fillDefault = winRole,
                        atLba = newAt,
                        where = newWhere,
                    ),
                )
                if (logicalFree >= DiskLimits.NTFS_MIN_MB) add(
                    TypeOpt(
                        label = s.purposeLogical,
                        hint = s.partsLogicalOn,
                        type = "logical",
                        fs = "ntfs",
                        logical = true,
                        maxMb = logicalFree.coerceAtLeast(DiskLimits.NTFS_MIN_MB),
                        defaultMb = minOf(logicalFree, 4096).coerceAtLeast(DiskLimits.NTFS_MIN_MB),
                    ),
                )
                if (preContainer == null && avail >= DiskLimits.NTFS_MIN_MB) {
                    add(
                        TypeOpt(
                            label = s.planAddExtended,
                            hint = s.planAddExtendedHint,
                            type = "extended",
                            extended = true,
                            atLba = newAt,
                            where = newWhere,
                        ),
                    )
                }
                if (!hasSys && avail >= DiskLimits.SYS_MIN_MB) add(
                    TypeOpt(
                        label = s.purposeSys,
                        hint = s.espHint,
                        type = "recovery",
                        fs = "ntfs",
                        minMb = DiskLimits.SYS_MIN_MB,
                        maxMb = minOf(DiskLimits.SYS_MAX_MB, avail).coerceAtLeast(DiskLimits.SYS_MIN_MB),
                        defaultMb = DiskLimits.SYS_DEFAULT_MB,
                        unit = SizeUnit.MB,
                        atLba = newAt,
                        where = newWhere,
                    ),
                )
            } else {
                // GPT：ESP + 保留分区是一对，只能放最前方；**只有点中前方那块**时才给选
                val onFront = front != null && newAt == frontStart
                if (!hasEsp && !hasMsr && onFront && espMaxMb >= DiskLimits.ESP_MIN_MB) add(
                    TypeOpt(
                        label = s.purposeEspPair,
                        hint = s.espPairHint,
                        type = "efi",
                        fs = "fat32",
                        espPair = true,
                        minMb = DiskLimits.ESP_MIN_MB,
                        maxMb = espMaxMb,
                        defaultMb = espMaxMb,
                        unit = SizeUnit.MB,
                        atLba = frontStart,
                        where = "head",
                    ),
                )
                if (avail >= DiskLimits.NTFS_MIN_MB) add(
                    TypeOpt(
                        // 盘上还没有数据分区 → 这块就是 Windows 系统分区（默认吃满），否则是普通数据分区
                        label = if (winRole) s.purposeWinPrimary else s.purposeNtfs,
                        hint = if (winRole) s.winHint else s.partsNewHint,
                        type = "data",
                        fs = "ntfs",
                        minMb = DiskLimits.NTFS_MIN_MB,
                        maxMb = avail,
                        defaultMb = if (winRole) 0 else minOf(avail, 4096).coerceAtLeast(DiskLimits.NTFS_MIN_MB),
                        fillDefault = winRole,
                        atLba = newAt,
                        where = newWhere,
                    ),
                )
                // 恢复分区：一个盘只有一个；位置只能是"ESP 后的第一块 NTFS 分区"正后方（Windows 认这个位置）
                val firstData = preParts
                    .filter { it.typeGuid.startsWith("EBD0A0A2", true) || it.mbri == 0x07 }
                    .minByOrNull { it.lba }
                val recAt = firstData?.let { alignUpLba(it.lba + it.sectors) }
                val recGap = recAt?.let { lba ->
                    gapsOf(preParts, usableEnd).firstOrNull { lba >= it.first && lba <= it.last }
                }
                if (!hasRec && recGap != null) {
                    val recMax = ((recGap.last + 1 - recGap.first) * 512 / (1024 * 1024)).toInt()
                    if (recMax >= DiskLimits.REC_MIN_MB) add(
                        TypeOpt(
                            label = s.purposeRec,
                            hint = s.recHint,
                            type = "recovery",
                            fs = "ntfs",
                            minMb = DiskLimits.REC_MIN_MB,
                            maxMb = minOf(DiskLimits.REC_MAX_MB, recMax),
                            defaultMb = DiskLimits.REC_MIN_MB.coerceAtMost(recMax),
                            unit = SizeUnit.MB,
                            atLba = recGap.first,
                            where = "max-gap",
                        ),
                    )
                }
            }
            if (avail >= DiskLimits.NTFS_MIN_MB) add(
                TypeOpt(
                    label = s.purposeNone,
                    hint = s.partsNewHint,
                    type = "data",
                    maxMb = avail,
                    defaultMb = minOf(avail, 4096).coerceAtLeast(DiskLimits.NTFS_MIN_MB),
                    atLba = newAt,
                    where = newWhere,
                ),
            )
        }
    }

    /** 选完类型：固定大小 / 吃满的直接入队，其余问大小（和建盘页同一个输入卡片）。 */
    fun chooseType(t: TypeOpt) {
        typeMenu = false
        when {
            t.extended -> enqueue(DraftOp.Extended(0, t.where, t.atLba))
            t.fixedMb != null -> enqueue(
                DraftOp.Add(t.fixedMb, t.type, t.fs, logical = t.logical, where = t.where, atLba = t.atLba),
            )
            // 默认值可能比这块空间还大 → 夹一下
            else -> editing = t.copy(defaultMb = t.defaultMb.coerceIn(t.minMb, t.maxMb.coerceAtLeast(t.minMb)))
        }
    }

    /** 按队列顺序真正落盘：先删（逻辑卷→容器）、再格式化、最后新建。 */
    suspend fun saveDraft() {
        val path = image ?: return
        if (draft.isEmpty()) {
            onToast(s.draftNothing)
            return
        }
        // MBR：第二块应该是 NTFS 主分区（Windows 系统分区）——只管**顺序**，不卡大小
        if (mbrWinBad) {
            onToast(s.partsNeedWin)
            return
        }
        val order = buildList {
            val rm = draft.filterIsInstance<DraftOp.Remove>()
            addAll(rm.filter { !it.container })
            addAll(rm.filter { it.container })
            addAll(draft.filterIsInstance<DraftOp.Format>())
            addAll(draft.filterIsInstance<DraftOp.Extended>())
            // ★ ESP + 保留分区是最早那一对（就排在新分区前面），漏了它整条草稿等于没执行
            addAll(draft.filterIsInstance<DraftOp.Esp>())
            addAll(draft.filterIsInstance<DraftOp.Add>().filter { !it.logical })
            addAll(draft.filterIsInstance<DraftOp.Add>().filter { it.logical })
        }
        if (order.isEmpty()) {
            // 兜底：草稿非空却一条都没排进执行序列 → 别把用户的改动默默清掉
            onToast(s.draftNothing)
            return
        }
        saving = true
        var applied = 0
        var failure: ToolRunner.Result? = null
        var contLba: Long? = parts.firstOrNull { it.isContainer() }?.lba
        // 同一块可能被排进多条 Remove（先建后删、删了又建），执行时只删一次
        val goneLba = HashSet<Long>()
        try {
            for (op in order) {
                saveAt = (applied + 1) to order.size
                if (op is DraftOp.Remove && op.lba in goneLba) {
                    applied++
                    continue
                }
                val r: ToolRunner.Result? = when (op) {
                    is DraftOp.Remove -> ImageRepo.exec(
                        cmd = "part.remove",
                        args = mapOf("lba" to op.lba.toString()),
                        path = path,
                        writable = true,
                    )

                    is DraftOp.Format -> ImageRepo.format(lba = op.lba, fs = op.fs, label = op.label, path = path)

                    // ESP + MSR：先 ESP 顶到最前，再让 MSR 紧跟着（此时"最前面的空闲区"正好在 ESP 后面）
                    is DraftOp.Esp -> {
                        // ESP 顶到最前，MSR 紧跟着：两块都用确切 LBA（工具端 1 MiB 对齐）
                        val espLba = if (op.atLba > 0) alignUpLba(op.atLba) else 0L
                        val r1 = ImageRepo.exec(
                            cmd = "part.add",
                            args = buildMap {
                                put("type", "efi")
                                put("size-mb", op.espMb.toString())
                                if (espLba > 0) put("lba", espLba.toString()) else put("where", "max-gap")
                            },
                            path = path,
                            writable = true,
                        )
                        if (!r1.ok) {
                            r1
                        } else {
                            val gotEspLba = r1.data?.optLong("lba", 0L) ?: espLba
                            val msr = ImageRepo.exec(
                                cmd = "part.add",
                                args = buildMap {
                                    put("type", "msr")
                                    put("size-mb", DiskLimits.MSR_MB.toString())
                                    if (gotEspLba > 0) {
                                        put("lba", (gotEspLba + op.espMb * ALIGN_SECTORS).toString())
                                    } else {
                                        put("where", "max-gap")
                                    }
                                },
                                path = path,
                                writable = true,
                            )
                            if (msr.ok && gotEspLba > 0) {
                                val f = ImageRepo.format(
                                    lba = gotEspLba,
                                    fs = "fat32",
                                    label = op.label ?: ImageRepo.LABEL_ESP,
                                    path = path,
                                )
                                if (!f.ok) onToast(s.purposeFormatFailed(toolErrorText(s, f)))
                            }
                            msr
                        }
                    }

                    is DraftOp.Extended -> ImageRepo.exec(
                        cmd = "part.add",
                        args = buildMap {
                            put("type", "extended")
                            if (op.mb > 0) put("size-mb", op.mb.toString())
                            // 点中的那块有确切起点 → 直接告诉工具从哪开始（REQ-10）
                            if (op.atLba > 0) put("lba", op.atLba.toString())
                            else put("where", op.where ?: "max-gap")
                        },
                        path = path,
                        writable = true,
                    ).also { if (it.ok) contLba = it.data?.optLong("lba", 0L) ?: contLba }

            is DraftOp.Add -> if (op.logical) {
                        if (contLba == null) null
                        else ImageRepo.exec(
                            cmd = "part.add",
                            args = mapOf("type" to "logical", "size-mb" to op.mb.toString()),
                            path = path,
                            writable = true,
                        )
                    } else {
                        ImageRepo.exec(
                            cmd = "part.add",
                            args = buildMap {
                                put("type", op.type)
                                put("size-mb", op.mb.toString())
                                // 点哪块建哪块：起点是 App 算的，直接传 lba（REQ-10）
                                if (op.atLba > 0) put("lba", op.atLba.toString())
                                else put("where", op.where ?: "max-gap")
                            },
                            path = path,
                            writable = true,
                        )
                    }
                }
                if (r == null) {
                    onToast(s.draftNoContainer)
                    break
                }
                if (!r.ok) {
                    failure = r
                    break
                }
                if (op is DraftOp.Remove) goneLba += op.lba
                // 新建的立刻格式化（跟着这条 Add 走；失败只提示，不重排队——分区已经建出来了）
                if (op is DraftOp.Add && op.formatFs != null) {
                    val lba = r.data?.optLong("lba", 0L) ?: 0L
                    if (lba > 0L) {
                        // 卷标：草稿里起过名就用他的，否则按角色（恢复分区 Recovery，其它不写）
                        val f = ImageRepo.format(
                            lba = lba,
                            fs = op.formatFs,
                            label = op.label ?: if (op.type == "recovery") ImageRepo.LABEL_REC else null,
                            path = path,
                        )
                        if (!f.ok) onToast(s.purposeFormatFailed(toolErrorText(s, f)))
                    }
                }
                applied++
            }
        } finally {
            saving = false
            saveAt = 0 to 0
            // 没做的留在队列里，用户可以改完再按一次；
            // 保存过程中新加的那几条也不能被这里盖掉（先算差集再拼回去）
            val addedMeanwhile = draft.filterNot { it in order }
            draft = order.drop(applied) + addedMeanwhile
            reload += 1
        }
        when {
            failure != null -> {
                val at = failure.at
                if (failure.code == "CMD-ARG-BAD" && (at == "where" || at == "lba" || at == "size-mb")) {
                    onToast(s.partsNoAtLba + " · " + toolErrorText(s, failure))
                } else {
                    onToast(toolErrorText(s, failure) + " · " + s.draftSaveFailed)
                }
            }

            applied > 0 -> onToast(s.draftSaved(applied))
        }
    }

    /**
     * 打开卷标输入框。
     *
     * [queued] ≥ 0 = 队列里第几条是**这块还没执行的格式化**：卷标直接写进那条操作
     * （盘上还没有文件系统，`ntfs.label` 跑了也必失败），保存时跟着 `fs.format --label=` 走。
     */
    fun startLabel(p: ImageRepo.Part, queued: Int = -1) {
        if (queued >= 0) {
            val op = draft.getOrNull(queued) as? DraftOp.Format ?: return
            labelJob = LabelJob(p, p.bytes, op.fs.uppercase(), op.label ?: "", queued)
            return
        }
        scope.launch {
            val scan = ImageRepo.parseScan(ImageRepo.scan(p.lba, image))
            val known = scan != null && scan.hasFs &&
                (scan.fs.equals("NTFS", true) || scan.fs.equals("FAT32", true))
            if (!known) {
                // 菜单里已经把它染灰了；这里是"扫描结果还没回来 / 刚变化"的兜底，说法保持一致
                onToast(s.partsNeedFormat)
            } else {
                labelJob = LabelJob(p, p.bytes, scan.fs.uppercase(), scan.label ?: "")
            }
        }
    }

    /** 队列里排在第 [i] 条的是**新建**（还没落盘的分区）：给它改卷标 = 改那条 Add。 */
    fun startLabelOfDraft(i: Int) {
        val op = draft.getOrNull(i)
        // 新建的按它选的文件系统；ESP+保留分区那一对固定 FAT32
        val fs = (op as? DraftOp.Add)?.formatFs?.uppercase()
            ?: if (op is DraftOp.Esp) "FAT32" else return
        val lba = pre.pending.entries.firstOrNull { it.value == i }?.key ?: 0L
        val bytes = preParts.firstOrNull { it.lba == lba }?.bytes
            ?: ((op as? DraftOp.Add)?.mb?.toLong()?.times(1024 * 1024) ?: 0L)
        val cur = when (op) {
            is DraftOp.Add -> op.label ?: ""
            is DraftOp.Esp -> op.label ?: ImageRepo.LABEL_ESP
        }
        labelJob = LabelJob(null, bytes, fs, cur, i)
    }

    /** 草稿一条的标题（新增绿 / 删除红）。 */
    fun draftTitle(op: DraftOp): String = when (op) {
        is DraftOp.Add -> if (op.logical) s.draftAddLogical(fmtBytes(op.mb.toLong() * 1024 * 1024))
        else s.draftAdd(s.draftPartName, fmtBytes(op.mb.toLong() * 1024 * 1024))

        is DraftOp.Extended -> s.draftAddExt(
            if (op.mb > 0) fmtBytes(op.mb.toLong() * 1024 * 1024) else s.commonRest,
        )

        // ESP + MSR 是一条：显示两块的合计
        is DraftOp.Esp -> s.draftAddEsp(
            fmtBytes((op.espMb + DiskLimits.MSR_MB).toLong() * 1024 * 1024),
        )

        is DraftOp.Remove -> s.draftRemove(op.name, fmtBytes(op.bytes))
        is DraftOp.Format -> s.draftFormat(op.name, op.fs.uppercase())
    }

    /** 草稿一条的副标题 = 这块分区的文件系统（不再是"点一下撤销"）。 */
    /** 队列里排着的卷标也要显示出来（`NTFS · Windows OS`），否则用户以为没记住。 */
    fun withLabel(fs: String, label: String?): String =
        if (label.isNullOrBlank()) fs else "$fs · $label"

    fun draftFs(op: DraftOp): String = when (op) {
        is DraftOp.Add -> withLabel(fsTextOf(null, null, op.formatFs), op.label)
        is DraftOp.Esp -> withLabel(s.draftFsEspPair, op.label)
        is DraftOp.Extended -> s.partsUnformatted
        is DraftOp.Remove -> fsTextOf(
            parts.firstOrNull { it.lba == op.lba },
            scans[op.lba],
        )

        is DraftOp.Format -> withLabel(op.fs.uppercase(), op.label)
    }

    // ★ 有待保存的改动：返回（按钮 / 系统手势）都要先确认，别默默丢掉草稿。
    BackHandler(enabled = draft.isNotEmpty()) { askLeave = true }
    if (askLeave) {
        ConfirmDialog(
            title = s.draftLeave,
            summary = s.draftDiscardBody(draft.size),
            confirmText = s.draftDiscard,
            cancelText = s.cancel,
            onDismiss = { askLeave = false },
            onConfirm = {
                askLeave = false
                draft = emptyList()
                onBack()
            },
        )
    }

    SmallTitlePage(
        title = s.parts,
        onBack = { if (draft.isEmpty()) onBack() else askLeave = true },
    ) {
        if (!granted || justGranted) {
            GroupTitle(s.partsPermTitle)
            GroupCard {
                if (granted) {
                    Item(s.partsPermRestartAction, s.partsPermRestart)
                } else {
                    Item(s.partsPermTitle, s.partsPermBody)
                }
            }
            Action(
                text = if (granted) s.partsPermRestartAction else s.partsPermAction,
            ) {
                if (granted) StorageAccess.restartApp(ctx) else StorageAccess.requestAllFiles(ctx)
            }
            Note(if (granted) s.partsPermRestart else s.partsPermBody)
        }

        // 模板：选文件 ↔ 卡片瞬间替换；卡片自己错峰弹入；下方正文高度展开
        if (image == null) {
            // 通用选盘页：选镜像 + 最近使用（只渲染文件还在的）
            PickImagePage(
                onPicked = { path, uri ->
                    image = path ?: uri
                    draft = emptyList()
                    reload += 1
                },
            )
        } else {
            // 顶部统一模板：标题「当前操作的文件：<名字>」+ 副标题是所在目录，
            // 点一下用系统文件管理器打开那个文件夹；切镜像走下面那行「更换镜像」。
            CurrentFileCard(
                name = pickName(image!!),
                path = image,
                onToast = onToast,
            )
        }

        if (image != null) {
            when {
                loading -> BlockEnter(2) { Note(s.partsLoading) }
                err != null -> BlockEnter(2) { Note(err!!, warn = true) }
                noTable -> BlockEnter(2) {
                    EmptyCard(
                        icon = MiuixIcons.Layers,
                        title = s.blankTitle,
                        desc = s.blankDesc,
                    )
                }
                else -> {
                    // ── 磁盘布局长条（草稿里的新块也画进来） ──
                    // ★ 有表就画：**空表（0 块分区）也是"整盘一块未分配"**，
                    //   不画的话用户根本没有可点的区域，等于建不出第一块分区。
                    BlockEnter(2) {
                    if (diskSectors > 0L) {
                        GroupTitle(s.partsLayoutTitle)
                        val segs = buildSegments(
                            ImageRepo.PtInfo(table, usableEnd, preParts),
                            s,
                            scans,
                            pendingLba = pre.pending,
                            pendingFormats = draft.filterIsInstance<DraftOp.Format>()
                                .associate { it.lba to it.fs.uppercase() },
                        )
                        DiskBar(segs, usableEnd) { seg ->
                            val p = seg.part
                            when {
                                p == null -> {
                                    val gapMb = (seg.sectors * 512 / (1024 * 1024)).toInt()
                                    if (gapMb >= DiskLimits.NTFS_MIN_MB) openTypeMenu(seg)
                                    else onToast(s.partsNoSpace)
                                }

                                seg.draftIndex >= 0 -> draftAction = seg.draftIndex
                                else -> actionTarget = p
                            }
                        }
                        // 分区表类型（用户要求：分区管理也要能看到 GPT / MBR）
                        Note(s.ptTableValue(table, fmtBytes(diskSectors * 512)))
                        Note(s.partsBarHint)
                    }
                    }

                    // 结构提示：MBR 先建系统保留、第二块放 Windows（NTFS）；GPT 要有 ESP
                    // ★ 2026-10-26：**不再卡 Windows 分区大小**（下限 = 128 MB，同 NTFS）
                    BlockEnter(3) {
                    if (isMbr) {
                        if (!hasBoot) Note(s.partsNeedBoot)
                        when {
                            mbrWinBad -> Note(s.partsNeedWin, warn = true)
                            // 只有一块（还没建 Windows）不算错，只是提醒下一块该建什么
                            prePrimaries.size == 1 && winPos < 0 -> Note(s.partsNeedWin)
                        }
                    } else if (!hasEsp && parts.isNotEmpty()) {
                        // ESP 和保留分区只能放最前方：最前面那块不够 128 + 16 就直接说清原因
                        val frontMb = (gapsOf(preParts, usableEnd).firstOrNull()
                            ?.let { it.last - it.first + 1 } ?: 0L) * 512 / (1024 * 1024)
                        if (frontGapOf(preParts, usableEnd) == null) {
                            Note(s.partsNeedFront(fmtBytes(frontMb * 1024 * 1024)), warn = true)
                        } else {
                            Note(s.partsNeedEsp)
                        }
                    }
                    }

                    // ── 待保存队列（新增绿 / 删除红；最后一行是恢复原布局） ──
                    BlockEnter(4) {
                    if (draft.isNotEmpty()) {
                    GroupTitle(s.draftTitle(draft.size))
                    GroupCard {
                        draft.forEachIndexed { i, op ->
                            Item(
                                title = draftTitle(op),
                                summary = draftFs(op),
                                titleColor = when (op) {
                                    is DraftOp.Add, is DraftOp.Extended, is DraftOp.Esp -> DRAFT_ADD
                                    is DraftOp.Remove -> colors.error
                                    is DraftOp.Format -> colors.primary
                                },
                                onClick = { draftAction = i },
                            )
                        }
                        Item(
                            title = s.draftDiscard,
                            summary = s.draftDiscardHint,
                            onClick = { discardConfirm = true },
                        )
                    }
                    }
                    }

                    // ── 分区列表 ──
                    BlockEnter(5) {
                    if (preParts.isEmpty()) {
                        // 空表：长条已经画成"整盘未分配"，这里只补一句怎么加第一块
                        Note(s.partsEmptyTable)
                    } else {
                        GroupTitle("${s.partsList}（${prePrimaries.size + preLogicals.size}）")
                        GroupCard {
                            preParts.sortedBy { it.lba }.forEach { p ->
                                val di = pre.pending[p.lba]
                                val scan = scans[p.lba]
                                val pendingTag = di != null
                                val label = if (pendingTag) s.draftPendingTag
                                else scan?.label?.takeIf { it.isNotBlank() } ?: s.partsNoLabel
                                val prefix = if (p.logical) "└ " else ""
                                // 副标题第一段是**文件系统**，而且只写真的：MSR 写"Windows 保留分区"，
                                // 没卷写"未分配"——待保存的新分区也一样（它现在还不存在）
                                val fsText = fsTextOf(p, scan)
                                Item(
                                    title = if (p.isContainer()) {
                                        s.partsContainerWithCount(preLogicals.size)
                                    } else {
                                        "$prefix${nameOf(p)} · $label · ${fmtBytes(p.bytes)}"
                                    },
                                    summary = if (p.isContainer()) s.partsContainerSummary
                                    else listOfNotNull(
                                        // MBR 的 0x27 是 NTFS 的，光看文件系统认不出它是"保留"那块 → 前面加个身份。
                                        // 0x27 **活动** = BIOS 的"系统保留"（bootmgr 在这）；0x27 非活动 = WinRE 恢复分区。
                                        if (p.mbri == 0x27) {
                                            if (p.active) s.roleSys else s.roleRec
                                        } else {
                                            null
                                        },
                                        fsText,
                                        scan?.takeIf { it.hasFs }?.let { s.partsUsedLine(fmtBytes(it.usedBytes)) },
                                        if (p.logical) s.partLogical else null,
                                        if (p.active) s.partActive else null,
                                    ).joinToString(" · "),
                                    onClick = {
                                        if (pendingTag) draftAction = di else actionTarget = p
                                    },
                                )
                            }
                        }
                        if (isMbr) Note(s.partsSlots(prePrimaries.size, preLogicals.size))
                    }
                    }

                    // ── 底部：保存修改（点长条上的未分配区新建分区） ──
                    BlockEnter(6) {
                    Action(
                        text = s.partsSaveChanges,
                        enabled = !busy && !saving,
                        muted = draft.isEmpty(),
                        danger = false,
                    ) {
                        if (draft.isEmpty()) onToast(s.draftNothing)
                        else scope.launch { saveDraft() }
                    }
                    Note(if (saving) s.draftSaving(saveAt.first, saveAt.second) else s.partsSaveHint)
                    }
                }
            }
        }
    }

    // ── 新建第一步：选类型 ──
    if (typeMenu) {
        val options = typeOptions()
        if (options.isEmpty()) {
            typeMenu = false
            onToast(s.partsNoSpace)
        } else ChoiceDialog(
            title = s.partsTypeTitle,
            summary = s.addPartFree(fmtBytes(maxGapMb.toLong() * 1024 * 1024)),
            items = options.map { it.label },
            cancelText = s.cancel,
            onDismiss = { typeMenu = false },
            onSelect = { i ->
                typeMenu = false
                options.getOrNull(i)?.let { chooseType(it) }
            },
        )
    }

    // ── 新建第二步：填大小（和建盘页同一个输入卡片：MB/GB + 最小/默认/最大） ──
    editing?.let { t ->
        NumberDialog(
            title = t.label,
            hint = t.hint,
            initial = if (t.fillDefault) 0 else t.defaultMb,
            min = t.minMb,
            max = t.maxMb,
            allowZero = t.fillDefault,
            defaultUnit = t.unit,
            cancelText = s.cancel,
            confirmText = s.confirm,
            quick = listOf(
                s.dialogMin to t.minMb,
                s.dialogDefault to if (t.fillDefault) 0 else t.defaultMb,
                s.dialogMax to t.maxMb,
            ),
            onDismiss = { editing = null },
            onConfirm = { value, _ ->
                editing = null
                // 0 = 吃满（`part.add` 只认具体大小，落盘时换算成点中那块的大小）
                val mb = if (value == 0) newRoom else value
                when {
                    // ESP + MSR 是一条草稿，撤销/删除都是两条一起
                    t.espPair -> enqueue(DraftOp.Esp(mb, t.atLba))
                    // 逻辑卷但盘上还没有容器：先建容器再建逻辑卷（一次保存里按顺序做）
                    t.logical -> {
                        if (preContainer == null) enqueue(DraftOp.Extended(0, newWhere, newAt))
                        enqueue(DraftOp.Add(mb, t.type, t.fs, logical = true))
                    }
                    else -> enqueue(
                        DraftOp.Add(mb, t.type, t.fs, logical = false, where = t.where, atLba = t.atLba),
                    )
                }
            },
            onInvalid = { onToast(s.partsSizeBad) },
        )
    }

    // ── 草稿里的一条：点一下弹操作菜单（撤销 / 调整大小），不再套确认卡 ──
    draftAction?.let { i ->
        val op = draft.getOrNull(i)
        if (op == null) {
            draftAction = null
        } else {
            // MSR 固定 16 MB 没得调；只有"会建出文件系统"的那几条能起卷标。
            // 做不了的照样列出来、灰着，点了说原因（和分区操作菜单同一套规矩）
            val canResize = op is DraftOp.Add && op.type != "msr"
            val canLabel = (op is DraftOp.Add && op.formatFs != null) || op is DraftOp.Esp
            val rows = buildList {
                if (canResize) add(ActionRow(s.partsResize, run = { draftResize = i }))
                else add(ActionRow(s.partsResize, why = s.draftNoResize))
                if (canLabel) add(ActionRow(s.lblEntry, run = { startLabelOfDraft(i) }))
                else add(ActionRow(s.lblEntry, why = s.draftNoLabel))
                add(ActionRow(s.draftUndoAction, run = { dropDraft(i) }))
            }
            ChoiceDialog(
                title = s.draftPendingTitle,
                summary = draftTitle(op) + " · " + draftFs(op),
                items = rows.map { it.label },
                enabled = rows.map { it.enabled },
                cancelText = s.cancel,
                onDismiss = { draftAction = null },
                onSelect = { k ->
                    draftAction = null
                    rows.getOrNull(k)?.run?.invoke()
                },
                onDisabled = { k ->
                    draftAction = null
                    rows.getOrNull(k)?.why?.let(onToast)
                },
            )
        }
    }

    // ── 调整草稿里这块的大小（没落盘，随便改；上限 = 现在这块 + 还能塞进去的） ──
    draftResize?.let { i ->
        val op = draft.getOrNull(i)
        if (op !is DraftOp.Add) {
            draftResize = null
        } else {
            val minMb = minMbFor(op.type)
            // 只能往这块**自己后面**长：取"这块计划结束位置"往后那一小段未分配
            val plannedEnd = alignUpLba(op.atLba) + op.mb.toLong() * ALIGN_SECTORS
            val spare = if (op.logical) {
                containerFreeMb
            } else {
                val gap = gapsOf(preParts, usableEnd)
                    .firstOrNull { plannedEnd >= it.first && plannedEnd <= it.last + 1 }
                (((gap?.let { it.last + 1 - plannedEnd } ?: 0L) * 512) / (1024 * 1024)).toInt()
            }
            val maxMb = (op.mb + spare).coerceAtLeast(minMb)
            NumberDialog(
                title = s.partsResize,
                hint = s.partsResizeHint,
                initial = op.mb.coerceIn(minMb, maxMb),
                min = minMb,
                max = maxMb,
                allowZero = false,
                defaultUnit = if (maxMb >= 2048) SizeUnit.GB else SizeUnit.MB,
                cancelText = s.cancel,
                confirmText = s.confirm,
                quick = listOf(
                    s.dialogMin to minMb,
                    s.dialogDefault to op.mb.coerceIn(minMb, maxMb),
                    s.dialogMax to maxMb,
                ),
                onDismiss = { draftResize = null },
                onConfirm = { v, _ ->
                    draftResize = null
                    draft = draft.toMutableList().also { list -> list[i] = op.copy(mb = v) }
                },
                onInvalid = { onToast(s.partsSizeBad) },
            )
        }
    }

    // ── 恢复原布局：清空队列（镜像一个字节都没写过，也要确认一下） ──
    if (discardConfirm) {
        ConfirmDialog(
            title = s.draftDiscard,
            summary = s.draftDiscardBody(draft.size),
            confirmText = s.draftDiscard,
            cancelText = s.cancel,
            onDismiss = { discardConfirm = false },
            onConfirm = {
                discardConfirm = false
                draft = emptyList()
                onToast(s.draftDiscarded)
            },
        )
    }

    // ── 点分区：格式化 / 改卷标 / 删除 ──
    actionTarget?.let { p ->
        val containerRow = p.isContainer()
        val idx = nameOf(p)
        val pendingRow = pre.pending.containsKey(p.lba)
        val isMsr = p.typeGuid.startsWith("E3C9E316", true)
        // 有没有卷只认 part.scan（level ≥1）。null = **还没扫到**（进页面是逐块扫的），
        // 这时别把"改卷标"染灰——点进去 startLabel 会自己补扫一次再判断
        val hasFs = scans[p.lba]?.hasFs
        // 这块排着"还没执行的格式化"吗？排着就允许改卷标：卷标写进那条操作，保存时一起落盘
        val qf = draft.indexOfFirst { it is DraftOp.Format && it.lba == p.lba }
        val rows = buildList {
            if (containerRow) {
                // 扩展容器只是占位：格式化 / 改卷标都列出来灰着（藏起来会以为功能没了）
                add(ActionRow(s.partsFormat, why = s.partsExtNoFormat))
                add(ActionRow(s.lblEntry, why = s.partsExtNoFormat))
            } else if (pendingRow) {
                // 还没落盘的新分区：要改就去点队列里那张卡
                add(ActionRow(s.partsFormat, why = s.partsPendingWait))
                add(ActionRow(s.lblEntry, why = s.partsPendingWait))
            } else {
                if (isMsr) {
                    // MSR 天生不承载文件系统：两项照旧列出来，**灰着**，点了说原因
                    add(ActionRow(s.partsFormat, why = s.partsMsrNoFormat))
                    add(ActionRow(s.lblEntry, why = s.partsMsrNoLabel))
                } else {
                    add(ActionRow(s.partsFormat, run = { doFormat(p) }))
                    when {
                        hasFs == true -> add(ActionRow(s.lblEntry, run = { startLabel(p) }))
                        // 有卷标可改的两种情况：盘上已有卷，或队列里排着还没执行的格式化
                        qf >= 0 -> add(ActionRow(s.lblEntry, run = { startLabel(p, qf) }))
                        hasFs == null -> add(ActionRow(s.lblEntry, run = { startLabel(p) }))
                        else -> add(ActionRow(s.lblEntry, why = s.partsNeedFormat))
                    }
                }
            }
            add(ActionRow(s.partsDelete, run = { deleting = p }))
        }
        ChoiceDialog(
            title = s.partsActionTitle,
            summary = if (containerRow) s.partsContainerWithCount(preLogicals.size)
            else "$idx · ${fmtBytes(p.bytes)}",
            items = rows.map { it.label },
            enabled = rows.map { it.enabled },
            cancelText = s.cancel,
            onDismiss = { actionTarget = null },
            onSelect = { i ->
                actionTarget = null
                rows.getOrNull(i)?.run?.invoke()
            },
            onDisabled = { i ->
                actionTarget = null
                rows.getOrNull(i)?.why?.let(onToast)
            },
        )
    }

    // ── 删除（入队；删容器连里面的逻辑卷一起排进去） ──
    deleting?.let { p ->
        val containerRow = p.isContainer()
        val pendingRow = pre.pending.containsKey(p.lba)
        ConfirmDialog(
            title = s.partsDelete,
            summary = when {
                !isMbr && (
                    p.typeGuid.startsWith("C12A7328", true) || p.typeGuid.startsWith("E3C9E316", true)
                    ) -> s.partsDelEspPair
                pendingRow && p.logical -> s.draftCancelAddLogical(fmtBytes(p.bytes))
                pendingRow -> s.draftCancelAdd(fmtBytes(p.bytes))
                containerRow -> buildString {
                    append(s.partsDeleteBodyContainer(fmtBytes(p.bytes)))
                    if (preLogicals.isNotEmpty()) {
                        append("\n")
                        append(s.partsDeleteContainer(preLogicals.size))
                    }
                }

                p.logical -> s.partsDeleteBodyLogical(
                    nameOf(p).removePrefix("L").toIntOrNull() ?: 0,
                    fmtBytes(p.bytes),
                )

                else -> s.partsDeleteBody(
                    nameOf(p).removePrefix("#").toIntOrNull() ?: 0,
                    fmtBytes(p.bytes),
                )
            },
            confirmText = s.partsDelete,
            cancelText = s.cancel,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                if (pendingRow) {
                    // 还没落盘 → 直接从草稿里拿掉
                    pre.pending[p.lba]?.let { dropDraft(it) }
                } else {
                    // 删掉这块上挂着的格式化（同一块又删又格式化没意义）
                    var next = draft.filterNot { it is DraftOp.Format && it.lba == p.lba }
                    if (containerRow) {
                        // 草稿里还没落盘的逻辑卷：容器都要删了，它们直接作废
                        next = next.filterNot { it is DraftOp.Add && it.logical }
                        // 真实存在的逻辑卷：工具端要求先删里面的，再删容器
                        parts
                            .filter { it.logical && it.lba >= p.lba && it.lba < p.lba + p.sectors }
                            .forEach { l ->
                                next = next + DraftOp.Remove(
                                    lba = l.lba,
                                    logical = true,
                                    name = nameOf(l),
                                    bytes = l.bytes,
                                )
                            }
                    }
                    // GPT 的 ESP / MSR 是一对：删一个就把两个都排上（它们是 Windows 认的一对）
                    val espOrMsr = !isMbr && (
                        p.typeGuid.startsWith("C12A7328", true) || p.typeGuid.startsWith("E3C9E316", true)
                        )
                    if (espOrMsr) {
                        parts.filter {
                            it.typeGuid.startsWith("C12A7328", true) || it.typeGuid.startsWith("E3C9E316", true)
                        }.forEach { q ->
                            if (next.none { it is DraftOp.Remove && it.lba == q.lba }) {
                                next = next + DraftOp.Remove(lba = q.lba, name = nameOf(q), bytes = q.bytes)
                            }
                        }
                    } else if (next.none { it is DraftOp.Remove && it.lba == p.lba }) {
                        next = next + DraftOp.Remove(
                            lba = p.lba,
                            container = containerRow,
                            logical = p.logical,
                            name = nameOf(p),
                            bytes = p.bytes,
                        )
                    } else {
                        onToast(s.partsAlreadyQueued)
                    }
                    draft = next
                    onToast(s.draftQueued)
                }
            },
        )
    }

    // ── 改卷标 ──
    // 盘上已经有文件系统 → 立即生效（`ntfs.label` / `fat.label`，不动布局）；
    // 只有"排在队列里还没执行的格式化" → 不跑命令，把卷标写进那条操作，保存时一起落盘。
    labelJob?.let { job ->
        val fat = job.fs.equals("FAT32", true)
        LabelDialog(
            title = s.lblTitle + " · " + fmtBytes(job.bytes),
            hint = s.lblHint,
            initial = job.initial,
            maxLength = if (fat) 11 else 32,
            confirmText = s.confirm,
            cancelText = s.cancel,
            onDismiss = { labelJob = null },
            onConfirm = { value ->
                labelJob = null
                if (fat && value.isBlank()) {
                    onToast(s.lblFatEmpty)
                    return@LabelDialog
                }
                val qi = job.queueIndex
                if (qi >= 0) {
                    // 只改队列里那条（格式化 / 新建都带 label），一个字节都不写
                    var changed = false
                    draft = draft.toMutableList().also { list ->
                        when (val op = list.getOrNull(qi)) {
                            is DraftOp.Format -> {
                                list[qi] = op.copy(label = value.ifBlank { null }); changed = true
                            }

                            is DraftOp.Add -> {
                                list[qi] = op.copy(label = value.ifBlank { null }); changed = true
                            }

                            else -> Unit
                        }
                    }
                    if (changed) onToast(s.partsLabelQueued)
                } else {
                    val p = job.part ?: return@LabelDialog
                    scope.launch {
                        busy = true
                        val r = ImageRepo.label(p.lba, job.fs, value.ifBlank { null }, image)
                        busy = false
                        if (r.ok) {
                            val got = r.data?.optString("label", "").orEmpty()
                            onToast(if (got.isBlank()) s.lblCleared else s.lblOk(got))
                        } else {
                            onToast(toolErrorText(s, r))
                        }
                        reload += 1
                    }
                }
            },
        )
    }
}

/** 草稿里的新增：绿色（和"删除红"配套，一眼分出加/减）。 */
private val DRAFT_ADD = Color(0xFF2E9E4F)

/** 显示用的文件名：路径取 basename，SAF 的 content:// 取最后一段并解码。 */
/** 磁盘/映像类后缀。名字已带这些后缀就原样显示，不再补。 */
private val DISK_EXT = setOf(
    "qcow2", "qcow", "img", "raw", "dd", "vhd", "vhdx", "vmdk", "vdi",
    "iso", "wim", "esd", "swm",
)

/** 同一路径只算一次（读文件头有 IO，别在每次重组时重读）。 */
private val pickNameCache = HashMap<String, String>()

/**
 * 页面上显示的文件名，**带后缀**。
 *
 * 启动器（Vectras VM 等）把映像存成无后缀的名字，光看 `jyrf5cw96s20763` 分不出是 qcow2 还是 vhd。
 * 名字没有后缀时按魔数补一个；判不出的格式不加后缀，不猜。
 */
internal fun pickName(p: String): String = pickNameCache.getOrPut(p) {
    val raw = if (p.startsWith("content://")) android.net.Uri.decode(p.substringAfterLast('/'))
    else File(p).name.ifBlank { p }
    val ext = raw.substringAfterLast('.', "").lowercase()
    if (ext in DISK_EXT) raw else ImageRepo.diskSuffix(p)?.let { "$raw.$it" } ?: raw
}

/** 诊断串：文件到底存不存在、能不能读（打不开时显示给用户，便于判断是权限还是路径）。 */
private fun diag(path: String?): String {
    if (path == null) return "path=null"
    val f = File(path)
    return "exists=${f.exists()} read=${f.canRead()} len=${f.length()}"
}

/** 字节 → 好读的字符串（分区用量用）。 */
internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.0f MB", b / (1L shl 20).toDouble())
    b <= 0 -> "0 MB"
    else -> "${b / 1024} KB"
}

/** 分区角色：GPT 看 typeGuid 前缀，MBR 看 0xXX。 */
internal fun partRoleLabel(p: ImageRepo.Part, s: Strings): String {
    // MBR：类型号在 `mbri` 里（typeGuid 是空的），必须先认它，否则全显示"其它"
    if (p.mbri > 0) {
        return when (p.mbri) {
            // 0x27 活动 = 系统保留（bootmgr/BCD）；0x27 非活动 = WinRE 恢复分区（Windows 自己的用法）
            0x27 -> if (p.active) s.roleSys else s.roleRec
            0x0F -> s.roleExt
            0xEF -> s.roleEsp
            0x07, 0x0B, 0x0C -> s.roleWin
            0x12 -> s.roleRec
            else -> s.roleOther
        }
    }
    val t = p.typeGuid.uppercase()
    return when {
        t.startsWith("C12A7328") -> s.roleEsp
        t.startsWith("E3C9E316") -> s.roleMsr
        t.startsWith("DE94BBA4") -> s.roleRec
        t.startsWith("EBD0A0A2") -> s.roleWin
        t == "0X27" || t == "27" -> if (p.active) s.roleSys else s.roleRec
        t == "0X07" || t == "0X0B" || t == "07" || t == "0B" -> s.roleWin
        t == "0X0C" || t == "0C" -> s.roleFat
        t == "0X0F" || t == "0F" -> s.roleExt
        t.isBlank() -> s.roleOther
        else -> p.typeGuid.take(8)
    }
}

/** 能不能格式化：MSR（16 MiB）和扩展容器（0x0F）不承载文件系统。 */
private fun ImageRepo.Part.formattable(): Boolean {
    val t = typeGuid.uppercase()
    return !(t.startsWith("E3C9E316") || t == "0X0F" || t == "0F")
}

/**
 * 格式化：`fs.format`。
 *
 * 工具端只有 **NTFS / FAT32**（没有 exFAT），簇大小也不能指定（按分区大小自动选），
 * 所以界面里"簇大小"是只读的一行，不做一个假装能调的开关。
 * `quick=false` 会逐扇区写零，几十 GB 会跑很久，默认快速。
 */
@Composable
fun FormatPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    val s = LocalStrings.current
    val image by ImageRepo.current.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var noTable by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf<List<ImageRepo.Part>>(emptyList()) }
    var targetLba by remember { mutableStateOf<Long?>(null) }
    // 分区详情（part.scan）：卷标、已用/可用、是不是 Windows 系统分区
    var scans by remember { mutableStateOf<Map<Long, ImageRepo.Scan>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }
    var diskCap by remember { mutableStateOf(0L) }
    var hostUsed by remember { mutableStateOf(0L) }
    var fs by remember { mutableIntStateOf(0) }          // 0 = NTFS, 1 = FAT32
    var label by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(image, reload) {
        loading = true
        loadErr = null
        noTable = false
        val path = image
        if (path == null) {
            loading = false
            loadErr = s.fmtNoImage
            return@LaunchedEffect
        }
        val r = ImageRepo.parts(path)
        val parsed = ImageRepo.parseParts(r)
        if (parsed == null) {
            if (r.code == "PART-READ-FAIL") {
                noTable = true
                parts = emptyList()
            } else {
                loadErr = toolErrorText(s, r)
            }
        } else {
            val list = parsed.second.filter { it.formattable() }
            parts = list
            if (list.none { it.lba == targetLba }) targetLba = null
        }
        loading = false

        // 磁盘容量 / 宿主实际占用
        val file = File(path)
        hostUsed = if (file.exists()) file.length() else 0L
        diskCap = ImageRepo.info(path).data?.optLong("size_bytes", 0L) ?: 0L

        if (parts.isNotEmpty()) {
            // part.scan 要挂载卷（几百 ms/分区），逐个读、边读边刷新，别让页面干等
            scanning = true
            val acc = LinkedHashMap<Long, ImageRepo.Scan>()
            for (p in parts) {
                ImageRepo.parseScan(ImageRepo.scan(p.lba, path))?.let { acc[p.lba] = it }
                scans = acc.toMap()
            }
            scanning = false
        }
    }

    val fsName = if (fs == 0) "ntfs" else "fat32"
    val fsTitle = if (fs == 0) "NTFS" else "FAT32"
    val targetIndex = parts.indexOfFirst { it.lba == targetLba }
    val target = if (targetIndex >= 0) parts[targetIndex] else null

    SmallTitlePage(title = s.fmtTitle, onBack = onBack) {
        // 顶部统一模板（所有页面一套）：点卡片 = 打开镜像所在文件夹
        if (image != null) {
            CurrentFileCard(
                name = image?.let { pickName(it) } ?: "—",
                path = image,
                onToast = onToast,
            )
        }
        if (diskCap > 0L) {
            GroupCard {
                Item(s.fmtDisk, s.fmtDiskSize(fmtBytes(diskCap), fmtBytes(hostUsed)))
            }
        }

        GroupTitle(s.fmtTarget)
        when {
            loading -> Note(s.partsLoading)
            loadErr != null -> Note(loadErr!!, warn = true)
            noTable -> EmptyCard(
                icon = MiuixIcons.Layers,
                title = s.blankTitle,
                desc = s.blankDesc,
            )
            parts.isEmpty() -> Note(s.fmtNoParts)
            else -> ChoiceCard(
                options = parts.mapIndexed { i, p ->
                    val scan = scans[p.lba]
                    Choice(
                        title = buildString {
                            append("#${i + 1} · ${fmtBytes(p.bytes)}")
                            // 根目录有 Windows 才是真的系统分区（typeGuid 只说明"数据分区"）
                            if (scan?.looksLikeWindows == true) {
                                append(" · ")
                                append(s.fmtWinSystem)
                            }
                        },
                        summary = buildList {
                            add(partRoleLabel(p, s))
                            when {
                                scan == null -> if (scanning) add(s.fmtScanning)
                                !scan.hasFs -> add(s.fmtPartEmpty)
                                else -> {
                                    add(scan.fs.uppercase())
                                    add(
                                        s.fmtPartUsage(
                                            fmtBytes(scan.usedBytes),
                                            fmtBytes(scan.usedBytes + scan.freeBytes),
                                        ),
                                    )
                                }
                            }
                            scan?.label?.let { add(s.fmtLabelValue(it)) }
                            if (p.logical) add(s.partLogical)
                            if (p.active) add(s.partActive)
                            add("LBA ${p.lba}")
                        }.joinToString(" · "),
                    )
                },
                selected = targetIndex,
            ) { targetLba = parts[it].lba }
        }
        // 新建的 NTFS 卷天生就占几十 MB 元数据，不解释一句用户会以为"格式化没干净"
        if (scans.values.any { it.hasFs && it.fs.equals("NTFS", true) && it.usedBytes in 0..(256L shl 20) }) {
            Note(s.fmtNtfsOverhead)
        }

        GroupTitle(s.fmtFs)
        ChoiceCard(
            options = listOf(
                Choice("NTFS", s.fmtNtfsSummary),
                Choice("FAT32", s.fmtFat32Summary),
            ),
            selected = fs,
        ) {
            fs = it
            // FAT32 卷标最多 11 个字符，切过去时截一下，别到工具端才报错
            if (it == 1) label = label.take(11)
        }

        GroupTitle(s.fmtLabel)
        TextField(
            value = label,
            onValueChange = { label = it.take(if (fs == 1) 11 else 32) },
            modifier = Modifier.fillMaxWidth(),
        )
        Note(s.fmtLabelHint)

        GroupCard {
            Item(s.fmtCluster, s.fmtClusterAuto)
            Item(
                s.fmtQuick,
                if (quick) s.fmtQuickOn else s.fmtQuickOff,
                end = { Switch(checked = quick, onCheckedChange = { quick = it }) },
            )
        }
        if (!quick) Note(s.fmtSlowWarn, warn = true)
        Note(s.fmtDanger, warn = true)

        Action(
            text = if (busy) s.fmtBusy else s.fmtAction(fsTitle),
            enabled = !busy,
            muted = target == null,
        ) {
            if (target == null) {
                onToast(s.fmtErrTarget)
                return@Action
            }
            confirm = true
        }
    }

    // 不可撤销：先确认再动手
    if (confirm && target != null) {
        val t = target
        val idx = targetIndex + 1
        ConfirmDialog(
            title = s.fmtConfirmTitle,
            summary = s.fmtConfirmBody(s.fmtTargetName(idx)),
            confirmText = s.fmtConfirmAck,
            cancelText = s.cancel,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                busy = true
                scope.launch {
                    val r = ImageRepo.format(t.lba, fsName, label.ifBlank { null }, quick)
                    busy = false
                    if (r.ok) {
                        onToast(s.fmtOk(fsTitle, fmtMb((t.bytes / (1024 * 1024)).toInt())))
                    } else {
                        onToast(toolErrorText(s, r))
                    }
                    reload += 1
                }
            },
        )
    }
}

/**
 * 清理空间：`img.trim`（REQ-20261110-66）。
 *
 * **和原来的 `img.compact` 不是一件事**：compact 是另存一份新镜像（删掉的数据会被当成有效数据照抄），
 * 这里是**原地回收** —— 按分区空闲位图，把 guest 已经删掉、qcow2 里却仍占着的簇还回去：
 * 清 L2 entry、refcount 减到 0；打洞可用时文件当场变小，不可用时只释放块。
 *
 * 打洞在 `/sdcard`（FUSE）上到底行不行还没定论，所以我们给了兜底：
 * 「重写一次以缩小」走 `img.trim --rebuild`（老库退回 `img.compact`，同一套语义）。
 *
 * 命令没到位的旧工具库上，页面照常显示，按钮变灰 + 一句说明。
 */
@Composable
fun ReclaimPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    val s = LocalStrings.current
    val image by ImageRepo.current.collectAsState()
    // 旧库（a4192120… / 2026.11.10）里没有 img.trim：整块动作先灰着。
    // 不 remember：cmd.list 是启动时异步拿的，记住 false 会一直灰着。
    val supported = ToolCaps.has("img.trim")
    var punch by remember { mutableStateOf(true) }
    var analyzing by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var rebuilding by remember { mutableStateOf(false) }
    val busy = analyzing || cleaning || rebuilding
    var srcBytes by remember { mutableStateOf(0L) }
    var capBytes by remember { mutableStateOf(0L) }
    /** 分析结果（dry-run 给的数）；null = 还没分析过，主按钮显示「分析」 */
    var planBytes by remember { mutableStateOf<Long?>(null) }
    var planClusters by remember { mutableStateOf(0L) }
    var doneText by remember { mutableStateOf<String?>(null) }
    /** 打洞全失败 → 文件没缩，给「重写一次」 */
    var punchFailed by remember { mutableStateOf(false) }
    /** 工具端 `punch_note` 原文（打洞失败时显示，别自己拼文案）。 */
    var punchNote by remember { mutableStateOf<String?>(null) }
    var rebuiltNote by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(image) {
        planBytes = null
        doneText = null
        rebuiltNote = null
        punchFailed = false
        punchNote = null
        err = null
        srcBytes = image?.let { File(it).length() } ?: 0L
        capBytes = image?.let { ImageRepo.info(it).data?.optLong("size_bytes", 0L) } ?: 0L
    }

    /** 第一步永远先 dry-run：让用户看清能省多少，再决定要不要写。 */
    fun analyze() {
        val img = image ?: return
        scope.launch {
            analyzing = true
            err = null
            doneText = null
            rebuiltNote = null
            punchFailed = false
        punchNote = null
            planBytes = null
            val r = withContext(Dispatchers.IO) { ImageRepo.trim(img = img, dryRun = true) }
            analyzing = false
            if (!r.ok) {
                err = toolErrorText(s, r)
                return@launch
            }
            planBytes = r.data?.optLong("guest_bytes_freed", 0L) ?: 0L
            planClusters = r.data?.optLong("clusters_freed", 0L) ?: 0L
        }
    }

    fun clean() {
        val img = image ?: return
        scope.launch {
            cleaning = true
            err = null
            rebuiltNote = null
            val r = withContext(Dispatchers.IO) {
                ImageRepo.trim(img = img, dryRun = false, punch = punch)
            }
            cleaning = false
            if (!r.ok) {
                err = toolErrorText(s, r)
                return@launch
            }
            val d = r.data
            val freed = d?.optLong("guest_bytes_freed", 0L) ?: 0L
            val before = d?.optLong("alloc_before", 0L) ?: 0L
            val after = d?.optLong("alloc_after", 0L) ?: 0L
            // 工具端 REPLY-66：打洞失败不报错，用 punch_ok=false（+ punch_note）表达；
            // 老库只给 punch_failed。两个都看，别漏了"文件没变小"那种情况。
            punchFailed = (d?.optLong("punch_failed", 0L) ?: 0L) > 0L ||
                d?.optBoolean("punch_ok", true) == false
            // REPLY-71 Q2：有 punch_ok=false 就一定有 punch_note，直接原样显示
            punchNote = d?.optString("punch_note")?.takeIf { it.isNotBlank() }
            doneText = s.trimDone(fmtBytes(freed), fmtBytes(before), fmtBytes(after))
            planBytes = null             // 清完要重新分析才知道还剩多少
            srcBytes = File(img).length()
        }
    }

    /** 打洞不成立时的兜底：另存一份只含「guest 已分配簇」的新盘。 */
    fun rebuild() {
        val img = image ?: return
        val name = ImageRepo.displayName(img) ?: "disk"
        val dir = File(img).parentFile
        var out = File(dir, "$name-trimmed.qcow2")
        var n = 2
        while (out.exists()) out = File(dir, "$name-trimmed-${n++}.qcow2")
        scope.launch {
            rebuilding = true
            err = null
            // 先按新接口调；老库没 --rebuild 就退回 img.compact（同一套「另存一份」语义）
            var r = withContext(Dispatchers.IO) {
                ImageRepo.trim(
                    img = img, dryRun = false, punch = false,
                    rebuild = true, out = out.absolutePath,
                )
            }
            if (!r.ok && (r.code == "CMD-ARG-BAD" || r.code == "CMD-NOTFOUND")) {
                r = withContext(Dispatchers.IO) {
                    ImageRepo.compact(src = img, out = out.absolutePath)
                }
            }
            rebuilding = false
            if (!r.ok) {
                err = toolErrorText(s, r)
                return@launch
            }
            rebuiltNote = out.name
            onToast(s.trimRebuilt(out.name))
        }
    }

    SmallTitlePage(title = s.trimTitle, onBack = onBack) {
        GroupCard {
            Item(s.trimSource, pickName(image.orEmpty()))
            if (srcBytes > 0L) Item(s.trimDisk, s.fmtDiskSize(fmtBytes(capBytes), fmtBytes(srcBytes)))
        }
        Note(s.trimNote)

        if (!supported) {
            Note(s.trimUnsupported, warn = true)
        } else {
            GroupCard {
                Item(
                    s.trimPunch,
                    if (punch) s.trimPunchOn else s.trimPunchOff,
                    end = { Switch(checked = punch, onCheckedChange = { punch = it }) },
                )
            }

            planBytes?.let {
                Note(s.trimPlan(fmtBytes(it), planClusters))
                Note(s.trimPlanHint)
            }
            doneText?.let { Note(it) }
            if (punchFailed) {
                Note(punchNote ?: s.trimNoShrink, warn = true)
                Action(text = if (rebuilding) s.trimCleaning else s.trimRebuild, enabled = !busy) {
                    rebuild()
                }
            }
            rebuiltNote?.let { Note(s.trimRebuildNote(it)) }
            err?.let { Note(it, warn = true) }

            Action(
                text = when {
                    analyzing -> s.trimAnalyzing
                    cleaning -> s.trimCleaning
                    planBytes == null -> s.trimAnalyze
                    else -> s.trimClean
                },
                enabled = !busy && image != null,
                muted = image == null,
            ) {
                when {
                    image == null -> onToast(s.trimNeedImage)
                    planBytes == null -> analyze()
                    else -> clean()
                }
            }
        }
    }
}
