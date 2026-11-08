package org.icdqcow2.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * 「管理所有文件」权限 + SAF 目录选择。
 *
 * 为什么必须申请 MANAGE_EXTERNAL_STORAGE：
 *   镜像要**按真实路径写**（img.create 的 `out` 是宿主路径，不是 fd），
 *   而 `Android/data` 以外的目录（例如 `/sdcard/IcdImages`）在普通分区存储下
 *   只能通过 SAF 拿到 `content://` 权限，C 层拿不到真实路径。
 *   有了「所有文件访问」，`/storage/emulated/0/...` 与 SD 卡都能直接 File 写。
 *
 * SAF 只用来**挑文件夹**：`ACTION_OPEN_DOCUMENT_TREE` 返回树 URI，
 * 这里把 `primary:Images` 这种 documentId 还原成真实路径再交给 C 层。
 */
object StorageAccess {

    /** 是否已开启「所有文件访问」。 */
    fun hasAllFiles(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 跳到系统设置里的「所有文件访问」开关页。
     *
     * 三级回退（各家 ROM 改得五花八门，小米/传音都可能少某一级）：
     * 1. `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + `package:` —— 直达本应用的开关；
     * 2. `MANAGE_ALL_FILES_ACCESS_PERMISSION` —— 所有应用的列表；
     * 3. `APPLICATION_DETAILS_SETTINGS` —— 应用详情页。
     * @return 有没有成功拉起某个界面（false 时界面要提示手动路径）
     */
    fun requestAllFiles(ctx: Context): Boolean {
        val tries = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
        for (i in tries) {
            val ok = runCatching {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    /**
     * SAF 树 URI → 真实路径。
     *
     * documentId 形如 `primary:Images/win`（内置存储）或 `1A2B-3C4D:`（SD 卡根）。
     * 映射不出来（第三方 DocumentsProvider）返回 null——那种目录 C 层写不了。
     */
    fun treeToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        absoluteFromDocId(docId)?.let { return it }
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }.trim('/')

        val base = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            // 可移动卷的 ID 形如 1A2B-3C4D，真实路径是 /storage/<ID>
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /**
     * SAF 的**单个文件** URI（`ACTION_OPEN_DOCUMENT`）→ 真实路径。
     *
     * 和 [treeToPath] 同一套 documentId 解析，只是相对路径就是文件本身。
     * 第三方 provider（云端、虚拟目录）映射不出来 → null，那种文件 C 层打不开。
     */
    fun documentToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        absoluteFromDocId(docId)?.let { return it }
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }.trim('/')
        if (relative.isEmpty()) return null
        val base = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
            else -> return null
        }
        return "$base/$relative"
    }

    /**
     * 有的文件管理器（小米文件管理、部分第三方）把**绝对路径**塞进了 documentId：
     * `primary:/storage/emulated/0/镜像/x.qcow2`。
     *
     * 直接拼卷前缀会得到 `/storage/emulated/0/storage/emulated/0/镜像/x.qcow2` ——
     * 路径看着像真的、`File.exists()` 却是 false：历史条目被当成"文件没了"过滤掉、
     * 点击也报打不开。这里先认这种情况，原样返回。
     */
    private val MOUNTED_PATH = Regex(
        "^(storage/(emulated/\\d+|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})|mnt/(media_rw|user|sdcard)|sdcard)(/|$)",
    )

    private fun absoluteFromDocId(docId: String): String? {
        val rel = docId.substringAfter(':', "").trim('/')
        if (rel.isEmpty()) return null
        return if (MOUNTED_PATH.containsMatchIn(rel)) "/$rel" else null
    }

    /** SAF 文件的大小（provider 报的原值；拿不到返回 0）。 */
    fun querySize(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** SAF 文件的原名（provider 报的；拿不到返回 null）。 */
    fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    /**
     * 重启应用。
     *
     * 为什么需要：系统是在**进程启动时**决定「所有文件访问」的挂载范围的。
     * 用户在设置里刚打开这个开关时，正在跑的进程仍然是旧的受限挂载 ——
     * 表现就是"权限明明给了，还是 Permission denied / IMG-OPEN-FAIL"。
     * 重新拉一个 Activity + 杀掉本进程，新进程就拿到完整访问权。
     */
    fun restartApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            runCatching { ctx.startActivity(intent) }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * 用系统文件管理器**打开某个文件夹**（"当前操作的文件"卡片点它用）。
     *
     * 走 SAF 的 `externalstorage` 文档 uri：DocumentsUI / MIUI 文件管理都认这个形态。
     * `/storage/emulated/0/镜像` → `content://com.android.externalstorage.documents/document/primary%3A镜像`
     *
     * 返回 false 的两种情况界面要分开说：
     * - 路径在应用私有目录（`/data/...`）：文件管理器根本进不去（`filePrivateDir`）；
     * - 系统里没有应用能响应（`fileOpenFailed`）。
     */
    fun openFolder(ctx: Context, path: String): Boolean {
        val dir = if (File(path).isDirectory) path else (File(path).parent ?: return false)
        val volume: String
        val rel: String
        when {
            dir == "/storage/emulated/0" || dir.startsWith("/storage/emulated/0/") -> {
                volume = "primary"
                rel = dir.removePrefix("/storage/emulated/0").trim('/')
            }
            dir == "/sdcard" || dir.startsWith("/sdcard/") -> {
                volume = "primary"
                rel = dir.removePrefix("/sdcard").trim('/')
            }
            // SD 卡 / U 盘：/storage/1234-ABCD/xxx
            Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(/.*)?$").matches(dir) -> {
                volume = dir.removePrefix("/storage/").substringBefore('/')
                rel = dir.removePrefix("/storage/$volume").trim('/')
            }
            else -> return false
        }
        val docId = if (rel.isEmpty()) "$volume:" else "$volume:$rel"
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/" + Uri.encode(docId),
        )
        // 三种 type 依次试：`resource/folder` 是文件管理器通用的，`vnd.android.document/directory`
        // 是 DocumentsUI 的；都不认再裸 uri 试一次。
        val mimes = listOf("resource/folder", DocumentsContract.Document.MIME_TYPE_DIR, null)
        // 先点名常见文件管理器（避免弹"选择应用"让用户每次挑），再退回系统任意能接的。
        val known = listOf(
            "com.mi.android.globalFileexplorer",  // MIUI 文件管理
            "com.android.documentsui",            // AOSP 文件
            "com.google.android.documentsui",
            "com.android.fileexplorer",
        )
        for (mime in mimes) {
            for (pkg in known) {
                val intent = (if (mime == null) Intent(Intent.ACTION_VIEW).setData(uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime))
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (intent.resolveActivity(ctx.packageManager) != null &&
                    runCatching { ctx.startActivity(intent) }.isSuccess
                ) return true
            }
        }
        for (mime in mimes) {
            val intent = if (mime == null) Intent(Intent.ACTION_VIEW).setData(uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
            }
        }
        return false
    }

    /**
     * 某个路径所在卷的**剩余空间**（字节）。
     *
     * 释放镜像前要用它校验"手机装不装得下"：qcow2 会随着写入长大。
     * 路径不存在就往上找存在的父目录，全都不存在返回 0（界面当"未知"处理）。
     */
    fun freeBytes(path: String?): Long {
        var f = path?.let { File(it) } ?: return 0L
        while (!f.exists() && f.parentFile != null) f = f.parentFile!!
        return runCatching { android.os.StatFs(f.absolutePath).availableBytes }.getOrDefault(0L)
    }

    /** 目录能不能真的写进去（建目录 + 落一个探针文件）。 */
    fun canWrite(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".write-probe")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)
}