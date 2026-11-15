package com.v2ray.ang.core

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.v2ray.ang.enums.PermissionType

class LanProxyExposurePolicyTest {
    @Test
    fun requiresExplicitOptInCredentialsAndPermission() {
        assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, PermissionType.ACCESS_LOCAL_NETWORK.getPermission())
        assertTrue(LanProxyExposurePolicy.hasCredentials("user", "pass"))
        assertFalse(LanProxyExposurePolicy.hasCredentials(null, "pass"))
        assertFalse(LanProxyExposurePolicy.hasCredentials("user", "   "))
        assertFalse(LanProxyExposurePolicy.shouldExpose(false, "user", "pass", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "", "pass", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "user", "   ", true))
        assertFalse(LanProxyExposurePolicy.shouldExpose(true, "user", "pass", false))
        assertTrue(LanProxyExposurePolicy.shouldExpose(true, "user", "pass", true))
    }
}
