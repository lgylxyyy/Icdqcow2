package org.icdqcow2.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 伙伴应用联动闸门。
 *
 * 背景：有合作方要求「本方应用与其付费功能共存时，不提供镜像写入类能力」。
 * 这里只做**能力降级**，不收集、不上传任何数据，也不申请 QUERY_ALL_PACKAGES ——
 * 名单里点名的包写进了 AndroidManifest 的 `<queries>`，Android 11+ 才查得到。
 *
 * 两层来源：
 *   ① 编译期名单（[PartnerList.PACKAGES]，来自仓库根 partners.json）
 *   ② 运行期签名标记（[PartnerMarker]，协议见 docs/partner-marker.md）
 *      当前**置空**：[MARKERS_ENABLED] = false，协议与代码都在，等伙伴接入后再打开。
 *
 * 闸门位置：命令层唯一入口 [ToolRunner.exec]，不是 UI —— 藏按钮能被别的页面绕过，
 * 命令层绕不过去。
 */
object PartnerGate {

    /** 方案二开关。打开前必须先把伙伴公钥 pin 进 [PartnerMarker.TRUSTED_KEYS]。 */
    const val MARKERS_ENABLED = false

    private val _active = MutableStateFlow<List<String>>(emptyList())

    /** 当前已安装且命中的伙伴包名；空表示不受限。 */
    val active: StateFlow<List<String>> = _active.asStateFlow()

    val restricted: Boolean get() = _active.value.isNotEmpty()

    @Volatile
    private var appCtx: Context? = null

    /** 启动时调一次；之后 `onResume` 再调 [refresh]（用户可能在后台装了/卸了）。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        refresh()
    }

    fun refresh() {
        val ctx = appCtx ?: return
        val hits = PartnerList.PACKAGES.filter { installed(ctx, it) }.toMutableList()
        if (MARKERS_ENABLED) {
            hits += PartnerMarker.scan(ctx)
        }
        _active.value = hits.distinct()
        // 真机调试用：adb logcat -s IcdqcowPartner
        runCatching {
            android.util.Log.i("IcdqcowPartner", "hits=" + _active.value.joinToString(","))
        }
    }

    private fun installed(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * 命中伙伴时这条命令是否要拒绝。返回错误码（`PARTNER-LOCK`）或 null。
     *
     * 只拦「写镜像 / 改分区 / 铺引导 / 释放映像」；只读命令一律放行 ——
     * 用户仍然可以浏览镜像、看分区、导出文件，只是不能生成新的可启动镜像。
     */
    fun deny(cmd: String): String? =
        if (restricted && cmd in BLOCKED_WRITE) CODE else null

    const val CODE = "PARTNER-LOCK"

    /** 写入类命令白名单式拦截：凡会改动镜像或目标磁盘的都算。 */
    private val BLOCKED_WRITE = setOf(
        // 建盘 / 克隆 / 压缩
        "img.create", "img.clone", "img.compact",
        // 分区表
        "part.add", "part.remove", "part.resize", "part.apply", "part.attr",
        "part.mbrboot", "part.copy",
        // 文件系统
        "fs.format", "fat.mkdir", "ntfs.mkdir", "fat.put", "ntfs.put",
        "fat.touch", "ntfs.touch", "fat.label", "ntfs.label",
        "copy", "move", "rename", "rm", "archive.create",
        // 引导 / 部署 / 恢复
        "bcd.build", "bcd.create", "bcd.gen-uefi",
        "boot.populate", "boot.repair", "boot.tpl-apply",
        "wim.apply", "wim.capture", "win.finish",
        "recovery.populate", "recovery.populate-system",
        // 注册表写入
        "reg.set", "reg.del",
    )

    /**
     * 命中伙伴时**不允许进入**的页面路由。这些都是"会写镜像"的流程；
     * 只读页（文件、自检、导出、注册表、控制台）不受影响。
     */
    val BLOCKED_ROUTES = setOf(
        // 主页上的全部入口
        "setup",      // 全流程装机
        "create",     // 创建磁盘
        "parts",      // 分区管理
        "ptexport",   // 导出分区表
        "clone",      // 克隆映像
        "deploy",     // 释放映像
        "bootrepair", // 引导修复
        "winre",      // 写入 WinRE
        "verify",     // 全量自检
        // 主页之外、同样会写镜像的入口
        "capture",    // 捕获映像
        "format",     // 格式化
        "import",     // 导入（写目标盘）
    )

    /** 只读命令（显式列出，便于对照；不在 [BLOCKED_WRITE] 里就默认放行）。 */
    val READ_ONLY = setOf(
        "cmd.list", "sys.tmpdir", "ctl.cancel",
        "img.info", "du", "qc.verify",
        "part.scan", "part.show", "part.export", "part.layout",
        "fat.ls", "fat.stat", "fat.exists", "fat.export",
        "ntfs.ls", "ntfs.stat", "ntfs.exists", "ntfs.export", "ntfs.parent",
        "wim.ls", "wim.identify", "wim.file-version", "wim.size",
        "iso.ls", "iso.probe", "archive.ls", "archive.extract",
        "boot.check", "boot.plan", "boot.tpl-export", "boot.tpl-inspect",
        "recovery.status", "win.version", "winre.find", "winre.auto",
        "reg.list", "reg.get",
    )
}
