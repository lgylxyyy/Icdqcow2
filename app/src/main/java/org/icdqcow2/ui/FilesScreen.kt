package org.icdqcow2.ui

import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.TempDirs
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.ShizukuShell
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.core.ToolCaps
import org.icdqcow2.core.ToolEvents
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.pages.Choice
import org.icdqcow2.ui.pages.ChoiceCard
import org.icdqcow2.ui.pages.ChoiceDialog
import org.icdqcow2.ui.pages.ConflictDialog
import org.icdqcow2.ui.pages.CardDropdownTopEnd
import org.icdqcow2.ui.pages.ConfirmDialog
import org.icdqcow2.ui.pages.LabelDialog
import org.icdqcow2.ui.pages.ProgressBar
import org.icdqcow2.ui.pages.isContainer
import org.icdqcow2.ui.pages.toolErrorText
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 文件页（底部导航第二个 tab）：**双栏浏览 + 复制/剪切/粘贴**。
 *
 * 规格见 `docs/UI设计-2026-10-24.md` §5 与 `docs/文件页-双栏-改造计划-2026-10-28.md`：
 *   · 两栏各是一台独立的文件浏览器（各自的**位置栈**、路径、选择集）；
 *   · 点 qcow2 → 这一栏变成"分区列表"（卷标 / 文件系统 / 已用）；点 ISO/WIM/压缩包 → 直接进去；
 *   · 复制/剪切 → 对栏粘贴；路径走工具端统一 `!` 语法，跨容器不中转；
 *   · ISO / WIM / 压缩包只读：粘贴/删除按钮灰着**但能点**，点了说原因；
 *   · 返回键 = 上一级，到 `/` 才交给系统退到桌面。
 */
private const val PAGE = 2000

/** 认成"纯文本"的扩展名（点了直接进编辑器，不再问）。 */
private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "log", "json", "jsonc", "xml", "html", "htm", "ini", "cfg", "conf",
    "properties", "yml", "yaml", "toml", "reg", "inf", "sh", "bash", "kt", "kts", "java", "c",
    "h", "cpp", "hpp", "cs", "py", "js", "ts", "css", "gradle", "bat", "cmd", "ps1", "csv",
    "tsv", "srt", "vtt", "lst", "nfo", "pro", "sql", "diff", "patch", "asc", "pem",
)

private fun isTextName(n: String): Boolean {
    val low = n.lowercase()
    if (low.substringAfterLast('.', "") in TEXT_EXTS) return true
    return low in setOf("readme", "license", "makefile", ".gitignore", ".profile", ".bashrc", "hosts")
}

private enum class Kind { HOST, NTFS, FAT, ISO, WIM, ARCHIVE, IMAGE, WIMIDX }

/** 一栏的"容器位置"。IMAGE = 刚进 qcow2、还没选分区的那一层（列表是分区）。 */
private data class Loc(
    val kind: Kind,
    val img: String? = null,
    val lba: Long = 0,
    val iso: String? = null,
    /** WIM/ESD 文件；ISO 里的 wim 时这里仍是 ISO 路径。 */
    val wim: String? = null,
    /** ISO 里那个 wim/esd 的包内路径（`/sources/install.esd`）；直接给 wim 时为 null。 */
    val inner: String? = null,
    /** 压缩包路径。 */
    val arc: String? = null,
    val index: Int = 1,
    val label: String = "",
    /** 第几个**已用**分区（1 起）。0 = 不知道，显示时退回 lba。 */
    val partIndex: Int = 0,
) {
    val writable: Boolean get() = kind == Kind.HOST || kind == Kind.NTFS || kind == Kind.FAT
    val readonlyHint: Boolean
        get() = kind == Kind.ISO || kind == Kind.WIM || kind == Kind.ARCHIVE

    val fsTag: String
        get() = when (kind) {
            Kind.NTFS -> "NTFS"
            Kind.FAT -> "FAT32"
            Kind.ISO -> "ISO"
            Kind.WIM -> "WIM"
            Kind.ARCHIVE -> "ZIP"
            Kind.IMAGE, Kind.WIMIDX -> ""
            else -> "host"
        }

    /**
     * 给**人看**的路径串：分区用 `!part3:` 而不是 `!lba444416:`（用户 2026-10-29 要求）。
     * 工具调用仍用 [spec]（lba 精确、不受分区变动影响）。
     */
    fun displaySpec(p: String): String = when (kind) {
        Kind.NTFS, Kind.FAT ->
            if (partIndex > 0) "$img!part$partIndex:${p.ifEmpty { "/" }}"
            else "$img!lba$lba:${p.ifEmpty { "/" }}"
        else -> spec(p)
    }

    /** 工具端统一路径串。 */
    fun spec(p: String): String = when (kind) {
        Kind.HOST -> "host:" + p.trimEnd('/').ifEmpty { "/" }
        Kind.NTFS, Kind.FAT -> "$img!lba$lba:${p.ifEmpty { "/" }}"
        Kind.ISO -> "$iso!:${p.ifEmpty { "/" }}"
        Kind.WIM -> {
            val inn = inner
            if (inn.isNullOrEmpty()) "$wim!$index:${p.ifEmpty { "/" }}"
            else "$wim!:$inn!$index:${p.ifEmpty { "/" }}"
        }
        Kind.ARCHIVE -> "$arc!:${p.ifEmpty { "/" }}"
        Kind.IMAGE, Kind.WIMIDX -> ""
    }
}

private data class Entry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/** 分区列表的一行（进 qcow2 之后那一层）。 */
private data class PartRow(
    val index: Int,
    val lba: Long,
    val bytes: Long,
    val fs: String? = null,
    val label: String? = null,
    val used: Long? = null,
    val hasFs: Boolean = false,
)

/** 搜索结果里的一条。 */
private data class SearchHit(
    val name: String,
    val dir: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

/** 搜索最多返回多少条（再多也没人看，还卡）。 */
private const val SEARCH_MAX = 500

/**
 * 在**聚焦栏的当前目录**里递归找名字（不分大小写）。
 *
 * - 宿主目录：Java `listFiles()` 广度遍历，纯本地、很快；
 * - 容器（分区 / ISO / WIM / 压缩包）：一层层 `ls`（BFS），直接用 [query] 换个 path 就行，
 *   所以五种容器只有一套代码。目录数/结果数都设上限，避免在几万条的盘里转到天荒地老。
 */
private suspend fun searchPane(
    p: PaneState,
    q: String,
    s: Strings,
    onFind: (List<SearchHit>, Boolean) -> Unit,
) {
    val needle = q.lowercase()
    val out = ArrayList<SearchHit>()
    var truncated = false
    if (p.loc.kind == Kind.HOST) {
        withContext(Dispatchers.IO) {
            val queue = ArrayDeque<File>()
            queue.add(File(p.path))
            var seen = 0
            while (queue.isNotEmpty() && out.size < SEARCH_MAX) {
                val d = queue.removeFirst()
                val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
                for (f in kids) {
                    seen++
                    if (f.name.lowercase().contains(needle)) {
                        out += SearchHit(
                            name = f.name,
                            dir = d.absolutePath,
                            isDir = f.isDirectory,
                            size = if (f.isDirectory) 0L else f.length(),
                            mtime = f.lastModified() / 1000,
                        )
                        if (out.size >= SEARCH_MAX) { truncated = true; break }
                    }
                    if (f.isDirectory) queue.add(f)
                    if (seen > 100_000) { truncated = true; break }
                }
            }
        }
    } else {
        val queue = ArrayDeque<String>()
        queue.add(p.path)
        var dirs = 0
        while (queue.isNotEmpty() && out.size < SEARCH_MAX && dirs < 400) {
            val d = queue.removeFirst()
            dirs++
            val l = runCatching { query(p, 0, s, pathOverride = d) }.getOrNull() ?: continue
            for (e in l.list) {
                if (e.name.lowercase().contains(needle)) {
                    out += SearchHit(e.name, d, e.dir, e.size, e.mtime)
                    if (out.size >= SEARCH_MAX) { truncated = true; break }
                }
                if (e.dir) queue.add(join(d, e.name))
            }
            if (l.more) truncated = true
        }
        if (dirs >= 400) truncated = true
    }
    onFind(out, truncated)
}

/** WIM/ESD 里的一个映像（`wim.identify` 的 `images[]`）。 */
private data class WimImage(
    val index: Int,
    val name: String,
    val edition: String,
    val version: String,
    val build: Int,
    val arch: String,
    val bytes: Long,
    val files: Int,
    val dirs: Int,
    val langs: String?,
)

/** 一次"搬到另一栏"的完整计划（先算好路径，冲突决策后再执行）。 */
private data class TransferJob(
    val move: Boolean,
    val from: Int,
    val to: Int,
    /** 绝对路径对：源 → 目标。 */
    val items: List<Pair<String, String>>,
    /** 源和目标同一个路径（自己目录里复制自己）→ 强制顺延成 (1)。 */
    val duplicates: Set<String> = emptySet(),
)

/**
 * 宿主文件是不是「稀疏」的（st_blocks 远小于 st_size）。
 *
 * 只在设置=「每次询问」时用来决定"要不要问一句"：不稀疏的文件问了也没意义。
 * 门槛 64 MiB：小文件即使稀疏，实体化也浪费不了多少；大文件才值得打扰用户。
 */
private fun isSparseHostFile(path: String): Boolean = runCatching {
    val st = android.system.Os.stat(path)
    val size = st.st_size
    val blocks = st.st_blocks * 512L
    size >= (64L shl 20) && blocks * 2 < size
}.getOrDefault(false)

/** 属性卡里要不要按"映像"看（决定显示容量/占用还是文件大小）。 */
private fun looksLikeImage(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in
        setOf("qcow2", "raw", "vhd", "vmdk", "img", "dd", "bin")

/** 系统回收站的前缀（MediaProvider 也是这么命名的）。 */
private const val TRASH_MARK = ".trashed-"

/** MediaStore 只收媒体；其它类型只能我们自己做回收站。 */
private val MEDIA_EXT = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    "mp4", "mkv", "avi", "mov", "3gp", "webm",
    "mp3", "flac", "wav", "ogg", "m4a", "aac", "amr",
)

private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "tgz", "tar.gz", "gz", "xz", "bz2", "cab")

/** 能往里写的压缩包格式（`archive.ls` 的 `format`；其余一律只读）。 */
private val ARCHIVE_WRITABLE = setOf("zip", "7z", "tar", "tgz", "tar.gz", "pax")

private fun isMediaName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in MEDIA_EXT

/** 回收站文件名 → 原名；不是回收站文件返回 null。 */
private fun unTrashName(name: String): String? {
    if (!name.startsWith(TRASH_MARK)) return null
    val rest = name.removePrefix(TRASH_MARK)
    val dash = rest.indexOf('-')
    if (dash <= 0) return null
    rest.substring(0, dash).toLongOrNull() ?: return null
    return rest.substring(dash + 1).ifEmpty { null }
}

/** 重命名时要自动选中的长度：最后一个 `.` 之前（`.gitignore` 这种整名选）。 */
private fun stemLength(name: String): Int {
    val i = name.lastIndexOf('.')
    return if (i > 0) i else name.length
}

private fun extOf(name: String): String = name.substringAfterLast('.', "")

/** 在 [dir] 里找一个不重名的 `base (n).ext`。 */
private fun freeName(dir: String, base: String): String {
    var p = join(dir, base)
    if (!File(p).exists()) return p
    val stem = base.substringBeforeLast('.', base)
    val ext = base.substringAfterLast('.', "").let { if (it.isEmpty() || it == base) "" else ".$it" }
    var i = 1
    while (File(p).exists()) {
        p = join(dir, "$stem ($i)$ext")
        i++
    }
    return p
}

/** shell 侧"跳过"的哨兵返回值。 */
private const val SHELL_SKIP = "SKIP"

/** 栈里的一个位置（返回时还原）。 */
private data class Browse(val loc: Loc, val path: String)

/** 一栏的全部状态；`mutableStateOf` 委托，改了就重组。 */
private class PaneState(loc: Loc, path: String) {
    var loc by mutableStateOf(loc)
    var path by mutableStateOf(path)
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var total by mutableStateOf(0)
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var err by mutableStateOf<String?>(null)
    var sel by mutableStateOf<Set<String>>(emptySet())
    var filesCount by mutableStateOf(-1)
    var dirsCount by mutableStateOf(-1)

    /** 进容器的路径栈（栈底是宿主目录）。返回时逐级 pop。 */
    val stack = ArrayList<Browse>()

    /** 压缩包的 `index_token`（10 分钟有效，省一次全包扫描）。 */
    var arcToken: String? = null

    /** 进 qcow2 之后那层分区列表。 */
    var parts by mutableStateOf<List<PartRow>>(emptyList())
    var partsLoading by mutableStateOf(false)

    /** 正在后台把剩余分页拉完（"全部渲染"，用户不用点展开）。 */
    var loadingMore by mutableStateOf(false)

    /** 压缩包是不是固实的（RAR solid）：只用于在副标题上提示"解压会慢"。 */
    var arcSolid by mutableStateOf(false)

    /** `iso.probe` 探到的格式（udf / iso9660 / joliet）与块大小，只用于显示。 */
    var isoKind by mutableStateOf<String?>(null)
    var isoLb by mutableStateOf(0)

    /** 进 WIM/ESD 之后那一层：映像索引列表。 */
    var wimImages by mutableStateOf<List<WimImage>>(emptyList())
    var wimLoading by mutableStateOf(false)

    /** 压缩包格式（`archive.ls` 的 `format`）；决定能不能往里粘贴/删除。 */
    var arcFormat by mutableStateOf<String?>(null)

    /**
     * 这一层能不能写。
     *
     * 压缩包：zip / 7z / tar 工具端能写（整包重写），rar / cab / iso9660 只能读。
     * 还没列过（`arcFormat == null`）时先乐观一次，真不行工具端会回 `PATH-READONLY`。
     */
    fun writableHere(): Boolean = when (loc.kind) {
        Kind.HOST, Kind.NTFS, Kind.FAT -> true
        Kind.ARCHIVE -> arcFormat == null || arcFormat in ARCHIVE_WRITABLE
        else -> false
    }
}

private data class Listing(
    val list: List<Entry>,
    val total: Int,
    val more: Boolean,
    val files: Int = -1,
    val dirs: Int = -1,
    val token: String? = null,
    /** 压缩包才有：`zip` / `7z` / `rar` / `tar` / `cab` … */
    val format: String? = null,
    /** RAR 才有：固实包（解压会从第一个文件顺序解，慢）。 */
    val solid: Boolean = false,
)

/** 长按卡里的动作。顺序就是菜单顺序。 */
private enum class Act { Copy, Move, Rename, Delete, Restore, Pack, Props }

/** 属性统计的中间结果。 */
private data class PropsState(
    val title: String,
    val files: Int = 0,
    val dirs: Int = 0,
    val bytes: Long = 0,
    /** 占用空间（只有 NTFS 给；0 = 不知道，不显示）。 */
    val alloc: Long = 0,
    val scanned: Int = 0,
    val truncated: Boolean = false,
    val done: Boolean = false,
    val err: String? = null,
    /** 映像是"容量 / 占用"两件事（A5）；非映像为 null。 */
    val capBytes: Long? = null,
    val usedBytes: Long? = null,
    val allocKnown: Boolean = true,
)

@Composable
fun FilesScreen(
    onToast: (String) -> Unit,
    active: Boolean = true,
    showTrash: Boolean = false,
    onShowTrash: (Boolean) -> Unit = {},
    deleteToTrash: Boolean = false,
    conflictPolicy: String = "ask",
    onConflictPolicy: (String) -> Unit = {},
    /** raw 复制行为（设置页）：keep | full | ask。空 = keep。 */
    rawCopyMode: String = "keep",
    /**
     * 打开内置文本编辑器（外层当二级页弹）。
     * @param path 要编辑的文件
     * @param readOnlyOrigin true = 从只读位置取出来的副本（保存时要挑可写位置）
     * @param fromPane 从哪一栏打开的
     */
    onOpenEditor: (String, Boolean, Int) -> Unit = { _, _, _ -> },
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 长任务（压缩 / 跨容器复制）的实时进度；工具端没推就只显示那句话
    val prog by ToolEvents.progress.collectAsState()
    val density = LocalDensity.current
    val statusBarTop = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    }

    // 「管理所有文件」：启动时已经问过一次；用户当时拒了的话，进到文件页再问一次。
    // 不用 active 本身当条件 —— 横向滑动会让它反复真假，那样会连环弹；
    // 这里记一个「本次进页已问过」的标记，一次会话里最多打扰一次。
    var permAsked by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active && !permAsked && !StorageAccess.hasAllFiles()) {
            permAsked = true
            StorageAccess.requestAllFiles(ctx)
        }
    }

    val left = remember { PaneState(Loc(Kind.HOST, label = s.filesHost), "/storage/emulated/0") }
    val right = remember { PaneState(Loc(Kind.HOST, label = s.filesHost), "/storage/emulated/0") }
    var focus by remember { mutableIntStateOf(0) }   // 默认焦点在左栏
    // 多选**钉**在哪一栏：多选只在发起的那一栏生效；切到对面 = 正常浏览（进目录/开文件），
    // 多选集原地不动 —— 这样才腾得出手去对面翻到目标目录（用户 2026-10-29 要求）。
    var multiPane by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    // 长按一行的动作菜单（复制/剪切/重命名/删除/压缩/属性）
    var menuFor by remember { mutableStateOf<Pair<Int, Entry>?>(null) }
    var gotoFor by remember { mutableStateOf(-1) }
    var menuOpen by remember { mutableStateOf(false) }      // 标题栏右上角 ⋮
    var multi by remember { mutableStateOf(false) }          // 多选模式
    var renameFor by remember { mutableStateOf<Pair<Int, Entry>?>(null) }
    // 改了后缀名的确认（等用户在卡片里点"仍然改名"）
    var pendingRename by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    // 同名冲突：等用户在弹窗里选（覆盖 / 保留两个 / 跳过），先记住这次要搬什么
    var pendingJob by remember { mutableStateOf<TransferJob?>(null) }
    var pendingConflictName by remember { mutableStateOf("") }
    /** 设置=「每次询问」时，先问一句再搬（T4/A3）。 */
    var pendingSparseJob by remember { mutableStateOf<TransferJob?>(null) }
    var pendingFatJob by remember { mutableStateOf<TransferJob?>(null) }
    // 选 WIM/ESD 的映像索引
    // ── 搜索（⋮ 菜单里的"搜索"）──
    // 范围 = 聚焦栏的**当前目录及其子目录**；宿主目录走 Java 遍历，容器内走逐层 ls（BFS）。
    var searchOpen by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searchRun by remember { mutableStateOf(false) }
    var searchTrunc by remember { mutableStateOf(false) }
    // ── 长任务：阻塞式进度卡（复制/移动/压缩/删除）──
    var opJob by remember { mutableStateOf<Job?>(null) }
    var opDone by remember { mutableIntStateOf(0) }
    var opTotal by remember { mutableIntStateOf(0) }
    var opBytes by remember { mutableStateOf(0L) }
    var opBytesTotal by remember { mutableStateOf(0L) }

    // 点回收站里的文件 → 问一句"还原吗"（不再直接"打开"）
    var restoreAsk by remember { mutableStateOf<Pair<Int, Entry>?>(null) }

    // 长按索引行 → 映像信息卡（不弹"选择"卡，直接进列表）
    var wimInfo by remember { mutableStateOf<WimImage?>(null) }
    // 索引列表最下面那条「XML 元数据」，点开是全部映像的原文摘要
    var wimXmlOpen by remember { mutableStateOf(false) }
    // 属性统计
    var props by remember { mutableStateOf<PropsState?>(null) }
    var propsJob by remember { mutableStateOf<Job?>(null) }
    // 压缩到…
    var packFor by remember { mutableStateOf<Pair<Int, Set<String>>?>(null) }

    fun pane(i: Int): PaneState = if (i == 0) left else right

    /** 复制/移动/删除/压缩作用在哪一栏：多选时永远是"钉住"的那栏。 */
    fun actPane(): Int = if (multi && pane(multiPane).sel.isNotEmpty()) multiPane else focus

    // 删除 → 系统回收站（MediaStore.createTrashRequest）需要等系统返回
    var pendingTrash by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val trashLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        val pend = pendingTrash
        pendingTrash = null
        busy = null
        val pg = if (pend?.first == 0) left else right
        if (res.resultCode == android.app.Activity.RESULT_OK && pend != null) {
            pg.sel = emptySet()
            onToast(s.filesDone(pend.second))
            scope.launch { reload(pg, s) }
        } else {
            onToast(s.cancel)
        }
    }

    // 切到别的 tab / 页面开始横滑就收回 ⋮ 菜单（卡片是页面内浮层，不会自己消失）
    LaunchedEffect(active) {
        if (!active) menuOpen = false
    }

    // 换目录就清选择：否则"已选 3"会跟到别的文件夹
    LaunchedEffect(left.loc, left.path) {
        left.sel = emptySet()
        reload(left, s)
    }
    LaunchedEffect(right.loc, right.path) {
        right.sel = emptySet()
        reload(right, s)
    }

    // ── 容器进出 ──

    fun enter(p: PaneState, loc: Loc, path: String = "/") {
        p.stack.add(Browse(p.loc, p.path))
        p.loc = loc
        p.path = path
        p.arcToken = null
        p.parts = emptyList()
    }

    fun pop(p: PaneState) {
        val b = p.stack.removeLastOrNull() ?: return
        p.loc = b.loc
        p.path = b.path
        p.arcToken = null
        p.parts = emptyList()
    }

    /** 进分区：从"分区列表"这一层落到具体文件系统。 */
    fun enterPart(pi: Int, part: PartRow) {
        val p = pane(pi)
        if (!part.hasFs) {
            onToast(s.filesNoFs)
            return
        }
        val fs = (part.fs ?: "").uppercase()
        val kind = if ((part.fs ?: "").startsWith("fat", true)) Kind.FAT else Kind.NTFS
        enter(
            p,
            Loc(
                kind, img = p.loc.img, lba = part.lba,
                label = s.filesPartLabel(part.index, fs),
                partIndex = part.index,
            ),
        )
    }

    /** 从索引列表进某个映像。 */
    fun enterWim(pi: Int, w: WimImage) {
        val p = pane(pi)
        enter(
            p,
            Loc(
                Kind.WIM,
                wim = p.loc.wim,
                inner = p.loc.inner,
                index = w.index,
                label = s.wimIdxRow(w.index) + " · " + w.name,
            ),
        )
    }

    /** 改名落地（后缀确认之后也走这里）。 */
    fun doRename(p: PaneState, e: Entry, clean: String) {
        scope.launch {
            busy = s.filesWorking
            val r = ToolRunner.exec(
                "rename",
                mapOf("path" to p.loc.spec(join(p.path, e.name)), "name" to clean),
                imagePath = null,
                writable = true,
                imageFd = -1,
            )
            busy = null
            onToast(if (r.ok) s.filesDone(1) else toolErrorText(s, r))
            reload(p, s)
        }
    }

    /** ↑ 按钮：上一级；容器根就 pop；到宿主 `/` 就什么都不做。 */
    fun upStep(pi: Int) {
        val p = pane(pi)
        if (multi) {
            multi = false
            left.sel = emptySet()
            right.sel = emptySet()
        }
        when {
            p.loc.kind == Kind.IMAGE -> pop(p)
            p.loc.kind != Kind.HOST && p.path == "/" -> pop(p)
            p.path != "/" -> p.path = p.path.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    /** 返回键：真返回 false 表示"这一层交回系统"（退到桌面）。 */
    fun backStep(): Boolean {
        if (multi) {
            multi = false
            left.sel = emptySet()
            right.sel = emptySet()
            return true
        }
        val p = pane(focus)
        if (p.loc.kind == Kind.HOST) {
            if (p.path == "/") return false
            p.path = p.path.substringBeforeLast('/').ifEmpty { "/" }
            return true
        }
        if (p.loc.kind != Kind.IMAGE && p.path != "/") {
            p.path = p.path.substringBeforeLast('/').ifEmpty { "/" }
            return true
        }
        if (p.stack.isNotEmpty()) {
            pop(p)
            return true
        }
        return false
    }

    val canBack: Boolean = multi ||
        pane(focus).path != "/" ||
        pane(focus).loc.kind == Kind.IMAGE ||
        pane(focus).stack.isNotEmpty()

    BackHandler(enabled = active && canBack) { backStep() }

    /**
     * 左栏 → 右栏（或反过来）：**没有剪贴板**，选中的东西直接搬到另一栏当前目录。
     * 走的还是工具端 `copy` / `move`（`!` 路径语法），跨容器也不用中转。
     */
    /** 真正开搬。[policy] = ask 以外的值：rename / overwrite / skip。 */
    fun runTransfer(job: TransferJob, policy: String, sparseOverride: String? = null) {
        val src = pane(job.from)
        val dst = pane(job.to)
        val srcLoc = src.loc
        val dstLoc = dst.loc
        opJob = scope.launch {
            busy = if (job.move) s.filesMoving else s.filesCopying
            // 先把"要搬几个、多大"算出来；有目录时总量不可知（不当场 du），就只报已完成的大小
            opTotal = job.items.size
            opDone = 0
            opBytes = 0L
            opBytesTotal = run {
                var known = 0L
                var anyDir = false
                job.items.forEach { (srcAbs, _) ->
                    val nm = srcAbs.substringAfterLast('/')
                    val en = src.entries.firstOrNull { it.name == nm }
                    if (en == null || en.dir) anyDir = true else known += en.size
                }
                if (anyDir) 0L else known
            }
            var ok = 0
            var skippedTrash = 0
            var skippedName = 0
            var sparseLost = 0
            var sparseLostBytes = 0L
            var movedByRename = 0
            var fail: String? = null
            for ((srcAbs, dstAbs) in job.items) {
                if (!isActive) break
                val ent0 = src.entries.firstOrNull { it.name == srcAbs.substringAfterLast('/') }
                // 自己目录里复制自己 → 一律改名（不要去弹窗问，也不许覆盖源文件）
                val selfCopy = srcAbs == dstAbs
                val effPolicy = if (selfCopy) "rename" else policy

                // 目录复制到自己内部（真无限递归）→ 拒绝
                if (!selfCopy && File(srcAbs).isDirectory && isSameOrInside(dstAbs, srcAbs)) {
                    fail = s.filesCopyIntoSelf
                    break
                }

                val nameOfItem = srcAbs.substringAfterLast('/')
                val srcIsDir = src.entries.firstOrNull { it.name == nameOfItem }?.dir == true
                // 压缩包 → 宿主 的**整目录**：工具端 copy 明确不支持（COPY-UNSUPPORTED），改走 archive.extract
                if (srcLoc.kind == Kind.ARCHIVE && dstLoc.kind == Kind.HOST && srcIsDir) {
                    var target = dstAbs
                    if (File(target).exists()) {
                        when (effPolicy) {
                            "skip" -> {
                                skippedName++
                                continue
                            }
                            "rename" -> target = freeName(
                                dstAbs.substringBeforeLast('/').ifEmpty { "/" },
                                nameOfItem,
                            )
                        }
                    }
                    // 统一走 copy（REPLY-55）：archive.extract 对根目录会报 ARCHIVE-ENTRY-NOTFOUND
                    val r = ImageRepo.copyFrom(
                        src = srcLoc.spec(join(src.path, nameOfItem)),
                        dst = ImageRepo.hostPath(target),
                        recursive = true,
                    )
                    if (!r.ok) {
                        fail = toolErrorText(s, r)
                        break
                    }
                    ok++
                    opDone++
                    if (ent0 != null && !ent0.dir) opBytes += ent0.size
                    if (job.move) {
                        // 复制成功才删包内条目（删失败不算失败，包还在）
                        ToolRunner.exec(
                            "rm",
                            mapOf(
                                "path" to srcLoc.spec(join(src.path, nameOfItem)),
                                "recursive" to "true",
                            ),
                            imagePath = null,
                            writable = true,
                            imageFd = -1,
                        )
                    }
                    continue
                }

                val srcFile = File(srcAbs)
                val dstFile = File(dstAbs)
                // 只有"两边都是宿主"时才可能走 shell；容器里的路径 shell 碰不到
                val viaShell = srcLoc.kind == Kind.HOST && dstLoc.kind == Kind.HOST &&
                    ShizukuShell.granted() && (!appCanRead(srcFile) || !appCanWriteInto(dstFile))
                if (viaShell) {
                    when (val e = shellTransfer(srcAbs, dstAbs, job.move, effPolicy, s)) {
                        null -> {
                            ok++
                            opDone++
                            if (ent0 != null && !ent0.dir) opBytes += ent0.size
                        }
                        SHELL_SKIP -> skippedName++
                        else -> {
                            fail = e
                            break
                        }
                    }
                    continue
                }

                val args = mutableMapOf(
                    "src" to srcLoc.spec(join(src.path, srcAbs.substringAfterLast('/'))),
                    "dst" to dstLoc.spec(join(dst.path, dstAbs.substringAfterLast('/'))),
                    "recursive" to "true",
                    "create_parents" to "true",
                    "conflict" to when (effPolicy) {
                        "overwrite" -> "overwrite"
                        "skip" -> "fail"                    // fail = 已存在就报错，我们当成"跳过"
                        else -> "rename"
                    },
                )
                // ★ 只对 copy 传、且用中划线：move 没这个参数（写 skip_trashed 会 CMD-ARG-BAD）
                // ★ T4/A3：raw 映像的稀疏策略。keep/ask 默认 auto（源有洞就保持），
                //   full = never（用户明确要"完整副本"）。
                args["sparse"] = sparseOverride
                    ?: if (rawCopyMode == "full") "never" else "auto"
                if (!job.move && srcLoc.kind == Kind.HOST) args["skip-trashed"] = "true"
                if (job.move) args["allow_host_delete"] = (srcLoc.kind == Kind.HOST).toString()

                val r = ToolRunner.exec(
                    if (job.move) "move" else "copy",
                    args,
                    imagePath = null,
                    writable = true,
                    imageFd = -1,
                )
                when {
                    r.ok -> {
                        ok++
                        opDone++
                        if (ent0 != null && !ent0.dir) opBytes += ent0.size
                        skippedTrash += r.data?.optInt("skipped_trashed", 0) ?: 0
                        // ★ A3：源是稀疏 raw 而目标没保住洞 → 必须如实说出来（别让用户
                        //   以为文件"莫名其妙变大了"）。
                        if (r.data?.optBoolean("sparse_source", false) == true &&
                            r.data?.optBoolean("sparse_kept", true) == false
                        ) {
                            sparseLost++
                            sparseLostBytes += r.data?.optLong("out_alloc_bytes", 0L) ?: 0L
                        }
                        if (r.data?.optString("moved_by") == "rename") movedByRename++
                    }
                    // 跳过策略下"目标已存在"是预期结果，不算失败
                    effPolicy == "skip" && (r.code ?: "").contains("EXIST") -> skippedName++
                    else -> {
                        fail = toolErrorText(s, r)
                        break
                    }
                }
            }
            busy = null
            opJob = null
            src.sel = emptySet()
            onToast(
                fail ?: when {
                    sparseLost > 0 -> s.filesSparseLost(sizeText(sparseLostBytes))
                    skippedTrash > 0 -> s.filesDoneSkip(ok, skippedTrash)
                    skippedName > 0 -> s.filesDoneSkipName(ok, skippedName)
                    job.move && movedByRename == ok && ok > 0 -> s.filesMovedByRename
                    else -> s.filesDone(ok)
                },
            )
            if (ok > 0) {
                reload(dst, s)
                if (job.move) reload(src, s)
            }
        }
    }

    /**
     * 编辑器写完只读副本 → 把它**存到对面那一栏**的当前目录。
     * 对面可以是手机目录，也可以是 qcow2 里的 NTFS/FAT（工具端 copy 的 dst 认容器）。
     */
    fun savePendingCopy(fromPane: Int) {
        val src = org.icdqcow2.ui.EditorBridge.saveTo ?: return
        val dstPane = pane(if (fromPane == 0) 1 else 0)
        val nm = File(src).name
        if (!dstPane.writableHere()) {
            onToast(s.filesReadonly)
            return
        }
        focus = if (fromPane == 0) 1 else 0
        scope.launch {
            busy = s.filesCopying
            val r = ToolRunner.exec(
                "copy",
                mapOf(
                    "src" to "host:" + src,
                    "dst" to dstPane.loc.spec(join(dstPane.path, nm)),
                    "create_parents" to "true",
                    "conflict" to "rename",
                ),
                imagePath = null,
                writable = true,
                imageFd = -1,
            )
            busy = null
            org.icdqcow2.ui.EditorBridge.saveTo = null
            if (r.ok) {
                onToast(s.filesDone(1))
                reload(dstPane, s)
            } else {
                onToast(toolErrorText(s, r))
            }
        }
    }

    /**
     * 复制/移动到另一栏：先把「要搬什么」算好，再看有没有同名冲突。
     */
    fun planTransfer(move: Boolean) {
        val fromIdx = actPane()
        val src = pane(fromIdx)
        if (src.sel.isEmpty()) {
            onToast(s.filesPickOne)
            return
        }
        val toIdx = if (fromIdx == 0) 1 else 0
        val dst = pane(toIdx)
        if (!dst.writableHere()) {
            onToast(
                if (dst.loc.kind == Kind.ARCHIVE) s.filesZipReadonly else s.filesReadonly,
            )
            return
        }
        if (src.loc.kind == Kind.IMAGE) {
            onToast(s.filesPickOne)
            return
        }
        val items = src.sel.toList().map { n ->
            val sRaw = join(src.path, n)
            val dRaw = join(dst.path, n)
            val sAbs = if (src.loc.kind == Kind.HOST) realPath(sRaw) else sRaw
            val dAbs = if (dst.loc.kind == Kind.HOST) realPath(dRaw) else dRaw
            sAbs to dAbs
        }
        val hostBoth = src.loc.kind == Kind.HOST && dst.loc.kind == Kind.HOST
        // 自己目录里复制自己（src == dst）不算冲突，直接顺延成 "(1)"（用户明确要求）
        val dup = items.filter { (a, b) -> a == b }.map { it.first }.toSet()
        val clash = if (hostBoth) items.filter { (a, b) -> a != b && File(b).exists() } else emptyList()
        val job = TransferJob(move, focus, toIdx, items, dup)
        // ★ §2.2：目标是 FAT32/exFAT 卷 → 不支持稀疏文件，宿主稀疏文件复制进去会按完整容量写。
        //   与 A3 的「宿主→宿主询问」并存：那边问保不保稀疏，这边直接告知必然实体化。
        if (dst.loc.kind == Kind.FAT && items.any { isSparseHostFile(it.first) }) {
            pendingFatJob = job
            return
        }
        // ★ A3：设置里选了「每次询问」且源里确实有稀疏的宿主文件 → 先问一句。
        //   问的是「保持稀疏 / 按完整容量」——这两个结果对手机存储是天壤之别。
        if (rawCopyMode == "ask" && hostBoth && items.any { isSparseHostFile(it.first) }) {
            pendingSparseJob = job
            return
        }
        if (clash.isNotEmpty() && conflictPolicy == "ask") {
            pendingJob = job
            pendingConflictName = clash.first().second.substringAfterLast('/')
            return
        }
        runTransfer(job, conflictPolicy)
    }

    fun deleteSelected() {
        val p = pane(focus)
        if (p.sel.isEmpty()) {
            onToast(s.filesPickOne)
            return
        }
        if (!p.writableHere()) {
            onToast(if (p.loc.kind == Kind.ARCHIVE) s.filesZipReadonly else s.filesReadonly)
            return
        }
        confirmDel = true
    }

    /**
     * 自己实现的回收站：`mv` 成 `.trashed-<到期时间>-原名`（和 MediaProvider 同名规则）。
     * 目录也走这条路（整目录一次改名，不递归改内层）。
     * 返回 (成功数, 错误文案)。
     */
    suspend fun trashNames(p: PaneState, names: List<String>): Pair<Int, String?> {
        var done = 0
        var fail: String? = null
        val expiry = System.currentTimeMillis() / 1000 + 30L * 24 * 3600
        for (n in names) {
            val dst = freeName(p.path, "$TRASH_MARK$expiry-$n")
            val r = ToolRunner.exec(
                "move",
                mapOf(
                    "src" to "host:" + join(p.path, n),
                    "dst" to "host:$dst",
                    "allow_host_delete" to "true",
                    "conflict" to "rename",
                ),
                imagePath = null,
                writable = true,
                imageFd = -1,
            )
            if (r.ok) done++ else {
                fail = toolErrorText(s, r)
                break
            }
        }
        return done to fail
    }

    /** 从回收站还原：去掉 `.trashed-<时间>-` 前缀（重名就顺延）。 */
    fun restoreTrash(p: PaneState, e: Entry) {
        val orig = unTrashName(e.name) ?: return
        scope.launch {
            busy = s.filesWorking
            val dst = freeName(p.path, orig)
            val r = ToolRunner.exec(
                "move",
                mapOf(
                    "src" to "host:" + join(p.path, e.name),
                    "dst" to "host:$dst",
                    "allow_host_delete" to "true",
                    "conflict" to "rename",
                ),
                imagePath = null,
                writable = true,
                imageFd = -1,
            )
            busy = null
            onToast(if (r.ok) s.filesRestored else toolErrorText(s, r))
            reload(p, s)
        }
    }

    fun runDelete() {
        val p = pane(actPane())
        val names = p.sel.toList()
        if (names.isEmpty()) return

        // ── 回收站 ──
        // 系统 API（MediaStore.createTrashRequest）**只收媒体文件**，非媒体会抛
        // IllegalArgumentException；目录也不收。所以：
        //   · 全是媒体文件（且没有回收站项）→ 走系统（能在系统「回收站」里还原）；
        //   · 其余（目录 / 非媒体）→ 我们自己按同一套命名规则改名，观感一致；
        //   · 已经在回收站里的 → 就是"彻底删除"，不再套一层 .trashed-。
        val inTrash = names.filter { isTrashed(it) }
        val normal = names.filterNot { isTrashed(it) }
        val canTrash = deleteToTrash && p.loc.kind == Kind.HOST && normal.isNotEmpty()

        if (canTrash && inTrash.isEmpty()) {
            val onlyFiles = normal.all { n -> p.entries.firstOrNull { it.name == n }?.dir == false }
            if (onlyFiles && normal.all { isMediaName(it) }) {
                val uris = normal.mapNotNull { n -> mediaUriOf(ctx, join(p.path, n)) }
                if (uris.size == normal.size) {
                    val sender = runCatching {
                        MediaStore.createTrashRequest(ctx.contentResolver, uris, true).intentSender
                    }.getOrNull()
                    if (sender != null) {
                        busy = s.filesWorking
                        pendingTrash = focus to normal.size
                        trashLauncher.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                        )
                        return
                    }
                }
            }
        }

        opJob = scope.launch {
            busy = s.filesDeleting
            opTotal = names.size
            opDone = 0
            opBytes = 0L
            opBytesTotal = 0L
            var trashedOk = 0
            var purgedOk = 0
            var fail: String? = null

            if (canTrash) {
                val (n, e) = trashNames(p, normal)
                trashedOk = n
                fail = e
            }
            val purge = if (canTrash) inTrash else names
            if (fail == null) {
                for (n in purge) {
                    if (!isActive) break
                    suspend fun rmAs(name: String) = ToolRunner.exec(
                        "rm",
                        mapOf(
                            "path" to p.loc.spec(join(p.path, name)),
                            "recursive" to "true",
                            // 宿主文件删除同理：UI 已经二次确认过，这里才敢开
                            "allow_host_delete" to (p.loc.kind == Kind.HOST).toString(),
                        ),
                        imagePath = null,
                        writable = true,
                        imageFd = -1,
                    )
                    var r = rmAs(n)
                    // ★ FAT32 的坑：`fat.ls` 显示的名字和 `rm` 查找的名字**大小写规则不一致**
                    //   （带 NT 小写标志的 8.3 短名，ls 印大写、rm 按小写找 → PATH-SRC-NOTFOUND）。
                    //   FAT 本来就是大小写不敏感的，所以这里直接换个大小写再试一次。
                    if (!r.ok && (r.code ?: "").contains("NOTFOUND") &&
                        (p.loc.kind == Kind.FAT || p.loc.kind == Kind.NTFS)
                    ) {
                        val alt = if (n == n.uppercase()) n.lowercase() else n.uppercase()
                        if (alt != n) {
                            val r2 = rmAs(alt)
                            if (r2.ok) r = r2
                        }
                    }
                    if (r.ok) {
                        purgedOk++
                        opDone++
                    } else {
                        fail = toolErrorText(s, r)
                        break
                    }
                }
            }
            busy = null
            opJob = null
            p.sel = emptySet()
            onToast(
                fail ?: when {
                    trashedOk > 0 && purgedOk > 0 -> s.filesDone(trashedOk + purgedOk)
                    trashedOk > 0 -> s.filesTrashed(trashedOk)
                    else -> s.filesDone(purgedOk)
                },
            )
            reload(p, s)
        }
    }

    // ── 打开一个条目（容器进容器，普通文件交系统）──

    fun openWithSystem(f: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }.getOrNull() ?: run { onToast(s.filesNoApp); return }
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extOf(f.name).lowercase()) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        runCatching { ctx.startActivity(intent) }.onFailure { onToast(s.filesNoApp) }
    }

    /**
     * 容器（qcow2 分区 / ISO / WIM / 压缩包）里的**单个文件**怎么"打开"：
     * 先用对应命令把它**取到缓存目录**，再交给系统的 ACTION_VIEW。
     *
     * 为什么不是直接读：容器里的文件不是手机文件系统里的普通文件，没有路径可以递给别的 App；
     * 只有先落地一份才行（`ntfs.export` / `fat.export` / `iso.extract` / `wim.extract` / `archive.extract`）。
     * ⚠ 取出来的只是**副本**：改它不会写回容器（要写回得走"放回去"，见 s.filesOpenNoEditor）。
     */
    fun previewEntry(pi: Int, e: Entry) {
        val p = pane(pi)
        val loc = p.loc
        val inner = join(p.path, e.name)
        scope.launch {
            busy = s.filesExtracting
            try {
                val outDir = File(TempDirs.stagingDir(ctx), "preview").apply { mkdirs() }
                val dst = File(outDir, e.name)
                runCatching { if (dst.exists()) dst.delete() }
                val r = when (loc.kind) {
                    Kind.NTFS, Kind.FAT -> ToolRunner.exec(
                        if (loc.kind == Kind.NTFS) "ntfs.export" else "fat.export",
                        mapOf(
                            "lba" to loc.lba.toString(),
                            "path" to inner,
                            "dst" to dst.absolutePath,
                        ),
                        imagePath = loc.img,
                        writable = true,
                        imageFd = -1,
                    )
                    // 统一走 copy（REPLY-55）：ISO / WIM / 压缩包同一套语法
                    Kind.ISO, Kind.WIM, Kind.ARCHIVE -> ImageRepo.copyFrom(
                        src = loc.spec(inner),
                        dst = ImageRepo.hostPath(dst.absolutePath),
                    )
                    else -> null
                }
                if (r == null) {
                    onToast(s.filesNoPreview)
                    return@launch
                }
                if (!r.ok) {
                    onToast(toolErrorText(s, r))
                    return@launch
                }
                // 取出来的是纯文本 → 直接用内置编辑器打开（不再甩给别的应用）；
                // 编辑器保存时会走"挑一个可写位置"那条路（副本不能写回只读容器）。
                if (isTextName(dst.name)) {
                    onOpenEditor(dst.absolutePath, true, pi)
                } else {
                    openWithSystem(dst)
                }
            } catch (t: Throwable) {
                onToast(t.message ?: s.filesNoPreview)
            } finally {
                busy = null
            }
        }
    }

    fun openEntry(pi: Int, e: Entry) {
        val p = pane(pi)
        val abs = join(p.path, e.name)
        runCatching {
            android.util.Log.i(
                "IcdqcowFiles",
                "openEntry pane=$pi kind=${p.loc.kind} path=$abs size=${e.size}",
            )
        }
        when (p.loc.kind) {
            Kind.HOST -> {
                val f = File(abs)
                // 纯文本直接进内置编辑器（容器里的文件请先"打开"取出来，那份在缓存里）
                if (!f.isDirectory && isTextName(f.name) && f.length() <= 8L * 1024 * 1024) {
                    onOpenEditor(abs, false, pi)
                    return
                }
                when (sniffContainer(f)) {
                    "qcow2" -> enter(p, Loc(Kind.IMAGE, img = abs, label = f.name))
                    "iso" -> {
                        // 先 `iso.probe`（只读几个描述符块，不列目录）：能不能进、是 UDF 还是 ISO9660
                        if (!ToolCaps.has("iso.probe")) {
                            enter(p, Loc(Kind.ISO, iso = abs, label = f.name))
                        } else {
                            scope.launch {
                                busy = s.filesWorking
                                val r = ToolRunner.exec(
                                    "iso.probe",
                                    mapOf("iso" to abs),
                                    imagePath = null,
                                    writable = false,
                                    imageFd = -1,
                                )
                                busy = null
                                if (!r.ok) {
                                    onToast(toolErrorText(s, r))
                                    return@launch
                                }
                                p.isoKind = r.data?.optString("kind")?.takeIf { it.isNotBlank() }
                                p.isoLb = r.data?.optInt("lb_size", 0) ?: 0
                                enter(p, Loc(Kind.ISO, iso = abs, label = f.name))
                            }
                        }
                    }
                    "wim" -> enter(p, Loc(Kind.WIMIDX, wim = abs, label = f.name))
                    "archive" -> if (f.length() <= 0L) {
                        // 0 字节不是"空包"，是坏文件：直接说清楚，别进去看到一个空列表
                        onToast(s.filesEmptyArchive)
                    } else {
                        enter(p, Loc(Kind.ARCHIVE, arc = abs, label = f.name))
                    }
                    else -> openWithSystem(f)
                }
            }
            Kind.ISO -> {
                // ISO 只读：里面的 wim/esd 还能再进一层（工具端支持 --wim=<iso> --inner=）
                if (sniffName(e.name) == "wim") {
                    enter(
                        p,
                        Loc(Kind.WIMIDX, wim = p.loc.iso ?: return, inner = abs, label = e.name),
                    )
                }
                else previewEntry(pi, e)
            }
            // 分区 / WIM / 压缩包里的文件：取一份到缓存再交给系统应用
            Kind.NTFS, Kind.FAT, Kind.WIM, Kind.ARCHIVE -> previewEntry(pi, e)
            else -> onToast(s.filesNoPreview)
        }
    }

    // ── 选择 / 焦点 ──

    fun rowClick(pi: Int, e: Entry) {
        val p = pane(pi)
        if (multi) {
            // 多选钉在 multiPane：那边只管勾选；**另一边照常浏览**（能进目录、能滚到目标位置）
            if (pi != multiPane) {
                focus = pi
                if (e.dir) {
                    p.path = join(p.path, e.name)
                } else {
                    p.sel = setOf(e.name)
                    openEntry(pi, e)
                }
                return
            }
            focus = pi
            p.sel = if (p.sel.contains(e.name)) p.sel - e.name else p.sel + e.name
            return
        }
        focus = pi
        // 回收站里的东西：点它不是"打开"，而是问一遍要不要还原
        if (isTrashed(e.name)) {
            p.sel = setOf(e.name)
            restoreAsk = pi to e
            return
        }
        if (e.dir) {
            // 双保险：回收站里的文件夹同样只问"还原吗"，绝不进去
            if (isTrashed(e.name)) {
                p.sel = setOf(e.name)
                restoreAsk = pi to e
                return
            }
            p.sel = emptySet()
            p.path = join(p.path, e.name)
        } else {
            p.sel = setOf(e.name)
            openEntry(pi, e)
        }
    }

    fun rowLongPress(pi: Int, e: Entry) {
        focus = pi
        if (!multi) pane(pi).sel = setOf(e.name)   // 单选：切到按住的这一项
        menuFor = pi to e                         // 多选：不动选择集
    }

    /** 复制/移动/删除/压缩作用在哪一栏：多选时永远是"钉住"的那栏。 */
    /** 长按菜单要作用的那批名字（多选时用已经选好的集）。 */
    fun menuTargets(pi: Int, e: Entry): Set<String> {
        val p = pane(pi)
        return if (multi && p.sel.isNotEmpty()) p.sel else setOf(e.name)
    }

    // ── 属性统计 ──

    fun startProps(pi: Int, names: Set<String>) {
        val p = pane(pi)
        val title = if (names.size == 1) names.first() else s.filesSelected(names.size)
        propsJob?.cancel()
        props = PropsState(title = title)
        propsJob = scope.launch {
            try {
                // ★ A5：映像文件（qcow2/raw/vhd/vmdk）显示「容量 · 占用」。
                //   raw 的 st_size 是容量，st_blocks 才是占用；只显示文件大小会把
                //   用户吓一跳（一个 64 GiB 的文件其实只占 1 MiB）。
                val one = names.singleOrNull()
                if (one != null && p.loc.kind == Kind.HOST && looksLikeImage(one)) {
                    val abs = realPath(join(p.path, one))
                    val r = ToolRunner.exec(
                        "img.info",
                        emptyMap(),
                        imagePath = abs,
                        writable = false,
                        imageFd = -1,
                    )
                    if (r.ok) {
                        val d = r.data
                        props = props?.copy(
                            capBytes = d?.optLong("logical_bytes", 0L) ?: 0L,
                            usedBytes = d?.optLong("alloc_bytes", 0L) ?: 0L,
                            allocKnown = d?.optBoolean("alloc_known", true) ?: true,
                        )
                    }
                }
                walkProps(p, names, s) { files, dirs, bytes, alloc, scanned, trunc ->
                    props = props?.copy(
                        files = files, dirs = dirs, bytes = bytes, alloc = alloc,
                        scanned = scanned, truncated = trunc,
                    )
                }
                props = props?.copy(done = true)
            } catch (c: CancellationException) {
                props = props?.copy(done = true, err = s.cancel)
            } catch (t: Throwable) {
                props = props?.copy(done = true, err = t.message)
            }
        }
    }

    // ── 压缩到… ──

    /**
     * 压缩：产物**写进对面那一栏的当前目录**（用户 2026-10-29 明确要求）。
     *
     * 三条前置，缺一条就不干（别跑一半才报错）：
     *  1. 对面必须是手机目录 —— 压缩包只能落在真实文件系统上；
     *  2. 对面那个目录得真的**有写权限**（`canWrite` / Shizuku 兜底），没有就直接拒绝；
     *  3. 源在**只读容器**里（ISO / WIM / 压缩包 / 只读分区）→ 先把选中的东西取到缓存，
     *     再从缓存打包（"只读文件先抽出来再压缩"）。
     */
    fun startPack(pi: Int, names: Set<String>, fmt: String, name: String) {
        val src = pane(pi)
        val toIdx = if (pi == 0) 1 else 0
        val dstPane = pane(toIdx)
        if (dstPane.loc.kind != Kind.HOST) {
            onToast(s.packDstHost)
            return
        }
        val dstDir = dstPane.path
        if (!appCanWriteInto(File(dstDir, "probe")) && !ShizukuShell.granted()) {
            onToast(s.packNoPerm)
            return
        }
        opJob = scope.launch {
            ToolEvents.clear()
            busy = s.filesPacking
            opTotal = names.size
            opDone = 0
            opBytes = 0L
            opBytesTotal = 0L
            var fail: String? = null
            val srcPaths = ArrayList<String>()
            val readOnlySrc = src.loc.kind != Kind.HOST

            if (readOnlySrc) busy = s.packExtracting
            for (n in names) {
                if (!isActive) break
                val inner = join(src.path, n)
                if (!readOnlySrc) {
                    // 宿主源：解析成真实路径（/sdcard 之类的符号链接工具端认不准）
                    srcPaths += realPath(inner)
                    opDone++
                    continue
                }
                // 只读容器：取一份到缓存
                val tmp = File(TempDirs.stagingDir(ctx), "pack").apply { mkdirs() }.resolve(n)
                runCatching { if (tmp.exists()) tmp.deleteRecursively() }
                val loc = src.loc
                val r = when (loc.kind) {
                    Kind.NTFS, Kind.FAT -> ToolRunner.exec(
                        "copy",
                        mapOf(
                            "src" to loc.spec(inner),
                            "dst" to "host:" + tmp.absolutePath,
                            "recursive" to "true",
                            "create_parents" to "true",
                        ),
                        imagePath = null,
                        writable = true,
                        imageFd = -1,
                    )
                    // 统一走 copy（REPLY-55）
                    Kind.ISO, Kind.WIM, Kind.ARCHIVE -> ImageRepo.copyFrom(
                        src = loc.spec(inner),
                        dst = ImageRepo.hostPath(tmp.absolutePath),
                    )
                    else -> null
                }
                if (r == null || !r.ok) {
                    fail = if (r == null) s.filesNoPreview else toolErrorText(s, r)
                    break
                }
                srcPaths += tmp.absolutePath
                opDone++
            }

            if (fail == null) {
                busy = s.filesPacking
                val dst = join(dstDir, name)
                val args = mutableMapOf(
                    "dst" to dst,
                    "format" to fmt,
                    // 多源只能靠逗号（JNI 传不了重复 key，见 REQ-42）；名字里的逗号要转义
                    "src" to srcPaths.joinToString(",") { it.replace(",", "\\,") },
                    "exclude" to ".trashed-*,*.tmp",
                    // 同名不覆盖：顺延成「名字 (1).zip」，返回值里的 dst 才是最终名字
                    "conflict" to "rename",
                )
                val r = ToolRunner.exec("archive.create", args, imagePath = null, writable = true, imageFd = -1)
                if (!r.ok) fail = toolErrorText(s, r)
                else {
                    val made = r.data?.optString("dst").orEmpty().ifEmpty { name }
                    onToast(s.filesZipDone(made.substringAfterLast('/')))
                }
            } else {
                onToast(fail)
            }
            busy = null
            opJob = null
            ToolEvents.clear()
            if (fail == null) reload(dstPane, s)
            // 缓存里那份不再需要（打包已完成）
            runCatching { File(TempDirs.stagingDir(ctx), "pack").deleteRecursively() }
        }
    }

    // 和其他两个 tab 同一套大标题页：折叠标题、渐变模糊、状态栏 inset 都由它兜住
    // 标题栏副标题：没选中报这一层有多少文件/文件夹，选中了报"共选择 N 个项目 · 总大小"
    val cur = pane(focus)
    // 栏里不再显示"容器文件名 / 只读"，这两样都并到页面副标题最后
    val tail = buildString {
        if (cur.loc.kind != Kind.IMAGE && cur.loc.kind != Kind.HOST && !cur.writableHere()) {
            append(" · ").append(s.filesReadonlyTag)
        }
        if (cur.loc.kind == Kind.ARCHIVE && cur.arcSolid) {
            append(" · ").append(s.filesSolid)
        }
        if (cur.loc.kind == Kind.ISO && !cur.isoKind.isNullOrBlank()) {
            append(" · ").append(cur.isoKind!!.uppercase())
            if (cur.isoLb > 0) append(' ').append(cur.isoLb).append('B')
        }
    }
    val infoBase = busy ?: if (cur.sel.isNotEmpty()) {
        var sum = 0L
        cur.entries.forEach { e -> if (cur.sel.contains(e.name) && !e.dir) sum += e.size }
        s.filesSelSum(cur.sel.size, sizeText(sum))
    } else if (cur.loc.kind == Kind.IMAGE) {
        s.filesCounts(0, cur.parts.size)
    } else if (cur.filesCount < 0) {
        s.filesTotal(cur.total)
    } else {
        // 隐藏回收站文件时，计数也要跟着扣掉，不然"12 个文件"和列表数量对不上
        val hidden = if (showTrash) 0 else cur.entries.count { !it.dir && isTrashed(it.name) }
        s.filesCounts(
            (cur.filesCount - hidden).coerceAtLeast(0),
            cur.dirsCount.coerceAtLeast(0),
        )
    }
    val info = infoBase + tail

    Box(Modifier.fillMaxSize()) {
        LargeTitleScaffold(
            title = s.filesTitle,
            subtitle = info,
            extendToBottom = true,
            actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(MiuixIcons.More, contentDescription = null)
                    }
                }
            },
        ) {


            // ── 双栏 ──
            Row(Modifier.fillMaxWidth().weight(1f)) {
                PaneView(
                    p = left,
                    focused = focus == 0,
                    s = s,
                    onFocus = { focus = 0 },
                    onGoto = { gotoFor = 0 },
                    onUp = { upStep(0) },
                    onRowClick = { e -> rowClick(0, e) },
                    onOpenPart = { part -> enterPart(0, part) },
                    onOpenWim = { w -> enterWim(0, w) },
                    onLongWim = { w -> wimInfo = w },
                    onWimXml = { wimXmlOpen = true },
                    saveBar = org.icdqcow2.ui.EditorBridge.saveTo != null && org.icdqcow2.ui.EditorBridge.pane == 0,
                    onSaveBarSave = { savePendingCopy(0) },
                    onSaveBarCancel = { org.icdqcow2.ui.EditorBridge.saveTo = null; focus = 0 },
                    onMore = { scope.launch { reload(left, s, more = true) } },
                    onLongPress = { e -> rowLongPress(0, e) },
                    multi = multi && multiPane == 0,
                    showTrash = showTrash,
                    side = 0,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
                )
                PaneView(
                    p = right,
                    focused = focus == 1,
                    s = s,
                    onFocus = { focus = 1 },
                    onGoto = { gotoFor = 1 },
                    onUp = { upStep(1) },
                    onRowClick = { e -> rowClick(1, e) },
                    onOpenPart = { part -> enterPart(1, part) },
                    onOpenWim = { w -> enterWim(1, w) },
                    onLongWim = { w -> wimInfo = w },
                    onWimXml = { wimXmlOpen = true },
                    saveBar = org.icdqcow2.ui.EditorBridge.saveTo != null && org.icdqcow2.ui.EditorBridge.pane == 1,
                    onSaveBarSave = { savePendingCopy(1) },
                    onSaveBarCancel = { org.icdqcow2.ui.EditorBridge.saveTo = null; focus = 1 },
                    onMore = { scope.launch { reload(right, s, more = true) } },
                    onLongPress = { e -> rowLongPress(1, e) },
                    multi = multi && multiPane == 1,
                    showTrash = showTrash,
                    side = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ⋮ 菜单：页面内浮层，顶边 = 状态栏高 + 16dp − 32dp
        CardDropdownTopEnd(
            show = menuOpen,
            items = listOf(
                s.filesSearch,
                s.filesInternal,
                s.filesMulti,
                if (showTrash) s.filesHideTrash else s.filesShowTrash,
            ),
            selectedIndex = if (multi) 2 else -1,
            topPadding = statusBarTop,
            endPadding = 16.dp,
            onDismiss = { menuOpen = false },
            onSelected = { i ->
                menuOpen = false
                when (i) {
                    0 -> {
                        searchQ = ""
                        searchHits = emptyList()
                        searchTrunc = false
                        searchOpen = true
                    }
                    1 -> {
                        val p = pane(focus)
                        p.stack.clear()
                        p.loc = Loc(Kind.HOST, label = s.filesHost)
                        p.path = "/storage/emulated/0"
                    }
                    2 -> {
                        multi = !multi
                        if (multi) {
                            multiPane = focus
                        } else {
                            left.sel = emptySet()
                            right.sel = emptySet()
                        }
                    }
                    else -> onShowTrash(!showTrash)
                }
            },
        )
    }

    // ── 同名冲突：覆盖 / 两个都保留 / 跳过（可勾"后续都这样"）──
    val pj = pendingJob
    if (pj != null) {
        ConflictDialog(
            title = s.conflictTitle,
            summary = s.conflictSummaryOf(pendingConflictName),
            items = listOf(s.conflictOverwrite, s.conflictKeepBoth, s.conflictSkip),
            rememberText = s.conflictRemember,
            cancelText = s.cancel,
            onSelect = { i, remember ->
                val pol = when (i) {
                    0 -> "overwrite"
                    1 -> "rename"
                    else -> "skip"
                }
                pendingJob = null
                if (remember) onConflictPolicy(pol)
                runTransfer(pj, pol)
            },
            onDismiss = { pendingJob = null },
        )
    }

    // ── 跳到路径（统一 `!` 语法）──
    if (gotoFor >= 0) {
        val target = gotoFor
        val p = pane(target)
        LabelDialog(
            title = s.filesGoto,
            hint = s.filesGotoHint,
            initial = if (p.loc.kind == Kind.HOST) p.path else p.loc.displaySpec(p.path),
            maxLength = 400,
            confirmText = s.confirm,
            cancelText = s.cancel,
            onDismiss = { gotoFor = -1 },
            onConfirm = { text ->
                gotoFor = -1
                scope.launch {
                    val hit = runCatching { resolveSpec(text, s) }.getOrNull()
                    if (hit == null) {
                        onToast(s.filesGotoBad)
                    } else {
                        p.stack.clear()
                        p.loc = hit.loc
                        p.path = hit.path
                    }
                }
            },
        )
    }

    // ── 重命名（自动只选主名）──
    val rn = renameFor
    if (rn != null) {
        val (pi, e) = rn
        val p = pane(pi)
        LabelDialog(
            title = s.filesRename,
            hint = e.name,
            initial = e.name,
            maxLength = 255,
            confirmText = s.confirm,
            cancelText = s.cancel,
            selectUpTo = stemLength(e.name),
            onDismiss = { renameFor = null },
            onConfirm = { name ->
                renameFor = null
                val clean = name.trim()
                if (clean.isNotEmpty() && clean != e.name) {
                    // 后缀变了要问一句：手误改了后缀，文件就打不开了
                    if (!extOf(e.name).equals(extOf(clean), ignoreCase = true)) {
                        pendingRename = Triple(pi, e.name, clean)
                    } else {
                        doRename(p, e, clean)
                    }
                }
            },
        )
    }

    // ── raw 复制询问（设置=「每次询问」）──
    val spj = pendingSparseJob
    if (spj != null) {
        ChoiceDialog(
            title = s.rawCopy,
            summary = s.rawCopyAskSummary,
            items = listOf(s.rawCopyKeep, s.rawCopyFull),
            cancelText = s.cancel,
            onDismiss = { pendingSparseJob = null },
            onSelect = { i ->
                pendingSparseJob = null
                runTransfer(spj, conflictPolicy, sparseOverride = if (i == 1) "never" else "auto")
            },
        )
    }

    // ── FAT32/exFAT 目标先行强提示（§2.2）──
    val pfj = pendingFatJob
    if (pfj != null) {
        ConfirmDialog(
            title = s.filesFatSparseWarn,
            summary = s.filesFatSparseBody,
            confirmText = s.confirm,
            cancelText = s.cancel,
            danger = false,
            onDismiss = { pendingFatJob = null },
            onConfirm = {
                pendingFatJob = null
                runTransfer(pfj, conflictPolicy)
            },
        )
    }

    // ── 改后缀确认 ──
    val pr = pendingRename
    if (pr != null) {
        val (pi, oldName, newName) = pr
        ConfirmDialog(
            title = s.filesExtAsk,
            summary = s.filesExtBody("." + extOf(oldName), "." + extOf(newName)),
            confirmText = s.filesExtYes,
            cancelText = s.cancel,
            danger = false,
            onDismiss = { pendingRename = null },
            onConfirm = {
                pendingRename = null
                val p = pane(pi)
                val e = p.entries.firstOrNull { it.name == oldName }
                if (e != null) doRename(p, e, newName)
            },
        )
    }

    // ── XML 元数据（索引列表最后一条）──
    if (wimXmlOpen) {
        val imgs = pane(focus).wimImages
        WindowDialog(
            show = true,
            title = s.wimXmlRow,
            onDismissRequest = { wimXmlOpen = false },
            onDismissFinished = { wimXmlOpen = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                imgs.forEach { w ->
                    Text(
                        buildString {
                            append(s.wimIdxRow(w.index)).append("  ").append(w.name)
                            if (w.version.isNotBlank()) append("\n").append(s.wimInfoVersion).append(" ").append(w.version)
                            if (w.arch.isNotBlank()) append(" · ").append(w.arch.uppercase())
                            append("\n").append(s.wimInfoSize).append(" ").append(sizeText(w.bytes))
                            w.langs?.let { append(" · ").append(it) }
                            if (w.edition.isNotBlank()) append("\n").append(w.edition)
                        },
                        style = textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = { wimXmlOpen = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(s.confirm, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }

    // ── 映像信息卡（长按索引行）──
    val wi = wimInfo
    if (wi != null) {
        WindowDialog(
            show = true,
            title = wi.name.ifEmpty { s.wimIdxRow(wi.index) },
            onDismissRequest = { wimInfo = null },
            onDismissFinished = { wimInfo = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                @Composable
                fun row(k: String, v: String) {
                    Text(
                        "$k  $v",
                        style = textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                row(s.wimIdxRow(wi.index), "")
                if (wi.version.isNotBlank()) row(s.wimInfoVersion, wi.version)
                if (wi.arch.isNotBlank()) row(s.wimInfoArch, wi.arch.uppercase())
                row(s.wimInfoSize, sizeText(wi.bytes))
                if (wi.files > 0 || wi.dirs > 0) {
                    row(s.wimInfoFiles, "${wi.files} / ${wi.dirs}")
                }
                wi.langs?.let { row(s.wimInfoLang, it) }
                if (wi.edition.isNotBlank()) row(s.wimInfoEdition, wi.edition)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { wimInfo = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(s.cancel, style = MiuixTheme.textStyles.button)
                    }
                    Button(
                        onClick = {
                            val p = pane(focus)
                            val w = wi
                            wimInfo = null
                            enter(
                                p,
                                Loc(
                                    Kind.WIM,
                                    wim = p.loc.wim,
                                    inner = p.loc.inner,
                                    index = w.index,
                                    label = s.wimIdxRow(w.index) + " · " + w.name,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(s.confirm, style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }

    // ── 长任务：阻塞式进度卡 ──
    //   进度条 + "N / M 项 · x GB / y GB"；这一层把底下所有点击都吃掉，只能点"取消"。
    if (busy != null) {
        val pv = prog
        val pct = pv?.pct?.takeIf { it >= 0 }
            ?: (if (opTotal > 0) opDone * 100 / opTotal else 0)
        val counts = buildString {
            val d = if (pv != null && pv.total > 0) pv.cur else opDone.toLong()
            val t = if (pv != null && pv.total > 0) pv.total else opTotal.toLong()
            if (t > 0) append(s.filesProgressCount(d.toInt(), t.toInt()))
            val bd = if (pv != null && pv.totalBytes > 0) pv.curBytes else opBytes
            val bt = if (pv != null && pv.totalBytes > 0) pv.totalBytes else opBytesTotal
            if (bt > 0) {
                if (isNotEmpty()) append(" · ")
                append(sizeText(bd)).append(" / ").append(sizeText(bt))
                // 压缩包口径：RAR 才给得出"已处理"，zip/7z 的 archive_cur_bytes=0
                // —— 0 是"这个格式给不了"，绝不能当成"没进度"。
                if (pv != null && pv.archiveTotalBytes > 0) {
                    append("（").append(sizeText(pv.archiveTotalBytes)).append("）")
                }
            } else if (bd > 0) {
                if (isNotEmpty()) append(" · ")
                append(sizeText(bd))
            }
        }
        org.icdqcow2.ui.pages.BlockingProgressCard(
            title = busy ?: "",
            pct = pct,
            detail = buildString {
                // archive.extract 的字节是"解压后"口径，标一下，免得用户以为比包还大
                if (pv?.bytesKind == "extracted") append(s.filesExtracted).append(" ")
                append(pv?.item?.takeIf { it.isNotEmpty() } ?: "")
            }.trim(),
            counts = counts.ifEmpty { null },
            // ★ 与全局进度浮层同一套显示内容（用户 2026-11-12 第 8 条）：
            //   剩余大小 / 已用 / 预计剩余。文件页**保留取消按钮**（这类任务可以中止）。
            remainBytes = when {
                pv == null -> 0L
                pv.overallTotalBytes > 0L -> (pv.overallTotalBytes - pv.overallCurBytes).coerceAtLeast(0L)
                pv.totalBytes > 0L -> (pv.totalBytes - pv.curBytes).coerceAtLeast(0L)
                else -> 0L
            },
            elapsedMs = pv?.elapsedMs ?: 0L,
            etaSec = pv?.eta ?: -1,
            dstItem = pv?.dstItem?.takeIf { it.isNotBlank() },
            filesCur = pv?.filesCur ?: 0L,
            filesTotal = pv?.filesTotal ?: 0L,
            cancelText = s.cancel,
            onCancel = {
                ToolRunner.cancel()
                opJob?.cancel()
                opJob = null
                ToolEvents.clear()
                busy = null
            },
        )
    }

    // ── 回收站：点一下问"还原吗" ──
    val ra = restoreAsk
    if (ra != null) {
        val (pi0, e0) = ra
        WindowDialog(
            show = true,
            title = s.filesRestore,
            summary = s.filesRestoreAsk,
            onDismissRequest = { restoreAsk = null },
            onDismissFinished = { restoreAsk = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    e0.name,
                    style = textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
                Text(
                    unTrashName(e0.name).orEmpty(),
                    style = textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { restoreAsk = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(s.cancel, style = MiuixTheme.textStyles.button) }
                    Button(
                        onClick = {
                            restoreAsk = null
                            restoreTrash(pane(pi0), e0)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(s.filesRestore, style = MiuixTheme.textStyles.button) }
                }
            }
        }
    }

    // ── 搜索 ──
    if (searchOpen) {
        val p = pane(focus)
        LaunchedEffect(searchQ, searchOpen) {
            val q = searchQ.trim()
            if (q.isEmpty()) {
                searchHits = emptyList()
                searchRun = false
                return@LaunchedEffect
            }
            searchRun = true
            kotlinx.coroutines.delay(250)          // 打字防抖
            searchPane(p, q, s) { hits, trunc ->
                searchHits = hits
                searchTrunc = trunc
                searchRun = false
            }
        }
        WindowDialog(
            show = true,
            title = s.filesSearch,
            onDismissRequest = { searchOpen = false },
            onDismissFinished = { searchOpen = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = searchQ,
                    onValueChange = { searchQ = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = s.filesSearchHint,
                    style = textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                when {
                    searchRun -> Text(
                        s.filesSearching,
                        style = textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    searchQ.trim().isEmpty() -> Unit
                    searchHits.isEmpty() -> Text(
                        s.filesSearchEmpty,
                        style = textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(searchHits) { h ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // 跳到它所在目录（文件顺便勾上）
                                            p.path = h.dir
                                            p.sel = if (h.isDir) emptySet() else setOf(h.name)
                                            searchOpen = false
                                            scope.launch { reload(p, s) }
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FileTypeIcon(
                                        if (h.isDir) FileIcons.of("dir", true) else FileIcons.of(h.name, false),
                                        22.dp,
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            h.name,
                                            style = textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            h.dir.ifEmpty { "/" },
                                            style = textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            if (searchTrunc) {
                                item {
                                    Text(
                                        s.filesSearchLimit(SEARCH_MAX),
                                        style = textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 长按动作菜单 ──
    val m = menuFor
    if (m != null) {
        val (pi, e) = m
        val trashed = unTrashName(e.name) != null
        // 能不能复制/移动，看的是"另一栏收不收得下"和"这一栏删不删得掉"：
        //   · 对侧只读（ISO / 只读压缩包）或压根不是文件系统（分区列表）→ 两个都别给；
        //   · 本栏只读 → 只能"复制出去"，不能"移动出去"（移动要删源）。
        val dstPane = pane(if (pi == 0) 1 else 0)
        val srcPane = pane(pi)
        val dstOk = dstPane.loc.kind != Kind.IMAGE && dstPane.writableHere()
        val srcOk = srcPane.loc.kind != Kind.IMAGE
        val canCopy = srcOk && dstOk
        val canMove = canCopy && srcPane.writableHere()
        val canPack = ToolCaps.has("archive.create") &&
            dstPane.loc.kind == Kind.HOST && dstPane.writableHere()
        val srcWritable = srcOk && srcPane.writableHere()
        // 只读路径：重命名 / 删除**整条不显示**（用户 2026-10-29：不是灰掉，是别出现）
        val acts = buildList {
            add(Act.Copy)
            add(Act.Move)
            if (srcWritable) {
                add(Act.Rename)
                add(Act.Delete)
            }
            if (trashed) add(Act.Restore)
            add(Act.Pack)
            add(Act.Props)
        }
        val labels = acts.map { a ->
            when (a) {
                Act.Copy -> if (pi == 0) s.filesCopyR else s.filesCopyL
                Act.Move -> if (pi == 0) s.filesMoveR else s.filesMoveL
                Act.Rename -> s.filesRename
                Act.Delete -> if (trashed) s.filesDeleteForever else s.filesDelete
                Act.Restore -> s.filesRestore
                Act.Pack -> s.filesZipTo
                Act.Props -> s.filesProps
            }
        }
        ChoiceDialog(
            title = e.name,
            summary = if (e.dir) s.filesFolder else sizeText(e.size),
            items = labels,
            enabled = acts.map {
                when (it) {
                    Act.Copy -> canCopy
                    Act.Move -> canMove
                    Act.Pack -> canPack
                    else -> true
                }
            },
            onDisabled = { i ->
                menuFor = null
                onToast(
                    when (acts.getOrNull(i)) {
                        Act.Pack -> s.filesZipUnavailable
                        else -> s.filesReadonly
                    },
                )
            },
            cancelText = s.cancel,
            onDismiss = { menuFor = null },
            onSelect = { i ->
                menuFor = null
                focus = pi
                val targets = menuTargets(pi, e)
                when (acts[i]) {
                    Act.Copy -> planTransfer(move = false)
                    Act.Move -> planTransfer(move = true)
                    Act.Rename -> renameFor = pi to e
                    Act.Delete -> deleteSelected()
                    Act.Restore -> restoreTrash(pane(pi), e)
                    Act.Pack -> packFor = pi to targets
                    Act.Props -> startProps(pi, targets)
                }
            },
        )
    }

    // ── 删除确认 ──
    if (confirmDel) {
        val p = pane(focus)
        val names = p.sel.toList()
        val allTrashed = names.isNotEmpty() && names.all { isTrashed(it) }
        val anyTrashed = names.any { isTrashed(it) }
        val hasDir = names.any { n -> p.entries.firstOrNull { it.name == n }?.dir == true }
        val warns = buildList {
            if (allTrashed) {
                add(s.filesPurgeWarn)
            } else {
                if (deleteToTrash && p.loc.kind == Kind.HOST) add(s.filesDelWarnTrash) else add(s.filesDelWarn)
                if (anyTrashed) add(s.filesPurgeWarn)
                if (hasDir && deleteToTrash && p.loc.kind == Kind.HOST) add(s.filesTrashDirWarn)
            }
        }
        ConfirmDialog(
            title = if (allTrashed) s.filesPurgeAsk(names.size) else s.filesDelAsk(names.size),
            summary = names.joinToString("\n") + "\n" + warns.joinToString("\n"),
            confirmText = if (allTrashed) s.filesDeleteForever else s.filesDelete,
            cancelText = s.cancel,
            onDismiss = { confirmDel = false },
            onConfirm = {
                confirmDel = false
                runDelete()
            },
        )
    }

    // ── 属性卡 ──
    val ps = props
    if (ps != null) {
        WindowDialog(
            show = true,
            title = ps.title,
            onDismissRequest = {
                propsJob?.cancel()
                props = null
            },
            onDismissFinished = { props = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    s.filesPropsCounts(ps.files, ps.dirs),
                    style = textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                val cap = ps.capBytes
                if (cap != null) {
                    Text(
                        "${s.filesPropsCapacity}  ${sizeText(cap)}",
                        style = textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${s.filesPropsAlloc}  " +
                            (if (ps.allocKnown) sizeText(ps.usedBytes ?: 0L) else s.filesPropsUnknown),
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        "${s.filesPropsSize}  ${sizeText(ps.bytes)}",
                        style = textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                if (cap == null && ps.alloc > 0 && ps.alloc != ps.bytes) {
                    Text(
                        "${s.filesPropsAlloc}  ${sizeText(ps.alloc)}",
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (!ps.done) {
                    Text(
                        s.filesPropsScanning(ps.scanned),
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (ps.truncated) {
                    Text(
                        s.filesPropsTruncated,
                        style = textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                ps.err?.let {
                    Text(it, style = textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!ps.done) {
                        Button(
                            onClick = {
                                propsJob?.cancel()
                                props = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Text(s.filesPropsStop, style = MiuixTheme.textStyles.button)
                        }
                    }
                    Button(
                        onClick = { props = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(s.confirm, style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }

    // ── 压缩到… ──
    val pk = packFor
    if (pk != null) {
        val (pi, names) = pk
        val dstPane = pane(if (pi == 0) 1 else 0)
        val first = names.firstOrNull().orEmpty()
        val base = first.substringBeforeLast('.', first).ifEmpty { "archive" }
        PackDialog(
            s = s,
            writable = dstPane.loc.kind == Kind.HOST && dstPane.writableHere(),
            defaultName = "$base.zip",
            onDismiss = { packFor = null },
            onStart = { fmt, name ->
                packFor = null
                startPack(pi, names, fmt, name)
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PaneView(
    p: PaneState,
    focused: Boolean,
    s: Strings,
    onFocus: () -> Unit,
    onGoto: () -> Unit,
    onUp: () -> Unit,
    onRowClick: (Entry) -> Unit,
    onOpenPart: (PartRow) -> Unit,
    onOpenWim: (WimImage) -> Unit,
    onLongWim: (WimImage) -> Unit,
    onWimXml: () -> Unit,
    onMore: () -> Unit,
    onLongPress: (Entry) -> Unit,
    multi: Boolean,
    showTrash: Boolean,
    side: Int,
    /** 顶上有"将文件保存到 →"这条（编辑器保存只读副本时出现）。 */
    saveBar: Boolean = false,
    onSaveBarSave: () -> Unit = {},
    onSaveBarCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val listState = rememberLazyListState()

    // 滑动这一栏也算"选中这一侧"：蓝底跟着手指走（不消费事件，列表照常滚）
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onFocus()
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            // 焦点栏：一层很淡的主题色，说明"复制/粘贴作用在这一栏"。
            // 只把**外侧**上角做成圆角（左栏左上、右栏右上），和卡片观感一致。
            .background(
                color = if (focused) colors.primary.copy(alpha = 0.06f) else Color.Transparent,
                shape = if (side == 0) RoundedCornerShape(topStart = 16.dp)
                else RoundedCornerShape(topEnd = 16.dp),
            )
            // 点空白处也算选中这一侧
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onFocus() })
            },
    ) {
        // ── "将文件保存到 →"：编辑器保存只读副本后，回到这一栏挑位置 ──
        if (saveBar) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                insideMargin = PaddingValues(0.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = if (side == 0) s.filesSaveBarLeft else s.filesSaveBarRight,
                        style = textStyles.body2,
                        color = colors.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSaveBarSave() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Text(
                        text = s.cancel,
                        style = textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSaveBarCancel() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // 一行：容器名（进容器才有）+ 路径（点 = 跳转卡）+ 上一级
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val src = remember { MutableInteractionSource() }
                PathText(
                    path = if (p.loc.kind == Kind.IMAGE) "" else p.path,
                    style = textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .weight(1f)
                        // indication = null：不要 MiuixIndication 那层黑色遮罩（点一下像给字加了阴影）
                        .clickable(interactionSource = src, indication = null) {
                            onFocus()
                            onGoto()
                        },
                )
                IconButton(
                    onClick = {
                        onFocus()
                        onUp()
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = s.filesUpOne,
                        tint = colors.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.onSurfaceVariantSummary.copy(alpha = 0.12f)),
        )

        if (p.loc.kind == Kind.IMAGE) {
            PartsList(p, s, onOpenPart, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        // WIM/ESD：先把映像索引列出来（不弹窗选）
        if (p.loc.kind == Kind.WIMIDX) {
            WimIdxList(p, s, onOpenWim, onLongWim, onWimXml, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }

        // 列表里真正要显示的那些（隐藏回收站文件时剔掉）
        val visible = if (showTrash) p.entries else p.entries.filterNot { isTrashed(it.name) }
        when {
            p.err != null -> Text(
                p.err!!,
                style = textStyles.footnote1,
                color = colors.error,
                modifier = Modifier.padding(10.dp),
            )
            visible.isEmpty() && p.loading -> Text(
                s.filesLoading,
                style = textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(10.dp),
            )
            // 目录里本来就没东西
            p.entries.isEmpty() -> Text(
                s.filesEmpty,
                style = textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(10.dp),
            )
            // 有东西，但全是被隐藏的回收站文件 —— 不能显示成"空的"，得说清楚
            visible.isEmpty() -> Text(
                s.filesEmptyTrashHidden,
                style = textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(10.dp),
            )
            else -> {
              // ★ 无限滚动：滚到离底 8 行内就自动拉下一页（每页 1000 条），
              //   用户永远不用点"加载更多"，也不会看到"折叠"。
              val lastVisible by remember {
                  derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
              }
              LaunchedEffect(lastVisible, p.entries.size, p.hasMore) {
                  if (p.hasMore && !p.loading && lastVisible >= p.entries.size - 8) onMore()
              }
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                // ★ 卡片一直铺到屏幕底、浮动导航压在它上面：
                //   给列表留出导航高度，滚到最底时最后一行仍然看得见
                contentPadding = PaddingValues(
                    bottom = LocalBottomBarHeight.current + 16.dp,
                ),
            ) {
                items(visible) { e ->
                    EntryRow(
                        e = e,
                        multi = multi,
                        // 多选栏即使没焦点也保持高亮（要能看着它去对面找目录）
                        selected = (focused || multi) && p.sel.contains(e.name),
                        onClick = { onRowClick(e) },
                        onLongClick = { onLongPress(e) },
                    )
                }
                if (p.loadingMore) {
                    item {
                        Text(
                            text = s.filesSearching,
                            style = textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }
              }
            }
        }
    }
}

/** 进 qcow2 之后那一层：分区列表（卷标 / 文件系统 / 已用）。 */
@Composable
private fun PartsList(
    p: PaneState,
    s: Strings,
    onOpenPart: (PartRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    when {
        p.partsLoading && p.parts.isEmpty() -> Text(
            s.filesPartsLoading,
            style = textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(10.dp),
        )
        p.err != null -> Text(
            p.err!!,
            style = textStyles.footnote1,
            color = colors.error,
            modifier = Modifier.padding(10.dp),
        )
        p.parts.isEmpty() -> Text(
            s.filesPartsEmpty,
            style = textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(10.dp),
        )
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(bottom = LocalBottomBarHeight.current + 16.dp),
        ) {
            items(p.parts) { part ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPart(part) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileTypeIcon(FileIcons.ofPartition(part.fs, part.hasFs), 24.dp)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = part.label ?: s.filesNoLabel,
                            style = textStyles.body2,
                            color = if (part.hasFs) colors.onSurface
                            else colors.onSurfaceVariantSummary,
                        )
                        val sizeText = sizeText(part.bytes)
                        val meta = if (part.hasFs) {
                            listOfNotNull(
                                part.fs?.uppercase(),
                                part.used?.let { s.filesPartMeta(sizeText(it), sizeText) },
                            ).joinToString(" · ")
                        } else {
                            s.filesPartRawMeta(sizeText)
                        }
                        Text(
                            text = meta,
                            // 和文件行同一号字（footnote2），窄栏里允许折两行，别把"已用/总"截掉
                            style = textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** WIM/ESD 的映像索引列表：主标题「索引 N」，小字是 display_name，长按看详情。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WimIdxList(
    p: PaneState,
    s: Strings,
    onOpen: (WimImage) -> Unit,
    onLong: (WimImage) -> Unit,
    onXml: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val listState = rememberLazyListState()
    when {
        p.wimLoading && p.wimImages.isEmpty() -> Text(
            s.wimIdxLoading,
            style = textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(10.dp),
        )
        p.err != null -> Text(
            p.err!!,
            style = textStyles.footnote1,
            color = colors.error,
            modifier = Modifier.padding(10.dp),
        )
        p.wimImages.isEmpty() -> Text(
            s.wimIdxEmpty,
            style = textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(10.dp),
        )
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(bottom = LocalBottomBarHeight.current + 16.dp),
        ) {
            items(p.wimImages) { w ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(w) },
                            onLongClick = { onLong(w) },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileTypeIcon(FileIcons.of("install.wim", false), 24.dp)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = s.wimIdxRow(w.index),
                            style = textStyles.body2,
                            color = colors.onSurface,
                        )
                        if (w.name.isNotEmpty()) {
                            Text(
                                text = w.name,
                                style = textStyles.footnote2,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            // 索引下面再列一条"普通文件"：wimlib 读到的 XML 元数据
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onXml() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileTypeIcon(FileIcons.of("a.xml", false), 24.dp)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = s.wimXmlRow,
                            style = textStyles.body2,
                            color = colors.onSurface,
                        )
                        Text(
                            text = s.wimXmlSub,
                            style = textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: Entry,
    multi: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(e.name, e.dir, 24.dp)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            // 回收站里的项：标题显示**原名**（不再把 `.trashed-<到期>-` 前缀怼在脸上），
            // 用灰色区分；这样第二行就能放全「时间 · 大小」（用户 2026-10-29 要求）。
            // 回收站项：**原样显示**（含 `.trashed-1795839165-` 前缀，用户要看得见），整行红
            val trashed = isTrashed(e.name)
            Text(
                text = e.name,
                style = textStyles.body2,
                color = if (trashed) colors.error else colors.onSurface,
                // 名字要显示全：不截断，长了自动换行
            )
            // 第二行：`时间 · 大小`（文件夹只有时间）
            val time = timeText(e.mtime)
            val size = if (e.dir) null else sizeText(e.size)
            val meta = listOfNotNull(time, size).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    // 比正文小两号（footnote1 → footnote2）；回收站项的这行是**浅红**
                    style = textStyles.footnote2,
                    color = if (trashed) colors.error.copy(alpha = 0.6f)
                    else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
        if (multi) {
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = { onClick() },
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ── 数据 ──

/** 有行标题的容器名；宿主栏返回 null（不占那一行）。 */
private fun containerTitle(loc: Loc): String? = when (loc.kind) {
    Kind.HOST -> null
    Kind.WIMIDX -> loc.label.ifEmpty { File(loc.wim ?: "").name }
    else -> loc.label.ifEmpty {
        when (loc.kind) {
            Kind.ISO -> File(loc.iso ?: "").name
            Kind.IMAGE -> File(loc.img ?: "").name
            Kind.ARCHIVE -> File(loc.arc ?: "").name
            Kind.WIM -> File(loc.wim ?: "").name
            else -> ""
        }
    }
}

/**
 * 把共享存储里的一个路径换成 MediaStore 的 Uri（回收站只能按 Uri 操作）。
 */
private fun mediaUriOf(ctx: android.content.Context, path: String): android.net.Uri? {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val selection = "${MediaStore.MediaColumns.DATA} = ? AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
    return try {
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(path),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(collection, c.getLong(0))
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }
}

/** 系统回收站文件：删除时被 MediaProvider 改名成 `.trashed-<过期时间>-原名`。 */
private fun isTrashed(name: String): Boolean = name.startsWith(TRASH_MARK)

private fun join(dir: String, name: String): String =
    if (dir == "/" || dir.isEmpty()) "/$name" else "$dir/$name"

private suspend fun reload(p: PaneState, s: Strings, more: Boolean = false) {
    if (p.loc.kind == Kind.IMAGE) {
        loadParts(p, s)
        return
    }
    if (p.loc.kind == Kind.WIMIDX) {
        loadWimIdx(p, s)
        return
    }
    p.loading = true
    p.err = null
    val offset = if (more) p.entries.size else 0
    if (!more) {
        p.entries = emptyList()
        p.total = 0
        p.hasMore = false
    }
    runCatching { query(p, offset, s) }
        .onSuccess { l ->
            p.entries = if (more) p.entries + l.list else l.list
            p.total = l.total
            p.hasMore = l.more
            p.arcSolid = l.solid
            // 工具端认出来的"格式 = empty"：0 字节 / 认不出的包，不是空的 zip
            if (p.loc.kind == Kind.ARCHIVE && l.format == "empty") {
                p.err = s.filesEmptyArchive
                p.entries = emptyList()
            }
            // ★ 「全部渲染」：剩下的页在后台一次拉完（用户 2026-10-29：不要"展开"按钮，
            //   一个文件夹有几万条也得全都在这儿）。每页 2000（工具端上限），最多 200 页。
            if (p.hasMore && p.err == null) {
                p.loadingMore = true
                var off2 = p.entries.size
                var guard = 0
                while (p.hasMore && guard < 200) {
                    val nx = runCatching { query(p, off2, s) }.getOrNull() ?: break
                    if (nx.list.isEmpty()) break
                    p.entries = p.entries + nx.list
                    p.hasMore = nx.more
                    p.total = maxOf(p.total, nx.total)
                    off2 += nx.list.size
                    guard++
                }
                p.loadingMore = false
            }
            // 排序：**文件夹全部在前**，同类按名字（不分大小写）
            p.entries = p.entries.sortedWith(
                compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() },
            )
            p.hasMore = false
            if (l.token != null) p.arcToken = l.token
            if (l.format != null) p.arcFormat = l.format
            p.filesCount = l.files
            p.dirsCount = l.dirs
        }
        .onFailure { e -> p.err = e.message ?: s.filesReadFail }
    p.loading = false
}

/**
 * 进 WIM/ESD 之后那一层：把 `wim.identify` 的映像索引列成一栏
 * （用户 2026-10-29 要求：不要弹窗选，直接在栏里列出 1、2、3…）。
 */
private suspend fun loadWimIdx(p: PaneState, s: Strings) {
    val wim = p.loc.wim ?: return
    p.wimLoading = true
    p.err = null
    p.wimImages = emptyList()
    try {
        val args = mutableMapOf("src" to wim)
        val inn = p.loc.inner
        if (!inn.isNullOrEmpty()) args["inner"] = inn
        val r = ToolRunner.exec("wim.identify", args, imagePath = null, writable = false, imageFd = -1)
        if (!r.ok) {
            p.err = toolErrorText(s, r)
            return
        }
        val arr = r.data?.optJSONArray("images")
        val out = ArrayList<WimImage>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            out += WimImage(
                index = o.optInt("index", i + 1),
                name = o.optString("display_name").ifEmpty { o.optString("name") },
                edition = o.optString("edition_id"),
                version = o.optString("version"),
                build = o.optInt("build", 0),
                arch = o.optString("arch"),
                bytes = o.optLong("total_bytes", 0L),
                files = o.optInt("files", 0),
                dirs = o.optInt("dirs", 0),
                langs = o.optString("languages").takeIf { it.isNotBlank() },
            )
        }
        p.wimImages = out
    } catch (t: Throwable) {
        p.err = t.message ?: s.filesReadFail
    } finally {
        p.wimLoading = false
    }
}

/** `part.show` + 逐个 `part.scan`：先把分区列出来，再一条条补卷标/文件系统/已用。 */
private suspend fun loadParts(p: PaneState, s: Strings) {
    val img = p.loc.img ?: return
    p.partsLoading = true
    p.err = null
    p.parts = emptyList()
    try {
        val r = ToolRunner.exec(
            "part.show",
            emptyMap(),
            imagePath = img,
            writable = false,
            imageFd = -1,
        )
        val (_, parts) = ImageRepo.parseParts(r) ?: run {
            p.err = toolErrorText(s, r)
            return
        }
        p.parts = parts.filterNot { it.isContainer() }.mapIndexed { i, part ->
            PartRow(index = i + 1, lba = part.lba, bytes = part.bytes)
        }
        p.partsLoading = false
        for (row in p.parts.toList()) {
            val sc = ImageRepo.parseScan(
                ToolRunner.exec(
                    "part.scan",
                    mapOf("lba" to row.lba.toString()),
                    imagePath = img,
                    writable = false,
                    imageFd = -1,
                ),
            ) ?: continue
            p.parts = p.parts.map {
                if (it.lba == row.lba) it.copy(
                    fs = sc.fs,
                    label = sc.label,
                    used = sc.usedBytes,
                    hasFs = sc.hasFs,
                ) else it
            }
        }
    } catch (t: Throwable) {
        p.err = t.message ?: s.filesReadFail
    } finally {
        p.partsLoading = false
    }
}

private suspend fun query(
    p: PaneState,
    offset: Int,
    s: Strings,
    pathOverride: String? = null,
): Listing {
    val loc = p.loc
    val path = pathOverride ?: p.path
    return when (loc.kind) {
        Kind.HOST -> withContext(Dispatchers.IO) {
            val dir = File(path)
            // 普通应用读不了的位置（/storage/emulated、/data…）交给 Shizuku 借 shell 来读
            if (!dir.exists() || !dir.isDirectory || dir.listFiles() == null) {
                if (ShizukuShell.granted()) return@withContext listViaShell(path, s)
            }
            // 三种情况必须分开：不存在 / 不是目录 / 存在但读不了（权限）——都不能当成"空目录"
            if (!dir.exists()) throw IllegalStateException(s.filesNoSuchDir)
            if (!dir.isDirectory) throw IllegalStateException(s.filesNoSuchDir)
            val raw = dir.listFiles() ?: throw IllegalStateException(s.filesNoAccess)
            val all = raw.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
            )
            val dirs = all.count { it.isDirectory }
            val files = all.size - dirs
            val slice = all.drop(offset).take(PAGE)
            Listing(
                list = slice.map {
                    Entry(
                        name = it.name,
                        dir = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length(),
                        mtime = it.lastModified() / 1000,
                    )
                },
                total = all.size,
                more = offset + slice.size < all.size,
                files = files,
                dirs = dirs,
            )
        }
        Kind.NTFS, Kind.FAT -> {
            val isNtfs = loc.kind == Kind.NTFS
            val args = mutableMapOf(
                "lba" to loc.lba.toString(),
                "path" to path,
                "offset" to offset.toString(),
                "limit" to PAGE.toString(),
                "times" to "true",
            )
            if (isNtfs) args["sizes"] = "true"
            parse(
                ToolRunner.exec(
                    if (isNtfs) "ntfs.list" else "fat.ls",
                    args,
                    imagePath = loc.img,
                    writable = false,
                    imageFd = -1,
                ),
            )
        }
        Kind.ISO -> parse(
            ToolRunner.exec(
                "iso.ls",
                mapOf("iso" to (loc.iso ?: ""), "path" to path),
                imagePath = null,
                writable = false,
                imageFd = -1,
            ),
            s,
        )
        Kind.WIM -> {
            val args = mutableMapOf(
                "wim" to (loc.wim ?: ""),
                "index" to loc.index.toString(),
                "path" to path,
                "limit" to PAGE.toString(),
            )
            val inn = loc.inner
            if (!inn.isNullOrEmpty()) args["inner"] = inn
            parse(
                ToolRunner.exec("wim.ls", args, imagePath = null, writable = false, imageFd = -1),
                s,
            )
        }
        Kind.ARCHIVE -> {
            suspend fun run(token: String?): ToolRunner.Result {
                val args = mutableMapOf(
                    "archive" to (loc.arc ?: ""),
                    "path" to path,
                    "offset" to offset.toString(),
                    "limit" to PAGE.toString(),
                )
                if (!token.isNullOrEmpty()) args["index-token"] = token
                return ToolRunner.exec("archive.ls", args, imagePath = null, writable = false, imageFd = -1)
            }
            var r = run(p.arcToken)
            if (!r.ok && (r.code ?: "").contains("INDEX-EXPIRED")) {
                p.arcToken = null
                r = run(null)
            }
            parse(r, s)
        }
        Kind.IMAGE, Kind.WIMIDX -> Listing(emptyList(), 0, false)
    }
}

private fun parse(r: ToolRunner.Result, s: Strings? = null): Listing {
    if (!r.ok) throw IllegalStateException(
        if (s != null) toolErrorText(s, r) else (r.hint ?: r.code ?: r.raw.take(160)),
    )
    val d = r.data ?: throw IllegalStateException("empty")
    val arr = d.optJSONArray("names") ?: d.optJSONArray("entries") ?: d.optJSONArray("items")
    val list = ArrayList<Entry>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").ifEmpty {
                o.optString("path", "").substringAfterLast('/')
            }
            if (name.isEmpty()) continue
            list += Entry(
                name = name,
                dir = o.optBoolean("dir", false) || o.optBoolean("is_dir", false),
                size = o.optLong("size", 0L),
                mtime = o.optLong("mtime", 0L),
            )
        }
    }
    val total = d.optInt("total", list.size)
    val more = d.optBoolean("has_more", false)
    // 分页时只看到一页，文件/文件夹数就不准了 → 交给上层显示"共 N 项"
    return Listing(
        list = list,
        total = if (total <= 0) list.size else total,
        more = more,
        files = if (more) -1 else list.count { !it.dir },
        dirs = if (more) -1 else list.count { it.dir },
        token = d.optString("index_token").takeIf { it.isNotEmpty() },
        format = d.optString("format").takeIf { it.isNotEmpty() },
        solid = d.optBoolean("solid", false),
    )
}

// ── 容器识别 ──

/** 按魔数 + 后缀判容器类型；不是容器返回 null。 */
private fun sniffContainer(f: File): String? {
    if (!f.isFile) return null
    val head = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(33000)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            buf
        }
    }.getOrNull()
    val byMagic = head?.let { magicOf(it) }
    if (byMagic != null) return byMagic
    return sniffName(f.name)
}

private fun sniffName(name: String): String? {
    val n = name.lowercase()
    return when {
        // 磁盘映像：尽量把名字里能看出类型的都认了。具体格式工具端按魔数判（REPLY-59），
        // 这里只决定"用磁盘视图打开"还是"当普通文件"。
        n.endsWith(".qcow2") || n.endsWith(".qcow") || n.endsWith(".img") ||
            n.endsWith(".raw") || n.endsWith(".dd") ||
            n.endsWith(".vhd") || n.endsWith(".vmdk") -> "qcow2"
        n.endsWith(".iso") -> "iso"
        n.endsWith(".wim") || n.endsWith(".esd") || n.endsWith(".swm") -> "wim"
        else -> {
            val e = n.substringAfterLast('.')
            if (e in ARCHIVE_EXT) "archive" else null
        }
    }
}

private fun magicOf(b: ByteArray): String? {
    fun at(i: Int, vararg s: Int): Boolean {
        if (i + s.size > b.size) return false
        for (k in s.indices) if ((b[i + k].toInt() and 0xFF) != s[k]) return false
        return true
    }
    return when {
        at(0, 0x51, 0x46, 0x49, 0xFB) -> "qcow2"
        at(0, 0x4D, 0x53, 0x57, 0x49, 0x4D) -> "wim"       // MSWIM
        at(32769, 0x43, 0x44, 0x30, 0x30, 0x31) -> "iso"   // CD001
        at(0, 0x50, 0x4B, 0x03, 0x04) -> "archive"
        at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "archive"
        at(0, 0x52, 0x61, 0x72, 0x21) -> "archive"
        at(0, 0x1F, 0x8B) -> "archive"
        at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A) -> "archive"
        at(0, 0x42, 0x5A, 0x68) -> "archive"
        at(0, 0x4D, 0x53, 0x43, 0x46) -> "archive"
        at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> "archive"
        else -> null
    }
}

// ── 统一路径（`!` 语法）解析 ──

private data class SpecHit(val loc: Loc, val path: String)

private suspend fun resolveSpec(raw: String, s: Strings): SpecHit? {
    var t = raw.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("host:")) t = t.removePrefix("host:")
    val segs = t.split("!")
    var host = segs[0]
    if (!host.startsWith("/")) throw IllegalStateException(s.filesGotoAbs)
    host = host.trimEnd('/').ifEmpty { "/" }
    var loc: Loc? = null
    var path = "/"

    for (i in 1 until segs.size) {
        val seg = segs[i]
        val colon = seg.indexOf(':')
        if (colon < 0) return null
        val sel = seg.substring(0, colon).trim()
        val inner = seg.substring(colon + 1).ifEmpty { "/" }
        val prev = loc
        loc = when {
            // 已经在只读容器里 → 下一段只能是 ISO 里的 wim/esd
            prev != null && prev.kind == Kind.ISO -> {
                Loc(
                    Kind.WIM, wim = host, inner = path, index = sel.toIntOrNull() ?: 1,
                    label = File(path).name + " #" + (sel.toIntOrNull() ?: 1),
                )
            }
            prev != null -> return null
            else -> when (sniffFileOrExt(host)) {
                "qcow2" -> {
                    val (lba, idx) = findPart(host, sel) ?: return null
                    val fs = scanFs(host, lba) ?: return null
                    Loc(
                        if (fs.startsWith("fat", true)) Kind.FAT else Kind.NTFS,
                        img = host, lba = lba,
                        label = s.filesPartLabel(idx, fs.uppercase()),
                        partIndex = idx,
                    )
                }
                "iso" -> Loc(Kind.ISO, iso = host, label = File(host).name)
                "wim" -> Loc(
                    Kind.WIM, wim = host, index = sel.toIntOrNull() ?: 1,
                    label = File(host).name + " #" + (sel.toIntOrNull() ?: 1),
                )
                "archive" -> Loc(Kind.ARCHIVE, arc = host, label = File(host).name)
                else -> null
            }
        }
        if (loc == null) return null
        path = inner
    }
    val l = loc ?: run {
        if (!File(host).isDirectory && !ShizukuShell.granted()) return null
        return SpecHit(Loc(Kind.HOST, label = s.filesHost), host)
    }
    return SpecHit(l, path)
}

private fun sniffFileOrExt(host: String): String? {
    val f = File(host)
    if (!f.exists()) return null
    return sniffContainer(f)
}

/** 选择器 → (lba, 第几个分区)；`lba123` / `p2` 都认。 */
private suspend fun findPart(img: String, sel: String): Pair<Long, Int>? {
    val r = ToolRunner.exec("part.show", emptyMap(), imagePath = img, writable = false, imageFd = -1)
    val (_, parts) = ImageRepo.parseParts(r) ?: return null
    val used = parts.filterNot { it.isContainer() }
    if (sel.startsWith("lba", true)) {
        val lba = sel.drop(3).toLongOrNull() ?: return null
        val at = used.indexOfFirst { it.lba == lba }
        return if (at >= 0) lba to (at + 1) else null
    }
    // part3 / p3 都认
    val n = sel.removePrefix("part").removePrefix("p").toIntOrNull() ?: return null
    val p = used.getOrNull(n - 1) ?: return null
    return p.lba to n
}

private suspend fun scanFs(img: String, lba: Long): String? {
    val sc = ImageRepo.parseScan(
        ToolRunner.exec(
            "part.scan",
            mapOf("lba" to lba.toString()),
            imagePath = img,
            writable = false,
            imageFd = -1,
        ),
    ) ?: return null
    return if (sc.hasFs) sc.fs else null
}

// ── 属性统计 ──

private suspend fun walkProps(
    p: PaneState,
    names: Set<String>,
    s: Strings,
    onTick: (Int, Int, Long, Long, Int, Boolean) -> Unit,
) {
    var files = 0
    var dirs = 0
    var bytes = 0L
    var alloc = 0L
    var scanned = 0
    var truncated = false

    // 进度回调切回主线程再写 Compose 状态（从 IO 线程写快照虽不崩，但重组时机不保证）
    suspend fun tick() = withContext(Dispatchers.Main) {
        onTick(files, dirs, bytes, alloc, scanned, truncated)
    }

    // 工具端有 `du`（REQ-40）就用它：一条命令算完宿主/分区/ISO/WIM/压缩包，口径统一
    if (ToolCaps.has("du")) {
        var allOk = true
        for (n in names) {
            val r = ToolRunner.exec(
                "du",
                mapOf("src" to p.loc.spec(join(p.path, n))),
                imagePath = null, writable = false, imageFd = -1,
            )
            val d = r.data
            if (!r.ok || d == null) {
                allOk = false
                break
            }
            files += d.optInt("files", 0)
            dirs += d.optInt("dirs", 0)
            bytes += d.optLong("total_bytes", 0L)
            alloc += d.optLong("alloc_bytes", 0L)
            scanned += d.optInt("scanned", 0)
            if (d.optBoolean("truncated", false)) truncated = true
            tick()
        }
        if (allOk) return
        // 半路失败：清零重来，别让数字翻倍
        files = 0; dirs = 0; bytes = 0L; alloc = 0L; scanned = 0; truncated = false
    }

    when (p.loc.kind) {
        Kind.HOST -> withContext(Dispatchers.IO) {
            val stack = ArrayDeque<File>()
            names.forEach { stack.add(File(join(p.path, it))) }
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                scanned++
                if (scanned > 400000) {
                    truncated = true
                    break
                }
                if (cur.isDirectory) {
                    dirs++
                    cur.listFiles()?.forEach { stack.add(it) }
                } else {
                    files++
                    bytes += cur.length()
                }
                if (scanned % 200 == 0) tick()
            }
            tick()
        }
        Kind.NTFS, Kind.FAT -> {
            val queue = ArrayDeque<String>()
            names.forEach { queue.add(join(p.path, it)) }
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                var off = 0
                while (true) {
                    val args = mutableMapOf(
                        "lba" to p.loc.lba.toString(),
                        "path" to dir,
                        "offset" to off.toString(),
                        "limit" to "500",
                        "sizes" to "true",
                    )
                    val r = ToolRunner.exec(
                        if (p.loc.kind == Kind.NTFS) "ntfs.list" else "fat.ls",
                        args,
                        imagePath = p.loc.img,
                        writable = false,
                        imageFd = -1,
                    )
                    val l = runCatching { parse(r) }.getOrNull() ?: break
                    for (e in l.list) {
                        scanned++
                        if (e.dir) {
                            dirs++
                            queue.add(join(dir, e.name))
                        } else {
                            files++
                            bytes += e.size
                        }
                    }
                    off += l.list.size
                    if (!l.more || l.list.isEmpty()) break
                    if (scanned > 200000) {
                        truncated = true
                        break
                    }
                }
                tick()
                if (scanned > 200000) {
                    truncated = true
                    break
                }
            }
            tick()
        }
        Kind.ISO -> {
            val r = ToolRunner.exec(
                "iso.ls",
                mapOf("iso" to (p.loc.iso ?: ""), "path" to p.path, "recursive" to "true", "max" to "4000"),
                imagePath = null, writable = false, imageFd = -1,
            )
            val l = runCatching { parse(r) }.getOrNull()
            if (l != null) {
                for (e in l.list) {
                    scanned++
                    if (e.dir) dirs++ else {
                        files++
                        bytes += e.size
                    }
                }
                truncated = r.data?.optBoolean("truncated", false) ?: false
            }
            tick()
        }
        Kind.WIM -> {
            val args = mutableMapOf(
                "wim" to (p.loc.wim ?: ""),
                "index" to p.loc.index.toString(),
                "path" to p.path,
                "recursive" to "true",
                "limit" to "4000",
            )
            val inn = p.loc.inner
            if (!inn.isNullOrEmpty()) args["inner"] = inn
            val r = ToolRunner.exec("wim.ls", args, imagePath = null, writable = false, imageFd = -1)
            runCatching { parse(r) }.getOrNull()?.let { l ->
                for (e in l.list) {
                    scanned++
                    if (e.dir) dirs++ else {
                        files++
                        bytes += e.size
                    }
                }
                if (l.list.size >= 4000) truncated = true
            }
            tick()
        }
        Kind.ARCHIVE -> {
            // `archive.ls` 每页上限 2000：一页页翻，直到 has_more=false（或撞到 20 万条）
            var off = 0
            var token: String? = p.arcToken
            while (true) {
                val args = mutableMapOf(
                    "archive" to (p.loc.arc ?: ""),
                    "path" to p.path,
                    "recursive" to "true",
                    "offset" to off.toString(),
                    "limit" to "2000",
                )
                if (!token.isNullOrEmpty()) args["index-token"] = token
                var r = ToolRunner.exec("archive.ls", args, imagePath = null, writable = false, imageFd = -1)
                if (!r.ok && (r.code ?: "").contains("INDEX-EXPIRED")) {
                    token = null
                    args.remove("index-token")
                    r = ToolRunner.exec("archive.ls", args, imagePath = null, writable = false, imageFd = -1)
                }
                val l = runCatching { parse(r) }.getOrNull() ?: break
                token = l.token ?: token
                for (e in l.list) {
                    scanned++
                    if (e.dir) dirs++ else {
                        files++
                        bytes += e.size
                    }
                }
                off += l.list.size
                tick()
                if (!l.more || l.list.isEmpty()) break
                if (scanned > 200000) {
                    truncated = true
                    break
                }
            }
        }
        Kind.IMAGE, Kind.WIMIDX -> Unit
    }
}

// ── 打开文件 / 文本 ──

private fun sizeText(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

private val TIME_FMT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 修改时间 → `2026-10-28 21:23`；拿不到返回 null（ISO 目前没有）。 */
private fun timeText(t: Long): String? {
    if (t <= 0) return null
    return synchronized(TIME_FMT) { TIME_FMT.format(java.util.Date(t * 1000)) }
}

/**
 * 路径文本：**超长时省略前面**，永远露出最后几级（`…/Windows/System32`）。
 */
@Composable
private fun PathText(
    path: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(path, maxPx, style) {
            if (maxPx <= 0) {
                path
            } else {
                val c = Constraints(maxWidth = maxPx)
                val whole = measurer.measure(path, style, maxLines = 1, constraints = c)
                if (!whole.hasVisualOverflow) {
                    path
                } else {
                    val segs = path.split('/')
                    var out = path
                    var i = 1
                    while (i < segs.size) {
                        val cand = "…/" + segs.drop(i).joinToString("/")
                        out = cand
                        if (!measurer.measure(cand, style, maxLines = 1, constraints = c)
                                .hasVisualOverflow
                        ) break
                        i++
                    }
                    out
                }
            }
        }
        Text(text = shown, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * 老路径别名 → 真实可读路径。
 */
private fun hostAlias(p: String): String {
    val t = p.trimEnd('/').ifEmpty { "/" }
    return when (t) {
        "/sdcard", "/storage/self/primary", "/storage/emulated" -> "/storage/emulated/0"
        else -> t
    }
}

/** 解析符号链接（/sdcard → /storage/emulated/0、/bin → /system/bin）；解析不了就原样返回。 */
private fun realPath(p: String): String {
    val r = ShizukuShell.execResult("readlink -f " + ShizukuShell.quote(p))
    val out = r?.out?.trim().orEmpty()
    if (r?.ok == true && out.isNotEmpty()) return out
    val f = runCatching { File(p).canonicalFile }.getOrNull()
    return if (f != null && f.exists()) f.absolutePath else p
}

/** dst 和 src 是同一个，或者 dst 在 src 里面（复制目录到自己内部会无限递归）。 */
private fun isSameOrInside(dst: String, src: String): Boolean {
    val a = dst.trimEnd('/')
    val b = src.trimEnd('/')
    if (b.isEmpty()) return false          // 源是根目录时不做这个判断
    return a == b || a.startsWith("$b/")
}

/** 普通应用能不能直接读这个文件/目录（不行就交给 shell）。 */
private fun appCanRead(f: File): Boolean = runCatching {
    f.exists() && (if (f.isDirectory) f.list() != null else f.canRead())
}.getOrDefault(false)

/** 普通应用能不能往 f 所在的目录里写（父目录存在且可写）。 */
private fun appCanWriteInto(f: File): Boolean = runCatching {
    val parent = f.parentFile
    parent != null && parent.exists() && parent.canWrite()
}.getOrDefault(false)

/**
 * 借 Shizuku（shell）搬一个文件/目录。
 */
private suspend fun shellTransfer(src: String, dst: String, move: Boolean, policy: String, s: Strings): String? {
    // 统一走工具端 copy / move（REPLY-55）：冲突策略只有一份实现，宿主/容器行为一致。
    // 之前用 shell cp/mv/tar：重命名规则要自己写一遍，且 tar 依赖 toybox 行为。
    val r = ToolRunner.exec(
        if (move) "move" else "copy",
        buildMap {
            put("src", ImageRepo.hostPath(src))
            put("dst", ImageRepo.hostPath(dst))
            put("recursive", "true")
            put("create_parents", "true")
            put(
                "conflict",
                when (policy) {
                    "overwrite" -> "overwrite"
                    "skip" -> "fail"        // 已存在报错，调用方按"跳过"处理
                    else -> "rename"
                },
            )
            if (!move) put("skip-trashed", "true")
            if (move) put("allow_host_delete", "true")
        },
        imagePath = null,
        writable = true,
        imageFd = -1,
    )
    if (r.ok) {
        if (policy == "skip" && (r.code ?: "").contains("EXIST")) return SHELL_SKIP
        return null
    }
    if (policy == "skip" && (r.code ?: "").contains("EXIST")) return SHELL_SKIP
    return when {
        (r.code ?: "") == "HOST-NOACCESS" || (r.code ?: "").contains("PERM") -> s.filesNoAccess
        (r.code ?: "").contains("READONLY") -> s.filesReadonlyHere
        (r.code ?: "").contains("NOTFOUND") -> s.filesNoSuchDir
        else -> toolErrorText(s, r)
    }
}

private fun listViaShell(path: String, s: Strings): Listing {
    val q = ShizukuShell.quote(path)
    // 根目录要特殊处理：`'/'/*` 会被 shell 展开成 `//apex`（双斜杠）
    val base = if (path == "/") "" else path.trimEnd('/')
    val qb = ShizukuShell.quote(base)
    val cmd = buildString {
        append("ls -ld ").append(q).append(" 2>&1; echo __SEP__; ")
        append("for f in ").append(qb).append("/* ").append(qb).append("/.[!.]*; do ")
        append("[ -e \"\$f\" ] || continue; ")
        append("stat -c \"%A|%s|%Y|%n\" \"\$f\"; done")
    }
    val r = ShizukuShell.execResult(cmd)
        ?: throw IllegalStateException(s.filesNoAccess)

    val sep = r.out.indexOf("__SEP__")
    val head = (if (sep >= 0) r.out.substring(0, sep) else r.out) + "\n" + r.err
    val first = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    when {
        first.contains("Permission denied") || first.contains("not permitted") ->
            throw IllegalStateException(s.filesNoAccess)
        first.contains("No such file") || first.contains("Not a directory") ->
            throw IllegalStateException(s.filesNoSuchDir)
        first.startsWith("d") -> Unit                       // 正常目录，继续
        first.isEmpty() -> throw IllegalStateException(s.filesNoAccess)
        else -> throw IllegalStateException(s.filesNoSuchDir)
    }

    val list = ArrayList<Entry>()
    if (sep >= 0) {
        r.out.substring(sep + "__SEP__".length).lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val f = t.split("|", limit = 4)
            if (f.size < 4) return@forEach
            val mode = f[0]
            if (!(mode.startsWith("d") || mode.startsWith("-") || mode.startsWith("l"))) return@forEach
            val name = f[3].substringAfterLast('/')
            if (name.isEmpty() || name == "." || name == "..") return@forEach
            list += Entry(
                name = name,
                dir = mode.startsWith("d"),
                size = f[1].toLongOrNull() ?: 0L,
                mtime = f[2].toLongOrNull() ?: 0L,
            )
        }
    }
    list.sortWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
    val dirs = list.count { it.dir }
    return Listing(list, list.size, false, files = list.size - dirs, dirs = dirs)
}

/** 压缩到…的格式 + 文件名卡片。 */
@Composable
private fun PackDialog(
    s: Strings,
    writable: Boolean,
    defaultName: String,
    onDismiss: () -> Unit,
    onStart: (fmt: String, name: String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    var fmt by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(defaultName) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }
    val formats = listOf("zip", "7z", "iso")

    WindowDialog(
        show = show,
        title = s.filesPackTitle(formats[fmt].uppercase()),
        summary = if (writable) null else s.filesZipReadonly,
        onDismissRequest = { show = false },
        onDismissFinished = { if (pending) onStart(formats[fmt], name.trim()) else onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChoiceCard(
                options = formats.map { Choice(it.uppercase(), null) },
                selected = fmt,
                onSelect = {
                    fmt = it
                    // 换格式顺手把后缀也换掉（用户改了名字就不动）
                    val base = name.substringBeforeLast('.', name)
                    name = "$base." + formats[it]
                },
            )
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { show = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(s.cancel, style = MiuixTheme.textStyles.button)
                }
                Button(
                    onClick = { pending = true; show = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(s.filesPackStart, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}