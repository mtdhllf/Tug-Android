package com.github.mtdhllf.tug.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.TugApp
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 诊断：用真实 Ubuntu `.torrent` 检查 Tracker / Peers / 速率。
 *
 * 需要手动把种子 push 到应用外部 files 目录；Ubuntu tracker 会返回
 * “not authorized”，因此不做 CI 断言，默认忽略。
 */
@Ignore("需要手动 push ubuntu.torrent，且 Ubuntu tracker 可能拒绝 announce")
@RunWith(AndroidJUnit4::class)
class TorrentDiagnosticsInstrumentedTest {

    @Test(timeout = 180_000)
    fun ubuntu_trackers_peers_speed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as TugApp
        val torrentFile = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir,
        ).map { File(it, "ubuntu.torrent") }.firstOrNull { it.exists() }
            ?: error("缺少 ubuntu.torrent（已检查 files 与外部 files）")
        val bytes = torrentFile.readBytes()

        val id = app.taskManager.add(
            NewTask(
                url = "torrent://ubuntu",
                kind = TaskKind.Torrent,
                torrentBytes = bytes,
            ),
        )
        try {
            withTimeout(60_000) {
                while (true) {
                    val task = app.taskManager.tasks.value.firstOrNull { it.id == id }
                    if (task?.state == TaskState.Failed) error("任务失败: ${task.error}")
                    if (task?.state == TaskState.Downloading && task.total != null) break
                    delay(500)
                }
            }
            var maxDownloaded = 0L
            var maxRate = 0L
            var lastPeersTotal = 0
            var lastTrackers = -1
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) {
                delay(1_000)
                val task = app.taskManager.tasks.value.firstOrNull { it.id == id } ?: break
                maxDownloaded = maxOf(maxDownloaded, task.downloaded)
                maxRate = maxOf(maxRate, task.downloadBps)
                val details = app.torrentEngine.torrentDetails(id)
                lastPeersTotal = details?.peers?.size ?: -1
                lastTrackers = details?.trackers?.size ?: -1
                android.util.Log.i(
                    "BTDIAG",
                    "state=${task.state} down=${task.downloaded} rate=${task.downloadBps} " +
                        "statusPeers=${task.peers} detailPeers=$lastPeersTotal " +
                        "trackers=$lastTrackers " +
                        "trackerUrls=${details?.trackers?.joinToString { it.url }}",
                )
            }
            android.util.Log.i(
                "BTDIAG",
                "RESULT downloaded=$maxDownloaded maxRate=$maxRate " +
                    "detailPeers=$lastPeersTotal trackers=$lastTrackers",
            )
            assertTrue("Tracker 为空", lastTrackers > 0)
            assertTrue("没有下载到数据", maxDownloaded > 0L)
        } finally {
            runCatching { app.taskManager.remove(id, deleteFiles = true) }
        }
    }
}
