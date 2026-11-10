package org.icdqcow2.core

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 本机安装的 APK 到底是谁签的。
 *
 * 为什么需要：本地调试包用的是 debug 密钥，GitHub Actions 出的正式包用的是发布密钥，
 * 两者指纹完全不同。装完之后光看版本号分不出来，把指纹显示在「设置 · 关于」里，
 * 一眼就能确认手上这个包是哪个构建产物 —— 也是排查「签名不符装不上」的唯一现场证据。
 *
 * 注意：证书指纹是**公开**信息（APK 里本来就有），显示它不泄露私钥。
 */
object Signing {

    /** 发布证书 SHA-256（小写无冒号），与 README「签名」章节、CI 里的 EXPECTED_SHA256 一致。 */
    const val RELEASE_SHA256 = "823f40ba39912855d7ac5ec9eb3b1f18d8b7e04043d390bab5ea5800aba1fbe3"

    /** 当前安装包的签名证书 SHA-256；取不到就返回空串，不抛异常。 */
    fun sha256(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { b -> "%02x".format(b) }
    }.getOrDefault("")

    /** 是否与发布证书一致。 */
    fun isReleaseKey(ctx: Context): Boolean =
        sha256(ctx).equals(RELEASE_SHA256, ignoreCase = true)

    /** 是否是 debug 构建（debuggable 标记）。 */
    fun isDebugBuild(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 给人看的短形式：`82:3F:40:BA … A1:FB:E3`（全串太长，列表里放不下）。 */
    fun prettyShort(hex: String): String {
        if (hex.length < 32) return hex.uppercase()
        fun fmt(range: IntRange) = hex.substring(range).chunked(2).joinToString(":").uppercase()
        return fmt(0..7) + " … " + fmt((hex.length - 6) until hex.length)
    }
}
