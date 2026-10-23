package com.github.mtdhllf.tug.core.engine.torrent.spike

import android.util.Log
import java.io.File
import org.libtorrent4j.AlertListener
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert

/**
 * S8 BT Spike：真机验证 libtorrent4j 的原生加载、magnet 元数据与下载。
 *
 * 这是探针代码，验证通过后会演进为正式的 [com.github.mtdhllf.tug.core.engine.torrent.TorrentDownloadEngine]。
 */
object LibtorrentSpike {

    private const val TAG = "BTSPIKE"
    private const val MB = 1024L * 1024L

    data class Result(
        val libtorrentVersion: String,
        val metadataMs: Long,
        val name: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val dhtNodes: Long,
        val elapsedMs: Long,
    )

    data class FileResult(
        val name: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val dhtNodes: Long,
        val elapsedMs: Long,
    )

    /** 打开 alert 全量日志（排障用；默认关闭避免刷屏）。 */
    @Volatile
    var verboseAlerts: Boolean = false

    private fun logAlert(alert: Alert<*>) {
        if (!verboseAlerts) return
        val text = alert.toString()
        Log.i(TAG, "alert ${alert.type()} ${text.take(180)}")
    }

    private fun startStatsMonitor(session: SessionManager, label: String): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(1000)
                    val stats = session.stats()
                    Log.i(
                        TAG,
                        "$label dht=${stats.dhtNodes()} down=${stats.totalDownload()} " +
                            "up=${stats.totalUpload()} rate=${stats.downloadRate()}",
                    )
                } catch (_: Throwable) {
                    // session 已停止
                }
            }
        }.apply {
            isDaemon = true
            name = "bt-spike-monitor"
            start()
        }
        return {
            running.set(false)
            runCatching { thread.join(1500) }
        }
    }

    /**
     * @param cacheDir 私有缓存目录（staging）
     * @param magnet 磁力链接（建议带 tracker）
     * @param metadataTimeoutSec 元数据超时
     * @param downloadSeconds 元数据拿到后最多观察下载的秒数
     */
    fun run(
        cacheDir: File,
        magnet: String,
        metadataTimeoutSec: Int = 60,
        downloadSeconds: Int = 20,
    ): Result {
        val t0 = System.currentTimeMillis()
        val version = LibTorrent.version()
        Log.i(TAG, "libtorrent=${LibTorrent.version()} libtorrent4j=${LibTorrent.libtorrent4jVersion()}")

        val session = SessionManager()
        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) = logAlert(alert)
        })
        val saveDir = File(cacheDir, "bt-spike/data").apply { mkdirs() }
        val tmpDir = File(cacheDir, "bt-spike/tmp").apply { mkdirs() }
        session.start()
        logAlertEnabled()
        try {
            val stopMonitor = startStatsMonitor(session, "magnet")
            val metaStart = System.currentTimeMillis()
            val torrentBytes = try {
                session.fetchMagnet(magnet, metadataTimeoutSec, tmpDir)
            } finally {
                stopMonitor()
            }
            if (torrentBytes == null) {
                val stats = session.stats()
                error(
                    "magnet 元数据获取失败（${metadataTimeoutSec}s 超时, " +
                        "dht=${stats.dhtNodes()} down=${stats.totalDownload()} " +
                        "up=${stats.totalUpload()}）",
                )
            }
            val metadataMs = System.currentTimeMillis() - metaStart
            val info = TorrentInfo(torrentBytes)
            Log.i(
                TAG,
                "metadata ok: name=${info.name()} total=${info.totalSize()} " +
                    "files=${info.numFiles()} in ${metadataMs}ms",
            )

            session.download(info, saveDir)
            val downloaded = observeDownload(session, downloadSeconds)
            val result = Result(
                libtorrentVersion = version,
                metadataMs = metadataMs,
                name = info.name(),
                totalBytes = info.totalSize(),
                downloadedBytes = downloaded,
                dhtNodes = session.stats().dhtNodes(),
                elapsedMs = System.currentTimeMillis() - t0,
            )
            Log.i(TAG, "SPIKE_RESULT $result")
            return result
        } finally {
            runCatching { session.stop() }
            Log.i(TAG, "session stopped")
        }
    }

    /** 由测试直接传 `.torrent` 字节，绕过 magnet 元数据阶段。 */
    fun runTorrentFile(
        cacheDir: File,
        torrentBytes: ByteArray,
        downloadSeconds: Int = 20,
    ): FileResult {
        val t0 = System.currentTimeMillis()
        val session = SessionManager()
        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) = logAlert(alert)
        })
        val saveDir = File(cacheDir, "bt-spike-file/data").apply { mkdirs() }
        session.start()
        try {
            val info = TorrentInfo(torrentBytes)
            Log.i(TAG, "torrent file: name=${info.name()} total=${info.totalSize()}")
            session.download(info, saveDir)
            val downloaded = observeDownload(session, downloadSeconds)
            val result = FileResult(
                name = info.name(),
                totalBytes = info.totalSize(),
                downloadedBytes = downloaded,
                dhtNodes = session.stats().dhtNodes(),
                elapsedMs = System.currentTimeMillis() - t0,
            )
            Log.i(TAG, "SPIKE_FILE_RESULT $result")
            return result
        } finally {
            runCatching { session.stop() }
            Log.i(TAG, "session stopped (file)")
        }
    }

    private fun observeDownload(session: SessionManager, seconds: Int): Long {
        val until = System.currentTimeMillis() + seconds * 1000L
        var maxDownloaded = 0L
        while (System.currentTimeMillis() < until) {
            Thread.sleep(1000)
            val stats = session.stats()
            maxDownloaded = maxOf(maxDownloaded, stats.totalDownload())
            Log.i(TAG, "download progress=$maxDownloaded rate=${stats.downloadRate()} dht=${stats.dhtNodes()}")
            if (maxDownloaded >= 3 * MB) break
        }
        return maxDownloaded
    }

    private fun logAlertEnabled() {
        Log.i(TAG, "verboseAlerts=$verboseAlerts")
    }
}
