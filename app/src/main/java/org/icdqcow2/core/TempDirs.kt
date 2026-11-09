package org.icdqcow2.core

import android.content.Context
import java.io.File

/**
 * 工具端临时文件目录的**唯一入口**。
 *
 * 为什么要统一（2026-11-09 定）：
 *   以前让工具端按"容器所在目录"自动兜底，问题有三：
 *     ① App 崩了，临时文件烂在用户盘里没人清；
 *     ② 目标是只读路径（ISO / 只读挂载 / 别的应用目录）时建不出目录，命令直接失败；
 *     ③ 临时文件散落在用户可见的盘上，属于污染。
 *   所以：**所有需要临时写入的命令，一律用 App 自己的缓存目录**，
 *   并在每次启动时清掉上次残留。
 *
 * 目录结构：
 *   cacheDir/tooltmp/          ← 统一根目录，传给 imgapp_set_tmpdir
 *   cacheDir/tooltmp/staging/  ← App 自己的中转区（与工具无关）
 */
object TempDirs {

    /** 工具端临时目录（传给 `imgapp_set_tmpdir`）。 */
    fun toolDir(ctx: Context): File = File(ctx.cacheDir, "tooltmp").apply { mkdirs() }

    /** App 侧中转目录（打包前取只读容器里的文件、抽 boot.sdi 等）。 */
    fun stagingDir(ctx: Context): File = File(toolDir(ctx), "staging").apply { mkdirs() }

    /**
     * 启动时清扫。
     *
     * 只在 App 刚起来时调用：这时不可能有命令在跑，整目录删掉最干净。
     * 返回删掉的文件数（用于日志）。
     */
    fun sweep(ctx: Context): Int {
        val root = File(ctx.cacheDir, "tooltmp")
        if (!root.exists()) {
            root.mkdirs()
            return 0
        }
        var n = 0
        runCatching {
            root.walkTopDown().forEach { if (it.isFile) n++ }
        }
        runCatching { root.deleteRecursively() }
        root.mkdirs()
        return n
    }

    /**
     * 单次命令用的子目录（用完由调用方删）。
     *
     * 不用固定名：连发两条命令时固定名会互相覆盖。
     */
    fun scratch(ctx: Context, tag: String): File =
        File(toolDir(ctx), tag + "-" + System.nanoTime()).apply { mkdirs() }
}
