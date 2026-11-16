package com.v2ray.ang.ui.main

/** Converts decrypted NPV text into only the URI schemes AICP's profile parser supports. */
internal object AicpNpvProfileExtractor {
    private val supportedSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "socks", "socks4", "socks5",
        "wireguard", "hy2", "hysteria2", "aether"
    )

    fun extractImportableLinks(decryptedText: String): List<String> = decryptedText
        .lineSequence()
        .map(String::trim)
        .filter { line ->
            line.isNotEmpty() && line.length <= 64 * 1024 &&
                !line.startsWith("//") && !line.any(Char::isWhitespace) &&
                line.contains("://") &&
                line.substringBefore("://").lowercase() in supportedSchemes
        }
        .distinct()
        .toList()
}
