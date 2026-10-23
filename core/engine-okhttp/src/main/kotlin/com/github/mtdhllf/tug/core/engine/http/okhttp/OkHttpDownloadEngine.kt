package com.github.mtdhllf.tug.core.engine.http.okhttp

import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.api.LiveProgressSource
import com.github.mtdhllf.tug.core.engine.http.DEFAULT_SEGMENT_SIZE
import com.github.mtdhllf.tug.core.engine.http.HttpDownloadEngine
import com.github.mtdhllf.tug.core.engine.http.RateLimiter
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * OkHttp 版 HTTP 下载引擎。
 *
 * 内部复用 `tug-http` 的分片/续传/重试逻辑，只把传输层替换为 [OkHttpTransport]；
 * 与 [HttpDownloadEngine] 完全同构，可二选一使用。
 */
public class OkHttpDownloadEngine(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    client: OkHttpClient? = null,
    rateLimiter: RateLimiter = RateLimiter(),
    segmentSize: Long = DEFAULT_SEGMENT_SIZE,
) : DownloadEngine, LiveProgressSource {

    private val transport = OkHttpTransport(client)
    private val delegate = HttpDownloadEngine(
        scope = scope,
        transport = transport,
        rateLimiter = rateLimiter,
        segmentSize = segmentSize,
    )

    override val kind: String
        get() = delegate.kind

    override fun events(): Flow<EngineEvent> = delegate.events()

    override suspend fun start(taskId: Long, input: NewTask, saveDir: String) =
        delegate.start(taskId, input, saveDir)

    override suspend fun pause(taskId: Long) = delegate.pause(taskId)

    override suspend fun resume(taskId: Long) = delegate.resume(taskId)

    override suspend fun remove(taskId: Long, deleteFiles: Boolean) =
        delegate.remove(taskId, deleteFiles)

    override fun snapshot(taskId: Long): TaskView? = delegate.snapshot(taskId)

    override fun setDownloadRateLimit(bytesPerSecond: Long?) =
        delegate.setDownloadRateLimit(bytesPerSecond)

    override fun liveTasks(): StateFlow<Map<Long, TaskView>> = delegate.liveTasks()

    override fun shutdown() {
        delegate.shutdown()
        runCatching { transport.close() }
    }
}
