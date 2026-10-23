package com.github.mtdhllf.tug.core.store

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.core.store.sqlite.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置唯一数据源：写入 → 新实例读取，验证持久化。
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.getSharedPreferences("tug_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun update_persistsAcrossInstances() {
        val first = SettingsRepository(context)
        first.setConnectionsPerTask(12)
        first.setMaxConcurrentTasks(5)
        first.setGlobalDownloadLimitKib(2048)
        first.setWifiOnly(true)

        val second = SettingsRepository(context)
        assertEquals(12, second.settings.value.connectionsPerTask)
        assertEquals(5, second.settings.value.maxConcurrentTasks)
        assertEquals(2048L, second.settings.value.globalDownloadLimitKib)
        assertTrue(second.settings.value.wifiOnly)
        assertEquals(false, second.settings.value.btChooseFilesBeforeStart)

        second.setBtChooseFilesBeforeStart(true)
        assertTrue(SettingsRepository(context).settings.value.btChooseFilesBeforeStart)

        second.setGlobalDownloadLimitKib(null)
        val third = SettingsRepository(context)
        assertNull(third.settings.value.globalDownloadLimitKib)
    }

    @Test
    fun invalidValues_areClamped() {
        val repo = SettingsRepository(context)
        repo.setConnectionsPerTask(100)
        repo.setMaxConcurrentTasks(0)
        assertEquals(32, repo.settings.value.connectionsPerTask)
        assertEquals(1, repo.settings.value.maxConcurrentTasks)
    }
}
