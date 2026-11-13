package org.icdqcow2.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 镜像仓库：当前镜像 + 建盘 / 读信息。
 *
 * 保存位置两档（用户可见的说法）：
 * - **默认路径** `/sdcard/Documents/Icdqcow2/Images`：公共目录，文件管理器里能直接看到
 *   （2026-11-11 改：以前是应用私有 `files/images`，用户拿不到自己的盘）；
 * - **自定义路径**：用户用 SAF 挑的任意目录（`/sdcard/xxx`、SD 卡…），
 *   也一样**需要「所有文件访问」权限**（C 层要真实路径）。
 *
 * 升级时 `migrateLegacyDirs()` 会把旧的私有 `files/images` 搬过来并删掉那个空目录。
 *
 * 当前镜像路径与保存位置都持久化在 SharedPreferences，杀进程后还能接上。
 */
object ImageRepo {

    /** 认得的表文件版本上限（工具端加版本时一起抬）。 */
    private const val TABLE_FILE_V = 1

    /** 保存位置：默认（应用私有）或自定义（用户挑的目录）。 */
    enum class SaveMode { Default, Custom }

    private lateinit var appCtx: Context
    private val sp by lazy { appCtx.getSharedPreferences("images", Context.MODE_PRIVATE) }

    /**
     * 用 SAF 打开的那个文件的 fd。
     *
     * 为什么需要：别的应用（虚拟机、下载器）在 `/Documents` 里建的镜像可能是它们的 uid + 0660，
     * 光靠「管理所有文件」在某些 ROM/版本上仍然 `Permission denied`。传给工具端 fd 就绕开了路径权限
     * （手册：只读命令走 SAF fd 可用）。fd >= 0 时**路径传 null**。
     *
     * ★ 2026-11-12（批次 A）：**只读 fd 不再顶掉真实路径**。
     *   以前 `openFd` 拿 "rw" 失败就退 "r"，而退到的那个只读 fd 也照样塞进来 →
     *   [pathForTool] 把真实路径丢掉、工具端拿着只读 fd 去写 →
     *   报「映像是只读打开的」；`img.info` 也因此算不出宿主占用（显示 "—"）。
     *   现在分成两个槽：
     *   · [pickedFd] = 真正可写（"rw" 成功）→ 顶掉路径，写操作走它；
     *   · [readonlyFd] = 只读（只拿到 "r"）→ **只给读操作兜底**，并把
     *     [imageReadonly] 置真，界面上提示「此文件只读」。
     *   两个都没有时一律走真实路径。
     */
    private var pickedFd: android.os.ParcelFileDescriptor? = null

    /** SAF 只读 fd（"rw" 失败、只拿到 "r"）：只给读操作兜底，不顶掉路径。 */
    private var readonlyFd: android.os.ParcelFileDescriptor? = null

    private val _imageReadonly = MutableStateFlow(false)

    /** 当前映像是不是只读打开的（SAF 只拿到 "r"）。写功能的页面据此提示用户。 */
    val imageReadonly: StateFlow<Boolean> = _imageReadonly.asStateFlow()

    /**
     * 记下 SAF 打开的 fd（会关掉上一个）。
     *
     * @param writable true = "rw" 打开成功（可写）；false = 只拿到 "r"（只读）。
     * @param pathKnown 这个文件**有没有真实路径**可以用来写。
     *
     * ★ 2026-11-13（用户报「自己建的盘也提示只读」）：只读 fd **不再等于**「映像只读」。
     *   只要拿得到真实路径，写操作就走路径（MANAGE_EXTERNAL_STORAGE 在通常能写），
     *   灰字提示（[imageReadonly]）只在「既没有可写 fd、也没有真实路径」时才亮 ——
     *   那才是真的没有任何写路子。有路径时写失败，由工具端返回的错误说明原因。
     */
    fun setPickedFd(pfd: android.os.ParcelFileDescriptor?, writable: Boolean = true, pathKnown: Boolean = true) {
        runCatching { pickedFd?.close() }
        runCatching { readonlyFd?.close() }
        pickedFd = null
        readonlyFd = null
        if (pfd != null) {
            if (writable) pickedFd = pfd else readonlyFd = pfd
        }
        _imageReadonly.value = readonlyFd != null && !pathKnown
    }

    /** 当前有没有**可写**的 SAF fd（写操作的资格）。 */
    fun hasFd(): Boolean = pickedFd != null

    /** 当前有没有 SAF fd（可写或只读）—— 读操作用这个。 */
    fun hasAnyFd(): Boolean = pickedFd != null || readonlyFd != null

    private fun fdValue(): Int = pickedFd?.fd ?: -1

    /** 读操作用哪个 fd：优先可写的，没有才用只读的。 */
    private fun anyFdValue(): Int = pickedFd?.fd ?: readonlyFd?.fd ?: -1

    /**
     * 有**可写** fd 就把路径让位（工具端要求二选一）；只读 fd **不让位** ——
     * 真实路径能写就写路径，写不了再由上层报「只读」。
     */
    private fun pathForTool(path: String?): String? = if (pickedFd != null) null else path

    /**
     * 读操作的目标：(路径, fd) 二选一。
     *
     * 优先级：可写 fd（fd 取代路径）→ 真实路径 → 只读 fd 兜底。
     * 只读 fd 永远不顶掉真实路径 —— 那正是「占用显示不出来」的根因。
     */
    private fun readTarget(path: String?): Pair<String?, Int> = when {
        pickedFd != null -> null to fdValue()
        path != null -> path to -1
        else -> null to anyFdValue()
    }

    private val _current = MutableStateFlow<String?>(null)

    /** 当前镜像的绝对路径；null = 还没选。 */
    val current: StateFlow<String?> = _current.asStateFlow()

    private val _mode = MutableStateFlow(SaveMode.Default)

    /** 新建的镜像放默认目录还是自定义目录？ */
    val mode: StateFlow<SaveMode> = _mode.asStateFlow()

    private val _customDir = MutableStateFlow<String?>(null)

    /** 用户挑的自定义目录（绝对路径）；null = 还没挑过。 */
    val customDir: StateFlow<String?> = _customDir.asStateFlow()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        _mode.value = runCatching {
            SaveMode.valueOf(sp.getString("save_mode", null) ?: "")
        }.getOrDefault(SaveMode.Default)
        _customDir.value = sp.getString("custom_dir", null)

        // 老版本只有 external 开关（Android/data/.../Documents/images）：迁移成"自定义路径"，
        // 别让用户升级后发现新建的盘跑到应用私有目录去了。
        if (!sp.contains("save_mode") && sp.getBoolean("external", false)) {
            val old = File(appCtx.getExternalFilesDir("Documents"), "images")
            if (old.isDirectory) {
                _customDir.value = old.absolutePath
                _mode.value = SaveMode.Custom
                sp.edit().putString("custom_dir", old.absolutePath).putString("save_mode", SaveMode.Custom.name).apply()
            }
        }

        _current.value = sp.getString("current", null)?.takeIf { File(it).exists() }
    }

    fun setMode(m: SaveMode) {
        _mode.value = m
        sp.edit().putString("save_mode", m.name).apply()
    }

    fun setCustomDir(path: String?) {
        _customDir.value = path
        sp.edit().putString("custom_dir", path).apply()
        if (path != null) setMode(SaveMode.Custom)
    }

    fun setCurrent(path: String?) {
        _current.value = path
        sp.edit().putString("current", path).apply()
    }

    /**
     * 默认目录：`/storage/emulated/0/Documents/Icdqcow2/Images`（用户 2026-11-11 定）。
     *
     * 以前是应用私有 `files/images` —— 文件管理器进不去，用户拿不到自己的盘。
     * 现在放公共 Documents 下（和 `Firmware` 目录并排），**不再创建**私有 `images` 目录。
     * 没有「所有文件访问」时 `mkdirs` 会失败，那是权限问题，界面上会提示，不再偷偷退回私有目录。
     */
    fun defaultDir(): File = publicImagesDir().apply { if (!isDirectory) mkdirs() }

    /** 公共镜像目录对象（不保证已存在）。 */
    fun publicImagesDir(): File = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
        "Icdqcow2/Images",
    )

    /**
     * 把**旧默认目录**里的镜像搬到新的默认目录（幂等，启动时跑一次就够）。
     *
     * 旧位置有两处：
     * 1. 应用私有 `files/images`（2026-10 之前的默认）；
     * 2. 更早的 `Android/data/<pkg>/files/Documents/images`（`external` 开关时代）。
     *
     * 规则：同名不覆盖（让用户自己决定）；跨文件系统 rename 失败就 copy + 删源；
     * 只有旧目录**空了**才删它 —— 没搬成功的东西留着，宁可留个目录也不丢文件。
     */
    fun migrateLegacyDirs() {
        val dst = if (_mode.value == SaveMode.Custom) imagesDir() else defaultDir()
        if (!dst.isDirectory) return
        val olds = listOf(
            File(appCtx.filesDir, "images"),
            File(appCtx.getExternalFilesDir("Documents"), "images"),
        )
        for (old in olds) {
            if (!old.isDirectory) continue
            if (runCatching { old.canonicalPath == dst.canonicalPath }.getOrDefault(false)) continue
            for (f in old.listFiles().orEmpty()) {
                if (!f.isFile) continue
                val to = File(dst, f.name)
                if (to.exists()) continue
                val moved = runCatching {
                    if (f.renameTo(to)) {
                        true
                    } else {
                        f.copyTo(to, overwrite = false)
                        f.delete()
                        true
                    }
                }.getOrDefault(false)
                if (!moved) runCatching { to.delete() }
            }
            runCatching { if (old.listFiles().orEmpty().isEmpty()) old.delete() }
        }
        // 当前镜像正好落在旧目录里：跟着搬到新位置，别让用户"刚升级盘就没了"
        val cur = _current.value
        if (cur != null && !File(cur).exists()) {
            val cand = File(dst, File(cur).name)
            if (cand.exists()) setCurrent(cand.absolutePath)
        }
    }

    /** 当前生效目录；自定义目录不可用（被删/没权限）时回落到默认目录。 */
    fun imagesDir(): File {
        val custom = _customDir.value
        if (_mode.value == SaveMode.Custom && !custom.isNullOrBlank()) {
            val f = File(custom)
            if (f.isDirectory || f.mkdirs()) return f
        }
        return defaultDir()
    }

    /** 镜像的显示名（不带目录和扩展名）。 */
    fun displayName(path: String?): String? =
        path?.let { File(it).name.removeSuffix(".qcow2") }

    /**
     * 按魔数判磁盘格式，返回后缀名：`qcow2` / `vhd` / `vhdx` / `vmdk` / `wim` / `iso`。
     *
     * 只读文件头尾各 512 字节，不进工具、不解码。判不出返回 null（raw 与任意文件无法区分，
     * 这里**不猜**）。Vectras VM 这类启动器把映像存成无后缀的名字，
     * 页面上不补后缀就看不出是什么格式，用它补。
     */
    fun diskSuffix(path: String?): String? {
        if (path == null || path.startsWith("content://")) return null
        val f = File(path)
        if (!f.isFile) return null
        fun ascii(b: ByteArray, i: Int, txt: String): Boolean {
            if (i + txt.length > b.size) return false
            for (k in txt.indices) if ((b[i + k].toInt() and 0xFF) != txt[k].code) return false
            return true
        }
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(512)
                val hn = raf.read(head)
                val h = if (hn > 0) head.copyOf(hn) else ByteArray(0)
                val magic = when {
                    h.size >= 4 && (h[0].toInt() and 0xFF) == 0x51 && (h[1].toInt() and 0xFF) == 0x46 &&
                        (h[2].toInt() and 0xFF) == 0x49 && (h[3].toInt() and 0xFF) == 0xFB -> "qcow2"
                    ascii(h, 0, "KDMV") -> "vmdk"
                    ascii(h, 0, "vhdxfile") -> "vhdx"
                    ascii(h, 0, "conectix") -> "vhd"
                    ascii(h, 0, "MSWIM") -> "wim"
                    else -> null
                }
                if (magic != null) return@use magic
                // 固定 VHD 的 footer 在末尾 512 字节
                if (raf.length() >= 512) {
                    raf.seek(raf.length() - 512)
                    val tail = ByteArray(512)
                    if (raf.read(tail) == 512 && ascii(tail, 0, "conectix")) return@use "vhd"
                }
                // ISO 的卷描述符在 32 KiB 处（0x8000 + 1）
                if (raf.length() >= 32774) {
                    raf.seek(32769)
                    val d = ByteArray(5)
                    if (raf.read(d) == 5 && ascii(d, 0, "CD001")) return@use "iso"
                }
                null
            }
        }.getOrNull()
    }

    // ── 命令 ──

    /** 建盘结果：`extrasDropped` = 工具端不接受额外分区参数、已退回只建必要分区。 */
    data class CreateOutcome(
        val result: ToolRunner.Result,
        val extrasDropped: Boolean = false,
        /** 老 so 不认 `mbr-fill=auto`，已退回不传（调用方要自己补建容器）。 */
        val mbrFillDropped: Boolean = false,
    )

    /**
     * 建 qcow2 + 分区表。
     *
     * ★ 参数叫 `out` 不是 `img`：叫 img 会被当成"已存在的镜像"去打开。
     * ★ `table` 支持 gpt / mbr / none（none = 空盘，只建 qcow2 头，不写任何分区表）。
     * ★ `layout` 支持 windows（ESP+MSR+Windows）/ single（整盘一个分区）/ empty（有表无分区）。
     * ★ 必要分区参数（esp-mb / msr-mb / rec-mb / sys-mb）直接透传给 img.create。
     * ★ 额外分区用 `extra-mb=2048,4096`（逗号分隔 MiB）追加；工具端还没接这条参数时，
     *   会收到 `CMD-ARG-BAD`（at=extra-mb），这里自动退回"只建必要分区"并把
     *   `extrasDropped` 报给界面，绝不让整盘创建陪葬。
     */
    suspend fun create(
        name: String,
        sizeGb: Int,
        table: String,
        layout: String = "windows",
        dir: File = imagesDir(),
        extra: Map<String, String> = emptyMap(),
        extrasMb: List<Int> = emptyList(),
        /** `mbr-fill`：MBR 想要"3 主 + 容器 + 逻辑卷"就传 `auto`（REQ-08）。 */
        mbrFill: String? = null,
        /** 容器格式：qcow2（默认）/ raw / vmdk。VHD 建盘不支持（REQ-60 §2.4）。 */
        format: String = "qcow2",
    ): CreateOutcome {
        val path = File(dir, "$name.$format").absolutePath
        val base = buildMap {
            put("out", path)
            put("size-gb", sizeGb.toString())
            put("table", table)
            put("layout", layout)
            if (format != "qcow2") put("format", format)
            extra.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
        }

        suspend fun run(extras: List<Int>, fill: String?): ToolRunner.Result {
            val args = buildMap {
                putAll(base)
                if (extras.isNotEmpty()) put("extra-mb", extras.joinToString(","))
                if (fill != null) put("mbr-fill", fill)
            }
            return ToolRunner.exec(cmd = "img.create", args = args, writable = true)
        }

        var r = run(extrasMb, mbrFill)
        var extrasDropped = false
        var fillDropped = false
        // 老 so 不认识 extra-mb：退回只建必要分区（不要让整盘陪葬）
        if (!r.ok && r.code == "CMD-ARG-BAD" && r.at == "extra-mb" && extrasMb.isNotEmpty()) {
            extrasDropped = true
            r = run(emptyList(), mbrFill)
        }
        // 老 so 不认识 mbr-fill=auto：退回不传（调用方自己追加容器 + 逻辑卷）
        if (!r.ok && r.code == "CMD-ARG-BAD" && r.at == "mbr-fill" && mbrFill != null) {
            fillDropped = true
            r = run(if (extrasDropped) emptyList() else extrasMb, null)
        }
        if (r.ok) {
            // 新盘是纯路径写出来的：把之前 SAF 打开的那个 fd 丢掉，
            // 否则后面每一条命令都会打到**上一个**镜像上（fd 优先于路径）
            setPickedFd(null)
            setCurrent(path)
            // 新盘直接进"最近使用"
            ImageHistory.record(path, null, "$name.qcow2", runCatching { File(path).length() }.getOrDefault(0L))
        }
        return CreateOutcome(r, extrasDropped, fillDropped)
    }

    /**
     * 统一出口：有 SAF fd 就传 fd（路径传 null），否则传路径。
     *
     * 写操作以前直接传 `imagePath`，遇到"别的应用的文件"就 `IMG-OPEN-FAIL`；
     * 现在读写都走这里，fd 优先。
     */
    suspend fun exec(
        cmd: String,
        args: Map<String, String> = emptyMap(),
        path: String? = _current.value,
        writable: Boolean = false,
    ): ToolRunner.Result {
        // 写操作但既没有可写 fd、又拿不到真实路径：
        // · 有只读 fd → 明确报「只读」（别让工具端报一堆 open fail，用户看不懂）；
        // · 连只读 fd 都没有 → 调用点应该已经挡了，这里兜底报「还没有选择镜像」。
        if (writable && pickedFd == null && path == null) {
            return if (readonlyFd != null) readonlyImage() else missing()
        }
        // ★ 2026-11-13：**删掉了「路径 canWrite 预检查」**（用户报「自己建的盘也说只读」）。
        //   Android 的 FUSE 上 `File.canWrite()` 走 access(W_OK)，和真实写入权限
        //   不一致（appops 给了 MANAGE_EXTERNAL_STORAGE 时实际能写，access 仍可能 false）
        //   → 拿它当拦截依据会误伤自己建的文件。能不能写，交给工具端去试，它报的错更准。
        android.util.Log.i(
            "IcdqcowPick",
            "exec $cmd writable=$writable path=${path ?: "(null)"} " +
                "pickedFd=${pickedFd != null} readonlyFd=${readonlyFd != null} " +
                "canWrite=${path?.let { runCatching { java.io.File(it).canWrite() }.getOrNull() }}",
        )
        val effPath = pathForTool(path)
        return ToolRunner.exec(
            cmd = cmd,
            args = args,
            imagePath = effPath,
            writable = writable,
            // 写操作只认可写 fd：把只读 fd 塞进写命令必然失败，不如提前拦。
            imageFd = if (effPath != null) -1 else if (writable) fdValue() else anyFdValue(),
        )
    }

    suspend fun info(path: String? = _current.value): ToolRunner.Result {
        if (path == null && !hasAnyFd()) return missing()
        val (p, fd) = readTarget(path)
        return ToolRunner.exec("img.info", imagePath = p, imageFd = fd)
    }

    /**
     * 就地清理（`img.trim`，REQ-20261110-66）。
     *
     * 按分区空闲位图，把 guest 已经删掉、qcow2 里却仍占着的簇还回去：
     * 清 L2 entry + refcount 减到 0；`punch` 打开时再对宿主簇打洞，文件当场变小
     * （存储不支持打洞时只释放块，文件大小不变 —— 那时才需要 `rebuild` 另存一份）。
     *
     * `dryRun=true` = 只算不写，返回 `guest_bytes_freed` / `clusters_freed`，App 拿它给用户看数。
     */
    suspend fun trim(
        img: String,
        dryRun: Boolean = false,
        punch: Boolean = true,
        rebuild: Boolean = false,
        out: String? = null,
    ): ToolRunner.Result = exec(
        "img.trim",
        args = buildMap {
            put("dry-run", dryRun.toString())
            if (!dryRun) {
                put("punch", punch.toString())
                if (rebuild) {
                    put("rebuild", "true")
                    if (out != null) put("out", out)
                }
            }
        },
        path = img,
        writable = !dryRun,
    )

    /**
     * 另存紧凑镜像（`img.compact`，REPLY-06）。
     *
     * ★ 和 `img.clone` 完全不同：这是**新写一个文件**，源盘只读、不会被改也不会被删。
     * ★ `keep` 里的分区会拷数据，**没列进去的分区表还在、数据留空**（不是删分区）。
     * ★ `guid` 默认 `keep`（保住 BCD 里的 GUID），不要传 random。
     */
    suspend fun compact(
        src: String,
        out: String,
        keep: List<Long> = emptyList(),
        verify: Boolean = true,
        force: Boolean = false,
        /** `zlib` = 压缩（T6）；`off` 或不传 = 紧凑化。只有工具库带 `--compress` 时才传。 */
        compress: String? = null,
    ): ToolRunner.Result {
        val args = buildMap {
            put("src", src)
            put("out", out)
            put("verify", verify.toString())
            put("force", force.toString())
            if (keep.isNotEmpty()) put("keep", keep.joinToString(","))
            if (compress != null) put("compress", compress)
        }
        return ToolRunner.exec("img.compact", args = args, writable = true)
    }

    /**
     * 原地回收未使用的块（`img.sparsify`，T7）。
     *
     * 和 [compact] 不是一件事：这里**不换文件**，只是把客户机已经不用的块还给宿主。
     * `mode`：`fs` = 按分区空闲位图（NTFS/FAT32）；`zero` = 按内容全零；`all` = 先 fs 再 zero。
     * `dryRun=true` 只统计（返回 `would_free_bytes`），一个字节都不改。
     */
    suspend fun sparsify(
        img: String,
        mode: String = "all",
        dryRun: Boolean = false,
    ): ToolRunner.Result = exec(
        "img.sparsify",
        args = buildMap {
            put("mode", mode)
            put("dry-run", dryRun.toString())
        },
        path = img,
        writable = !dryRun,
    )

    /**
     * 只改卷标（`ntfs.label` / `fat.label`，REPLY-06）。不碰文件、不动用量。
     *
     * `label = null` = 清卷标（只有 NTFS 允许；FAT32 要用 `NO NAME`）。
     */
    suspend fun label(
        lba: Long,
        fs: String,
        label: String?,
        path: String? = _current.value,
    ): ToolRunner.Result {
        if (path == null) return missing()
        val cmd = if (fs.equals("FAT32", true)) "fat.label" else "ntfs.label"
        val args = buildMap {
            put("lba", lba.toString())
            put("label", label ?: "")
        }
        return exec(cmd, args = args, path = path, writable = true)
    }

    suspend fun parts(path: String? = _current.value): ToolRunner.Result {
        if (path == null && !hasAnyFd()) return missing()
        val (p, fd) = readTarget(path)
        return ToolRunner.exec("part.show", imagePath = p, imageFd = fd)
    }

    /**
     * 格式化一个分区（`fs.format`）。
     *
     * 工具端只有 `ntfs` / `fat32` 两种（**没有 exFAT**），簇大小也不能指定、
     * 由工具按分区大小自己选；`quick=false` 会逐扇区写零，大分区极慢，默认 true。
     */
    suspend fun format(
        lba: Long,
        fs: String,
        label: String? = null,
        quick: Boolean = true,
        path: String? = _current.value,
    ): ToolRunner.Result {
        if (path == null) return missing()
        val args = buildMap {
            put("lba", lba.toString())
            put("fs", fs)
            put("quick", quick.toString())
            if (!label.isNullOrBlank()) put("label", label)
        }
        return exec("fs.format", args = args, path = path, writable = true)
    }

    /** 卷标口径（2026-10-26）：自动打上的三个卷标，别在别处另写字面量。 */
    const val LABEL_ESP = "ESP"
    const val LABEL_WIN = "Windows OS"
    const val LABEL_REC = "Recovery"

    /** 建盘后要格式化的一块：角色 → 文件系统 + 卷标。 */
    data class FmtTarget(val lba: Long, val fs: String, val label: String?)

    /**
     * 建完盘按角色格式化（2026-10-26 定稿口径）。
     *
     * - **ESP** → FAT32，卷标 `ESP`；
     * - **Windows 系统分区**（windows 布局里第一块 Windows 分区）→ NTFS，卷标 `Windows OS`；
     * - **Windows 数据分区**（额外分区 / 逻辑卷 / 单分区的那个卷）→ NTFS，**不写卷标**；
     * - 系统保留（0x27，活动）→ NTFS，不写卷标；**恢复分区 → NTFS，卷标 `Recovery`**；
     * - MSR（`E3C9E316…`）、扩展容器（0x0F）→ 跳过（本身不承载文件系统）。
     *
     * 判定**只看 typeGuid / mbri + LBA 顺序**，不按 `parts` 下标猜：MBR 上
     * `part.show` 会把扩展容器里的逻辑卷一起列出来，顺序和「第几块」并不对应。
     */
    fun formatPlan(layout: String, parts: List<Part>): List<FmtTarget> {
        var winTaken = false
        val out = ArrayList<FmtTarget>(parts.size)
        for (p in parts.sortedBy { it.lba }) {
            val t = p.typeGuid.uppercase()
            when {
                t.startsWith("E3C9E316") -> Unit                                   // MSR：不格式化
                t == "0X0F" || t == "0F" || p.mbri == 0x0F -> Unit                  // 扩展容器
                t.startsWith("C12A7328") || p.mbri == 0xEF ->
                    out += FmtTarget(p.lba, "fat32", LABEL_ESP)
                t.startsWith("DE94BBA4") -> out += FmtTarget(p.lba, "ntfs", LABEL_REC)  // GPT 恢复分区
                // MBR 的 0x27 有两种：活动的那块是系统保留（Bootmgr），不活动的才是恢复分区
                p.mbri == 0x27 -> out += FmtTarget(p.lba, "ntfs", if (p.active) null else LABEL_REC)
                else -> {
                    val first = layout == "windows" && !winTaken
                    winTaken = true
                    out += FmtTarget(p.lba, "ntfs", if (first) LABEL_WIN else null)
                }
            }
        }
        return out
    }

    /**
     * 单块分区的「按类型格式化」口径（分区管理页用）：直接告诉界面这块钱要格式化成什么。
     *
     * 只有 **ESP → FAT32（卷标 `ESP`）**，其余一律 NTFS；卷标仍按角色走
     * （恢复分区 `Recovery`、Windows 系统分区 `Windows OS`、数据分区不写）。
     * **MSR / 扩展容器返回 null**（不承载文件系统，界面不该给"格式化"入口）。
     *
     * 盘上有 ESP 或 0x27 系统保留才算"Windows 盘"，这时第一块数据分区才是
     * `Windows OS`；纯数据盘不硬贴这个名字。
     */
    fun formatTargetOf(parts: List<Part>, lba: Long): FmtTarget? {
        val looksWindows = parts.any {
            it.typeGuid.startsWith("C12A7328", true) || it.mbri == 0xEF || it.mbri == 0x27
        }
        return formatPlan(if (looksWindows) "windows" else "data", parts)
            .firstOrNull { it.lba == lba }
    }

    // ── 分区表文件（REQ-14：`part.export` / `part.apply`） ──

    /**
     * 分区表文件的预览口径（`kind = "icdq.parttable"`，格式由工具端定）。
     *
     * `raw` 是**文件正文**：预览、落盘、传给 `part.apply --in=` 用的都是同一份文本，
     * 保证"看到的"和"用上的"不会分叉。
     */
    data class TableFile(
        /** `GPT` / `MBR`。 */
        val table: String,
        val parts: Int,
        /** 至少要多大盘（GiB）：按最后一块的结束位置算，含 1 MiB 对齐与 GPT 尾部备份余量。 */
        val needGb: Int,
        /** 文件里记的源盘容量（GiB），只作参考。 */
        val diskGb: Int,
        val raw: String,
    )

    /** 解析表文件；不是我们的格式 / 版本太新 / 字段非法 → null（界面按"这不是分区表"处理）。 */
    fun parseTableFile(text: String): TableFile? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("kind") != "icdq.parttable") return null
        if (o.optInt("v", 0) !in 1..TABLE_FILE_V) return null
        val table = o.optString("table").uppercase()
        if (table != "GPT" && table != "MBR") return null
        if (o.optInt("sectorSize", 512) != 512) return null
        val arr = o.optJSONArray("parts") ?: return null
        var end = 0L
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: return null
            val first = p.optLong("first", 0L)
            val sec = p.optLong("sectors", 0L)
            if (first <= 0L || sec <= 0L) return null
            end = maxOf(end, first + sec)
        }
        val bytes = (end + 2048) * 512
        val gb = ((bytes + (1L shl 30) - 1) / (1L shl 30)).toInt().coerceAtLeast(1)
        val diskGb = ((o.optLong("diskSectors", 0L) * 512) / (1L shl 30)).toInt()
        return TableFile(table, arr.length(), gb, diskGb, text)
    }

    /**
     * 导出整张分区表（`part.export`，REQ-14）。源盘**只读**，导出的只有表、不含数据。
     *
     * `out` 给路径就写文件；不给只把内容回在 `data.pt` 里。
     */
    suspend fun exportTable(out: String? = null, path: String? = _current.value): ToolRunner.Result {
        if (path == null && !hasAnyFd()) return missing()
        val (p, fd) = readTarget(path)
        return ToolRunner.exec(
            "part.export",
            args = buildMap { if (out != null) put("out", out) },
            imagePath = p,
            imageFd = fd,
        )
    }

    /**
     * 按表文件**覆盖**目标盘的分区表（`part.apply`，REQ-14）。
     *
     * LBA / GUID / 名字 / 属性全按文件；只写表区，不碰分区数据。
     * `force = true` 才会覆盖已有分区表（建盘流程里目标是一张空表，直接给 true）。
     */
    suspend fun applyTable(
        inPath: String,
        path: String? = _current.value,
        force: Boolean = true,
    ): ToolRunner.Result = exec(
        "part.apply",
        args = buildMap {
            put("in", inPath)
            if (force) put("force", "true")
        },
        path = path,
        writable = true,
    )

    // ── 释放镜像（WIM/ESD → 分区） ──

    /**
     * `wim.identify`：不信文件名，从内容识别。给 `index=0` 要全部映像。
     *
     * `scan = true`（只对 ISO 有意义）：回 `candidates[]`，把里面**所有** wim/esd 列出来
     * （`/sources`、`/x86/sources`、`/x64/sources` 那种合一盘）。注意它**也会列出 `boot.wim`**，
     * 调用方要自己按文件名筛（App 只认 `install.*`）。
     */
    suspend fun wimIdentify(
        src: String,
        scan: Boolean = false,
        /** ISO：里面哪一条（REPLY-28 起直读，不用再抽整份）。 */
        inner: String? = null,
    ): ToolRunner.Result =
        ToolRunner.exec(
            "wim.identify",
            args = buildMap {
                put("src", src)
                put("index", "0")
                if (scan) put("scan", "true")
                if (!inner.isNullOrBlank()) put("inner", inner)
            },
        )

    /** `wim.size`：这个映像展开后要多少字节（工具给数字，App 定政策）。ISO 要给 [inner]。 */
    suspend fun wimSize(wim: String, index: Int, inner: String? = null): ToolRunner.Result =
        ToolRunner.exec(
            "wim.size",
            args = buildMap {
                put("wim", wim)
                put("index", index.toString())
                if (!inner.isNullOrBlank()) put("inner", inner)
            },
        )

    /**
     * `wim.apply`：把映像灌进某个分区。
     *
     * ★ `format = true` 会**先格式化目标分区**（毁掉里面的数据），默认就是 on；
     *   界面必须先过二次确认卡。
     */
    suspend fun applyWim(
        lba: Long,
        wim: String,
        index: Int = 1,
        format: Boolean = true,
        label: String? = null,
        /** ISO：里面哪一条（`wim.identify --scan` 的 `inner_path`）。工具端**直读**，不落临时文件。 */
        inner: String? = null,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "wim.apply",
        args = buildMap {
            put("lba", lba.toString())
            put("wim", wim)
            put("index", index.toString())
            put("format", if (format) "on" else "off")
            if (!label.isNullOrBlank()) put("label", label)
            if (!inner.isNullOrBlank()) put("inner", inner)
        },
        path = path,
        writable = true,
    )

    /**
     * `wim.extract`：从 WIM/ESD（或 ISO 里的 WIM）取**单个文件**到本地。
     *
     * 用来拿 `boot.sdi` 这类小件（实测 357 ms），比"先灌盘再从卷里 export"干净：
     * 不依赖目标分区里已经有这个文件。
     */
    suspend fun wimExtract(
        wim: String,
        path: String,
        dst: String,
        index: Int = 1,
        inner: String? = null,
    ): ToolRunner.Result = copyFrom(
        // 统一走 `copy`：`<wim>!<index>:/path` → 宿主文件
        src = wimPath(wim, index, path, inner),
        dst = "host:" + dst,
    )

    /** `iso.extract`：从 ISO 里抽一个文件出来（ISO 里的 `install.wim` 要它先落地）。 */
    suspend fun isoExtract(iso: String, innerPath: String, dst: String): ToolRunner.Result =
        copyFrom(src = isoPath(iso, innerPath), dst = "host:" + dst)

    /** `bcd.create`：用内置模板建一张能启动的 BCD（BIOS / UEFI 自动按分区表）。 */
    suspend fun bcdCreate(
        sysLba: Long,
        espLba: Long? = null,
        srLba: Long? = null,
        keepRecovery: Boolean = false,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "bcd.create",
        args = buildMap {
            put("sys-lba", sysLba.toString())
            if (espLba != null && espLba > 0L) put("esp-lba", espLba.toString())
            if (srLba != null && srLba > 0L) put("sr-lba", srLba.toString())
            if (keepRecovery) put("keep-recovery", "true")
        },
        path = path,
        writable = true,
    )

    /**
     * `recovery.populate` / `recovery.populate-system`：把 WinRE 填进恢复分区。
     *
     * `system = true` → 写进**系统分区**的 `Windows\System32\Recovery`（BIOS 的用法；
     * GPT 没有独立恢复分区时也走这条，`lba` 给系统分区）。
     */
    suspend fun recoveryPopulate(
        lba: Long,
        wim: String,
        index: Int = 1,
        enableScript: Boolean = false,
        osLba: Long = 0L,
        system: Boolean = false,
        /** ISO：里面哪一条（REPLY-17 起 `winre-src` 也能给 ISO，直读不抽取）。 */
        inner: String? = null,
        /**
         * 恢复分区里的 `boot.sdi`（ramdisk 靠它加载 winre.wim）。
         * 工具端只吃**本地文件**，App 先用 `ntfs.export` 从系统分区抽出来再传。
         * 不给的话 `boot.check` 会报 `rec.bootsdi`「高级启动会失败」。
         */
        bootsdi: String? = null,
        /** `winre.find` 给的 `internal_path`（有些映像的 winre 在第二个标准位置）。 */
        winrePath: String? = null,
        /**
         * ★ 先格式化目标分区再填。
         *
         * 工具端 `recovery.populate` 的 `format` 参数默认是 `false`，**它不会自己格**；
         * 而填充完它会复核（`REC-VERIFY-FAIL`），分区里有旧文件/旧目录结构就容易失败。
         * 所以只要目标分区是我们自己建的独立恢复分区，App 一律传 `true`。
         *
         * ⚠️ 会毁掉该分区上原有数据 —— 只对"专用恢复分区"用，不要对系统分区用。
         */
        format: Boolean = false,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        if (system) "recovery.populate-system" else "recovery.populate",
        args = buildMap {
            put("rec-lba", lba.toString())
            put("winre-src", wim)
            put("winre-index", index.toString())
            if (!inner.isNullOrBlank()) put("inner", inner)
            if (!bootsdi.isNullOrBlank()) put("bootsdi-src", bootsdi)
            if (!winrePath.isNullOrBlank()) put("winre-path", winrePath)
            if (format) put("format", "true")
            if (enableScript) {
                put("enable-script", "true")
                put("os-lba", osLba.toString())
            }
        },
        path = path,
        writable = true,
    )

    // ── 引导（写引导 / 自检）──

    /** `ntfs.export`：把分区里的文件抽到本地（UEFI 的 bootmgfw.efi、BIOS 的 bootmgr 都靠它）。 */
    suspend fun ntfsExport(
        lba: Long,
        srcPath: String,
        dst: String,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "ntfs.export",
        args = mapOf("lba" to lba.toString(), "path" to srcPath, "dst" to dst),
        path = path,
        writable = true,
    )

    /** `fat.put`：往 FAT32（ESP）里写文件。 */
    suspend fun fatPut(
        lba: Long,
        src: String,
        dst: String,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "copy",
        args = mapOf("src" to "host:" + src, "dst" to diskPath(lba, dst), "create_parents" to "true"),
        path = path,
        writable = true,
    )

    /** `ntfs.put`：往 NTFS 里写文件（BIOS 系统保留分区放 bootmgr）。 */
    suspend fun ntfsPut(
        lba: Long,
        src: String,
        dst: String,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "copy",
        args = mapOf("src" to "host:" + src, "dst" to diskPath(lba, dst), "create_parents" to "true"),
        path = path,
        writable = true,
    )

    /**
     * `win.version`：读已装盘的真实 Windows 版本。
     *
     * 工具端交叉验证四路：注册表 `CurrentVersion`、`ntoskrnl` 的 PE 版本、`ntdll`、`winload.exe`，
     * 回包里给 `registry.product_name` / `registry.edition_id` / `registry.current_build` / `arch`。
     * 展示名用 [WinEdition.label] 拼（**不看** build 之外的猜测）。
     */
    suspend fun winVersion(
        lba: Long,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "win.version",
        args = mapOf("lba" to lba.toString()),
        path = path,
    )

    /**
     * `winre.auto`：写/删「首次开机 reagentc /enable」脚本（`SetupComplete.cmd`）。
     *
     * 修复路径用它 —— `recovery.populate` 那条会**先格式化分区**，修一下反而丢文件。
     */
    suspend fun winreAuto(
        lba: Long,
        enable: Boolean = true,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "winre.auto",
        args = mapOf("lba" to lba.toString(), "enable" to enable.toString()),
        path = path,
        writable = true,
    )

    // ── 统一路径构造（REPLY-55）────────────────────────────────────────────
    // 工具端两种分区写法都认：`!pN:`（第 N 个分区）与 `!lba<N>:`（显式 LBA）。
    // App 内部一律用 lba —— 不依赖分区序号，表变了也不会指错分区。

    /** 宿主文件 → 工具端路径。 */
    fun hostPath(abs: String): String = "host:" + abs

    /** 磁盘内路径：`<img>!lba<LBA>:<path>`。 */
    fun diskPath(lba: Long, path: String, img: String? = _current.value): String =
        "${img ?: ""}!lba$lba:${path.ifEmpty { "/" }}"

    /** ISO 内路径：`<iso>!:<path>`。 */
    fun isoPath(iso: String, path: String): String = "$iso!:$path"

    /** WIM 内路径：`<wim>!<index>:<path>`；ISO 里的 WIM 用 `<iso>!:<inner>!<index>:<path>`。 */
    fun wimPath(wim: String, index: Int, path: String, inner: String? = null): String =
        if (inner.isNullOrBlank()) "$wim!$index:$path"
        else "$wim!:$inner!$index:$path"

    // ── 注册表（REPLY-58 / REQ-60 §2.2）────────────────────────────────────
    // 只读写 hive 文件，不启动 Windows、不挂载卷。**本轮只做只读**（list / get）。

    /** 支持的 hive（大小写不敏感）。 */
    val REG_HIVES = listOf("SYSTEM", "SOFTWARE", "SAM", "SECURITY", "DEFAULT")

    /**
     * `reg.list`：列子键与值。
     *
     * `path` 形如 `SYSTEM\Setup`；返回 `items[] {name, kind, type, bytes}` + `truncated`。
     * 键不存在报 `REG-NOTFOUND`（不是空列表）。
     */
    suspend fun regList(
        lba: Long,
        path: String,
        img: String? = _current.value,
    ): ToolRunner.Result = exec(
        "reg.list",
        args = mapOf(
            "lba" to lba.toString(),
            "path" to path,
            "subkeys" to "true",
            "values" to "true",
        ),
        path = img,
        writable = false,
    )

    /**
     * `reg.get`：读一个值。
     *
     * 返回 `type` + `value`（REG_SZ/DWORD/QWORD）或 `value_hex`（REG_BINARY）。
     */
    suspend fun regGet(
        lba: Long,
        path: String,
        img: String? = _current.value,
    ): ToolRunner.Result = exec(
        "reg.get",
        args = mapOf("lba" to lba.toString(), "path" to path),
        path = img,
        writable = false,
    )

    /**
     * `sys.tmpdir`：工具端当前生效的临时目录（REQ-61 §3）。
     *
     * 返回字段：`path` / `source`（命中的档位）/ `writable` /
     * `free_bytes` / `total_bytes` / `stale_removed`（本次清掉的残留数）。
     *
     * ★ 空间预检必须跟它给的 `free_bytes` 比 —— 那是**工具真正会用的那一卷**。
     *   之前 App 拿输出文件所在卷的剩余空间去比，而 `wim.capture` 的中间树
     *   落在 tmpdir（另一卷），两边可能一个够一个不够。
     */
    suspend fun sysTmpdir(): ToolRunner.Result = exec(
        "sys.tmpdir",
        path = null,
        writable = false,
    )

    // ── wim.capture（REQ-60 §2.3）──────────────────────────────────────────

    /** 支持的压缩方式。 */
    val CAPTURE_COMP = listOf("none", "xpress", "lzx", "lzms")

    /**
     * 估算捕获体积（不写盘）。返回 `bytes_needed` / `files` / `bytes_needed_mb`。
     * 调用方**必须**用返回值做空间预检：临时目录需要 ≥ bytes_needed × 1.2。
     */
    suspend fun captureEstimate(
        lba: Long,
        img: String? = _current.value,
    ): ToolRunner.Result = exec(
        "wim.capture",
        args = mapOf("lba" to lba.toString(), "estimate" to "true"),
        path = img,
        writable = false,
    )

    /**
     * 正式捕获：把系统分区整树打包成 WIM。
     *
     * ⚠️ wimlib 只能读宿主路径，工具端会先把整树解到临时目录（≈原始体积）再打包。
     * 临时目录走 `TempDirs`（App 缓存），启动时清扫。
     */
    suspend fun capture(
        lba: Long,
        out: String,
        comp: String = "xpress",
        name: String = "Windows",
        img: String? = _current.value,
    ): ToolRunner.Result = exec(
        "wim.capture",
        args = mapOf(
            "lba" to lba.toString(),
            "out" to out,
            "comp" to comp,
            "name" to name,
        ),
        path = img,
        writable = true,
    )

    /** 统一 copy：`src` → `dst`（两个都是统一路径）。`recursive` 默认关（单文件）。 */
    suspend fun copyFrom(
        src: String,
        dst: String,
        recursive: Boolean = false,
        maxDepth: Int? = null,
        conflict: String? = null,
        createParents: Boolean = false,
    ): ToolRunner.Result = exec(
        "copy",
        args = buildMap {
            put("src", src)
            put("dst", dst)
            if (recursive) put("recursive", "true")
            if (maxDepth != null) put("max-depth", maxDepth.toString())
            if (conflict != null) put("conflict", conflict)
            if (createParents) put("create_parents", "true")
        },
        writable = true,
    )

    /**
     * `boot.check`：引导链自检。返回里 `data.required_fail` 是**必需项**失败数，
     * `data.items[]` 每条有 `id` / `level`(required|warn|info) / `pass` / `name`。
     * App 用 `required_fail > 0` 判定"大概率起不来"。
     */
    suspend fun bootCheck(
        mode: String = "auto",
        expectRecovery: Boolean = false,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "boot.check",
        args = buildMap {
            put("mode", mode)
            if (expectRecovery) put("expect-recovery", "true")
        },
        path = path,
    )

    /** `boot.repair`：BIOS 引导修复（VBR 用内置模板；MBR 码要 `--mbr-code` 给 440 字节文件）。 */
    suspend fun bootRepair(
        sysLba: Long,
        srLba: Long? = null,
        noVbr: Boolean = false,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "boot.repair",
        args = buildMap {
            put("sys-lba", sysLba.toString())
            if (srLba != null && srLba > 0L) put("sr-lba", srLba.toString())
            if (noVbr) put("no-vbr", "true")
            // ★ 不要传 `vbr-tpl`：它要的是"镜像:lba"那种**外部模板源**，
            //   传 "builtin" 会被判 CMD-ARG-BAD@vbr-tpl（工具端默认就是用内置模板）。
            //   2026-10-29 修复：引导修复一直失败就是这个参数写错了。
        },
        path = path,
        writable = true,
    )

    /**
     * `boot.populate`：**一条命令铺完引导**（REPLY-20）。
     *
     * UEFI：ESP 没文件系统才格 FAT32 → `bootmgfw.efi`（+ `\EFI\Boot\bootx64.efi` 回退路径）
     * → `\Windows\Boot\Fonts\*` → `boot.sdi` → BCD；
     * BIOS：SR 格式化 → 活动标志 → `bootmgr` → 内置 MBR/VBR 码 → BCD。
     * 返回里带 `formatted` / `files[]` / `bcd_bytes` / **`boot_check{required_fail,warn_fail,summary}`**。
     */
    suspend fun bootPopulate(
        sysLba: Long,
        espLba: Long? = null,
        srLba: Long? = null,
        fonts: Boolean = true,
        force: Boolean = false,
        path: String? = _current.value,
    ): ToolRunner.Result = exec(
        "boot.populate",
        args = buildMap {
            put("sys-lba", sysLba.toString())
            if (espLba != null && espLba > 0L) put("esp-lba", espLba.toString())
            if (srLba != null && srLba > 0L) put("sr-lba", srLba.toString())
            put("fonts", fonts.toString())
            if (force) put("force", "true")
        },
        path = path,
        writable = true,
    )

    /** `winre.find` 的结果：这份文件里的 WinRE 在哪。 */
    data class WinreHit(
        val self: Boolean,
        val index: Int,
        val internalPath: String,
        val sizeBytes: Long,
        /** 命中的是 ISO 里哪条 WIM（`/sources/install.wim` 这种）；裸文件/单 WIM 为 null。 */
        val inner: String? = null,
    )

    /**
     * `winre.find` 的结论。REPLY-25 起工具端会把**所有候选**都试一遍（`boot.wim` 里没有就地跳过），
     * 并且错误码分三种：`WINRE-NOTFOUND` 才是"真没有"，其它（`WIM-NOTFOUND`/`IMG-READ-FAIL`）
     * 都算"没查成"，不能当"没有"来拦用户。
     */
    data class WinreProbe(val hit: WinreHit?, val code: String) {
        val found get() = hit != null
        /** 真没有：只有这个码算数。 */
        val empty get() = !found && code == "WINRE-NOTFOUND"
        /** 没查成（工具自己出错）。 */
        val failed get() = !found && !empty
    }

    /**
     * `winre.find`：这份 ISO/WIM/ESD/裸 winre.wim 里有没有 `Winre.wim`（只读，0.5~1 s）。
     *
     * 不给 `--inner` 也行（工具端自己挑候选，REPLY-25）；`--index` 只是"先看这条"。
     * `--probe-only` 明确表意：只查不落盘。
     */
    suspend fun winreProbe(src: String, inner: String? = null, index: Int = 0): WinreProbe {
        val r = ToolRunner.exec(
            "winre.find",
            args = buildMap {
                put("src", src)
                put("probe-only", "true")
                if (index > 0) put("index", index.toString())
                if (!inner.isNullOrBlank()) put("inner", inner)
            },
        )
        val d = r.data
        if (r.ok && d != null && d.optBoolean("found", false)) {
            return WinreProbe(
                hit = WinreHit(
                    self = d.optBoolean("self", false),
                    index = d.optInt("index", 1),
                    internalPath = d.optString("internal_path"),
                    sizeBytes = d.optLong("size_bytes", 0L),
                    inner = d.optString("iso_inner_path").ifBlank { null },
                ),
                code = r.code ?: "OK",
            )
        }
        return WinreProbe(hit = null, code = if (r.ok) "WINRE-NOTFOUND" else (r.code ?: "IMG-READ-FAIL"))
    }

    /** `ntfs.exists`：只问"在不在"（判断系统分区是 32 位还是 64 位这类）。 */
    suspend fun ntfsExists(
        lba: Long,
        path: String,
        img: String? = _current.value,
    ): Boolean? {
        val r = exec(
            "ntfs.exists",
            args = mapOf("lba" to lba.toString(), "path" to path),
            path = img,
        )
        if (!r.ok) return null
        return r.data?.optBoolean("exists")
    }

    /** `fat.exists`：ESP 上问"这个引导文件在不在"（UEFI 64 / UEFI 32 靠它区分）。 */
    suspend fun fatExists(
        lba: Long,
        path: String,
        img: String? = _current.value,
    ): Boolean? {
        val r = exec(
            "fat.exists",
            args = mapOf("lba" to lba.toString(), "path" to path),
            path = img,
        )
        if (!r.ok) return null
        return r.data?.optBoolean("exists")
    }

    private fun missing() = ToolRunner.Result(
        cmd = "-",
        ok = false,
        code = "NO-IMAGE",
        summary = "还没有选择镜像",
    )

    /**
     * 映像是只读打开的（SAF 只拿到 "r"），改不了。
     *
     * 2026-11-12（批次 A）：以前这种情形是把只读 fd 塞给写命令，工具端报
     * 一堆 IMG-OPEN-FAIL，用户看不懂；现在 App 侧直接说清楚"只读、怎么才能改"。
     */
    private fun readonlyImage() = ToolRunner.Result(
        cmd = "-",
        ok = false,
        code = "IMG-READONLY",
        summary = "映像是只读打开的",
    )

    /** 单个分区的文件系统口径（`part.scan`）。 */
    data class Scan(
        val fs: String,
        val level: Int,
        val label: String?,
        val freeBytes: Long,
        val usedBytes: Long,
        val looksLikeWindows: Boolean,
        val clusterBytes: Int,
    ) {
        val hasFs: Boolean get() = level >= 1
    }

    /**
     * `part.scan`：分区的三级口径（无文件系统 / 有卷但空 / 有文件），
     * 外加卷标、已用/可用、以及**根目录有没有 `Windows`**（判断系统分区）。
     *
     * 会挂载卷，比 `part.show` 慢（几百 ms/分区），所以只在需要展示的页面上按需调。
     */
    suspend fun scan(lba: Long, path: String? = _current.value): ToolRunner.Result {
        if (path == null && !hasAnyFd()) return missing()
        val (p, fd) = readTarget(path)
        return ToolRunner.exec(
            "part.scan",
            imagePath = p,
            imageFd = fd,
            args = mapOf("lba" to lba.toString()),
        )
    }

    fun parseScan(r: ToolRunner.Result): Scan? {
        val d = r.data ?: return null
        return Scan(
            fs = d.optString("fs", "none"),
            level = d.optInt("level", -1),
            label = d.optString("label", "").takeIf { it.isNotBlank() && !it.equals("NO NAME", true) },
            freeBytes = d.optLong("free_bytes", 0),
            usedBytes = d.optLong("used_bytes", 0),
            looksLikeWindows = d.optBoolean("looks_like_windows", false),
            clusterBytes = d.optInt("cluster_bytes", 0),
        )
    }

    // ── 结果解析 ──

    data class Part(
        val lba: Long,
        val sectors: Long,
        val typeGuid: String,
        val active: Boolean,
        val logical: Boolean,
        /** MBR 的分区类型字节（0x27/0x07/0x0F/0xEF…）；GPT 为 -1。 */
        val mbri: Int = -1,
        /** GPT 分区属性（`0x8000000000000001` 这种）；MBR 为 null。 */
        val attributes: String? = null,
    ) {
        val bytes: Long get() = sectors * 512
    }

    /** 分区表快照：表类型 + **磁盘总扇区数** + 分区列表。 */
    data class PtInfo(val table: String, val diskSectors: Long, val parts: List<Part>) {
        val diskBytes: Long get() = diskSectors * 512
    }

    /** 改 GPT 分区属性（`part.attr`）；MBR 盘工具端会拒绝。 */
    suspend fun setPartAttr(lba: Long, attr: String, path: String? = _current.value): ToolRunner.Result =
        exec(
            "part.attr",
            args = mapOf("lba" to lba.toString(), "attr" to attr),
            path = path,
            writable = true,
        )

    fun parseParts(r: ToolRunner.Result): Pair<String, List<Part>>? {
        val info = parsePt(r) ?: return null
        return info.table to info.parts
    }

    /** 分区表 JSON 里的一条分区（`part.show` / 表文件的 `parts[]` 字段名一致）。 */
    fun parsePart(o: JSONObject): Part = Part(
        lba = o.optLong("first", o.optLong("start", 0)),
        sectors = o.optLong("sectors", 0),
        typeGuid = o.optString("typeGuid", o.optString("type", "")),
        active = o.optBoolean("active", false),
        logical = o.optBoolean("logical", false),
        mbri = if (o.has("mbri")) o.optInt("mbri", -1) else -1,
        attributes = o.optString("attributes").takeIf { it.isNotBlank() },
    )

    /** 表文件 / `part.export` 的 `pt` 里的 `parts[]`。 */
    fun parseTableParts(o: JSONObject?): List<Part> {
        val arr = o?.optJSONArray("parts") ?: return emptyList()
        val out = ArrayList<Part>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += parsePart(it) }
        return out
    }

    fun parsePt(r: ToolRunner.Result): PtInfo? {
        val pt = r.data?.optJSONObject("pt") ?: return null
        val table = pt.optString("table")
            .ifBlank { r.data?.optString("table").orEmpty() }
            .ifBlank { "?" }
            .uppercase()
        val diskSectors = pt.optLong("diskSectors", 0L)
        val arr = pt.optJSONArray("parts") ?: return PtInfo(table, diskSectors, emptyList())
        val out = ArrayList<Part>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            out += parsePart(o)
        }
        return PtInfo(table, diskSectors, out)
    }

    /**
     * 建盘之后追加逻辑卷（工具端没有 `mbr-fill=auto` 时的退路）。
     *
     * 没有扩展容器就先 `part.add --type=extended`（省略 size = 吃满剩余），
     * 然后逐块 `--type=logical`。返回**第一个失败**，全成功返回 null。
     */
    /** App 自己排的一块分区（`part.add --lba` 用）。 */
    data class LayoutPart(val type: String, val sizeMb: Int, val label: String) {
        init {
            // `part.add --label` 是 **GPT 分区名**，工具端按 11 字符卡（FAT32 卷标那套规则）；
            // 超了整条命令会被拒，所以在构造处就挡住（"Windows OS" = 10，刚好）
            require(label.length <= 11) { "GPT 分区名 ≤11 字符：$label" }
        }
    }

    /**
     * 按 **App 算好的位置**建盘（GPT）：`img.create --layout=empty` → 逐块 `part.add --lba=`。
     *
     * 为什么不用 `img.create --layout=windows` 自带的排布：它把**恢复分区排在所有数据分区之后**，
     * 而 Windows 文档的顺序是 `ESP · MSR · Windows · Recovery · 数据分区…`。
     * "分区放哪儿"是 App 的决定，所以位置由这里算（1 MiB 对齐，起始 LBA 2048）。
     */
    suspend fun createWithLayout(
        name: String,
        sizeGb: Int,
        parts: List<LayoutPart>,
        dir: File = imagesDir(),
        format: String = "qcow2",
    ): CreateOutcome {
        val path = File(dir, "$name.$format").absolutePath
        val mk = ToolRunner.exec(
            "img.create",
            args = mapOf(
                "out" to path,
                "size-gb" to sizeGb.toString(),
                "table" to "gpt",
                "layout" to "empty",
            ).let { if (format == "qcow2") it else it + ("format" to format) },
            writable = true,
        )
        if (!mk.ok) return CreateOutcome(mk, false, false)

        var lba = 2048L                       // GPT 之后第一个 1 MiB 对齐点
        for (p in parts) {
            if (p.sizeMb <= 0) continue
            val r = ToolRunner.exec(
                "part.add",
                args = mapOf(
                    "lba" to lba.toString(),
                    "size-mb" to p.sizeMb.toString(),
                    "type" to p.type,
                    "label" to p.label,
                ),
                imagePath = path,
                writable = true,
            )
            if (!r.ok) return CreateOutcome(r, false, false)
            lba += p.sizeMb.toLong() * 2048L  // 1 MiB = 2048 扇区
        }
        setPickedFd(null)
        setCurrent(path)
        ImageHistory.record(path, null, "$name.qcow2", runCatching { File(path).length() }.getOrDefault(0L))
        return CreateOutcome(mk, false, false)
    }

    suspend fun appendLogical(sizes: List<Int>, path: String? = _current.value): ToolRunner.Result? {
        if (path == null) return null
        if (sizes.isEmpty()) {
            // 只建空的扩展容器（用户勾了扩展分区但还没加逻辑卷）
            val c = ToolRunner.exec("part.add", args = mapOf("type" to "extended"), imagePath = path, writable = true)
            return if (c.ok) null else c
        }
        val hasContainer = parsePt(parts(path))?.parts
            ?.any { it.typeGuid.equals("0x0F", true) || it.mbri == 0x0F } == true
        if (!hasContainer) {
            val c = ToolRunner.exec(
                cmd = "part.add",
                args = mapOf("type" to "extended"),
                imagePath = path,
                writable = true,
            )
            if (!c.ok) return c
        }
        for (mb in sizes) {
            val r = ToolRunner.exec(
                cmd = "part.add",
                args = mapOf("type" to "logical", "size-mb" to mb.toString()),
                imagePath = path,
                writable = true,
            )
            if (!r.ok) return r
        }
        return null
    }
}