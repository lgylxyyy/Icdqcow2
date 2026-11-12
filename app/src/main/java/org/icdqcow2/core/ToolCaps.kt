package org.icdqcow2.core

import org.json.JSONObject

/**
 * 工具端的**能力探测**（从 `cmd.list` 的 `cmds[].args[]` 读）。
 *
 * 为什么不用"试一次看报错"：参数校验失败虽然不写盘，但会多跑一次命令，
 * 日志里还会留一条失败记录；用户还会看到"建盘失败"闪一下。
 *
 * 现在只探两件事，都跟 MBR 建盘有关：
 * - `img.create` 有没有 `mbr-fill` 参数（REPLY-07）；
 * - 它的枚举取值里有没有 `auto`（REQ-08，App 只用 auto，见 [mbrFillPolicy]）。
 */
object ToolCaps {

    /** `cmd.list` 里出现的命令名（用来判断"这条路当前 so 支不支持"）。 */
    var CMDS: Set<String> = emptySet()
        private set

    /** 某个命令当前 so 认不认（没有 `cmd.list` 时一律 false，走兜底）。 */
    fun has(cmd: String): Boolean = cmd in CMDS

    /** 每个命令认的参数名（`cmd.list` 的 `args[].name`）。 */
    var ARGS: Map<String, Set<String>> = emptyMap()
        private set

    /** 某个命令认不认这个参数（用它决定「新能力」要不要亮）。 */
    fun hasArg(cmd: String, arg: String): Boolean = ARGS[cmd]?.contains(arg) == true

    /** 工具端 `cmd.list` 的 `features`（编进了哪些可选库）：wimlib / ntfs3g / zlib / zstd … */
    var FEATURES: Set<String> = emptySet()
        private set

    /** 工具端有没有编入 libzstd（qcow2 的 zstd 压缩簇：读要解码器、写要编码器）。 */
    val ZSTD: Boolean get() = "zstd" in FEATURES

    /** `img.sparsify` 到位（T7）：原地回收 raw/qcow2 未使用的块。 */
    val SPARSIFY get() = has("img.sparsify")

    /** `img.compact --compress` 到位（T6）：qcow2 压缩簇。 */
    val COMPACT_COMPRESS get() = hasArg("img.compact", "compress")

    /** `img.create` 有 `mbr-fill` 参数（REPLY-07 起）。 */
    var MBR_FILL = false
        private set

    /** `mbr-fill` 的允许取值（`cmd.list` 里 `values` 数组；老 so 没有这个字段）。 */
    var MBR_FILL_VALUES: Set<String> = emptySet()
        private set

    /**
     * 建 MBR 盘时该传的 `mbr-fill` 值；`null` = 不传（保持 reserve 老行为）。
     *
     * 只认 `auto`：`primary` 会把 4 个主槽全用满、不留容器，之后加不了第 5 块
     * （见 REQ-20261025-08）。所以工具端没交付 `auto` 之前，这里恒为 null。
     */
    val mbrFillPolicy: String?
        get() = if ("auto" in MBR_FILL_VALUES) "auto" else null

    /**
     * 工具端的 `wim.identify` / `wim.apply` 支持 **ISO 直读**（REPLY-16：`--scan` / `--inner`，
     * wimlib 走进程内虚拟 fd，不落临时文件）。
     *
     * 判据是 `cmd.list` 里 `wim.identify` 有 `scan` 参数。
     * ⚠ 2026-10-26 那版 so（`8335a9d8…`）因为 `U __real_*` 悬空、**载入就失败**，
     * 已临时回退到 `06b4d83a…`（没有这两个参数）→ 这里会是 false，
     * 释放页会自动走"先 `iso.extract` 解出来再灌"的老路（见 [WIM_ISO_DIRECT]）。
     */
    var WIM_ISO_DIRECT = false
        private set

    fun applyFrom(listJson: String) {
        runCatching {
            val root = JSONObject(listJson)
            val cmds = root.optJSONArray("cmds")
                ?: root.optJSONObject("data")?.optJSONArray("cmds")
                ?: return
            val names = mutableSetOf<String>()
            val argMap = mutableMapOf<String, MutableSet<String>>()
            for (i in 0 until cmds.length()) {
                val c = cmds.optJSONObject(i) ?: continue
                val cname = c.optString("name")
                cname.takeIf { it.isNotEmpty() }?.let { names.add(it) }
                val args = c.optJSONArray("args") ?: continue
                if (cname.isNotEmpty()) {
                    val set = argMap.getOrPut(cname) { mutableSetOf() }
                    for (j in 0 until args.length()) {
                        args.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { set.add(it) }
                    }
                }
                when (c.optString("name")) {
                    "img.create" -> for (j in 0 until args.length()) {
                        val a = args.optJSONObject(j) ?: continue
                        if (a.optString("name") != "mbr-fill") continue
                        MBR_FILL = true
                        val vals = a.optJSONArray("values") ?: continue
                        MBR_FILL_VALUES = buildSet {
                            for (k in 0 until vals.length()) add(vals.optString(k))
                        }
                    }
                    // ISO 直读 = `wim.identify` 有 `scan`（REPLY-16）
                    "wim.identify" -> for (j in 0 until args.length()) {
                        val a = args.optJSONObject(j) ?: continue
                        if (a.optString("name") == "scan") {
                            WIM_ISO_DIRECT = true
                            break
                        }
                    }
                }
            }
            if (names.isNotEmpty()) CMDS = names
            if (argMap.isNotEmpty()) ARGS = argMap
            // features：编进了哪些可选库（zstd 等）。缺省 false 的键不收。
            root.optJSONObject("features")?.let { feats ->
                val set = mutableSetOf<String>()
                val it = feats.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (feats.optBoolean(k, false)) set.add(k)
                }
                if (set.isNotEmpty()) FEATURES = set
            }
        }
    }
}