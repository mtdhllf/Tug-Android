package com.github.mtdhllf.tug.core.engine.http

import java.util.concurrent.atomic.AtomicLong

/**
 * 进度上报节流与速度估算。
 *
 * 默认约 1s 一次；速度用 EWMA，避免瞬时尖刺。线程安全（多分段并发采样）。
 */
class ProgressThrottle(
    private val intervalMs: Long = 1_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var initialized = false
    private var lastEmit = 0L
    private var lastBytes = 0L
    private var speedBps = 0L

    /**
     * 记录当前已下载字节；若到间隔则更新速度并返回 true（表示应上报）。
     */
    fun onSample(downloaded: Long, force: Boolean = false): Boolean {
        synchronized(lock) {
            val now = nowMs()
            if (initialized && !force && now - lastEmit < intervalMs) {
                return false
            }
            if (initialized) {
                val elapsed = (now - lastEmit).coerceAtLeast(1L)
                val instant = (downloaded - lastBytes) * 1000L / elapsed
                speedBps = if (speedBps == 0L) instant else (speedBps * 7 + instant) / 8
            } else {
                speedBps = 0L
            }
            lastBytes = downloaded
            lastEmit = now
            initialized = true
            return true
        }
    }

    fun speed(): Long = synchronized(lock) { speedBps }

    fun reset() {
        synchronized(lock) {
            initialized = false
            lastEmit = 0L
            lastBytes = 0L
            speedBps = 0L
        }
    }
}

/** 原子累计已下载字节（多分段并发加）。 */
class ByteCounter(initial: Long = 0L) {
    private val value = AtomicLong(initial)

    fun add(n: Long) = value.addAndGet(n)

    fun get(): Long = value.get()
}
