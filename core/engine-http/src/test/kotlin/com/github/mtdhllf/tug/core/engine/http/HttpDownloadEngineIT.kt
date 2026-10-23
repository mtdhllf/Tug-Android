package com.github.mtdhllf.tug.core.engine.http

import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpDownloadEngineIT {
    private lateinit var server: MockWebServer
    private lateinit var engine: HttpDownloadEngine
    private lateinit var tmpDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = HttpDownloadEngine(scope = scope, segmentSize = 16_384)
        tmpDir = File.createTempFile("tug-http-it", null).apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        engine.shutdown()
        server.shutdown()
        tmpDir.deleteRecursively()
    }

    @Test
    fun multiConnectionDownload_writesCorrectContent() = runBlocking {
        val payload = ByteArray(80_000) { (it % 251).toByte() }
        serveRange(payload, etag = "\"v1\"")

        val event = startAndAwait(1, NewTask(url = fileUrl("file.bin"), connections = 4))
        assertEquals(TaskState.Completed, event.state)

        val out = File(tmpDir, "file.bin")
        assertTrue(out.exists())
        assertEquals(payload.size.toLong(), out.length())
        assertArrayEquals(payload, out.readBytes())
        assertFalse("完成后不应残留 .part", File(tmpDir, "file.bin.part").exists())
        assertFalse("完成后不应残留 meta", HttpDownloadEngine.metaFile(tmpDir, "file.bin").exists())
    }

    @Test
    fun singleStream_whenNoRange_stillDownloads() = runBlocking {
        val payload = ByteArray(30_000) { it.toByte() }
        serveSingleStreamOnly(payload, fileName = "single.dat")

        val event = startAndAwait(2, NewTask(url = server.url("/no-range").toString()))
        assertEquals(TaskState.Completed, event.state)

        val out = File(tmpDir, "single.dat")
        assertTrue(out.exists())
        assertArrayEquals(payload, out.readBytes())
    }

    /** 预置 part + meta，验证只补缺失区间且最终字节正确（真实续传路径）。 */
    @Test
    fun resumeFromMeta_downloadsOnlyMissingRanges() = runBlocking {
        val payload = ByteArray(60_000) { (it % 17).toByte() }
        val name = "resume.bin"
        val url = fileUrl(name)
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        serveRange(payload, etag = "\"v1\"", recordedRanges = ranges)

        // 前 16384 字节视为已完成：写入正确数据 + meta
        val part = File(tmpDir, "$name.part")
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(payload.size.toLong())
            raf.seek(0)
            raf.write(payload, 0, 16_384)
        }
        val meta = HttpMeta(
            taskId = 3,
            url = url,
            total = payload.size.toLong(),
            segmentSize = 16_384,
            completed = listOf(listOf(0L, 16_384L)),
            fileName = name,
            rangeSupported = true,
            etag = "\"v1\"",
        )
        HttpDownloadEngine.metaFile(tmpDir, name).writeText(
            json.encodeToString(HttpMeta.serializer(), meta),
        )

        val event = startAndAwait(3, NewTask(url = url, connections = 3))
        assertEquals(TaskState.Completed, event.state)
        assertArrayEquals(payload, File(tmpDir, name).readBytes())
        assertTrue(
            "应只请求缺失区间，实际请求: $ranges",
            ranges.any { it.startsWith("bytes=16384-") },
        )
    }

    @Test
    fun existingFile_isNeverOverwritten() = runBlocking {
        val v1 = ByteArray(20_000) { 1 }
        val v2 = ByteArray(20_000) { 2 }
        serveRange(v1, etag = "\"v1\"")
        assertEquals(TaskState.Completed, startAndAwait(4, NewTask(url = fileUrl("etag.bin"), connections = 2)).state)

        serveRange(v2, etag = "\"v2\"")
        assertEquals(TaskState.Completed, startAndAwait(5, NewTask(url = fileUrl("etag.bin"), connections = 2)).state)

        assertArrayEquals("原文件必须保持不变", v1, File(tmpDir, "etag.bin").readBytes())
        val second = tmpDir.listFiles()!!.first { it.name.startsWith("etag (1)") }
        assertArrayEquals(v2, second.readBytes())
    }

    /** 分片响应 Content-Range 起点与请求不一致时不得报完成（防假完成）。 */
    @Test
    fun mismatchedContentRange_failsWithoutCompleting() = runBlocking {
        val payload = ByteArray(40_000) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: ""
                if (range == "bytes=0-0") {
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/${payload.size}")
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(payload, 0, 1))
                }
                // 故意返回错误起点
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-99/${payload.size}")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(payload, 0, 100))
            }
        }
        val event = withTimeout(20_000) {
            startAndAwait(6, NewTask(url = fileUrl("mismatch.bin"), connections = 1))
        }
        assertEquals(TaskState.Failed, event.state)
        assertFalse("失败时不得产生最终文件", File(tmpDir, "mismatch.bin").exists())
    }

    @Test
    fun pause_stopsQuicklyAndKeepsResumableState() = runBlocking {
        // 约 2MB、每 16KB 分片限速 8KB/40ms → 总时长 ~5s，留足暂停窗口
        val payload = ByteArray(2_000_000) { (it % 251).toByte() }
        serveRangeSlowly(payload, etag = "\"v1\"")

        engine.start(
            taskId = 7,
            input = NewTask(url = fileUrl("slow.bin"), connections = 2),
            saveDir = tmpDir.absolutePath,
        )
        // 等到确实有字节落盘
        withTimeout(20_000) {
            while ((engine.snapshot(7)?.downloaded ?: 0L) <= 0L) delay(20)
        }

        val startedAt = System.currentTimeMillis()
        engine.pause(7)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("暂停应在 1s 内生效，实际 ${elapsed}ms", elapsed < 1_000)
        assertEquals(TaskState.Paused, engine.snapshot(7)?.state)
        assertTrue("应保留可续传的 .part", File(tmpDir, "slow.bin.part").exists())
        assertTrue(
            "应保留续传元数据",
            HttpDownloadEngine.metaFile(tmpDir, "slow.bin").exists(),
        )
    }

    @Test
    fun pauseThenResume_fromPartialOffset_completes() = runBlocking {
        val payload = ByteArray(2_000_000) { (it % 251).toByte() }
        serveRangeSlowly(payload, etag = "\"v1\"")

        engine.start(
            taskId = 8,
            input = NewTask(url = fileUrl("pause-resume.bin"), connections = 4),
            saveDir = tmpDir.absolutePath,
        )
        withTimeout(20_000) {
            while ((engine.snapshot(8)?.downloaded ?: 0L) <= 0L) delay(20)
        }
        engine.pause(8)
        val pausedBytes = engine.snapshot(8)!!.downloaded
        assertTrue("暂停后应保留进度，实际 $pausedBytes", pausedBytes > 0)
        val meta = HttpDownloadEngine.metaFile(tmpDir, "pause-resume.bin")
        assertTrue("应保存续传元数据", meta.exists())
        assertTrue("meta 应包含 partial 偏移", meta.readText().contains("\"partial\""))

        engine.start(
            taskId = 8,
            input = NewTask(url = fileUrl("pause-resume.bin"), connections = 4),
            saveDir = tmpDir.absolutePath,
        )
        val event = withTimeout(90_000) {
            engine.events()
                .filterIsInstance<EngineEvent.State>()
                .first {
                    it.taskId == 8L &&
                        (it.state == TaskState.Completed || it.state == TaskState.Failed)
                }
        }
        assertEquals("续传失败: ${event.error}", TaskState.Completed, event.state)
        assertArrayEquals(payload, File(tmpDir, "pause-resume.bin").readBytes())
    }

    /** 无 Range 服务器：暂停后清掉半成品，并给出"不可续传"提示。 */
    @Test
    fun singleStream_pause_cleansPartialAndMarksNotResumable() = runBlocking {
        val payload = ByteArray(600_000) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Disposition", "attachment; filename=\"single-pause.bin\"")
                .setHeader("Content-Length", payload.size)
                .setBody(Buffer().write(payload))
                .throttleBody(8 * 1024L, 40, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        engine.start(
            taskId = 9,
            input = NewTask(url = server.url("/single-pause.bin").toString(), connections = 4),
            saveDir = tmpDir.absolutePath,
        )
        withTimeout(20_000) {
            while ((engine.snapshot(9)?.downloaded ?: 0L) <= 0L) delay(20)
        }
        engine.pause(9)

        val snapshot = engine.snapshot(9)
        assertEquals(TaskState.Paused, snapshot?.state)
        assertTrue(
            "应提示不可续传，实际 ${snapshot?.error}",
            snapshot?.error?.contains("不支持断点续传") == true,
        )
        assertFalse("单流暂停后不应保留 .part", File(tmpDir, "single-pause.bin.part").exists())
        assertFalse(
            "单流暂停后不应保留 meta",
            HttpDownloadEngine.metaFile(tmpDir, "single-pause.bin").exists(),
        )
    }

    /** 新任务的分片大小按文件/连接数自适应。 */
    @Test
    fun freshDownload_usesAdaptiveSegmentSize() = runBlocking {
        val payload = ByteArray(400_000) { (it % 251).toByte() }
        serveRangeSlowly(payload, etag = "\"v1\"")

        engine.start(
            taskId = 10,
            input = NewTask(url = fileUrl("adaptive.bin"), connections = 4),
            saveDir = tmpDir.absolutePath,
        )
        withTimeout(20_000) {
            while ((engine.snapshot(10)?.downloaded ?: 0L) <= 0L) delay(20)
        }
        engine.pause(10)

        val meta = HttpDownloadEngine.metaFile(tmpDir, "adaptive.bin")
        assertTrue("应保留 meta", meta.exists())
        // 本测试引擎 preferred=16 KiB；400_000/4=100_000，但下限 64 KiB 生效
        assertTrue(
            "分片大小应为 65536，实际 ${meta.readText()}",
            meta.readText().contains("\"segmentSize\":65536"),
        )
    }

    @Test
    fun sanitizeAndHelpers_stable() {
        assertEquals("a_b_c__.iso", HttpDownloadEngine.sanitizeFileName("a/b:c*?.iso"))
        assertEquals(12345L, HttpDownloadEngine.parseContentRangeTotal("bytes 0-0/12345"))
        assertEquals(16_384L, HttpDownloadEngine.parseContentRangeStart("bytes 16384-32767/60000"))
        assertTrue(HttpDownloadEngine.computePending(100, 30, listOf(30L..59L)).isNotEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun startAndAwait(taskId: Long, input: NewTask): EngineEvent.State {
        val terminal = scope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.events()
                .filterIsInstance<EngineEvent.State>()
                .first {
                    it.taskId == taskId &&
                        (it.state == TaskState.Completed ||
                            it.state == TaskState.Failed ||
                            it.state == TaskState.Paused)
                }
        }
        engine.start(taskId = taskId, input = input, saveDir = tmpDir.absolutePath)
        return withTimeout(30_000) { terminal.await() }
    }

    private fun fileUrl(name: String): String = server.url("/$name").toString()

    private fun serveRange(
        payload: ByteArray,
        etag: String,
        recordedRanges: MutableList<String>? = null,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) recordedRanges?.add(range)
                val total = payload.size
                if (range == "bytes=0-0") {
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/$total")
                        .setHeader("ETag", etag)
                        .setHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(payload, 0, 1))
                }
                if (range != null && range.startsWith("bytes=")) {
                    val spec = range.removePrefix("bytes=")
                    val start = spec.substringBefore('-').toLong()
                    val end = spec.substringAfter('-').toLong().coerceAtMost(total - 1L)
                    val length = (end - start + 1).toInt()
                    val slice = payload.copyOfRange(start.toInt(), start.toInt() + length)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/$total")
                        .setHeader("ETag", etag)
                        .setHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(payload))
            }
        }
    }

    /** 慢速响应，便于验证暂停时效。 */
    private fun serveRangeSlowly(payload: ByteArray, etag: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val total = payload.size
                if (range == "bytes=0-0") {
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/$total")
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(payload, 0, 1))
                }
                val spec = range!!.removePrefix("bytes=")
                val start = spec.substringBefore('-').toLong()
                val end = spec.substringAfter('-').toLong().coerceAtMost(total - 1L)
                val length = (end - start + 1).toInt()
                val slice = payload.copyOfRange(start.toInt(), start.toInt() + length)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/$total")
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(slice))
                    .throttleBody(8 * 1024L, 40, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun serveSingleStreamOnly(payload: ByteArray, fileName: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                    .setHeader("Content-Length", payload.size)
                    .setBody(Buffer().write(payload))
            }
        }
    }
}
