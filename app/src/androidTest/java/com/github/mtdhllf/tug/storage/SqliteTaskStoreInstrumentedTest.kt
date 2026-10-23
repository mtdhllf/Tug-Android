package com.github.mtdhllf.tug.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.store.sqlite.SqliteTaskStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 原生 SQLite 任务库的真机验证：写入 → 重新打开 → 数据仍在。
 */
@RunWith(AndroidJUnit4::class)
class SqliteTaskStoreInstrumentedTest {

    private val dbName = "tug-test-${System.currentTimeMillis()}.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        runCatching { context.deleteDatabase(dbName) }
    }

    @Test
    fun addUpdateAndReopen_persistsTask() = runBlocking {
        val first = SqliteTaskStore(context, dbName)
        val id = first.add(
            NewTask(url = "http://example.com/persist.bin", connections = 6),
            "/tmp/staging",
            "persist.bin",
        )
        first.updateState(id, TaskState.Downloading)
        first.updateProgress(id, 1024L, 4096L)
        first.updateLocation(id, "content://media/external/downloads/1", "persist.bin", "persist.bin")
        first.close()

        val second = SqliteTaskStore(context, dbName)
        try {
            val task = second.get(id)
            assertNotNull("重新打开数据库后任务应仍存在", task)
            task!!
            assertEquals(TaskState.Downloading, task.state)
            assertEquals(1024L, task.downloaded)
            assertEquals(4096L, task.total)
            assertEquals("content://media/external/downloads/1", task.saveDir)
            assertEquals(6, task.connections)
            assertEquals(1, second.list().size)
        } finally {
            second.close()
        }
    }

    @Test
    fun markInterrupted_pausesActiveTasks() = runBlocking {
        val store = SqliteTaskStore(context, dbName)
        try {
            val active = store.add(NewTask(url = "http://example.com/a.bin"), "/tmp", "a.bin")
            val done = store.add(NewTask(url = "http://example.com/b.bin"), "/tmp", "b.bin")
            store.updateState(active, TaskState.Downloading)
            store.updateState(done, TaskState.Completed)

            val changed = store.markInterrupted()

            assertEquals(1, changed)
            assertEquals(TaskState.Paused, store.get(active)!!.state)
            assertEquals(TaskState.Completed, store.get(done)!!.state)
        } finally {
            store.close()
        }
    }
}

