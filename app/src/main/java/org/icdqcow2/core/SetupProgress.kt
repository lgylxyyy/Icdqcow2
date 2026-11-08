package org.icdqcow2.core

import android.content.Context

/**
 * 「全流程装机」的续做进度。
 *
 * 只在用户退出时**主动勾了**「保存当前进度」才写；默认清空。
 * 下次进向导时：文件还在 → 直接回到那一页继续操作该文件；文件没了 → 直接全新开始。
 */
object SetupProgress {

    private const val FILE = "setup_progress"
    private const val K_STEP = "step"
    private const val K_IMAGE = "image"

    /** [step] 沿用向导里的编号：2 = 释放镜像，3 = 全盘检查（1 是选/建盘，没什么可续的）。 */
    data class Saved(val step: Int, val image: String)

    fun load(c: Context): Saved? {
        val sp = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val step = sp.getInt(K_STEP, 0)
        val image = sp.getString(K_IMAGE, null)
        if (step < 2 || image.isNullOrBlank()) return null
        return Saved(step, image)
    }

    fun save(c: Context, step: Int, image: String) {
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_STEP, step)
            .putString(K_IMAGE, image)
            .apply()
    }

    fun clear(c: Context) {
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}