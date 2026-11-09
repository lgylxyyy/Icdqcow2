package org.icdqcow2.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.icdqcow2.core.LicenseCatalog
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开源许可证列表。
 *
 * 合规要求：LGPL / GPL 组件必须能查看到**许可证全文**，只列组件名不够。
 * 每项是一张卡片，点进去看全文（[LicenseTextPage]）。
 *
 * 清单在 [LicenseCatalog] 一处维护，界面不写死组件。
 */
@Composable
fun LicensesPage(
    onBack: () -> Unit,
    onOpenText: (file: String, title: String) -> Unit,
) {
    val s = LocalStrings.current
    val core = LicenseCatalog.entries.filter { it.bundled }
    val framework = LicenseCatalog.entries.filterNot { it.bundled }

    SmallTitlePage(title = s.licensesTitle, onBack = onBack) {
        BlockEnter(0) {
            GroupTitle(s.licensesCore)
            GroupCard {
                core.forEach { e ->
                    Item(
                        e.name,
                        e.license + " · " + s.licPurpose(e.purposeKey),
                        onClick = { onOpenText(e.assetFile, e.name) },
                    )
                }
            }
        }
        BlockEnter(1) {
            GroupTitle(s.licensesFramework)
            GroupCard {
                framework.forEach { e ->
                    Item(
                        e.name,
                        e.license + " · " + s.licPurpose(e.purposeKey),
                        onClick = { onOpenText(e.assetFile, e.name) },
                    )
                }
            }
        }
        BlockEnter(2) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(
                    s.licensesComplianceNote,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 许可证全文页。文本从 assets 读，IO 线程 + remember 缓存。 */
@Composable
fun LicenseTextPage(
    file: String,
    title: String,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    var text by remember(file) { mutableStateOf<String?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    LaunchedEffect(file) {
        val t = withContext(Dispatchers.IO) { LicenseCatalog.readText(ctx, file) }
        if (t == null) failed = true else text = t
    }

    SmallTitlePage(title = title, onBack = onBack) {
        BlockEnter(0) {
            when {
                failed -> Item(s.licensesLoadFailed, null)
                text == null -> Item(s.partsLoading, null)
                else -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text!!,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
