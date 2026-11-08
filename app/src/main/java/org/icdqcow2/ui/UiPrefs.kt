package org.icdqcow2.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 分组小标题（`SmallTitle`）的固定色。用户指定，别换成 Miuix 语义色。 */
val SectionTextColor = Color(0xFF8C93B0)

/**
 * 「渐变模糊 + 悬浮菜单栏」总开关（设置 → 外观）。
 *
 * 关闭后：
 * - 页面不再把内容录进 GraphicsLayer（`layerBackdrop` 都不挂），顶部不画模糊带；
 * - 底部导航从悬浮胶囊换成通栏。
 *
 * 默认 `true`；API < 33 时 [org.icdqcow2.core.SettingsStore] 会强制成 `false`，
 * 因为模糊依赖 `RuntimeShader`，33 以下建不出来。
 */
val LocalVisualEffects = compositionLocalOf { true }
/**
 * 底部导航占掉的高度。内容画面**铺到屏幕底**（不留白），
 * 但列表末尾要多留这么多，否则最后一项会被导航栏压住。
 */
val LocalBottomBarHeight = compositionLocalOf { 0.dp }
