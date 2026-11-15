package com.v2ray.ang

import com.v2ray.ang.util.HttpUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpUtilTest {

    @Test
    fun testIdnToASCII() {
        // Regular URL remains unchanged
        val regularUrl = "https://example.com/path"
        assertEquals(regularUrl, HttpUtil.toIdnUrl(regularUrl))

        // Non-ASCII URL converts to ASCII (Punycode)
        val nonAsciiUrl = "https://例子.测试/path"
        val expectedNonAscii = "https://xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedNonAscii, HttpUtil.toIdnUrl(nonAsciiUrl))

        // Mixed URL only converts the host part
        val mixedUrl = "https://例子.com/测试"
        val expectedMixed = "https://xn--fsqu00a.com/测试"
        assertEquals(expectedMixed, HttpUtil.toIdnUrl(mixedUrl))

        // URL with Basic Authentication using regular domain
        val basicAuthUrl = "https://user:password@example.com/path"
        assertEquals(basicAuthUrl, HttpUtil.toIdnUrl(basicAuthUrl))

        // URL with Basic Authentication using non-ASCII domain
        val basicAuthNonAscii = "https://user:password@例子.测试/path"
        val expectedBasicAuthNonAscii = "https://user:password@xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedBasicAuthNonAscii, HttpUtil.toIdnUrl(basicAuthNonAscii))

        // URL with non-ASCII username and password
        val nonAsciiAuth = "https://用户:密码@example.com/path"
        // Basic auth credentials should remain unchanged as they're percent-encoded separately
        assertEquals(nonAsciiAuth, HttpUtil.toIdnUrl(nonAsciiAuth))
    }

    @Test
    fun sensitiveHeadersStayOnExactOriginOnly() {
        assertTrue(HttpUtil.sameOriginForSensitiveHeaders(
            "https://user:secret@example.com:443/sub",
            "https://example.com/next"
        ))
        assertFalse(HttpUtil.sameOriginForSensitiveHeaders(
            "https://user:secret@example.com/sub",
            "https://cdn.example.net/next"
        ))
        assertFalse(HttpUtil.sameOriginForSensitiveHeaders(
            "https://user:secret@example.com/sub",
            "http://example.com/next"
        ))
        assertFalse(HttpUtil.sameOriginForSensitiveHeaders(
            "https://user:secret@example.com/sub",
            "https://example.com:8443/next"
        ))
    }

    @Test
    fun crossOriginRedirectDropsCallerCredentialsAndCustomHeaders() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "X-Api-Key" to "secret-key",
            "Cookie" to "session=secret",
            "Proxy-Authorization" to "Basic proxy-secret",
            "Accept" to "text/plain",
            "If-None-Match" to "etag-1"
        )
        assertEquals(
            mapOf("Accept" to "text/plain", "If-None-Match" to "etag-1"),
            HttpUtil.filterRequestHeadersForRedirect(headers, sameOrigin = false)
        )
        assertEquals(
            headers.filterKeys { !it.equals("Proxy-Authorization", ignoreCase = true) },
            HttpUtil.filterRequestHeadersForRedirect(headers, sameOrigin = true),
        )
    }

    @Test
    fun httpsRedirectCannotDowngradeToHttp() {
        assertTrue(HttpUtil.isHttpsDowngrade("https://example.com/a", "http://example.com/b"))
        assertFalse(HttpUtil.isHttpsDowngrade("https://example.com/a", "https://cdn.example.com/b"))
        assertFalse(HttpUtil.isHttpsDowngrade("http://example.com/a", "http://example.com/b"))
    }

    @Test
    fun boundedResponseReaderAcceptsLimitAndRejectsOverflow() {
        assertEquals(
            "12345",
            String(HttpUtil.readBoundedBytes(ByteArrayInputStream("12345".toByteArray()), 5))
        )
        assertThrows(IOException::class.java) {
            HttpUtil.readBoundedBytes(ByteArrayInputStream("123456".toByteArray()), 5)
        }
    }


}