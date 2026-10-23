package com.github.mtdhllf.tug.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9 BT 引擎确定性测试：内嵌迷你种子，不依赖公网 peer。
 *
 * 验证：TaskManager 识别 Torrent → TorrentDownloadEngine 加载 libtorrent4j、
 * 解析种子、启动 session、状态迁移、暂停落库。
 *
 * 真实网络下载（magnet / .torrent URL）见手动冒烟与 S8 Spike 报告。
 */
@RunWith(AndroidJUnit4::class)
class BtTaskInstrumentedTest {

    /** 手写 bencode：单文件 1024B、piece length 16384、pieces 为零哈希。 */
    private fun tinyTorrentBytes(): ByteArray {
        val pieces = ByteArray(20)
        val info = buildList<ByteArray> {
            add("d".toByteArray())
            add("6:lengthi1024e".toByteArray())
            add("4:name8:test.bin".toByteArray())
            add("12:piece lengthi16384e".toByteArray())
            add("6:pieces20:".toByteArray())
            add(pieces)
            add("e".toByteArray())
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return "d4:info".toByteArray() + info + "e".toByteArray()
    }

    @Test(timeout = 120_000)
    fun torrent_bytes_task_starts_and_pauses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as TugApp
        val input = NewTask(
            url = "torrent://test.bin",
            kind = TaskKind.Torrent,
            torrentBytes = tinyTorrentBytes(),
        )
        // 入队前解析：应拿到 1 个文件与已知大小
        val metadata = app.torrentEngine.parse(input)
        println("BT_PARSE_RESULT=$metadata")
        assertEquals("test.bin", metadata.name)
        assertEquals(1024L, metadata.totalSize)
        assertEquals(1, metadata.files.size)

        val id = app.taskManager.add(input.copy(torrentBytes = metadata.torrentBytes))
        try {
            withTimeout(45_000) {
                while (true) {
                    val current = app.taskManager.tasks.value.firstOrNull { it.id == id }
                    if (current?.state == TaskState.Failed) {
                        error("任务失败: ${current.error}")
                    }
                    if (current?.state == TaskState.Downloading && current.total != null) break
                    delay(500)
                }
            }
            val running = app.taskManager.tasks.value.first { it.id == id }
            println("BT_BYTES_RESULT=$running")
            assertEquals(TaskKind.Torrent, running.kind)
            assertEquals(1024L, running.total)

            app.taskManager.pause(id)
            delay(2_000)
            val paused = app.taskManager.tasks.value.first { it.id == id }
            assertEquals(TaskState.Paused, paused.state)
            assertTrue("暂停后仍应保留任务", paused.kind == TaskKind.Torrent)
        } finally {
            runCatching { app.taskManager.remove(id, deleteFiles = true) }
        }
    }
}
