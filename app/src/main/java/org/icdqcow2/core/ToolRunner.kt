package org.icdqcow2.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.icdqcow2.imgtool.Native
import org.json.JSONObject

/**
 * 工具调用的唯一入口。
 *
 * 为什么是单例 + Mutex：
 *   imgtool 的进度/取消是**进程级单例**，同一时刻只能有一个命令在跑
 *   （见 docs/工具手册 §0）。界面各处（控制台/文件页/装机）都必须走这里，
 *   谁也不能绕过队列。
 */
object ToolRunner {

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    data class Result(
        val cmd: String,
        val ok: Boolean,
        val code: String? = null,
        val at: String? = null,
        val durationMs: Long = 0,
        val summary: String = "",
        val raw: String = "",
        /** 成功时的 `data` 对象，给需要明细的调用方二次解析。 */
        val data: JSONObject? = null,
        /**
         * 工具端失败信封里的说明（REPLY-11 起所有码都带）。
         *
         * ★ **只有中文**（`--lang=en` 也不变），所以界面默认不显示它：
         *   只在"App 表里没有这个码"且当前语言是中文时兜底，免得露一串裸码。
         */
        val hint: String? = null,
        /** 失败但活儿干了一半（`LABEL-WRITE-FAIL` + `formatted=true` = 卷已建好，只是名字没写上）。 */
        val formatted: Boolean = false,
    )

    /** 命令表（cmd.list 的原始 JSON）。失败返回 null，调用方用静态兜底表。 */
    suspend fun listJson(): String? = withContext(Dispatchers.IO) {
        runCatching { Native.list() }.getOrNull()
    }

    /**
     * 协作式取消：`Native.cancel()`，下一个检查点生效（`wim.apply` 每写一块都查）。
     * 取消后正在跑的 [exec] 会以错误返回（工具端 `IMG-ECANCELLED`）。
     */
    fun cancel() {
        runCatching { Native.cancel() }
    }

    suspend fun exec(
        cmd: String,
        args: Map<String, String> = emptyMap(),
        imagePath: String? = null,
        writable: Boolean = false,
        /** SAF 文件描述符：>=0 时**取代** imagePath（工具端直接读 fd，绕开路径权限）。 */
        imageFd: Int = -1,
    ): Result = mutex.withLock {
        _running.value = true
        try {
            withContext(Dispatchers.IO) {
                val keys = args.keys.toTypedArray()
                val vals = args.values.toTypedArray()
                // ★ 崩溃诊断：这条**在调用前**打，native 层 SIGSEGV 时进程直接没了，
                //   后面那条"→ ok/FAIL"永远出不来 —— 有这一条才知道死在哪个命令上。
                runCatching {
                    android.util.Log.i(
                        "IcdqcowTool",
                        buildString {
                            append("→ ").append(cmd)
                            args.forEach { (k, v) ->
                                append(' ').append('@').append(k).append('=')
                                append(if (v.length > 120) v.take(120) + "…" else v)
                            }
                            if (imagePath != null) append(" @img=").append(imagePath)
                        },
                    )
                }
                val raw = runCatching {
                    // ★ 栈由**工具端自己**兜（REPLY-21）：`img_cmd_exec` 先量当前线程栈，
                    //   < 6 MB 就换一条 16 MB 栈的线程跑并 join 回来，签名/返回值都不变。
                    //   所以这里不用再套一层大栈线程（以前 App 侧套过，REPLY-21 之后删除）。
                    Native.exec(cmd, keys, vals, imagePath, imageFd, writable, false, null, 2)
                }.getOrElse { return@withContext Result(cmd, false, "JNI-FAIL", null, 0, it.message ?: "JNI 调用失败", "") }
                val r = parse(cmd, raw)
                // ★ 诊断用（2026-10-27）：`adb logcat -s IcdqcowTool` 就能看到 App 实际下发了什么、
                //   工具回了什么码 —— 有些"命令行能跑、App 不能"的问题只能靠这行定位。
                runCatching {
                    android.util.Log.i(
                        "IcdqcowTool",
                        buildString {
                            append(cmd)
                            args.forEach { (k, v) ->
                                append(' ').append('@').append(k).append('=')
                                append(if (v.length > 120) v.take(120) + "…" else v)
                            }
                            if (imagePath != null) append(" @img=").append(imagePath)
                            append(" → ").append(if (r.ok) "ok" else "FAIL")
                            if (!r.ok) append(' ').append(r.code ?: "?").append(" @").append(r.at ?: "-")
                            append(" (").append(r.durationMs).append("ms)")
                            // 失败时把工具的原话也带上（hint 里会写清是"打不开"还是"确实没有"）
                            if (!r.ok) {
                                val tail = r.raw.replace('\n', ' ')
                                append(" {").append(if (tail.length > 320) tail.take(320) + "…" else tail).append('}')
                            }
                        },
                    )
                }
                r
            }
        } finally {
            _running.value = false
        }
    }

    private fun parse(cmd: String, raw: String): Result {
        val line = raw.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: raw
        return runCatching {
            val o = JSONObject(line)
            val ok = o.optBoolean("ok", false)
            val ms = o.optLong("duration_ms", 0)
            if (ok) {
                val data = o.optJSONObject("data")
                Result(cmd, true, durationMs = ms, summary = summarize(cmd, data), raw = line, data = data)
            } else {
                val err = o.optJSONObject("err")
                Result(
                    cmd = cmd,
                    ok = false,
                    code = err?.optString("error_code"),
                    at = err?.optString("at"),
                    hint = err?.optString("hint")?.takeIf { it.isNotBlank() },
                    formatted = err?.optBoolean("formatted", false) == true ||
                        o.optJSONObject("data")?.optBoolean("formatted", false) == true,
                    durationMs = ms,
                    summary = err?.optString("error_code").orEmpty(),
                    raw = line,
                    // ★ 失败信封也可能带 data（PART-SIZE-BAD 的 min/max、PART-NOSPACE 的 avail_mb）
                    data = o.optJSONObject("data"),
                )
            }
        }.getOrElse {
            Result(cmd, false, "RESULT-BAD", null, 0, "返回不是合法 JSON", line)
        }
    }

    /** 只挑几个对用户有意义的数字，不把整段 data 塞到卡片上。 */
    private fun summarize(cmd: String, data: JSONObject?): String {
        if (data == null) return "ok"
        val parts = mutableListOf<String>()
        when (cmd) {
            "img.info" -> {
                data.optLong("size_bytes", 0).takeIf { it > 0 }?.let { parts += fmtBytes(it) }
                data.optInt("parts_total", -1).takeIf { it >= 0 }?.let { parts += "$it 个分区" }
            }
            "part.show" -> data.optJSONObject("pt")?.optJSONArray("parts")?.let { parts += "${it.length()} 个分区" }
            "cmd.list" -> data.optJSONArray("cmds")?.let { parts += "${it.length()} 条命令" }
            else -> {
                data.optLong("bytes", -1).takeIf { it > 0 }?.let { parts += fmtBytes(it) }
                data.optInt("count", -1).takeIf { it >= 0 }?.let { parts += "$it 项" }
                data.optBoolean("clean", false).takeIf { it }?.let { parts += "结构干净" }
            }
        }
        if (parts.isEmpty()) {
            val keys = data.keys()
            while (keys.hasNext()) { parts += keys.next(); if (parts.size >= 3) break }
        }
        return parts.joinToString(" · ")
    }

    fun fmtBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }
}