package com.github.mtdhllf.tug.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRulesTest {

    @Test
    fun offline_alwaysBlocksDownloads() {
        assertFalse(NetworkRules.allowed(online = false, wifi = true, wifiOnly = false))
        assertFalse(NetworkRules.allowed(online = false, wifi = true, wifiOnly = true))
    }

    @Test
    fun wifiOnly_blocksCellular() {
        assertFalse(NetworkRules.allowed(online = true, wifi = false, wifiOnly = true))
    }

    @Test
    fun wifiOnly_allowsWifi() {
        assertTrue(NetworkRules.allowed(online = true, wifi = true, wifiOnly = true))
    }

    @Test
    fun wifiOnlyDisabled_allowsCellular() {
        assertTrue(NetworkRules.allowed(online = true, wifi = false, wifiOnly = false))
    }
}
