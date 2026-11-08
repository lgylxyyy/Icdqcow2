package org.icdqcow2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 任务中心：装机、复制、校验都排队在这里。 */
@Composable
fun TasksPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    SmallTitlePage(title = "任务中心", onBack = onBack) {
        EmptyCard(
            icon = MiuixIcons.Tasks,
            title = "没有进行中的任务",
            desc = "装机、导入、导出、校验会在后台排队；任务运行时这里的图标会转，未完成任务在杀进程后会被标记为中断。",
        )
        GroupCard {
            Item("同时运行", "1 个（工具端串行）")
            Item("完成后通知", "开启", onClick = { onToast("通知权限：待接入") })
        }
    }
}

/** 控制台：输入命令，看工具端原始输出。 */
@Composable
fun ConsolePage(onBack: () -> Unit, onToast: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme

    SmallTitlePage(title = "控制台", onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "imgtool 2026.10.23 · arm64-v8a",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    "输入 help 查看命令；输出只读，不会自动换行折行。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }

        GroupTitle("命令")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
            )
            TextButton(text = "执行", onClick = { onToast("控制台：接口未接入") })
        }

        Note("调试用。正式操作走各功能页，控制台里执行的写操作不会进任务中心。")
    }
}

/** 错误码手册：搜索 + 列表。 */
@Composable
fun ErrorsPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = listOf(
        "E1001" to "qcow2 魔数不符",
        "E1002" to "qcow2 版本不支持",
        "E1003" to "引用计数表损坏",
        "E2001" to "分区表签名缺失",
        "E2002" to "分区区间重叠",
        "E3001" to "目标分区空间不足",
        "E3002" to "卷索引越界",
        "E4001" to "引导扇区写入失败",
        "E5001" to "校验哈希不一致",
        "E5002" to "IO 超时",
    )
    val list = all.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }

    SmallTitlePage(title = "错误码手册", onBack = onBack) {
        GroupTitle("搜索")
        TextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth(),
        )
        GroupCard {
            if (list.isEmpty()) {
                Item("没有匹配项", "换个关键字试试")
            } else {
                list.forEach { (code, desc) ->
                    Item(code, desc, onClick = { onToast("$code · $desc：详情页待接入") })
                }
            }
        }
        Note("共 80 个稳定错误码；这里只列了最常撞到的几条，完整表随工具手册发布。")
    }
}

/** 导出日志：勾选范围，打包到下载目录。 */
@Composable
fun LogsPage(onBack: () -> Unit, onToast: (String) -> Unit) {
    var app by remember { mutableStateOf(true) }
    var tool by remember { mutableStateOf(true) }
    var kernel by remember { mutableStateOf(false) }

    SmallTitlePage(title = "导出日志", onBack = onBack) {
        GroupTitle("包含内容")
        GroupCard {
            Item(
                "应用日志",
                "页面操作、异常栈",
                end = { Switch(checked = app, onCheckedChange = { app = it }) },
            )
            Item(
                "工具输出",
                "imgtool 每条命令的 stdout / stderr",
                end = { Switch(checked = tool, onCheckedChange = { tool = it }) },
            )
            Item(
                "内核日志",
                "dmesg 与 IO 错误，体积大",
                end = { Switch(checked = kernel, onCheckedChange = { kernel = it }) },
            )
        }
        GroupCard {
            Item("输出位置", "下载/Icdqcow2/logs/")
            Item("当前大小", "约 1.2 MB")
        }
        Note("日志可能包含镜像路径与分区信息，分享前先看一眼。")
        Action("导出", enabled = app || tool || kernel) { onToast("导出日志：接口未接入") }
    }
}
