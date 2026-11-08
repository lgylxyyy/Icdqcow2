package org.icdqcow2.core

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 界面文案表（数据驱动）。
 *
 * 文案存在 `assets/i18n/ui-strings.json`：一个 key → 多语言列的二维表。
 * **新增语言只改这个 JSON**：在 `languages` 里加一条 `{code,label}`，
 * 再给每条字符串补上同名的语言键即可——设置页的语言列表也会自动多一项，不用动 Kotlin。
 *
 * 取不到译文时的回退顺序：当前语言 → en → zh → 该 key 的第一列，永不抛异常。
 */
object I18n {

    const val SYSTEM_LANG = "system"

    private const val ASSET = "i18n/ui-strings.json"

    data class Language(val code: String, val label: String)

    /** key → (语言码 → 文案) */
    private val raw = ConcurrentHashMap<String, Map<String, String>>()
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var languages: List<Language> = listOf(
        Language("zh", "简体中文"),
        Language("en", "English"),
    )

    fun load(ctx: Context) {
        runCatching {
            val text = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            root.optJSONArray("languages")?.let { arr ->
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val code = o.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Language(code, o.optString("label", code))
                }
                if (list.isNotEmpty()) languages = list
            }

            root.optJSONObject("strings")?.let { strings ->
                val keys = strings.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val o = strings.optJSONObject(key) ?: continue
                    val cols = HashMap<String, String>()
                    val it2 = o.keys()
                    while (it2.hasNext()) {
                        val code = it2.next()
                        cols[code] = o.optString(code)
                    }
                    raw[key] = cols
                }
            }
        }
        tables.clear()
    }

    /** 可选语言（顺序即设置页显示顺序）。 */
    fun languages(): List<Language> = languages

    /** 当前生效的语言码：`system` → 系统语言（有译文才用）→ en。 */
    fun resolve(pref: String): String {
        if (pref != SYSTEM_LANG && languages.any { it.code == pref }) return pref
        val sys = Locale.getDefault().language
        if (languages.any { it.code == sys }) return sys
        if (languages.any { it.code == "en" }) return "en"
        return languages.firstOrNull()?.code ?: "en"
    }

    /** 某种语言**解析后**的扁平表（已按回退规则填好）。 */
    fun table(code: String): Map<String, String> = tables.getOrPut(code) {
        val out = HashMap<String, String>(raw.size)
        raw.forEach { (key, cols) ->
            out[key] = cols[code]
                ?: cols["en"]
                ?: cols["zh"]
                ?: cols.values.firstOrNull()
                ?: ""
        }
        out
    }

    fun t(code: String, key: String, vararg args: Any?): String =
        format(table(code)[key] ?: key, args)

    /** `{0}` `{1}` 占位符替换（不用 String.format，避免 `%` 冲突）。 */
    fun format(template: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return template
        var out = template
        args.forEachIndexed { i, arg ->
            out = out.replace("{$i}", arg?.toString() ?: "")
        }
        return out
    }
}