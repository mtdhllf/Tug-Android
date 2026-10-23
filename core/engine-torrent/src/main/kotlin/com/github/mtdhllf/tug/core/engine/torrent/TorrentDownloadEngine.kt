package com.github.mtdhllf.tug.core.engine.torrent

import com.github.mtdhllf.tug.core.engine.api.DownloadEngine
import com.github.mtdhllf.tug.core.engine.api.EngineEvent
import com.github.mtdhllf.tug.core.engine.api.LiveProgressSource
import com.github.mtdhllf.tug.core.engine.api.TorrentEngine
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.model.TorrentDetails
import com.github.mtdhllf.tug.core.model.TorrentFileEntry
import com.github.mtdhllf.tug.core.model.TorrentMetadata
import com.github.mtdhllf.tug.core.model.TorrentPeerEntry
import com.github.mtdhllf.tug.core.model.TorrentTrackerEntry
import com.github.mtdhllf.tug.core.model.TugLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * BitTorrent 引擎（libtorrent4j，MIT）。
 *
 * 设计：
 * - 惰性创建单例 [SessionManager]，首次启动任务时初始化；
 * - 任务启动时先拿元数据（磁力/HTTP 种子），再 add 到 session；
 * - 高频进度通过 [liveTasks] StateFlow 暴露，状态迁移走 `events()`；
 * - 暂停保留 handle（resume 可继续，未做进程级 fastresume，S9b 补）；
 * - 完成后将 torrent 从 session 移除（当前版本不做长效做种）。
 */
class TorrentDownloadEngine(
    private val workDir: File,
    private val scope: CoroutineScope,
) : TorrentEngine, LiveProgressSource {

    override val kind: String = "torrent"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    private val _live = MutableStateFlow<Map<Long, TaskView>>(emptyMap())

    private val runtimes = ConcurrentHashMap<Long, Runtime>()
    private val snapshots = ConcurrentHashMap<Long, TaskView>()
    private val trackerJobs = ConcurrentHashMap<Long, Job>()
    private val peerId: ByteArray = TrackerClient.newPeerId()

    private val sessionLock = Any()

    @Volatile
    private var session: SessionManager? = null

    private val pollerStarted = AtomicBoolean(false)

    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    override fun liveTasks(): StateFlow<Map<Long, TaskView>> = _live.asStateFlow()

    private data class Runtime(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val saveDir: String,
        val url: String,
        val name: String,
        val fileName: String?,
        val total: Long,
        val selected: Set<Int>,
        val trackers: List<String> = emptyList(),
        val completed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class Resolved(
        val info: TorrentInfo,
        val bytes: ByteArray?,
        val trackers: List<String>,
    )

    override suspend fun start(taskId: Long, input: NewTask, saveDir: String) = withContext(Dispatchers.IO) {
        // 已在运行：TaskManager.resume 通过 pump 再次调用 start，此时只需恢复既有 handle
        runtimes[taskId]?.let { existing ->
            runCatching { existing.handle.resume() }
            snapshots[taskId] = taskView(taskId, existing, TaskState.Downloading)
            _events.tryEmit(
                EngineEvent.State(taskId, TaskState.Downloading, name = existing.name),
            )
            TugLog.i(TAG, "start 命中已存在任务，恢复 id=$taskId")
            return@withContext
        }
        startPollerIfNeeded()
        _events.tryEmit(EngineEvent.State(taskId, TaskState.FetchingMetadata))
        TugLog.i(TAG, "start id=$taskId url=${input.url} saveDir=$saveDir")

        val manager = ensureSession()
        val resolved = resolve(manager, input)
        val info = resolved.info
        val dir = File(saveDir).apply { mkdirs() }

        val handle = addTorrent(manager, info, dir, input.onlyFiles, resolved.trackers)
            ?: throw IOException("添加种子失败（无法获取 handle）")

        val runtime = Runtime(
            info = info,
            handle = handle,
            saveDir = saveDir,
            url = input.url,
            name = info.name()?.takeIf { it.isNotBlank() } ?: "torrent-$taskId",
            fileName = info.name()?.takeIf { it.isNotBlank() },
            total = info.totalSize(),
            selected = selectedIndices(info, input.onlyFiles),
            trackers = resolved.trackers,
        )
        runCatching { handle.resume() }
        runtimes[taskId] = runtime
        startTrackerAnnounce(taskId, runtime)
        snapshots[taskId] = taskView(taskId, runtime, TaskState.Downloading)
        _events.tryEmit(
            EngineEvent.State(
                taskId = taskId,
                state = TaskState.Downloading,
                name = runtime.name,
                fileName = runtime.fileName,
            ),
        )
        TugLog.i(TAG, "started id=$taskId name=${runtime.name} total=${runtime.total}")
    }

    override suspend fun pause(taskId: Long) = withContext(Dispatchers.IO) {
        val runtime = runtimes[taskId] ?: return@withContext
        trackerJobs.remove(taskId)?.cancel()
        runCatching { runtime.handle.pause() }
        snapshots[taskId] = taskView(taskId, runtime, TaskState.Paused)
        _events.tryEmit(EngineEvent.State(taskId, TaskState.Paused, name = runtime.name))
        TugLog.i(TAG, "paused id=$taskId")
    }

    override suspend fun resume(taskId: Long) = withContext(Dispatchers.IO) {
        val runtime = runtimes[taskId] ?: return@withContext
        runCatching { runtime.handle.resume() }
        startTrackerAnnounce(taskId, runtime)
        snapshots[taskId] = taskView(taskId, runtime, TaskState.Downloading)
        _events.tryEmit(EngineEvent.State(taskId, TaskState.Downloading, name = runtime.name))
        TugLog.i(TAG, "resumed id=$taskId")
    }

    override suspend fun remove(taskId: Long, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        trackerJobs.remove(taskId)?.cancel()
        val runtime = runtimes.remove(taskId)
        snapshots.remove(taskId)
        refreshLive()
        if (runtime != null) {
            runCatching { session?.remove(runtime.handle) }
            if (deleteFiles) {
                runCatching {
                    val target = File(runtime.saveDir, runtime.name)
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                }
            }
        }
        TugLog.i(TAG, "removed id=$taskId deleteFiles=$deleteFiles")
    }

    override fun snapshot(taskId: Long): TaskView? = snapshots[taskId]

    override fun setDownloadRateLimit(bytesPerSecond: Long?) {
        val manager = session ?: return
        runCatching {
            manager.downloadRateLimit(
                (bytesPerSecond ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
        }
    }

    override fun shutdown() {
        trackerJobs.values.forEach { it.cancel() }
        trackerJobs.clear()
        runCatching { session?.stop() }
        session = null
        runtimes.clear()
        snapshots.clear()
        _live.value = emptyMap()
        TugLog.i(TAG, "shutdown")
    }

    private fun ensureSession(): SessionManager = synchronized(sessionLock) {
        session ?: SessionManager().apply {
            start(
                org.libtorrent4j.SessionParams(
                    buildSessionSettings(),
                ),
            )
        }.also { session = it }
    }

    /**
     * 移动网络下的性能/连通性设置。
     *
     * 注意：不能把上传限速压到极低——BT 的 tit-for-tat 会因此被 peer choke，
     * 下载速度会明显变慢；完成即移除 torrent，所以不会做种/长期上传。
     */
    private fun buildSessionSettings(): org.libtorrent4j.SettingsPack =
        org.libtorrent4j.SettingsPack().apply {
            connectionsLimit(500)
            activeLimit(500)
            activeDownloads(10)
            activeSeeds(10)
            maxPeerlistSize(4000)
            downloadRateLimit(0)
            uploadRateLimit(0)
            setEnableDht(true)
            setEnableLsd(true)
            runCatching {
                setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(),
                    true,
                )
                setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(),
                    true,
                )
                // 移动网络下保留 uTP（很多 peer 只开 uTP），不做强制禁用
            }
        }

    private fun startPollerIfNeeded() {
        if (!pollerStarted.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                delay(700)
                runCatching { tick() }
                    .onFailure { TugLog.w(TAG, "tick 失败: ${it.message}", it) }
            }
        }
    }

    private fun tick() {
        for ((id, runtime) in runtimes) {
            val status = runCatching { runtime.handle.status() }.getOrNull() ?: continue
            val total = runtime.total.takeIf { it > 0L } ?: status.totalWanted().takeIf { it > 0L }
            val downloaded = status.totalDone()
            val finished = status.progress() >= 1f && (total ?: 0L) > 0L
            val state = if (finished) TaskState.Completed else TaskState.Downloading
            val view = taskView(
                taskId = id,
                runtime = runtime,
                state = state,
                downloaded = downloaded,
                total = total,
                uploaded = status.totalUpload(),
                downloadBps = status.downloadRate().toLong(),
                uploadBps = status.uploadRate().toLong(),
                peers = runCatching { runtime.handle.peerInfo().size }
                    .getOrDefault(status.numPeers()),
            )
            snapshots[id] = view
            if (finished && runtime.completed.compareAndSet(false, true)) {
                TugLog.i(TAG, "completed id=$id name=${runtime.name} bytes=$downloaded")
                _events.tryEmit(
                    EngineEvent.State(
                        id,
                        TaskState.Completed,
                        name = runtime.name,
                        fileName = runtime.fileName,
                    ),
                )
                runtimes.remove(id)
                runCatching { session?.remove(runtime.handle) }
            }
        }
        refreshLive()
    }

    private fun refreshLive() {
        _live.value = snapshots.toMap()
    }

    private fun taskView(
        taskId: Long,
        runtime: Runtime,
        state: TaskState,
        downloaded: Long = snapshots[taskId]?.downloaded ?: 0L,
        total: Long? = runtime.total,
        uploaded: Long = snapshots[taskId]?.uploaded ?: 0L,
        downloadBps: Long = snapshots[taskId]?.downloadBps ?: 0L,
        uploadBps: Long = snapshots[taskId]?.uploadBps ?: 0L,
        peers: Int = snapshots[taskId]?.peers ?: 0,
    ): TaskView {
        val totalValue = total ?: runtime.total
        val eta = if (downloadBps > 0 && totalValue > downloaded) {
            (totalValue - downloaded) / downloadBps
        } else {
            null
        }
        return TaskView(
            id = taskId,
            kind = TaskKind.Torrent,
            name = runtime.name,
            url = runtime.url,
            saveDir = runtime.saveDir,
            fileName = runtime.fileName,
            state = state,
            total = totalValue,
            downloaded = downloaded,
            uploaded = uploaded,
            downloadBps = downloadBps,
            uploadBps = uploadBps,
            peers = peers,
            etaSecs = eta,
            connections = 0,
        )
    }

    override suspend fun parse(input: NewTask): TorrentMetadata = withContext(Dispatchers.IO) {
        startPollerIfNeeded()
        val manager = ensureSession()
        val resolved = resolve(manager, input)
        val info = resolved.info
        TorrentMetadata(
            name = info.name()?.takeIf { it.isNotBlank() } ?: "torrent",
            totalSize = info.totalSize(),
            files = filesOf(info, selectedIndices(info, input.onlyFiles)),
            torrentBytes = resolved.bytes,
        )
    }

    override fun torrentDetails(taskId: Long): TorrentDetails? {
        val runtime = runtimes[taskId] ?: return null
        return runCatching {
            TorrentDetails(
                infoHash = runtime.info.infoHash().toHex(),
                files = filesOf(runtime.info, runtime.selected),
                peers = runtime.handle.peerInfo().map { peer ->
                    TorrentPeerEntry(
                        address = peer.ip(),
                        client = peer.client(),
                        downBps = peer.downSpeed().toLong(),
                        upBps = peer.upSpeed().toLong(),
                        progress = peer.progress(),
                    )
                },
                trackers = trackerEntries(runtime),
            )
        }.onFailure {
            TugLog.w(TAG, "torrentDetails 失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun trackerEntries(runtime: Runtime): List<TorrentTrackerEntry> {
        // 直接使用解析出的 tracker 列表：避免 handle.trackers() 在 announce 期间阻塞
        return runtime.trackers.mapIndexed { index, url ->
            TorrentTrackerEntry(url = url, tier = index, verified = false)
        }
    }

    /** 解析磁力 / HTTP 种子 URL / 本地种子字节，并提取 Tracker。 */
    private fun resolve(manager: SessionManager, input: NewTask): Resolved {
        input.torrentBytes?.let { bytes ->
            val trackers = (trackersFromTorrentBytes(bytes) + DEFAULT_TRACKERS).distinct()
            return Resolved(TorrentInfo(bytes), bytes, trackers)
        }
        val url = input.url
        if (url.startsWith("magnet:", ignoreCase = true)) {
            waitForDht(manager, 10_000)
            val tmp = File(workDir, "metadata").apply { mkdirs() }
            val magnet = ensurePublicTrackers(url)
            val trackers = magnetTrackers(magnet)
            var lastError: String? = null
            repeat(METADATA_ATTEMPTS) { attempt ->
                TugLog.i(TAG, "fetching magnet metadata attempt=${attempt + 1}: ${magnet.take(120)}")
                val bytes = runCatching {
                    manager.fetchMagnet(magnet, METADATA_TIMEOUT_SEC, tmp)
                }.onFailure {
                    lastError = it.message
                    TugLog.w(TAG, "magnet metadata attempt=${attempt + 1} 失败: ${it.message}")
                }.getOrNull()
                if (bytes != null) return Resolved(TorrentInfo(bytes), bytes, trackers)
                lastError = lastError ?: "超时"
            }
            throw IOException("磁力链接元数据获取失败（重试 $METADATA_ATTEMPTS 次）: $lastError")
        }
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            val connection = java.net.URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val bytes = connection.getInputStream().use { it.readBytes() }
            val trackers = (trackersFromTorrentBytes(bytes) + DEFAULT_TRACKERS).distinct()
            return Resolved(TorrentInfo(bytes), bytes, trackers)
        }
        throw IllegalArgumentException("不支持的 BT 来源：${url.take(120)}")
    }

    /** 从 bencode 种子内容提取 announce / announce-list（保持 tier 顺序）。 */
    private fun trackersFromTorrentBytes(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        return runCatching {
            val root = BencodeReader(bytes).read() as? Map<*, *> ?: return emptyList()
            val urls = mutableListOf<String>()
            (root["announce"] as? String)?.takeIf { it.isNotBlank() }?.let(urls::add)
            (root["announce-list"] as? List<*>)?.forEach { tier ->
                (tier as? List<*>)?.forEach { item ->
                    (item as? String)?.takeIf { it.isNotBlank() }?.let(urls::add)
                }
            }
            urls.distinct()
        }.getOrDefault(emptyList())
    }

    private fun magnetTrackers(magnet: String): List<String> {
        val urls = Regex("[?&]tr=([^&]+)").findAll(magnet)
            .mapNotNull { match ->
                runCatching {
                    java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                }.getOrNull()
            }
            .toList()
        return urls.distinct().ifEmpty { DEFAULT_TRACKERS }
    }

    /** 显式把 tracker 添加到 handle 并立即 reannounce（避免只依赖 DHT 导致慢）。 */
    private fun applyTrackers(handle: TorrentHandle, trackers: List<String>) {
        if (trackers.isEmpty()) return
        val existing = runCatching { handle.trackers().map { it.url() } }
            .getOrDefault(emptyList())
        trackers.filterNot { existing.contains(it) }.forEach { url ->
            runCatching { handle.addTracker(AnnounceEntry(url)) }
                .onFailure { TugLog.w(TAG, "addTracker 失败 url=$url: ${it.message}") }
        }
        TugLog.i(TAG, "trackers applied=${trackers.size} new=${(trackers - existing.toSet()).size}")
    }

    private fun filesOf(info: TorrentInfo, selected: Set<Int>): List<TorrentFileEntry> {
        val storage = info.files()
        return (0 until info.numFiles()).map { index ->
            TorrentFileEntry(
                index = index,
                path = storage.filePath(index),
                size = storage.fileSize(index),
                selected = selected.contains(index),
            )
        }
    }

    private fun selectedIndices(info: TorrentInfo, onlyFiles: List<Int>?): Set<Int> {
        if (onlyFiles.isNullOrEmpty()) return (0 until info.numFiles()).toSet()
        return onlyFiles.filter { it in 0 until info.numFiles() }.toSet()
    }

    private fun applyFileSelection(handle: TorrentHandle, info: TorrentInfo, onlyFiles: List<Int>?) {
        if (onlyFiles.isNullOrEmpty()) return
        val selected = selectedIndices(info, onlyFiles)
        val priorities = Priority.array(Priority.IGNORE, info.numFiles())
        selected.forEach { priorities[it] = Priority.DEFAULT }
        runCatching { handle.prioritizeFiles(priorities) }
    }

    /** 等待 DHT 预热（最多 [timeoutMs]），提升磁力元数据成功率。 */
    private fun waitForDht(manager: SessionManager, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val nodes = runCatching { manager.stats().dhtNodes() }.getOrDefault(0L)
            if (nodes > 0) {
                TugLog.i(TAG, "DHT ready nodes=$nodes")
                return
            }
            Thread.sleep(500)
        }
        TugLog.w(TAG, "DHT 预热超时，继续尝试 tracker")
    }

    /** 磁力缺少 tracker 时追加公共 tracker，作为 DHT 的兜底。 */
    private fun ensurePublicTrackers(magnet: String): String {
        if (magnet.contains("&tr=", ignoreCase = true) ||
            magnet.contains("?tr=", ignoreCase = true)
        ) {
            return magnet
        }
        val suffix = DEFAULT_TRACKERS.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return magnet + suffix
    }

    /**
     * add torrent 并获取**持久 handle**。
     *
     * 注意：不能保存 `AddTorrentAlert.handle()` —— 它只在 alert 回调内有效，
     * 跨回调使用会在 `status()` 时因 native shared_ptr 失效而 SIGSEGV。
     */
    private fun addTorrent(
        manager: SessionManager,
        info: TorrentInfo,
        saveDir: File,
        onlyFiles: List<Int>? = null,
        trackers: List<String> = emptyList(),
    ): TorrentHandle? {
        manager.download(info, saveDir)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            manager.find(info.infoHash())?.let { handle ->
                applyFileSelection(handle, info, onlyFiles)
                applyTrackers(handle, trackers)
                return handle
            }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * 手动 HTTPS tracker announce（libtorrent4j Android 构建无 OpenSSL），
     * 把 tracker 返回的 peers 主动注入 handle。
     */
    private fun startTrackerAnnounce(taskId: Long, runtime: Runtime) {
        if (runtime.trackers.isEmpty()) return
        trackerJobs.remove(taskId)?.cancel()
        trackerJobs[taskId] = scope.launch(Dispatchers.IO) {
            var first = true
            while (isActive) {
                val downloaded = snapshots[taskId]?.downloaded ?: 0L
                val left = (runtime.total - downloaded).coerceAtLeast(0L)
                val infoHash = hexToBytes(runtime.info.infoHash().toHex())
                val peers = runCatching {
                    TrackerClient.announce(
                        trackers = runtime.trackers,
                        infoHash = infoHash,
                        peerId = peerId,
                        port = 6881,
                        downloaded = downloaded,
                        left = left,
                        event = if (first) "started" else "",
                    )
                }.onFailure {
                    TugLog.w(TAG, "tracker announce 失败: ${it.message}")
                }.getOrDefault(emptyList())
                if (peers.isNotEmpty()) {
                    var connected = 0
                    peers.forEach { peer ->
                        if (connectPeer(runtime.handle, peer)) connected++
                    }
                    TugLog.i(TAG, "tracker peers=${peers.size} connected=$connected")
                }
                first = false
                delay(10 * 60_000L)
            }
        }
    }

    private fun connectPeer(handle: TorrentHandle, peer: TrackerClient.Peer): Boolean {
        return runCatching {
            val ec = org.libtorrent4j.swig.error_code()
            val address = org.libtorrent4j.swig.address.from_string(peer.ip, ec)
            if (ec.value() != 0) return false
            handle.swig().connect_peer(org.libtorrent4j.swig.tcp_endpoint(address, peer.port))
            true
        }.getOrDefault(false)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    /** 极简 bencode 读取器（只用于提取 announce / announce-list）。 */
    private class BencodeReader(private val data: ByteArray) {
        private var index = 0

        fun read(): Any? = when (data[index].toInt().toChar()) {
            'd' -> readDict()
            'l' -> readList()
            'i' -> readInt()
            else -> readString()
        }

        private fun readDict(): Map<String, Any?> {
            index++ // d
            val map = LinkedHashMap<String, Any?>()
            while (index < data.size && data[index].toInt().toChar() != 'e') {
                val key = readString()
                map[key] = read()
            }
            index++ // e
            return map
        }

        private fun readList(): List<Any?> {
            index++ // l
            val list = mutableListOf<Any?>()
            while (index < data.size && data[index].toInt().toChar() != 'e') {
                list.add(read())
            }
            index++ // e
            return list
        }

        private fun readInt(): Long {
            index++ // i
            val start = index
            while (data[index].toInt().toChar() != 'e') index++
            val value = data.decodeToString(start, index).toLongOrNull() ?: 0L
            index++ // e
            return value
        }

        private fun readString(): String {
            val start = index
            while (data[index].toInt().toChar() != ':') index++
            val length = data.decodeToString(start, index).toIntOrNull() ?: 0
            index++ // :
            val value = data.decodeToString(index, index + length)
            index += length
            return value
        }
    }

    private companion object {
        const val TAG = "TugBt"
        const val METADATA_TIMEOUT_SEC = 45
        const val METADATA_ATTEMPTS = 2
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
        )
    }
}
