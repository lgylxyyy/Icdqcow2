package org.icdqcow2.ui.pages

import androidx.compose.ui.graphics.Color
import org.icdqcow2.core.StorageAccess
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Ok
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.icdqcow2.ui.SectionTextColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import org.icdqcow2.ui.LocalStrings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 功能页（二级页面）的公共积木。
 *
 * 结构：`组标题 → 圆角卡（里面若干行）→ 主按钮 → 底部说明`，
 * 用户在 15 个页面之间切换时不用重新找控件。
 */

@Composable
fun GroupTitle(text: String) {
    SmallTitle(
        text = text,
        textColor = SectionTextColor,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 0.dp),
    )
}

@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 0.dp),
    ) {
        Column(content = content)
    }
}

/** 卡内一行：标题 + 说明 + 右侧控件。 */
@Composable
fun Item(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    /** 标题颜色；null = 主题默认。草稿卡里用它把"新增"染绿、"删除"染红。 */
    titleColor: Color? = null,
    end: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        titleColor = if (titleColor != null) {
            BasicComponentDefaults.titleColor(color = titleColor)
        } else {
            BasicComponentDefaults.titleColor()
        },
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        endActions = end,
    )
}

@Composable
fun Gap() = HorizontalDivider()

/** 分段选择（文件系统、模式等）。 */
@Composable
fun Tabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(
        tabs = options,
        selectedTabIndex = selected,
        onTabSelected = onSelect,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 主操作按钮。`danger` 用于会毁数据的操作。 */
@Composable
fun Action(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    /** 看着是"不可用"（蒙一层），但仍然可点——点了用来告诉用户差什么。 */
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = if (danger) {
            ButtonDefaults.buttonColorsPrimary(
                color = colors.error,
                contentColor = colors.onError,
            )
        } else if (muted) {
            ButtonDefaults.buttonColorsPrimary(color = colors.primary.copy(alpha = 0.45f))
        } else {
            ButtonDefaults.buttonColorsPrimary()
        },
    ) {
        Text(text, style = MiuixTheme.textStyles.button)
    }
}

/** 一句提示。`warn` 时用错误色。 */
@Composable
fun Note(text: String, warn: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (warn) colors.error else colors.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
    )
}

/**
 * 单选卡的一项：主名称 + 一行说明。
 *
 * 对勾在**最左侧**（Miuix `RadioButton`，miuix-classic SingleChoicePreference 同款），
 * 选中项的文字整行变主题色；不用图标、不用分段按钮。
 */
data class Choice(
    val title: String,
    val summary: String? = null,
)

/** 单选卡片：一张卡里若干行，选中行左侧出对勾、文字转主题色。 */
@Composable
fun ChoiceCard(options: List<Choice>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 必须 0：条目自带 12dp 内边距，卡片再留 2dp 的话首/末条按下时高亮会离边缘差一条缝
        insideMargin = PaddingValues(vertical = 0.dp),
    ) {
        Column {
            options.forEachIndexed { i, option ->
                val on = i == selected
                BasicComponent(
                    title = option.title,
                    summary = option.summary,
                    titleColor = BasicComponentColors(
                        color = if (on) colors.primary else colors.onBackground,
                        disabledColor = colors.disabledOnSecondaryVariant,
                    ),
                    summaryColor = BasicComponentColors(
                        color = if (on) colors.primary.copy(alpha = 0.7f) else colors.onSurfaceVariantSummary,
                        disabledColor = colors.disabledOnSecondaryVariant,
                    ),
                    startAction = { RadioButton(selected = on, onClick = null) },
                    insideMargin = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

/** 卡片里的一行，右侧带箭头 —— 点进去还有一层（分区设置…）。 */
@Composable
fun LinkItem(title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Item(
        title = title,
        summary = summary,
        enabled = enabled,
        end = {
            Icon(
                MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}

/** 尺寸单位：输入卡片里可以切。内部一律按 MB 存，GB 只在输入/显示时换算。 */
enum class SizeUnit(val label: String) { MB("MB"), GB("GB") }

/** MB → 输入框里的文字（GB 最多三位小数，去掉多余的 0）。 */
internal fun mbToText(mb: Int, unit: SizeUnit): String =
    if (unit == SizeUnit.MB) {
        mb.toString()
    } else {
        val s = String.format(java.util.Locale.US, "%.3f", mb / 1024.0)
        s.trimEnd('0').trimEnd('.')
    }

/** 输入框文字 → MB。空/非数字返回 null。 */
internal fun textToMb(text: String, unit: SizeUnit): Int? {
    val v = text.toDoubleOrNull() ?: return null
    val mb = if (unit == SizeUnit.GB) v * 1024 else v
    if (mb > Int.MAX_VALUE) return null
    return Math.round(mb).toInt()
}

/**
 * 数字输入框：**单位写在框内最右侧**，文字靠左。
 *
 * [trailing] 给自定义尾槽（单位按钮）；不给就只显示 [unit] 文字。
 * [allowDecimal] 为 true 时（GB）允许一个小数点。
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxDigits: Int = 7,
    allowDecimal: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = { raw ->
            val kept = raw.filter { it.isDigit() || (allowDecimal && it == '.') }
            val dot = kept.indexOf('.')
            val cleaned = if (dot < 0) kept else kept.substring(0, dot + 1) + kept.substring(dot + 1).replace(".", "")
            onValueChange(cleaned.take(maxDigits + 3))
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        // ★ 固定 Decimal：动态切 Number/Decimal 会让系统重启输入会话，输入法会闪一下
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MiuixTheme.textStyles.main.copy(textAlign = TextAlign.Start),
        trailingIcon = {
            if (trailing != null) {
                trailing()
            } else {
                Text(
                    unit,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        },
    )
}

/**
 * 一行进度条（长任务用：`wim.apply` / `iso.extract` …）。
 *
 * 数据来自 `ToolEvents.progress`（工具端事件流，`pct` 已经算好）。
 * 不做动画：工具端节流推、这里直接画，省得跟真实进度"追不上"。
 */
@Composable
fun ProgressBar(pct: Int, text: String) {
    val colors = MiuixTheme.colorScheme
    val p = pct.coerceIn(0, 100)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            if (p > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(p / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primary),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MiuixTheme.textStyles.footnote2,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/**
 * **阻塞式进度卡**（用户 2026-10-29 定）：复制/移动/压缩/删除这类长任务跑的时候，
 * 就在页面正中间弹这张卡，底下页面**点不动**（所有触摸都被这里吃掉），只留一个「取消」。
 *
 * 为什么要挡：以前进度条挂在标题下方，用户还能继续点别的行 → 同一个文件被搬两次、
 * 目标目录写一半被刷新，工具端直接写失败。挡住是最省事也最不容易出错的解法。
 *
 * @param detail 进度条下面那行小字（一般是 `45% · 12.3 MB/s` 或错误详情）
 * @param counts 再下面一行（`12 / 40 项 · 3.2 GB / 9.1 GB`），null 就不显示
 */
@Composable
fun BlockingProgressCard(
    title: String,
    pct: Int,
    detail: String,
    counts: String?,
    cancelText: String,
    onCancel: (() -> Unit)?,
) {
    val colors = MiuixTheme.colorScheme
    // 进度卡也遵守"返回 = 取消"：有取消按钮就当按下它
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
            .background(Color.Black.copy(alpha = 0.35f))
            .then(
                Modifier.pointerInput(Unit) {
                    // 把所有指针事件吃掉：卡在中间的时候，下面的列表/按钮一律收不到点击
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            insideMargin = PaddingValues(18.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MiuixTheme.textStyles.title4, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))
                ProgressBar(pct, detail)
                if (!counts.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        counts,
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(cancelText, style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

/**
 * 「当前操作的文件」卡片——**所有页面共用这一套**（用户 2026-10-26 定）。
 *
 * 标题 `当前操作的文件：<名字>`，副标题是它所在**目录的完整路径**，
 * 点一下用系统文件管理器打开那个文件夹（不再"点一下回到选盘页"）。
 *
 * 只做一件事：看文件在哪、去那个文件夹。**不再放「更换镜像」行**（用户 2026-10-26
 * 明确不要）；要换镜像就退出这一页重进（进页面还是选盘页）。
 */
@Composable
fun CurrentFileCard(
    name: String,
    path: String?,
    onToast: (String) -> Unit,
) {
    val s = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 选盘页可能给的是 content:// uri（没有真实路径时）→ 先还原成路径再算目录
    val real = path?.let {
        if (it.startsWith("content://")) {
            runCatching { StorageAccess.documentToPath(android.net.Uri.parse(it)) }.getOrNull()
        } else {
            it
        }
    }
    val dir = real?.let { if (java.io.File(it).isDirectory) it else java.io.File(it).parent }

    // 和正文块同一个模板：它就是第 0 块（0ms 起步），正文块从 70ms 起依次跟上。
    BlockEnter(0) {
        GroupCard {
            Item(
                title = s.fileCard(name),
                summary = dir ?: s.filePathUnknown,
                onClick = {
                    when {
                        dir == null -> onToast(s.filePathUnknown)
                        // 应用私有目录（默认保存位置）文件管理器进不去，说清楚而不是弹个失败
                        dir.startsWith("/data/") -> onToast(s.filePrivateDir)
                        !StorageAccess.openFolder(ctx, dir) -> onToast(s.fileOpenFailed)
                    }
                },
            )
        }
    }
}

/**
 * 选择型弹窗（列表里点一项就走）。用于"选哪个分区"这类临时选择，
 * 不占用页面里的位置。
 */
@Composable
fun ChoiceDialog(
    title: String,
    summary: String? = null,
    items: List<String>,
    cancelText: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    /**
     * 哪几行**不能做**（灰着）。铁律是"灰着也要能点"：点下去不关动作，
     * 而是把"为什么不行"交给 [onDisabled]（页面拿来弹提示）。
     * 默认全部可做。
     */
    enabled: List<Boolean> = items.map { true },
    onDisabled: (Int) -> Unit = {},
    /**
     * 每行末尾的**灰色**补充（如 `（x64）`）。用 AnnotatedString 拼，接在标题后面、
     * 会跟着自动换行 —— 比拆成两列干净（长名字不会把括号挤到下一行）。
     */
    suffixes: List<String?> = emptyList(),
) {
    var show by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(-1) }
    var blocked by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) { show = true }

    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = { show = false },
        onDismissFinished = {
            when {
                picked >= 0 -> onSelect(picked)
                blocked >= 0 -> onDisabled(blocked)
                else -> onDismiss()
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, label ->
                val on = enabled.getOrElse(i) { true }
                val suffix = suffixes.getOrElse(i) { null }
                val shown = if (suffix.isNullOrEmpty()) {
                    androidx.compose.ui.text.AnnotatedString(label)
                } else {
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(label)
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            ),
                        ) { append(suffix) }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (on) picked = i else blocked = i
                            show = false
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        shown,
                        style = MiuixTheme.textStyles.main,
                        color = if (on) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { show = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(cancelText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 同名冲突弹窗：选项 + 一个「后续都这样」的复选框。
 *
 * 跟 [ChoiceDialog] 的区别只有两点：回调多带一个 `remember`，以及多一行复选框。
 * 注意 `show` 只切状态、组件常驻，退场动画播完才回调（和其它弹窗一致）。
 */
@Composable
fun ConflictDialog(
    title: String,
    summary: String?,
    items: List<String>,
    rememberText: String,
    cancelText: String,
    onSelect: (index: Int, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    var picked by remember { mutableIntStateOf(-1) }
    var remember by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }

    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = { show = false },
        onDismissFinished = {
            if (picked >= 0) onSelect(picked, remember) else onDismiss()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, label ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            picked = i
                            show = false
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            // 「后续都这样」——勾上就把选择写进设置（和设置页的开关是同一个值）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { remember = !remember }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    state = if (remember) ToggleableState.On else ToggleableState.Off,
                    onClick = { remember = !remember },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    rememberText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { show = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(cancelText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 文本输入弹窗（改卷标用）。
 *
 * 和 [NumberDialog] 一样：`show` 只切状态、组件常驻，退场动画播完才回调。
 */
@Composable
fun LabelDialog(
    title: String,
    hint: String,
    initial: String,
    maxLength: Int = 32,
    confirmText: String,
    cancelText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    /**
     * 打开时自动选中 `[0, selectUpTo)` —— 重命名用它选中"主名"，**不含后缀**。
     * 这样用户按下输入法的删除键一次就能把主名清空，后缀不会被误删。
     */
    selectUpTo: Int? = null,
    /** 打开就聚焦并弹出输入法（重命名 / 跳转路径都要，用户 2026-10-29 明确要求）。 */
    autoFocus: Boolean = true,
) {
    var show by remember { mutableStateOf(false) }
    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial,
                selection = TextRange(0, (selectUpTo ?: initial.length).coerceIn(0, initial.length)),
            ),
        )
    }
    var pending by remember { mutableStateOf(false) }
    var selApplied by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { show = true }

    // 焦点和输入法要等对话框**内容进树**再要（`show=true` 之前 TextField 还不存在，
    // 提前 requestFocus 会抛、keyboard.show() 也是空操作）。3~4 帧后基本就绪，
    // 没就绪就再等一帧重试，最多 12 次。
    LaunchedEffect(show) {
        if (!show || !autoFocus) return@LaunchedEffect
        for (i in 0 until 12) {
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus() }.isSuccess) {
                keyboard?.show()
                if (selectUpTo != null) {
                    text = text.copy(
                        selection = TextRange(0, selectUpTo.coerceIn(0, text.text.length)),
                    )
                }
                return@LaunchedEffect
            }
        }
    }

    WindowDialog(
        show = show,
        title = title,
        summary = hint,
        onDismissRequest = {
            keyboard?.hide()
            show = false
        },
        onDismissFinished = {
            keyboard?.hide()
            if (pending) onConfirm(text.text.trim()) else onDismiss()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = text,
                onValueChange = {
                    text = it.copy(text = it.text.take(maxLength))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // 选区在**拿到焦点之后**再设一次：入场动画里 TextField 自己会 requestFocus
                    // 并把光标挪到末尾，抢在它前面设的会被覆盖（实测）。
                    .onFocusChanged { st ->
                        if (st.isFocused && !selApplied && selectUpTo != null) {
                            selApplied = true
                            text = text.copy(
                                selection = TextRange(0, selectUpTo.coerceIn(0, text.text.length)),
                            )
                        }
                    }
                    .focusRequester(focusRequester),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        show = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(cancelText, style = MiuixTheme.textStyles.button)
                }
                Button(
                    onClick = {
                        keyboard?.hide()
                        pending = true
                        show = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(confirmText, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/**
 * 贴着某一行的下拉浮层（**无全屏蒙层**）。
 *
 * 用 Popup 画在独立窗口：不参与布局（不会把行撑高、不会推下面的卡片），
 * 也不像 Miuix 的 Spinner 那样给整屏加一层压暗。
 * 位置贴着锚点右上角，弹层会**盖住**右侧的 ⇅ 图标。
 */
/** 下拉浮层里那张小卡片（紧凑、带对勾）。 */
/**
 * 弹层定位：**以被点的那一行为基准**。
 *
 * - 右边缘 = 行右边缘 − [inset]；
 * - 向下弹：上边缘 = 行上边缘 + [inset]（盖住这行的一部分）；
 * - 向下放不下时翻到上方：下边缘 = 行下边缘 − [inset]。
 */
private class RowOverlapPositionProvider(
    private val insetPx: Int,
    private val marginPx: Int,
    /** 浮层内容相对卡片的留白（阴影用的），定位时要抵消掉。 */
    private val padPx: Int = 0,
    private val dxPx: Int = 0,
    private val dyPx: Int = 0,
) : top.yukonga.miuix.kmp.basic.PopupPositionProvider {

    override fun getMargins(): PaddingValues = PaddingValues(0.dp)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align,
    ): IntOffset {
        // 右边缘 = 行右边缘 − inset（之前漏了这一项，所以水平方向一直贴死）
        val x = (anchorBounds.right - popupContentSize.width - insetPx + padPx + dxPx)
            .coerceAtLeast(windowBounds.left + marginPx)
        val down = anchorBounds.top + insetPx - padPx + dyPx
        val up = anchorBounds.bottom - insetPx - popupContentSize.height + padPx + dyPx
        val y = if (down + popupContentSize.height <= windowBounds.bottom - marginPx) down
        else up.coerceAtLeast(windowBounds.top + marginPx)
        return IntOffset(x, y)
    }
}

/**
 * 官方下拉浮层，**只是关掉了全屏压暗**。
 *
 * `OverlayListPopup` 本身就有 `enableWindowDim`（源码 ListPopupLayout.kt:75），
 * 默认 true；设 false 就不画那层蒙层，而定位/动画/尺寸全用官方实现：
 * `ListPopupDefaults.dropdownPositionProvider()`（放不下自动翻上）、
 * `MinWidth = 200.dp`、行样式 `DropdownImpl`。
 */
/**
 * 【组件模板】行 + 官方下拉浮层。
 *
 * 用法（任何"点一行 → 从行里弹出选项卡片"的地方都照这个抄）：
 * ```
 * var open by remember { mutableStateOf(false) }
 * Box {
 *     OptionRow(
 *         title = "历史文件显示数量",
 *         summary = "当前显示 3 条",
 *         value = "3 条",
 *         onClick = { open = !open },
 *     )
 *     MiuixOptionMenu(
 *         show = open,
 *         items = listOf("不显示", "3 条", "5 条"),
 *         selectedIndex = sel,
 *         onDismiss = { open = false },
 *         onSelected = { i -> ...; open = false },
 *     )
 * }
 * ```
 *
 * 行为要点（都是踩过的坑）：
 * - 用 `WindowListPopup`（独立窗口）而不是 Overlay 版：Overlay 需要 Miuix 的 Scaffold 才能定位；
 * - `enableWindowDim = false`：不要全屏压暗（库里默认开，没有别的开关）；
 * - 底色**只在外层卡片刷一次**，行透明：否则每行各画一块，会有"半块阴影"；
 * - 选中标记用 `MiuixIcons.Basic.Check` + 主题色，和官方下拉一致；
 * - 位置以**被点的那一行**为基准，右边缘/上边缘（或上翻时的下边缘）各内缩 [inset]。
 */
@Composable
fun MiuixOptionMenu(
    show: Boolean,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
    /** 位置微调（相对锚点右下角）。 */
    offsetX: androidx.compose.ui.unit.Dp = 0.dp,
    offsetY: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val shadowPad = 24.dp
    val shadowPadPx = with(d) { shadowPad.roundToPx() }
    // 亮色：纯白卡片；暗色：保持原来的 surfaceContainerHighest
    val cardColor = if (MiuixTheme.colorScheme.surface.luminance() > 0.5f) Color.White
    else MiuixTheme.colorScheme.surfaceContainerHighest
    WindowListPopup(
        show = show,
        enableWindowDim = false,
        popupPositionProvider = RowOverlapPositionProvider(
            insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { inset.roundToPx() },
            marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() },
            padPx = shadowPadPx,
            dxPx = with(androidx.compose.ui.platform.LocalDensity.current) { offsetX.roundToPx() },
            dyPx = with(androidx.compose.ui.platform.LocalDensity.current) { offsetY.roundToPx() },
        ),
        onDismissRequest = onDismiss,
        minWidth = ListPopupDefaults.MinWidth,
    ) {
        Column(
            modifier = Modifier
                // 窗口按内容裁剪：先留出 24dp 给阴影，再画同半径的阴影
                .padding(shadowPad)
                .width(ListPopupDefaults.MinWidth)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(cardColor),
        ) {
            items.forEachIndexed { i, label ->
                val first = i == 0
                val last = i == items.lastIndex
                val selected = i == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelected(i)
                            onDismiss()
                        }
                        .padding(
                            start = DropdownDefaults.InsideHorizontalPadding,
                            end = DropdownDefaults.InsideHorizontalPadding,
                            top = if (first) DropdownDefaults.FirstLastVerticalPadding
                            else DropdownDefaults.MiddleVerticalPadding,
                            bottom = if (last) DropdownDefaults.FirstLastVerticalPadding
                            else DropdownDefaults.MiddleVerticalPadding,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.main,
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            MiuixIcons.Basic.Check,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(DropdownDefaults.CheckIconSize),
                        )
                    }
                }
            }
        }
    }
}

/** 【组件模板】带"当前值 + ⇅"的行，配合 [MiuixOptionMenu] 使用。 */
@Composable
fun OptionRow(
    title: String,
    summary: String?,
    value: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        // ★ 点击作用在**整行**：以前点右边那个胶囊会单独闪一下（小阴影），
        //   现在统一成"整行/整卡"的按压反馈
        endActions = {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    value,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Icon(
                    MiuixIcons.Basic.ArrowUpDown,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(
                        width = DropdownDefaults.ArrowSize.width,
                        height = DropdownDefaults.ArrowSize.height,
                    ),
                )
            }
        },
        onClick = onClick,
    )
}

/** 行尾的 ⇅（官方 `DropdownArrowEndAction` 的等价实现）。 */
@Composable
fun RowScope.DropdownArrow() {
    Icon(
        MiuixIcons.Basic.ArrowUpDown,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(
            width = DropdownDefaults.ArrowSize.width,
            height = DropdownDefaults.ArrowSize.height,
        ),
    )
}

/**
 * 危险操作确认卡片（删除分区、格式化这类不可撤销的动作）。
 *
 * 用法：调用方用 `if (confirm) { ConfirmDialog(...) }` 控制"在不在"，
 * 卡片自己负责 `show` 的进出动画——取消/确认先播退场，`onDismissFinished`
 * 之后才回调，调用方那时再把状态置 false（否则会"啪"一下消失）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    summary: String,
    confirmText: String,
    cancelText: String,
    danger: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }

    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = { show = false },
        onDismissFinished = { if (accepted) onConfirm() else onDismiss() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { show = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(cancelText, style = MiuixTheme.textStyles.button)
            }
            Button(
                onClick = { accepted = true; show = false },
                modifier = Modifier.weight(1f),
                colors = if (danger) {
                    ButtonDefaults.buttonColorsPrimary(color = colors.error, contentColor = colors.onError)
                } else {
                    ButtonDefaults.buttonColorsPrimary()
                },
            ) {
                Text(confirmText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 切页动画：**自下而上弹出**（没有左右滑动）。
 *
 * 旧内容立刻移除（`ExitTransition.None`），新内容从下方 1 屏高度处滑上来并淡入；
 * 返回时相反（自上而下落回）。所有"选文件 → 正文"、向导各步都用它。
 */
fun androidx.compose.animation.AnimatedContentTransitionScope<*>.slidePage(
    forward: Boolean,
): androidx.compose.animation.ContentTransform {
    val dir = if (forward) 1 else -1
    return (
        androidx.compose.animation.slideInVertically(
            androidx.compose.animation.core.tween(
                280,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            ),
        ) { it * dir } +
            androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(180),
            )
        ).togetherWith(androidx.compose.animation.ExitTransition.None)
}

/**
 * 块级入场：**自下而上浮现**（位移 24dp + 淡入 260ms），**不改布局高度**。
 *
 * - 不用高度展开：那是"从上往下把内容揭开"，观感完全不同；
 * - [index] 错峰：0 → 0ms，1 → 70ms，2 → 140ms…（封顶 210ms），一整列块会依次托上来；
 * - 配合 `key(...)` 用，块"首次出现"就播一次 —— 数据晚到的块也有动画。
 */
@Composable
fun BlockEnter(index: Int = 0, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * 70L).coerceAtMost(350L))
        t.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            translationY = (1f - t.value) * 24.dp.toPx()
            alpha = t.value
        },
    ) {
        // 必须自己再排一层 Column：调用方传进来的往往是**一串**卡片/说明
        // （原来直接写在 SmallTitlePage 的 Column 里）。若这里用 Box，它们会全部叠在
        // 同一个位置 —— 就是"显示错版"。间距 12dp 与 SmallTitlePage 内部保持一致。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
fun ConfirmCheckDialog(
    title: String,
    summary: String,
    checkLabel: String,
    confirmText: String,
    cancelText: String,
    danger: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    // 每次弹出来都从"未勾选"开始（用户要求：默认不保存）
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }

    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = { show = false },
        onDismissFinished = { if (accepted) onConfirm(checked) else onDismiss() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { checked = !checked },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = { checked = !checked },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                checkLabel,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { show = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(cancelText, style = MiuixTheme.textStyles.button)
            }
            Button(
                onClick = { accepted = true; show = false },
                modifier = Modifier.weight(1f),
                colors = if (danger) {
                    ButtonDefaults.buttonColorsPrimary(color = colors.error, contentColor = colors.onError)
                } else {
                    ButtonDefaults.buttonColorsPrimary()
                },
            ) {
                Text(confirmText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 数字输入卡片（Miuix `WindowDialog`）。
 *
 * ★ 动画：`WindowDialog` 的进出动画由 `show` 驱动——**组件要一直在位**，
 *   只切 `show`（Miuix 官方示例就是这么用的）。先在首帧把 show 置 true 播进入动画；
 *   取消/确定只把 show 置 false，等 `onDismissFinished` 真正回调页面。
 *
 * ★ 单位浮层**不开新窗口**（不用 Popup）：开新窗口会让输入框失焦、输入法闪一下。
 *   它就是卡片内的一张浮层，靠 `zIndex` 压在上面。
 */
@Composable
fun NumberDialog(
    title: String,
    hint: String,
    initial: Int,
    min: Int,
    max: Int,
    allowZero: Boolean,
    defaultUnit: SizeUnit = SizeUnit.MB,
    /** 没动过输入框时落的值（默认等于 initial）。 */
    untouchedValue: Int = initial,
    cancelText: String,
    confirmText: String,
    quick: List<Pair<String, Int>> = emptyList(),
    deleteText: String? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (value: Int, clamped: Boolean) -> Unit,
    onInvalid: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var unit by remember(title) { mutableStateOf(defaultUnit) }
    var text by remember(title) { mutableStateOf(mbToText(initial, defaultUnit)) }
    var menu by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }

    // 确定/取消都先走关闭动画，动画结束再回调页面
    var pending by remember { mutableStateOf<Int?>(null) }
    var pendingClamped by remember { mutableStateOf(false) }
    // 删除也要走退场动画：先播动画，onDismissFinished 里才真的删
    var deletePending by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val parsedMb = textToMb(text, unit)
    val usable = parsedMb != null && (parsedMb != 0 || allowZero)

    LaunchedEffect(Unit) { show = true }
    BackHandler {
        // 返回键：浮层开着就只收浮层
        if (menu) menu = false else show = false
    }

    fun switchUnit(target: SizeUnit) {
        if (target == unit) return
        val mb = textToMb(text, unit)
        if (mb != null) text = mbToText(mb, target)
        unit = target
    }

    WindowDialog(
        show = show,
        title = title,
        summary = hint,
        onDismissRequest = {
            // 点对话框外（scrim）时：菜单开着就只收菜单，别把整个对话框关掉
            if (menu) menu = false else show = false
        },
        onDismissFinished = {
            val v = pending
            when {
                deletePending -> onDelete?.invoke()
                v != null -> onConfirm(v, pendingClamped)
                else -> onDismiss()
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().dismissCardDropdownOnTap()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField(
                    value = text,
                    onValueChange = { text = it },
                    unit = unit.label,
                    allowDecimal = unit == SizeUnit.GB,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) menu = false },
                    trailing = {
                        // 胶囊 + 就地挂下拉：锚点就是胶囊本身，位置规则与设置页/建盘页一致
                        Box {
                            UnitPill(unit.label) { menu = !menu }
                            CardDropdown(
                                show = menu,
                                items = SizeUnit.entries.map { it.label },
                                selectedIndex = SizeUnit.entries.indexOf(unit),
                                onDismiss = { menu = false },
                                onSelected = { i ->
                                    switchUnit(SizeUnit.entries[i])
                                    menu = false
                                },
                            )
                        }
                    },
                )

                if (quick.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quick.forEach { (label, value) ->
                            Chip(label, Modifier.weight(1f)) { text = mbToText(value, unit) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { show = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(cancelText, style = MiuixTheme.textStyles.button)
                    }
                    Button(
                        onClick = {
                            // 没改过输入框 → 用原值，避免 GB 换算的 ±1 MB 抖动触发"已自动调整"
                            val mb = if (text == mbToText(initial, unit)) untouchedValue else parsedMb
                            if (mb == null || (mb == 0 && !allowZero)) {
                                onInvalid()
                            } else {
                                val target = if (mb == 0) 0 else mb.coerceIn(min, max)
                                pending = target
                                pendingClamped = target != mb
                                show = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(
                            // 值不合法：蒙一层灰的蓝（仍可点，点了只提示）
                            color = if (usable) colors.primary else colors.primary.copy(alpha = 0.45f),
                        ),
                    ) {
                        Text(confirmText, style = MiuixTheme.textStyles.button)
                    }
                }

                if (onDelete != null && deleteText != null) {
                    // 危险操作用和「格式化」同款的大红按钮（Miuix Button + error 配色），
                    // 不要用红字描边：深色下几乎看不见，会被当成按钮没了。
                    Action(text = deleteText, danger = true) {
                        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        deletePending = true
                        show = false
                    }
                }
            }

        }
    }
}

/** 小圆角快捷值按钮（对话框里用；等分宽度，两端对齐）。 */
@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = colors.primary,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** 空态：图标 + 一句主文案 + 一句说明。 */
@Composable
fun EmptyCard(icon: ImageVector, title: String, desc: String) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariantSummary, modifier = Modifier.size(34.dp))
            Text(title, style = MiuixTheme.textStyles.title4, color = colors.onSurface)
            Text(
                desc,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** 键值对一行（放在 [GroupCard] 内部，不占满整行触控）。 */
@Composable
fun KeyValue(key: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MiuixTheme.textStyles.main, color = colors.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MiuixTheme.textStyles.body2,
            color = colors.onSurfaceVariantSummary,
        )
    }
}
/**
 * 卡内一行：左侧先放一个图标（文件页用），其余和 [Item] 一样。
 *
 * Miuix 的 `BasicComponent` 本身有 `startAction` 槽，[Item] 没暴露；
 * 这里只包一层，别在页面里直接写 BasicComponent（那样会绕过统一的配色默认值）。
 */
@Composable
fun IconItem(
    icon: @Composable () -> Unit,
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        titleColor = BasicComponentDefaults.titleColor(),
        summary = summary,
        onClick = onClick,
        startAction = { icon() },
    )
}

/**
 * 卡片式浮层菜单（**透明窗口**）。
 *
 * 为什么不用 `WindowListPopup`：它有自己的一层窗口底色，我们为了 24dp 阴影在外面留的
 * padding 会被这层底色填成一块实心方块——看着就是把下面的内容挡住了。
 * 这里用 Compose 原生 `Popup(focusable = true)`：
 *   · 窗口本身透明，padding 区域完全空出来，阴影能自然落到后面的内容上；
 *   · `focusable = true` → 点外部收回，而且**点击被吃掉**，不会穿到下面的文件行。
 */
// ───────────────────────────────────────────────────────────────────────────
// 卡片菜单（设置页下拉 / 文件页 ⋮ 共用一套）
//
// 踩过的坑写在最前面，动之前先读：
//
//  ① **模糊千万不能关进小图层**。老写法是
//         .graphicsLayer { alpha = …; scaleX = … }.softShadow(…)
//     等于把阴影画在「只有卡片那么大」的图层纹理里。模糊本来就该往外扩，
//     扩到纹理边界就被 clamp（边缘像素被重复采样）→ 卡片圆角那圈发黑发厚。
//     换 BlurMaskFilter、换官方 dropShadow、去掉缩放都没用，因为图层还在。
//  ② 现在的图层顺序是固定的，**不要调整**：
//         offset{}        位移：布局偏移，不建图层
//         dropShadow      阴影：画进父画布，不套小图层
//         alpha           淡入：图层里只剩纯色卡片，没有模糊
//         clip + background
//  ③ 动画只动「阴影自己的参数」（颜色 alpha、offset）和布局位移。
//     官方文档推荐的也是动 Shadow 参数，而不是缩放带阴影的图层。
// ───────────────────────────────────────────────────────────────────────────
// 卡片下拉（全项目唯一实现）
//
// 用户 2026-11-09 定的规格，改之前先读：
//   · 动效：弹出 spring(0.55, MediumLow) 带回弹；收回 spring(0.85, Medium) 播完才移除
//   · 位移：scale 0.82→1 + 10dp 对角，transformOrigin(1,0)（从锚点那一角长出来）
//   · 阴影：静态四周阴影，不随动画改 alpha
//   · 条目贴边：上下不留白，圆角裁剪让按压高亮跟着圆角走
//   · 震动：打开一次（在胶囊 [UnitPill] 里）+ 选中一次，共两次
//   · 收回：点卡片外任何地方都收回，但**不消费**点击 —— 下层组件照常生效
//
// 两个位置模式（用户要求"除 GB/MB 外其他卡片的位置和方向不变"）：
//   [CardDropdown]       贴锚点：设置页行、建盘页输入框、对话框里的单位胶囊
//   [CardDropdownTopEnd] 钉在容器右上角：文件页 ⋮、编辑器
//
// ⚠️ 图层顺序：graphicsLayer（缩放/淡入）在外，阴影在内。不要用 dropShadow：它的模糊
//    一旦被关进缩放小图层就会被 clamp，卡片圆角那圈发黑（踩过）。
// ───────────────────────────────────────────────────────────────────────────

/** 卡片下拉的展开进度。`visible = false` 表示退场播完了，调用方别再渲染。 */
private class CardMenuAnim(val visible: Boolean, val t: Float)

@Composable
private fun rememberCardMenuAnim(show: Boolean): CardMenuAnim {
    val p = remember { Animatable(0f) }
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            if (!rendered) {
                rendered = true
                // 先以 t = 0 组出来，下一帧再开始播；否则首帧就是终态，看不到动画
                withFrameNanos { }
            }
            p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        } else if (rendered) {
            // 收回：动画**跑完**才移除，别用 delay 硬删（那样看不到过程）
            p.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium))
            rendered = false
        }
    }
    return CardMenuAnim(rendered, p.value)
}

/**
 * 当前打开的卡片下拉（同一时刻只会有一个）。
 *
 * 为什么要全局登记：收回旁听器必须挂在**页面根**（祖先）上，而菜单是页面深处的组件 ——
 * Compose 没有"向上挂修饰符"的 API。所以由菜单登记、页面根（[SmallTitlePage] /
 * [LargeTitlePage]）和对话框内容统一旁听，见 [dismissCardDropdownOnTap]。
 */
object CardDropdownState {
    private var owner: Any? = null
    private var dismisser: (() -> Unit)? = null

    /**
     * 登记"点外面收回"。
     *
     * 不需要"放行打开手势"：打开发生在 UP 的 Main 阶段，而旁听器在同一次事件派发的
     * Final 阶段跑 —— 那时 [open] 还没被调用（它要等重组后的 DisposableEffect），
     * 所以打开那一下不会被自己关掉。
     */
    fun open(token: Any, dismiss: () -> Unit) {
        owner = token
        dismisser = dismiss
    }

    fun close(token: Any) {
        if (owner === token) {
            owner = null
            dismisser = null
        }
    }

    /** 旁听器每次"抬起"调一次；菜单没开就是空操作。 */
    fun dispatchUp() {
        dismisser?.invoke()
    }

    /** 测：当前是否有菜单打开（对话框用它决定 scrim 点击先收谁）。 */
    val isOpen: Boolean get() = dismisser != null
}

/**
 * "点卡片外收回" 的旁听器。挂在页面/对话框内容的**祖先**上，随时装、不消费事件。
 *
 * 为什么用 `PointerEventPass.Final` + 抬起时收回：
 *   · 不消费 → 点到的按钮/输入框照常生效（"选组件的同时收回卡片"）；
 *   · 抬起时下层已经处理完 → 点胶囊自己时它已经把菜单关掉，这里再调只会是空操作，
 *     不会出现"关了又开"；
 *   · 卡片本体在独立 Popup 窗口里，点它的事件根本不进这个窗口 —— 不用判断坐标。
 */
fun Modifier.dismissCardDropdownOnTap(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Final)
            if (e.changes.any { !it.pressed && it.previousPressed }) CardDropdownState.dispatchUp()
        }
    }
}

/**
 * 卡片本体。两个位置模式共用，保证样式完全一致。
 *
 * 阴影用平台 elevation（`Modifier.shadow`）而不是模糊：静态、不随进度变，四周都有。
 */
@Composable
private fun CardDropdownSurface(
    items: List<String>,
    selectedIndex: Int,
    width: androidx.compose.ui.unit.Dp,
    t: Float,
    onSelected: (Int) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    val p = t.coerceIn(0f, 1f)
    val cardColor = if (colors.surface.luminance() > 0.5f) Color.White
    else colors.surfaceContainerHighest
    val drift = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        Modifier.graphicsLayer {
            val s = 0.82f + 0.18f * p
            scaleX = s
            scaleY = s
            translationX = (1f - p) * drift      // 从右上 → 左下
            translationY = -(1f - p) * drift
            alpha = p
            transformOrigin = TransformOrigin(1f, 0f)
        },
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .shadow(elevation = 14.dp, shape = shape, clip = false)
                .clip(shape)
                .background(cardColor),
        ) {
            items.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 选中一次震动（与打开那次一起，构成用户要求的两次）
                            haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            onSelected(i)
                        }
                        // 条目贴边：卡片上下不留白，否则首尾条目外会露出"白条"
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.main,
                        color = if (selected) colors.primary else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            MiuixIcons.Basic.Check,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(DropdownDefaults.CheckIconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 贴锚点的卡片下拉。
 *
 * 定位规则（用户口径，**不要改**）：向下开时卡片顶边 = 条目顶边 + 16dp、右边 = 条目右边；
 * 下面放不下才翻到上方。
 *
 * `focusable = false`：不抢焦点，点外面的事件才能落到下层组件（下层照常生效），
 * 收回交给 [dismissCardDropdownOnTap]；返回键用宿主窗口的 BackHandler 兜。
 */
@Composable
fun CardDropdown(
    show: Boolean,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
    width: androidx.compose.ui.unit.Dp = ListPopupDefaults.MinWidth,
    offsetX: androidx.compose.ui.unit.Dp = 0.dp,
    offsetY: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val anim = rememberCardMenuAnim(show)
    val token = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val haptic = LocalHapticFeedback.current

    // 打开这一次震动放在菜单里（而不是胶囊里）：不管用胶囊、整行还是 ⋮ 打开，都震一次
    LaunchedEffect(show) { if (show) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn) }

    // 登记给页面根的旁听器（同一时刻只有一个菜单）
    DisposableEffect(show, token) {
        if (show) CardDropdownState.open(token, latestDismiss)
        else CardDropdownState.close(token)
        onDispose { CardDropdownState.close(token) }
    }
    // 系统返回键：菜单开着就只收菜单（Popup 不抢焦点，所以要挂在宿主窗口上）
    if (show) BackHandler { onDismiss() }
    if (!anim.visible) return

    val d = LocalDensity.current
    val pad = 24.dp                       // 窗口四周留白给阴影
    val padPx = with(d) { pad.roundToPx() }
    val dx = with(d) { offsetX.roundToPx() }
    val dy = with(d) { offsetY.roundToPx() }
    val marginPx = with(d) { 8.dp.roundToPx() }
    val gapPx = with(d) { 16.dp.roundToPx() }

    Popup(
        popupPositionProvider = remember(dx, dy, padPx, gapPx, marginPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val cardW = popupContentSize.width - 2 * padPx
                    val cardH = popupContentSize.height - 2 * padPx
                    val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                    val x = (anchorBounds.right - cardW - padPx + dx).coerceIn(marginPx, maxX)

                    val downTop = anchorBounds.top + gapPx + dy
                    val upTop = anchorBounds.bottom - gapPx - cardH + dy
                    // 放不放得下要用**卡片**判断，不能用带阴影留白的窗口高度
                    val fitsDown = downTop + cardH <= windowSize.height - marginPx
                    val maxTop = windowSize.height - marginPx - cardH
                    val cardTop = (if (fitsDown) downTop else upTop)
                        .coerceIn(marginPx, maxTop.coerceAtLeast(marginPx))
                    return IntOffset(x, cardTop - padPx)
                }
            }
        },
        properties = PopupProperties(focusable = false),
    ) {
        Box(Modifier.padding(pad)) {
            CardDropdownSurface(items, selectedIndex, width, anim.t) { i ->
                onSelected(i)
                onDismiss()
            }
        }
    }
}

/**
 * 钉在容器右上角的卡片下拉（文件页 ⋮ / 编辑器）。
 *
 * 位置由调用方给的 padding 决定，**与改造前完全一致**；只换了样式、动效、震动和收回语义。
 * 旁听器直接装在这一层的全屏 Box 上。
 */
@Composable
fun CardDropdownTopEnd(
    show: Boolean,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    endPadding: androidx.compose.ui.unit.Dp = 16.dp,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    val anim = rememberCardMenuAnim(show)
    val token = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(show) { if (show) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn) }
    DisposableEffect(show, token) {
        if (show) CardDropdownState.open(token, latestDismiss)
        else CardDropdownState.close(token)
        onDispose { CardDropdownState.close(token) }
    }
    if (show) BackHandler { onDismiss() }
    if (!anim.visible) return

    Box(
        modifier
            .fillMaxSize()
            .dismissCardDropdownOnTap(),
    ) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = topPadding, end = endPadding)) {
            CardDropdownSurface(items, selectedIndex, 200.dp, anim.t) { i ->
                onSelected(i)
                onDismiss()
            }
        }
    }
}

/**
 * 输入框右侧的"单位 / 格式"胶囊：文字 + ⌃⌄，点按是圆角高亮 + 一次轻震。
 *
 * 建盘页的容器格式、对话框的 MB/GB 都用它 —— 样式必须一致（用户 2026-11-09 定）。
 * 以前格式那边是裸 `Text` 加 `clickable`，高亮是压在文字背后的矩形，看着像"文字有阴影"。
 */
@Composable
fun UnitPill(text: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
        Icon(
            MiuixIcons.Basic.ArrowUpDown,
            contentDescription = null,
            tint = colors.onSurfaceVariantSummary,
            modifier = Modifier.size(10.dp, 16.dp),
        )
    }
}