package com.github.mtdhllf.tug.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.http.HttpDownloadEngine
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机端到端：多连接下载 → 完成 → 发布到公共下载目录 → 读回校验。
 */
@RunWith(AndroidJUnit4::class)
class DownloadEndToEndInstrumentedTest {

    @Test
    fun multiConnectionDownload_thenPublishToPublicDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().apply { start() }
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        server.dispatcher = rangeDispatcher(payload)

        val staging = File(context.cacheDir, "tug-e2e").apply {
            deleteRecursively()
            mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = HttpDownloadEngine(scope = scope, segmentSize = 16_384)

        try {
            val terminal = scope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.events()
                    .filterIsInstance<EngineEvent.State>()
                    .first { it.state == TaskState.Completed || it.state == TaskState.Failed }
            }
            engine.start(
                taskId = 1L,
                input = NewTask(url = server.url("/e2e.bin").toString(), connections = 4),
                saveDir = staging.absolutePath,
            )
            val event = withTimeout(60_000) { terminal.await() }
            assertEquals("下载失败: ${event.error}", TaskState.Completed, event.state)

            val downloaded = File(staging, "e2e.bin")
            assertTrue(downloaded.exists())
            assertArrayEquals(payload, downloaded.readBytes())

            val published = PublicDownloads.publish(context, downloaded, "tug-s0-e2e.bin")
            assertTrue("发布失败: $published", published is PublishResult.Success)
            published as PublishResult.Success
            assertFalse("发布后暂存文件应删除", downloaded.exists())

            val resolver = context.contentResolver
            if (published.location.startsWith("content://")) {
                val uri = Uri.parse(published.location)
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertArrayEquals(payload, bytes)
                assertEquals(1, resolver.delete(uri, null, null))
            } else {
                val file = File(published.location)
                assertArrayEquals(payload, file.readBytes())
                assertTrue(file.delete())
            }
        } finally {
            engine.shutdown()
            server.shutdown()
            staging.deleteRecursively()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun rangeDispatcher(payload: ByteArray): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            val total = payload.size
            if (range == "bytes=0-0") {
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/$total")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(payload, 0, 1))
            }
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=")
                val start = spec.substringBefore('-').toLong()
                val end = spec.substringAfter('-').toLong().coerceAtMost(total - 1L)
                val slice = payload.copyOfRange(start.toInt(), end.toInt() + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/$total")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(slice))
            }
            return MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(payload))
        }
    }
}
