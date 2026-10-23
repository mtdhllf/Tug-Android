package com.github.mtdhllf.tug.core.store.sqlite

import android.content.Context
import com.github.mtdhllf.tug.core.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置唯一数据源（SharedPreferences + StateFlow，无第三方依赖）。
 *
 * 目前持久化与下载行为相关的字段；主题/通知等字段留给 UI 阶段接入。
 */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        persist(next)
        _settings.value = next
    }

    fun setConnectionsPerTask(value: Int) = update {
        it.copy(connectionsPerTask = value.coerceIn(1, 32))
    }

    fun setMaxConcurrentTasks(value: Int) = update {
        it.copy(maxConcurrentTasks = value.coerceIn(1, 16))
    }

    fun setGlobalDownloadLimitKib(value: Long?) = update {
        it.copy(globalDownloadLimitKib = value?.takeIf { v -> v > 0 })
    }

    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnly = value) }

    fun setTheme(mode: com.github.mtdhllf.tug.core.model.ThemeMode) = update {
        it.copy(theme = mode)
    }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setBtChooseFilesBeforeStart(enabled: Boolean) = update {
        it.copy(btChooseFilesBeforeStart = enabled)
    }

    private fun load(): Settings = Settings(
        connectionsPerTask = prefs.getInt(KEY_CONNECTIONS, 8).coerceIn(1, 32),
        maxConcurrentTasks = prefs.getInt(KEY_CONCURRENT, 3).coerceIn(1, 16),
        globalDownloadLimitKib = if (prefs.contains(KEY_GLOBAL_LIMIT)) {
            prefs.getLong(KEY_GLOBAL_LIMIT, 0L).takeIf { it > 0 }
        } else {
            null
        },
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
        theme = com.github.mtdhllf.tug.core.model.ThemeMode.entries
            .firstOrNull { it.name == prefs.getString(KEY_THEME, null) }
            ?: com.github.mtdhllf.tug.core.model.ThemeMode.System,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
        btChooseFilesBeforeStart = prefs.getBoolean(KEY_BT_CHOOSE_FILES, false),
    )

    private fun persist(settings: Settings) {
        prefs.edit()
            .putInt(KEY_CONNECTIONS, settings.connectionsPerTask)
            .putInt(KEY_CONCURRENT, settings.maxConcurrentTasks)
            .putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putBoolean(KEY_BT_CHOOSE_FILES, settings.btChooseFilesBeforeStart)
            .apply {
                val limit = settings.globalDownloadLimitKib
                if (limit == null) remove(KEY_GLOBAL_LIMIT) else putLong(KEY_GLOBAL_LIMIT, limit)
            }
            .apply()
    }

    private companion object {
        const val PREFS = "tug_settings"
        const val KEY_CONNECTIONS = "connections_per_task"
        const val KEY_CONCURRENT = "max_concurrent_tasks"
        const val KEY_GLOBAL_LIMIT = "global_download_limit_kib"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_BT_CHOOSE_FILES = "bt_choose_files_before_start"
    }
}
