package org.icdqcow2.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单个 tab 的宽度。 */
private val ITEM_WIDTH = 74.dp

/** 胶囊高度。 */
private val BAR_HEIGHT = 64.dp

/**
 * 色块与胶囊边缘的间距（四边都是它）。
 *
 * 取 4dp 时色块圆角半径 28dp、胶囊 32dp，两者**同心**，
 * 所以滑到第一格/最后一格时，色块的半圆正好贴着胶囊的圆边。
 */
private val PILL_INSET = 4.dp
private val PILL_HEIGHT = BAR_HEIGHT - PILL_INSET * 2

/** 越界拖动时的阻尼系数（越拖越沉）。 */
private const val OVER_DRAG_DAMP = 0.45f

/**
 * 液态玻璃底部导航栏。
 *
 * ── 位置 ──
 * 只有一个真值：`pill` 的浮点位置（0.0 ~ 2.0，单位是"格"）。
 * - 内容区拖动 / 点底栏切页时，它贴着 `pagerState` 的连续进度走（跟手，不重算时长）；
 * - 手指在底栏上拖动时，它跟手指走（阻尼越界）；
 * - 松手后吸附到最近一格，同时请求切页。
 *
 * ── 表现 ──
 * - 按下：色块缩到 85%，颜色同步加深（同一个 press 进度驱动，所以颜色和大小是联动的）；
 * - 越界：色块沿移动方向压扁（scaleX 收、scaleY 放）；
 * - 高亮：色块中心过格 50% 就转高亮，但**不切页面**，页面只在松手/点击时切。
 */
@Composable
fun GlassNavigationBar(
    backdrop: Backdrop,
    progress: () -> Float,
    onSnap: (Int) -> Unit,
    items: List<GlassBarItem>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = remember { Capsule() }
    val density = LocalDensity.current
    val itemPx = with(density) { ITEM_WIDTH.toPx() }
    val scope = rememberCoroutineScope()

    val dark = colors.surface.luminance() < 0.5f
    val alphaIdle = if (dark) 0.14f else 0.07f
    val alphaPress = if (dark) 0.26f else 0.15f

    val pill = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    // 按在底栏上的整体状态：按住就缩小，拖动过程中不能松掉
    var pressing by remember { mutableStateOf(false) }

    // 内容区滚动 / 点击切页：色块贴着 pager 的连续进度走
    LaunchedEffect(Unit) {
        snapshotFlow { progress() }.collect { p ->
            if (!dragging) pill.snapTo(p)
        }
    }

    val pos = pill.value
    val highlighted = pos.roundToInt().coerceIn(0, items.lastIndex)
    val over = when {
        pos < 0f -> -pos
        pos > items.lastIndex.toFloat() -> pos - items.lastIndex
        else -> 0f
    }

    val press by animateFloatAsState(
        targetValue = if (pressing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )

    Box(
        modifier = modifier
            .width(ITEM_WIDTH * items.size)
            .height(BAR_HEIGHT)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(16.dp.toPx())
                    lens(
                        refractionHeight = 14.dp.toPx(),
                        refractionAmount = 24.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight(width = 0.8.dp, alpha = 0.85f) },
                shadow = { Shadow(radius = 16.dp, alpha = 0.5f) },
            )
            .pointerInput(itemPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        pressing = true
                    },
                    onDragCancel = {
                        dragging = false
                        pressing = false
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val raw = pill.value + dx / itemPx
                        val damped = when {
                            raw < 0f -> raw * OVER_DRAG_DAMP
                            raw > items.lastIndex -> items.lastIndex + (raw - items.lastIndex) * OVER_DRAG_DAMP
                            else -> raw
                        }
                        scope.launch { pill.snapTo(damped) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val target = pill.value.roundToInt().coerceIn(0, items.lastIndex)
                            val from = progress().roundToInt()
                            // 时长必须和 pager 一致：否则一边先停，交还控制权时色块会被
                            // pager 的旧进度拽回去（就是你看到的"先回原位再滑过去"）
                            val dur = if (abs(target - from) <= 1) 320 else 520
                            val dir = if (target.toFloat() > pill.value) 1f else -1f
                            onSnap(target)
                            // 先松手：大小/颜色从这一刻开始回弹，与吸附动画同步进行
                            pressing = false
                            pill.animateTo(
                                targetValue = target.toFloat(),
                                animationSpec = keyframes {
                                    durationMillis = dur
                                    (target + dir * 0.05f) at dur - 80
                                    target.toFloat() at dur
                                },
                            )
                            dragging = false
                        }
                    },
                )
            },
    ) {
        // ── 色块 ──
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        PILL_INSET.roundToPx() + (pos * itemPx).roundToInt(),
                        PILL_INSET.roundToPx(),
                    )
                }
                .width(ITEM_WIDTH - PILL_INSET * 2)
                .height(PILL_HEIGHT)
                .graphicsLayer {
                    val s = 1f - 0.15f * press
                    scaleX = s * (1f - over * 0.35f)
                    scaleY = s * (1f + over * 0.12f)
                }
                .background(
                    colors.onSurface.copy(alpha = alphaIdle + alphaPress * press),
                    RoundedCornerShape(percent = 50),
                ),
        )

        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val on = index == highlighted
                Column(
                    modifier = Modifier
                        .width(ITEM_WIDTH)
                        .fillMaxHeight()
                        .pointerInput(index) {
                            detectTapGestures(
                                onPress = {
                                    pressing = true
                                    tryAwaitRelease()
                                    // 拖动中由 onDragEnd 负责复位，这里别抢
                                    if (!dragging) pressing = false
                                },
                                onTap = {
                                    onSnap(index)
                                },
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = colors.onSurface.copy(alpha = if (on) 0.95f else 0.5f),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = item.label,
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurface.copy(alpha = if (on) 0.9f else 0.45f),
                    )
                }
            }
        }
    }
}

data class GlassBarItem(
    val icon: ImageVector,
    val label: String,
)

/** 玻璃栏下方垫一层"底色 → 透明"的缓冲，避免背后文字直接怼到玻璃边缘。 */
@Composable
fun BottomFadeScrim(modifier: Modifier = Modifier) {
    val page = MiuixTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to page.copy(alpha = 0.55f),
                    1f to page.copy(alpha = 0.92f),
                ),
            ),
    )
}
