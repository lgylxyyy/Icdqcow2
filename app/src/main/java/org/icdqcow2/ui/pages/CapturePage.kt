package org.icdqcow2.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.ToolEvents
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 捕获映像：把系统分区整树打包成 WIM（`wim.capture`）。
 *
 * 流程（REQ-60 §2.3 + REQ-61 §3）：
 *   ① 选盘 + 输出文件
 *   ② estimate=true 拿 bytes_needed（**未压缩树**的体积，秒级，不写盘）
 *   ③ 空间预检：两个地方都要查 ——
 *      · 工具端的临时目录（`sys.tmpdir` 给 free_bytes）：中间树落在这里，
 *        要装下整棵未压缩的树，是**硬需求**；
 *      · 输出文件所在盘：装压缩后的 WIM，体积事先算不出来，只能给下限。
 *   ④ 正式捕获（none/xpress/lzx/lzms）
 *
 * 临时目录走 `TempDirs`（App 私有 filesDir），启动时会清扫，App 崩溃也不会污染用户盘。
 */
@Composable
fun CapturePage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialImage: String? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // 进度页数据源：工具端事件流（REQ-68 钉了 pct/elapsed/eta/phase/item 的口径）
    val prog by ToolEvents.progress.collectAsState()

    var image by remember { mutableStateOf(initialImage) }
    var winLba by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var comp by remember { mutableStateOf("xpress") }
    var capName by remember { mutableStateOf("Windows") }
    var outPath by remember { mutableStateOf("") }

    /** 估算结果（字节）。null = 还没估。 */
    var needBytes by remember { mutableStateOf<Long?>(null) }
    var needFiles by remember { mutableStateOf(0L) }
    /** 工具端临时目录所在卷的剩余空间（sys.tmpdir 给的权威值）。 */
    var tmpFree by remember { mutableStateOf<Long?>(null) }
    var tmpPath by remember { mutableStateOf("") }
    /** 输出文件所在卷的剩余空间。 */
    var outFree by remember { mutableStateOf<Long?>(null) }

    var running by remember { mutableStateOf(false) }
    // 工具端事件里没有 elapsed（JNI 桥没发）→ 本地计时兜底
    val elapsedMine = rememberElapsedMs(running)
    var result by remember { mutableStateOf<ToolRunner.Result?>(null) }

    // 选盘 → 自动定位 Windows 分区；输出文件默认放宿主 Download
    LaunchedEffect(image) {
        val img = image ?: run {
            winLba = null
            return@LaunchedEffect
        }
        loading = true
        err = null
        needBytes = null
        val r = runCatching { loadSnap(img) }.getOrNull()
        winLba = r?.windows()?.lba
        if (r?.pt == null) err = s.brNoTable else if (winLba == null) err = s.regNoWindows
        if (outPath.isBlank()) {
            outPath = "/storage/emulated/0/Download/" +
                (img.substringAfterLast('/').substringBeforeLast('.') + ".wim")
        }
        loading = false
    }

    /** 估算 + 空间预检（两个卷都查）。 */
    fun estimate() {
        val img = image ?: return
        val lba = winLba ?: return
        loading = true
        err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { ImageRepo.captureEstimate(lba = lba, img = img) }
            loading = false
            if (!r.ok) {
                err = toolErrorText(s, r)
                return@launch
            }
            needBytes = r.data?.optLong("bytes_needed", 0L) ?: 0L
            needFiles = r.data?.optLong("files", 0L) ?: 0L
            // 工具端真正的临时目录在哪、那一卷还剩多少 —— 问它本人，别自己猜
            val t = withContext(Dispatchers.IO) { ImageRepo.sysTmpdir() }
            if (t.ok) {
                tmpPath = t.data?.optString("path").orEmpty()
                tmpFree = t.data?.optLong("free_bytes", -1L)?.takeIf { it >= 0 }
            }
            // 输出文件所在卷
            outFree = runCatching {
                java.io.File(outPath).parentFile?.usableSpace
            }.getOrNull()
        }
    }

    /** 硬门槛：临时目录装不下整棵树 = 必然失败，别让用户白等。 */
    fun tmpShort(): Boolean {
        val need = needBytes ?: return false
        val free = tmpFree ?: return false
        return free < need * 12L / 10L
    }

    /** 输出盘明显不够（低于未压缩体积的 35%）= 压缩后也放不下。 */
    fun outHopeless(): Boolean {
        val need = needBytes ?: return false
        val free = outFree ?: return false
        return free < need * 35L / 100L
    }

    /** 输出盘偏小（低于 60%）：压缩后大概率够，但提醒一句。 */
    fun outTight(): Boolean {
        val need = needBytes ?: return false
        val free = outFree ?: return false
        return free < need * 60L / 100L
    }

    /** 开始捕获（已过空间预检）。 */
    fun runCapture() {
        val img = image ?: return
        val lba = winLba ?: return
        if (outPath.isBlank()) {
            onToast(s.capNeedOut)
            return
        }
        if (tmpShort()) {
            err = s.capNoSpaceTmp(fmtBytes((needBytes ?: 0L)), fmtBytes(tmpFree ?: 0L))
            return
        }
        if (outHopeless()) {
            err = s.capNoSpaceOut(fmtBytes((needBytes ?: 0L)))
            return
        }
        running = true
        err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ImageRepo.capture(
                    lba = lba,
                    out = outPath,
                    comp = comp,
                    name = capName.ifBlank { "Windows" },
                    img = img,
                )
            }
            running = false
            result = r
            if (r.ok) onToast(s.capDone) else err = toolErrorText(s, r)
        }
    }

    SmallTitlePage(title = s.capTitle, onBack = onBack) {
        if (running) {
            // 整页只留进度：圆盘 + 蓝边 + 圆心百分比 + 内下方时间 + 外下方当前文件
            BlockEnter(0) {
                RunProgressBlock(
                    pct = prog?.pct ?: -1,
                    timeText = progressTimeText(
                        maxOf(prog?.elapsedMs ?: 0L, elapsedMine),
                        prog?.eta ?: -1,
                    ),
                    phase = progressPhaseText(prog?.phase),
                    countsText = prog?.let { g ->
                        val tb = g.totalBytes
                        if (tb > 0L) fmtBytes(g.curBytes) + "/" + fmtBytes(tb) else null
                    },
                    item = prog?.item?.takeIf { it.isNotBlank() },
                )
            }
            Action(text = s.cancel, danger = true) { ToolRunner.cancel() }
        } else {
            BlockEnter(0) {
                GroupTitle(s.regImage)
                if (image == null) {
                    Note(s.regNeedImage)
                    PickImagePage(onPicked = { p, uri -> image = p ?: uri })
                } else {
                    CurrentFileCard(name = pickName(image!!), path = image, onToast = onToast)
                }
            }

            if (image != null && winLba != null) {
                BlockEnter(1) {
                    GroupTitle(s.capOut)
                    TextField(
                        value = outPath,
                        onValueChange = { outPath = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Note(s.capOutHint)
                }
                BlockEnter(2) {
                    GroupTitle(s.capComp)
                    ChoiceCard(
                        options = ImageRepo.CAPTURE_COMP.map { Choice(it, s.capCompHint(it)) },
                        selected = ImageRepo.CAPTURE_COMP.indexOf(comp).coerceAtLeast(0),
                    ) { i ->
                        if (comp != ImageRepo.CAPTURE_COMP[i]) {
                            comp = ImageRepo.CAPTURE_COMP[i]
                            // 换压缩方式 → 估算作废，把「估算大小」按钮放回来重估一次
                            needBytes = null
                            result = null
                            err = null
                        }
                    }
                }
                BlockEnter(3) {
                    GroupTitle(s.capName)
                    TextField(
                        value = capName,
                        onValueChange = { capName = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BlockEnter(4) {
                    when {
                        needBytes == null ->
                            Action(text = s.capEstimate, enabled = !loading && !running) { estimate() }
                        else -> {
                            GroupCard {
                                Item(
                                    s.capEstNeed,
                                    fmtBytes(needBytes!!) + " · " + needFiles + " " + s.capFiles,
                                )
                                Item(
                                    s.capTmpFree,
                                    (tmpFree?.let { fmtBytes(it) } ?: "—") +
                                        if (tmpPath.isBlank()) "" else " · " + tmpPath,
                                )
                                Item(s.capOutFree, outFree?.let { fmtBytes(it) } ?: "—")
                            }
                            when {
                                tmpShort() -> {
                                    Note(
                                        s.capNoSpaceTmp(
                                            fmtBytes(needBytes!!),
                                            fmtBytes(tmpFree ?: 0L),
                                        ),
                                        warn = true,
                                    )
                                }
                                outHopeless() -> {
                                    Note(s.capNoSpaceOut(fmtBytes(needBytes!!)), warn = true)
                                }
                                else -> {
                                    Action(text = s.capRun, enabled = !running) { runCapture() }
                                    if (outTight()) Note(s.capWarnSpace, warn = true)
                                }
                            }
                        }
                    }
                    if (err != null) Note(err!!, warn = true)
                }
                result?.let { r ->
                    if (r.ok) {
                        val d = r.data
                        BlockEnter(5) {
                            GroupTitle(s.capResult)
                            GroupCard {
                                Item(s.capRatio, (d?.optInt("ratio_pct", 0) ?: 0).toString() + "%")
                                Item(
                                    s.capInOut,
                                    fmtBytes(d?.optLong("bytes_in", 0L) ?: 0L) + " → " +
                                        fmtBytes(d?.optLong("bytes_out", 0L) ?: 0L),
                                )
                                // 工具端 REPLY-65：重解析点（junction）与宿主符号链接合并计数，
                                // 这些内容没有进 WIM，得告诉用户一声
                                val skipped = d?.optLong("skipped", 0L) ?: 0L
                                if (skipped > 0L) Item(s.capSkippedTitle, s.capSkipped(skipped))
                            }
                        }
                    }
                }
            } else if (image != null && err != null) {
                BlockEnter(1) { Note(err!!, warn = true) }
            }
        }
    }
}
