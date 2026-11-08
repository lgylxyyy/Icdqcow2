package org.icdqcow2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.icdqcow2.core.I18n
import kotlinx.coroutines.launch
import org.icdqcow2.core.SettingsStore
import org.icdqcow2.core.StorageAccess
import org.icdqcow2.core.ThemeMode
import org.icdqcow2.ui.pages.Choice
import org.icdqcow2.ui.pages.ChoiceCard
import org.icdqcow2.ui.pages.CardMenuPopup
import org.icdqcow2.ui.pages.OptionRow
import org.icdqcow2.ui.pages.rememberAllFilesGranted
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：外观、特效、语言、文件操作、诊断、关于。
 *
 * 单选（外观 / 语言）统一用 [ChoiceCard]（左侧对勾那种），与建盘页的保存位置卡一致。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onToast: (String) -> Unit,
    /** 从设置页进二级页（控制台 / 错误码手册 / 日志）——主页不再有这几个入口。 */
    onOpen: (String) -> Unit = {},
) {
    val s = LocalStrings.current
    val theme by settings.themeMode.collectAsState()
    val lang by settings.language.collectAsState()
    val effects by settings.visualEffects.collectAsState()
    val blurSupported = settings.blurSupported
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmDelete by remember { mutableStateOf(settings.confirmDelete) }
    var showHidden by remember { mutableStateOf(settings.showHidden) }
    // 点「管理所有文件」先弹说明卡片，再跳系统设置（系统本身不会有运行时弹窗）
    var permDlg by remember { mutableStateOf(false) }
    val allFilesGranted = rememberAllFilesGranted()
    val historyCount by settings.historyCount.collectAsState()
    var historyDlg by remember { mutableStateOf(false) }
    // 开源许可（含 UnRAR：REPLY-49 明确要求随二进制附 license 说明）
    var showLicense by remember { mutableStateOf(false) }

    LargeTitlePage(title = s.settingsTitle) {
        section(s.secAppearance)
        item {
            ChoiceCard(
                options = listOf(
                    Choice(s.themeSystem, s.themeSystemSummary),
                    Choice(s.themeLight, s.themeLightSummary),
                    Choice(s.themeDark, s.themeDarkSummary),
                ),
                selected = when (theme) {
                    ThemeMode.SYSTEM -> 0
                    ThemeMode.LIGHT -> 1
                    ThemeMode.DARK -> 2
                },
            ) { i ->
                settings.setThemeMode(
                    when (i) {
                        1 -> ThemeMode.LIGHT
                        2 -> ThemeMode.DARK
                        else -> ThemeMode.SYSTEM
                    },
                )
            }
        }

        section(s.secEffects)
        card {
            BasicComponent(
                title = s.effectsTitle,
                summary = when {
                    !blurSupported -> s.effectsUnsupported
                    effects -> s.effectsOn
                    else -> s.effectsOff
                },
                enabled = blurSupported,
                endActions = {
                    Switch(
                        checked = effects,
                        onCheckedChange = { if (blurSupported) settings.setVisualEffects(it) },
                        enabled = blurSupported,
                    )
                },
            )
        }

        section(s.secLanguage)
        item {
            val langs = I18n.languages()
            ChoiceCard(
                options = listOf(Choice(s.langSystem, s.langSystemSummary)) +
                    langs.map { Choice(it.label, null) },
                selected = if (lang == I18n.SYSTEM_LANG) 0
                else (langs.indexOfFirst { it.code == lang } + 1).coerceAtLeast(0),
            ) { i ->
                settings.setLanguage(if (i == 0) I18n.SYSTEM_LANG else langs[i - 1].code)
            }
        }

        section(s.secPerm)
        card {
            BasicComponent(
                title = s.settingsPermTitle,
                summary = if (allFilesGranted) s.settingsPermGranted else s.settingsPermMissing,
                endActions = {
                    if (!allFilesGranted) {
                        top.yukonga.miuix.kmp.basic.Text(
                            s.settingsPermGrant,
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        top.yukonga.miuix.kmp.basic.Text(
                            "✓",
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                onClick = { permDlg = true },
            )
        }

        if (permDlg) item {
            org.icdqcow2.ui.pages.ConfirmDialog(
                title = s.permDlgTitle,
                summary = s.permDlgBody,
                confirmText = if (allFilesGranted) s.partsPermRestartAction else s.permDlgGo,
                cancelText = s.permDlgLater,
                danger = false,
                onDismiss = { permDlg = false },
                onConfirm = {
                    permDlg = false
                    if (StorageAccess.hasAllFiles()) {
                        StorageAccess.restartApp(ctx)
                    } else if (!StorageAccess.requestAllFiles(ctx)) {
                        onToast(s.permManual)
                    }
                },
            )
        }

        section(s.secFiles)
        card {
            val values = listOf(0, 3, 5, 7, 9)
            val labels = values.map { v -> if (v == 0) s.historyNone else s.historyCount(v) }
            val sel = values.indexOf(historyCount).coerceAtLeast(0)
            Box {
                OptionRow(
                    title = s.settingsHistoryCount,
                    summary = if (historyCount == 0) s.historyNone
                    else s.settingsHistorySummary(historyCount),
                    value = labels[sel],
                    onClick = { historyDlg = !historyDlg },
                )
                CardMenuPopup(
                    show = historyDlg,
                    items = labels,
                    selectedIndex = sel,
                    onDismiss = { historyDlg = false },
                    onSelected = { i ->
                        settings.setHistoryCount(values[i])
                        historyDlg = false
                    },
                )
            }

            // Shizuku：借 shell 读 /storage/emulated、/data 这类普通应用进不去的位置
            var szTick by remember { mutableIntStateOf(0) }
            LaunchedEffect(szTick) {
                if (org.icdqcow2.core.ShizukuShell.available() &&
                    !org.icdqcow2.core.ShizukuShell.granted()
                ) {
                    kotlinx.coroutines.delay(1000)
                    szTick++
                }
            }
            BasicComponent(
                title = s.szTitle,
                summary = when {
                    !org.icdqcow2.core.ShizukuShell.available() -> s.szOff
                    org.icdqcow2.core.ShizukuShell.granted() -> s.szOn
                    else -> s.szAsk
                },
                onClick = {
                    when {
                        !org.icdqcow2.core.ShizukuShell.available() -> onToast(s.szOff)
                        org.icdqcow2.core.ShizukuShell.granted() -> onToast(s.szOn)
                        else -> org.icdqcow2.core.ShizukuShell.request()
                    }
                },
            )

            BasicComponent(
                title = s.confirmDelete,
                summary = s.confirmDeleteSummary,
                endActions = {
                    Switch(
                        checked = confirmDelete,
                        onCheckedChange = {
                            confirmDelete = it
                            settings.confirmDelete = it
                        },
                    )
                },
            )
            val deleteToTrash by settings.deleteToTrash.collectAsState()
            BasicComponent(
                title = s.delTrash,
                summary = s.delTrashSummary,
                endActions = {
                    Switch(
                        checked = deleteToTrash,
                        onCheckedChange = { settings.setDeleteToTrash(it) },
                    )
                },
            )
            val showTrash by settings.showTrash.collectAsState()
            BasicComponent(
                title = s.filesShowTrash,
                summary = s.showTrashSummary,
                endActions = {
                    Switch(
                        checked = showTrash,
                        onCheckedChange = { settings.setShowTrash(it) },
                    )
                },
            )
            BasicComponent(
                title = s.showHidden,
                summary = s.showHiddenSummary,
                endActions = {
                    Switch(
                        checked = showHidden,
                        onCheckedChange = {
                            showHidden = it
                            settings.showHidden = it
                        },
                    )
                },
            )
            val conflictPolicy by settings.conflictPolicyFlow.collectAsState()
            val autoRename = conflictPolicy == "rename"
            BasicComponent(
                title = s.conflictAuto,
                summary = if (autoRename) s.conflictAutoOn else s.conflictAutoOff,
                endActions = {
                    Switch(
                        checked = autoRename,
                        onCheckedChange = {
                            settings.setConflictPolicy(if (it) "rename" else "ask")
                        },
                    )
                },
            )
        }

        section(s.secDiag)
        card {
            BasicComponent(title = s.logLevel, summary = s.logLevelSummary, onClick = { onToast(s.logLevel) })
            BasicComponent(title = s.exportLog, summary = s.exportLogSummary, onClick = { onToast(s.exportLog) })
            // 这三项从主页「维护」组搬过来（主页不再显示）
            BasicComponent(title = s.console, summary = s.consoleSummary, onClick = { onOpen("console") })
            BasicComponent(title = s.errBook, summary = s.errBookSummary, onClick = { onOpen("errors") })
            BasicComponent(title = s.logs, summary = s.logsSummary, onClick = { onOpen("logs") })
        }

        section(s.secAbout)
        card {
            BasicComponent(title = s.version, summary = s.versionSummary)
            BasicComponent(title = s.toolVersion, summary = s.toolVersionSummary)
            BasicComponent(title = s.license, summary = s.licenseSummary, onClick = { showLicense = true })
            BasicComponent(title = s.privacy, summary = s.privacySummary)
        }

        section(s.secCommunity)
        card {
            BasicComponent(
                title = s.tgTitle,
                summary = s.tgSub,
                onClick = {
                    if (!org.icdqcow2.core.Links.joinTelegram(ctx)) onToast(s.tgFail)
                },
            )
            BasicComponent(
                title = s.ghAppTitle,
                summary = s.ghAppSummary,
                onClick = {
                    if (!org.icdqcow2.core.Links.openUrl(ctx, org.icdqcow2.core.Links.REPO_APP)) {
                        onToast(s.tgFail)
                    }
                },
            )
            BasicComponent(
                title = s.ghToolTitle,
                summary = s.ghToolSummary,
                onClick = {
                    if (!org.icdqcow2.core.Links.openUrl(ctx, org.icdqcow2.core.Links.REPO_TOOL)) {
                        onToast(s.tgFail)
                    }
                },
            )
            BasicComponent(
                title = s.ghIssueTitle,
                summary = s.ghIssueSummary,
                onClick = {
                    if (!org.icdqcow2.core.Links.openUrl(ctx, org.icdqcow2.core.Links.ISSUE_APP)) {
                        onToast(s.tgFail)
                    }
                },
            )
        }
    }

    if (showLicense) {
        WindowDialog(
            show = true,
            title = s.license,
            onDismissRequest = { showLicense = false },
            onDismissFinished = { showLicense = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        s.licenseBody,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = { showLicense = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(s.confirm, style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item {
        SmallTitle(
            text = title,
            textColor = SectionTextColor,
            modifier = Modifier.padding(top = 4.dp),
            insideMargin = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 0.dp),
        )
    }
}

private fun LazyListScope.card(content: @Composable () -> Unit) {
    item {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 0.dp)) {
            Column { content() }
        }
    }
}