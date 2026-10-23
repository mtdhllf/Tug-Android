package com.github.mtdhllf.tug.core.store

import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存实现：用于测试、示例工程，或不需要持久化的宿主。
 *
 * 进程内共享；不落盘。
 */
class InMemoryTaskStore : TaskStore {
    private val mutex = Mutex()
    private val nextId = AtomicLong(1)
    private val tasks = LinkedHashMap<Long, TaskView>()
    private val payloads = HashMap<Long, NewTask>()

    override suspend fun list(): List<TaskView> = mutex.withLock {
        tasks.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun get(id: Long): TaskView? = mutex.withLock { tasks[id] }

    override suspend fun add(input: NewTask, saveDir: String, defaultName: String): Long {
        val id = nextId.getAndIncrement()
        val name = input.fileName?.takeIf { it.isNotBlank() } ?: defaultName
        val now = System.currentTimeMillis()
        mutex.withLock {
            tasks[id] = TaskView(
                id = id,
                kind = input.kind,
                name = name,
                url = input.url,
                saveDir = saveDir,
                fileName = input.fileName,
                state = TaskState.Queued,
                connections = input.connections ?: 8,
                createdAt = now,
                updatedAt = now,
            )
            payloads[id] = input
        }
        return id
    }

    override suspend fun payload(id: Long): NewTask? = mutex.withLock { payloads[id] }

    override suspend fun updateState(
        id: Long,
        state: TaskState,
        error: String?,
        name: String?,
        fileName: String?,
    ) {
        mutex.withLock {
            val old = tasks[id] ?: return@withLock
            tasks[id] = old.copy(
                state = state,
                error = error,
                name = name?.takeIf { it.isNotBlank() } ?: old.name,
                fileName = fileName ?: old.fileName,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun updateProgress(id: Long, downloaded: Long, total: Long?) {
        mutex.withLock {
            val old = tasks[id] ?: return@withLock
            tasks[id] = old.copy(
                downloaded = downloaded,
                total = total ?: old.total,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun updateLocation(
        id: Long,
        saveDir: String,
        fileName: String?,
        displayName: String?,
    ) {
        mutex.withLock {
            val old = tasks[id] ?: return@withLock
            tasks[id] = old.copy(
                saveDir = saveDir,
                fileName = fileName ?: old.fileName,
                name = displayName?.takeIf { it.isNotBlank() } ?: old.name,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun delete(id: Long) {
        mutex.withLock {
            tasks.remove(id)
            payloads.remove(id)
        }
    }

    override suspend fun markInterrupted(): Int = mutex.withLock {
        var changed = 0
        for ((id, task) in tasks) {
            if (task.state.isActive) {
                tasks[id] = task.copy(
                    state = TaskState.Paused,
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                )
                changed++
            }
        }
        changed
    }
}
