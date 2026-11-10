package org.icdqcow2.ui.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.icdqcow2.ui.LocalStrings
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 长任务的**进度模板**：圆盘 + 一圈蓝边（走满一圈 = 100%）+ 圆心百分比 + 圆盘内下方小字时间
 * + 圆盘外下方「当前正在读/写的那一个文件」。
 *
 * 释放映像（`wim.apply`）与捕获映像（`wim.capture`）共用 — 用户 2026-11-10 定：
 * 两边的进度不能一个长条一个圆盘，只能有一个模板。
 *
 * 数据全部来自 `ToolEvents.progress`（工具端事件流，REQ-20261110-68 钉了字段口径）：
 * `pct` / `elapsed` / `eta_s` / `phase` / `item`。App 不自己计时、不自己数文件 ——
 * 只在工具端算不出来时退化成「估算中」和不定态圆环。
 */

/** 秒 → `m:ss`（超过 1 小时给 `h:mm:ss`）。 */
internal fun fmtClock(sec: Long): String {
    if (sec < 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

/**
 * 圆盘内下方那行小字：「已用 01:12 · 约剩 02:40」。
 *
 * `elapsedMs` / `etaSec` 都是工具端给的（`elapsed` / `eta_s`）；剩余时间没有（-1）就写「估算中」，
 * 不显示 0:00 —— 那是假的。
 */
@Composable
fun progressTimeText(elapsedMs: Long, etaSec: Int): String {
    val s = LocalStrings.current
    val used = fmtClock(elapsedMs / 1000L)
    return if (etaSec > 0) s.progTime(used, fmtClock(etaSec.toLong())) else s.progTimeUnknown(used)
}

/** `phase` → 人话（工具端没给认得的 phase 就返回 null，不显示这一行）。 */
@Composable
fun progressPhaseText(phase: String?): String? {
    val s = LocalStrings.current
    return when (phase?.lowercase()) {
        "extract", "decompress" -> s.progPhaseExtract
        "pack" -> s.progPhasePack
        "write" -> s.progPhaseWrite
        "copy" -> s.progPhaseCopy
        else -> null
    }
}

/**
 * 进度块本体。
 *
 * @param pct 0~100；< 0 = 工具端还没算出百分比（画一个转圈的不定态）
 * @param timeText [progressTimeText] 的结果
 * @param phase 阶段文字（[progressPhaseText]）
 * @param countsText 可选的一行「1.2 GB / 3.4 GB」之类
 * @param item 当前正在读/写的文件（完整路径 + 文件名）
 */
@Composable
fun RunProgressBlock(
    pct: Int,
    timeText: String,
    phase: String? = null,
    countsText: String? = null,
    item: String? = null,
    ringSize: Dp = 188.dp,
) {
    val colors = MiuixTheme.colorScheme
    val known = pct in 0..100
    val p = pct.coerceIn(0, 100)
    // 不定态：蓝边自己转，避免"没进度 = 像卡死"
    val spin by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin",
    )

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 9.dp.toPx()
                val inset = sw / 2f
                val d = size.minDimension - sw
                val r = d / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // 盘面：比背景略深一点，蓝边压在盘的边线上
                drawCircle(color = colors.surfaceContainerHigh, radius = r, center = c)
                drawArc(
                    color = colors.primary,
                    startAngle = -90f + (if (known) 0f else spin),
                    sweepAngle = if (known) 360f * p / 100f else 96f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(d, d),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (known) "$p%" else "—",
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    timeText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }

        if (!countsText.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                countsText,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (!phase.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(phase, style = MiuixTheme.textStyles.body2, color = colors.primary)
        }
        if (!item.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                item,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}