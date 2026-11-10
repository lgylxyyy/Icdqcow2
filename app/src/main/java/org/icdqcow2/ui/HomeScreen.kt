package org.icdqcow2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页（工作台）。
 *
 * 卡片每行两个、竖向排列；每张卡 = 图标 + 名称 + 一行说明。
 * 卡片上不放按钮——所有运行动作都进二级页面。
 *
 * 2026-10-28 用户定：
 * - 去掉「当前映像」卡（不再常驻"当前操作的文件"，每次都弹选择）；
 * - 「控制台」移到设置页；
 * - 「导入文件」删除；
 * - 「引导自检 / 镜像自检」合并成「全量自检」；
 * - 「系统部署」与「引导与恢复」合并成一个分组；
 * - 「维护」整组（错误码手册 / 日志）移到设置页。
 */
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    // 伙伴联动状态：必须在 composable 作用域读，LazyListScope 里不能调 collectAsState
    val partners by org.icdqcow2.core.PartnerGate.active.collectAsState()
    LargeTitlePage(
        title = s.homeTitle,
    ) {
        // 伙伴联动：命中时在顶部给出明确说明。不静默隐藏功能 —— 那会被当成 bug。
        if (partners.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column {
                        Text(
                            s.partnerNoticeTitle,
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.partnerNoticeBody,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // 主入口：整行大卡 + logo
        item {
            WideCard(
                title = s.setup,
                desc = s.setupSummary,
                logo = MiuixIcons.Merge,
            ) { onOpen("setup") }
        }

        section(s.secDisk)
        grid(
            Tile(s.createDisk, s.createDiskSummary, MiuixIcons.AddCircle) { onOpen("create") },
            Tile(s.parts, s.partsSummary, MiuixIcons.ListView) { onOpen("parts") },
        )
        grid(
            // 原来这里是「格式化」——单分区格式化已并进「分区管理」，位置换「导出分区表」（REQ-14）。
            Tile(s.exportTable, s.exportTableSummary, MiuixIcons.Download) { onOpen("ptexport") },
            Tile(s.clone, s.cloneSummary, MiuixIcons.Copy) { onOpen("clone") },
        )

        // 「系统部署」+「引导与恢复」合并成一组
        section(s.secSys)
        grid(
            Tile(s.deploy, s.deploySummary, MiuixIcons.Import) { onOpen("deploy") },
            // 引导修复原来在第二行左侧，现在顶到"导出文件"空出来的位置
            Tile(s.bootRepair, s.bootRepairSummary, MiuixIcons.Update) { onOpen("bootrepair") },
        )
        grid(
            // WinRE 顶到引导修复原来的位置
            Tile(s.winre, s.winreSummary, MiuixIcons.Reset) { onOpen("winre") },
            Tile(s.selfCheck, s.selfCheckSummary, MiuixIcons.Ok) { onOpen("verify") },
        )
        grid(
            // 注册表：只读浏览系统盘 hive（写入未开放）
            Tile(s.regTitle, s.regSummary, MiuixIcons.ListView) { onOpen("registry") },
            Tile(s.regCaptureTitle, s.regCaptureSummary, MiuixIcons.Download) { onOpen("capture") },
        )
    }
}

data class Tile(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

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

/** 一行两张卡。 */
private fun LazyListScope.grid(left: Tile, right: Tile) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TileCard(left, Modifier.weight(1f))
            TileCard(right, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TileCard(tile: Tile, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val text = MiuixTheme.textStyles

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = tile.onClick,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        // 标题、副标题各一行：所有卡片自然等高，不会出现单行文案下面一大片留白。
        // 放不下的英文文案在 i18n 里直接写短，不靠截断。
        Column {
            Icon(tile.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                tile.title,
                style = text.title4,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                tile.desc,
                style = text.body2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 整行大卡：logo + 名称 + 一行说明。 */
@Composable
private fun WideCard(
    title: String,
    desc: String,
    logo: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val text = MiuixTheme.textStyles

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        logo,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = text.title4, color = colors.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(
                    desc,
                    style = text.body2,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
