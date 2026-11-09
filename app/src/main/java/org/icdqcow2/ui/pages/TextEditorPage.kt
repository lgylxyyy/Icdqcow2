package org.icdqcow2.ui.pages

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import io.github.rosemoe.sora.event.SelectionChangeEvent
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.TempDirs
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.LocalVisualEffects
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 读出来的文件。 */
private data class Loaded(
    val text: String,
    val enc: String,
    val binary: Boolean,
    val bytes: Long,
)

/**
 * 智能读文件：认 BOM → 严格 UTF-8 → 退回 GBK。
 * 顺便判一下"是不是二进制"（前 8 KB 里有没有 NUL）。
 */
private fun readSmart(f: File): Loaded {
    val raw = f.readBytes()
    fun bin(): Boolean {
        val n = minOf(raw.size, 8192)
        for (i in 0 until n) if (raw[i] == 0.toByte()) return true
        return false
    }
    if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
        return Loaded(String(raw, 3, raw.size - 3, Charsets.UTF_8), "UTF-8", false, f.length())
    }
    if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
        return Loaded(String(raw, 2, raw.size - 2, Charsets.UTF_16LE), "UTF-16LE", false, f.length())
    }
    if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
        return Loaded(String(raw, 2, raw.size - 2, Charsets.UTF_16BE), "UTF-16BE", false, f.length())
    }
    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw))
            .toString()
    }.getOrElse {
        runCatching { String(raw, Charset.forName("GBK")) }.getOrElse { String(raw, Charsets.ISO_8859_1) }
    }
    val enc = if (text == String(raw, Charsets.UTF_8)) "UTF-8" else "GBK"
    return Loaded(text, enc, bin(), f.length())
}

private fun charsetOf(name: String): Charset = when (name) {
    "GBK" -> runCatching { Charset.forName("GBK") }.getOrElse { Charsets.UTF_8 }
    "UTF-16LE" -> Charsets.UTF_16LE
    "UTF-16BE" -> Charsets.UTF_16BE
    else -> Charsets.UTF_8
}

/** 超过这个大小只读打开。 */
private const val MAX_EDIT_BYTES = 8L * 1024 * 1024

/** 两个颜色按比例混合（把"主题色 12%"算成不透明色，Sora 的调色板只吃 ARGB）。 */
private fun blend(fg: Color, bg: Color, ratio: Float): Color = Color(
    red = fg.red * ratio + bg.red * (1 - ratio),
    green = fg.green * ratio + bg.green * (1 - ratio),
    blue = fg.blue * ratio + bg.blue * (1 - ratio),
    alpha = 1f,
)

/**
 * 文本编辑器（sora-editor 内核）。
 *
 * 为什么换成 sora：Compose 的 `BasicTextField` 会把**整篇文本一次性排版**，宽度还得有界；
 * 关掉自动换行时要么撞 Compose 的 262143 px 上限直接崩，要么撑出十几万像素的画布卡成幻灯片。
 * sora 是画布式代码编辑器：文本存在自己的数据结构里，**只排版和绘制屏幕里可见的那几行**，
 * 横向滚动只是改偏移量 —— 和 NP 管理器一类编辑器同一个原理。
 *
 * 代价：语法高亮要按它的 `Language` 机制重写（这一版先不做高亮，行号/当前行/查找替换/撤销重做
 * 都是它自带的）。
 */
@Composable
fun TextEditorPage(
    path: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
    /** 从只读位置取出来的副本：保存时交给外层去"挑一个可写位置"。 */
    onSaveTo: ((String) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val cs = MiuixTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val effects = LocalVisualEffects.current

    val f = remember(path) { File(path) }
    val name = remember(path) { f.name }

    var editor by remember(path) { mutableStateOf<CodeEditor?>(null) }
    var loaded by remember(path) { mutableStateOf<Loaded?>(null) }
    var loadErr by remember(path) { mutableStateOf<String?>(null) }
    var dirty by remember(path) { mutableStateOf(false) }
    var enc by remember(path) { mutableStateOf("UTF-8") }
    var readOnly by remember(path) { mutableStateOf(false) }
    var lineCount by remember(path) { mutableIntStateOf(1) }
    // 选中有没有内容：用来弹我们自己的"复制/粘贴/全选"浮条（换掉 sora 自带那套 Material 图标）
    var hasSel by remember { mutableStateOf(false) }

    var wrap by remember { mutableStateOf(true) }
    var gutter by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQ by remember { mutableStateOf("") }
    var replQ by remember { mutableStateOf("") }
    var showSave by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    // 打开
    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) { runCatching { readSmart(f) } }
        r.onSuccess { l ->
            loaded = l
            enc = l.enc
            readOnly = l.bytes > MAX_EDIT_BYTES
            lineCount = l.text.count { it == '\n' } + 1
            if (l.binary) onToast(s.edMonoFail)
            if (readOnly) onToast(s.edTooBig(formatSize(l.bytes)))
        }.onFailure { e -> loadErr = e.message ?: "read failed" }
    }

    // 调色板：整行蓝底用 CURRENT_LINE（sora 自己会把**整行宽度**涂满）
    val scheme = remember(cs) {
        EditorColorScheme().apply {
            setColor(EditorColorScheme.TEXT_NORMAL, cs.onSurface.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER, cs.onSurfaceVariantSummary.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, cs.surface.toArgb())
            // 当前行：淡绿（用户 2026-10-29 要求，原来是淡蓝）
            setColor(EditorColorScheme.CURRENT_LINE, blend(Color(0xFF34C759), cs.surface, 0.16f).toArgb())
            // 语法高亮（自定义 id，见 HiColor）
            setColor(HiColor.COMMENT, cs.onSurfaceVariantSummary.toArgb())
            setColor(HiColor.STRING, (if (cs.surface.luminance() > 0.5f) Color(0xFF1B7F3B) else Color(0xFF7BD88F)).toArgb())
            setColor(HiColor.KEYWORD, cs.primary.toArgb())
            setColor(HiColor.NUMBER, (if (cs.surface.luminance() > 0.5f) Color(0xFFB35C00) else Color(0xFFFFB86B)).toArgb())
            setColor(HiColor.TAG, (if (cs.surface.luminance() > 0.5f) Color(0xFF0B63A8) else Color(0xFF7FC4FF)).toArgb())
            setColor(HiColor.ATTR, (if (cs.surface.luminance() > 0.5f) Color(0xFF8A5A00) else Color(0xFFE0C080)).toArgb())
            setColor(EditorColorScheme.SELECTION_HANDLE, cs.primary.toArgb())
            setColor(EditorColorScheme.TEXT_SELECTED, cs.onSurface.toArgb())
        }
    }

    fun currentText(): String = editor?.text?.toString() ?: loaded?.text ?: ""

    fun doSave() {
        if (readOnly) return
        val txt = currentText()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(f.parentFile, f.name + ".edittmp")
                    tmp.writeBytes(txt.toByteArray(charsetOf(enc)))
                    if (!tmp.renameTo(f)) {
                        f.writeBytes(txt.toByteArray(charsetOf(enc)))
                        tmp.delete()
                    }
                }.isSuccess
            }
            if (ok) {
                dirty = false
                onToast(s.edSaved)
            } else {
                onToast(s.edSaveFail(f.absolutePath))
            }
        }
    }

    fun requestClose() {
        if (dirty) askDiscard = true else onClose()
    }

    BackHandler { requestClose() }

    val barPx = remember { mutableIntStateOf(with(density) { 64.dp.roundToPx() }) }
    val barHeight = with(density) { barPx.intValue.toDp() }
    val meta = buildString {
        append(s.edLines(lineCount, formatSize(loaded?.bytes ?: f.length())))
        if (readOnly) append(" · ").append(s.edReadOnly)
    }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(barHeight + 4.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    loadErr != null -> Text(
                        loadErr!!,
                        style = textStyles.footnote1,
                        color = cs.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    loaded == null -> Text(
                        s.edLoading,
                        style = textStyles.footnote1,
                        color = cs.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            CodeEditor(c).apply {
                                typefaceText = Typeface.MONOSPACE
                                setTextSizePx(with(density) { 14.sp.toPx() })
                                setLineSpacing(with(density) { 3.dp.toPx() }, 1.15f)
                                setTabWidth(4)
                                setScrollBarEnabled(true)
                                setHighlightCurrentLine(true)
                                setLineNumberEnabled(gutter)
                                setWordwrap(wrap)
                                colorScheme = scheme
                                setEditorLanguage(RegexLanguage(codeLangOf(name)))
                                setEditable(!readOnly)
                                setText(loaded?.text.orEmpty())
                                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                                    dirty = true
                                    lineCount = this.text.lineCount
                                }
                                // 自带的选区浮窗是 Material 图标，关掉，换成下面那条 Miuix 的
                                getComponent(
                                    io.github.rosemoe.sora.widget.component.EditorTextActionWindow::class.java,
                                ).isEnabled = false
                                subscribeEvent(SelectionChangeEvent::class.java) { e, _ ->
                                    hasSel = e.isSelected
                                }
                                editor = this
                            }
                        },
                        update = { ed ->
                            ed.setWordwrap(wrap)
                            ed.setLineNumberEnabled(gutter)
                            ed.setEditable(!readOnly)
                            ed.colorScheme = scheme
                            ed.setEditorLanguage(RegexLanguage(codeLangOf(name)))
                        },
                    )
                }
            }
            if (findOpen) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    insideMargin = PaddingValues(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextField(
                            value = findQ,
                            onValueChange = { findQ = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = replQ,
                            onValueChange = { replQ = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val ed = editor ?: return@Button
                                    if (findQ.isEmpty()) return@Button
                                    ed.searcher.search(
                                        findQ,
                                        EditorSearcher.SearchOptions(false, false),
                                    )
                                    ed.searcher.gotoNext()
                                },
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text(s.edNext, style = textStyles.button, maxLines = 1) }
                            Button(
                                onClick = { editor?.searcher?.gotoPrevious() },
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text(s.edPrev, style = textStyles.button, maxLines = 1) }
                            Button(
                                onClick = { editor?.searcher?.replaceThis(replQ) },
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text(s.edReplace, style = textStyles.button, maxLines = 1) }
                            Button(
                                onClick = {
                                    editor?.searcher?.replaceAll(replQ)
                                    dirty = true
                                },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) { Text(s.edReplaceAll, style = textStyles.button, maxLines = 1) }
                        }
                    }
                }
            }
        }

        if (effects) {
            val pageColor = cs.surface
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight * 5f / 6f)
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to pageColor,
                                0.15f to pageColor,
                                1f to Color.Transparent,
                            ),
                        )
                    },
            )
        }
        if (hasSel) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { editor?.copyText() }) {
                    Icon(MiuixIcons.Copy, contentDescription = null, tint = cs.onSurface)
                }
                IconButton(onClick = { editor?.pasteText() }) {
                    Icon(MiuixIcons.Paste, contentDescription = null, tint = cs.onSurface)
                }
                IconButton(onClick = { editor?.selectAll() }) {
                    Icon(MiuixIcons.SelectAll, contentDescription = null, tint = cs.onSurface)
                }
            }
        }

        SmallTopAppBar(
            title = if (dirty) "$name •" else name,
            subtitle = meta,
            color = if (effects) Color.Transparent else cs.surface,
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(MiuixIcons.More, contentDescription = null, tint = cs.onSurface)
                }
            },
            navigationIcon = {
                IconButton(onClick = { requestClose() }) {
                    Icon(MiuixIcons.Back, contentDescription = null, tint = cs.onSurface)
                }
            },
            modifier = Modifier.fillMaxWidth().onSizeChanged { barPx.intValue = it.height },
        )
    }

    // ── ⋮ 菜单 ──
    CardMenuOverlay(
        show = menuOpen,
        items = listOf(
            s.edFind,
            s.edWrap + "：" + if (wrap) s.edOn else s.edOff,
            s.edLineNo + "：" + if (gutter) s.edOn else s.edOff,
            s.edUndo,
            s.edRedo,
            s.edSelectAll,
            s.edCopy,
            s.edPaste,
            s.edSave,
        ),
        selectedIndex = -1,
        topPadding = 56.dp,
        onDismiss = { menuOpen = false },
        onSelected = { i ->
            menuOpen = false
            val ed = editor
            when (i) {
                0 -> findOpen = !findOpen
                1 -> wrap = !wrap
                2 -> gutter = !gutter
                3 -> ed?.undo()
                4 -> ed?.redo()
                5 -> ed?.selectAll()
                6 -> ed?.copyText()
                7 -> ed?.pasteText()
                else -> showSave = true
            }
        },
    )

    // ── 保存卡 ──
    if (showSave) {
        WindowDialog(
            show = true,
            title = s.edSaveTitle,
            onDismissRequest = { showSave = false },
            onDismissFinished = { showSave = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.edSaveTo, style = textStyles.footnote2, color = cs.onSurfaceVariantSummary)
                Text(path, style = textStyles.footnote2, color = cs.onSurface)
                Text(s.edEncoding + "：" + enc, style = textStyles.footnote2, color = cs.onSurfaceVariantSummary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("UTF-8", "GBK").forEach { e ->
                        Button(
                            onClick = { enc = e },
                            colors = if (enc == e) ButtonDefaults.buttonColorsPrimary()
                            else ButtonDefaults.buttonColors(),
                        ) { Text(e, style = textStyles.button, maxLines = 1) }
                    }
                }
                if (onSaveTo != null) {
                    Text(s.edSaveToHint, style = textStyles.footnote2, color = cs.onSurfaceVariantSummary)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { showSave = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(s.cancel, style = textStyles.button) }
                    Button(
                        onClick = {
                            showSave = false
                            if (onSaveTo != null) {
                                // 只读位置取出来的副本：先落到缓存文件，再交给文件页挑位置
                                val txt = currentText()
                                scope.launch {
                                    val tmp = File(TempDirs.stagingDir(ctx), "saveto").apply { mkdirs() }.resolve(f.name)
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { tmp.writeBytes(txt.toByteArray(charsetOf(enc))) }.isSuccess
                                    }
                                    if (ok) {
                                        dirty = false
                                        onSaveTo(tmp.absolutePath)
                                    } else {
                                        onToast(s.edSaveFail(tmp.absolutePath))
                                    }
                                }
                            } else {
                                doSave()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(s.edSave, style = textStyles.button) }
                }
            }
        }
    }

    // ── 未保存确认 ──
    if (askDiscard) {
        WindowDialog(
            show = true,
            title = s.edUnsaved,
            summary = s.edDiscardAsk,
            onDismissRequest = { askDiscard = false },
            onDismissFinished = { askDiscard = false },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { askDiscard = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(s.cancel, style = textStyles.button) }
                Button(
                    onClick = {
                        askDiscard = false
                        showSave = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(s.edSave, style = textStyles.button) }
                Button(
                    onClick = {
                        askDiscard = false
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(s.edDiscard, style = textStyles.button) }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}