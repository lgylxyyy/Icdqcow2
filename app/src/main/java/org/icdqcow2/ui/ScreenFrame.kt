package org.icdqcow2.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.icdqcow2.core.ToolRun
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面骨架。
 *
 * 顶部一条**底色渐变覆盖层**（[TopFadeBand]）：页面底色 → 透明，
 * 在标题栏高度的 2/5 处就完全消失。它只画颜色、不碰内容，
 * 所以文字不会被叠加加深，也不会出现模糊色块。
 *
 * 标题栏本身背景透明（`color = Color.Transparent`），只画字和按钮；
 * 内容铺满整屏、从状态栏底下过，滚上去时被顶部覆盖层冲淡。
 *
 * 画面**直接铺到屏幕底**（不吃 Scaffold 的 bottom padding），
 * 导航栏浮在上面；列表末尾靠 [LocalBottomBarHeight] 留白，最后一项不会被压住。
 */

/** 标题栏还没量出高度时的兜底。 */
private val FALLBACK_LARGE_BAR = 96.dp
private val FALLBACK_SMALL_BAR = 80.dp

private val CONTENT_TOP_GAP = 8.dp
private val PAGE_BOTTOM = 28.dp


/** 大标题页（主页 / 文件 / 设置）：透明标题栏、大标题折叠、小标题弹入、顶部渐变模糊。 */
@Composable
fun LargeTitlePage(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val effects = LocalVisualEffects.current
    val behavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(with(density) { FALLBACK_LARGE_BAR.roundToPx() }) }
    val barHeightDp = with(density) { barHeight.toDp() }

    // 页面必须有实底：二级页转场时它是滑在上级页面之上的，透明会透出下面的内容
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = barHeightDp + CONTENT_TOP_GAP,
                bottom = PAGE_BOTTOM + LocalBottomBarHeight.current,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )

        if (effects) {
            TopFadeBand(height = barHeightDp * 5f / 6f)
        }

        TopAppBar(
            title = title,
            largeTitle = title,
            // 关掉"渐变 / 液态玻璃"时回到 Miuix 原生实底；开着才透出下面的内容
            color = if (effects) Color.Transparent else MiuixTheme.colorScheme.surface,
            scrollBehavior = behavior,
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (it.height > barHeight) barHeight = it.height },
        )
    }
}

/**
 * 大标题页 + **自由布局的内容区**（文件页那种"双栏"用）。
 *
 * 和 [LargeTitlePage] 同一套观感（透明标题栏、渐变模糊、状态栏 inset），另外：
 *   · [subtitle]：标题栏自带的副标题（展开时挂在大标题下面，折叠后挂在小标题下面）；
 *   · **点标题栏空白处折叠 / 展开**：自己切 `TopAppBar` ↔ `SmallTopAppBar`，
 *     内容区顶部留白用 `animateDpAsState` 过渡，不依赖滚动（这一页没有整页滚动容器）。
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** true = 内容一直铺到屏幕底（压着浮动导航），底部留白交给页面自己加。 */
    extendToBottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val effects = LocalVisualEffects.current
    val behavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    var collapsed by remember { mutableStateOf(false) }
    // 两种栏各量一次高度，之后来回切都不跳
    var largePx by remember { mutableIntStateOf(with(density) { FALLBACK_LARGE_BAR.roundToPx() }) }
    var smallPx by remember { mutableIntStateOf(with(density) { FALLBACK_SMALL_BAR.roundToPx() }) }

    val topPad by animateDpAsState(
        targetValue = with(density) { (if (collapsed) smallPx else largePx).toDp() } + CONTENT_TOP_GAP,
        animationSpec = tween(200),
        label = "barPad",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = topPad,
                    bottom = if (extendToBottom) 0.dp else PAGE_BOTTOM + LocalBottomBarHeight.current,
                    start = 16.dp,
                    end = 16.dp,
                ),
            content = content,
        )

        if (effects) {
            TopFadeBand(height = with(density) { (if (collapsed) smallPx else largePx).toDp() } * 5f / 6f)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    if (collapsed) {
                        if (it.height > smallPx) smallPx = it.height
                    } else {
                        if (it.height > largePx) largePx = it.height
                    }
                },
        ) {
            if (collapsed) {
                SmallTopAppBar(
                    title = title,
                    subtitle = subtitle ?: "",
                    color = if (effects) Color.Transparent else MiuixTheme.colorScheme.surface,
                    actions = actions,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle ?: "",
                    color = if (effects) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = behavior,
                    actions = actions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 点击层：盖在标题栏上面。
        // 不能只给标题栏加 clickable —— TopAppBar 内部自己吃掉了手指事件，
        // 父层收不到点击（实测「点了没反应」就是这么来的）。
        Box(
            Modifier
                .fillMaxWidth()
                // 右边留出 actions（⋮）的宽度：这一层只负责"点标题折叠"
                .padding(end = 56.dp)
                .height(with(density) { (if (collapsed) smallPx else largePx).toDp() })
                .pointerInput(collapsed) {
                    detectTapGestures(onTap = { collapsed = !collapsed })
                },
        )
    }
}

/** 小标题页（二级页面）：标题栏固定、返回键在左上角、不折叠。 */

@Composable
fun SmallTitlePage(
    title: String,
    onBack: (() -> Unit)? = null,
    /** 只在「全流程装机」里带进来的页面用（第 N 步 / 共 M 步）；单独打开时不传。 */
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val effects = LocalVisualEffects.current
    // ★ 任务运行时不显示返回箭头（用户 2026-11-12 拍板 Q4：不允许取消）。
    //   这几个任务（压缩/释放空间/WinRE 写入/部署）中途退出可能把映像写坏，
    //   浮层那边还会吃掉系统返回，这里把"能退回去"的暗示也去掉。
    val busy by ToolRun.run.collectAsState()
    // 页面一进来就清焦点：否则第一个 TextField 抢焦点后 bringIntoView 会把整页滚上去
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.clearFocus()
    }
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(with(density) { FALLBACK_SMALL_BAR.roundToPx() }) }
    val barHeightDp = with(density) { barHeight.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(barHeightDp + CONTENT_TOP_GAP))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
            Spacer(Modifier.height(PAGE_BOTTOM + LocalBottomBarHeight.current))
        }

        if (effects) {
            TopFadeBand(height = barHeightDp * 5f / 6f)
        }

        SmallTopAppBar(
            title = title,
            subtitle = subtitle ?: "",
            color = if (effects) Color.Transparent else MiuixTheme.colorScheme.surface,
            actions = actions,
            navigationIcon = {
                if (onBack != null && busy == null) {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (it.height > barHeight) barHeight = it.height },
        )
    }
}

/**
 * 顶部覆盖层：页面底色 → 透明（自上而下）。
 *
 * **不画内容副本**——之前用多层模糊栈（18/11/5/0dp 各一层带 alpha 渐变）时，
 * 同一份文字被叠了四遍，颜色加深、笔画变粗，SmallTitle 尤其明显。
 * 底色渐变不碰内容，所以字还是它自己，只是被冲淡。
 */
@Composable
private fun TopFadeBand(height: Dp) {
    val pageColor = MiuixTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        // 渐变整体下移 15%：顶部 15% 是纯色，之后才开始化开
                        0f to pageColor,
                        0.15f to pageColor,
                        1f to Color.Transparent,
                    ),
                )
            },
    )
}
