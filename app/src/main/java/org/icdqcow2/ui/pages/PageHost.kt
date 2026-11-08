package org.icdqcow2.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.icdqcow2.ui.PAGE_TITLES
import org.icdqcow2.ui.SmallTitlePage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 二级页面分发。没列进来的路由走 [NobodyPage]。 */
@Composable
fun PageHost(
    route: String,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    /** 带着镜像跳进来（自检页的异常项用它直接落到"已选中该盘"）。 */
    arg: String? = null,
) {
    when (route) {
        "create" -> CreateDiskPage(onBack, onToast)
        "parts" -> PartsPage(onBack, onToast, initialImage = arg)
        "format" -> FormatPage(onBack, onToast)
        "ptexport" -> ExportTablePage(onBack, onToast)
        "clone" -> CompactPage(onBack, onToast)
        // 引导自检 + 镜像自检 合并成「全量自检」
        "verify" -> FullCheckPage(onBack = onBack, onToast = onToast, initialImage = arg)
        "deploy" -> DeployPage(onBack, onToast, initialImage = arg)
        "export" -> ExportPage(onBack, onToast)
        "bootrepair" -> BootRepairPage(onBack, onToast, initialImage = arg)
        "winre" -> WinRePage(onBack, onToast, initialImage = arg, onFinish = onBack)
        "tasks" -> TasksPage(onBack, onToast)
        "console" -> ConsolePage(onBack, onToast)
        "errors" -> ErrorsPage(onBack, onToast)
        "logs" -> LogsPage(onBack, onToast)
        else -> NobodyPage(route, onBack)
    }
}

/** 还没做的页面：只给标题和一句状态，不许暗示功能已经能用。 */
@Composable
private fun NobodyPage(route: String, onBack: () -> Unit) {
    SmallTitlePage(title = PAGE_TITLES[route] ?: "未命名页面", onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column {
                Text("尚未实现", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
                Text(
                    "接口与入口已留好，功能在后续版本接入",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
