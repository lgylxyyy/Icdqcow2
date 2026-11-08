package org.icdqcow2.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 编辑器 ↔ 文件页 的桥。
 *
 * 场景：从**只读位置**（ISO / WIM / 压缩包 / 只读分区）里点开一个文本文件时，
 * 实际编辑的是缓存里的副本；保存时不能写回只读位置，于是：
 *   编辑器把"写好的缓存文件"塞进 [saveTo] → 关掉编辑器回到文件页 →
 *   原来那一栏顶上出现「将文件保存到 →」/「← 将文件保存到」和「取消」两条。
 *
 * 用单例而不是回调链，是因为编辑器是 MainActivity 的二级页、文件页是 pager 里的 tab，
 * 两者之间没有直接的组合关系。
 */
object EditorBridge {
    /** 待保存的缓存文件绝对路径；null = 没有待保存的东西。 */
    var saveTo by mutableStateOf<String?>(null)

    /** 当初是从哪一栏打开的（0 左 / 1 右）——"保存到"就写到**对面**那一栏。 */
    var pane by mutableIntStateOf(0)
}