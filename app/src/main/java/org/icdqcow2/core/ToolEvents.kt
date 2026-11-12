package org.icdqcow2.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.icdqcow2.imgtool.Native
import org.json.JSONObject

/**
 * 工具端**事件流**（`Native.setEventSink`）。
 *
 * 目前只消费一种：`{"ev":"progress",…}` —— 长任务（`wim.apply` / `iso.extract` / `img.clone`…）
 * 的进度。字段是本项目 `img_prog_json()` 定的：
 * `phase` / `op` / `cur` / `total` / `curBytes` / `totalBytes` / `pct` / `eta` / `elapsed` /
 * `item` / `msg`。
 *
 * 回调在**工作线程**上（JNI 现挂现调），所以这里只做"解析 + 写 StateFlow"，不碰 UI。
 */
object ToolEvents {

    /** 一次进度快照。 */
    data class Progress(
        val pct: Int,
        val cur: Long,
        val total: Long,
        val curBytes: Long,
        val totalBytes: Long,
        /** 预计剩余秒数（工具端给的，-1 = 不知道）。 */
        val eta: Int,
        val phase: String,
        val op: String,
        val item: String,
        val msg: String,
        /** 本命令已运行毫秒（`elapsed`）。进度页圆盘内那行「已用」直接用它，不用 App 侧计时。 */
        val elapsedMs: Long = 0L,
        /** 工具端算好的**全局单调**进度（`-1` = 还没算出来）。REPLY-49：wim.apply 直接用它。 */
        val overallPct: Int = -1,
        val overallCurBytes: Long = 0L,
        val overallTotalBytes: Long = 0L,
        /** `"raw"`（默认）/ `"extracted"`（archive.extract：cur/total 是解压后口径）。 */
        val bytesKind: String = "raw",
        /** 压缩包口径。RAR 的 cur 是准的；zip/7z 拿不到 → 0（**0 = 给不了，不是没进度**）。 */
        val archiveCurBytes: Long = 0L,
        val archiveTotalBytes: Long = 0L,
        /**
         * 文件计数（REQ-77 Q1）：`files_cur` / `files_total`。
         * 0 = 这个任务没有文件概念，或总数未知（**不是"还有 0 个"**）。
         */
        val filesCur: Long = 0L,
        val filesTotal: Long = 0L,
        /**
         * 正在写到哪里（REQ-77 Q2）：`dst_item`。
         * [item] 是**源**路径，这个是**目标**路径（写文件类任务才有）。
         */
        val dstItem: String = "",
    )

    private val _progress = MutableStateFlow<Progress?>(null)

    /** 最新进度；null = 没有在跑的长任务。 */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    fun clear() {
        _progress.value = null
    }

    /**
     * 装到 [Native.setEventSink]。**必须**在 `MainActivity.onCreate` 里调一次：
     * 不装的话工具端不会推事件（内部是 `imgapp_set_event_cb(NULL,…)`）。
     */
    fun install() {
        runCatching {
            Native.setEventLevel(2) // Info：里程碑 + 进度
            Native.setEventSink(Sink())
        }
    }

    /** 实现 `Native.EventSink`（JNI 里按 `onEvent(String)` 调）。 */
    private class Sink : Native.EventSink {
        override fun onEvent(jsonLine: String) {
            // 真实形状（jni/imgapp_bridge.c 的 prog_to_json）：
            // {"level":"INFO","logger":"prog","event":"progress","rc":0,
            //  "fields":{"phase":…,"op":…,"cur":N,"total":N,"cur_bytes":N,"total_bytes":N,
            //            "pct":N,"eta_s":N,"item":"…"}}
            if (!jsonLine.contains("\"progress\"")) return
            val o = runCatching { JSONObject(jsonLine) }.getOrNull() ?: return
            if (o.optString("event") != "progress") return
            val f = o.optJSONObject("fields") ?: return
            _progress.value = Progress(
                pct = f.optInt("pct", -1),
                cur = f.optLong("cur", 0L),
                total = f.optLong("total", 0L),
                curBytes = f.optLong("cur_bytes", 0L),
                totalBytes = f.optLong("total_bytes", 0L),
                eta = f.optInt("eta_s", -1),
                phase = f.optString("phase"),
                op = f.optString("op"),
                item = f.optString("item"),
                msg = f.optString("msg"),
                // REQ-69 §5：优先毫秒字段；老桥只有 elapsed（秒）→ 乘 1000 兜底
                elapsedMs = f.optLong("elapsed_ms", f.optLong("elapsed", 0L) * 1000L),
                overallPct = f.optInt("overall_pct", -1),
                overallCurBytes = f.optLong("overall_cur_bytes", 0L),
                overallTotalBytes = f.optLong("overall_total_bytes", 0L),
                bytesKind = f.optString("bytes_kind", "raw"),
                archiveCurBytes = f.optLong("archive_cur_bytes", 0L),
                archiveTotalBytes = f.optLong("archive_total_bytes", 0L),
                // REQ-77：文件计数 + 正在写入的目标路径
                filesCur = f.optLong("files_cur", 0L),
                filesTotal = f.optLong("files_total", 0L),
                dstItem = f.optString("dst_item", ""),
            )
        }
    }
}