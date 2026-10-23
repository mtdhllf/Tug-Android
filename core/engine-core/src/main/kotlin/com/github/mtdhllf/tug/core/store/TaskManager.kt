package com.github.mtdhllf.tug.core.store

import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.api.LiveProgressSource
import com.github.mtdhllf.tug.core.model.GlobalStats
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.Settings
import com.github.mtdhllf.tug.core.model.TaskEvent
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TugLog
import com.github.mtdhllf.tug.core.model.TugTags
import com.github.mtdhllf.tug.core.model.kindFor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 任务编排：队列、并发、持久化与引擎调度。
 *
 * 不依赖具体协议；HTTP / BT 通过 [DownloadEngine] 注入。
 *
 * 关键约定：
 * - 只有 `Queued` 会被自动启动，`Failed` 必须由用户显式重试；
 * - 进度只有一个来源（引擎的实时快照），事件只用于状态迁移；
 * - 引擎 `start()` 返回只代表"已开始下载"，槽位在终态事件里释放。
 */
class TaskManager(
    private val store: TaskStore,
    private val engines: Map<String, DownloadEngine>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    defaultDownloadDir: String,
    initialSettings: Settings = Settings(),
    private val deleter: TaskFileDeleter? = null,
) {
    @Volatile
    var defaultDownloadDir: String = defaultDownloadDir
        private set

    @Volatile
    private var settings: Settings = initialSettings

    private val mutex = Mutex()
    private val running = mutableSetOf<Long>()

    private val _tasks = MutableStateFlow<List<TaskView>>(emptyList())
    val tasks: StateFlow<List<TaskView>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TaskEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(GlobalStats())
    val stats: StateFlow<GlobalStats> = _stats.asStateFlow()

    private val liveProgress = ConcurrentHashMap<Long, LiveProgress>()

    private data class LiveProgress(
        var downloaded: Long = 0L,
        var total: Long? = null,
    )

    init {
        // 进度：只订阅引擎的实时快照（500ms 一跳，已合并）
        engines.values.filterIsInstance<LiveProgressSource>().forEach { source ->
            scope.launch {
                source.liveTasks().collect { mergeLive(it) }
            }
        }
        // 状态：只订阅引擎事件
        engines.values.forEach { engine ->
            scope.launch {
                engine.events().collect { event ->
                    runCatching { onEngineEvent(event) }
                        .onFailure { TugLog.w(TugTags.TASK, "处理引擎事件失败: ${it.message}", it) }
                }
            }
        }
        // 进度落库（1s，节流写，不刷 UI）
        scope.launch {
            while (true) {
                delay(1_000)
                runCatching { flushProgress() }
                    .onFailure { TugLog.w(TugTags.TASK, "进度落库失败: ${it.message}") }
            }
        }
    }

    private suspend fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Progress -> Unit // 进度走快照流，避免双通道竞争
            is EngineEvent.State -> {
                TugLog.d(TugTags.TASK, "engine state task=${event.taskId} state=${event.state}")
                store.updateState(
                    event.taskId,
                    event.state,
                    error = event.error,
                    name = event.name,
                    fileName = event.fileName,
                )
                val terminal = event.state == TaskState.Completed ||
                    event.state == TaskState.Failed ||
                    event.state == TaskState.Paused
                if (terminal) {
                    liveProgress.remove(event.taskId)
                }
                // 终态把最终字节数写回库；保留已有进度，避免"恢复时探测失败"把进度清零
                val current = store.get(event.taskId)
                if (terminal && current != null) {
                    val snap = engines[current.kind.name.lowercase()]?.snapshot(event.taskId)
                    if (snap != null) {
                        store.updateProgress(
                            event.taskId,
                            maxOf(current.downloaded, snap.downloaded),
                            snap.total ?: current.total,
                        )
                    }
                }
                if (terminal) {
                    releaseSlot(event.taskId)
                }
                refresh()
                val view = store.get(event.taskId)
                if (view != null) {
                    _events.tryEmit(TaskEvent.Updated(view))
                    when (event.state) {
                        TaskState.Completed ->
                            _events.tryEmit(TaskEvent.Completed(view.id, view.name, view.path))
                        TaskState.Failed ->
                            _events.tryEmit(
                                TaskEvent.Failed(view.id, view.error ?: "下载失败"),
                            )
                        else -> Unit
                    }
                }
                if (terminal) {
                    pump()
                }
            }
        }
    }

    /** 引擎快照 → UI 列表（无 DB 写入）。 */
    private fun mergeLive(live: Map<Long, TaskView>) {
        if (live.isEmpty()) return
        val current = _tasks.value
        if (current.isEmpty()) {
            _tasks.value = live.values.toList()
            return
        }
        var changed = false
        val next = current.map { task ->
            val l = live[task.id]
            if (l == null) return@map task
            if (task.state == TaskState.Completed ||
                task.state == TaskState.Failed ||
                task.state == TaskState.Paused
            ) {
                return@map task
            }
            val updated = task.copy(
                name = l.fileName?.takeIf { it.isNotBlank() } ?: task.name,
                fileName = l.fileName ?: task.fileName,
                downloaded = l.downloaded,
                total = l.total ?: task.total,
                downloadBps = l.downloadBps,
                etaSecs = l.etaSecs,
                state = l.state,
                error = l.error,
            )
            if (updated != task) changed = true
            if (l.state == TaskState.Downloading) {
                liveProgress[task.id] = LiveProgress(l.downloaded, l.total)
            }
            updated
        }
        if (changed) {
            _tasks.value = next
            _stats.value = statsOf(next)
        }
    }

    private suspend fun flushProgress() {
        for ((id, p) in liveProgress) {
            store.updateProgress(id, p.downloaded, p.total)
        }
    }

    suspend fun bootstrap() {
        store.markInterrupted()
        refresh()
        pump()
    }

    suspend fun list(): List<TaskView> {
        refresh()
        return _tasks.value
    }

    suspend fun add(input: NewTask): Long {
        val saveDir = input.saveDir?.takeIf { it.isNotBlank() } ?: defaultDownloadDir
        val kind = when {
            input.torrentBytes != null -> TaskKind.Torrent
            input.kind == TaskKind.Torrent -> TaskKind.Torrent
            else -> kindFor(input.url)
        }
        val effective = input.copy(
            kind = kind,
            connections = input.connections ?: settings.connectionsPerTask,
        )
        val defaultName = effective.fileName?.takeIf { it.isNotBlank() }
            ?: if (kind == TaskKind.Torrent) {
                torrentDisplayName(effective.url)
            } else {
                effective.url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
            }
        TugLog.i(
            TugTags.TASK,
            "add url=${effective.url} kind=$kind saveDir=$saveDir name=$defaultName " +
                "connections=${effective.connections}",
        )
        val id = store.add(effective, saveDir, defaultName)
        refresh()
        pump()
        return id
    }

    /** 磁力链接的显示名：优先 `dn=`，否则用 info hash 前缀。 */
    private fun torrentDisplayName(url: String): String {
        val dn = Regex("[?&]dn=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        if (!dn.isNullOrBlank()) {
            return runCatching { java.net.URLDecoder.decode(dn, "UTF-8") }.getOrDefault(dn)
        }
        val hash = Regex("btih:([0-9a-zA-Z]+)").find(url)?.groupValues?.getOrNull(1)
        return if (hash.isNullOrBlank()) "magnet-task" else "magnet-${hash.take(8)}"
    }

    suspend fun pause(id: Long) {
        val current = store.get(id)
        if (current != null && current.state == TaskState.Completed) {
            TugLog.w(TugTags.TASK, "pause 被忽略：任务 $id 已完成")
            return
        }
        TugLog.i(TugTags.TASK, "pause id=$id")
        engines.values.forEach { runCatching { it.pause(id) } }
        // 暂停返回即把最终进度落库，避免 UI/续传读到上一跳的旧值
        val snap = engines.values.firstNotNullOfOrNull { it.snapshot(id) }
        if (snap != null) {
            store.updateProgress(id, snap.downloaded, snap.total)
        }
        store.updateState(id, TaskState.Paused, error = snap?.error)
        liveProgress.remove(id)
        releaseSlot(id)
        refresh()
        store.get(id)?.let { _events.tryEmit(TaskEvent.Updated(it)) }
        pump()
    }

    suspend fun resume(id: Long) {
        val view = store.get(id) ?: return
        if (view.state == TaskState.Completed) {
            TugLog.w(TugTags.TASK, "resume 被忽略：任务 $id 已完成")
            return
        }
        TugLog.i(TugTags.TASK, "resume id=$id")
        store.updateState(id, TaskState.Queued, error = null)
        refresh()
        store.get(id)?.let { _events.tryEmit(TaskEvent.Updated(it)) }
        pump()
    }

    suspend fun remove(id: Long, deleteFiles: Boolean) {
        TugLog.i(TugTags.TASK, "remove id=$id deleteFiles=$deleteFiles")
        val view = store.get(id)
        engines.values.forEach { runCatching { it.remove(id, false) } }
        store.delete(id)
        liveProgress.remove(id)
        releaseSlot(id)
        _events.tryEmit(TaskEvent.Removed(id))
        val fileDeleter = deleter
        if (deleteFiles && view != null && fileDeleter != null) {
            val target = view
            runCatching { fileDeleter.delete(target) }
                .onFailure { TugLog.w(TugTags.TASK, "删除文件失败: ${it.message}", it) }
        }
        refresh()
        pump()
    }

    /** 完成文件已发布到公共目录：回写位置，供 UI 打开/分享。 */
    suspend fun markPublished(id: Long, saveDir: String, fileName: String, displayName: String) {
        store.updateLocation(id, saveDir, fileName, displayName)
        refresh()
        store.get(id)?.let { _events.tryEmit(TaskEvent.Updated(it)) }
    }

    /** 发布失败：任务仍算完成，但行内展示可读错误。 */
    suspend fun markPublishFailed(id: Long, message: String) {
        store.updateState(id, TaskState.Completed, error = message)
        refresh()
        store.get(id)?.let { _events.tryEmit(TaskEvent.Updated(it)) }
    }

    /** 应用设置（并发/连接数/全局限速），运行中任务即时生效。 */
    fun applySettings(newSettings: Settings) {
        settings = newSettings
        val globalLimit = newSettings.globalDownloadLimitKib
            ?.takeIf { it > 0 }?.times(1024)
        engines.values.forEach { runCatching { it.setDownloadRateLimit(globalLimit) } }
        TugLog.i(
            TugTags.TASK,
            "settings applied concurrent=${newSettings.maxConcurrentTasks} " +
                "connections=${newSettings.connectionsPerTask} globalLimit=$globalLimit",
        )
        scope.launch { pump() }
    }

    suspend fun pauseAll() {
        val active = store.list().filter { it.state.isActive }
        active.forEach { pause(it.id) }
    }

    suspend fun resumeAll() {
        val paused = store.list().filter { it.state == TaskState.Paused }
        paused.forEach { resume(it.id) }
    }

    fun globalStats(): GlobalStats = statsOf(_tasks.value)

    private fun statsOf(list: List<TaskView>): GlobalStats = GlobalStats(
        downloadBps = list.filter { it.state == TaskState.Downloading }.sumOf { it.downloadBps },
        uploadBps = list.sumOf { it.uploadBps },
        activeTasks = list.count { it.state.isActive },
        totalTasks = list.size,
        completedTasks = list.count { it.state == TaskState.Completed },
    )

    /** 只有占用了槽位的任务才 release，避免重复 release。 */
    private suspend fun releaseSlot(taskId: Long) {
        val released = mutex.withLock { running.remove(taskId) }
        if (released) {
            TugLog.d(TugTags.TASK, "task=$taskId slot released running=${running.size}")
        }
    }

    private suspend fun refresh() {
        val list = store.list()
        _tasks.value = list
        _stats.value = statsOf(list)
    }

    private suspend fun pump() {
        val queued = store.list().filter { it.state == TaskState.Queued }
        if (queued.isEmpty()) {
            refresh()
            return
        }
        for (task in queued) {
            val claimed = mutex.withLock {
                if (task.id in running) {
                    false
                } else if (running.size >= settings.maxConcurrentTasks.coerceAtLeast(1)) {
                    false
                } else {
                    running.add(task.id)
                    true
                }
            }
            if (!claimed) {
                TugLog.d(TugTags.TASK, "pump 无空闲槽位或已在运行，等待 task=${task.id}")
                return
            }
            val engine = engines[task.kind.name.lowercase()]
            if (engine == null) {
                mutex.withLock { running.remove(task.id) }
                store.updateState(task.id, TaskState.Failed, error = "不支持的任务类型: ${task.kind}")
                continue
            }
            val input = store.payload(task.id) ?: NewTask(
                url = task.url,
                saveDir = task.saveDir,
                kind = task.kind,
            )
            val saveDir = task.saveDir.ifBlank { defaultDownloadDir }
            if (saveDir.startsWith("content://")) {
                // 已完成并发布过的任务不能再写回 MediaStore 位置，避免把 URI 当目录
                mutex.withLock { running.remove(task.id) }
                store.updateState(
                    task.id,
                    TaskState.Failed,
                    error = "任务已发布到公共目录，无法从原位置重新下载",
                )
                continue
            }
            TugLog.i(
                TugTags.TASK,
                "pump start id=${task.id} kind=${task.kind} url=${input.url} saveDir=$saveDir",
            )
            scope.launch {
                try {
                    store.updateState(task.id, TaskState.Downloading)
                    engine.start(
                        task.id,
                        input.copy(connections = input.connections ?: settings.connectionsPerTask),
                        saveDir,
                    )
                    // 返回仅代表已开始下载；槽位在 Completed/Failed/Paused 事件里释放
                } catch (t: Throwable) {
                    TugLog.e(TugTags.TASK, "task=${task.id} 启动失败", t)
                    store.updateState(task.id, TaskState.Failed, error = t.message ?: t.toString())
                    releaseSlot(task.id)
                    refresh()
                    pump()
                }
            }
        }
        refresh()
    }
}
