package org.icdqcow2.ui.pages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 极简语法高亮（自己写的，不引第三方 —— 项目是离线构建，加依赖有风险；而且高亮只需要
 * 「关键字 / 字符串 / 注释 / 数字 / tag」这几类，通用 lexer 是杀鸡用牛刀）。
 *
 * 做法：每种语言给一组正则（按优先级排好：注释、字符串先吃掉，再匹配关键字/数字），
 * 命中的区间插一个 [SpanStyle]。起点已被吃掉的匹配直接跳过 —— 避免 "// foo 里的 int"
 * 被染成关键字。超过 [MAX_HIGHLIGHT] 字符不染（几百 KB 每敲一键跑正则会卡）。
 */
enum class CodeLang { PLAIN, KT, C, SHELL, PY, JSON, XML, MD, INI }

/** 按扩展名选语言；认不出来的当纯文本。 */
fun codeLangOf(name: String): CodeLang {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "java", "gradle", "scala", "groovy" -> CodeLang.KT
        "c", "h", "cpp", "hpp", "cc", "cs", "js", "ts", "tsx", "jsx", "css",
        "rs", "go", "swift", "m", "mm", "dart", "php" -> CodeLang.C
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "service", "rc", "pro" -> CodeLang.SHELL
        "py", "pyw" -> CodeLang.PY
        "json", "jsonc" -> CodeLang.JSON
        "xml", "html", "htm", "svg", "plist", "xaml", "axml", "manifest" -> CodeLang.XML
        "md", "markdown" -> CodeLang.MD
        "ini", "properties", "cfg", "conf", "toml", "yml", "yaml", "reg", "inf" -> CodeLang.INI
        else -> CodeLang.PLAIN
    }
}

/** 高亮配色（深浅色由调用方给）。 */
data class CodePalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
    val attr: Color,
)

/** 超过这么多字符就不高亮（保留纯文本），否则每次输入都要跑一遍正则。 */
const val MAX_HIGHLIGHT = 200_000

private const val K_COMMENT = 1
private const val K_STRING = 2
private const val K_KEYWORD = 3
private const val K_NUMBER = 4
private const val K_TAG = 5
private const val K_ATTR = 6

private val KT_KEYWORDS = listOf(
    "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else",
    "when", "while", "for", "do", "return", "break", "continue", "try", "catch", "finally",
    "throw", "null", "true", "false", "this", "super", "is", "as", "in", "out", "private",
    "public", "protected", "internal", "override", "open", "abstract", "sealed", "data",
    "enum", "companion", "const", "lateinit", "inline", "suspend", "by", "init", "get", "set",
    "typealias", "operator", "infix", "reified", "crossinline", "noinline", "vararg",
)

private val C_KEYWORDS = listOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
    "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
    "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
    "union", "unsigned", "void", "volatile", "while", "class", "namespace", "template",
    "public", "private", "protected", "virtual", "override", "new", "delete", "this", "nullptr",
    "true", "false", "function", "var", "let", "async", "await", "export", "import", "from",
    "interface", "type", "extends", "implements", "try", "catch", "finally", "throw",
    "instanceof", "typeof", "yield", "pub", "use", "mod", "impl", "trait", "match", "mut",
    "fn", "self", "Self", "package", "func", "defer", "go", "chan", "select", "map", "range",
)

private val SHELL_KEYWORDS = listOf(
    // bash
    "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case",
    "esac", "function", "return", "local", "export", "readonly", "declare", "set", "unset",
    "echo", "printf", "cd", "exit", "shift", "source", "eval", "exec", "test", "trap",
    // cmd / bat（用户 2026-10-29 要求 .cmd/.bat 也要高亮）
    "goto", "call", "setlocal", "endlocal", "errorlevel", "exist", "defined", "not",
    "equ", "neq", "lss", "leq", "gtr", "geq", "start", "copy", "del", "move", "ren",
    "mkdir", "rmdir", "cls", "title", "pause", "choice", "set", "pushd", "popd", "rem",
)

private val PY_KEYWORDS = listOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
    "pass", "import", "from", "as", "try", "except", "finally", "raise", "with", "lambda",
    "yield", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "None",
    "True", "False", "async", "await", "self",
)

private fun kw(list: List<String>) = Regex("\\b(?:" + list.joinToString("|") + ")\\b")

private fun rules(lang: CodeLang): List<Pair<Regex, Int>> = when (lang) {
    CodeLang.PLAIN -> emptyList()

    CodeLang.KT -> listOf(
        Regex("/\\*[\\s\\S]*?\\*/") to K_COMMENT,
        Regex("//.*") to K_COMMENT,
        Regex("\"\"\"[\\s\\S]*?\"\"\"") to K_STRING,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("'(?:\\\\.|[^'\\\\])*'") to K_STRING,
        kw(KT_KEYWORDS) to K_KEYWORD,
        Regex("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?[fFlLdD]?)\\b") to K_NUMBER,
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to K_ATTR,
    )

    CodeLang.C -> listOf(
        Regex("/\\*[\\s\\S]*?\\*/") to K_COMMENT,
        Regex("//.*") to K_COMMENT,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("'(?:\\\\.|[^'\\\\])*'") to K_STRING,
        Regex("`(?:\\\\.|[^`\\\\])*`") to K_STRING,
        kw(C_KEYWORDS) to K_KEYWORD,
        Regex("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?[fFuUlL]?)\\b") to K_NUMBER,
        Regex("#[A-Za-z_][A-Za-z0-9_]*") to K_ATTR,
    )

    CodeLang.SHELL -> listOf(
        Regex("(?m)#.*$") to K_COMMENT,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("'[^']*'") to K_STRING,
        kw(SHELL_KEYWORDS) to K_KEYWORD,
        Regex("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?") to K_ATTR,
        Regex("\\b\\d+\\b") to K_NUMBER,
    )

    CodeLang.PY -> listOf(
        Regex("(?m)#.*$") to K_COMMENT,
        Regex("\"\"\"[\\s\\S]*?\"\"\"") to K_STRING,
        Regex("'''[\\s\\S]*?'''") to K_STRING,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("'(?:\\\\.|[^'\\\\])*'") to K_STRING,
        kw(PY_KEYWORDS) to K_KEYWORD,
        Regex("\\b\\d+(?:\\.\\d+)?\\b") to K_NUMBER,
        Regex("@[A-Za-z_][A-Za-z0-9_.]*") to K_ATTR,
    )

    CodeLang.JSON -> listOf(
        Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)") to K_ATTR,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("\\b(?:true|false|null)\\b") to K_KEYWORD,
        Regex("-?\\b\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?\\b") to K_NUMBER,
    )

    CodeLang.XML -> listOf(
        Regex("<!--[\\s\\S]*?-->") to K_COMMENT,
        Regex("</?[A-Za-z_][A-Za-z0-9_:.\\-]*") to K_TAG,
        Regex("[A-Za-z_][A-Za-z0-9_:.\\-]*(?=\\s*=)") to K_ATTR,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to K_STRING,
        Regex("'(?:\\\\.|[^'\\\\])*'") to K_STRING,
    )

    CodeLang.MD -> listOf(
        Regex("(?m)^#{1,6}[ \\t].*$") to K_KEYWORD,
        Regex("```[\\s\\S]*?```") to K_COMMENT,
        Regex("`[^`\\n]+`") to K_STRING,
        Regex("\\*\\*[^*\\n]+\\*\\*") to K_ATTR,
        Regex("\\[[^\\]]*\\]\\([^)]*\\)") to K_TAG,
        Regex("(?m)^\\s*[-*+] ") to K_ATTR,
        Regex("(?m)^\\s*\\d+\\. ") to K_ATTR,
    )

    CodeLang.INI -> listOf(
        Regex("(?m)^\\s*[#;].*$") to K_COMMENT,
        Regex("(?m)^\\s*\\[[^\\]\\n]*\\]") to K_TAG,
        Regex("(?m)^\\s*[A-Za-z0-9_.\\-]+(?=\\s*[=:])") to K_ATTR,
        Regex("\\b(?:true|false|null|yes|no|on|off)\\b", RegexOption.IGNORE_CASE) to K_KEYWORD,
        Regex("\\b\\d+\\b") to K_NUMBER,
    )
}

private fun styleOf(kind: Int, p: CodePalette): SpanStyle = when (kind) {
    K_COMMENT -> SpanStyle(color = p.comment)
    K_STRING -> SpanStyle(color = p.string)
    K_KEYWORD -> SpanStyle(color = p.keyword)
    K_NUMBER -> SpanStyle(color = p.number)
    K_TAG -> SpanStyle(color = p.tag)
    else -> SpanStyle(color = p.attr)
}

/** 把 [src] 染成带颜色的 AnnotatedString（超过 [MAX_HIGHLIGHT] 或 PLAIN 就原样返回）。 */
fun highlightText(src: String, lang: CodeLang, pal: CodePalette): AnnotatedString {
    if (lang == CodeLang.PLAIN || src.isEmpty() || src.length > MAX_HIGHLIGHT) {
        return AnnotatedString(src)
    }
    val taken = BooleanArray(src.length)
    val spans = ArrayList<Pair<IntRange, SpanStyle>>()
    for ((re, kind) in rules(lang)) {
        for (m in re.findAll(src)) {
            val r = m.range
            if (r.isEmpty()) continue
            if (taken[r.first]) continue
            var ok = true
            for (i in r) {
                if (taken[i]) { ok = false; break }
            }
            if (!ok) continue
            for (i in r) taken[i] = true
            spans += r to styleOf(kind, pal)
        }
    }
    return buildAnnotatedString {
        append(src)
        spans.forEach { (r, st) -> addStyle(st, r.first, r.last + 1) }
    }
}

/**
 * 给 sora 用的**逐行**高亮：[TextLexer] 这套正则按行跑一遍，
 * 返回 `(起始列, 结束列(不含), 类别)`，类别用 [SPAN_*] 常量。
 *
 * 为什么按行：sora 的 `SimpleAnalyzeManager` 是按行喂样式的，块注释跨行时
 * 这一段会略微不准（整行都算注释），换来的是实现简单、增量快。
 */
const val SPAN_COMMENT = 1
const val SPAN_STRING = 2
const val SPAN_KEYWORD = 3
const val SPAN_NUMBER = 4
const val SPAN_TAG = 5
const val SPAN_ATTR = 6

fun lineSpans(line: String, lang: CodeLang): List<Triple<Int, Int, Int>> {
    if (lang == CodeLang.PLAIN || line.isEmpty()) return emptyList()
    val taken = BooleanArray(line.length)
    val out = ArrayList<Triple<Int, Int, Int>>()
    for ((re, kind) in rules(lang)) {
        for (m in re.findAll(line)) {
            val r = m.range
            if (r.isEmpty() || taken[r.first]) continue
            var ok = true
            for (i in r) if (taken[i]) { ok = false; break }
            if (!ok) continue
            for (i in r) taken[i] = true
            out += Triple(r.first, r.last + 1, kind)
        }
    }
    return out
}
