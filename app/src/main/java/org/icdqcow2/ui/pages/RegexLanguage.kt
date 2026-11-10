package org.icdqcow2.ui.pages

import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.textStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 高亮用的**自定义颜色 id**。
 *
 * sora 的 Span 里存的是"调色板下标"而不是 ARGB，真正颜色由 `EditorColorScheme` 给；
 * 所以这里申请几个自己的 id（避开它内置的常量段），在编辑器初始化时 `setColor(id, argb)`。
 */
object HiColor {
    const val COMMENT = 2001
    const val STRING = 2002
    const val KEYWORD = 2003
    const val NUMBER = 2004
    const val TAG = 2005
    const val ATTR = 2006

    fun idOf(kind: Int): Int = when (kind) {
        SPAN_COMMENT -> COMMENT
        SPAN_STRING -> STRING
        SPAN_KEYWORD -> KEYWORD
        SPAN_NUMBER -> NUMBER
        SPAN_TAG -> TAG
        else -> ATTR
    }
}

/**
 * 把 [TextLexer] 那套正则接到 sora 上：实现它的 `Language` + `SimpleAnalyzeManager`。
 *
 * sora 是**按行**喂样式的：`analyze` 里把整段文本切成行，每行用自己的正则跑一遍，
 * 再按"列号"塞 span。这样增量修改时只需要重排改动的那几行，20 万字符以内都很快。
 */
class RegexLanguage(private val lang: CodeLang) : Language {

    private val manager = object : SimpleAnalyzeManager<Void?>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Void?>): Styles {
            val builder = MappedSpans.Builder()
            val normal = textStyle(
                EditorColorScheme.TEXT_NORMAL, 0, false, false, false, false,
            )
            var start = 0
            while (start <= text.length) {
                var end = text.indexOf("\n", start)
                if (end < 0) end = text.length
                val line = text.substring(start, end)
                builder.add(0, SpanFactory.obtain(0, normal))
                for ((s, _, kind) in lineSpans(line, lang)) {
                    if (s >= line.length) continue
                    val style = textStyle(HiColor.idOf(kind), 0, false, false, false, false)
                    builder.add(s, SpanFactory.obtain(s, style))
                }
                start = end + 1
                if (end >= text.length) break
            }
            return Styles(builder.build())
        }
    }

    override fun getAnalyzeManager() = manager

    override fun getInterruptionLevel() = Language.INTERRUPTION_LEVEL_SLIGHT

    override fun requireAutoComplete(
        source: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: android.os.Bundle,
    ) = Unit

    override fun getIndentAdvance(source: ContentReference, line: Int, column: Int) = 0

    override fun useTab() = false

    /** 不做格式化：给一个空实现（接口要求非空，sora 会拿它做"格式化文档"菜单，我们不提供）。 */
    override fun getFormatter(): Formatter = object : Formatter {
        override fun format(content: io.github.rosemoe.sora.text.Content, range: io.github.rosemoe.sora.text.TextRange) = Unit
        override fun formatRegion(
            content: io.github.rosemoe.sora.text.Content,
            range: io.github.rosemoe.sora.text.TextRange,
            region: io.github.rosemoe.sora.text.TextRange,
        ) = Unit
        override fun setReceiver(receiver: Formatter.FormatResultReceiver?) = Unit
        override fun isRunning() = false
        override fun destroy() = Unit
    }

    override fun getSymbolPairs(): SymbolPairMatch? = null

    override fun getNewlineHandlers(): Array<NewlineHandler>? = null

    override fun destroy() = Unit
}