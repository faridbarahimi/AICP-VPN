package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanProxyExposurePolicyTest {
    @Test
    fun requiresExplicitOptInCredentialsAndPermission() {
        assertFalse(LanProxyExposurePolicy.shouldExpose(false, "user", "pass", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "", "pass", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "user", "   ", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "user", "pass", false))
        assertTrue(LanProxyExposurePolicy.shouldExpose(true, "user", "pass", true))
    }
}
