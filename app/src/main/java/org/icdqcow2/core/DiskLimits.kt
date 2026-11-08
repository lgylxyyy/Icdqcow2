package org.icdqcow2.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 分区尺寸口径。
 *
 * **唯一真相源是工具端的 `cmd.list`**（`data.table.limits`，由 `img_constraints.c`
 * 的 `ARG_LIMITS[]` 生成）。App 启动时调一次 [applyFrom] 覆盖这里的默认值；
 * 读不到（旧 so / 异常）才用下面的常量兜底。
 *
 * 兜底值 = 2026-10-25 工具端答复（REPLY-03 §1）：
 * | 角色 | 最小 | 最大 | 0 含义 |
 * | disk（GiB） | 1 | 1024 | 不合法 |
 * | ESP | 128 MiB | 1024 MiB | 不合法 |
 * | MSR | 16 | 16 | 不合法 |
 * | 恢复 | 512 MiB | 2048 MiB | 不建 |
 * | Windows | 128 MiB（2026-10-26 起同 NTFS） | 无上限 | 吃满剩余 |
 * | MBR 系统保留 | 128 MiB | 1024 MiB | 用默认 512 |
 * | 数据分区 | 16 MiB | 无上限 | 不合法 |
 */
object DiskLimits {

    var DISK_MIN_GB = 1
    var DISK_MAX_GB = 1024

    var ESP_MIN_MB = 128
    var ESP_MAX_MB = 1024
    val ESP_DEFAULT_MB = 200

    var MSR_MB = 16

    /**
     * Windows 分区（`sys-mb`）下限，**2026-10-26 起 = NTFS 的 128 MB**。
     *
     * ★ 产品口径：Windows 系统分区**不再要求 16 GB**。装机要求是经验值，不是磁盘规则；
     *   做小盘/测试盘时 16 GB 下限只会挡路。
     * ★ 工具端已同步（REQ-12，so `2ae381b8…`）：`windows.min` / `sys-mb.gpt.min` /
     *   `mbr-win.min` 都是 128，建 128 MB 的盘能成。这里仍然 `min(工具值, 128)`：
     *   那是**防御旧 so**（旧版报 16384），别让旧值把界面口径盖回去。
     */
    var WIN_MIN_MB = NTFS_MIN_MB

    var REC_MIN_MB = 512
    var REC_MAX_MB = 2048

    var SYS_MIN_MB = 128
    var SYS_MAX_MB = 1024
    /**
     * 系统保留分区（MBR）默认 **128 MB**（2026-10-27 用户定：512 MB 太大，
     * `bootmgr` 400 KB + `\Boot\BCD` 36 KB，连 NTFS 元数据一起才 3 MB）。
     */
    val SYS_DEFAULT_MB = 128

    /** 数据分区（额外分区 / part.add）——**工具端口径**，由 `cmd.list` 覆盖。 */
    var EXTRA_MIN_MB = 16

    /**
     * NTFS 数据分区的**界面下限**（MB）。
     *
     * 工具端 `part.add` 收 16 MB，但 128 MB 以下的 NTFS 分区连 $MFT/日志都铺不开，
     * 格式化完基本不能用，所以界面统一按 128 MB 起（恢复分区仍是 512 MB）。
     * 故意和 [EXTRA_MIN_MB] 分开：一个是"能不能建"，一个是"值不值得建"。
     */
    const val NTFS_MIN_MB = 128

    /**
     * 长条上**显示**空闲段的最小阈值（MB）。
     *
     * 比 [EXTRA_MIN_MB] 小：别把"稍微小一点但确实是空闲"的段藏掉
     * （别的工具建的盘，分区之间可能只差几 MB）；但 1 MiB 对齐/HDR 那种
     * 纯表头留白还是不要画出来。
     */
    const val FREE_SHOW_MIN_MB = 4

    /**
     * MBR 能不能指定 Windows 分区大小（`img.create --win-mb`）。
     *
     * 由 `cmd.list` 的 `limits["mbr-win"]` 决定：**没有这一项就是旧 so**，
     * 建盘页把 MBR 的 Windows 行显示成只读。工具端交付（REQ-05）后自动变可点，
     * App 不需要改代码。
     */
    // ★ 用 Compose 状态托管：`applyFrom` 是异步跑的，界面可能先组合完；
    //   普通 var 改了不会触发重组，用户就会一直看到"本版还不支持"。
    private var mbrWinSupported by mutableStateOf(false)
    var MBR_WIN_SUPPORTED: Boolean
        get() = mbrWinSupported
        private set(v) { mbrWinSupported = v }

    /** `mbr-win` 的上限（MB，0 = 不限制，由剩余空间决定）。 */
    var MBR_WIN_MAX_MB = 0

    /** `mbr-win` 的下限（MB）。和 [WIN_MIN_MB] 同口径（128 MB），同样夹住旧 so 的 16384。 */
    var MBR_WIN_MIN_MB = NTFS_MIN_MB

    const val NAME_MAX = 32

    /**
     * Windows 布局建议的**磁盘容量**下限（GB）。
     *
     * 这是"能装下 Windows"的经验值（ESP + MSR + 系统盘 + 余量），**和分区下限无关**：
     * 分区下限已经降到 128 MB，但 1 GB 的盘做 Windows 布局仍然没意义，所以容量照旧 20 GB 起。
     */
    const val WIN_LAYOUT_RECOMMEND_GB = 20

    /** 工具端给的 0 上限 = 无上限，UI 里用一个足够大的值代替。 */
    private const val NO_LIMIT = 1 shl 22 // 4 TiB，单位 MB

    /**
     * 从 `cmd.list` 的原始 JSON 覆盖口径。结构见 REPLY-20261025-03 §3。
     * 解析失败就是**不动**（保持兜底值），不抛异常。
     */
    fun applyFrom(listJson: String) {
        runCatching {
            val root = JSONObject(listJson)
            // `cmd.list` 的真实形状是 **顶层** {version, features, limits, cmds}
            // （img_cmd_list_json 直接拼的裸对象）；有些通道会包一层 data / data.table，
            // 三种都试一遍，免得口径悄悄不生效。
            val limits = root.optJSONObject("limits")
                ?: root.optJSONObject("data")?.optJSONObject("limits")
                ?: root.optJSONObject("data")?.optJSONObject("table")?.optJSONObject("limits")
                ?: return

            limits.optJSONObject("disk")?.let {
                DISK_MIN_GB = it.optInt("min", DISK_MIN_GB)
                DISK_MAX_GB = clampMax(it.optInt("max", DISK_MAX_GB), DISK_MIN_GB, 1024 * 1024)
            }
            limits.optJSONObject("esp")?.let {
                ESP_MIN_MB = it.optInt("min", ESP_MIN_MB)
                ESP_MAX_MB = clampMax(it.optInt("max", ESP_MAX_MB), ESP_MIN_MB, NO_LIMIT)
            }
            limits.optJSONObject("msr")?.let {
                MSR_MB = it.optInt("min", MSR_MB)
            }
            limits.optJSONObject("recovery")?.let {
                REC_MIN_MB = it.optInt("min", REC_MIN_MB)
                REC_MAX_MB = clampMax(it.optInt("max", REC_MAX_MB), REC_MIN_MB, NO_LIMIT)
            }
            limits.optJSONObject("windows")?.let {
                // 取小：旧 so 报 16384 时不让它盖掉界面口径（新 so 已经是 128）
                WIN_MIN_MB = minOf(it.optInt("min", WIN_MIN_MB), NTFS_MIN_MB)
            }
            limits.optJSONObject("sys")?.let {
                SYS_MIN_MB = it.optInt("min", SYS_MIN_MB)
                SYS_MAX_MB = clampMax(it.optInt("max", SYS_MAX_MB), SYS_MIN_MB, NO_LIMIT)
            }
            limits.optJSONObject("data")?.let {
                EXTRA_MIN_MB = it.optInt("min", EXTRA_MIN_MB)
            }
            // MBR 的 Windows 尺寸（REQ-05）；字段在不在就是"支不支持"的开关
            limits.optJSONObject("mbr-win")?.let {
                MBR_WIN_SUPPORTED = true
                MBR_WIN_MIN_MB = minOf(it.optInt("min", MBR_WIN_MIN_MB), NTFS_MIN_MB)
                MBR_WIN_MAX_MB = it.optInt("max", 0).coerceAtLeast(0)
            }
            // sys-mb 分表不同：GPT 用 windows，MBR 用 sys（上面已经分别取过）
        }
    }

    /** `0` 或不合理值 → 用兜底上限，避免 UI 里出现 max < min。 */
    private fun clampMax(max: Int, min: Int, fallback: Int): Int =
        if (max <= 0) fallback else max.coerceAtLeast(min)
}