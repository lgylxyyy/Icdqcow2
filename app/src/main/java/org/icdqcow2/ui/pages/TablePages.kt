package org.icdqcow2.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage

/**
 * 导出分区表（REQ-14：`part.export`）。
 *
 * 只导**表**：LBA / 大小 / 类型 / 分区 GUID / 名字 / 属性；分区里的数据一个字节都不带。
 * 工具端把规范 JSON 写成文件，App 再原样写到用户选的位置
 * （SAF `CreateDocument`，不需要任何存储权限；写的是工具给的原文本，不重新序列化）。
 */
@Composable
fun ExportTablePage(onBack: () -> Unit, onToast: (String) -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var image by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var noTable by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<ImageRepo.TableFile?>(null) }
    var parts by remember { mutableStateOf<List<ImageRepo.Part>>(emptyList()) }

    // 「保存到…」：系统另存为，拿到的 uri 直接写字节
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val text = file?.raw
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val name = runCatching { uri.lastPathSegment?.substringAfterLast('/') }.getOrNull() ?: "…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                }.getOrDefault(false)
            }
            onToast(if (ok) s.ptxSaved(name) else s.ptxSaveFail)
        }
    }

    LaunchedEffect(image, reload) {
        val path = image ?: return@LaunchedEffect
        loading = true
        err = null
        noTable = false
        file = null
        parts = emptyList()
        // 先让工具端写成规范 JSON（缓存目录），读回来既当预览也当保存内容
        val tmp = File(ctx.cacheDir, "pt-export.json")
        runCatching { tmp.delete() }
        val r = ImageRepo.exportTable(out = tmp.absolutePath, path = path)
        if (!r.ok) {
            // 空盘（没有表）就是 PART-READ-FAIL，别当成"文件坏了"
            if (r.code == "PART-READ-FAIL") noTable = true else err = toolErrorText(s, r)
        } else {
            val text = runCatching { tmp.readText() }.getOrNull()
            val parsed = text?.let { ImageRepo.parseTableFile(it) }
            if (parsed == null) {
                err = s.ptxBad
            } else {
                file = parsed
                parts = ImageRepo.parseTableParts(r.data?.optJSONObject("pt")).sortedBy { it.lba }
            }
        }
        loading = false
    }

    SmallTitlePage(title = s.ptxTitle, onBack = onBack) {
        // 模板（与分区管理一致）：选文件 ↔ 卡片瞬间替换；正文用高度展开跟上，
        // 只有「当前操作的文件」那张卡自己错峰弹入。
        val picked = image
        if (picked == null) {
            PickImagePage(onPicked = { path, uri ->
                image = path ?: uri
                reload += 1
            })
        } else {
            CurrentFileCard(
                name = pickName(picked),
                path = picked,
                onToast = onToast,
            )
        }

        // 模板：卡片是第 0 块，下面每块依次 70ms 错峰、自下而上浮现。
        // 每块都套 key(...)，所以数据晚到（导出工具返回后才有的卡片）也会播一次。
        if (picked != null) {
            BlockEnter(1) {
                key("ptxHead") {
                    file?.let { f ->
                        GroupCard {
                            Item(s.ptxTable, s.ptxTableValue(f.table, f.parts))
                            Item(s.ptxNeed, s.ptxNeedValue(f.needGb))
                        }
                    }
                }
            }

            BlockEnter(2) {
                key("ptxState") {
                    when {
                        loading -> Note(s.partsLoading)
                        err != null -> Note(err!!, warn = true)
                        noTable -> Note(s.ptxNone, warn = true)
                        file != null -> {
                            GroupTitle("${s.partsList}（${parts.size}）")
                            GroupCard {
                                parts.forEachIndexed { i, p ->
                                    Item(
                                        "#${i + 1} · ${fmtBytes(p.bytes)}",
                                        "${partRoleLabel(p, s)} · LBA ${p.lba}",
                                    )
                                }
                            }
                            Note(s.ptxNoData)
                        }
                    }
                }
            }

            if (file != null) {
                BlockEnter(3) {
                    key("ptxSave") {
                        val f = file
                        Action(
                            text = s.ptxSave,
                            enabled = true,
                            muted = f == null,
                        ) {
                            if (f == null) onToast(s.ptxNone) else saver.launch(saveName(picked.orEmpty(), s))
                        }
                    }
                }
            }
        }
    }
}

/** 默认文件名：`<镜像名>.parttable.json`（SAF 里用户还能改）。 */
private fun saveName(imagePath: String, s: org.icdqcow2.ui.Strings): String {
    val base = when {
        imagePath.startsWith("content://") -> Uri.decode(imagePath.substringAfterLast('/'))
        else -> ImageRepo.displayName(imagePath) ?: "parttable"
    }.removeSuffix(".qcow2").ifBlank { "parttable" }
    return "$base.${s.ptxExt}"
}