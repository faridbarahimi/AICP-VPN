package com.v2ray.ang.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UrlSchemeImportPolicyTest {
    @Test
    fun rejectsEmptyAndOversizedPayloads() {
        assertFalse(UrlSchemeImportPolicy.isAcceptable(null))
        assertFalse(UrlSchemeImportPolicy.isAcceptable("  "))
        assertTrue(UrlSchemeImportPolicy.isAcceptable("vless://sample"))
        assertTrue(UrlSchemeImportPolicy.isAcceptable("x".repeat(UrlSchemeImportPolicy.MAX_PAYLOAD_CHARS)))
        assertFalse(UrlSchemeImportPolicy.isAcceptable("x".repeat(UrlSchemeImportPolicy.MAX_PAYLOAD_CHARS + 1)))
    }

    @Test
    fun preservesExistingFragmentAndAppendsOuterFragmentOnlyWhenMissing() {
        assertEquals("https://example.test/sub#secret", UrlSchemeImportPolicy.appendFragment("https://example.test/sub", "secret"))
        assertEquals("https://example.test/sub#inside", UrlSchemeImportPolicy.appendFragment("https://example.test/sub#inside", "outer"))
        assertEquals("https://example.test/sub", UrlSchemeImportPolicy.appendFragment("https://example.test/sub", null))
    }
}
