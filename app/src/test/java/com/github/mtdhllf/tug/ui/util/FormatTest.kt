package com.github.mtdhllf.tug.ui.util

import com.github.mtdhllf.tug.R
import com.github.mtdhllf.tug.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun formatBytes_scalesUnits() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun formatSpeed_handlesZeroAndUnits() {
        assertEquals("0 B/s", formatSpeed(0))
        assertEquals("2.0 KB/s", formatSpeed(2048))
    }

    @Test
    fun formatEta_formatsClock() {
        assertEquals("--", formatEta(null))
        assertEquals("--", formatEta(0))
        assertEquals("02:05", formatEta(125))
        assertEquals("1:02:05", formatEta(3725))
    }

    @Test
    fun formatPercent_unknownTotal() {
        assertEquals("--", formatPercent(0.5f, null))
        assertEquals("50%", formatPercent(0.5f, 100))
    }

    @Test
    fun statusLabels_haveStringResources() {
        assertEquals(R.string.state_downloading, taskStatusRes(TaskState.Downloading))
        assertEquals(R.string.state_completed, taskStatusRes(TaskState.Completed))
    }
}
