package org.icdqcow2.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局「正在跑的工具任务」登记处（用户 2026-11-12：统一进度页）。
 *
 * 为什么要有它：以前只有文件页有阻塞式进度卡，压缩 / 释放空间 / WinRE / 部署
 * 这些长任务跑起来时，页面还是老样子 —— 用户不知道在干什么，也不知道还剩多久。
 * 现在所有长任务都来这里登记，浮层挂在 AppRoot 层（页面之上），页面自己不用管浮层。
 *
 * 用法：
 * ```
 * ToolRun.start(s.compactTitle)          // 开始一个任务
 * try { … } finally { ToolRun.finish() }  // 成功/失败都要收摊
 * ```
 *
 * 进度数值来自 [ToolEvents.progress]（工具端推的事件流）；
 * 工具端没给进度时，页面可以用 [updateFallback] 塞一份自己算的。
 */
object ToolRun {

    /** 一个正在跑的任务。 */
    data class Run(
        /** 任务名（浮层标题）：如「压缩映像」。 */
        val title: String,
        /** 页面自己算的进度（工具端没给事件时兜底）。 */
        val fallback: ToolEvents.Progress? = null,
        /**
         * **只登记、不画浮层**。
         *
         * 给"页面自己已经有进度界面"的任务用（部署页有步骤条 + 百分比 + ETA），
         * 再叠一个浮层是重复的；但"任务跑着不能退出"这条规矩它也得守，
         * 所以照样登记 —— 返回箭头会隐藏、系统返回会被吃掉。
         */
        val silent: Boolean = false,
    )

    private val _run = MutableStateFlow<Run?>(null)

    /** 当前任务；null = 没有任务在跑。 */
    val run: StateFlow<Run?> = _run.asStateFlow()

    /** 任务是不是正在跑（页面据此隐藏返回箭头等）。 */
    fun busy(): Boolean = _run.value != null

    /** 登记一个任务开始。 */
    fun start(title: String, silent: Boolean = false) {
        _run.value = Run(title, silent = silent)
        // 上一个任务的残留进度不能带到新任务里
        ToolEvents.clear()
    }

    /**
     * 更新兜底进度。
     *
     * 相等就跳过（StateFlow 按 equals 去重），所以页面可以放心地每次重组都调一次，
     * 不会引起"改状态 → 重组 → 再改状态"的死循环。
     */
    fun updateFallback(p: ToolEvents.Progress?) {
        val cur = _run.value ?: return
        if (cur.fallback == p) return
        _run.value = cur.copy(fallback = p)
    }

    /** 任务结束（成功/失败都调）。 */
    fun finish() {
        _run.value = null
        ToolEvents.clear()
    }
}
