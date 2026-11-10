package org.icdqcow2.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.icdqcow2.core.TempDirs
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 释放镜像：把 WIM/ESD/ISO 里的 Windows 灌进某个分区（`wim.apply`）。
 *
 * 流程（用户 2026-10-26 定的）：
 * 1. 选**要操作的磁盘镜像** → 顶部「当前操作的文件」卡片；
 * 2. **分区管理那套磁盘长条**：点哪块装哪块；ESP / MSR / 0x27 / 容器点了无效；
 * 3. **自动预选**第一款 NTFS 数据分区（用户可以改）；
 * 4. 选映像 → ISO 用 `--scan` 列候选 → 合一盘弹卡选一条 → 「选择 Windows 版本」再选卷；
 * 5. 三个开关：写引导（默认开）/ 写 Windows 恢复分区 / 写启用恢复脚本；
 * 6. 两处大小校验（只在**不够**时提示）+ 分区自检确认窗；
 * 7. 「开始释放」→ **确认卡片**（镜像/版本/目标/展开大小/开关一览）→ 新开一个
 *    **释放页**看进度（`ToolEvents` 事件流 + 自算 ETA），跑完回表单。
 *
 * 目标分区会被命名成 `Windows OS`（别的分区上同名的先清掉，见释放前的"重命名"步骤）。
 */
/**
 * 和主页"点卡片进子页"完全同一套左右滑动：forward=true 新页从右侧进、旧页往左出。
 * 页面内部的切换（选镜像 → 配置、配置 → 进度）也用它，别再硬切。
 */
@Composable
fun DeployPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** 全流程装机带进来时显示（第 N 步 / 共 M 步）。 */
    subtitle: String? = null,
    /** 只给向导用：主按钮换成「下一步」；单独打开时仍是「开始释放」。 */
    primaryLabel: String? = null,
    /**
     * 只给向导用：**带着已选好的镜像进来**，跳过「选择镜像」那一屏。
     * 默认 null → 单独打开时行为完全不变（先选镜像）。
     */
    initialImage: String? = null,
    /** 只给向导用：跑完之后底部按钮（「下一步」）跳到下一步；null = 老行为（返回配置页）。 */
    onNext: (() -> Unit)? = null,
    /** 只给向导用：告诉外面"现在是不是正在写盘"（退出弹窗要说重话）。 */
    onRunningChange: ((Boolean) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var image by remember { mutableStateOf<String?>(initialImage) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var noTable by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf("") }
    var diskSectors by remember { mutableLongStateOf(0L) }
    var parts by remember { mutableStateOf<List<ImageRepo.Part>>(emptyList()) }
    var scans by remember { mutableStateOf<Map<Long, ImageRepo.Scan>>(emptyMap()) }
    var targetLba by remember { mutableStateOf<Long?>(null) }
    /** 已经给哪张盘自动选过（换盘才重新自动选，不抢用户的手动选择）。 */
    var autoPickedFor by remember { mutableStateOf<String?>(null) }

    // 映像
    var images by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var wimPath by remember { mutableStateOf<String?>(null) }
    var wimInner by remember { mutableStateOf<String?>(null) }
    var cands by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var wimIndex by remember { mutableIntStateOf(1) }
    var wimNeed by remember { mutableLongStateOf(0L) }
    var indexPick by remember { mutableStateOf(false) }
    var candPick by remember { mutableStateOf(false) }
    // ★ 2026-10-27 晚（REPLY-29，so a86d0870）：ISO 里的 WIM 能**直读**了，
    //   "选完 ISO 先抽 2.7 GB install.wim" 那套（prepWim/prepBusy/prepErr）整套删掉。
    var writeBoot by remember { mutableStateOf(true) }
    var writeRec by remember { mutableStateOf(false) }
    var writeScript by remember { mutableStateOf(false) }
    var formatFirst by remember { mutableStateOf(true) }

    // ── WinRE 来源 ──
    /** 当前映像里的 WinRE 探测结论（null = 还没查）。 */
    var curRe by remember { mutableStateOf<ImageRepo.WinreProbe?>(null) }
    var winreBusy by remember { mutableStateOf(false) }
    /** 用户单独指定的 RE 来源（另一份 ISO/ESD/WIM，或裸 winre.wim）。 */
    var reSrcPath by remember { mutableStateOf<String?>(null) }
    var reSrcRe by remember { mutableStateOf<ImageRepo.WinreProbe?>(null) }

    var confirm by remember { mutableStateOf(false) }
    var selfCheck by remember { mutableStateOf<String?>(null) }
    /** WinRE 没扫到时是否问过"仍然尝试"。 */
    var winreAskSkip by remember { mutableStateOf(false) }

    // 释放过程
    var run by remember { mutableStateOf<DeployRun?>(null) }
    var etaSec by remember { mutableIntStateOf(-1) }
    val etaMeter = remember { org.icdqcow2.core.EtaMeter() }
    val progress by org.icdqcow2.core.ToolEvents.progress.collectAsState()
    // REQ-68：工具端 `eta` 已改成"当前阶段窗口速率 + 阶段累计速率"，换阶段不再继承
    // 格式化那 0.1 秒，也不会拿"流个数"当字节算（以前剩 200 MB 会报"剩 0 秒"）。
    // 所以：有就用它；只有 -1（还估不出来）时才退回本地 EtaMeter 兜底。
    LaunchedEffect(progress) {
        val p = progress ?: return@LaunchedEffect
        val fromTool = p.eta
        etaSec = when {
            fromTool > 0 -> fromTool
            p.totalBytes > 0L -> etaMeter.feed(p.curBytes, p.totalBytes)
            else -> -1
        }
    }

    // ── 读磁盘 ──
    LaunchedEffect(image, reload) {
        val path = image ?: return@LaunchedEffect
        // 这些是同步 JNI（img.info / part.show / stat）：必须在 IO 线程跑。
        // 留在主线程的话，进页面那 1 秒里动画一帧都推不动，看起来就是"卡片没有动画"。
        withContext(Dispatchers.IO) {
        loading = true
        err = null
        noTable = false
        parts = emptyList()
        scans = emptyMap()
        targetLba = null
        val r = ImageRepo.parts(path)
        val parsed = ImageRepo.parseParts(r)
        if (parsed == null) {
            if (r.code == "PART-READ-FAIL") noTable = true else err = toolErrorText(s, r)
        } else {
            table = parsed.first
            val list = parsed.second.sortedBy { it.lba }
            parts = list
            diskSectors = ImageRepo.parsePt(r)?.diskSectors ?: 0L
            val acc = LinkedHashMap<Long, ImageRepo.Scan>()
            for (p in list) {
                if (p.isContainer()) continue
                ImageRepo.parseScan(ImageRepo.scan(p.lba, path))?.let {
                    acc[p.lba] = it
                    scans = acc.toMap()
                }
            }
            scans = acc.toMap()
            // ★ 自动预选：第一款 NTFS 数据分区（没有 NTFS 就退一步选"有文件系统"的）
            if (autoPickedFor != path) {
                val usable = list.filter { !it.isDeployBlocked() }
                val pick = usable.firstOrNull { (acc[it.lba]?.fs ?: "").equals("NTFS", true) }
                    ?: usable.firstOrNull { acc[it.lba]?.hasFs == true }
                if (pick != null) {
                    targetLba = pick.lba
                    formatFirst = (acc[pick.lba]?.level ?: 1) >= 2
                }
                autoPickedFor = path
            }
        }
        loading = false
        }
    }

    // ── 选映像：identify（ISO 带 --scan 列全部候选）→ 选哪条 → 再选 Windows 版本 ──
    fun pickCandidate(c: org.json.JSONObject) {
        wimInner = c.optString("inner_path").ifBlank { null }
        val arr = c.optJSONArray("images")
        val list = buildList {
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
        images = list
        wimIndex = list.firstOrNull()?.optInt("index", 1) ?: 1
    }

    val wimPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageAccess.documentToPath(uri)
        if (path == null) {
            onToast(s.depWimBad)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val isoDirect = org.icdqcow2.core.ToolCaps.WIM_ISO_DIRECT
            var isIso = path.endsWith(".iso", true)
            var id = ImageRepo.wimIdentify(path, scan = isIso && isoDirect)
            if (!isIso && id.ok && id.data?.optString("container") == "iso") {
                isIso = true
                id = ImageRepo.wimIdentify(path, scan = isoDirect)
            }
            if (!id.ok) {
                onToast(toolErrorText(s, id))
                return@launch
            }
            wimPath = path
            if (isIso) {
                val arr = id.data?.optJSONArray("candidates")
                val list = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        if (!c.optBoolean("ok", false)) continue
                        val name = c.optString("inner_path").substringAfterLast('/')
                        // `--scan` 也会列 boot.wim（WinPE/Setup），那不是系统映像
                        if (!name.startsWith("install", true)) continue
                        add(c)
                    }
                }
                if (list.isEmpty()) {
                    onToast(s.depWimBad)
                    return@launch
                }
                cands = list
                if (list.size == 1) pickCandidate(list[0]) else candPick = true
            } else {
                cands = emptyList()
                wimInner = null
                val arr = id.data?.optJSONArray("images")
                val list = buildList {
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
                }
                images = list
                wimIndex = list.firstOrNull()?.optInt("index", 1) ?: 1
            }
        }
    }

    val rePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageAccess.documentToPath(uri)
        if (path == null) {
            onToast(s.depWimBad)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            winreBusy = true
            // 不给 --inner：工具端会把 ISO 里所有候选都试一遍（boot.wim 没有就跳过，REPLY-25）
            val re = ImageRepo.winreProbe(path)
            winreBusy = false
            when {
                re.found -> {
                    // 包括裸 winre.wim（工具端回 self:true，REPLY-24 起支持）
                    reSrcPath = path
                    reSrcRe = re
                }
                re.empty -> onToast(s.depWinreBad)
                // 没查成（工具自己出错）：不拦，先记下来，真写入时再报它自己的原话
                else -> {
                    onToast(s.depWinreForce)
                    reSrcPath = path
                    reSrcRe = re
                }
            }
        }
    }

    // ── 版本 / 文件变了就重新估体积 ──
    LaunchedEffect(wimPath, wimIndex, wimInner, images) {
        val path = wimPath ?: return@LaunchedEffect
        val size = ImageRepo.wimSize(path, wimIndex, wimInner)
        if (size.ok) {
            wimNeed = size.data?.optLong("est_ntfs_bytes", 0L) ?: 0L
        } else {
            val fallback = images.firstOrNull { it.optInt("index", -1) == wimIndex }
                ?.optLong("total_bytes", 0L) ?: 0L
            wimNeed = if (fallback > 0L) fallback + fallback / 50 else 0L
        }
    }

    // ── 当前映像里有没有 WinRE（直读 ISO 成员，REPLY-28 起不用先抽）──
    LaunchedEffect(wimPath, wimIndex, wimInner) {
        val p = wimPath
        curRe = null
        if (p == null) return@LaunchedEffect
        winreBusy = true
        var re = ImageRepo.winreProbe(p, wimInner, wimIndex)
        if (!re.found) re = ImageRepo.winreProbe(p)
        curRe = re
        winreBusy = false
    }

    // ── 派生 ──
    val isMbr = table.contains("MBR", true) || parts.any { it.logical } ||
        parts.any { it.typeGuid.startsWith("0x", true) }
    val isGpt = !isMbr
    val usableEnd = usableLast(diskSectors, isMbr) + 1
    val target = targetLba?.let { lba -> parts.firstOrNull { it.lba == lba } }
    val targetIdx = target?.let { t -> parts.indexOfFirst { it.lba == t.lba } + 1 } ?: 0
    val targetScan = targetLba?.let { scans[it] }
    val espPart = parts.firstOrNull {
        it.typeGuid.startsWith("C12A7328", true) || it.mbri == 0xEF
    }
    val srPart = parts.firstOrNull { it.mbri == 0x27 && it.active }
    val recPart = if (isMbr) parts.firstOrNull { it.mbri == 0x27 && !it.active }
    else parts.firstOrNull { it.typeGuid.startsWith("DE94BBA4", true) }
    val bootPart = if (isMbr) srPart else espPart

    val freePath = image?.let {
        if (it.startsWith("content://")) {
            runCatching { StorageAccess.documentToPath(android.net.Uri.parse(it)) }.getOrNull()
        } else {
            it
        }
    }
    val hostFree = StorageAccess.freeBytes(freePath)
    val needPart = if (wimNeed > 0L) wimNeed + 64L * 1024 * 1024 else 0L
    val needHost = needPart
    val partOk = target != null && wimNeed > 0L && target.bytes >= needPart
    val hostOk = wimNeed > 0L && hostFree >= needHost

    val curImageObj = images.firstOrNull { it.optInt("index", -1) == wimIndex }
    /**
     * 写之前就拦掉的"物理上起不来"：**ARM / ARM64** —— 这套盘是给 x86 固件/虚拟机用的，
     * arm 映像根本进不了引导流程 → 一律拒绝。
     * 拦在"选完映像"这一刻，别让用户先灌 100 秒再在写引导那步失败。
     *
     * 2026-11-08 起不再拦"32 位 + GPT"：
     * 工具端 `boot.plan` 的真实结论是 —— Vista/Win7 x86 + GPT 是 `DENIED`（物理不可能），
     * **Win8 及以后 x86 + GPT 可以走 32 位 UEFI**（`BOOT-GPT-X86-NEEDS-CONFIRM`，REPLY-54 §4）。
     * 所以 GPT + x86 只是**提醒**（写入时用工具端的 verdict 兜底），不是硬拦。
     */
    // ★ 判定**只看工具端归一后的 arch**（`x86|x64|arm|arm64|""`），不掺文件名/路径的猜测 ——
    //   以前把 archLabel 的结果拿来判定，路径里带 arm64 之类的字眼会误伤。
    val archNorm = curImageObj?.optString("arch").orEmpty().lowercase()
    // 规则（用户 2026-10-26 定稿）：**GPT 只支持 64 位；MBR 支持 32 位和 64 位**。
    // 只按"位数"拦，不再自己加"ARM 一律禁"那种额外规则（要不要禁交给工具端/用户）。
    /** 写恢复分区/启动脚本要用 WinRE：单独选的那份优先，否则看当前映像。 */
    val curWinre = curRe?.hit
    val reSrcHit = reSrcRe?.hit
    val hasReSource = reSrcHit != null || curWinre != null
    /**
     * **真没有**才拦（REPLY-25 之后工具端可信了：`winre.find` 会把 ISO 里每条候选都试一遍，
     * 并且只有 `WINRE-NOTFOUND` 才代表"确实没有 WinRE"）。工具自己出错时只提示、不拦。
     */
    val reNone = if (reSrcPath != null) reSrcRe?.empty == true else curRe?.empty == true
    /**
     * ★ "没查" ≠ "没查成"：还没选映像时 `curRe == null`（一次都没探过），
     *   这时候冒一句"工具出错"就是假警报（2026-10-27 用户截图里那条）。
     *   只有探测**真返回了失败码**才提示。
     */
    val reFailed = if (reSrcPath != null) reSrcRe?.failed == true else curRe?.failed == true
    /** 失败时把工具的码也显示出来（`WINRE-NOTFOUND` / `IMG-READ-FAIL` …），报问题能直接看。 */
    val reCode = ((reSrcRe ?: curRe)?.code ?: "").ifBlank { "-" }
    val winreBlocked = (writeRec || writeScript) && !hasReSource && !winreBusy && reNone
    val winreWarn = (writeRec || writeScript) && !hasReSource && !winreBusy && reFailed

    // ★ 32 位 + GPT **不再硬拦**：Win8 及以后 x86 可以走 32 位 UEFI（REPLY-54 §4）。
    //   Vista / Win7 x86 + GPT 才是物理不可能 —— 那一层由工具端 `boot.populate` 的
    //   `BOOT-ARCH-NO-UEFI` / `boot.plan` 的 `DENIED` 兜底，我们不在这里猜版本号。
    val bit32Warn = isGpt && archNorm == "x86"
    /**
     * 工具端说"判不出"（`arch_basis:"none"`，REPLY-22 起 `ARCH=0` 不再当 x86）。
     * 不拦 —— 只是提醒：这种盘装完可能起不来（也没括弧可显示）。
     */
    val archUnknown = curImageObj != null &&
        (curImageObj.optString("arch_basis") == "none" || archNorm.isBlank())

    val versionText = images.firstOrNull { it.optInt("index", -1) == wimIndex }?.let { o ->
        val nm = o.optString("display_name").ifBlank { o.optString("name") }
        val ar = archLabel(
            o.optString("arch"), wimPath.orEmpty() + " " + wimInner.orEmpty(),
            trusted = o.optString("arch_basis") in TrustedBasis,
        )
        s.depIndexItem(wimIndex, if (ar != null) s.depIndexArch(nm, ar) else nm)
    }.orEmpty()

    LaunchedEffect(run != null) { onRunningChange?.invoke(run != null) }

    // ── 释放页（跑起来就整页切过去）──
    // key 用 Boolean 而不是 run 本身：DeployRun 每推进一格都会 copy 成新对象，
    // 拿它当 targetState 会让动画在每个进度点上重启一次。
    var lastRun by remember { mutableStateOf<DeployRun?>(null) }
    if (run != null) lastRun = run
    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = run != null,
        transitionSpec = { slidePage(forward = targetState) },
        label = "deployRunPage",
    ) { isRun ->
    val running = if (isRun) (run ?: lastRun) else null
    if (running != null) {
        DeployRunPage(
            run = running,
            progress = progress,
            etaSec = etaSec,
            expandTotal = wimNeed,
            onExit = {
                run = null
                reload += 1
            },
            onCancel = { org.icdqcow2.core.ToolRunner.cancel() },
            onToast = onToast,
            onNext = onNext,
            nextLabel = primaryLabel,
            // 单独打开（不是向导）时：装完直接回首页，不再停在配置页
            onDone = if (onNext == null) onBack else null,
        )
    } else {
        SmallTitlePage(title = s.deploy, onBack = onBack, subtitle = subtitle) {
            val pickedNow = image
            if (pickedNow == null) {
                Note(s.depNeedDisk)
                PickImagePage(onPicked = { path, uri ->
                    image = path ?: uri
                    reload += 1
                })
            } else {
                CurrentFileCard(name = pickName(pickedNow), path = pickedNow, onToast = onToast)
            }

            // 正文：和分区管理同一个模板——卡片自己错峰弹入，正文块紧接着"高度展开"
            val picked = image
            if (picked != null) {
                when {
                    loading -> BlockEnter(2) { Note(s.partsLoading) }
                    err != null -> BlockEnter(2) { Note(err!!, warn = true) }
                    noTable -> BlockEnter(2) { Note(s.depNoTable, warn = true) }
                    else -> {
                        BlockEnter(2) {
                        GroupTitle(s.depLayout)
                        if (parts.isNotEmpty() && diskSectors > 0L) {
                            val segs = buildSegments(ImageRepo.PtInfo(table, usableEnd, parts), s, scans)
                            DiskBar(segs, usableEnd, selectedLba = targetLba) { seg ->
                                val p = seg.part
                                when {
                                    p == null -> onToast(s.depFreeTap)
                                    p.isDeployBlocked() -> onToast(s.depCantTarget)
                                    else -> {
                                        targetLba = p.lba
                                        formatFirst = (scans[p.lba]?.level ?: 1) >= 2
                                    }
                                }
                            }
                            Note(s.ptTableValue(table, fmtBytes(diskSectors * 512)))
                            Note(s.depBarHint)
                        } else {
                            Note(s.partsEmptyTable)
                        }

                        }
                        BlockEnter(3) {
                        GroupTitle(s.depTarget)
                        GroupCard {
                            Item(
                                s.depTarget,
                                target?.let { "#$targetIdx · ${partRoleLabel(it, s)} · ${fmtBytes(it.bytes)}" }
                                    ?: s.depTargetNone,
                            )
                        }

                        }
                        BlockEnter(4) {
                        GroupTitle(s.depWim)
                        GroupCard {
                            LinkItem(
                                s.depWim,
                                wimPath?.let { pickName(it) } ?: s.depWimNone,
                                onClick = { wimPicker.launch(arrayOf("*/*")) },
                            )
                            if (images.isNotEmpty()) {
                                LinkItem(
                                    s.depIndex,
                                    if (versionText.isNotEmpty()) versionText else "#$wimIndex",
                                    onClick = { indexPick = true },
                                )
                            }
                            if (wimNeed > 0L) Item(s.depNeed, fmtBytes(wimNeed))
                        }

                        GroupTitle(s.depBoot)
                        GroupCard {
                            Item(
                                s.depBoot,
                                if (writeBoot) s.depBootHint else null,
                                end = {
                                    Switch(checked = writeBoot, onCheckedChange = { writeBoot = it })
                                },
                            )
                            Item(
                                s.depRec,
                                when {
                                    !writeRec -> null
                                    recPart == null -> s.depRecSys
                                    else -> s.depRecHint
                                },
                                end = {
                                    Switch(
                                        checked = writeRec,
                                        onCheckedChange = {
                                            if (it && winreBlocked) {
                                                // 灰着也能点：点了说清楚为什么开不了
                                                onToast(s.depWinreMissing)
                                            } else {
                                                writeRec = it
                                                if (!it) writeScript = false
                                                if (it && !hasReSource && reFailed) {
                                                    onToast(s.depWinreWarn(reCode))
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                            // WinRE 来源：**只有当前映像里没有 WinRE 才需要用户手动挑**
                            // （自动跟随的那份，多一行只会让人以为要去选）。
                            // ★ 副标题**永远不能为空**：Miuix 的 `BasicComponent` 是 `heightIn(min=56.dp)`
                            //   + 内容撑高，没有副标题的行就只有 56dp，夹在两条两行高的邻居中间显得矮一截。
                            if (writeRec && (curWinre == null || reSrcPath != null)) LinkItem(
                                if (hasReSource) s.depWinreCur else s.depWinrePick,
                                when {
                                    winreBusy -> s.depWinreChecking
                                    reSrcHit != null -> buildString {
                                        append(reSrcPath.orEmpty())
                                        if (reSrcHit!!.sizeBytes > 0L) {
                                            append(" · ")
                                            append(fmtBytes(reSrcHit!!.sizeBytes))
                                        }
                                    }
                                    curWinre != null -> s.depWinreFollow(
                                        pickName(wimPath.orEmpty()),
                                        fmtBytes(curWinre!!.sizeBytes),
                                    )
                                    // 副标题只讲"能选什么"；含不含、查没查成由下面那条黄字说
                                    else -> s.depWinreScope
                                },
                                onClick = { rePicker.launch(arrayOf("*/*")) },
                            )
                            Item(
                                s.depRecScript,
                                if (!writeScript) s.depRecScriptHint
                                else s.depScriptWhere("Windows/System32/Recovery"),
                                enabled = writeRec,
                                end = {
                                    Switch(
                                        checked = writeScript,
                                        onCheckedChange = {
                                            when {
                                                !writeRec -> onToast(s.depRecScriptHint)
                                                it && winreBlocked -> onToast(s.depWinreMissing)
                                                else -> {
                                                    writeScript = it
                                                    if (it && !hasReSource && reFailed) {
                                                        onToast(s.depWinreWarn(reCode))
                                                    }
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                        // 已经知道这份映像没有 WinRE → 常驻说清楚。
                        // ★ 手动选过 WinRE 文件时看**那份**，别再拿当前映像的探测结果挂红字
                        //   （选了含 winre 的文件还报红，是误报）。
                        val pickedEmpty = reSrcPath != null && reSrcRe?.empty == true
                        val curEmpty = reSrcPath == null && curRe?.empty == true
                        // 只有真要写恢复环境时才提示；开关关着就别拿这条红字烦人
                        if ((writeRec || writeScript) && (pickedEmpty || curEmpty)) {
                            Note(s.depWinreNote, warn = true)
                        }
                        if (writeRec && recPart == null) Note(s.depRecSys)
                        if (winreWarn) Note(s.depWinreWarn(reCode), warn = true)

                        GroupTitle(s.fmtTitle)
                        GroupCard {
                            Item(
                                s.depFormat,
                                end = {
                                    Switch(
                                        checked = formatFirst,
                                        onCheckedChange = { formatFirst = it },
                                    )
                                },
                            )
                        }

                        }
                        BlockEnter(5) {
                        GroupTitle(s.depCheck)
                        GroupCard {
                            Item(s.depPartSize, target?.let { fmtBytes(it.bytes) } ?: "—")
                            if (targetScan?.hasFs == true) {
                                Item(s.depPartFree, fmtBytes(targetScan.freeBytes))
                            }
                            Item(s.depHostFree, fmtBytes(hostFree))
                            Item(s.depNeed, if (wimNeed > 0L) fmtBytes(needPart) else s.depNeedUnknown)
                        }
                        // 只有不够才说话（够的时候不占地方）
                        if (bit32Warn) {
                            Note(s.depBit32Warn, warn = true)
                        } else if (archUnknown) {
                            Note(s.depArchUnknown, warn = true)
                        }
                        when {
                            target != null && wimNeed > 0L && !partOk ->
                                Note(s.depPartSmall(fmtBytes(needPart)), warn = true)
                            wimNeed > 0L && !hostOk ->
                                Note(s.depHostSmall(fmtBytes(needHost)), warn = true)
                        }

                        // 灰着也能点：点了把原因说出来（32 位 + GPT 就属于这种）
                        }
                        BlockEnter(6) {
                        Action(text = primaryLabel ?: s.depStart, muted = winreBlocked) {
                            // 顺序按用户要求：先问映像，再问分区，再问空间
                            when {
                                wimPath == null -> onToast(s.depNeedWim)
                                // WinRE 没扫到：不做死拦，问一句"仍然尝试"——写入时工具会自己再找一遍
                                winreBlocked -> winreAskSkip = true
                                winreWarn -> onToast(s.depWinreWarn(reCode))
                                target == null -> onToast(s.depTargetNone)
                                wimNeed <= 0L -> onToast(s.depNeedUnknown)
                                !partOk -> onToast(s.depPartSmall(fmtBytes(needPart)))
                                !hostOk -> onToast(s.depHostSmall(fmtBytes(needHost)))
                                writeBoot && bootPart == null ->
                                    selfCheck = if (isMbr) s.roleSys else s.roleEsp
                                else -> confirm = true
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    // 合一盘：先选哪一条 install.wim/esd（x86 / x64 …）
    if (candPick && cands.isNotEmpty()) {
        val labels = cands.map { c ->
            s.depInnerItem(c.optString("inner_path"), c.optInt("count", 0))
        }
        ChoiceDialog(
            title = s.depPickInner,
            items = labels,
            cancelText = s.cancel,
            onDismiss = { candPick = false },
            onSelect = { i ->
                candPick = false
                cands.getOrNull(i)?.let { pickCandidate(it) }
            },
        )
    }

    // Windows 版本：下方弹卡片（架构用灰色后缀，中文全角/英文半角）
    if (indexPick && images.isNotEmpty()) {
        val labels = images.map { o ->
            val nm = o.optString("display_name").ifBlank { o.optString("name") }
            s.depIndexItem(o.optInt("index", 0), nm)
        }
        val suffixes = images.map { o ->
            archLabel(
                o.optString("arch"), wimPath.orEmpty() + " " + wimInner.orEmpty(),
                trusted = o.optString("arch_basis") in TrustedBasis,
            )?.let { s.depIndexArch("", it) }
        }
        ChoiceDialog(
            title = s.depIndex,
            items = labels,
            suffixes = suffixes,
            cancelText = s.cancel,
            onDismiss = { indexPick = false },
            onSelect = { i ->
                indexPick = false
                images.getOrNull(i)?.optInt("index", 1)?.let { wimIndex = it }
            },
        )
    }

    // WinRE 没扫到：问一句再走（工具端在 .so 里会假报"没有"，见 REQ-26；写入时会自己再找）
    if (winreAskSkip) {
        // ★ 2026-10-27 用户定：这里**只提示、不代劳**。
        //   弹一句说明，关掉后仍停在原页面（什么都不开始）；
        //   用户要么自己挑一份含 Winre.wim 的文件，要么自己把开关关掉，再点开始。
        ConfirmDialog(
            title = s.depWinreAskTitle,
            summary = s.depWinreNote,
            confirmText = s.depWinreAskGo,
            cancelText = s.cancel,
            onDismiss = { winreAskSkip = false },
            onConfirm = { winreAskSkip = false },
        )
    }

    // 分区自检：缺 ESP / 系统保留
    selfCheck?.let { what ->
        ConfirmDialog(
            title = s.depCheckMissTitle,
            summary = s.depCheckMissBody(what),
            confirmText = s.depStart,
            cancelText = s.cancel,
            onDismiss = { selfCheck = null },
            onConfirm = {
                selfCheck = null
                confirm = true
            },
        )
    }

    // 确认卡片：把要干的事一条条列清楚，用户点确认才动手
    if (confirm && target != null) {
        val wimNow = wimPath
        val summary = wimNow?.let { wn -> buildString {
            append(s.depLine(s.depWim, pickName(wn)))
            if (versionText.isNotEmpty()) {
                append("\n"); append(s.depLine(s.depVersion, versionText))
            }
            append("\n"); append(
                s.depLine(s.depTarget, "#$targetIdx · ${fmtBytes(target.bytes)}"),
            )
            if (wimNeed > 0L) {
                append("\n"); append(s.depLine(s.depNeed, fmtBytes(needPart)))
            }
            append("\n"); append(s.depLine(s.depFormat, if (formatFirst) s.depOn else s.depOff))
            append("\n"); append(s.depLine(s.depBoot, if (writeBoot) s.depOn else s.depOff))
            append("\n"); append(s.depLine(s.depRec, if (writeRec) s.depOn else s.depOff))
            if (writeRec) {
                append("\n"); append(
                    s.depLine(s.depRecScript, if (writeScript) s.depOn else s.depOff),
                )
            }
        } }.orEmpty()
        ConfirmDialog(
            title = s.depConfirmTitle,
            summary = summary,
            confirmText = s.depStart,
            cancelText = s.cancel,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                scope.launch {
                    org.icdqcow2.core.ToolEvents.clear()
                    etaMeter.reset()
                    etaSec = -1

                    // 目标分区统一叫 `Windows OS`；别的分区占着这名字的先清掉（用户：先改名再释放）
                    val others = parts.filter {
                        it.lba != target.lba &&
                            scans[it.lba]?.label?.equals(ImageRepo.LABEL_WIN, true) == true
                    }
                    val steps = buildList {
                        if (others.isNotEmpty()) add(s.depRunRename)
                        // 不格式化 = 目标盘里已有系统：先把它们搬进 Windows.old（用户 2026-10-29 定）
                        if (!formatFirst) add(s.depRunOld)
                        add(s.depRunApply)
                        if (writeRec) add(s.depRunRec)
                        if (writeBoot) add(s.depRunBoot)
                    }
                    run = DeployRun(steps, 0)
                    var step = 0
                    var failure: String? = null

                    // ① 重命名：清掉重名的旧卷标（NTFS 空卷标 = 清除）
                    if (others.isNotEmpty()) {
                        for (p in others) {
                            val fs = scans[p.lba]?.fs ?: "NTFS"
                            val r = ImageRepo.label(p.lba, fs, null, image)
                            if (!r.ok) {
                                failure = toolErrorText(s, r)
                                break
                            }
                        }
                        if (failure == null) {
                            step++
                            run = run?.copy(index = step)
                        }
                    }

                    // 每一步开始都把进度快照清掉：上一步的 100% 不能算到这一步头上
                    org.icdqcow2.core.ToolEvents.clear()
                    // ② 写系统：ISO 里的 WIM **直读**（REPLY-29 起 `--inner` 不落临时文件）
                    val wimSrc = wimPath
                    // 灌盘前先判两件事（架构位数、有没有 WinRE），别让用户白等 40 秒才失败
                    if (failure == null && wimSrc != null) {
                        if (isGpt) {
                            val probe = ImageRepo.wimIdentify(wimSrc, inner = wimInner)
                            val archNow = probe.data?.optJSONArray("images")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                                    .firstOrNull { it.optInt("index", -1) == wimIndex }
                            }?.optString("arch").orEmpty()
                            if (archNow.equals("x86", true)) failure = s.depBit32
                        }
                        if (failure == null && (writeRec || writeScript) && reSrcPath == null) {
                            val has = ImageRepo.winreProbe(wimSrc, wimInner, wimIndex)
                            if (has.empty) failure = s.depWinreNote
                        }
                    }
                    // 把目标分区根目录里的东西**全部**搬进 Windows.old（和 Windows 安装程序同款）。
                    // 不挑名字：wimlib 只要看到目录非空就可能报 46，我们干脆把根清空。
                    suspend fun makeWindowsOld(): Int {
                        val base = "$image!lba${target.lba}:"
                        runCatching {
                            org.icdqcow2.core.ToolRunner.exec(
                                "ntfs.mkdir",
                                mapOf("lba" to target.lba.toString(), "path" to "/Windows.old"),
                                imagePath = image,
                                writable = true,
                            )
                        }
                        val ls = org.icdqcow2.core.ToolRunner.exec(
                            "ntfs.list",
                            mapOf(
                                "lba" to target.lba.toString(),
                                "path" to "/",
                                "limit" to "2000",
                            ),
                            imagePath = image,
                            writable = false,
                        )
                        val names = ls.data?.optJSONArray("names")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                        }.orEmpty()
                        var moved = 0
                        val fails = ArrayList<String>()
                        for (n in names) {
                            if (n.equals("Windows.old", true)) continue
                            val mv = org.icdqcow2.core.ToolRunner.exec(
                                "move",
                                mapOf(
                                    "src" to "$base/$n",
                                    "dst" to "$base/Windows.old/$n",
                                    "recursive" to "true",
                                    "create_parents" to "true",
                                    "conflict" to "fail",
                                    "allow_host_delete" to "false",
                                ),
                                imagePath = null,
                                writable = true,
                                imageFd = -1,
                            )
                            if (mv.ok) moved++ else fails += "$n:${mv.code ?: "?"}"
                        }
                        // 静默失败最要命（用户 2026-10-29 就踩了：根目录还在、Windows.old 是空的）。
                        // 失败项直接说出来，别让用户以为搬好了。
                        if (fails.isNotEmpty()) {
                            android.util.Log.w("Deploy", "Windows.old 搬运失败: " + fails.joinToString())
                            onToast(s.depOldFail(fails.size, fails.first()))
                        }
                        return moved
                    }
                    // 独立一步：创建 Windows.old（不格式化时才有这一步）
                    if (failure == null && !formatFirst) {
                        run = run?.copy(index = step)
                        val moved = makeWindowsOld()
                        if (moved > 0) onToast(s.depOldMoved("Windows.old"))
                        // 搬完核对一遍：根目录里除了 Windows.old 还有东西 → 别急着灌，
                        // 不然 wimlib 又会写一半失败（用户 2026-10-29 的教训）。
                        val left = runCatching {
                            val ls2 = org.icdqcow2.core.ToolRunner.exec(
                                "ntfs.list",
                                mapOf("lba" to target.lba.toString(), "path" to "/", "limit" to "2000"),
                                imagePath = image,
                                writable = false,
                            )
                            ls2.data?.optJSONArray("names")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                                    .count { !it.equals("Windows.old", true) && it.isNotEmpty() }
                            } ?: 0
                        }.getOrDefault(0)
                        if (left > 0) failure = s.depOldLeft(left)
                        step++
                        run = run?.copy(index = step)
                    }
                    if (failure == null && wimSrc != null) {
                        var r = ImageRepo.applyWim(
                            lba = target.lba,
                            wim = wimSrc,
                            index = wimIndex,
                            format = formatFirst,
                            label = ImageRepo.LABEL_WIN,
                            inner = wimInner,
                            path = image,
                        )
                        // 只有 41（WIMLIB_ERR_NOTEMPTY：目录非空）才"清空再重试"；
                        // 46 是 libntfs-3g 底层错（脏卷/只读/空间），重试毫无意义（REPLY-46-48）。
                        val wcode = runCatching {
                            org.json.JSONObject(r.raw).optJSONObject("err")?.optInt("wimlib_code", -1)
                        }.getOrNull() ?: -1
                        if (!r.ok && !formatFirst && wcode == 41) {
                            val moved = makeWindowsOld()
                            if (moved > 0) onToast(s.depOldMoved("Windows.old"))
                            r = ImageRepo.applyWim(
                                lba = target.lba,
                                wim = wimSrc,
                                index = wimIndex,
                                format = formatFirst,
                                label = ImageRepo.LABEL_WIN,
                                inner = wimInner,
                                path = image,
                            )
                        }
                        if (!r.ok) {
                            failure = toolErrorText(s, r)
                        } else if (!formatFirst) {
                            // 没格式化 → 卷标得单独写
                            val lr = ImageRepo.label(
                                target.lba, targetScan?.fs ?: "NTFS", ImageRepo.LABEL_WIN, image,
                            )
                            if (!lr.ok) failure = toolErrorText(s, lr)
                        }
                    }
                    if (failure == null) {
                        step++
                        run = run?.copy(index = step)
                    }

                    // ③ 恢复分区
                    org.icdqcow2.core.ToolEvents.clear()
                    if (failure == null && writeRec) {
                        // ramdisk 要的 boot.sdi 在恢复分区里也得有一份（`boot.check` 的 rec.bootsdi
                        // 就是查这个）——工具端只吃本地文件，先从系统分区抽出来
                        val sdiTmp = java.io.File(TempDirs.stagingDir(ctx), "deploy-boot.sdi")
                        val sdiRel = if (isGpt) {
                            "/Windows/Boot/DVD/EFI/boot.sdi"
                        } else {
                            "/Windows/Boot/DVD/PCAT/boot.sdi"
                        }
                        // ★ 直接从映像里取（实测 3.4 s、3 MB），不碰系统分区：
                        //   这条 2026-10-27 晚换成 `wim.extract`，`--inner` 直读 ISO 成员。
                        var sdiOk = if (wimSrc != null) {
                            ImageRepo.wimExtract(
                                wim = wimSrc,
                                path = sdiRel,
                                dst = sdiTmp.absolutePath,
                                index = wimIndex,
                                inner = wimInner,
                            ).ok
                        } else {
                            false
                        }
                        // 兜底：映像里取不出来，就从刚灌好的卷里导（老路，3 MB）
                        if (!sdiOk) {
                            sdiOk = ImageRepo.ntfsExport(
                                target.lba, sdiRel, sdiTmp.absolutePath, image,
                            ).ok
                        }
                        // 抽取模式：ISO 成员读不了 → 用刚从 ISO 抽出来的 install.wim 当来源
                        val reSrc = reSrcPath ?: wimSrc!!
                        val rp = ImageRepo.recoveryPopulate(
                            lba = recPart?.lba ?: target.lba,
                            // RE 来源：单独选的那份优先（可能另一份 ISO/ESD/WIM）
                            wim = reSrc,
                            // `--winre-index` = WinRE 在哪条映像里；另选来源但没探成时给 1，让工具自己找
                            index = reSrcHit?.index
                                ?: (if (reSrcPath != null) 1 else curWinre?.index ?: wimIndex),
                            enableScript = writeScript,
                            osLba = target.lba,
                            system = recPart == null,
                            inner = if (reSrcPath != null) null
                            else (curWinre?.inner ?: wimInner),
                            bootsdi = if (sdiOk) sdiTmp.absolutePath else null,
                            winrePath = (reSrcHit ?: curWinre)?.internalPath,
                            // ★ 独立恢复分区：先格式化再填（工具端 format 默认 false）。
                            //   `recovery.populate` 填充后要复核，旧内容会让它失败。
                            //   系统分区那条（recPart == null）**不能格**，那是 Windows 本尊。
                            format = recPart != null,
                            path = image,
                        )
                        runCatching { sdiTmp.delete() }
                        if (!rp.ok) failure = toolErrorText(s, rp)
                        else {
                            step++
                            run = run?.copy(index = step)
                        }
                    }

                    // ④ 引导：一条 `boot.populate` 铺完（REPLY-20：ESP 格式化 + bootmgfw/bootx64
                    //    + Fonts + boot.sdi + BCD；BIOS 则 SR + 活动标志 + bootmgr + 内置 MBR/VBR 码）
                    org.icdqcow2.core.ToolEvents.clear()
                    var bootNote: String? = null
                    // 32 位 + GPT 以前在这里硬拦；现在交给工具端判（它能读 WIM 的真实版本号）：
                    //   Vista/Win7 x86 + GPT → `BOOT-ARCH-NO-UEFI`
                    //   Win8+ x86 + GPT      → `BOOT-FORMAT-NEEDS-CONFIRM`（带 force 再来一次）
                    if (failure == null && writeBoot) {
                        var bp = ImageRepo.bootPopulate(
                            sysLba = target.lba,
                            espLba = if (isGpt) espPart?.lba else null,
                            srLba = if (isGpt) null else (srPart ?: target).lba,
                            path = image,
                        )
                        // 32 位 + GPT：工具端判"能做但有风险"（BOOT-FORMAT-NEEDS-CONFIRM），
                        // 带 force 再来一次就是用户已经确认过的意思。
                        if (!bp.ok && bp.code == "BOOT-FORMAT-NEEDS-CONFIRM") {
                            bp = ImageRepo.bootPopulate(
                                sysLba = target.lba,
                                espLba = if (isGpt) espPart?.lba else null,
                                srLba = if (isGpt) null else (srPart ?: target).lba,
                                force = true,
                                path = image,
                            )
                        }
                        if (!bp.ok) {
                            failure = toolErrorText(s, bp)
                        } else {
                            // 工具端内部已经跑过一次 boot.check，直接把结论拿过来用
                            val bad = bp.data?.optJSONObject("boot_check")?.optInt("required_fail", 0) ?: 0
                            bootNote = if (bad > 0) s.depRunBootFail(bad) else s.depRunBootOk
                        }
                    }

                    org.icdqcow2.core.ToolEvents.clear()
                    run = run?.copy(done = failure == null, error = failure, note = bootNote)
                    if (failure == null) onToast(s.depDone("#$targetIdx · ${fmtBytes(target.bytes)}"))
                }
            },
        )
    }
    }
}

/** 一次释放的步骤表 + 进度位置。 */
internal data class DeployRun(
    val steps: List<String>,
    val index: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
    /** 收尾提示（引导自检结果之类），不是错误。 */
    val note: String? = null,
)

/**
 * 释放页：进度条 + 步骤。跑的时候整页只显示这个，跑完/失败给「返回」。
 *
 * 进度数据来自 `ToolEvents.progress`（工具端事件流）；ETA 由 [org.icdqcow2.core.EtaMeter]
 * 兜底用最近 20 秒的吞吐率算；工具端 `eta` 可用时优先用工具端的（REQ-68）。
 */
@Composable
private fun DeployRunPage(
    subtitle: String? = null,
    primaryLabel: String? = null,
    onNext: (() -> Unit)? = null,
    /** 跑完时那颗按钮的文案（向导里是「下一步」）。 */
    nextLabel: String? = null,
    /** 跑完之后去哪儿（单独打开 = 回首页）；null = 老行为（回表单）。 */
    onDone: (() -> Unit)? = null,
    run: DeployRun,
    progress: org.icdqcow2.core.ToolEvents.Progress?,
    etaSec: Int,
    /** 表单里那个「映像展开大小」（字节）；进度按它换算成同口径显示。 */
    expandTotal: Long = 0L,
    onExit: () -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
) {
    val s = LocalStrings.current
    var askCancel by remember { mutableStateOf(false) }

    /** 还在跑（没完成、也没出错）：这时返回必须先确认，否则会"页面没了、任务还在写"。 */
    val active = !run.done && run.error == null
    // 系统返回键也拦在这层：写盘过程中直接退出会把任务留在后台继续写
    BackHandler(enabled = active) { askCancel = true }

    SmallTitlePage(
        title = s.depRunTitle,
        onBack = { if (active) askCancel = true else onExit() },
        subtitle = subtitle,
    ) {
        val p = progress
        val expandNow = if (expandTotal > 0L) expandTotal else (p?.totalBytes ?: 0L)

        // ── 进度合成（2026-10-27 用户定：**读取、写入各占 50%**）──
        //   · 工具端一个任务里会换 op（`read`/`decompress`、`write`/`write_file`…），
        //     `cur_bytes` 是**按 op** 计的，换 op 就归零 → 直接显示会"清零/倒退"；
        //   · 所以按 op 分两条线，各自取最大值（单调不减）；
        //   · 两条都出现过 → 各占 50%；只出现一条（`wim.apply` 只有解压）
        //     → 它自己占满 100%，否则后半段会一直卡在 50%；
        //   · 每换一步（run.index 变）才重置。
        val rMax = remember(run.index) { mutableStateOf(0) }
        val wMax = remember(run.index) { mutableStateOf(0) }
        val seenR = remember(run.index) { mutableStateOf(false) }
        val seenW = remember(run.index) { mutableStateOf(false) }
        val op = p?.op.orEmpty().lowercase()
        // 有字节数就用字节数算（工具端换 op 时 pct 会重置/失真）；
        // 没有字节数才退回它给的 pct。
        val opPct = run {
            val tb = p?.totalBytes ?: 0L
            val cb = p?.curBytes ?: 0L
            if (tb > 0L && cb >= 0L) ((cb * 100) / tb).toInt().coerceIn(0, 100)
            else (p?.pct ?: -1).coerceIn(0, 100)
        }
        androidx.compose.runtime.LaunchedEffect(op, opPct, run.index) {
            if (opPct < 0) return@LaunchedEffect
            when (op) {
                "read", "decompress" -> {
                    seenR.value = true
                    if (opPct > rMax.value) rMax.value = opPct
                }
                "write", "write_file", "alloc_cluster" -> {
                    seenW.value = true
                    if (opPct > wMax.value) wMax.value = opPct
                }
            }
        }
        val globalPct = p?.overallPct ?: -1
        val rawPct = when {
            seenR.value && seenW.value -> (rMax.value + wMax.value) / 2   // 各 50%
            seenR.value -> rMax.value
            seenW.value -> wMax.value
            else -> 0        // 还没有可用进度（正在估算）→ 0%，不要挂在 100%
        }
        // ★ 有工具端的 overall_pct 就用它（REPLY-49：解压+写入的真实总量，单调不减）；
        //   没有再退回自己按 op 拼的值。
        val blended = if (globalPct in 0..100) globalPct else rawPct
        // 没真正跑完之前最多显示 95%：以前会 50% 直接跳 100%
        val pct = if (run.done || run.error != null) blended else blended.coerceAtMost(95)
        val done = if (expandNow > 0L) expandNow * pct / 100 else 0L
        // 统一进度模板（圆盘 + 蓝边 + 圆心百分比 + 内下方时间 + 外下方当前文件）
        RunProgressBlock(
            pct = pct,
            timeText = progressTimeText(progress?.elapsedMs ?: 0L, etaSec),
            phase = progressPhaseText(progress?.phase),
            countsText = if (expandNow > 0L && done > 0L) {
                "${fmtBytes(done)}/${fmtBytes(expandNow)}"
            } else {
                null
            },
            item = progress?.item?.takeIf { it.isNotBlank() },
        )

        GroupCard {
            run.steps.forEachIndexed { i, name ->
                val mark = when {
                    run.done || i < run.index -> "✓"
                    i == run.index && run.error == null -> "…"
                    else -> ""
                }
                Item(name, mark)
            }
        }

        val failure = run.error
        val note = run.note
        when {
            failure != null -> Note(failure, warn = true)
            run.done -> {
                Note(s.depRunDone)
                if (note != null) Note(note)
            }
            etaSec > 0 -> Note(
                s.depRunEta(String.format("%d:%02d", etaSec / 60, etaSec % 60)),
            )
            else -> Note(s.depRunEtaCalc)
        }

        if (run.done || failure != null) {
            val canNext = run.done && onNext != null
            Action(text = if (canNext) (nextLabel ?: s.depRunBack) else s.depRunBack) {
                when {
                    canNext -> onNext?.invoke()
                    run.done && onDone != null -> onDone.invoke()
                    else -> onExit()
                }
            }
        } else {
            Action(text = s.depRunAbort, danger = true) { askCancel = true }
        }
    }

    if (askCancel) {
        // 两个按钮的语义必须生成说明文本（用户 2026-10-29）：
        //   取消 = 不中止了，继续释放（关掉这张卡）
        //   退出 = 真中止，并且离开这个页面
        ConfirmDialog(
            title = s.depRunCancelTitle,
            summary = s.depRunCancelBody,
            confirmText = s.depRunQuit,
            cancelText = s.cancel,
            danger = true,
            onDismiss = { askCancel = false },   // 系统返回 = 取消（继续释放）
            onConfirm = {
                askCancel = false
                onCancel()
                onToast(s.depRunCancelled)
                onExit()
            },
        )
    }
}

/** `arch_basis` 里**可以当结论用**的那两种（REPLY-22：content=按内容、media=按 ISO 介质元数据）。 */
private val TrustedBasis = setOf("content", "media")

/** 这块不能装系统：ESP / MSR / 0x27（系统保留、恢复）/ 扩展容器。 */
internal fun ImageRepo.Part.isDeployBlocked(): Boolean {
    val t = typeGuid.uppercase()
    return isContainer() || mbri == 0x27 || mbri == 0xEF || mbri == 0x12 ||
        t.startsWith("C12A7328") || t.startsWith("E3C9E316") || t.startsWith("DE94BBA4")
}

/** 导出文件：镜像 → 手机。 */
@Composable
fun ExportPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    var verify by remember { mutableStateOf(false) }

    SmallTitlePage(title = "导出文件", onBack = onBack) {
        GroupCard {
            Item("当前镜像", "未选择")
            Item("镜像内路径", "未选择（在文件页长按选）")
            Item("导出到", "下载/Icdqcow2/")
        }
        GroupCard {
            Item(
                "导出后校验哈希",
                if (verify) "读完再算一遍，确认没读坏" else "只做 CRC 校验",
                end = { Switch(checked = verify, onCheckedChange = { verify = it }) },
            )
        }
        Action("开始导出", enabled = false) { onToast("请先选择要导出的文件") }
    }
}

/**
 * 把一个映像的架构显示成 `x86` / `x64` / `ARM64`。
 *
 * 数据来自 `wim.identify` 的 `arch`（WIM XML 的 `<ARCH>` 原值）：`9`=x64、`0`=x86、
 * `5`=ARM、`12`=ARM64。**但微软官方镜像普遍填 `0`**（实测那份 21H2 x64 ISO 报的就是 `0`），
 * 所以"0/x86"这种情况再看一眼 [hint]（ISO 文件名、`inner_path` 像 `/x64/sources/install.wim`）——
 * 有 `x64`/`amd64` 字样就以它为准。都不确定就返回 null（不显示括号，别瞎猜）。
 */
internal fun archLabel(raw: String?, hint: String?, trusted: Boolean = false): String? {
    val a = raw?.trim()?.lowercase().orEmpty()
    val mapped = when (a) {
        "9", "x64", "amd64" -> "x64"
        "5", "arm" -> "ARM"
        "12", "arm64" -> "ARM64"
        "0", "x86" -> "x86"
        else -> null
    }
    // 工具端给了结论（REPLY-22 起 `arch_basis` 是 content/media）就**直接信它**，连 x86 也信。
    // ★ 2026-10-27：**彻底不看文件名**（`hint` 已废弃）。踩过两次：
    //   `19044..._x64.iso` 名字写 x64、内容是 32 位 → 界面显示 (x64)、
    //   同时又弹"判不出架构"，自相矛盾，还害得"GPT 只装 64 位"那条拦不住。
    //   工具端说判不出（`arch` 空 / basis=none）就**什么都不显示**，让黄字去解释。
    if (trusted) return mapped
    return mapped
}
