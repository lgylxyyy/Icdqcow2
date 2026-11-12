package org.icdqcow2.ui.pages

import java.io.File
import org.icdqcow2.core.DiskLimits

/**
 * 目录里已有同名文件就顺延成 `名称 (1)`、`名称 (2)`…
 *
 * ★ 必须在**组合阶段**就能调用（`remember` 里算），不能只靠 `LaunchedEffect`：
 *   effect 是"第一帧画完才跑"，那一帧按旧名字渲染 → 会先闪一条「同名映像已存在」
 *   再改名（用户 2026-11-11 报了两次，第二次就是因为我上次只加了显示门控）。
 *
 * @param ext 文件扩展名（不含点）。**空串** = `wanted` 已经是完整文件名
 *   （压缩页的输出文件名带 `.qcow2`，不能按"名字 + 扩展名"拼）。
 * @param dup 名字模板（`name.dup`，中英都是 `{0} ({1})`）
 */
internal fun uniqueNameIn(
    dir: File,
    ext: String,
    wanted: String,
    dup: (String, Int) -> String,
): String {
    // ext 为空时 wanted 就是完整文件名，不再补点
    val full = { n: String -> if (ext.isEmpty()) n else "$n.$ext" }
    if (!File(dir, full(wanted)).exists()) return wanted
    val suffix = 4                       // " (n)" = 空格 + ( + 数字 + )
    val keep = (DiskLimits.NAME_MAX - suffix - 1).coerceAtLeast(1)
    for (n in 1..99) {
        val cand = dup(wanted.take(keep), n)
        if (!File(dir, full(cand)).exists()) return cand
    }
    return wanted                       // 99 个都撞上：保持原名，让冲突照常报出来
}
