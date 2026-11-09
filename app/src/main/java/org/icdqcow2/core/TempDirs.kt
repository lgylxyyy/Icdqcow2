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
 *   filesDir/tooltmp/          ← 统一根目录，传给 imgapp_set_tmpdir
 *   filesDir/tooltmp/staging/  ← App 自己的中转区（与工具无关）
 *
 * 为什么用 filesDir 而不是 cacheDir：系统会在内部存储紧张时**自动清空 cacheDir**，
 * 命令写到一半被清就是数据事故；filesDir 不会被自动清，残留由本对象在启动时清扫。
 */
object TempDirs {

    /** 工具端临时目录（传给 `imgapp_set_tmpdir`）。 */
    fun toolDir(ctx: Context): File = File(root(ctx), "tooltmp").apply { mkdirs() }

    private fun root(ctx: Context): File = ctx.filesDir

    /** App 侧中转目录（打包前取只读容器里的文件、抽 boot.sdi 等）。 */
    fun stagingDir(ctx: Context): File = File(toolDir(ctx), "staging").apply { mkdirs() }

    /**
     * 启动时清扫。
     *
     * 只在 App 刚起来时调用：这时不可能有命令在跑，整目录删掉最干净。
     * 返回删掉的文件数（用于日志）。
     */
    fun sweep(ctx: Context): Int {
        var n = 0
        // ① 当前根：整目录删掉重建（此刻不可能有命令在跑）
        val dir = File(root(ctx), "tooltmp")
        if (dir.exists()) {
            runCatching { dir.walkTopDown().forEach { if (it.isFile) n++ } }
            runCatching { dir.deleteRecursively() }
        }
        dir.mkdirs()
        // ② 升级遗留：早期 tmpdir 直接设成 cacheDir，工具端占位文件（imgtool-dev-<pid>）、
        //    App 的 pt-*.json，以及各页面自建的中转子目录（preview/pack/fcwinre/…）
        //    都落在 cacheDir 根上。新根管不到它们，而其中 pack/preview 可能攒几百 MB——
        //    不删就是升级后白占用户空间。这里按名字逐个清（只清自己建过的名字）。
        runCatching {
            val legacyDirs = setOf(
                "preview", "pack", "fcwinre", "fcpt", "brre", "brfb", "wr", "saveto",
                // 上一版把 tooltmp 建在 cacheDir 下，改到 filesDir 后那个空壳同样收走
                "tooltmp",
            )
            ctx.cacheDir.listFiles()?.forEach { f ->
                val mine = f.name.startsWith("imgtool-") ||
                    f.name == "pt-import.json" || f.name == "pt-export.json" ||
                    f.name == "deploy-boot.sdi" ||
                    (f.isDirectory && f.name in legacyDirs)
                if (mine) {
                    runCatching { f.deleteRecursively() }.onSuccess { n++ }
                }
            }
        }
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
