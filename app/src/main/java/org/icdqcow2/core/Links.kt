package org.icdqcow2.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 外部跳转：社群、仓库、提 issue。
 *
 * 为什么要判断"装没装"：
 *   `tg://` 这种 scheme 只有在装了 Telegram 时才有人接；直接用
 *   `https://t.me/...` 会被浏览器接走（也可能被 Telegram 的 App Link 抢走，
 *   行为随版本变）。这里显式分两条路，保证"装了就直接进 App，没装就进网页"。
 *
 * `queries` 声明：
 *   Android 11+ 包可见性限制，不声明 `<queries>` 时 `getPackageInfo` 会抛
 *   NameNotFound 或返回 null。manifest 里已声明 Telegram 的包名和 `tg` scheme。
 */
object Links {

    const val TELEGRAM_GROUP = "https://t.me/+S7A3cUHgbuVkMmI9"
    const val TELEGRAM_PKG = "org.telegram.messenger"

    const val REPO_APP = "https://github.com/lgylxyyy/Icdqcow2"
    const val REPO_TOOL = "https://github.com/lgylxyyy/imgtool"
    const val REPO_TOOL_ANDROID = "https://github.com/lgylxyyy/imgtool"

    /** 提 issue 的直达链接（GitHub 会自动带上模板选择器）。 */
    const val ISSUE_APP = "$REPO_APP/issues/new/choose"
    const val ISSUE_TOOL = "$REPO_TOOL/issues/new/choose"

    /** Telegram 装了没。 */
    fun hasTelegram(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(TELEGRAM_PKG, 0)
        true
    }.getOrDefault(false)

    /**
     * 加入 Telegram 群。
     *
     * 装了 → 走 `tg://` 直接打开 App 内的加群确认页；
     * 没装 → 走 https 网页（浏览器 / 系统默认处理）。
     *
     * 返回 `true` 表示已经把 Intent 发出去了。
     */
    fun joinTelegram(ctx: Context): Boolean {
        if (hasTelegram(ctx)) {
            // 群邀请链接的私有 hash 在 tg:// 里同样有效
            val deep = "tg://resolve?domain=" + Uri.encode(TELEGRAM_GROUP.substringAfter("t.me/"))
            if (open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(deep)), forcePackage = TELEGRAM_PKG)) {
                return true
            }
        }
        return open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_GROUP)))
    }

    /** 打开一个网页（浏览器 / 对应 App）。 */
    fun openUrl(ctx: Context, url: String): Boolean =
        open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(ctx: Context, intent: Intent, forcePackage: String? = null): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forcePackage != null) intent.setPackage(forcePackage)
        if (intent.resolveActivity(ctx.packageManager) == null) return@runCatching false
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 设备上有没有能接 https 的（极简设备可能没有浏览器）。 */
    fun canBrowse(ctx: Context): Boolean = runCatching {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .resolveActivity(ctx.packageManager) != null
    }.getOrDefault(false)

    /** 给"关于"卡片显示：Telegram 装没装。 */
    fun telegramState(ctx: Context): Boolean =
        ctx.packageManager.let { pm ->
            runCatching { pm.getPackageInfo(TELEGRAM_PKG, PackageManager.PackageInfoFlags.of(0)) }.isSuccess
        }
}
