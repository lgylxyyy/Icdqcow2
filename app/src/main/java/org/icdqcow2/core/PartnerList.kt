package org.icdqcow2.core

/**
 * 受限制的伙伴应用包名（由 tools/sync_partners.py 从 partners.json 生成，勿手改）。
 *
 * 这些包名同时写进了 AndroidManifest 的 `<queries>`，否则 Android 11+ 查不到；
 * 只声明这一份名单，不用 QUERY_ALL_PACKAGES。
 * 注意：这里是**内部判定**用的数据，界面上不显示这些名称。
 */
internal object PartnerList {
    val PACKAGES: List<String> = listOf(
        "com.vectrasllc.vm",
    )
}
