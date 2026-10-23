package com.github.mtdhllf.tug.power

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证下载期间唤醒锁的获取与释放（真机，需 WAKE_LOCK 权限）。
 */
@RunWith(AndroidJUnit4::class)
class DownloadKeepAliveInstrumentedTest {

    @Test
    fun acquireAndRelease_tracksActiveDownloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keepAlive = DownloadKeepAlive(context)

        keepAlive.onActiveChanged(true)
        assertTrue("下载开始时应持有唤醒锁", keepAlive.isHeld)

        keepAlive.onActiveChanged(true)
        assertTrue("重复通知不应重复持锁", keepAlive.isHeld)

        keepAlive.onActiveChanged(false)
        assertFalse("下载结束时应释放唤醒锁", keepAlive.isHeld)
    }
}
