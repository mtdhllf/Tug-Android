package com.github.mtdhllf.tug.core.store

import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 可编程的假引擎：用于验证 TaskManager 的队列/并发/状态语义。
 */
class FakeEngine : DownloadEngine {
    override val kind: String = "http"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    val started = mutableListOf<Long>()
    val connectionsUsed = mutableListOf<Int>()
    var autoComplete: Boolean = true
    var completeDelayMs: Long = 100L

    var active: Int = 0
        private set
    var maxActive: Int = 0
        private set

    override suspend fun start(taskId: Long, input: NewTask, saveDir: String) {
        started += taskId
        connectionsUsed += input.connections ?: 8
        active++
        maxActive = maxOf(maxActive, active)
        _events.emit(EngineEvent.State(taskId, TaskState.Downloading))
        if (autoComplete) {
            delay(completeDelayMs)
            active--
            _events.emit(EngineEvent.State(taskId, TaskState.Completed))
        } else {
            active--
        }
    }

    suspend fun complete(taskId: Long) {
        _events.emit(EngineEvent.State(taskId, TaskState.Completed))
    }

    suspend fun fail(taskId: Long, message: String) {
        _events.emit(EngineEvent.State(taskId, TaskState.Failed, error = message))
    }

    override suspend fun pause(taskId: Long) {
        _events.emit(EngineEvent.State(taskId, TaskState.Paused))
    }

    override suspend fun resume(taskId: Long) = Unit

    override suspend fun remove(taskId: Long, deleteFiles: Boolean) {
        _events.emit(EngineEvent.State(taskId, TaskState.Paused))
    }

    override fun snapshot(taskId: Long): TaskView? = null

    override fun shutdown() = Unit
}
