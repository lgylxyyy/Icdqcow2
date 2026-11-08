package org.icdqcow2.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道（借 shell 身份执行命令）。
 *
 * 用途：`/storage/emulated`、`/data`、别的 App 私有目录这些普通应用**DAC/SELinux 都进不去**
 * 的位置——MT / NP 管理器的做法也一样，要么 root，要么借 ADB 授权（Shizuku）。
 *
 * ★ 只在**读**上用：列目录、`stat`。写操作（rm/mv）也走 shell 会更危险，
 *   等文件页的写路径稳定了再逐步放开。
 * ★ 没装 / 没启动 Shizuku 时全部返回 null，绝不阻塞正常路径。
 *
 * ★ 教训：**一定要看退出码**。`exec` 原来只取 stdout，命令不存在/参数不支持时
 *   stdout 是空字符串，上层解析出来就是"这个目录是空的"——查了很久才发现是
 *   toybox 的 `ls` 不支持 `--time-style=+%s`。
 */
object ShizukuShell {

    /** 一条命令的结果（stdout / stderr / 退出码）。 */
    class Result(val out: String, val err: String, val code: Int) {
        val ok: Boolean get() = code == 0
    }

    /** 服务在跑（有 binder）。 */
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 已经授权过。 */
    fun granted(): Boolean = available() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 弹授权（Shizuku 自己的界面）。 */
    fun request(code: Int = 1001) {
        runCatching { Shizuku.requestPermission(code) }
    }

    /** 跑一条 shell 命令，返回 stdout/stderr/退出码；没授权或通道起不来返回 null。 */
    fun execResult(cmd: String): Result? {
        if (!granted()) return null
        return runCatching {
            // api 13.1.5 里 `Shizuku.newProcess` 是 private，只能反射拿
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            // stderr 要单独读，否则写满缓冲区会把进程卡死
            val err = StringBuilder()
            val t = kotlin.concurrent.thread(isDaemon = true) {
                runCatching { proc.errorStream.bufferedReader().use { err.append(it.readText()) } }
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            t.join(400)
            android.util.Log.i(
                "IcdqcowShizuku",
                "code=$code cmd=${cmd.replace("\n", " ").take(140)} " +
                    "out=${out.take(200).replace("\n", "\\n")} " +
                    "err=${err.toString().take(200).replace("\n", "\\n")}",
            )
            Result(out, err.toString(), code)
        }.onFailure {
            android.util.Log.w("IcdqcowShizuku", "exec failed: ${it.message}")
        }.getOrNull()
    }

    /** 只取 stdout，且要求退出码为 0。 */
    fun exec(cmd: String): String? = execResult(cmd)?.takeIf { it.ok }?.out

    /** 把路径包成 shell 里安全的一小段（单引号转义）。 */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}