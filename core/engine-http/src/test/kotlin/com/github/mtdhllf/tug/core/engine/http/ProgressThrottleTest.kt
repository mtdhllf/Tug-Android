package com.github.mtdhllf.tug.core.engine.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    @Test
    fun emitsAboutOncePerSecond() {
        var now = 0L
        val throttle = ProgressThrottle(intervalMs = 1_000L, nowMs = { now })

        assertTrue(throttle.onSample(100)) // first always emits
        assertFalse(throttle.onSample(200)) // too soon
        now = 500
        assertFalse(throttle.onSample(300))
        now = 1_000
        assertTrue(throttle.onSample(1_100))
    }

    @Test
    fun forceAlwaysEmits() {
        var now = 0L
        val throttle = ProgressThrottle(intervalMs = 1_000L, nowMs = { now })
        assertTrue(throttle.onSample(1))
        assertTrue(throttle.onSample(2, force = true))
    }

    @Test
    fun speedIsEwmaOfDeltas() {
        var now = 0L
        val throttle = ProgressThrottle(intervalMs = 1_000L, nowMs = { now })
        throttle.onSample(0, force = true)
        now = 1_000
        throttle.onSample(1_000_000, force = true) // 1MB/s
        assertEquals(1_000_000L, throttle.speed())
        now = 2_000
        throttle.onSample(1_000_000, force = true) // 0 B/s
        // EWMA should drop but not jump to 0 immediately
        assertTrue(throttle.speed() < 1_000_000L)
        assertTrue(throttle.speed() > 0L)
    }
}
