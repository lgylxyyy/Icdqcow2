package org.icdqcow2.core

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近打开过的镜像（"历史文件"）。
 *
 * 三条关键设计：
 * 1. **同时存 path 和 uri**：别的应用（虚拟机）建的镜像路径可能没权限（`Permission denied`），
 *    只有 SAF 给的 uri + 持久授权能用 —— 只存 path 下次点了还是打不开；
 * 2. **"还在不在"要两条腿判断**：路径能 stat 到就算在；路径 stat 不到（老版本把
 *    documentId 里的绝对路径又拼了一遍卷前缀，存成了
 *    `/storage/emulated/0/storage/emulated/0/…`）就退回用 uri 能不能打开来判断 ——
 *    只看路径会把好条目静默丢掉（这正是"明明打开过、历史里却没有"的原因）；
 * 3. **界面订阅 [visible]**：判断要不要 stat 文件、能不能打开 uri 都是 IO，
 *    不能放在 Composable 里跑，也不能每次重组都跑一遍。
 */
object ImageHistory {

    data class Entry(
        val path: String?,
        val uri: String?,
        val name: String,
        val sizeBytes: Long,
        val lastUsed: Long,
    )

    private lateinit var appCtx: Context
    private val sp by lazy { appCtx.getSharedPreferences("image_history", Context.MODE_PRIVATE) }

    /** 内部最多留这么多条（显示条数由设置里的 `historyCount` 决定）。 */
    private const val KEEP_MAX = 20

    private val _showCount = MutableStateFlow(3)

    /** 「选择镜像」页显示几条（设置里可调 不显示/3/5/7/9）。 */
    val showCount: StateFlow<Int> = _showCount.asStateFlow()

    fun setShowCount(n: Int) {
        _showCount.value = n
    }

    private val _visible = MutableStateFlow<List<Entry>>(emptyList())

    /** 文件确实还在的条目（最近使用在前）。界面 collect 这个。 */
    val visible: StateFlow<List<Entry>> = _visible.asStateFlow()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // 先用**便宜**的判断（只 stat 路径）垫一版，避免页面空一下再跳出来；
        // 之后 refresh() 再补 uri 判断 + 自愈路径。
        _visible.value = load()
            .sortedByDescending { it.lastUsed }
            .filter { it.path != null && File(it.path).exists() }
    }

    private fun load(): List<Entry> = runCatching {
        val raw = sp.getString("items", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                path = o.optString("path", "").ifBlank { null },
                uri = o.optString("uri", "").ifBlank { null },
                name = o.optString("name", ""),
                sizeBytes = o.optLong("size", 0L),
                lastUsed = o.optLong("ts", 0L),
            )
        }
    }.getOrDefault(emptyList())

    /** 只写盘，不改 [visible]（发布由 [refresh] / [record] 统一做，避免把死条目放出去）。 */
    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("path", it.path ?: "")
                    .put("uri", it.uri ?: "")
                    .put("name", it.name)
                    .put("size", it.sizeBytes)
                    .put("ts", it.lastUsed),
            )
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    private fun pathAlive(p: String?): Boolean = p != null && File(p).exists()

    /** uri 还能不能打开（能开 = 文件还在，哪怕路径 stat 不到）。 */
    private fun uriAlive(u: String?): Boolean {
        val uri = u?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return runCatching {
            appCtx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun uriSize(u: String): Long =
        u.let { runCatching { Uri.parse(it) }.getOrNull() }?.let { StorageAccess.querySize(appCtx, it) } ?: 0L

    private fun fileLen(p: String?): Long =
        p?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L

    /** 真正的"还在不在"：路径优先，退回 uri。 */
    private fun alive(e: Entry): Boolean = pathAlive(e.path) || uriAlive(e.uri)

    /**
     * 由界面在进入「选择镜像」页时调用（IO 线程）：筛掉文件确实没了的条目，
     * 顺手把能修的东西修掉——
     * - 老版本存坏的路径 → 用 uri 反推出真实路径；
     * - 大小为 0 → 补上真实大小（历史里那一列才不会全是 `—`）。
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        var dirty = false
        val healed = load().map { e ->
            var out = e
            if (!pathAlive(out.path)) {
                val derived = out.uri
                    ?.let { u -> runCatching { Uri.parse(u) }.getOrNull() }
                    ?.let { StorageAccess.documentToPath(it) }
                if (derived != null && File(derived).exists()) {
                    out = out.copy(path = derived)
                    dirty = true
                }
            }
            // 文件大小每次都重新取：镜像会随着分区/数据写入变大，只补 0 的话永远停在第一次的值
            val real = fileLen(out.path).takeIf { it > 0 } ?: out.uri?.let { uriSize(it) } ?: 0L
            if (real > 0 && real != out.sizeBytes) {
                out = out.copy(sizeBytes = real)
                dirty = true
            }
            out
        }
        if (dirty) save(healed)
        _visible.value = healed.filter { alive(it) }.sortedByDescending { it.lastUsed }
    }

    /**
     * 记一条（同一个 path/uri 会被提到最前）。
     *
     * `sizeBytes <= 0` 时自己补：路径能 stat 就用文件长度，否则问 provider。
     */
    suspend fun record(path: String?, uri: String?, name: String, sizeBytes: Long) {
        if (path == null && uri == null) return
        withContext(Dispatchers.IO) {
            val size = sizeBytes.takeIf { it > 0 }
                ?: fileLen(path).takeIf { it > 0 }
                ?: uri?.let { uriSize(it) }?.takeIf { it > 0 }
                ?: 0L
            val display = name.ifBlank {
                path?.substringAfterLast('/')
                    ?: uri?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
                    ?: "image.qcow2"
            }
            val list = load()
                .filterNot { (it.path != null && it.path == path) || (it.uri != null && it.uri == uri) }
                .toMutableList()
            list.add(0, Entry(path, uri, display, size, System.currentTimeMillis()))
            while (list.size > KEEP_MAX) list.removeAt(list.lastIndex)
            save(list)
            _visible.value = list.filter { alive(it) }.sortedByDescending { it.lastUsed }
        }
    }
}