package org.icdqcow2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一条提示。
 *
 * `leaving = true` 表示它已经到时间/被挤掉，正在播退出动画，播完再从列表里删掉
 * （直接删会让它"啪"一下消失，没有动画）。
 */
data class ToastItem(
    val id: Long,
    val text: String,
    val leaving: Boolean = false,
)

/**
 * 顶部提示条队列。
 *
 * ★ 以前只留一条：新消息**顶掉**旧消息。结果是用户狂点按钮时，
 *   每点一次只看到"上一次"的提示，容易以为没反应，实际是消息太快被覆盖。
 *   现在改成队列：最多同时显示 [max] 条（默认 6），新的在最上面，
 *   超出后最旧的自动退出。同样的文字也不去重——连点 5 次就该看到 5 条，
 *   这样"点了没反应"是看得见的。
 */
@Stable
class ToastHostState(private val max: Int = 6) {

    var items by mutableStateOf<List<ToastItem>>(emptyList())
        private set

    private var seq = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        // 不去重：每点一次就多一条（最多 max 条），用户才能看出"我点了好几次都没反应"。
        // 超过上限时最旧的一条播退出动画被挤掉。
        val list = items.toMutableList()
        list.add(0, ToastItem(id = ++seq, text = text))
        while (list.size > max) list.removeAt(list.lastIndex)
        items = list
    }

    /** 开始播放退出动画。 */
    fun leave(id: Long) {
        items = items.map { if (it.id == id) it.copy(leaving = true) else it }
    }

    /** 退出动画播完，真正移除。 */
    fun remove(id: Long) {
        items = items.filterNot { it.id == id }
    }

    fun clear() {
        items = emptyList()
    }
}

/**
 * 顶部提示区：从上往下堆叠，新的在最上面，最多 [ToastHostState] 的条数。
 *
 * 不透明底色（半透明会透出下面的标题/卡片），字号比正文大一档，
 * 免得提示太小被忽略。整块不消费点击事件，点到的地方照常落到下面的界面。
 */
@Composable
fun ToastHost(
    state: ToastHostState,
    modifier: Modifier = Modifier,
    stayMillis: Long = 3200,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.items.forEach { item ->
            key(item.id) {
                // 到点自己退场
                LaunchedEffect(item.id) {
                    delay(stayMillis)
                    state.leave(item.id)
                }

                AnimatedVisibility(
                    visible = !item.leaving,
                    enter = slideInVertically(tween(240, easing = LinearOutSlowInEasing)) { -it } +
                        fadeIn(tween(160)),
                    exit = slideOutVertically(tween(180, easing = FastOutLinearInEasing)) { -it } +
                        fadeOut(tween(140)),
                ) {
                    val shape = RoundedCornerShape(20.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .shadow(12.dp, shape)
                            .background(MiuixTheme.colorScheme.surfaceContainer, shape)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = item.text,
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 退出动画播完再删（240ms 比上面 180ms 稍长，留一点余量）
                if (item.leaving) {
                    LaunchedEffect(item.id) {
                        delay(240)
                        state.remove(item.id)
                    }
                }
            }
        }
    }
}