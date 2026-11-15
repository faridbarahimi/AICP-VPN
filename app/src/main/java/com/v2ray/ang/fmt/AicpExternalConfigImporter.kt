package com.v2ray.ang.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Converts the supported subset of sing-box JSON and Clash YAML into the share-link formats
 * already understood by the app. Unsupported outbound types are deliberately not guessed.
 * This is an import adapter, not a claim that Xray implements every sing-box/Clash protocol.
 */
internal object AicpExternalConfigImporter {
    fun expand(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("{") || text.startsWith("[")) {
            runCatching { fromSingBox(JsonParser.parseString(text)) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it.joinToString("\n") }
        }
        if (text.contains("proxies:") && (text.contains("yaml", ignoreCase = true) || text.contains("- name:") || text.contains("- {"))) {
            runCatching { fromClashYaml(text) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it.joinToString("\n") }
        }
        return null
    }

    private fun fromSingBox(root: JsonElement): List<String> {
        val outbounds: JsonArray = when {
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("outbounds") ?: return emptyList()
            root.isJsonArray -> root.asJsonArray
            else -> return emptyList()
        }
        return outbounds.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val o = item.asJsonObject
            val type = o.str("type").lowercase()
            val server = o.str("server")
            val port = o.int("server_port")
            val tag = o.str("tag").ifBlank { "$type-$server" }
            if (server.isBlank() || port !in 1..65535) return@mapNotNull null
            val tls = o.obj("tls")
            val tlsEnabled = tls?.bool("enabled") == true
            val tlsServerName = tls?.str("server_name").orEmpty()
            val fingerprint = tls?.obj("utls")?.str("fingerprint").orEmpty()
            val transport = o.obj("transport")
            val transportType = transport?.str("type")?.trim()?.lowercase().orEmpty()
            val supportedTransportTypes = when (type) {
                "vless", "vmess", "trojan" -> setOf("", "tcp", "ws", "http", "grpc", "httpupgrade")
                "shadowsocks", "socks", "http" -> setOf("", "tcp")
                else -> setOf("")
            }
            // Do not silently turn QUIC/KCP or protocol-specific transports into TCP.
            if (transportType !in supportedTransportTypes) return@mapNotNull null
            val path = transport?.str("path").orEmpty()
            val host = transport?.obj("headers")?.str("Host").orEmpty()
            val query = linkedMapOf<String, String>()
            if (tlsEnabled) {
                query["security"] = if (tls?.obj("reality")?.bool("enabled") == true) "reality" else "tls"
                tlsServerName.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
                fingerprint.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
                tls?.arr("alpn")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                    ?.takeIf { it.isNotEmpty() }?.let { query["alpn"] = it.joinToString(",") }
            }
            when (transportType) {
                "ws" -> { query["type"] = "ws"; path.takeIf { it.isNotBlank() }?.let { query["path"] = it }; host.takeIf { it.isNotBlank() }?.let { query["host"] = it } }
                "http" -> { query["type"] = "http"; path.takeIf { it.isNotBlank() }?.let { query["path"] = it }; host.takeIf { it.isNotBlank() }?.let { query["host"] = it } }
                "grpc" -> { query["type"] = "grpc"; transport?.str("service_name")?.takeIf { it.isNotBlank() }?.let { query["serviceName"] = it } }
                "httpupgrade" -> { query["type"] = "httpupgrade"; path.takeIf { it.isNotBlank() }?.let { query["path"] = it }; host.takeIf { it.isNotBlank() }?.let { query["host"] = it } }
            }
            when (type) {
                "vless" -> {
                    val uuid = o.str("uuid"); if (uuid.isBlank()) return@mapNotNull null
                    o.str("flow").takeIf { it.isNotBlank() }?.let { query["flow"] = it }
                    tls?.obj("reality")?.let { reality ->
                        reality.str("public_key").takeIf { it.isNotBlank() }?.let { query["pbk"] = it }
                        reality.str("short_id").takeIf { it.isNotBlank() }?.let { query["sid"] = it }
                    }
                    uri("vless", "$uuid@$server:$port", query, tag)
                }
                "vmess" -> {
                    val uuid = o.str("uuid"); if (uuid.isBlank()) return@mapNotNull null
                    val vmess = linkedMapOf<String, Any>("v" to "2", "ps" to tag, "add" to server, "port" to port.toString(), "id" to uuid, "aid" to (o.int("alter_id").coerceAtLeast(0).toString()), "net" to (transportType.ifBlank { "tcp" }), "type" to "none", "tls" to if (tlsEnabled) "tls" else "")
                    tlsServerName.takeIf { it.isNotBlank() }?.let { vmess["sni"] = it }
                    path.takeIf { it.isNotBlank() }?.let { vmess["path"] = it }
                    host.takeIf { it.isNotBlank() }?.let { vmess["host"] = it }
                    val json = com.google.gson.Gson().toJson(vmess)
                    "vmess://${java.util.Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))}"
                }
                "trojan" -> {
                    val password = o.str("password"); if (password.isBlank()) return@mapNotNull null
                    uri("trojan", "${encUser(password)}@$server:$port", query, tag)
                }
                "shadowsocks" -> {
                    val method = o.str("method"); val password = o.str("password")
                    if (method.isBlank() || password.isBlank()) return@mapNotNull null
                    val auth = java.util.Base64.getEncoder().withoutPadding().encodeToString("$method:$password".toByteArray(StandardCharsets.UTF_8))
                    uri("ss", "$auth@$server:$port", query, tag)
                }
                "socks" -> {
                    val user = o.str("username"); val pass = o.str("password")
                    if (user.isNotBlank() && pass.isNotBlank()) uri("socks", "${encUser(user)}:${encUser(pass)}@$server:$port", query, tag)
                    else uri("socks", "$server:$port", query, tag)
                }
                "http" -> {
                    val user = o.str("username"); val pass = o.str("password")
                    if (user.isNotBlank() && pass.isNotBlank()) uri("http", "${encUser(user)}:${encUser(pass)}@$server:$port", query, tag)
                    else uri("http", "$server:$port", query, tag)
                }
                "hysteria2" -> {
                    val password = o.str("password"); if (password.isBlank()) return@mapNotNull null
                    uri("hysteria2", "${encUser(password)}@$server:$port", query, tag)
                }
                "wireguard" -> {
                    val privateKey = o.str("private_key"); val peerKey = o.str("peer_public_key")
                    if (privateKey.isBlank() || peerKey.isBlank()) return@mapNotNull null
                    query["publickey"] = peerKey
                    query["address"] = o.arr("local_address")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }?.joinToString(",") ?: "10.0.0.2/32"
                    o.str("pre_shared_key").takeIf { it.isNotBlank() }?.let { query["presharedkey"] = it }
                    o.arr("reserved")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }?.takeIf { it.isNotEmpty() }?.let { query["reserved"] = it.joinToString(",") }
                    uri("wireguard", "${encUser(privateKey)}@$server:$port", query, tag)
                }
                else -> null // direct/block/dns and protocols without a verified app runtime are not imported as proxies.
            }
        }.distinct()
    }

    /** Supports the common flat `proxies:` sequence; nested plugin-specific semantics are not guessed. */
    private fun fromClashYaml(text: String): List<String> {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == "proxies:" }
        if (start < 0) return emptyList()
        val blocks = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in lines.drop(start + 1)) {
            if (line.isNotBlank() && !line.first().isWhitespace() && !line.trimStart().startsWith("-")) break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed == "proxies:") continue
            if (trimmed.startsWith("- ")) {
                current = linkedMapOf()
                blocks += current
                val first = trimmed.removePrefix("- ")
                parseScalar(first)?.let { (k, v) -> current[k] = v }
            } else if (current != null && !trimmed.startsWith("-")) {
                parseScalar(trimmed)?.let { (k, v) -> current[k] = v }
            }
        }
        return blocks.mapNotNull { p ->
            val type = p["type"].orEmpty().lowercase()
            val name = p["name"].orEmpty().ifBlank { "$type-${p["server"].orEmpty()}" }
            val server = p["server"].orEmpty()
            val port = p["port"]?.toIntOrNull() ?: return@mapNotNull null
            if (server.isBlank() || port !in 1..65535) return@mapNotNull null
            val query = linkedMapOf<String, String>()
            val network = p["network"]?.lowercase().orEmpty().ifBlank { "tcp" }
            val tls = p["tls"]?.toBooleanStrictOrNull() == true
            if (tls) {
                query["security"] = "tls"
                p["servername"]?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
                p["client-fingerprint"]?.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
                p["alpn"]?.trim('[', ']', '"', '\'')?.takeIf { it.isNotBlank() }?.let { query["alpn"] = it.replace("'", "").replace("\"", "") }
                if (p["skip-cert-verify"]?.toBooleanStrictOrNull() == true) query["allowInsecure"] = "1"
            }
            when (type) {
                "vless" -> {
                    if (network !in setOf("tcp", "ws", "grpc")) return@mapNotNull null
                    val uuid = p["uuid"].orEmpty(); if (uuid.isBlank()) return@mapNotNull null
                    p["flow"]?.takeIf { it.isNotBlank() }?.let { query["flow"] = it }
                    when (network) {
                        "ws" -> { query["type"] = "ws"; p["ws-path"]?.let { query["path"] = it }; p["ws-headers-host"]?.let { query["host"] = it } }
                        "grpc" -> { query["type"] = "grpc"; p["grpc-service-name"]?.let { query["serviceName"] = it } }
                    }
                    uri("vless", "$uuid@$server:$port", query, name)
                }
                "vmess" -> {
                    if (network !in setOf("tcp", "ws", "http", "grpc")) return@mapNotNull null
                    val uuid = p["uuid"].orEmpty(); if (uuid.isBlank()) return@mapNotNull null
                    val vmess = linkedMapOf<String, Any>("v" to "2", "ps" to name, "add" to server, "port" to port.toString(), "id" to uuid, "aid" to (p["alterId"]?.toIntOrNull() ?: 0).toString(), "net" to network, "type" to "none", "tls" to if (tls) "tls" else "")
                    p["servername"]?.let { vmess["sni"] = it }; p["ws-path"]?.let { vmess["path"] = it }; p["ws-headers-host"]?.let { vmess["host"] = it }
                    "vmess://${java.util.Base64.getEncoder().withoutPadding().encodeToString(com.google.gson.Gson().toJson(vmess).toByteArray(StandardCharsets.UTF_8))}"
                }
                "trojan" -> if (network != "tcp") null else p["password"]?.takeIf { it.isNotBlank() }?.let { uri("trojan", "${encUser(it)}@$server:$port", query, name) }
                "ss", "shadowsocks" -> {
                    if (network != "tcp") return@mapNotNull null
                    val cipher = p["cipher"].orEmpty(); val password = p["password"].orEmpty()
                    if (cipher.isBlank() || password.isBlank() || p["plugin"].isNullOrBlank().not()) return@mapNotNull null
                    val auth = java.util.Base64.getEncoder().withoutPadding().encodeToString("$cipher:$password".toByteArray(StandardCharsets.UTF_8))
                    uri("ss", "$auth@$server:$port", query, name)
                }
                "socks5" -> {
                    if (network != "tcp") return@mapNotNull null
                    val user = p["username"].orEmpty(); val pass = p["password"].orEmpty()
                    if (user.isNotBlank() && pass.isNotBlank()) uri("socks", "${encUser(user)}:${encUser(pass)}@$server:$port", query, name) else uri("socks", "$server:$port", query, name)
                }
                "http" -> {
                    if (network != "tcp") return@mapNotNull null
                    val user = p["username"].orEmpty(); val pass = p["password"].orEmpty()
                    if (user.isNotBlank() && pass.isNotBlank()) uri("http", "${encUser(user)}:${encUser(pass)}@$server:$port", query, name) else uri("http", "$server:$port", query, name)
                }
                "hysteria2", "hy2" -> if (network != "tcp") null else p["password"]?.takeIf { it.isNotBlank() }?.let { uri("hysteria2", "${encUser(it)}@$server:$port", query, name) }
                else -> null
            }
        }.distinct()
    }

    private fun parseScalar(raw: String): Pair<String, String>? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val key = raw.substring(0, colon).trim()
        var value = raw.substring(colon + 1).trim()
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length - 1)
        value = value.trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
        if (key.isBlank() || value.contains("{", true) || value.contains("}", true)) return null
        return key to value
    }

    private fun uri(scheme: String, authority: String, query: Map<String, String>, name: String): String {
        val qs = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "$scheme://$authority${if (qs.isNotEmpty()) "?$qs" else ""}#${enc(name)}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encUser(value: String): String = enc(value).replace("%3A", "%3A")
    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    private fun JsonObject.int(key: String): Int = runCatching { get(key)?.asInt ?: 0 }.getOrDefault(0)
    private fun JsonObject.bool(key: String): Boolean = runCatching { get(key)?.asBoolean ?: false }.getOrDefault(false)
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
