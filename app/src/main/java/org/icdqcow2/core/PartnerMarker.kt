package org.icdqcow2.core

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 方案二：伙伴签名标记。**当前置空**（[PartnerGate.MARKERS_ENABLED] = false）。
 *
 * 协议（对外文档：docs/partner-marker.md）：
 *
 *  1. 伙伴把标记文件写到共享目录 `Document/PartnerLocks/<自己的包名>.lock`；
 *  2. 文件是一行 JSON：
 *     `{"v":1,"pkg":"…","target":"org.<我们的包名>","until":"2027-01-01","sig":"<base64>"}`
 *  3. `sig` 是伙伴用**自己的私钥**对「去掉 sig 字段后的规范化 JSON」做的 ECDSA P-256 +
 *     SHA-256 签名，DER 编码后 base64；
 *  4. 我们只信任 [TRUSTED_KEYS] 里 pin 的公钥（SPKI base64），且要求：
 *     - 签名可验证（改一个字节就废）
 *     - `pkg` 对应的应用**确实装了**，且其签名证书 SHA-256 与名单一致（防同名假包）
 *     - `target` 必须是本应用包名（防重放到别的应用）
 *     - `until` 未过期（防一劳永逸）
 *
 * 为什么这样设计：
 *  - 只有公钥进我们的 APK，私钥不落我方 —— 我们是 GPL 公开仓库，放对称密钥等于公开；
 *  - 用户删掉标记文件只会回到「方案一」的名单判定，不会因此解锁（假定该伙伴也在名单里）；
 *  - 伪造标记的结果是「验签失败 → 直接忽略」，也就是只会更宽松，不会把用户锁死。
 */
internal object PartnerMarker {

    /** 伙伴公钥（SPKI base64）。当前为空 = 方案二未启用。 */
    private val TRUSTED_KEYS: List<String> = emptyList()

    private const val DIR = "PartnerLocks"

    /** 扫描共享目录里的标记，返回可信的伙伴包名。任何异常都当作“没有”。 */
    fun scan(ctx: Context): List<String> {
        if (TRUSTED_KEYS.isEmpty()) return emptyList()
        return runCatching {
            val dirs = sharedDirs()
            dirs.flatMap { d -> scanDir(ctx, File(d, DIR)) }
        }.getOrDefault(emptyList())
    }

    private fun sharedDirs(): List<String> = listOf(
        "/storage/emulated/0/Documents",
        "/sdcard/Documents",
    )

    private fun scanDir(ctx: Context, dir: File): List<String> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".lock") } ?: return emptyList()
        return files.mapNotNull { verify(ctx, it) }
    }

    private fun verify(ctx: Context, f: File): String? = runCatching {
        val line = f.readText().trim()
        val json = org.json.JSONObject(line)
        val pkg = json.optString("pkg")
        if (pkg.isEmpty()) return null
        if (json.optInt("v") != 1) return null
        if (json.optString("target") != ctx.packageName) return null
        if (json.optString("until") < todayIso()) return null

        val sigB64 = json.optString("sig")
        if (sigB64.isEmpty()) return null
        json.remove("sig")
        val payload = json.toString().toByteArray()

        val ok = TRUSTED_KEYS.any { spki -> verify(ctx, payload, sigB64, spki) }
        if (ok) pkg else null
    }.getOrNull()

    private fun verify(ctx: Context, payload: ByteArray, sigB64: String, spkiB64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))
            val sig = Signature.getInstance("SHA256withECDSA").apply { initVerify(key) }
            sig.update(payload)
            sig.verify(Base64.getDecoder().decode(sigB64))
        }.getOrDefault(false)

    private fun todayIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}
