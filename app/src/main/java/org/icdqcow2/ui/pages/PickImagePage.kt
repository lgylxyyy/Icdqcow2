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

    /**
     * 拿到 SAF 的 fd：**先试 "rw"**，拿不到再退 "r"。
     *
     * ★ 2026-11-12（批次 A）：退到 "r" 得到的**只读 fd 不再顶掉真实路径**。
     *   以前不管 rw/r 都塞给 [ImageRepo.setPickedFd]，于是
     *   「别的应用的文件」（如虚拟机目录里的 qcow2）被当成可写映像：
     *   写命令报「映像是只读打开的」，`img.info` 还因为走了只读 fd
     *   算不出宿主占用（界面显示「容量 16.0 GB · 占用 —」）。
     *   现在退到只读时要**如实上报**：只读 fd 只给读操作兜底，真实路径照走，
     *   界面按 [ImageRepo.imageReadonly] 提示「此文件只读」。
     *
     * ★★ 2026-11-12 晚（用户报「没提示但报只读」）：**请求 "rw" 成功 ≠ fd 真能写**。
     *   有的 provider 会「成功返回」一个只读 fd（openFileDescriptor 不报错，
     *   但 fd 的实际访问模式是 O_RDONLY）→ 以前 `writable=true`、灰字不显示，
     *   工具端拿这个假 rw fd 去写才失败。现在拿到 fd 后用 `F_GETFL` 看真实模式，
     *   是 O_RDONLY 就按只读处理（关掉重拿 "r"，并如实上报只读 → 灰字会出现）。
     *
     * @return (fd, 是否可写)；两个模式都打不开时返回 null。
     */
    fun openFd(uri: Uri): Pair<android.os.ParcelFileDescriptor, Boolean>? {
        val rw = runCatching { ctx.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (rw != null) {
            val mode = runCatching {
                android.system.Os.fcntlInt(rw.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
            }.getOrDefault(android.system.OsConstants.O_RDONLY)
            val realRw = (mode and android.system.OsConstants.O_ACCMODE) == android.system.OsConstants.O_RDWR
            if (realRw) return rw to true
            // 假 rw：请求了写但给的是只读 fd → 关掉，按"只读"重走下面那条路
            runCatching { rw.close() }
        }
        return runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull()
            ?.let { it to false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fd = openFd(uri)
        val path = StorageAccess.documentToPath(uri)
        // ★ 持久化权限按**实际拿到的模式**取（2026-11-13）：以前固定要 READ|WRITE，
        //   provider 只给 READ 时会抛 SecurityException 被吞掉 → 之后从「最近使用」
        //   重开这个 uri 只剩读权限，写操作就永远失败。现在只拿到读就只声明读。
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                if (fd?.second == true) {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                } else {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                },
            )
        }
        val name = StorageAccess.queryName(ctx, uri)
            ?: path?.substringAfterLast('/')
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "image.qcow2"
        // 磁盘格式按魔数识别（REPLY-59），扩展名只用于提示；这五种都放行
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("qcow2", "raw", "img", "dd", "vhd", "vmdk")) {
            onToastPlaceholder(s.partsNotQcow2)
            return@rememberLauncherForActivityResult
        }
        // 只读 fd 也记下来，但**不再顶掉真实路径**；有路径就不算"只读映像"（P3）
        ImageRepo.setPickedFd(fd?.first, writable = fd?.second == true, pathKnown = path != null)
        // 诊断日志（用户复现「自己建的盘报只读」时用 `adb logcat -s IcdqcowPick` 看）
        android.util.Log.i(
            "IcdqcowPick",
            "pick uri=$uri path=${path ?: "(null)"} fd=${fd?.second} " +
                "imageReadonly=${ImageRepo.imageReadonly.value}",
        )
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
                            val fd = u?.let { openFd(it) }
                            // 拿不到 fd 也要清掉上一个文件的 fd —— 否则会拿着**别人的** fd
                            // 去开这个文件（2026-11-12 批次 A 顺手修的串文件隐患）
                            ImageRepo.setPickedFd(
                                fd?.first,
                                writable = fd?.second == true,
                                pathKnown = e.path != null,
                            )
                            android.util.Log.i(
                                "IcdqcowPick",
                                "pick(history) path=${e.path} fd=${fd?.second} " +
                                    "imageReadonly=${ImageRepo.imageReadonly.value}",
                            )
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