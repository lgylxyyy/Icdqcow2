package org.icdqcow2.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 32 位 UEFI 固件（edk2 OVMF IA32 的 CODE + VARS 一对）。
 *
 * 为什么要有这个东西：
 *   32 位 Windows 要在 **UEFI** 下启动，固件必须是能加载 32 位 PE 的 IA32 版 edk2。
 *   QEMU 的换法是挂两颗 pflash 芯片：
 *
 *     -drive file=<code>.fd,format=raw,readonly=on,if=pflash,unit=0
 *     -drive file=<vars>.fd,format=raw,if=pflash,unit=1
 *
 *   这两个文件必须在**公共目录**里 —— QEMU / Vectras 是别的 App，读不到我们的私有目录。
 *
 * 固件从哪来：
 *   **随 App 打包**（`assets/firmware/`）。不依赖用户装的 Vectras 版本，
 *   也不依赖任何下载站点（上游 TianoCore 已删除 OvmfPkgIa32，没有稳定直链）。
 *   首次启动时自动释放到公共目录，之后每次启动检查一遍，被删了自动补回。
 *
 * 许可：
 *   edk2 是 BSD-2-Clause-Patent，允许随二进制再分发。见仓库 `THIRD_PARTY.md`。
 */
object FirmwareStore {

    /** 公共目录：任何 App 都读得到，Vectras 的 QEMU 参数能直接指向这里。 */
    const val DIR = "/storage/emulated/0/Documents/Icdqcow2/Firmware"

    const val CODE_NAME = "edk2-i386-code.fd"
    const val VARS_NAME = "edk2-i386-vars.fd"

    const val CODE_BYTES = 3_653_632L
    const val VARS_BYTES = 540_672L

    /** 与 `assets/firmware/` 里那两份逐字节一致（同 Vectras 自带版本）。 */
    const val CODE_MD5 = "2e230c41fd85e94654e39d804b1ebc27"
    const val VARS_MD5 = "173134c7c1593bad9cd101dc10bef49b"

    val codePath: String get() = "$DIR/$CODE_NAME"
    val varsPath: String get() = "$DIR/$VARS_NAME"

    /** 一对固件在公共目录里的状态。 */
    data class State(
        val codeOk: Boolean = false,
        val varsOk: Boolean = false,
        /** 目录建不出来 / 写不进去（多半是没给「所有文件访问权限」）。 */
        val denied: Boolean = false,
    ) {
        val ready: Boolean get() = codeOk && varsOk
    }

    /** 拼给 QEMU 的那两段 pflash 参数（路径用公共目录的绝对路径）。 */
    fun qemuArgs(
        code: String = codePath,
        vars: String = varsPath,
    ): String =
        "-drive file=$code,format=raw,readonly=on,if=pflash,unit=0 " +
            "-drive file=$vars,format=raw,if=pflash,unit=1"

    private fun md5Of(f: File): String? = runCatching {
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                d.update(buf, 0, n)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 文件在、大小对、md5 对。 */
    private fun ok(f: File, bytes: Long, md5: String): Boolean =
        f.isFile && f.length() == bytes && md5Of(f) == md5

    /** 只看状态，**不写任何东西**（启动检查用这个，够快）。 */
    fun state(): State {
        val dir = File(DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return State(denied = true)
        val c = File(codePath)
        val v = File(varsPath)
        return State(
            codeOk = c.isFile && c.length() == CODE_BYTES,
            varsOk = v.isFile && v.length() == VARS_BYTES,
            denied = !dir.canWrite(),
        )
    }

    /**
     * 确保公共目录里有一对完好的固件：缺哪个补哪个，大小/校验不对就重写。
     *
     * `assets/firmware/` 是唯一真源，所以这里只做"拷出去 + 复核"。
     * 返回 `null` 表示成功，非 `null` 是失败原因（供调用方转为可读文本）。
     */
    fun ensure(ctx: Context): String? {
        val dir = File(DIR)
        if (!dir.isDirectory) {
            if (!runCatching { dir.mkdirs() }.getOrDefault(false)) return "no-access"
        }
        if (!runCatching { dir.canWrite() }.getOrDefault(false)) return "no-access"

        val jobs = listOf(
            Triple(CODE_NAME, CODE_BYTES, CODE_MD5),
            Triple(VARS_NAME, VARS_BYTES, VARS_MD5),
        )
        for ((name, bytes, md5) in jobs) {
            val dst = File(dir, name)
            if (ok(dst, bytes, md5)) continue
            // 校验不过的先删掉，避免留半截文件骗过下次检查
            if (dst.exists()) runCatching { dst.delete() }
            val tmp = File(dir, "$name.part")
            val copyErr = runCatching {
                ctx.assets.open("firmware/$name").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }.exceptionOrNull()
            if (copyErr != null) {
                runCatching { tmp.delete() }
                return "asset-missing"
            }
            if (tmp.length() != bytes || md5Of(tmp) != md5) {
                runCatching { tmp.delete() }
                return "asset-bad"
            }
            if (!runCatching { tmp.renameTo(dst) }.getOrDefault(false)) {
                // rename 失败（跨分区/已存在）就退化成覆盖复制
                val okCopy = runCatching {
                    tmp.inputStream().use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                runCatching { tmp.delete() }
                if (!okCopy && !ok(dst, bytes, md5)) return "write-fail"
            }
        }
        val st = state()
        if (st.denied) return "no-access"
        if (!st.ready) return "write-fail"
        return null
    }

    /** 从公共目录删掉（设置页"清理"用，可选）。 */
    fun wipe(): Boolean = runCatching {
        File(codePath).delete() or !File(codePath).exists()
    }.getOrDefault(false)
}
