package org.icdqcow2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.icdqcow2.R

/**
 * 文件类型图标（2026-10-27 定）。
 *
 * **图标库**：Iconify 的 **MDI（Material Design Icons）** 集合，Apache-2.0。
 * 由 `tools/gen_file_icons.py` 取 24×24 单色 path → `res/drawable/ic_ft_*.xml`，
 * 运行时 `Icon(..., tint = 类型色)`。所以：
 *   · 不引第三方运行时依赖（离线构建、体积都友好）；
 *   · 深浅色主题共用一套图，只换 tint；
 *   · 和 Miuix 同属 Material 视觉语言，圆角方块底 + 单色图形，不抢眼。
 *
 * 判定顺序：**目录 → 特殊文件名（bootmgr/bcd/…）→ 后缀**。
 * 后缀不认识 → [FileType.FILE]（通用文件图标），不会出现"没图标"。
 */
data class FileType(
    /** 稳定标识，日志/调试用。 */
    val key: String,
    val res: Int,
    val color: Color,
    val exts: Set<String> = emptySet(),
    /** 精确文件名（无后缀或后缀不可靠的系统文件）。 */
    val names: Set<String> = emptySet(),
)

object FileIcons {

    // ── 配色：一类型一色，饱和度压过一点，和 Miuix 的柔和观感对齐 ──
    private val FOLDER = Color(0xFFF0B429)
    private val EXE = Color(0xFF3B82F6)
    private val SYSTEM = Color(0xFF6366F1)
    private val DOC = Color(0xFF0EA5E9)
    private val WORD = Color(0xFF2B579A)
    private val EXCEL = Color(0xFF217346)
    private val PPT = Color(0xFFD24726)
    private val PDF = Color(0xFFD93025)
    private val IMAGE = Color(0xFF8B5CF6)
    private val AUDIO = Color(0xFFF59E0B)
    private val VIDEO = Color(0xFFEC4899)
    private val ARCHIVE = Color(0xFFCA8A04)
    private val CODE = Color(0xFF059669)
    private val CONFIG = Color(0xFF64748B)
    private val FONT = Color(0xFF7C3AED)
    private val CERT = Color(0xFFB45309)
    private val DB = Color(0xFF0891B2)
    private val DISK = Color(0xFF0D9488)
    private val ANDROID = Color(0xFF22C55E)
    private val WINDOWS = Color(0xFF0078D4)
    private val TEXT = Color(0xFF475569)
    private val LINK = Color(0xFF0284C7)
    private val UNKNOWN = Color(0xFF94A3B8)

    // ── 分区行（文件页里"进 qcow2 之后"那层列表用）──
    /** NTFS 分区。 */
    val PART_NTFS = FileType("part-ntfs", R.drawable.ic_ft_harddisk, Color(0xFF2563EB))
    /** FAT32 分区。 */
    val PART_FAT = FileType("part-fat", R.drawable.ic_ft_harddisk, Color(0xFFD97706))
    /** 没有文件系统 / 认不出的分区。 */
    val PART_RAW = FileType("part-raw", R.drawable.ic_ft_harddisk, UNKNOWN)

    /** 分区行图标：按文件系统上色（形状统一硬盘）。 */
    fun ofPartition(fs: String?, hasFs: Boolean): FileType = when {
        !hasFs -> PART_RAW
        fs.equals("ntfs", true) -> PART_NTFS
        fs?.startsWith("fat", true) == true -> PART_FAT
        else -> PART_RAW
    }

    val FOLDER_TYPE = FileType("folder", R.drawable.ic_ft_folder, FOLDER)
    val FOLDER_OPEN_TYPE = FileType("folder-open", R.drawable.ic_ft_folder_open, FOLDER)
    val FILE_TYPE = FileType("file", R.drawable.ic_ft_file, UNKNOWN)

    /**
     * 全部类型。**顺序有意义**：同后缀写进多个类型时，先匹配的赢
     * （所以 `zip` 只在压缩包、`wim` 只在磁盘镜像里出现一次）。
     */
    val types: List<FileType> = listOf(
        // ── 可执行 / 安装包 ──
        FileType("exe", R.drawable.ic_ft_application, EXE,
            exts = setOf("exe", "com", "scr", "msi", "msp", "msu", "appx", "msix", "appxbundle",
                "jar", "war", "ear", "bat", "cmd")),
        // ── 系统 / 驱动 / 库 ──
        FileType("system", R.drawable.ic_ft_puzzle, SYSTEM,
            exts = setOf("dll", "ocx", "cpl", "tlb", "ax", "drv", "vxd", "sys", "efi", "mui", "mun",
                "so", "dylib", "ko", "pdb", "lib", "a", "obj", "res", "manifest", "cat")),
        // ── Windows 启动链（无后缀/怪后缀，靠名字认） ──
        FileType("boot", R.drawable.ic_ft_microsoft_windows, WINDOWS,
            exts = setOf("bcd", "wim", "esd", "swm", "hiv"),
            names = setOf("bootmgr", "bootmgr.efi", "ntldr", "ntdetect.com", "io.sys", "msdos.sys",
                "bcd", "boot.ini", "bcdbackup")),
        // ── 磁盘镜像 / 分区表 ──
        FileType("disk", R.drawable.ic_ft_harddisk, DISK,
            exts = setOf("qcow2", "qcow", "vhd", "vhdx", "vmdk", "vdi", "img", "raw", "dmg",
                "ova", "ovf", "qed", "sparseimage")),
        FileType("disc", R.drawable.ic_ft_disc, DISK,
            exts = setOf("iso", "mdf", "nrg", "cue", "bin", "toast", "udf")),
        // ── 文档 ──
        FileType("doc", R.drawable.ic_ft_file_document, DOC,
            exts = setOf("doc", "docx", "dot", "dotx", "odt", "rtf", "pages", "wps", "xps", "oxps", "epub")),
        FileType("word", R.drawable.ic_ft_file_word_box, WORD,
            exts = setOf("docm", "dotm")),
        FileType("pdf", R.drawable.ic_ft_file_pdf_box, PDF,
            exts = setOf("pdf")),
        FileType("sheet", R.drawable.ic_ft_file_table, EXCEL,
            exts = setOf("xls", "xlsx", "xlsm", "xlsb", "ods", "numbers", "et")),
        FileType("excel", R.drawable.ic_ft_file_excel_box, EXCEL,
            exts = setOf("xlt", "xltx", "xltm")),
        FileType("csv", R.drawable.ic_ft_file_chart, EXCEL,
            exts = setOf("csv", "tsv", "dif")),
        FileType("slides", R.drawable.ic_ft_file_powerpoint_box, PPT,
            exts = setOf("ppt", "pptx", "pps", "ppsx", "pptm", "odp", "key", "dps")),
        FileType("text", R.drawable.ic_ft_text_box, TEXT,
            exts = setOf("txt", "log", "nfo", "readme", "lst", "out", "err", "diz")),
        FileType("markup", R.drawable.ic_ft_file_document, TEXT,
            exts = setOf("md", "markdown", "rst", "adoc", "asciidoc", "tex", "latex", "wiki")),
        FileType("book", R.drawable.ic_ft_book_open_page_variant, DOC,
            exts = setOf("mobi", "azw3", "fb2", "djvu", "cbz", "cbr")),
        // ── 表格数据 ──
        FileType("database", R.drawable.ic_ft_database, DB,
            exts = setOf("db", "sqlite", "sqlite3", "mdb", "accdb", "dbf", "frm", "ibd", "mdf", "ldf")),
        // ── 图片 ──
        FileType("image", R.drawable.ic_ft_file_image, IMAGE,
            exts = setOf("jpg", "jpeg", "jpe", "png", "gif", "bmp", "webp", "tif", "tiff", "ico",
                "svg", "svgz", "heic", "heif", "avif", "raw", "dng", "cr2", "cr3", "nef", "arw",
                "psd", "psb", "ai", "eps", "xcf", "kra", "tga", "jp2", "jxl", "wmf", "emf")),
        // ── 音频 ──
        FileType("audio", R.drawable.ic_ft_file_music, AUDIO,
            exts = setOf("mp3", "wav", "flac", "aac", "ogg", "oga", "opus", "m4a", "wma", "amr",
                "mid", "midi", "ape", "aiff", "aif", "dsf", "dff", "mka", "ra")),
        // ── 视频 ──
        FileType("video", R.drawable.ic_ft_file_video, VIDEO,
            exts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg",
                "ts", "m2ts", "3gp", "rmvb", "rm", "vob", "asf", "ogv", "mts")),
        FileType("subtitle", R.drawable.ic_ft_text_box, VIDEO,
            exts = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")),
        // ── 压缩包 ──
        FileType("archive", R.drawable.ic_ft_folder_zip, ARCHIVE,
            exts = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz", "xz", "txz", "zst",
                "lz", "lzma", "lz4", "cab", "arj", "lzh", "ace", "cpio", "rpm", "deb",
                "zipx", "z", "001", "part1", "e01")),
        FileType("package", R.drawable.ic_ft_package_variant, ARCHIVE,
            exts = setOf("pkg", "xapk", "apks", "obb", "aab", "snap", "flatpak", "nupkg")),
        // ── Android ──
        FileType("android", R.drawable.ic_ft_android, ANDROID,
            exts = setOf("apk", "dex", "odex", "vdex", "oat", "art", "smali", "aidl", "gradle", "aar")),
        // ── 代码 ──
        FileType("code", R.drawable.ic_ft_file_code, CODE,
            exts = setOf("c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "cs", "java", "kt", "kts",
                "py", "pyw", "rb", "go", "rs", "swift", "m", "mm", "php", "pl", "pm", "lua",
                "r", "jl", "dart", "scala", "groovy", "vb", "vbs", "pas", "asm", "s", "f90", "f",
                "hs", "ml", "nim", "zig", "ts", "tsx", "jsx", "vue", "svelte", "coffee")),
        FileType("js", R.drawable.ic_ft_code_braces, CODE,
            exts = setOf("js", "mjs", "cjs")),
        FileType("script", R.drawable.ic_ft_script_text, CODE,
            exts = setOf("sh", "bash", "zsh", "fish", "ps1", "psm1", "psd1", "awk", "sed", "tcl",
                "ahk", "nsi", "nsh", "inf", "reg")),
        FileType("console", R.drawable.ic_ft_console, CODE,
            exts = setOf("sql", "psql", "plsql", "hql", "cql")),
        FileType("web", R.drawable.ic_ft_web, CODE,
            exts = setOf("html", "htm", "xhtml", "shtml", "mhtml", "css", "scss", "sass", "less", "styl")),
        FileType("markup-lang", R.drawable.ic_ft_xml, CODE,
            exts = setOf("xml", "xsl", "xslt", "xsd", "dtd", "plist", "resx", "csproj", "vcxproj",
                "sln", "props", "targets", "nuspec", "xaml", "axml")),
        FileType("config", R.drawable.ic_ft_file_cog, CONFIG,
            exts = setOf("ini", "cfg", "conf", "config", "toml", "yaml", "yml", "json", "json5",
                "jsonc", "properties", "env", "rc", "lock", "prefs", "settings", "theme", "desktop", "service", "policy", "adm", "admx", "adml", "msc", "themes")),
        FileType("link", R.drawable.ic_ft_link_variant, LINK,
            exts = setOf("lnk", "url", "webloc", "symlink")),
        FileType("email", R.drawable.ic_ft_email, DOC,
            exts = setOf("eml", "msg", "mbox", "pst", "ost", "emlx")),
        // ── 字体 ──
        FileType("font", R.drawable.ic_ft_format_font, FONT,
            exts = setOf("ttf", "otf", "woff", "woff2", "eot", "fon", "fnt", "ttc", "otc", "pfb", "pfm")),
        // ── 证书 / 密钥 ──
        FileType("cert", R.drawable.ic_ft_file_certificate, CERT,
            exts = setOf("cer", "crt", "pem", "der", "pfx", "p12", "csr", "p7b", "spc", "cat2", "sig")),
        FileType("key", R.drawable.ic_ft_file_key, CERT,
            exts = setOf("key", "keystore", "jks", "bks", "ppk", "asc", "gpg", "pkr")),
        // ── 其它 ──
        FileType("cabinet", R.drawable.ic_ft_file_cabinet, CONFIG,
            exts = setOf("dat", "pak", "pack", "blob", "cache")),
        FileType("phone", R.drawable.ic_ft_cellphone, ANDROID,
            exts = setOf("ab", "backup", "twrp", "nb0")),
        FileType("server", R.drawable.ic_ft_server, SYSTEM,
            exts = setOf("vmx", "vmsd", "nvram", "vmem", "vsv", "vmsn", "hds", "pvi")),
    )

    private val byExt: Map<String, FileType> = buildMap {
        // 后写的**不**覆盖先写的：先出现的类型优先
        for (t in types) for (e in t.exts) putIfAbsent(e, t)
    }
    private val byName: Map<String, FileType> = buildMap {
        for (t in types) for (n in t.names) putIfAbsent(n, t)
    }

    /** 文件名 → 类型。目录单独给folder。 */
    fun of(fileName: String, isDir: Boolean): FileType {
        if (isDir) return FOLDER_TYPE
        val lower = fileName.lowercase()
        byName[lower]?.let { return it }
        // 多重后缀：install.wim.gz、a.tar.xz —— 先看最后两段
        val parts = lower.split('.')
        if (parts.size >= 3) {
            byExt[parts[parts.size - 2] + "." + parts.last()]?.let { return it }
        }
        if (parts.size >= 2) byExt[parts.last()]?.let { return it }
        // 没后缀的 Windows 启动文件（bootmgr 之类）已由 byName 命中
        return FILE_TYPE
    }

    /** 报告用：某类型覆盖的后缀（按字母序）。 */
    fun extsOf(t: FileType): List<String> = t.exts.sorted()
}

/**
 * 一个文件类型图标：圆角方块底（类型色 12% 透明度）+ 单色图形。
 * 尺寸默认 40dp，行内用 40；列表密集时可传 32。
 */
@Composable
fun FileTypeIcon(name: String, isDir: Boolean, size: Dp = 40.dp) {
    FileTypeIcon(FileIcons.of(name, isDir), size)
}

/** 直接给类型（分区行、特殊行用）。 */
@Composable
fun FileTypeIcon(t: FileType, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(t.color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(t.res),
            contentDescription = null,
            tint = t.color,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}