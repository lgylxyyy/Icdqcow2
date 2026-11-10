package org.icdqcow2.ui

/** 二级页面路由 → 标题。加页面时这里和 `PageScreen` 的 `when` 一起改。 */
val PAGE_TITLES: Map<String, String> = mapOf(
    "console" to "控制台",
    "create" to "创建磁盘",
    "parts" to "分区管理",
    "format" to "格式化",
    "ptexport" to "导出分区表",
    "clone" to "压缩镜像",
    "deploy" to "释放镜像",
    "files" to "浏览文件",
    "import" to "导入文件",
    "export" to "导出文件",
    "bootrepair" to "引导修复",
    "winre" to "恢复环境",
    "verify" to "全量自检",
    "errors" to "错误码手册",
    "logs" to "导出日志",
    "licenses" to "开源许可证",
    "registry" to "注册表",
    "capture" to "捕获映像",
    "setup" to "全流程装机",
)
