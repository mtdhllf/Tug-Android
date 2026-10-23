package com.github.mtdhllf.tug.core.store

import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.Settings
import com.github.mtdhllf.tug.core.model.TaskState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerTest {

    private fun manager(
        store: TaskStore,
        engine: FakeEngine,
        scope: kotlinx.coroutines.CoroutineScope,
        settings: Settings = Settings(maxConcurrentTasks = 2),
    ): TaskManager = TaskManager(
        store = store,
        engines = mapOf("http" to engine),
        scope = scope,
        defaultDownloadDir = "test-dir",
        initialSettings = settings,
    )

    @Test
    fun queue_respectsMaxConcurrent() = runTest {
        val store = InMemoryTaskStore()
        val engine = FakeEngine().apply {
            autoComplete = true
            completeDelayMs = 100
        }
        val taskManager = manager(store, engine, backgroundScope, Settings(maxConcurrentTasks = 1))

        taskManager.add(NewTask(url = "http://example.com/1.bin"))
        taskManager.add(NewTask(url = "http://example.com/2.bin"))
        runCurrent()

        assertEquals("并发上限为 1，应只启动 1 个", 1, engine.started.size)
        assertEquals(1, engine.maxActive)

        advanceTimeBy(250)
        runCurrent()

        assertEquals("第一个完成后应自动启动第二个", 2, engine.started.size)
        assertEquals("并发峰值不能超过设置", 1, engine.maxActive)
        assertEquals(2, taskManager.list().count { it.state == TaskState.Completed })
    }

    @Test
    fun failedTask_isNotAutoRestartedByPump() = runTest {
        val store = InMemoryTaskStore()
        val engine = FakeEngine().apply { autoComplete = false }
        val taskManager = manager(store, engine, backgroundScope)

        val id = taskManager.add(NewTask(url = "http://example.com/fail.bin"))
        runCurrent()
        assertEquals(1, engine.started.size)

        engine.fail(id, "boom")
        runCurrent()
        assertEquals(TaskState.Failed, taskManager.list().first { it.id == id }.state)

        // 触发一次调度（改设置会 pump），失败任务不应被自动重启
        taskManager.applySettings(Settings(maxConcurrentTasks = 3))
        runCurrent()
        assertEquals("Failed 任务不能被自动重试", 1, engine.started.size)
    }

    @Test
    fun pauseThenResume_restartsTask() = runTest {
        val store = InMemoryTaskStore()
        val engine = FakeEngine().apply { autoComplete = false }
        val taskManager = manager(store, engine, backgroundScope)

        val id = taskManager.add(NewTask(url = "http://example.com/pause.bin"))
        runCurrent()
        assertEquals(TaskState.Downloading, taskManager.list().first { it.id == id }.state)

        taskManager.pause(id)
        runCurrent()
        assertEquals(TaskState.Paused, taskManager.list().first { it.id == id }.state)

        taskManager.resume(id)
        runCurrent()
        assertEquals(TaskState.Downloading, taskManager.list().first { it.id == id }.state)
        assertEquals("恢复应重新启动引擎", 2, engine.started.size)
    }

    @Test
    fun bootstrap_marksInterruptedAsPaused() = runTest {
        val store = InMemoryTaskStore()
        val engine = FakeEngine().apply { autoComplete = false }

        // 模拟上次异常退出时留下的"下载中"任务
        val id = store.add(
            NewTask(url = "http://example.com/recover.bin", connections = 4),
            "test-dir",
            "recover.bin",
        )
        store.updateState(id, TaskState.Downloading)

        val taskManager = manager(store, engine, backgroundScope)
        taskManager.bootstrap()
        runCurrent()

        assertEquals(TaskState.Paused, taskManager.list().first { it.id == id }.state)
        assertTrue("启动恢复后不应自动开始下载", engine.started.isEmpty())
    }

    @Test
    fun add_usesConnectionsFromSettings() = runTest {
        val store = InMemoryTaskStore()
        val engine = FakeEngine().apply { autoComplete = false }
        val taskManager = manager(store, engine, backgroundScope, Settings(connectionsPerTask = 5))

        taskManager.add(NewTask(url = "http://example.com/conn.bin"))
        runCurrent()

        assertEquals(listOf(5), engine.connectionsUsed)
    }
}
