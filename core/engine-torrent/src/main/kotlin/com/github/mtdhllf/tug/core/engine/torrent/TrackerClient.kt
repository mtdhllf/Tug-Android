package com.github.mtdhllf.tug.core.engine.torrent

import com.github.mtdhllf.tug.core.model.TugLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom

/**
 * 轻量 Tracker 客户端。
 *
 * 背景：libtorrent4j 的 Android 原生库未启用 OpenSSL，HTTPS tracker 会报
 * `init fail (BIO routines)`；而 Ubuntu 等官方种子只提供 HTTPS tracker。
 * 这里用 Android 自带 TLS 做 announce，解析 compact peers，再注入 libtorrent。
 */
internal object TrackerClient {

    private const val TAG = "TugBtTracker"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    data class Peer(val ip: String, val port: Int)

    private val random = SecureRandom()

    fun newPeerId(): ByteArray {
        val prefix = "-TU1100-".toByteArray(Charsets.US_ASCII)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = ByteArray(12) { alphabet[random.nextInt(alphabet.length)].code.toByte() }
        return prefix + suffix
    }

    /**
     * 依次向所有 tracker announce，返回去重后的 peers。
     *
     * 返回的 peers 用于 `torrent_handle.connect_peer` 主动连接。
     */
    fun announce(
        trackers: List<String>,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        downloaded: Long,
        left: Long,
        event: String = "started",
    ): List<Peer> {
        val result = LinkedHashSet<Peer>()
        trackers.forEach { tracker ->
            val peers = runCatching {
                announceOne(tracker, infoHash, peerId, port, downloaded, left, event)
            }.onFailure {
                TugLog.w(TAG, "tracker announce 失败 $tracker: ${it.message}")
            }.getOrDefault(emptyList())
            if (peers.isNotEmpty()) {
                TugLog.i(TAG, "tracker $tracker peers=${peers.size}")
                result.addAll(peers)
            }
        }
        return result.toList()
    }

    private fun announceOne(
        tracker: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        port: Int,
        downloaded: Long,
        left: Long,
        event: String,
    ): List<Peer> {
        val params = buildString {
            append("info_hash=").append(percentEncode(infoHash))
            append("&peer_id=").append(percentEncode(peerId))
            append("&port=").append(port)
            append("&uploaded=0")
            append("&downloaded=").append(downloaded.coerceAtLeast(0))
            append("&left=").append(left.coerceAtLeast(0))
            append("&compact=1")
            append("&numwant=200")
            if (event.isNotBlank()) append("&event=").append(event)
        }
        val separator = if (tracker.contains('?')) '&' else '?'
        val url = URL(tracker + separator + params)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Tug-Android/1.1")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                TugLog.w(TAG, "tracker $tracker HTTP $code")
                return emptyList()
            }
            val body = connection.inputStream.use { it.readBytes() }
            parsePeers(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePeers(body: ByteArray): List<Peer> {
        val root = BencodeParser(body).read() as? Map<*, *> ?: return emptyList()
        (root["failure reason"] as? ByteArray)?.let {
            TugLog.w(TAG, "tracker failure: ${String(it, Charsets.UTF_8)}")
            return emptyList()
        }
        val peers = LinkedHashSet<Peer>()
        (root["peers"] as? ByteArray)?.let { compact ->
            var i = 0
            while (i + 6 <= compact.size) {
                val ip = "${compact[i].toInt() and 0xff}.${compact[i + 1].toInt() and 0xff}." +
                    "${compact[i + 2].toInt() and 0xff}.${compact[i + 3].toInt() and 0xff}"
                val port = ((compact[i + 4].toInt() and 0xff) shl 8) or
                    (compact[i + 5].toInt() and 0xff)
                if (port > 0) peers.add(Peer(ip, port))
                i += 6
            }
        }
        (root["peers6"] as? ByteArray)?.let { compact ->
            var i = 0
            while (i + 18 <= compact.size) {
                val address = InetAddress.getByAddress(compact.copyOfRange(i, i + 16))
                val port = ((compact[i + 16].toInt() and 0xff) shl 8) or
                    (compact[i + 17].toInt() and 0xff)
                if (port > 0) peers.add(Peer(address.hostAddress ?: "", port))
                i += 18
            }
        }
        return peers.filter { it.ip.isNotBlank() }
    }

    private fun percentEncode(bytes: ByteArray): String {
        val out = ByteArrayOutputStream(bytes.size * 3)
        bytes.forEach { b ->
            val v = b.toInt() and 0xff
            val c = v.toChar()
            if (c.isLetterOrDigit() && v < 128 || c == '-' || c == '_' || c == '.' || c == '~') {
                out.write(v)
            } else {
                out.write('%'.code)
                out.write("0123456789ABCDEF"[v shr 4].code)
                out.write("0123456789ABCDEF"[v and 0x0f].code)
            }
        }
        return String(out.toByteArray(), Charsets.US_ASCII)
    }

    /** 仅用于 tracker 响应的 bencode 解析：字符串保留原始字节。 */
    private class BencodeParser(private val data: ByteArray) {
        private var index = 0

        fun read(): Any? = when (data[index].toInt().toChar()) {
            'd' -> readDict()
            'l' -> readList()
            'i' -> readInt()
            else -> readBytes()
        }

        private fun readDict(): Map<String, Any?> {
            index++
            val map = LinkedHashMap<String, Any?>()
            while (index < data.size && data[index].toInt().toChar() != 'e') {
                val key = String(readBytes(), Charsets.ISO_8859_1)
                map[key] = read()
            }
            index++
            return map
        }

        private fun readList(): List<Any?> {
            index++
            val list = mutableListOf<Any?>()
            while (index < data.size && data[index].toInt().toChar() != 'e') {
                list.add(read())
            }
            index++
            return list
        }

        private fun readInt(): Long {
            index++
            val start = index
            while (data[index].toInt().toChar() != 'e') index++
            val value = String(data, start, index - start, Charsets.US_ASCII).toLongOrNull() ?: 0L
            index++
            return value
        }

        private fun readBytes(): ByteArray {
            val start = index
            while (data[index].toInt().toChar() != ':') index++
            val length = String(data, start, index - start, Charsets.US_ASCII).toIntOrNull() ?: 0
            index++
            val value = data.copyOfRange(index, index + length)
            index += length
            return value
        }
    }
}
