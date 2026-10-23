package com.github.mtdhllf.tug.core.engine.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 限速器（字节/秒），采用"预约时间片"节拍：每次申请把下一次可用时刻向后推，
 * 申请者等到自己的时间片再返回。
 *
 * 与旧实现的区别：不再要求"令牌桶里攒够 byteCount"，因此单次读块大于桶容量时
 * 不会死循环（旧实现 `limit < 64 KiB/s` 会永久卡住）。
 *
 * `limitBps == null` 或 `<= 0` 表示不限速。运行中修改 [limitBps] 立即生效。
 */
class RateLimiter(
    @Volatile var limitBps: Long? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var nextSlotMs: Double = 0.0

    suspend fun acquire(byteCount: Int) {
        if (byteCount <= 0) return
        val limit = limitBps
        if (limit == null || limit <= 0L) return

        val waitMs = mutex.withLock {
            val now = nowMs().toDouble()
            val start = if (nextSlotMs > now) nextSlotMs else now
            nextSlotMs = start + byteCount * 1000.0 / limit
            start - now
        }
        if (waitMs > 0) {
            delay(waitMs.toLong().coerceAtLeast(1L))
        }
    }
}
