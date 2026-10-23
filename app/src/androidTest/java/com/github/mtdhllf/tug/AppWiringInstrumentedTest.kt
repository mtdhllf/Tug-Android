package com.github.mtdhllf.tug

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskEvent
import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用 App 真实装配（TugApp.taskManager）验证：
 * 新建任务 → 多连接下载 → 完成事件 → 发布到 Download/Tug → 位置回写。
 */
@RunWith(AndroidJUnit4::class)
class AppWiringInstrumentedTest {

    private lateinit var app: TugApp
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var taskId: Long? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        android.util.Log.i("TugTest", "tearDown start taskId=$taskId")
        taskId?.let { id ->
            runBlocking {
                runCatching { app.taskManager.remove(id, deleteFiles = true) }
            }
        }
        android.util.Log.i("TugTest", "tearDown removed")
        runCatching { server.shutdown() }
        android.util.Log.i("TugTest", "tearDown server down")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun addTask_viaAppManager_publishesCompletedFile() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.dispatcher = rangeDispatcher(payload)

        // 确保启动恢复已完成（避免 markInterrupted 与本任务竞争）
        app.taskManager.bootstrap()

        val completed = CompletableDeferred<TaskEvent.Completed>()
        val collector = scope.launch {
            app.taskManager.events.collect { event ->
                if (event is TaskEvent.Completed && event.id == taskId) {
                    completed.complete(event)
                }
            }
        }

        taskId = app.taskManager.add(
            NewTask(url = server.url("/wiring.bin").toString(), connections = 4),
        )
        val event = withTimeout(60_000) { completed.await() }
        assertEquals(event.id, taskId)

        // 等待 TugApp 完成发布并回写位置
        var view = app.taskManager.list().first { it.id == taskId }
        withTimeout(20_000) {
            while (view.state != TaskState.Completed || !view.path.startsWith("content://")) {
                delay(100)
                view = app.taskManager.list().first { it.id == taskId }
            }
        }

        val uri = Uri.parse(view.path)
        val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(payload, bytes)
        assertTrue(
            "应位于公共下载目录，实际 ${view.path}",
            view.path.startsWith("content://"),
        )
        collector.cancel()
    }

    @Test
    fun pauseThenResume_continuesAndCompletes() = runBlocking {
        // 2MB / 4 连接 → 自适应分片 500KB，限速下总时长约 2.5s，留足暂停窗口
        val payload = ByteArray(2_000_000) { (it % 251).toByte() }
        server.dispatcher = slowRangeDispatcher(payload)
        app.taskManager.bootstrap()

        val id = app.taskManager.add(
            NewTask(url = server.url("/pause-resume.bin").toString(), connections = 4),
        )
        taskId = id

        // 等到有字节落盘
        withTimeout(30_000) {
            while (app.taskManager.list().first { it.id == id }.downloaded <= 0L) delay(50)
        }

        app.taskManager.pause(id)
        val paused = app.taskManager.list().first { it.id == id }
        android.util.Log.i(
            "TugTest",
            "paused state=${paused.state} bytes=${paused.downloaded} error=${paused.error}",
        )
        assertEquals("暂停后状态应为 Paused", TaskState.Paused, paused.state)
        val bytesAtPause = paused.downloaded
        assertTrue(
            "暂停时应已有进度: state=${paused.state} downloaded=${bytesAtPause} " +
                "total=${paused.total} error=${paused.error}",
            bytesAtPause > 0,
        )

        // 暂停后不得继续增长
        delay(1_000)
        val afterPause = app.taskManager.list().first { it.id == id }
        android.util.Log.i(
            "TugTest",
            "afterPause bytes=${afterPause.downloaded} state=${afterPause.state}",
        )
        assertEquals("暂停后字节数不应变化", bytesAtPause, afterPause.downloaded)

        android.util.Log.i("TugTest", "resuming")
        app.taskManager.resume(id)
        android.util.Log.i("TugTest", "resumed")
        withTimeout(120_000) {
            while (app.taskManager.list().first { it.id == id }.state != TaskState.Completed) {
                delay(200)
            }
        }

        var view = app.taskManager.list().first { it.id == id }
        withTimeout(20_000) {
            while (!view.path.startsWith("content://")) {
                delay(100)
                view = app.taskManager.list().first { it.id == id }
            }
        }
        val bytes = app.contentResolver.openInputStream(Uri.parse(view.path))!!.use { it.readBytes() }
        assertArrayEquals("续传后内容必须与源一致", payload, bytes)
    }

    private fun slowRangeDispatcher(payload: ByteArray): Dispatcher = object : Dispatcher() {
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
                    .throttleBody(8 * 1024L, 40, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(payload))
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
