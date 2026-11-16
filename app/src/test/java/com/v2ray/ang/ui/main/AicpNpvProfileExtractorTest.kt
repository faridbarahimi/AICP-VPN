package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AicpNpvProfileExtractorTest {
    @Test
    fun `imports only schemes supported by AICP and removes duplicates`() {
        val text = """
            // creator metadata
            vless://uuid@example.com:443?security=tls#one
            ssh://user@example.com:22
            vmess://eyJhZGQiOiJleGFtcGxlLmNvbSJ9
            vless://uuid@example.com:443?security=tls#one
            ss://YWVzLTI1Ni1nY206cGFzcw@example.com:443#ss
        """.trimIndent()

        assertEquals(
            listOf(
                "vless://uuid@example.com:443?security=tls#one",
                "vmess://eyJhZGQiOiJleGFtcGxlLmNvbSJ9",
                "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:443#ss"
            ),
            AicpNpvProfileExtractor.extractImportableLinks(text)
        )
    }

    @Test
    fun `rejects whitespace and oversized lines`() {
        val text = "vless://valid@example.com:443\nvless://invalid example.com:443\n" +
            "trojan://" + "x".repeat(64 * 1024)
        assertEquals(listOf("vless://valid@example.com:443"), AicpNpvProfileExtractor.extractImportableLinks(text))
    }
}
