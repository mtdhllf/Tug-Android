package com.github.mtdhllf.tug.core.engine.http

import com.github.mtdhllf.tug.core.model.TaskState
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * 参考 okdownload SpeedCalculator，但下载热路径不加锁：
 * - 累计用 [LongAdder]（多分片并发）
 * - 速度只在 flush 时计算
 */
class SpeedCalculator {
    private val increase = LongAdder()
    private val allIncrease = LongAdder()
    private val timestamp = AtomicLong(0)
    private val bytesPerSecond = AtomicLong(0)
    private val beginTimestamp = AtomicLong(0)

    fun reset() {
        increase.reset()
        allIncrease.reset()
        timestamp.set(0)
        bytesPerSecond.set(0)
        beginTimestamp.set(0)
    }

    /** 下载线程调用：只累加，不算速度、不加锁。 */
    fun downloading(deltaBytes: Long) {
        val now = nowMillis()
        timestamp.compareAndSet(0L, now)
        beginTimestamp.compareAndSet(0L, now)
        increase.add(deltaBytes)
        allIncrease.add(deltaBytes)
    }

    /**
     * 采样线程调用：约 1s 一次重算速度。
     * 首次 500ms 内可先给出值（okdownload 策略）。
     */
    fun bytesPerSecondAndFlush(): Long {
        val now = nowMillis()
        val ts = timestamp.get()
        val interval = now - ts
        val cached = bytesPerSecond.get()
        if (interval < 1000 && cached != 0L) return cached
        if (cached == 0L && interval < 500 && ts != 0L) return 0L

        val since = increase.sumThenReset()
        val duration = (now - ts).coerceAtLeast(1L)
        val speed = (since.toFloat() / duration * 1000f).toLong()
        timestamp.set(now)
        bytesPerSecond.set(speed)
        return speed
    }

    fun allIncreaseBytes(): Long = allIncrease.sum()

    fun downloadedProxy(): Long = allIncrease.sum()

    private fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * 单任务运行时统计（多分段共用）。
 *
 * 状态统一用 [TaskState] 枚举，避免旧版字符串比较把非法值静默落成 Downloading。
 */
class TaskRuntime(
    val taskId: Long,
) {
    val downloaded = AtomicLong(0L)
    val speed = SpeedCalculator()

    @Volatile var total: Long? = null
    @Volatile var name: String = ""
    @Volatile var fileName: String? = null
    @Volatile var saveDir: String = ""
    @Volatile var url: String = ""
    @Volatile var state: TaskState = TaskState.Queued
    @Volatile var error: String? = null

    /** 本任务连接数（用于展示与分片调度）。 */
    @Volatile var connections: Int = 8

    /** 是否可断点续传；无 Range 的单流任务为 false。 */
    @Volatile var resumable: Boolean = true

    /** 下载中的暂存文件（`.part`），完成后 rename 为最终文件。 */
    @Volatile var partFile: File? = null

    fun addDownloaded(n: Long) {
        if (n == 0L) return
        downloaded.addAndGet(n)
        speed.downloading(n)
    }

    /** 分片重试时把本轮已计入的字节扣回，避免进度虚高。 */
    fun subtractDownloaded(n: Long) {
        if (n == 0L) return
        downloaded.addAndGet(-n)
    }
}
