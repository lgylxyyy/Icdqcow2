package org.icdqcow2.ui.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 注册表只读浏览（`reg.list` / `reg.get`）。
 *
 * 为什么只读：写注册表改错会让系统无法启动（REQ-60 §2.2 安全提示）。
 * 工具端已支持 `reg.set` / `reg.del`，但本轮只暴露读取能力；
 * 写入要等二次确认 + 自动备份那套做完再开。
 *
 * 路径语法：`<hive>\<子键>\...`，hive 取 SYSTEM / SOFTWARE / SAM / SECURITY / DEFAULT。
 */
@Composable
fun RegistryPage(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    initialImage: String? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var image by remember { mutableStateOf(initialImage) }
    var snap by remember { mutableStateOf<DiskSnap?>(null) }
    var winLba by remember { mutableStateOf<Long?>(null) }
    /** 当前浏览的键路径（空 = 还没进 hive）。 */
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RegItem>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    /** 点值项时拉一次 `reg.get` 的结果：类型 → 内容。 */
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 选盘：拿 Windows 分区 LBA（所有 hive 都在系统分区里）
    LaunchedEffect(image) {
        val img = image ?: run {
            snap = null
            winLba = null
            return@LaunchedEffect
        }
        loading = true
        err = null
        val r = runCatching { loadSnap(img) }.getOrNull()
        snap = r
        winLba = r?.windows()?.lba
        if (r?.pt == null) err = s.brNoTable else if (winLba == null) err = s.regNoWindows
        path = ""
        items = emptyList()
        loading = false
    }

    /** 进入某个 hive 根或子键。 */
    fun browse(p: String) {
        val img = image ?: return
        val lba = winLba ?: return
        loading = true
        err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { ImageRepo.regList(lba = lba, path = p, img = img) }
            loading = false
            if (!r.ok) {
                err = toolErrorText(s, r)
                return@launch
            }
            val arr = r.data?.optJSONArray("items")
            val list = ArrayList<RegItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list += RegItem(
                        name = o.optString("name"),
                        isKey = o.optString("kind") == "key",
                        type = o.optString("type"),
                        bytes = o.optLong("bytes", 0L),
                    )
                }
            }
            // 子键在前、值在后；同类按名字不分大小写
            items = list.sortedWith(
                compareByDescending<RegItem> { it.isKey }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            truncated = r.data?.optBoolean("truncated", false) ?: false
            path = p
        }
    }

    /** 点值：拉一次 `reg.get` 看内容。 */
    fun readValue(p: String, name: String) {
        val img = image ?: return
        val lba = winLba ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ImageRepo.regGet(lba = lba, path = p + "\\" + name, img = img)
            }
            if (!r.ok) {
                onToast(toolErrorText(s, r))
                return@launch
            }
            val d = r.data
            val v = d?.optString("value").orEmpty().ifBlank { d?.optString("value_hex").orEmpty() }
            detail = (d?.optString("type").orEmpty()) to v
        }
    }

    SmallTitlePage(title = s.regTitle, onBack = onBack) {
        BlockEnter(0) {
            GroupTitle(s.regImage)
            if (image == null) {
                Note(s.regNeedImage)
                PickImagePage(onPicked = { p, uri -> image = p ?: uri })
            } else {
                CurrentFileCard(name = pickName(image!!), path = image, onToast = onToast)
            }
        }

        if (image != null && winLba != null) {
            BlockEnter(1) {
                GroupTitle(s.regHive)
                GroupCard {
                    ImageRepo.REG_HIVES.forEach { hive ->
                        Item(hive, null, onClick = { browse(hive) })
                    }
                }
            }

            if (path.isNotBlank()) {
                BlockEnter(2) {
                    GroupTitle(s.regPath)
                    GroupCard {
                        Item(
                            path,
                            s.regItemCount(items.size),
                            end = {
                                Text(
                                    s.regUp,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            },
                            onClick = { browse(path.substringBeforeLast('\\', "")) },
                        )
                    }
                }

                BlockEnter(3) {
                    GroupTitle(s.regItems)
                    when {
                        loading -> Note(s.partsLoading)
                        err != null -> Note(err!!, warn = true)
                        items.isEmpty() -> Note(s.regEmpty)
                        else -> GroupCard {
                            items.forEach { item ->
                                Item(
                                    item.name,
                                    if (item.isKey) s.regKindKey else item.type + " · " + item.bytes + " B",
                                    onClick = {
                                        if (item.isKey) browse(path + "\\" + item.name)
                                        else readValue(path, item.name)
                                    },
                                )
                            }
                        }
                    }
                    if (truncated) Note(s.regTruncated, warn = true)
                }
            }
        } else if (image != null && err != null) {
            BlockEnter(1) { Note(err!!, warn = true) }
        }

        detail?.let { pair ->
            BlockEnter(4) {
                GroupTitle(s.regValue)
                GroupCard {
                    Item(pair.first.ifBlank { s.regBinary }, pair.second.take(512))
                }
            }
        }

        BlockEnter(5) {
            Note(s.regReadOnlyNote, warn = true)
        }
    }
}

/** 注册表列表项：子键（kind=key）或值（带类型与字节数）。 */
private data class RegItem(
    val name: String,
    val isKey: Boolean,
    val type: String,
    val bytes: Long,
)
