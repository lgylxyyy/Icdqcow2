package org.icdqcow2.core

/**
 * 受限制的伙伴应用包名（由 tools/sync_partners.py 从 partners.json 生成，勿手改）。
 *
 * 这些包名同时写进了 AndroidManifest 的 `<queries>`，否则 Android 11+ 查不到；
 * 只声明这一份名单，不用 QUERY_ALL_PACKAGES。
 */
internal object PartnerList {
    val PACKAGES: List<String> = listOf(
        "com.vectrasllc.vm",
    )

    /** 包名 → 显示名，用于界面提示。 */
    val LABELS: Map<String, String> = mapOf(
        "com.vectrasllc.vm" to "Vectras VM",
    )

    fun labelOf(pkg: String): String = LABELS[pkg] ?: pkg
}
