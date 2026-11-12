package org.icdqcow2.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.icdqcow2.core.ToolEvents
import org.icdqcow2.core.ToolRun
import org.icdqcow2.ui.LocalStrings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局进度浮层（用户 2026-11-12：统一进度页）。
 *
 * 挂在 AppRoot 层（所有页面之上）：任何页面发起的工具任务都在这儿显示，
 * 页面自己不用管浮层 —— 以前只有文件页有进度卡，压缩/释放空间/WinRE/部署
 * 跑起来时界面一点变化都没有，用户不知道在干什么。
 *
 * ★ 进度图形用 [RunProgressBlock]（**圆盘模板**，与释放映像/捕获映像同一套）——
 *   用户 2026-11-10 就定过「进度不能一个长条一个圆盘，只能有一个模板」。
 *   第一版浮层我画了个线性条，被用户抓了现行（2026-11-12），已改回圆盘。
 *
 * 显示内容（用户第 8 条）：圆盘 + 百分比 + 剩余大小 + 正在处理/写入 + 文件计数 + 已用/预计剩余
 * + 一行「开始后不可取消」。
 *
 * ★ **不允许取消**（用户 2026-11-12 拍板 Q4）：这四个任务（压缩/释放空间/
 *   WinRE 写入/部署）中途停手可能把映像写坏，所以
 *   · 浮层里**没有**取消按钮；
 *   · [BackHandler] 把系统返回吃掉，误触时弹一句 toast 说明白为什么没反应；
 *   · 标题栏的返回箭头也由 [SmallTitlePage] 在任务运行时隐藏。
 */
@Composable
fun GlobalProgressOverlay(onToast: (String) -> Unit) {
    val run by ToolRun.run.collectAsState()
    val prog by ToolEvents.progress.collectAsState()
    val s = LocalStrings.current
    val cur = run ?: return

    // 返回键吃掉：任务跑着的时候退出页面没用（也危险）
    BackHandler(enabled = true) { onToast(s.progBlocked) }

    // 「只登记不画浮层」的任务（部署页自己就有步骤条 + 百分比）：
    //   规矩照守（返回被吃掉），但不要再压一层重复的进度卡上去。
    if (cur.silent) return

    val p = prog ?: cur.fallback
    // 工具端没给百分比 → -1 = 不定态圆环（转圈），别画成 0% 像卡死
    val pct = p?.pct?.takeIf { it in 0..100 } ?: -1
    // 剩余：overall 是"整件事的总账"（wim.apply 这类多段任务），
    // 没有 overall 才退到本段 total-cur。
    val remain = when {
        p == null -> 0L
        p.overallTotalBytes > 0 -> (p.overallTotalBytes - p.overallCurBytes).coerceAtLeast(0L)
        p.totalBytes > 0 -> (p.totalBytes - p.curBytes).coerceAtLeast(0L)
        else -> 0L
    }
    val item = p?.item?.takeIf { it.isNotBlank() }?.let { prettyProgressItem(it) }
    // REQ-77：item 是**源**路径，dst_item 是**目标**路径（写文件类任务才有）
    val dst = p?.dstItem?.takeIf { it.isNotBlank() }
    val filesCur = p?.filesCur ?: 0L
    val filesTotal = p?.filesTotal ?: 0L
    val elapsedMs = p?.elapsedMs?.takeIf { it > 0L } ?: 0L
    val etaSec = p?.eta?.takeIf { it >= 0 } ?: -1
    val colors = MiuixTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(300f)
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                // 把所有指针事件吃掉：浮层在的时候，下面的页面一律收不到点击
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            insideMargin = PaddingValues(18.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(cur.title, style = MiuixTheme.textStyles.title4, color = colors.onSurface)
                Spacer(Modifier.height(14.dp))
                RunProgressBlock(
                    pct = pct,
                    timeText = progressTimeText(elapsedMs, etaSec),
                    countsText = if (remain > 0L) s.progRemaining(fmtBytes(remain)) else null,
                    item = item,
                    dstText = dst?.let { s.progDst(it) },
                    filesText = if (filesTotal > 0L) s.progFiles(filesCur.toString(), filesTotal.toString()) else null,
                    ringSize = 148.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    s.progNoCancel,
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}