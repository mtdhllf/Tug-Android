package com.github.mtdhllf.tug.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mtdhllf.tug.core.engine.torrent.spike.LibtorrentSpike
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S8 BT Spike：真机验证 libtorrent4j 加载、magnet 元数据与真实下载。
 *
 * 运行：
 * adb shell am instrument -w -e class com.github.mtdhllf.tug.torrent.LibtorrentSpikeTest \
 *   com.github.mtdhllf.tug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LibtorrentSpikeTest {

    private val magnet =
        "magnet:?xt=urn:btih:7acf8fb590b2060dd9c3146ef770169d593433b0" +
            "&dn=debian-13.7.0-amd64-netinst.iso" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce" +
            "&tr=http%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"

    private val torrentUrl =
        "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/debian-13.7.0-amd64-netinst.iso.torrent"

    @Test(timeout = 240_000)
    fun magnet_metadata_and_download() {
        LibtorrentSpike.verboseAlerts = true
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = try {
            LibtorrentSpike.run(
                cacheDir = context.cacheDir,
                magnet = magnet,
                metadataTimeoutSec = 60,
                downloadSeconds = 20,
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
        println("SPIKE_RESULT=$result")
        assertTrue("libtorrent 版本为空", result.libtorrentVersion.isNotBlank())
        assertTrue("元数据未拿到", result.totalBytes > 0)
        assertTrue("未下载到真实数据", result.downloadedBytes > 0)
    }

    @Test(timeout = 240_000)
    fun torrent_file_download() {
        LibtorrentSpike.verboseAlerts = true
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = URI(torrentUrl).toURL().openStream().use { it.readBytes() }
        val result = try {
            LibtorrentSpike.runTorrentFile(context.cacheDir, bytes, downloadSeconds = 20)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
        println("SPIKE_FILE_RESULT=$result")
        assertTrue("种子解析失败", result.totalBytes > 0)
        assertTrue("未下载到真实数据", result.downloadedBytes > 0)
    }
}
