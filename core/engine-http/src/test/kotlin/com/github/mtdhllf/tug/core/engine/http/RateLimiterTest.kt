package com.github.mtdhllf.tug.core.engine.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun acquire_withoutLimit_isInstant() = runBlocking {
        val limiter = RateLimiter(limitBps = null)
        val start = System.currentTimeMillis()
        repeat(50) { limiter.acquire(64 * 1024) }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("should be instant, was ${elapsed}ms", elapsed < 100)
    }

    @Test
    fun acquire_throttlesToLimit() = runBlocking {
        // 200 KB/s → 100 KB 约需 500ms
        val limiter = RateLimiter(limitBps = 200_000L)
        val start = System.currentTimeMillis()
        var bytes = 0
        while (bytes < 100_000) {
            limiter.acquire(8_000)
            bytes += 8_000
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("expected >= 300ms, was ${elapsed}ms", elapsed >= 300)
        assertTrue("expected < 2000ms, was ${elapsed}ms", elapsed < 2000)
    }

    @Test
    fun acquire_concurrentCallers_shareBucket() = runBlocking {
        val limiter = RateLimiter(limitBps = 100_000L)
        val start = System.currentTimeMillis()
        val jobs = List(4) {
            launch {
                repeat(10) { limiter.acquire(5_000) }
            }
        }
        jobs.forEach { it.join() }
        val elapsed = System.currentTimeMillis() - start
        // 4*10*5000 = 200KB at 100KB/s → ~2s, allow 1s–4s
        assertTrue("expected >= 1000ms, was ${elapsed}ms", elapsed >= 1000)
        assertTrue("expected < 4000ms, was ${elapsed}ms", elapsed < 4000)
    }

    /** 回归：单次申请量大于"1 秒额度"时不能死循环（旧实现会永久卡住）。 */
    @Test
    fun acquire_largerThanBucketCapacity_doesNotHang() = runBlocking {
        val limiter = RateLimiter(limitBps = 1_000L)
        val start = System.currentTimeMillis()
        limiter.acquire(64 * 1024)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("首块应立即放行，实际 ${elapsed}ms", elapsed < 500)
    }
}
