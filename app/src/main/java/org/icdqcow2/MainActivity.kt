package org.icdqcow2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import org.icdqcow2.core.DiskLimits
import org.icdqcow2.core.FirmwareStore
import org.icdqcow2.core.ToolCaps
import org.icdqcow2.core.ToolRunner
import org.icdqcow2.core.I18n
import org.icdqcow2.core.ImageRepo
import org.icdqcow2.core.ToolEvents
import org.icdqcow2.core.SettingsStore
import org.icdqcow2.imgtool.Native
import org.icdqcow2.ui.FilesScreen
import org.icdqcow2.ui.GlassBarItem
import org.icdqcow2.ui.GlassNavigationBar
import org.icdqcow2.ui.HomeScreen
import org.icdqcow2.ui.LocalBottomBarHeight
import org.icdqcow2.ui.LocalStrings
import org.icdqcow2.ui.LocalVisualEffects
import org.icdqcow2.ui.stringsFor
import org.icdqcow2.ui.SettingsScreen
import org.icdqcow2.ui.SetupScreen
import org.icdqcow2.ui.pages.PageHost
import org.icdqcow2.ui.SmallTitlePage
import org.icdqcow2.ui.BottomFadeScrim
import org.icdqcow2.ui.ToastHost
import org.icdqcow2.ui.ToastHostState
import org.icdqcow2.ui.theme.Icdqcow2Theme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity(), NavigationEventDispatcherOwner {

    /**
     * Miuix 的 `WindowDialog` 内部用 `NavigationBackHandler`，要求上层提供
     * `NavigationEventDispatcherOwner`（activity-compose 1.9 不会自动提供）。
     * 缺了它一弹窗就抛 `IllegalStateException: No NavigationEventDispatcher was provided`。
     */
    override val navigationEventDispatcher = NavigationEventDispatcher()

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 伙伴联动：启动检测一次（用户可能在后台装了/卸了对方应用）
        org.icdqcow2.core.PartnerGate.init(this)
        enableEdgeToEdge()
        settings = SettingsStore(this)
        ImageRepo.init(this)
        org.icdqcow2.core.ImageHistory.init(this)
        // 界面文案表（assets/i18n/ui-strings.json）：新增语言只改这张表
        I18n.load(this)

        // 分区尺寸口径以工具端 cmd.list 为准（读不到就用 DiskLimits 里的兜底常量）
        lifecycleScope.launch {
            ToolRunner.listJson()?.let {
                DiskLimits.applyFrom(it)
                ToolCaps.applyFrom(it)
            }
        }

        // ★ 32 位 UEFI 固件：每次启动检查公共目录，缺了就自动从 assets 补回。
        //   为什么不放到"用的时候再做"：QEMU 是别的 App，参数里写的是绝对路径，
        //   文件没了 VM 直接起不来 —— 提前铺好比事后报错强。IO 很轻（校验一遍 md5）。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { FirmwareStore.ensure(this@MainActivity) }
        }

        // ★ Android 没有 /tmp：BCD 读写、恢复分区填充都要一个可写临时目录
        // 临时目录：统一用 App 缓存目录（用户 2026-11-09 定），不用工具端的"容器同目录"兜底。
        // 每次启动先清扫上次残留 —— 放在 setTmpdir 之前，避免删到本轮要用的目录。
        runCatching {
            val swept = org.icdqcow2.core.TempDirs.sweep(this)
            if (swept > 0) android.util.Log.i("Icdqcow2", "cleaned $swept stale temp files")
        }
        runCatching { Native.setTmpdir(org.icdqcow2.core.TempDirs.toolDir(this).absolutePath) }
        // 进度事件（wim.apply / iso.extract 这类长任务）：界面靠它画进度条
        ToolEvents.install()
        runCatching { Native.setIoTimeoutMs(30_000) }

        setContent {
            val mode by settings.themeMode.collectAsState()
            val lang by settings.language.collectAsState()
            // ★ 所有界面文案都走 Strings（中英双语，铁律）
            val strings = remember(lang) { stringsFor(lang) }
            Icdqcow2Theme(mode) {
                CompositionLocalProvider(
                    LocalStrings provides strings,
                    LocalNavigationEventDispatcherOwner provides this@MainActivity,
                ) {
                    AppRoot(settings)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        // 用户可能在后台装/卸了伙伴应用，回前台重新判定一次
        org.icdqcow2.core.PartnerGate.refresh()
    }
}

private const val TAB_HOME = 0
private const val TAB_FILES = 1
private const val TAB_SETTINGS = 2

/** 玻璃胶囊导航占掉的底部空间（84dp 胶囊 + 22dp 边距 + 手势条）。 */
private val GLASS_BAR_RESERVED = 126.dp

/** 通栏导航栏占掉的底部空间（ItemHeight 64dp + 手势条）。 */
private val NAV_BAR_RESERVED = 84.dp

/** tab 切换时长：跨 1 页 320ms，跨 2 页 520ms。 */
private fun tabSpec(from: Int, to: Int) = tween<Float>(
    durationMillis = if (abs(to - from) <= 1) 320 else 520,
    easing = FastOutSlowInEasing,
)

/** tab 切换时两侧页面缩到多小（已停用，页面不缩小以免出现页间竖线）。 */
private const val PAGE_SHRINK = 0.04f

@Composable
private fun AppRoot(settings: SettingsStore) {
    val s = LocalStrings.current
    val effects by settings.visualEffects.collectAsState()
    val showTrash by settings.showTrash.collectAsState()
    val deleteToTrash by settings.deleteToTrash.collectAsState()
    val conflictPolicy by settings.conflictPolicyFlow.collectAsState()
    val scope = rememberCoroutineScope()
    // 提示队列：最多同时 6 条，新的在最上面，旧的到点自己退场（见 ui/TopToast.kt）
    val toasts = remember { ToastHostState(max = 6) }
    // 选镜像页等通用页面用的全局提示通道
    remember { org.icdqcow2.ui.pages.setToastSink { toasts.show(it) }; true }
    // 历史文件显示条数：设置里改了立刻生效
    LaunchedEffect(Unit) {
        settings.historyCount.collect { org.icdqcow2.core.ImageHistory.setShowCount(it) }
    }
    val toast: (String) -> Unit = { toasts.show(it) }


    // 启动时检查「管理所有文件」：没有它，别的应用创建的镜像读不了、分区也改不了
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var permGate by remember { mutableStateOf(!org.icdqcow2.core.StorageAccess.hasAllFiles()) }

    val pagerState = rememberPagerState(pageCount = { 3 })
    // 端页（主页 / 设置）被"顶到头"时的回弹：0 → 1 → 0
    val edgePulse = remember { Animatable(0f) }
    // 底栏色块的唯一真值：pager 的连续进度（0.0 ~ 2.0）。
    // 用 lambda 而不是 Float，避免每次滚动都重组整个 AppRoot。
    val pagerProgress = remember(pagerState) {
        { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }
    val settled = pagerState.settledPage
    var firstSettle by remember { mutableStateOf(true) }
    LaunchedEffect(settled) {
        if (firstSettle) {
            firstSettle = false
            return@LaunchedEffect
        }
        // 只有两端有边界可顶，中间的文件页不加
        if (settled == 0 || settled == 2) {
            edgePulse.snapTo(0f)
            // 压一下再回位：damping 0.9 = 几乎不过冲，只"顶一下"
            edgePulse.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
            edgePulse.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    // 伙伴联动：写镜像相关的页面直接不放行，点一下就提示，而不是进去以后才失败
    val openRoute: (String) -> Unit = { route ->
        if (org.icdqcow2.core.PartnerGate.restricted &&
            route in org.icdqcow2.core.PartnerGate.BLOCKED_ROUTES
        ) {
            toast(s.partnerBlockedToast)
        } else {
            page = route
        }
    }
    // 文本编辑器要编哪个文件（二级页的参数走这里，路由只认字符串）
    var editorPath by remember { mutableStateOf<String?>(null) }
    // 编辑器是从哪一栏、是不是只读位置点开的（决定保存要不要"挑位置"）
    var editorPane by remember { mutableIntStateOf(0) }
    var editorReadOnlyOrigin by remember { mutableStateOf(false) }
    val tabIndex = pagerState.currentPage

    BackHandler(enabled = page != null) { page = null }

    val barItems = remember(s) {
        listOf(
            GlassBarItem(MiuixIcons.Home, s.tabHome),
            GlassBarItem(MiuixIcons.Folder, s.tabFiles),
            GlassBarItem(MiuixIcons.Settings, s.tabSettings),
        )
    }

    CompositionLocalProvider(
        LocalVisualEffects provides effects,
        LocalBottomBarHeight provides when {
            page != null -> 0.dp
            effects -> GLASS_BAR_RESERVED
            else -> NAV_BAR_RESERVED
        },
    ) {
        Scaffold(
            // ★ Miuix 语义：页面底色 = `surface`（#F7F7F7），卡片 = `surfaceContainer`（白）。
            //   `background` 在 Miuix 里是纯白，拿来当页面底色卡片就看不见了。
            containerColor = MiuixTheme.colorScheme.surface,
            bottomBar = {
                if (page == null && !effects) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tabIndex == TAB_HOME,
                            onClick = { scope.launch { pagerState.animateScrollToPage(TAB_HOME, animationSpec = tabSpec(pagerState.currentPage, TAB_HOME)) } },
                            icon = MiuixIcons.Home,
                            label = s.tabHome,
                        )
                        NavigationBarItem(
                            selected = tabIndex == TAB_FILES,
                            onClick = { scope.launch { pagerState.animateScrollToPage(TAB_FILES, animationSpec = tabSpec(pagerState.currentPage, TAB_FILES)) } },
                            icon = MiuixIcons.Folder,
                            label = s.tabFiles,
                        )
                        NavigationBarItem(
                            selected = tabIndex == TAB_SETTINGS,
                            onClick = { scope.launch { pagerState.animateScrollToPage(TAB_SETTINGS, animationSpec = tabSpec(pagerState.currentPage, TAB_SETTINGS)) } },
                            icon = MiuixIcons.Settings,
                            label = s.tabSettings,
                        )
                    }
                }
            },
        ) { _ ->
            val backdrop = rememberLayerBackdrop()

            // ⛔ 定死，别动（用户 2026-10-28 明确要求）：
            //   主页 → 二级页 必须是**从右往左滑入**，母页往左退 22% + 缩到 96% + 14% 遮罩。
            //   全站其它地方（向导、建盘→分区设置、选文件）用纵向弹出，唯独这里不是，
            //   这是刻意保留的"首页进入"手感，别为了"统一"再改一次。
            val depth by animateFloatAsState(
                if (page != null) 1f else 0f,
                tween(230, easing = LinearOutSlowInEasing),
                label = "depth",
            )

            Box(Modifier.fillMaxSize()) {
                // ── 背景层：整页录进 GraphicsLayer，给玻璃栏折射用 ──
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = -depth * size.width * 0.22f
                                val s = 1f - depth * 0.04f
                                scaleX = s
                                scaleY = s
                            },
                    ) {
                        // 注意：这里**不能**垫深色底。页面一旦缩小就会露出这条缝，
                        // 三页看起来就不像同一张纸了。层次只能靠缩放本身表达。
                        Box(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 0.dp,
                            beyondViewportPageCount = 1,
                        ) { index ->
                            // 三页之间不留任何缝：不缩小、不起圆角，只让端页被"顶一下"
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val edge = if (index == 0 || index == 2) edgePulse.value else 0f
                                        scaleX = 1f + 0.007f * edge
                                        scaleY = 1f - 0.004f * edge
                                    },
                            ) {
                                when (index) {
                                    TAB_HOME -> HomeScreen(onOpen = openRoute)
                                    TAB_FILES -> FilesScreen(
                                        onToast = toast,
                                        // 一旦开始横滑 / 切走就收回 ⋮ 菜单
                                        active = !pagerState.isScrollInProgress &&
                                            pagerState.currentPage == TAB_FILES,
                                        showTrash = showTrash,
                                        onShowTrash = { settings.setShowTrash(it) },
                                        deleteToTrash = deleteToTrash,
                                        conflictPolicy = conflictPolicy,
                                        onConflictPolicy = { settings.setConflictPolicy(it) },
                                        onOpenEditor = { p, readOnlyOrigin, paneIdx ->
                                            editorPath = p
                                            editorReadOnlyOrigin = readOnlyOrigin
                                            editorPane = paneIdx
                                            page = "editor"
                                        },
                                    )
                                    else -> SettingsScreen(
                                        settings = settings,
                                        onToast = toast,
                                        onOpen = { page = it },
                                    )
                                }
                            }
                        }
                        }
                    }
                }

                // 上级页变暗（压在背景层上、二级页下）
                if (depth > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.14f * depth)))
                }

                // ── 二级页：右侧滑入，返回时向右滑出 ──
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        // ⛔ 同上：从右往左，定死。
                        (
                            slideInHorizontally(tween(230, easing = LinearOutSlowInEasing)) { it } +
                                fadeIn(tween(120))
                            ).togetherWith(
                            slideOutHorizontally(tween(230, easing = FastOutLinearInEasing)) { it },
                        )
                    },
                    label = "page",
                ) { target ->
                    if (target == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        when (target) {
                            "setup" -> SetupScreen(onBack = { page = null }, onToast = toast)
                            "editor" -> org.icdqcow2.ui.pages.TextEditorPage(
                                path = editorPath.orEmpty(),
                                onClose = { page = null },
                                onToast = toast,
                                onSaveTo = if (editorReadOnlyOrigin) {
                                    { tmp ->
                                        org.icdqcow2.ui.EditorBridge.saveTo = tmp
                                        org.icdqcow2.ui.EditorBridge.pane = editorPane
                                        page = null
                                    }
                                } else null,
                            )
                            else -> PageHost(target, onBack = { page = null }, onToast = toast, onNav = openRoute)
                        }
                    }
                }

                // ── 液态玻璃导航栏 ──
                if (page == null && effects) {
                    // scrim 先画（在下），玻璃栏后画（在上），否则渐变会把胶囊底部盖白
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        BottomFadeScrim()
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = 22.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        GlassNavigationBar(
                            backdrop = backdrop,
                            progress = pagerProgress,
                            items = barItems,
                            onSnap = { i ->
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        i,
                                        animationSpec = tabSpec(pagerState.currentPage, i),
                                    )
                                }
                            },
                        )
                    }
                }

                // 启动自检：没给「管理所有文件」就弹一次（只弹一次，不烦人）
                if (permGate) {
                    org.icdqcow2.ui.pages.ConfirmDialog(
                        title = s.permDlgTitle,
                        summary = s.permDlgBody,
                        confirmText = s.permDlgGo,
                        cancelText = s.permDlgLater,
                        danger = false,
                        onDismiss = { permGate = false },
                        onConfirm = {
                            permGate = false
                            if (!org.icdqcow2.core.StorageAccess.requestAllFiles(ctx)) toasts.show(s.permManual)
                        },
                    )
                }

                // 提示条：顶部滑下、半透明。最后画，保证压在玻璃栏上面
                ToastHost(
                    state = toasts,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
