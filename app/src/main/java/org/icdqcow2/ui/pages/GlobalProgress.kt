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
 * 显示内容（用户第 8 条）：
 * · 进度条 + 百分比
 * · **剩余大小**（overall 口径优先：多段任务的总账；否则 total-cur）
 * · 正在处理：{item}（工具端给的当前文件名/路径）
 * · 已用 / 预计剩余（`elapsedMs` / `eta`）
 * · 一行「开始后不可取消」—— 提醒用户别以为能中断
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
    val pct = p?.pct?.takeIf { it >= 0 } ?: 0
    // 剩余：overall 是"整件事的总账"（wim.apply 这类多段任务），
    // 没有 overall 才退到本段 total-cur。
    val remain = when {
        p == null -> 0L
        p.overallTotalBytes > 0 -> (p.overallTotalBytes - p.overallCurBytes).coerceAtLeast(0L)
        p.totalBytes > 0 -> (p.totalBytes - p.curBytes).coerceAtLeast(0L)
        else -> 0L
    }
    val item = p?.item?.takeIf { it.isNotBlank() }
    val elapsedMs = p?.elapsedMs?.takeIf { it > 0L }
    val etaSec = p?.eta?.takeIf { it >= 0 }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            insideMargin = PaddingValues(18.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(cur.title, style = MiuixTheme.textStyles.title4, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))
                ProgressBar(
                    pct,
                    buildString {
                        append(pct).append("%")
                        if (remain > 0L) {
                            append(" · ")
                            append(s.progRemaining(fmtBytes(remain)))
                        }
                    },
                )
                if (!item.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.progItem(item),
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                if (elapsedMs != null || etaSec != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (elapsedMs != null) append(s.progElapsed(fmtSeconds(elapsedMs / 1000L)))
                            if (etaSec != null) {
                                if (isNotEmpty()) append(" · ")
                                append(s.progEta(fmtSeconds(etaSec.toLong())))
                            }
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    s.progNoCancel,
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 秒 → 「12 秒」/「3 分 05 秒」。 */
internal fun fmtSeconds(sec: Long): String = when {
    sec < 60L -> "$sec 秒"
    sec < 3600L -> "%d 分 %02d 秒".format(sec / 60L, sec % 60L)
    else -> "%d 小时 %02d 分".format(sec / 3600L, (sec % 3600L) / 60L)
}