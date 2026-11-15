package com.v2ray.ang.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSelectionTest {
    private val installed = setOf("browser", "play", "maps", "com.google.android.gms")

    @Test
    fun `normal proxy list selects matching installed packages`() {
        assertEquals(
            setOf("browser", "play"),
            AppSelection.fromProxyList(installed, "browser\nplay", bypassApps = false, forceGoogleApps = false),
        )
    }

    @Test
    fun `bypass proxy list selects installed packages that should stay direct`() {
        assertEquals(
            setOf("maps", "com.google.android.gms"),
            AppSelection.fromProxyList(installed, "browser\nplay", bypassApps = true, forceGoogleApps = false),
        )
    }

    @Test
    fun `exported bypass selection round trips exactly and ignores uninstalled packages`() {
        val exported = AppSelection.exportSelection(true, setOf("play", "maps", "removed.app"))
        val parsed = AppSelection.parseExportedSelection(exported, installed)
        assertTrue(parsed?.bypassApps == true)
        assertEquals(setOf("play", "maps"), parsed?.selectedPackages)
    }

    @Test
    fun `exported normal selection round trips exactly`() {
        val exported = AppSelection.exportSelection(false, setOf("browser", "play"))
        val parsed = AppSelection.parseExportedSelection(exported, installed)
        assertFalse(parsed?.bypassApps ?: true)
        assertEquals(setOf("browser", "play"), parsed?.selectedPackages)
    }

    @Test
    fun `raw provider list is not mistaken for an exported snapshot`() {
        assertNull(AppSelection.parseExportedSelection("browser\nplay", installed))
    }
}
