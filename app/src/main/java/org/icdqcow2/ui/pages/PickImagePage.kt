package org.icdqcow2.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.icdqcow2.core.ImageHistory
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.ui.LocalStrings

/**
 * 通用「选择镜像」页：所有"要操作某个磁盘文件"的页面都先过这里。
 *
 * 内容：一个「选择镜像文件」入口 + **最近使用**（只渲染文件真的还在的条目）。
 * 选中后：拿 SAF 的 fd（别的应用的文件只有 fd 能用）→ `img.info` 验一遍 →
 * 记进历史 → 回调给上层。
 *
 * ★ 历史列表订阅 [ImageHistory.visible]，**不在 Composable 里 stat 文件**：
 *   "还在不在"要开 fd 试，是 IO；以前每次重组都跑一遍、还把 uri-only 的条目
 *   全过滤掉（只看路径），表现就是"打开过、历史里没有"。
 */
@Composable
fun PickImagePage(
    onPicked: (path: String?, uri: String?) -> Unit,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted = rememberAllFilesGranted()
    val showCount by ImageHistory.showCount.collectAsState()
    val items by ImageHistory.visible.collectAsState()

    // 进页面刷一次：筛掉文件真没了的、修掉老版本存坏的路径、补齐缺失的大小
    LaunchedEffect(Unit) { ImageHistory.refresh() }

    /** 拿到 SAF 的 fd（写优先，退只读）；别的应用的镜像只有这条路走得通。 */
    fun openFd(uri: Uri) = runCatching { ctx.contentResolver.openFileDescriptor(uri, "rw") }
        .recoverCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }
        .getOrNull()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val path = StorageAccess.documentToPath(uri)
        val name = StorageAccess.queryName(ctx, uri)
            ?: path?.substringAfterLast('/')
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "image.qcow2"
        if (!name.endsWith(".qcow2", true)) {
            onToastPlaceholder(s.partsNotQcow2)
            return@rememberLauncherForActivityResult
        }
        ImageRepo.setPickedFd(openFd(uri))
        scope.launch {
            val r = ImageRepo.info(path)
            if (!r.ok) {
                onToastPlaceholder(s.partsBadImage(toolErrorText(s, r)))
                return@launch
            }
            val size = StorageAccess.querySize(ctx, uri)
                .takeIf { it > 0 }
                ?: path?.let { runCatching { File(it).length() }.getOrDefault(0L) }
                ?: 0L
            ImageHistory.record(path = path, uri = uri.toString(), name = name, sizeBytes = size)
            onPicked(path, uri.toString())
        }
    }

    GroupCard {
        LinkItem(s.pickPick, s.partsNone) {
            if (!granted) StorageAccess.requestAllFiles(ctx) else picker.launch(arrayOf("*/*"))
        }
    }

    // 设置里选"不显示"就整块不渲染
    if (showCount > 0) {
        GroupTitle(s.historyTitle)
        val shown = items.take(showCount)
        if (shown.isEmpty()) {
            Note(s.historyEmpty)
        } else {
            GroupCard {
                shown.forEach { e ->
                    val size = if (e.sizeBytes > 0) fmtBytes(e.sizeBytes) else "—"
                    LinkItem(e.name, size) {
                        scope.launch {
                            // 历史条目可能只有 uri：重新拿 fd 再验一遍
                            val u = e.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            val pfd = u?.let { openFd(it) }
                            if (pfd != null) ImageRepo.setPickedFd(pfd)
                            val r = ImageRepo.info(e.path)
                            if (!r.ok) {
                                onToastPlaceholder(s.partsBadImage(toolErrorText(s, r)))
                            } else {
                                ImageHistory.record(e.path, e.uri, e.name, e.sizeBytes)
                                onPicked(e.path, e.uri)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 让 PickImagePage 不依赖上层的 toast 通道：先放到全局队列里（MainActivity 的 ToastHostState）。 */
private val toastSink = MutableStateFlow<(String) -> Unit>({})

fun setToastSink(f: (String) -> Unit) {
    toastSink.value = f
}

private fun onToastPlaceholder(msg: String) {
    toastSink.value.invoke(msg)
}