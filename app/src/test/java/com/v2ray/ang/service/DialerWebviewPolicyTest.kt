package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialerWebviewPolicyTest {
    @Test
    fun onlyLoopbackDialerAddressesAreAccepted() {
        assertNotNull(DialerWebviewPolicy.localDialerUrl("127.0.0.1:12345"))
        assertNull(DialerWebviewPolicy.localDialerUrl("example.com:12345"))
        assertNull(DialerWebviewPolicy.localDialerUrl("127.0.0.1:0"))
        assertNull(DialerWebviewPolicy.localDialerUrl("127.0.0.1:12345/path"))
    }

    @Test
    fun webviewRequestsMustStayOnExactLoopbackOrigin() {
        val expected = "http://127.0.0.1:12345/"
        assertTrue(DialerWebviewPolicy.isAllowed(expected, "http://127.0.0.1:12345/assets/app.js"))
        assertFalse(DialerWebviewPolicy.isAllowed(expected, "https://127.0.0.1:12345/"))
        assertFalse(DialerWebviewPolicy.isAllowed(expected, "http://127.0.0.1:12346/"))
        assertFalse(DialerWebviewPolicy.isAllowed(expected, "http://evil.example/"))
        assertFalse(DialerWebviewPolicy.isAllowed(expected, "http://user@127.0.0.1:12345/"))
    }
}
