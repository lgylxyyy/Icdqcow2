package org.icdqcow2.core

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「操作前自动副本」（用户 2026-11-12 拍板 P2④）—— 参考实现。
 *
 * **什么时候需要**：释放空间（`img.sparsify` / `img.trim`）是**原地**改写映像 ——
 * 工具中途被杀、盘当时状态异常，都会让用户手里的映像变坏。压缩（`img.compact`）
 * 本来就是「另存一份新文件」，源不动，所以不需要副本。
 *
 * **副本有多大**：取决于目标文件系统。
 * · 支持稀疏的文件系统（SEEK_HOLE 可用）：只拷数据段，洞不拷 → 副本占用 ≈ 源占用；
 * · 不支持稀疏的（多数手机的 /sdcard 是 FUSE）：只能整份逐字节拷 → 副本占用 = 文件长度，
 *   哪怕源文件里全是洞。**这是必须提前告诉用户的事**（界面上写明 + 空间预检）。
 *
 * 副本先写 `.part` 再改名：中途失败不会留下一个「看着像完整副本」的半成品。
 */
object BackupCopy {

    /** Linux 的 SEEK_DATA / SEEK_HOLE（Android 的 Os 里没有这两个常量）。 */
    private const val SEEK_DATA = 3
    private const val SEEK_HOLE = 4

    /**
     * 副本文件名：`<原名去扩展名>-bak-<yyyyMMdd-HHmmss><原扩展名>`。
     * 放在**源文件旁边**（同一目录）—— 跨卷复制既慢又占双份空间，没有意义。
     */
    fun backupFileFor(src: File, stampMillis: Long = System.currentTimeMillis()): File {
        val name = src.name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(stampMillis))
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        return File(src.parentFile, "$base-bak-$stamp$suffix")
    }

    /**
     * 这个文件系统支不支持稀疏（SEEK_HOLE）？
     *
     * 不支持时调用方要知道「副本会占满文件长度」，好提前给用户算空间。
     */
    fun supportsSparse(src: File): Boolean = runCatching {
        FileInputStream(src).use { fis ->
            val fd = fis.fd
            val hole = Os.lseek(fd, 0, SEEK_HOLE)
            val data = Os.lseek(fd, 0, SEEK_DATA)
            hole >= 0 && data >= 0
        }
    }.getOrDefault(false)

    /**
     * 副本至少要占多少空间（字节）。
     *
     * · 支持稀疏 → 按「数据段总长」算（洞不占空间）；
     * · 不支持 → 按文件长度算（整份都要落盘）。
     */
    fun requiredBytes(src: File): Long {
        val total = src.length()
        if (total <= 0L) return 0L
        if (!supportsSparse(src)) return total
        var sum = 0L
        runCatching {
            FileInputStream(src).use { fis ->
                val fd = fis.fd
                var pos = 0L
                while (pos < total) {
                    val data = Os.lseek(fd, pos, SEEK_DATA)
                    if (data < 0) break
                    val hole = Os.lseek(fd, data, SEEK_HOLE)
                    val end = if (hole < 0) total else hole
                    sum += (end - data).coerceAtLeast(0L)
                    if (hole < 0) break
                    pos = hole
                }
            }
        }
        // 算不出就按最坏情况（文件长度）算，宁可多留空间也不爆盘
        return if (sum <= 0L) total else sum
    }

    /**
     * 复制一份副本。
     *
     * @param onProgress 已复制字节 / 总字节（总字节是**数据段**长度，支持稀疏时只算数据）。
     * @return null = 成功；非 null = 失败原因（已把半成品删掉）。
     */
    suspend fun copy(
        src: File,
        dst: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String? = withContext(Dispatchers.IO) {
        if (!src.exists()) return@withContext "源文件不存在"
        if (!src.isFile) return@withContext "源不是普通文件"
        if (dst.exists()) return@withContext "副本已存在：${dst.name}"
        val tmp = File(dst.parentFile, dst.name + ".part")
        val sparse = supportsSparse(src)
        try {
            val progressTotal = if (sparse) requiredBytes(src) else src.length()
            FileInputStream(src).use { fin ->
                FileOutputStream(tmp).use { fout ->
                    val buf = ByteArray(1 shl 20)
                    val fd = fin.fd
                    var pos = 0L
                    var copied = 0L
                    val total = src.length()
                    while (pos < total) {
                        // 稀疏：跳过洞（目标文件对应位置留空 → 天然是洞）
                        var len = (total - pos).coerceAtMost(buf.size.toLong())
                        if (sparse) {
                            val data = Os.lseek(fd, pos, SEEK_DATA)
                            if (data < 0) break                 // 后面全是洞
                            if (data > pos) {
                                pos = data
                                continue
                            }
                            val hole = Os.lseek(fd, pos, SEEK_HOLE)
                            if (hole > pos) len = (hole - pos).coerceAtMost(len)
                        }
                        val n = fin.read(buf, 0, len.toInt())
                        if (n <= 0) break
                        fout.write(buf, 0, n)
                        pos += n
                        copied += n
                        onProgress(copied, progressTotal)
                    }
                    // 稀疏时把长度补齐（尾部是洞）：truncate 扩长 = 稀疏尾
                    if (sparse && total > 0) fout.channel.truncate(total)
                    fout.fd.sync()
                }
            }
            if (!tmp.renameTo(dst)) {
                // 跨设备/文件系统时 rename 可能失败 → 退化成 copy
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
                if (!dst.exists()) return@withContext "副本改名失败：${dst.name}"
            }
            null
        } catch (t: Throwable) {
            tmp.delete()
            t.message ?: "复制失败"
        }
    }
}
