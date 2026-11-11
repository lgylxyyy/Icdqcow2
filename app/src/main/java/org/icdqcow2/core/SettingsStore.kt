package org.icdqcow2.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * raw 映像复制/移动时的默认策略（T4/A3）。
 *
 * raw 的 st_size 是容量、st_blocks 才是占用。复制或跨卷移动时有两种结果：
 * 保持稀疏（只写有数据的部分）或按完整容量写（目标实体化）。
 * ASK = 每次询问，用户当场决定。
 */
enum class RawCopyMode(val id: Int) {
    KEEP(0), FULL(1), ASK(2);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: KEEP
    }
}

enum class ThemeMode(val id: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * 设置。第一版用 SharedPreferences（零依赖），接口按“键值 + StateFlow”设计，
 * 以后换 DataStore 只改这一个文件。
 */
class SettingsStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(ThemeMode.of(sp.getInt(KEY_THEME, 0)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** 界面语言偏好：`I18n.SYSTEM_LANG`（跟随系统）或具体语言码（zh / en / …）。 */
    private val _language = MutableStateFlow(sp.getString(KEY_LANG, I18n.SYSTEM_LANG) ?: I18n.SYSTEM_LANG)
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(code: String) {
        sp.edit().putString(KEY_LANG, code).apply()
        _language.value = code
    }

    /** 「选择镜像」页里历史文件显示几条（默认 3）。 */
    private val _historyCount = MutableStateFlow(sp.getInt(KEY_HISTORY, 3))
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()

    fun setHistoryCount(n: Int) {
        sp.edit().putInt(KEY_HISTORY, n).apply()
        _historyCount.value = n
    }

    private val _dynamicColor = MutableStateFlow(sp.getBoolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /**
     * 顶部渐变模糊 + 悬浮菜单栏。
     *
     * 关掉之后：顶部不再画模糊带，底部导航从悬浮胶囊换成通栏。
     *
     * **门槛**：[BLUR_MIN_SDK]（Android 13 / API 33）。Miuix 的模糊走 `RuntimeShader`，
     * 33 以下根本建不出来；低于这个版本一律按关闭处理，且开关置灰不给点。
     */
    private val _visualEffects = MutableStateFlow(
        blurSupported && sp.getBoolean(KEY_VISUAL_EFFECTS, true),
    )
    val visualEffects: StateFlow<Boolean> = _visualEffects.asStateFlow()

    /** 本机能不能画模糊（API 33+）。 */
    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= BLUR_MIN_SDK

    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putInt(KEY_THEME, mode.id).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(on: Boolean) {
        sp.edit().putBoolean(KEY_DYNAMIC, on).apply()
        _dynamicColor.value = on
    }

    fun setVisualEffects(on: Boolean) {
        if (!blurSupported) return
        sp.edit().putBoolean(KEY_VISUAL_EFFECTS, on).apply()
        _visualEffects.value = on
    }

    // ── 下面这些先占位，等做文件页/设置页时再逐项接上 ──
    /**
     * 重名策略：`ask`（弹窗问）/ `rename`（自动加 (1)(2)…）/ `overwrite` / `skip`。
     * 用 StateFlow：设置页的开关和文件页的弹窗写的是同一个值。
     */
    private val _conflictPolicy = MutableStateFlow(sp.getString("conflict", "ask") ?: "ask")
    val conflictPolicyFlow: StateFlow<String> = _conflictPolicy.asStateFlow()
    fun setConflictPolicy(v: String) {
        sp.edit().putString("conflict", v).apply()
        _conflictPolicy.value = v
    }

    var confirmDelete: Boolean
        get() = sp.getBoolean("confirm_delete", true)
        set(v) = sp.edit().putBoolean("confirm_delete", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit().putBoolean("show_hidden", v).apply()

    /**
     * 文件页是否显示系统"回收站"文件（`.trashed-<过期时间>-原名`）。
     * 用 StateFlow 而不是普通属性：设置页和文件页两处都要跟着变。
     */
    private val _showTrash = MutableStateFlow(sp.getBoolean("show_trash", false))
    val showTrash: StateFlow<Boolean> = _showTrash.asStateFlow()
    fun setShowTrash(on: Boolean) {
        sp.edit().putBoolean("show_trash", on).apply()
        _showTrash.value = on
    }

    /** 删除时是否移入系统回收站（MediaStore.createTrashRequest）。 */
    private val _deleteToTrash = MutableStateFlow(sp.getBoolean("delete_to_trash", false))
    val deleteToTrash: StateFlow<Boolean> = _deleteToTrash.asStateFlow()
    fun setDeleteToTrash(on: Boolean) {
        sp.edit().putBoolean("delete_to_trash", on).apply()
        _deleteToTrash.value = on
    }

    /** raw 复制行为（默认为保持稀疏）。 */
    private val _rawCopyMode = MutableStateFlow(RawCopyMode.of(sp.getInt(KEY_RAWCOPY, 0)))
    val rawCopyMode: StateFlow<RawCopyMode> = _rawCopyMode.asStateFlow()

    fun setRawCopyMode(m: RawCopyMode) {
        sp.edit().putInt(KEY_RAWCOPY, m.id).apply()
        _rawCopyMode.value = m
    }

    var logLevel: Int
        get() = sp.getInt("log_level", 2)
        set(v) = sp.edit().putInt("log_level", v).apply()

    companion object {
        /** 渐变模糊的最低系统版本：RuntimeShader 自 API 33 起可用。 */
        const val BLUR_MIN_SDK = Build.VERSION_CODES.TIRAMISU

        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANG = "lang_code"
        private const val KEY_HISTORY = "history_count"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_VISUAL_EFFECTS = "visual_effects"
        private const val KEY_RAWCOPY = "raw_copy_mode"
    }
}