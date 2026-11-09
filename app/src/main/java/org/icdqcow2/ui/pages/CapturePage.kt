package org.icdqcow2.ui.pages

import androidx.compose.runtime.Composable
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
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import org.icdqcow2.core.ToolRunner

/**
 * 捕获映像：把系统分区整树打包成 WIM（`wim.capture`）。
 *
 * 流程（REQ-60 §2.3）：
 *   ① 选盘 + 输出文件
 *   ② estimate=true 拿 bytes_needed_mb（秒级，不写盘）
 *   ③ 空间预检：临时目录所在分区剩余 ≥ bytes_needed × 1.2，不足直接拒绝
 *   ④ 正式捕获（comp 可选 none/xpress/lzx/lzms）
 *
 * 临时目录走 [TempDirs]（App 缓存），启动时会清扫，App 崩溃也不会污染用户盘。
 */
@Composable
fun CapturePage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialImage: String? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var image by remember { mutableStateOf(initialImage) }
    var winLba by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var comp by remember { mutableStateOf("xpress") }
    var capName by remember { mutableStateOf("Windows") }
    var outPath by remember { mutableStateOf("") }

    /** 估算结果（MB）。null = 还没估。 */
    var needMb by remember { mutableStateOf<Long?>(null) }
    var needFiles by remember { mutableStateOf(0L) }
    var hostFreeMb by remember { mutableStateOf<Long?>(null) }

    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolRunner.Result?>(null) }

    // 选盘 → 自动定位 Windows 分区；输出文件默认放宿主 Download
    LaunchedEffect(image) {
        val img = image ?: run {
            winLba = null
            return@LaunchedEffect
        }
        loading = true
        err = null
        needMb = null
        val r = runCatching { loadSnap(img) }.getOrNull()
        winLba = r?.windows()?.lba
        if (r?.pt == null) err = s.brNoTable else if (winLba == null) err = s.regNoWindows
        if (outPath.isBlank()) {
            outPath = "/storage/emulated/0/Download/" +
                (img.substringAfterLast('/').substringBeforeLast('.') + ".wim")
        }
        loading = false
    }

    /** 估算 + 空间预检。 */
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
            val d = r.data
            needMb = d?.optLong("bytes_needed_mb", 0L) ?: 0L
            needFiles = d?.optLong("files", 0L) ?: 0L
            // 输出文件所在分区剩余空间
            hostFreeMb = runCatching {
                val f = java.io.File(outPath).parentFile
                f.usableSpace / (1024L * 1024L)
            }.getOrNull()
        }
    }

    /** 开始捕获（已经过空间预检）。 */
    fun runCapture() {
        val img = image ?: return
        val lba = winLba ?: return
        if (outPath.isBlank()) {
            onToast(s.capNeedOut)
            return
        }
        val need = needMb ?: return
        val free = hostFreeMb ?: return
        // 工具端要临时目录 ≈ 原始体积 + 输出文件 ≈ 压缩后体积，1.2 倍余量兜底
        if (free < need * 12L / 10L) {
            err = s.capNoSpace(fmtBytes(need * 1024L * 1024L))
            return
        }
        running = true
        err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ImageRepo.capture(lba = lba, out = outPath, comp = comp, name = capName.ifBlank { "Windows" }, img = img)
            }
            running = false
            result = r
            if (r.ok) onToast(s.capDone)
            else err = toolErrorText(s, r)
        }
    }

    SmallTitlePage(title = s.capTitle, onBack = onBack) {
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
                ) { i -> comp = ImageRepo.CAPTURE_COMP[i] }
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
                    needMb == null -> Action(text = s.capEstimate, enabled = !loading && !running) { estimate() }
                    else -> {
                        GroupCard {
                            Item(s.capEstNeed, fmtBytes(needMb!! * 1024L * 1024L) + " · " + needFiles + " " + s.capFiles)
                            Item(s.capEstFree, hostFreeMb?.let { fmtBytes(it * 1024L * 1024L) } ?: "—")
                        }
                        if (hostFreeMb != null && hostFreeMb!! < needMb!! * 12L / 10L) {
                            Note(s.capNoSpace(fmtBytes(needMb!! * 1024L * 1024L)), warn = true)
                        } else {
                            Action(text = s.capRun, enabled = !running) { runCapture() }
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
                            Item(s.capInOut,
                                fmtBytes(d?.optLong("bytes_in", 0L) ?: 0L) + " → " +
                                    fmtBytes(d?.optLong("bytes_out", 0L) ?: 0L))
                        }
                    }
                }
            }
        } else if (image != null && err != null) {
            BlockEnter(1) { Note(err!!, warn = true) }
        }
    }
}
