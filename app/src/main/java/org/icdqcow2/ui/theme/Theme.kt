package org.icdqcow2.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.icdqcow2.core.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 主题：**直接用 Miuix 的配色**，不再自己维护一套 token。
 *
 * - 浅色 `lightColorScheme()` / 深色 `darkColorScheme()` 都是 Miuix 的默认值
 *   （primary #3482FF、页面 #F7F7F7、暗色 #191919 …），换皮只需要改这里；
 * - `ThemeMode.SYSTEM` 跟随系统，LIGHT / DARK 强制；
 * - 顺手把状态栏/导航栏图标切成深色或浅色，否则暗色模式下图标看不见。
 */
@Composable
fun Icdqcow2Theme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}