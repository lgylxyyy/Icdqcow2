package org.icdqcow2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.SetupProgress
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.pages.ConfirmCheckDialog
import org.icdqcow2.ui.pages.CreateDiskPage
import org.icdqcow2.ui.pages.DeployPage
import org.icdqcow2.ui.pages.FullCheckPage
import org.icdqcow2.ui.pages.LinkItem
import org.icdqcow2.ui.pages.PickImagePage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TOTAL_STEPS = 3

/**
 * 全流程装机：**把已有页面按顺序串起来**，只负责"现在该做哪一步"。
 *
 * 导航是**单向**的：进入下一级就不再有"上一级"可退。任何一级按返回
 * （左上角箭头或系统返回键）都直接问"是否退出全流程装机"，确认即回主页；
 * 退出时可选「保存当前进度」，下次进来直接回到那一页继续操作那个镜像
 * （镜像文件不在了就当作没有进度，全新开始）。
 *
 * 副标题（第 N 步 / 共 3 步）只在向导里传给页面；从主页单独打开同页面不传 → 不显示。
 */
@Composable
fun SetupScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current

    // 续做进度：镜像还在才用；不在了就清掉、当作全新开始
    val resumed = remember {
        val saved = SetupProgress.load(ctx) ?: return@remember null
        if (java.io.File(saved.image).exists()) {
            // 必须在第一帧就用它当 current：释放页是拿 current 初始化镜像的
            ImageRepo.setCurrent(saved.image)
            saved
        } else {
            SetupProgress.clear(ctx)
            null
        }
    }

    /** 0 = 入口；2/3 = 第几步（第 1 步用 sub 区分"选盘/建盘"）。 */
    var stage by remember { mutableIntStateOf(resumed?.step ?: 0) }
    var sub by remember { mutableStateOf<String?>(null) }
    var askExit by remember { mutableStateOf(false) }
    /** 正在写盘（退出弹窗要说重话）。 */
    var deployRunning by remember { mutableStateOf(false) }

    fun hint(n: Int) = s.setupStepHint(n, TOTAL_STEPS)

    if (resumed != null) {
        LaunchedEffect(Unit) { onToast(s.setupResumeOk) }
    }

    // 返回 = 退出，不再退回上一级
    BackHandler { askExit = true }

    // 子页面 + 第几步 → 一个编号，交给 AnimatedContent 做"覆盖式"切换
    // 0 入口 / 1 选镜像 / 2 建新盘 / 20 释放映像 / 30 全盘检查
    val step = when {
        sub == "pick" -> 1
        sub == "create" -> 2
        stage == 2 -> 20
        stage == 3 -> 30
        else -> 0
    }

    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = step,
        transitionSpec = {
            // 旧页立刻移除，新页**自下而上弹出**（和"选文件 → 正文"一致，不做左右滑动）
            val dir = if (targetState >= initialState) 1 else -1
            (
                slideInVertically(tween(280, easing = LinearOutSlowInEasing)) { it * dir } +
                    fadeIn(tween(180))
                ).togetherWith(ExitTransition.None)
        },
        label = "setupStep",
    ) { st ->
        when (st) {
            // 第 1 步之一：选已有镜像
            1 -> SmallTitlePage(
                title = s.setupTitle,
                onBack = { askExit = true },
                subtitle = hint(1),
            ) {
                PickImagePage(onPicked = { path, _ ->
                    if (path != null) ImageRepo.setCurrent(path)
                    sub = null
                    stage = 2
                })
            }

            // 第 1 步之二：建新盘（向导模式）
            2 -> CreateDiskPage(
                onBack = { askExit = true },
                onToast = onToast,
                subtitle = hint(1),
                guided = true,
                primaryLabel = s.setupNextLabel,
                onCreated = { path ->
                    ImageRepo.setCurrent(path)
                    sub = null
                    stage = 2
                },
            )

            // 第 2 步：释放映像（写引导/写 RE/引导检查都在这一页里）
            20 -> DeployPage(
                onBack = { askExit = true },
                onToast = onToast,
                subtitle = hint(2),
                primaryLabel = s.setupNextLabel,
                initialImage = ImageRepo.current.value,
                // 跑完之后底部按钮（「下一步」）进第 3 步
                onNext = { stage = 3 },
                onRunningChange = { deployRunning = it },
            )

            // 第 3 步：全量自检（只读）；「完成」= 流程结束，回主页并清掉续做进度
            30 -> FullCheckPage(
                onBack = { askExit = true },
                onToast = onToast,
                subtitle = hint(3),
                initialImage = ImageRepo.current.value,
                onDone = {
                    SetupProgress.clear(ctx)
                    onBack()
                },
            )

            // 入口
            else -> SmallTitlePage(title = s.setupTitle, onBack = { askExit = true }) {
                // 两件事合成一张卡（Miuix 原生 Card + LinkItem，右箭头 + 按压反馈都是系统控件）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(vertical = 0.dp),
                ) {
                    LinkItem(
                        title = s.setupPickExisting,
                        summary = s.setupPickExistingSummary,
                    ) { sub = "pick" }
                    LinkItem(
                        title = s.setupPickCreate,
                        summary = s.setupPickCreateSummary,
                    ) { sub = "create" }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(s.setupFlowTitle, style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.setupFlowBody,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (askExit) {
        ConfirmCheckDialog(
            title = s.setupExitTitle,
            // 正在写盘：明确说清"返回会取消写入、数据可能损坏"
            summary = if (deployRunning) s.setupExitRunning else s.setupExitBody,
            checkLabel = s.setupExitKeep,
            confirmText = s.setupExitLeave,
            cancelText = s.setupExitStay,
            danger = true,
            onDismiss = { askExit = false },
            onConfirm = { keep ->
                askExit = false
                // 写盘中退出：先把任务停掉再走
                if (deployRunning) ToolRunner.cancel()
                val img = ImageRepo.current.value
                if (keep && stage >= 2 && !img.isNullOrBlank()) {
                    SetupProgress.save(ctx, stage, img)
                } else {
                    SetupProgress.clear(ctx)
                }
                onBack()
            },
        )
    }
}