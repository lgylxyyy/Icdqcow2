package org.icdqcow2.core

/**
 * 剩余时间估计——**只看最近几秒的吞吐率**，再对结果做一次 EMA 平滑。
 *
 * 为什么不用工具端给的 `eta_s`：它是按"整段平均速度"算的（`remaining / (done/elapsed)`），
 * 而 `wim.apply` 前几秒在格式化、建元数据，速度接近 0 → 平均值被拖死。实测一个 40 秒跑完的
 * 释放，界面上报「约剩 30 分钟」，就是吃了这个亏。
 *
 * 做法（业界常见组合，tqdm 用 EMA、indicatif 讨论里也推荐滑动窗口）：
 * 1. 用**最近 [windowMs] 毫秒**的采样算瞬时速度（滑动窗口，窗口内首尾相减，抗抖动）；
 * 2. 对"剩余时间"再做指数平滑（α = 0.35），避免数字来回跳；
 * 3. 数据不足（窗口跨度 < 1 秒、或字节没动）就返回 -1，界面显示"正在估算…"，宁可不显示也不瞎报。
 */
class EtaMeter(private val windowMs: Long = 8_000L, private val alpha: Double = 0.35) {

    private class Sample(val at: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var smoothed = -1.0

    fun reset() {
        samples.clear()
        smoothed = -1.0
    }

    /**
     * 喂一次进度。
     *
     * @param bytes 已写入字节（工具端 `cur_bytes`）
     * @param total 总字节（`total_bytes`）
     * @return 剩余秒数；-1 = 还估不出来
     */
    fun feed(bytes: Long, total: Long, now: Long = System.currentTimeMillis()): Int {
        if (total <= 0L) return -1
        // 同一个采样点重放（工具端节流后可能重复推）→ 直接返回上次结果
        val last = samples.lastOrNull()
        if (last != null && last.bytes == bytes) return smoothed.toInt().takeIf { it >= 0 } ?: -1

        samples.addLast(Sample(now, bytes))
        while (samples.size > 2 && now - samples.first().at > windowMs) samples.removeFirst()

        val first = samples.first()
        val dtMs = now - first.at
        if (dtMs < 1_000L) return smoothed.toInt().takeIf { it >= 0 } ?: -1

        val deltaBytes = bytes - first.bytes
        if (deltaBytes <= 0L) return -1L.toInt()

        val rate = deltaBytes.toDouble() / (dtMs / 1000.0)   // 字节/秒
        if (rate <= 0.0) return -1
        val remaining = (total - bytes).coerceAtLeast(0L)
        val raw = remaining / rate

        smoothed = if (smoothed < 0.0) raw else smoothed * (1 - alpha) + raw * alpha
        // 上限一天；下限 1 秒（已经快完了就别显示 0）
        return smoothed.toInt().coerceIn(1, 86_400)
    }
}