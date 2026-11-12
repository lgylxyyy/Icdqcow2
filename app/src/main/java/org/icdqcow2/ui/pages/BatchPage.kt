package org.icdqcow2.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.icdqcow2.core.BatchEngine
import org.icdqcow2.core.BatchService
import org.icdqcow2.core.SettingsStore
import org.icdqcow2.core.ToolCaps
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.SmallTitlePage

/**
 * 批量任务页（用户 2026-11-12 拍板 P2②③：批量压缩/释放 + 后台任务 + 通知）—— 参考实现。
 *
 * **用法**：选好操作（压缩 / 释放空间）和参数 → 把要处理的映像一个个加进来 →
 * 点开始。任务在后台服务里顺序跑，通知栏能看到当前文件和进度；
 * 页面可以随时离开（离开不影响任务）。
 *
 * **停止**：「停止队列」= 当前这一项做完就停，不打断正在写的那一项
 * （写盘写到一半被掐断，映像可能停在半写状态 —— 用户 2026-11-12 拍板过
 * 「开始后不可取消」）。
 */
@Composable
fun BatchPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val st by BatchEngine.state.collectAsState()
    val settings = remember { SettingsStore.get(ctx) }
    val backupOn by settings.autoBackup.collectAsState()

    var op by remember { mutableIntStateOf(0) }        // 0=压缩 1=释放空间
    var compress by remember { mutableIntStateOf(0) }   // 0=off 1=zlib 2=zstd
    var reclaim by remember { mutableIntStateOf(0) }    // 0=fs 1=zero
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var removeIdx by remember { mutableIntStateOf(-1) }

    val zipOk = ToolCaps.COMPACT_COMPRESS
    val zstdOk = ToolCaps.ZSTD

    // 通知权限：Android 13+ 是运行时权限。没给也能跑，只是通知栏看不到进度。
    var hasNotif by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotif = granted }

    fun start() {
        if (picked.isEmpty()) {
            onToast(s.batchEmpty)
            return
        }
        if (!hasNotif) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            onToast(s.batchNoNotif)
        }
        val arg = if (op == 0) listOf("off", "zlib", "zstd")[compress] else listOf("fs", "zero")[reclaim]
        val kind = if (op == 0) BatchEngine.Item.Op.COMPACT else BatchEngine.Item.Op.RECLAIM
        BatchEngine.attach(ctx)
        BatchEngine.clear()
        BatchEngine.add(picked, kind, arg)
        picked = emptyList()
        // 先起服务（前台服务要抢在 5 秒内 startForeground），再开跑
        BatchService.start(ctx)
        BatchEngine.start(ctx)
    }

    SmallTitlePage(title = s.batchTitle, onBack = onBack) {
        BlockEnter(0) {
            Note(s.batchOp)
            ChoiceCard(
                options = listOf(
                    Choice(s.batchOpCompact, s.batchOpCompactSummary),
                    Choice(s.batchOpReclaim, s.batchOpReclaimSummary),
                ),
                selected = op,
            ) { i -> op = i }

            if (op == 0) {
                Note(s.compactMode)
                ChoiceCard(
                    options = listOf(
                        Choice(s.compactModeTight, s.compactModeTightSummary),
                        Choice(s.compactModeZip, s.compactModeZipSummary),
                        Choice(s.compactModeZstd, s.compactModeZstdSummary),
                    ),
                    selected = compress,
                    enabled = listOf(true, zipOk, zstdOk),
                ) { i ->
                    when {
                        i == 1 && !zipOk -> onToast(s.compactModeZipOff)
                        i == 2 && !zstdOk -> onToast(s.compactModeZstdOff)
                        else -> compress = i
                    }
                }
                if (!zipOk) Note(s.compactModeZipOff)
                if (!zstdOk) Note(s.compactModeZstdOff)
                if (compress == 2) Note(s.compactZstdWarn, warn = true)
            } else {
                Note(s.trimMode)
                ChoiceCard(
                    options = listOf(
                        Choice(s.trimModeFs, s.trimModeFsSummary),
                        Choice(s.trimModeZero, s.trimModeZeroSummary),
                    ),
                    selected = reclaim,
                ) { i -> reclaim = i }
                Note(s.trimBackupTitle)
                ChoiceCard(
                    options = listOf(
                        Choice(s.trimBackupOn, s.trimBackupOnSummary),
                        Choice(s.trimBackupOff, s.trimBackupOffSummary),
                    ),
                    selected = if (backupOn) 0 else 1,
                ) { i -> settings.setAutoBackup(i == 0) }
                if (backupOn) Note(s.trimBackupSpace)
            }
        }

        BlockEnter(1) {
            GroupTitle(s.batchImages)
            if (picked.isEmpty()) {
                Note(s.batchEmpty)
            } else {
                GroupCard {
                    picked.forEachIndexed { i, p ->
                        Item(pickName(p), s.batchTapRemove) { removeIdx = i }
                    }
                }
                Action(s.batchClear) { picked = emptyList() }
            }
            Action(
                text = s.batchAdd,
                enabled = !st.running,
                muted = st.running,
            ) { picking = !picking }
            if (picking) {
                PickImagePage(onPicked = { path, uri ->
                    val p = path ?: uri
                    if (!p.isNullOrEmpty()) picked = picked + p
                    picking = false
                })
            }
        }

        // ── 运行状态 ─────────────────────────────────────────────
        if (st.running) {
            BlockEnter(2) {
                val cur = st.items.getOrNull(st.current)
                ProgressBar(
                    pct = (st.overall * 100).toInt(),
                    text = s.batchProgress(
                        st.current + 1,
                        st.items.size,
                        cur?.path?.let { pickName(it) } ?: "",
                    ),
                )
                if (st.stopRequested) Note(s.batchStopping)
                Action(s.batchStop) { BatchEngine.requestStop() }
            }
        }

        // ── 上一轮结果 ───────────────────────────────────────────
        val done = st.items.filter {
            it.state == BatchEngine.Item.State.DONE || it.state == BatchEngine.Item.State.FAILED
        }
        if (!st.running && done.isNotEmpty()) {
            BlockEnter(3) {
                GroupTitle(s.batchResult)
                GroupCard {
                    done.forEach { one ->
                        val ok = one.state == BatchEngine.Item.State.DONE
                        Item(
                            title = pickName(one.path),
                            summary = if (ok) one.result else one.error,
                        )
                    }
                }
                val failed = done.count { it.state == BatchEngine.Item.State.FAILED }
                if (failed > 0) Note(s.batchFailed(failed), warn = true)
            }
        }

        if (!st.running) {
            Action(
                text = s.batchStart,
                enabled = picked.isNotEmpty(),
                muted = picked.isEmpty(),
            ) { start() }
        }
    }

    // 移除某个映像（点一下列表行 → 确认）
    if (removeIdx >= 0 && removeIdx < picked.size) {
        val target = picked[removeIdx]
        ConfirmDialog(
            title = s.batchRemoveTitle,
            summary = s.batchRemoveBody(pickName(target)),
            confirmText = s.batchRemove,
            cancelText = s.cancel,
            danger = true,
            onDismiss = { removeIdx = -1 },
            onConfirm = {
                picked = picked.filterIndexed { i, _ -> i != removeIdx }
                removeIdx = -1
            },
        )
    }
}
