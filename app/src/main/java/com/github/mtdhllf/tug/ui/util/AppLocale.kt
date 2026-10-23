package com.github.mtdhllf.tug.ui.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言：跟随系统 / 简体中文 / English。
 *
 * Android 13+ 由系统的 per-app language 保存；低版本由本类持久化并交给
 * AppCompatDelegate 应用。空字符串表示跟随系统。
 */
object AppLocale {
    private const val PREFS = "tug_locale"
    private const val KEY_TAG = "language_tag"

    const val SYSTEM = ""
    const val ZH_CN = "zh-CN"
    const val ZH_TW = "zh-TW"
    const val EN = "en"
    const val ES = "es"
    const val HI = "hi"
    const val AR = "ar"

    fun savedTag(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, SYSTEM)
            .orEmpty()

    fun set(context: Context, tag: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, tag)
            .apply()
        applyLocales(tag)
    }

    fun restore(context: Context) {
        val tag = savedTag(context)
        if (tag.isNotBlank()) applyLocales(tag)
    }

    private fun applyLocales(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
