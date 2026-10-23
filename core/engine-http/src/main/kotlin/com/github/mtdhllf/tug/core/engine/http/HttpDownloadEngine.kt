package com.github.mtdhllf.tug.core.engine.http

import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.api.LiveProgressSource
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TugLog
import com.github.mtdhllf.tug.core.model.TugTags
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DEFAULT_SEGMENT_SIZE: Long = 8L * 1024 * 1024
const val DEFAULT_CONNECTIONS = 8
const val MAX_RETRIES = 5
const val PROGRESS_TICK_MS = 500L
const val PROBE_RETRIES = 3
const val SINGLE_STREAM_RETRIES = 3
const val PAUSE_TIMEOUT_MS = 5_000L

private const val META_VERSION = 1
private const val READ_BUFFER_SIZE = 64 * 1024
private const val MAX_FILE_NAME_BYTES = 200

@Serializable
data class HttpMeta(
    val version: Int = META_VERSION,
    val taskId: Long = 0L,
    val url: String,
    val finalUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val total: Long? = null,
    val segmentSize: Long = DEFAULT_SEGMENT_SIZE,
    /** 已完成区间，元素为 `[start, endExclusive)`。 */
    val completed: List<List<Long>> = emptyList(),
    /** 在途分片已落盘区间，元素为 `[start, endExclusive)`；用于暂停/崩溃后从片内续传。 */
    val partial: List<List<Long>> = emptyList(),
    val fileName: String? = null,
    val rangeSupported: Boolean = true,
    val updatedAt: Long = 0L,
)

/** 不可重试错误：远端变化、服务器不支持分片、参数错误等。 */
private class NonRetryableException(message: String) : IOException(message)

/** 可重试错误：5xx/429/408/短读/连接中断等。 */
private class RetryableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * HTTP 多连接分段下载引擎（S0 重写版）。
 *
 * 与旧实现的关键差异：
 * - `start()` 完成探测/元数据准备后即返回，不阻塞到下载结束；
 * - 分片任务挂在本任务 `coroutineScope` 下，暂停/失败可级联取消，暂停返回后不再写盘；
 * - 数据写入 `<name>.part`，完成后 rename 为最终文件；
 * - 每个分片完成即落 meta，暂停/崩溃后可按分片续传；
 * - 分片响应校验 `Content-Range` 起点与实际字节数，杜绝"假完成"；
 * - 状态事件不再走会丢弃的缓冲。
 */
class HttpDownloadEngine(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    transport: HttpTransport? = null,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val segmentSize: Long = DEFAULT_SEGMENT_SIZE,
) : DownloadEngine, LiveProgressSource {

    override val kind: String = "http"

    private val transport: HttpTransport = transport ?: UrlConnectionTransport()
    private val ownsTransport: Boolean = transport == null

    /** 状态事件：低频且不允许丢弃。 */
    private val _events = MutableSharedFlow<EngineEvent>()

    private val _live = MutableStateFlow<Map<Long, TaskView>>(emptyMap())
    private val jobs = HashMap<Long, Job>()
    private val runtimes = ConcurrentHashMap<Long, TaskRuntime>()
    private val finishedViews = ConcurrentHashMap<Long, TaskView>()
    private val activeCalls = ConcurrentHashMap<Long, MutableSet<HttpCall>>()
    private val locks = Any()
    private val metaLock = Mutex()
    private var ticker: Job? = null

    private val tickerDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(48)

    init {
        ticker = scope.launch(tickerDispatcher) {
            TugLog.i(TugTags.HTTP, "progress ticker started interval=${PROGRESS_TICK_MS}ms")
            while (true) {
                try {
                    publishAllProgress()
                } catch (c: CancellationException) {
                    TugLog.i(TugTags.HTTP, "progress ticker cancelled")
                    throw c
                } catch (t: Throwable) {
                    TugLog.w(TugTags.HTTP, "progress ticker error: ${t.message}", t)
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    override fun events() = _events.asSharedFlow()

    override fun liveTasks(): StateFlow<Map<Long, TaskView>> = _live.asStateFlow()

    override fun snapshot(taskId: Long): TaskView? = _live.value[taskId]
        ?: runtimes[taskId]?.toView(withSpeed = false)

    fun setRateLimit(bps: Long?) {
        rateLimiter.limitBps = bps
    }

    override fun setDownloadRateLimit(bytesPerSecond: Long?) {
        rateLimiter.limitBps = bytesPerSecond
    }

    override suspend fun start(taskId: Long, input: NewTask, saveDir: String) {
        TugLog.i(
            TugTags.HTTP,
            "task=$taskId start url=${input.url} saveDir=$saveDir " +
                "connections=${input.connections ?: DEFAULT_CONNECTIONS}",
        )
        stopTask(taskId, emitPaused = false)

        val runtime = TaskRuntime(taskId).apply {
            url = input.url
            this.saveDir = saveDir
            connections = (input.connections ?: DEFAULT_CONNECTIONS).coerceIn(1, 32)
        }
        runtimes[taskId] = runtime
        finishedViews.remove(taskId)
        emitState(taskId, runtime, TaskState.FetchingMetadata)

        val prepared = try {
            withContext(downloadDispatcher) {
                prepare(runtime, input, File(saveDir).apply { mkdirs() })
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            TugLog.e(TugTags.HTTP, "task=$taskId prepare failed: ${t.message}", t)
            fail(runtime, t)
            return
        }

        emitState(taskId, runtime, TaskState.Downloading)
        val run = TaskRun(runtime, prepared)
        val job = scope.launch(downloadDispatcher) {
            try {
                run.execute()
                complete(runtime, prepared)
            } catch (c: CancellationException) {
                if (prepared.resumable) run.saveMetaQuietly()
                TugLog.i(TugTags.HTTP, "task=$taskId cancelled")
                throw c
            } catch (t: Throwable) {
                if (prepared.resumable) run.saveMetaQuietly()
                TugLog.e(TugTags.HTTP, "task=$taskId failed: ${t.message}", t)
                fail(runtime, t)
            }
        }
        synchronized(locks) { jobs[taskId] = job }
    }

    override suspend fun pause(taskId: Long) {
        stopTask(taskId, emitPaused = true)
    }

    override suspend fun resume(taskId: Long) = Unit

    override suspend fun remove(taskId: Long, deleteFiles: Boolean) {
        stopTask(taskId, emitPaused = false)
        val runtime = runtimes.remove(taskId)
        finishedViews.remove(taskId)
        if (deleteFiles && runtime != null) {
            val dir = File(runtime.saveDir)
            val name = runtime.fileName ?: runtime.name
            deleteSidecars(dir, name)
            runCatching { File(dir, name).delete() }
        }
        _live.value = _live.value - taskId
    }

    override fun shutdown() {
        ticker?.cancel()
        ticker = null
        synchronized(locks) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        activeCalls.keys.toList().forEach { cancelCalls(it) }
        runtimes.clear()
        finishedViews.clear()
        _live.value = emptyMap()
        if (ownsTransport) {
            runCatching { transport.close() }
        }
    }

    // ---------------------------------------------------------------- 准备阶段

    private suspend fun prepare(rt: TaskRuntime, input: NewTask, dir: File): Prepared {
        val probe = probeWithRetry(input.url)
        val rawName = input.fileName?.takeIf { it.isNotBlank() }
            ?: probe.fileName
            ?: guessNameFromUrl(input.url)
        val baseName = sanitizeFileName(rawName)

        var finalFile = File(dir, baseName)
        var partFile = File(dir, "$baseName.part")
        var metaFile = metaFile(dir, baseName)
        var fileName = baseName

        val existing = readMeta(metaFile)
        val partExists = partFile.exists()

        val resumable = existing != null &&
            partExists &&
            existing.url == input.url &&
            existing.fileName == baseName &&
            existing.total == probe.total &&
            existing.rangeSupported &&
            probe.rangeSupported &&
            matchesResource(existing, probe)

        if (existing != null && !resumable) {
            // 远端已变化或元数据不可信：丢弃旧进度，从头开始
            TugLog.w(TugTags.HTTP, "task=${rt.taskId} 丢弃不可续传的旧数据 name=$baseName")
            deleteSidecars(dir, baseName)
        } else if (existing == null && partExists) {
            // 没有元数据的 .part 无法信任
            runCatching { partFile.delete() }
        }

        if (!resumable && finalFile.exists()) {
            // 绝不覆盖已有文件
            finalFile = uniqueFile(dir, baseName)
            fileName = finalFile.name
            partFile = File(dir, "$fileName.part")
            metaFile = metaFile(dir, fileName)
            deleteSidecars(dir, fileName)
        }

        val completed = if (resumable) {
            normalizeCompleted(existing!!.completed, probe.total)
        } else {
            emptyList()
        }
        val partial = if (resumable) {
            normalizePartial(existing!!.partial, probe.total)
        } else {
            emptyMap()
        }
        val resumedFrom = completed.sumOf { it.last - it.first + 1 } +
            partial.entries.sumOf { (start, offset) -> (offset - start).coerceAtLeast(0L) }

        // 分片大小：续传沿用元数据；新任务按文件大小/连接数自适应（小文件也能跑满连接）
        val plannedSegmentSize = if (resumable) {
            existing!!.segmentSize.takeIf { it > 0 } ?: segmentSize
        } else {
            effectiveSegmentSize(probe.total ?: 0L, rt.connections, segmentSize)
        }

        val meta = existing?.takeIf { resumable }?.copy(
            finalUrl = probe.finalUrl,
            etag = probe.etag ?: existing.etag,
            lastModified = probe.lastModified ?: existing.lastModified,
            updatedAt = System.currentTimeMillis(),
        ) ?: HttpMeta(
            version = META_VERSION,
            taskId = rt.taskId,
            url = input.url,
            finalUrl = probe.finalUrl,
            etag = probe.etag,
            lastModified = probe.lastModified,
            total = probe.total,
            segmentSize = plannedSegmentSize,
            completed = emptyList(),
            fileName = fileName,
            rangeSupported = probe.rangeSupported,
            updatedAt = System.currentTimeMillis(),
        )

        val total = probe.total
        if (total != null && total > 0 && probe.rangeSupported) {
            // 预分配，配合随机写；长度不再作为完成判据（见 finalize 覆盖校验）
            runCatching { RandomAccessFile(partFile, "rw").use { it.setLength(total) } }
        }
        if (!resumable || !metaFile.exists()) {
            writeMeta(metaFile, meta)
        }

        rt.fileName = fileName
        rt.name = fileName
        rt.total = total
        rt.downloaded.set(resumedFrom)
        rt.partFile = partFile
        rt.resumable = probe.rangeSupported && total != null && total > 0

        TugLog.i(
            TugTags.HTTP,
            "task=${rt.taskId} plan file=$fileName size=${total ?: "?"} " +
                "range=${probe.rangeSupported} resumed=$resumedFrom connections=${rt.connections}",
        )

        return Prepared(
            url = probe.finalUrl ?: input.url,
            finalFile = finalFile,
            partFile = partFile,
            metaFile = metaFile,
            fileName = fileName,
            total = total,
            rangeSupported = probe.rangeSupported,
            etag = probe.etag,
            completed = completed,
            partial = partial,
            resumedFrom = resumedFrom,
            connections = rt.connections,
            segmentSize = plannedSegmentSize,
            meta = meta,
        )
    }

    private data class Prepared(
        val url: String,
        val finalFile: File,
        val partFile: File,
        val metaFile: File,
        val fileName: String,
        val total: Long?,
        val rangeSupported: Boolean,
        val etag: String?,
        val completed: List<LongRange>,
        /** 在途分片 → 已落盘偏移（绝对字节位置）。 */
        val partial: Map<Long, Long>,
        val resumedFrom: Long,
        val connections: Int,
        val segmentSize: Long,
        val meta: HttpMeta,
    ) {
        /** 未知长度 / 不支持 Range 时降级单流（不可续传）。 */
        val singleStream: Boolean
            get() = !rangeSupported || total == null || total <= 0L

        /** 是否可断点续传。 */
        val resumable: Boolean
            get() = !singleStream
    }

    // ---------------------------------------------------------------- 下载执行

    private data class WorkItem(val range: LongRange, val from: Long)

    private inner class TaskRun(
        private val rt: TaskRuntime,
        private val prep: Prepared,
    ) {
        private val completed: MutableList<LongRange> =
            Collections.synchronizedList(prep.completed.toMutableList())

        /** 在途分片起点 → 已落盘偏移；每个 chunk 更新，供 checkpoint/暂停读取。 */
        private val offsets = ConcurrentHashMap<Long, Long>().apply { putAll(prep.partial) }

        suspend fun execute() {
            if (prep.singleStream) {
                try {
                    singleStream()
                } catch (c: CancellationException) {
                    cleanupSingleStream()
                    throw c
                } catch (t: Throwable) {
                    cleanupSingleStream()
                    throw t
                }
                return
            }
            val total = prep.total!!
            val pending = computePending(total, prep.segmentSize, completed.toList())
            val items = pending.map { segment ->
                val from = (offsets[segment.first] ?: segment.first)
                    .coerceIn(segment.first, segment.last)
                WorkItem(segment, from)
            }
            if (items.isNotEmpty()) {
                coroutineScope {
                    // 每 1s 把在途偏移落盘（崩溃最多回退 1s 数据）
                    val checkpointer = launch(downloadDispatcher) {
                        while (true) {
                            delay(1_000)
                            runCatching { metaLock.withLock { saveMetaLocked() } }
                        }
                    }
                    try {
                        val semaphore = Semaphore(prep.connections)
                        items.map { item ->
                            async(downloadDispatcher) { downloadSegment(item, semaphore) }
                        }.awaitAll()
                    } finally {
                        checkpointer.cancel()
                    }
                }
            }
            val merged = mergeRanges(completed.toList())
            val covered = merged.sumOf { it.last - it.first + 1 }
            if (covered != total) {
                throw IOException("下载未完成：已覆盖 $covered / $total 字节")
            }
            finalizeFile()
        }

        private suspend fun downloadSegment(item: WorkItem, semaphore: Semaphore) =
            semaphore.withPermit {
                val range = item.range
                var attempt = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val from = offsets[range.first]
                        ?.coerceIn(range.first, range.last)
                        ?: item.from.coerceIn(range.first, range.last)
                    val written = LongAdder()
                    try {
                        TugLog.d(
                            TugTags.HTTP,
                            "task=${rt.taskId} seg=${range.first}-${range.last} " +
                                "from=$from attempt=${attempt + 1}",
                        )
                        streamRange(range, from, written)
                        markCompleted(range)
                        return@withPermit
                    } catch (c: CancellationException) {
                        // 取消时保留 offsets（已含最后写入位置），交给 saveMeta 落盘
                        throw c
                    } catch (e: NonRetryableException) {
                        throw e
                    } catch (e: IOException) {
                        // 先判取消：暂停/删除时底层连接会抛 IOException，
                        // 此时不能把已写入的字节扣回，否则用户会看到进度归零。
                        currentCoroutineContext().ensureActive()
                        rt.subtractDownloaded(written.sum())
                        offsets[range.first] = from
                        attempt++
                        if (attempt > MAX_RETRIES) {
                            TugLog.w(TugTags.HTTP, "task=${rt.taskId} seg=${range.first} 重试耗尽: ${e.message}")
                            throw e
                        }
                        delay(backoffMs(attempt))
                    }
                }
            }

        private suspend fun streamRange(range: LongRange, from: Long, written: LongAdder) {
            val headers = buildMap {
                put("Accept-Encoding", "identity")
                put("Range", "bytes=$from-${range.last}")
                prep.etag?.takeIf { !it.startsWith("W/") }?.let { put("If-Range", it) }
            }
            val call = transport.newCall(HttpRequest(prep.url, headers = headers))
            registerCall(rt.taskId, call)
            try {
                val resp = call.execute()
                resp.use {
                    when {
                        resp.code == 206 -> {
                            val start = parseContentRangeStart(resp.header("Content-Range"))
                            if (start == null) {
                                throw NonRetryableException("服务器返回异常 Content-Range，无法校验分段")
                            }
                            if (start != from) {
                                throw NonRetryableException(
                                    "远端文件已变化（Content-Range 起点 $start，期望 $from）",
                                )
                            }
                        }
                        resp.code == 200 -> {
                            throw if (prep.etag != null) {
                                NonRetryableException("远端文件已变化（If-Range 返回 200），需重新下载")
                            } else {
                                NonRetryableException("服务器不支持分片下载")
                            }
                        }
                        resp.code == 416 ->
                            throw NonRetryableException("远端文件已变化（HTTP 416），需重新下载")
                        resp.code == 408 || resp.code == 429 ->
                            throw RetryableException("服务器暂时不可用: HTTP ${resp.code}")
                        resp.code in 500..599 ->
                            throw RetryableException("服务器暂时不可用: HTTP ${resp.code}")
                        else ->
                            throw NonRetryableException("下载失败: HTTP ${resp.code}")
                    }

                    resp.body().use { input ->
                        RandomAccessFile(prep.partFile, "rw").use { raf ->
                            raf.seek(from)
                            val buffer = ByteArray(READ_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                raf.write(buffer, 0, n)
                                written.add(n.toLong())
                                rt.addDownloaded(n.toLong())
                                offsets[range.first] = from + written.sum()
                                rateLimiter.acquire(n)
                            }
                        }
                    }

                    val expected = range.last - from + 1
                    if (written.sum() != expected) {
                        throw RetryableException(
                            "分片数据不完整: 期望 $expected 字节，实际 ${written.sum()}",
                        )
                    }
                }
            } finally {
                unregisterCall(rt.taskId, call)
            }
        }

        private suspend fun markCompleted(range: LongRange) {
            metaLock.withLock {
                completed.add(range)
                offsets.remove(range.first)
                saveMetaLocked()
            }
        }

        suspend fun saveMetaQuietly() {
            runCatching {
                metaLock.withLock { saveMetaLocked() }
            }.onFailure { TugLog.w(TugTags.HTTP, "task=${rt.taskId} 保存 meta 失败: ${it.message}") }
        }

        private suspend fun saveMetaLocked() {
            val merged = mergeRanges(completed.toList())
            val total = prep.total
            val partialPairs = offsets.entries
                .filter { it.value > it.key }
                .map { (start, offset) ->
                    listOf(start, if (total != null) min(offset, total) else offset)
                }
            writeMeta(
                prep.metaFile,
                prep.meta.copy(
                    completed = merged.map { listOf(it.first, it.last + 1) },
                    partial = partialPairs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        /** 单流不可续传：暂停/失败时清掉半成品，避免下次误用。 */
        private fun cleanupSingleStream() {
            runCatching { prep.partFile.delete() }
            runCatching { prep.metaFile.delete() }
        }

        private suspend fun singleStream() {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                attempt++
                try {
                    doSingleStream()
                    finalizeFile()
                    return
                } catch (c: CancellationException) {
                    throw c
                } catch (e: IOException) {
                    runCatching { prep.partFile.delete() }
                    rt.downloaded.set(0)
                    if (attempt >= SINGLE_STREAM_RETRIES) throw e
                    delay(backoffMs(attempt))
                }
            }
        }

        private suspend fun doSingleStream() {
            val call = transport.newCall(
                HttpRequest(
                    url = prep.url,
                    headers = mapOf("Accept-Encoding" to "identity"),
                ),
            )
            registerCall(rt.taskId, call)
            try {
                val resp = call.execute()
                resp.use {
                    if (resp.code !in 200..299) {
                        throw NonRetryableException("下载失败: HTTP ${resp.code}")
                    }
                    val contentLength = resp.contentLength
                    rt.total = contentLength
                    prep.partFile.parentFile?.mkdirs()
                    resp.body().use { input ->
                        prep.partFile.outputStream().use { output ->
                            val buffer = ByteArray(READ_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                output.write(buffer, 0, n)
                                rt.addDownloaded(n.toLong())
                                rateLimiter.acquire(n)
                            }
                        }
                    }
                    val expected = contentLength
                    if (expected != null && prep.partFile.length() != expected) {
                        throw RetryableException(
                            "数据不完整: 期望 $expected 字节，实际 ${prep.partFile.length()}",
                        )
                    }
                }
            } finally {
                unregisterCall(rt.taskId, call)
            }
        }

        private suspend fun finalizeFile() {
            withContext(downloadDispatcher) {
                runCatching {
                    RandomAccessFile(prep.partFile, "rw").use { it.fd.sync() }
                }
                val target = prep.finalFile
                if (target.exists()) target.delete()
                val moved = prep.partFile.renameTo(target)
                if (!moved) {
                    prep.partFile.copyTo(target, overwrite = true)
                    prep.partFile.delete()
                }
                prep.metaFile.delete()
                runCatching { File(prep.metaFile.parentFile, prep.metaFile.name + ".tmp").delete() }
                rt.downloaded.set(target.length())
                rt.total = target.length()
                rt.partFile = null
            }
        }
    }

    // ---------------------------------------------------------------- 状态与进度

    private suspend fun complete(rt: TaskRuntime, prep: Prepared) {
        rt.state = TaskState.Completed
        rt.error = null
        val view = rt.toView(withSpeed = false)
        finishedViews[rt.taskId] = view
        runtimes.remove(rt.taskId)
        synchronized(locks) { jobs.remove(rt.taskId) }
        emitState(rt.taskId, rt, TaskState.Completed)
        TugLog.i(TugTags.HTTP, "task=${rt.taskId} completed file=${prep.finalFile.name}")
    }

    private suspend fun fail(rt: TaskRuntime, t: Throwable) {
        val message = t.message ?: t.toString()
        rt.state = TaskState.Failed
        rt.error = message
        val view = rt.toView(withSpeed = false)
        finishedViews[rt.taskId] = view
        runtimes.remove(rt.taskId)
        synchronized(locks) { jobs.remove(rt.taskId) }
        emitState(rt.taskId, rt, TaskState.Failed, error = message)
    }

    /** 取消并等待任务退出；`emitPaused` 为 true 时广播 Paused（暂停），否则静默（remove/shutdown）。 */
    private suspend fun stopTask(taskId: Long, emitPaused: Boolean) {
        val job = synchronized(locks) { jobs.remove(taskId) }
        // 先取消协程（阻止重试/新请求），再取消在途连接（中断阻塞读），最后等待退出
        job?.cancel()
        cancelCalls(taskId)
        if (job != null) {
            withTimeoutOrNull(PAUSE_TIMEOUT_MS) { job.join() }
        }
        cancelCalls(taskId)
        if (!emitPaused) {
            runtimes.remove(taskId)
            return
        }
        val rt = runtimes[taskId] ?: return
        rt.state = TaskState.Paused
        rt.error = if (rt.resumable) null else "该资源不支持断点续传，继续将重新下载"
        val view = rt.toView(withSpeed = false)
        finishedViews[taskId] = view
        runtimes.remove(taskId)
        emitState(taskId, rt, TaskState.Paused, error = rt.error)
    }

    private suspend fun emitState(
        taskId: Long,
        rt: TaskRuntime,
        state: TaskState,
        error: String? = null,
    ) {
        rt.state = state
        if (error != null) rt.error = error
        val view = rt.toView(withSpeed = false)
        _live.value = _live.value + (taskId to view)
        _events.emit(
            EngineEvent.State(
                taskId = taskId,
                state = state,
                name = view.name,
                fileName = view.fileName,
                error = error,
            ),
        )
    }

    private suspend fun publishAllProgress() {
        if (runtimes.isEmpty() && finishedViews.isEmpty()) return
        val snapshot = HashMap<Long, TaskView>(runtimes.size + finishedViews.size)
        for ((id, rt) in runtimes) {
            val active = rt.state == TaskState.Downloading ||
                rt.state == TaskState.Queued ||
                rt.state == TaskState.FetchingMetadata
            val view = rt.toView(withSpeed = active)
            snapshot[id] = view
            if (active) {
                _events.emit(
                    EngineEvent.Progress(
                        taskId = id,
                        downloaded = view.downloaded,
                        total = view.total,
                        downloadBps = view.downloadBps,
                    ),
                )
            }
        }
        snapshot.putAll(finishedViews)
        _live.value = snapshot
    }

    private fun TaskRuntime.toView(withSpeed: Boolean): TaskView {
        val down = downloaded.get()
        val bps = if (withSpeed) speed.bytesPerSecondAndFlush() else 0L
        val totalNow = total
        return TaskView(
            id = taskId,
            kind = TaskKind.Http,
            name = fileName?.takeIf { it.isNotBlank() } ?: name.ifBlank { "任务 $taskId" },
            url = url,
            saveDir = saveDir,
            fileName = fileName,
            state = state,
            total = totalNow,
            downloaded = down,
            downloadBps = bps,
            error = error,
            connections = connections,
            rateLimitBps = rateLimiter.limitBps,
            etaSecs = if (bps > 0 && totalNow != null && totalNow > down) {
                (totalNow - down) / bps
            } else {
                null
            },
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------- 网络调用登记

    private fun registerCall(taskId: Long, call: HttpCall) {
        activeCalls.getOrPut(taskId) { Collections.synchronizedSet(mutableSetOf()) }.add(call)
    }

    private fun unregisterCall(taskId: Long, call: HttpCall) {
        activeCalls[taskId]?.remove(call)
    }

    private fun cancelCalls(taskId: Long) {
        activeCalls.remove(taskId)?.forEach { runCatching { it.cancel() } }
    }

    // ---------------------------------------------------------------- 探测

    private data class ProbeResult(
        val total: Long?,
        val etag: String?,
        val lastModified: String?,
        val fileName: String?,
        val rangeSupported: Boolean,
        val finalUrl: String?,
    )

    private suspend fun probeWithRetry(url: String): ProbeResult {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return probe(url)
            } catch (c: CancellationException) {
                throw c
            } catch (e: NonRetryableException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= PROBE_RETRIES) throw e
                TugLog.w(TugTags.HTTP, "探测失败，准备重试($attempt/${PROBE_RETRIES}): ${e.message}")
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun probe(url: String): ProbeResult = withContext(downloadDispatcher) {
        val call = transport.newCall(
            HttpRequest(
                url = url,
                headers = mapOf(
                    "Accept-Encoding" to "identity",
                    "Range" to "bytes=0-0",
                ),
            ),
        )
        val resp = call.execute()
        resp.use {
            val finalUrl = resp.finalUrl
            val etag = resp.header("ETag")
            val lastModified = resp.header("Last-Modified")
            val fromDisposition = resp.header("Content-Disposition")
                ?.let { parseContentDisposition(it) }
            TugLog.d(
                TugTags.HTTP,
                "probe code=${resp.code} contentRange=${resp.header("Content-Range")}",
            )
            when (resp.code) {
                206 -> {
                    val total = parseContentRangeTotal(resp.header("Content-Range") ?: "")
                    ProbeResult(total, etag, lastModified, fromDisposition, total != null, finalUrl)
                }
                200 -> {
                    val total = resp.contentLength
                        ?: resp.header("Content-Length")?.toLongOrNull()
                    ProbeResult(total, etag, lastModified, fromDisposition, false, finalUrl)
                }
                416 -> {
                    val total = parseContentRangeTotal(resp.header("Content-Range") ?: "")
                    if (total == 0L) {
                        ProbeResult(0L, etag, lastModified, fromDisposition, true, finalUrl)
                    } else {
                        throw NonRetryableException("探测失败: HTTP 416")
                    }
                }
                408, 429 -> throw RetryableException("探测失败: HTTP ${resp.code}")
                in 500..599 -> throw RetryableException("探测失败: HTTP ${resp.code}")
                else -> throw NonRetryableException("探测失败: HTTP ${resp.code}")
            }
        }
    }

    // ---------------------------------------------------------------- 元数据

    private fun readMeta(file: File): HttpMeta? {
        if (!file.exists()) return null
        return runCatching {
            val meta = json.decodeFromString(HttpMeta.serializer(), file.readText())
            if (meta.version != META_VERSION) null else meta
        }.getOrNull()
    }

    /** 原子写：临时文件 → 落盘 → rename。失败抛 IOException（不再静默删除）。 */
    private fun writeMeta(file: File, meta: HttpMeta) {
        val parent = file.parentFile ?: throw IOException("元数据缺少父目录")
        parent.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(json.encodeToString(HttpMeta.serializer(), meta))
        runCatching { RandomAccessFile(tmp, "rw").use { it.fd.sync() } }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("元数据写入失败: ${file.absolutePath}")
            }
        }
    }

    private fun matchesResource(meta: HttpMeta, probe: ProbeResult): Boolean {
        val etagOk = meta.etag == null || probe.etag == null || meta.etag == probe.etag
        val lmOk = meta.lastModified == null ||
            probe.lastModified == null ||
            meta.lastModified == probe.lastModified
        return etagOk && lmOk
    }

    private fun normalizePartial(raw: List<List<Long>>, total: Long?): Map<Long, Long> {
        if (total == null) return emptyMap()
        val result = LinkedHashMap<Long, Long>()
        for (pair in raw) {
            if (pair.size < 2) continue
            val start = pair[0]
            val end = pair[1]
            if (start < 0 || end <= start || start >= total) continue
            result[start] = end.coerceAtMost(total)
        }
        return result
    }

    private fun normalizeCompleted(raw: List<List<Long>>, total: Long?): List<LongRange> {
        val ranges = raw.mapNotNull { pair ->
            if (pair.size < 2) return@mapNotNull null
            val start = pair[0]
            val end = pair[1]
            if (end <= start) null else start until end
        }.map { range ->
            val last = if (total != null) min(range.last, total - 1) else range.last
            range.first..last
        }.filter { it.last >= it.first }
        return mergeRanges(ranges)
    }

    // ---------------------------------------------------------------- 工具

    companion object {
        /** 默认传输：纯 JDK HttpURLConnection，无第三方依赖。 */
        fun defaultTransport(): HttpTransport = UrlConnectionTransport()

        fun metaFile(dir: File, fileName: String): File = File(dir, "$fileName.tug.json")

        fun partFile(dir: File, fileName: String): File = File(dir, "$fileName.part")

        fun setLength(file: File, length: Long) {
            RandomAccessFile(file, "rw").use { it.setLength(length) }
        }

        /**
         * 分片大小自适应：小文件按连接数切小（最小 64 KiB），大文件用期望值。
         *
         * 与 Rust `segments.rs:46-56` 对齐。
         */
        fun effectiveSegmentSize(total: Long, connections: Int, preferred: Long): Long {
            val minSize = 64L * 1024
            if (total <= 0L) return preferred.coerceAtLeast(minSize)
            val connectionCount = connections.coerceAtLeast(1).toLong()
            val byConnections = (total + connectionCount - 1) / connectionCount
            return preferred
                .coerceAtMost(byConnections.coerceAtLeast(minSize))
                .coerceAtLeast(minSize)
        }

        private fun deleteSidecars(dir: File, fileName: String) {
            runCatching { File(dir, "$fileName.part").delete() }
            runCatching { metaFile(dir, fileName).delete() }
            runCatching { File(dir, "$fileName.tug.json.tmp").delete() }
        }

        fun sanitizeFileName(raw: String): String {
            var name = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
            name = name.trim('.', ' ')
            if (name.isEmpty()) name = "download.bin"
            return truncateUtf8(name, MAX_FILE_NAME_BYTES)
        }

        private fun truncateUtf8(name: String, maxBytes: Int): String {
            val bytes = name.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return name
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
            val suffixBytes = ext.toByteArray(Charsets.UTF_8).size
            val builder = StringBuilder()
            var used = 0
            for (ch in name.substring(0, name.length - ext.length)) {
                val size = ch.toString().toByteArray(Charsets.UTF_8).size
                if (used + size > maxBytes - suffixBytes) break
                builder.append(ch)
                used += size
            }
            return builder.toString() + ext
        }

        fun guessNameFromUrl(url: String): String {
            return try {
                val path = java.net.URI(url).path ?: ""
                val last = path.substringAfterLast('/')
                if (last.isNotBlank()) {
                    sanitizeFileName(java.net.URLDecoder.decode(last, "UTF-8"))
                } else {
                    "download.bin"
                }
            } catch (_: Exception) {
                "download.bin"
            }
        }

        fun parseContentDisposition(value: String): String? {
            val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.get(1)
            if (star != null) {
                return runCatching {
                    sanitizeFileName(java.net.URLDecoder.decode(star.trim().trim('"'), "UTF-8"))
                }.getOrNull()
            }
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.get(1)
            return plain?.let { sanitizeFileName(it.trim()) }
        }

        fun parseContentRangeTotal(contentRange: String): Long? {
            val total = contentRange.substringAfterLast('/').trim()
            return if (total == "*" || total.isEmpty()) null else total.toLongOrNull()
        }

        fun parseContentRangeStart(contentRange: String?): Long? {
            if (contentRange == null) return null
            val rest = contentRange.trim().removePrefix("bytes").trim()
            val range = rest.substringBefore('/').trim()
            return range.substringBefore('-').trim().toLongOrNull()
        }

        fun computePending(
            total: Long,
            segmentSize: Long,
            completed: List<LongRange>,
        ): List<LongRange> {
            val merged = mergeRanges(completed)
            val pending = mutableListOf<LongRange>()
            var pos = 0L
            for (range in merged) {
                if (pos < range.first) {
                    pending += splitRange(pos, range.first - 1, segmentSize)
                }
                pos = range.last + 1
            }
            if (pos < total) {
                pending += splitRange(pos, total - 1, segmentSize)
            }
            return pending
        }

        fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val valid = ranges.filter { it.last >= it.first }
            if (valid.isEmpty()) return emptyList()
            val sorted = valid.sortedBy { it.first }
            val result = mutableListOf<LongRange>()
            var start = sorted[0].first
            var end = sorted[0].last
            for (r in sorted.drop(1)) {
                if (r.first <= end + 1) {
                    if (r.last > end) end = r.last
                } else {
                    result += start..end
                    start = r.first
                    end = r.last
                }
            }
            result += start..end
            return result
        }

        private fun splitRange(from: Long, toInclusive: Long, segmentSize: Long): List<LongRange> {
            val result = mutableListOf<LongRange>()
            var start = from
            while (start <= toInclusive) {
                val end = min(start + segmentSize - 1, toInclusive)
                result += start..end
                start = end + 1
            }
            return result
        }

        private fun backoffMs(attempt: Int): Long {
            val base = 500L shl (attempt - 1).coerceIn(0, 3)
            val jitter = (System.nanoTime() % 251).coerceAtLeast(0)
            return (base + jitter).coerceAtMost(8_000L)
        }

        private fun uniqueFile(dir: File, name: String): File {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (i in 1..999) {
                val candidate = File(dir, "$base ($i)$ext")
                if (!candidate.exists() && !File(dir, "$base ($i)$ext.part").exists()) {
                    return candidate
                }
            }
            return File(dir, "$base-${System.currentTimeMillis()}$ext")
        }
    }
}
