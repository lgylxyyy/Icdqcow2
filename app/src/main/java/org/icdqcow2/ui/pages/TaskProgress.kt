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
 * **全 App 唯一的进度模板**（用户 2026-11-13 定）。
 *
 * 以前压缩/释放空间/WinRE 用 `GlobalProgressOverlay` 的一套、部署页又是另一套，
 * 圆盘大小、圈里放什么、时间口径全不一样 —— 用户原话「各个页面都不相同，观感很差」。
 * 现在所有功能页都用这一块：
 *
 * ```
 *      ╭──────────╮
 *     │   62%     │      ← 圈内第一行：百分比（-1 画不定态转圈）
 *     │ 3.2/5.3 GB│      ← 圈内第二行：已完成/总大小（没有字节口径就不显示）
 *      ╰──────────╯
 *      已用 12:34         ← 圈外：**只显示实际已用**（估算时间已删，用户 2026-11-13）
 *      剩余 2.1 GB        ← 剩余大小（字节口径，算得出来才显示）
 *      正在处理：…/a.bin  ← 源（工具端 item）
 *      正在写入：…/b.bin  ← 目标（REQ-77 dst_item）
 *      文件 3 / 40        ← 逐文件任务才有（files_total = 0 不显示）
 *      ┌ 步骤条（可选）─┐  ← 部署页专用：改名/Windows.old/铺映像/…
 *      │ ✓ 改名         │
 *      │ … 铺映像       │
 *      └───────────────┘
 * ```
 *
 * @param pct 0..100；**-1 = 不定态**（工具端还没算出百分比 → 转圈，不画假 0%）
 * @param doneBytes / totalBytes 字节口径（totalBytes = 0 → 圈内第二行不显示）
 * @param elapsedMs **实际已用**（App 本地计时；0 = 不显示）
 * @param remainBytes 剩余大小（0 = 不显示）
 * @param countsText 额外一行（文件页的「12 / 40 项」）；null = 不显示
 * @param phase 阶段文字（已本地化）
 * @param item 正在处理（源路径）
 * @param dst 正在写入（目标路径）
 * @param filesCur / filesTotal 文件计数（total = 0 不显示）
 * @param steps 步骤条（可选；空 = 没有这一步）
 * @param stepIndex 当前步骤下标
 * @param stepFailed 步骤条里当前步骤失败（画红）
 * @param ringSize 圆盘直径（浮层 148dp；文件页卡片 148dp；同一套）
 */
@Composable
fun TaskProgressView(
    pct: Int,
    doneBytes: Long = 0L,
    totalBytes: Long = 0L,
    elapsedMs: Long = 0L,
    remainBytes: Long = 0L,
    countsText: String? = null,
    phase: String? = null,
    item: String? = null,
    dst: String? = null,
    filesCur: Long = 0L,
    filesTotal: Long = 0L,
    steps: List<String> = emptyList(),
    stepIndex: Int = 0,
    stepDone: Boolean = false,
    stepFailed: Boolean = false,
    ringSize: Dp = 148.dp,
) {
    val s = LocalStrings.current
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
    // 圈内第二行：已完成 / 总大小（用户 2026-11-13：圈里放大小，不放时间）
    val bytesLine = if (totalBytes > 0L) {
        "${fmtBytes(doneBytes)} / ${fmtBytes(totalBytes)}"
    } else null

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                // ★ 环宽 9dp → 6dp（用户 2026-11-13：「现在太粗」）
                val sw = 6.dp.toPx()
                val d = size.minDimension - sw
                val r = d / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
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
                if (bytesLine != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        bytesLine,
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 圈外：实际已用（估算时间已删）──
        if (elapsedMs > 0L) {
            Spacer(Modifier.height(10.dp))
            Text(
                s.progUsed(fmtClock(elapsedMs / 1000L)),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (remainBytes > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                s.progRemaining(fmtBytes(remainBytes)),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (!countsText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                countsText,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (!phase.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(phase, style = MiuixTheme.textStyles.body2, color = colors.primary)
        }
        if (!item.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                s.progItem(prettyProgressItem(item)),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (!dst.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                s.progDst(dst),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (filesTotal > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                s.progFiles(filesCur.toString(), filesTotal.toString()),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }

        // ── 步骤条（可选；部署页用）──
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GroupCard {
                steps.forEachIndexed { i, name ->
                    val mark = when {
                        stepFailed && i == stepIndex -> "✕"
                        stepDone || i < stepIndex -> "✓"
                        i == stepIndex -> "…"
                        else -> ""
                    }
                    Item(name, mark)
                }
            }
        }
    }
}