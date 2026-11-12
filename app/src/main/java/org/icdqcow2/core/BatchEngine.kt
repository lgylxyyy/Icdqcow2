package org.icdqcow2.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 批量任务引擎（用户 2026-11-12 拍板 P2②③）—— 参考实现。
 *
 * **为什么要有它**：压缩映像、释放空间都是「一次一个盘」的长任务；用户手上
 * 往往有十几个盘要处理，一个一个点太折磨。批量把它们排进队列，顺序做，
 * 做完一个接着下一个 —— 全程不用守着。
 *
 * **跑在后台**：真正的执行放在 [BatchService]（前台服务）里，手机息屏、切走
 * 页面都不打断；通知栏能看到进度和当前文件。
 *
 * **停止语义**（重要）：[requestStop] 是「**当前这一项做完就停**」，不是打断。
 * 用户 2026-11-12 拍板过「开始后不可取消」——写盘写到一半被掐断，映像可能
 * 处在半写状态。所以这里只停「不再开始下一项」，绝不打断正在写的那一项。
 *
 * **失败不中断**：一项失败就记下来，继续下一项（一批盘里有一个坏的，
 * 不该让剩下的一起停）。
 */
object BatchEngine {

    /** 一项批量任务：一个映像 + 一件事。 */
    data class Item(
        val id: String,
        val path: String,
        val op: Op,
        /**
         * COMPACT：`off`（只紧凑化）/ `zlib` / `zstd`；
         * RECLAIM：`fs`（按客户机空闲）/ `zero`（按内容全零）。
         */
        val arg: String,
        val state: State = State.PENDING,
        /** 成功摘要（做完给用户看的一句话）。 */
        val result: String? = null,
        val error: String? = null,
    ) {
        enum class Op { COMPACT, RECLAIM }
        enum class State { PENDING, RUNNING, DONE, FAILED }
    }

    /** 批次整体状态（界面 + 通知都看这个）。 */
    data class State(
        val items: List<Item> = emptyList(),
        val running: Boolean = false,
        /** 正在跑的那一项的下标；-1 = 没在跑。 */
        val current: Int = -1,
        /** 当前阶段说明（已本地化）。 */
        val phase: String = "",
        /** 整体进度 0..1。 */
        val overall: Float = 0f,
        /** 当前这一项的进度 0..1（界面上可以显示得更细）。 */
        val itemProgress: Float = 0f,
        val stopRequested: Boolean = false,
        /** 这一轮跑完了（成功或失败都算）。 */
        val finished: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())

    /** 界面与通知都订阅它。 */
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var appCtx: Context? = null

    /** 挂上 application context（要读设置、算空间）。 */
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 往队列里加几个映像（同一个操作、同一个参数）。 */
    fun add(paths: List<String>, op: Item.Op, arg: String) {
        val fresh = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fresh.isEmpty()) return
        val items = fresh.map { p ->
            Item(
                id = "${System.nanoTime()}-${p.hashCode()}",
                path = p,
                op = op,
                arg = arg,
            )
        }
        _state.value = _state.value.copy(items = _state.value.items + items, finished = false)
    }

    /** 移除一项（跑着的时候不许改队列）。 */
    fun remove(id: String) {
        if (_state.value.running) return
        _state.value = _state.value.copy(items = _state.value.items.filterNot { it.id == id })
    }

    /** 清空队列（跑着的时候不许）。 */
    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    /** 停队列：当前项做完就停（不打断正在写的那一项）。 */
    fun requestStop() {
        _state.value = _state.value.copy(stopRequested = true)
    }

    /** 开始跑。返回 false = 没有可跑的东西 / 已经在跑。 */
    fun start(ctx: Context): Boolean {
        attach(ctx)
        if (_state.value.running) return false
        if (_state.value.items.none { it.state == Item.State.PENDING }) return false
        job = scope.launch { run() }
        return true
    }

    private fun update(idx: Int, f: (Item) -> Item) {
        val items = _state.value.items.toMutableList()
        if (idx !in items.indices) return
        items[idx] = f(items[idx])
        _state.value = _state.value.copy(items = items)
    }

    private suspend fun run() {
        _state.value = _state.value.copy(running = true, stopRequested = false, finished = false)
        val total = _state.value.items.count { it.state == Item.State.PENDING }
        var done = 0

        // 整体进度 =（已完成的项 + 当前项的进度）/ 总项数
        val progJob = scope.launch {
            ToolEvents.progress.collect { p ->
                val st = _state.value
                if (!st.running || p == null) return@collect
                val doneCount = st.items.count { it.state == Item.State.DONE }
                val cur = (p.pct.coerceIn(0, 100)) / 100f
                _state.value = st.copy(
                    overall = ((doneCount + cur) / total).coerceIn(0f, 1f),
                    itemProgress = cur,
                )
            }
        }

        try {
            val idxs = _state.value.items.indices.toList()
            for (idx in idxs) {
                val item = _state.value.items.getOrNull(idx) ?: continue
                if (item.state != Item.State.PENDING) continue
                if (_state.value.stopRequested) break
                update(idx) { it.copy(state = Item.State.RUNNING) }
                _state.value = _state.value.copy(current = idx, itemProgress = 0f)
                // ★ 不登记到 ToolRun（全局浮层）：登记了会把返回箭头藏掉、系统返回也吃掉，
                //   用户就离不开这个页面了 —— 而批量任务本来就该能放着不管。
                //   进度由本页的进度条 + 通知栏显示。
                ToolEvents.clear()
                try {
                    val note = runItem(item)
                    update(idx) { it.copy(state = Item.State.DONE, result = note) }
                } catch (t: Throwable) {
                    update(idx) { it.copy(state = Item.State.FAILED, error = t.message ?: t.toString()) }
                } finally {
                    done++
                    _state.value = _state.value.copy(overall = (done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } finally {
            progJob.cancel()
            _state.value = _state.value.copy(
                running = false,
                current = -1,
                phase = "",
                overall = if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f),
                finished = true,
            )
        }
    }

    /** 阶段标题（按当前语言取界面文案）。 */
    private fun titleFor(item: Item): String = when (item.op) {
        Item.Op.COMPACT -> tr("batch.opCompact", "批量压缩")
        Item.Op.RECLAIM -> tr("batch.opReclaim", "批量释放空间")
    }

    /** 取界面文案（当前语言；取不到就用兜底）。BatchService 的通知文案也用它。 */
    internal fun tr(key: String, fallback: String): String {
        val ctx = appCtx ?: return fallback
        val code = runCatching { I18n.resolve(SettingsStore.get(ctx).language.value) }.getOrDefault("zh")
        return I18n.table(code)[key] ?: fallback
    }

    private suspend fun runItem(item: Item): String = when (item.op) {
        Item.Op.COMPACT -> runCompact(item)
        Item.Op.RECLAIM -> runReclaim(item)
    }

    /** 压缩：另存一份新的 qcow2（源不动）。 */
    private suspend fun runCompact(item: Item): String {
        val src = File(item.path)
        val dir = src.parentFile ?: throw IllegalStateException("源文件没有所在目录")
        val base = src.name.substringBeforeLast('.', src.name).ifEmpty { "image" }
        var outName = "$base-compact.qcow2"
        var n = 1
        while (File(dir, outName).exists() && n < 99) {
            outName = "$base-compact ($n).qcow2"
            n++
        }
        val out = File(dir, outName)
        val r = ImageRepo.compact(
            src = item.path,
            out = out.absolutePath,
            compress = item.arg.takeIf { it != "off" },
        )
        if (!r.ok) throw IllegalStateException(errText(r))
        val before = src.length()
        val after = out.length()
        return "$outName · ${fmtBytes(before)} → ${fmtBytes(after)}"
    }

    /** 释放空间：原地回收（老 so 没有 img.sparsify 时退回 img.trim）。 */
    private suspend fun runReclaim(item: Item): String {
        val src = File(item.path)
        var backupNote = ""
        // ★ P2④：和单盘释放空间一样，原地改写前先留副本
        val settings = appCtx?.let { SettingsStore.get(it) }
        if (settings?.autoBackup?.value == true) {
            val dst = BackupCopy.backupFileFor(src)
            val need = BackupCopy.requiredBytes(src)
            val free = runCatching { src.parentFile?.usableSpace ?: 0L }.getOrDefault(0L)
            if (need > free) {
                throw IllegalStateException("空间不够：副本需要 ${fmtBytes(need)}，当前只剩 ${fmtBytes(free)}")
            }
            val reason = BackupCopy.copy(src, dst) { c, t ->
                val frac = if (t > 0) (c.toFloat() / t).coerceIn(0f, 1f) else 0f
                _state.value = _state.value.copy(
                    itemProgress = frac,
                    phase = tr("batch.phaseCopy", "复制副本"),
                )
            }
            if (reason != null) throw IllegalStateException("复制副本失败：$reason")
            backupNote = "副本 ${dst.name} · "
        }
        val r = if (ToolCaps.SPARSIFY) {
            ImageRepo.sparsify(item.path, item.arg, dryRun = false)
        } else {
            ImageRepo.trim(img = item.path, dryRun = false, punch = true)
        }
        if (!r.ok) throw IllegalStateException(errText(r))
        val freed = r.data?.optLong("freed_bytes", 0L) ?: 0L
        return backupNote + "释放 ${fmtBytes(freed)}"
    }

    private fun errText(r: ToolRunner.Result): String {
        val e = r.err
        return e?.optString("message")?.takeIf { it.isNotBlank() }
            ?: e?.optString("error_code")?.takeIf { it.isNotBlank() }
            ?: "操作失败"
    }

    private fun fmtBytes(n: Long): String {
        if (n <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${n} B" else String.format("%.1f %s", v, units[i])
    }
}
