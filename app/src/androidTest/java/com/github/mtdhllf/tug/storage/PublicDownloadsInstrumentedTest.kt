package com.github.mtdhllf.tug.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证完成文件能真正落到公共下载目录并读回一致内容（Android 专有路径）。
 */
@RunWith(AndroidJUnit4::class)
class PublicDownloadsInstrumentedTest {

    @Test
    fun publish_writesFileIntoPublicDownloadsAndRemovesStaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = File(context.cacheDir, "tug-publish-test.bin")
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        staging.writeBytes(payload)

        val preferred = "tug-s0-publish-test.bin"
        val result = PublicDownloads.publish(context, staging, preferred)

        assertTrue("publish 失败: $result", result is PublishResult.Success)
        result as PublishResult.Success
        assertFalse("暂存文件应已删除", staging.exists())
        assertTrue(
            "应落在 ${PublicDownloads.displayDir}，实际 ${result.location}",
            result.location.contains("Download", ignoreCase = true) ||
                result.location.startsWith("content://"),
        )

        val resolver = context.contentResolver
        if (result.location.startsWith("content://")) {
            val uri = Uri.parse(result.location)
            val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(payload, bytes)
            assertEquals(1, resolver.delete(uri, null, null))
        } else {
            val file = File(result.location)
            assertArrayEquals(payload, file.readBytes())
            assertTrue(file.delete())
        }
    }
}
