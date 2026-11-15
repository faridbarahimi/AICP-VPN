package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpUtil {

    /**
     * Converts the domain part of a URL string to its IDN (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like "https://例子.中国/path" will be converted to "https://xn--fsqu00a.xn--fiqs8s/path".
     *
     * @param str The URL string to convert (can contain non-ASCII characters in the domain).
     * @return The URL string with the domain part converted to ASCII-compatible (Punycode) format.
     */
    fun toIdnUrl(str: String): String {
        val url = URL(str)
        val host = url.host
        val asciiHost = IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED)
        if (host != asciiHost) {
            return str.replace(host, asciiHost)
        } else {
            return str
        }
    }

    /**
     * Converts a Unicode domain name to its IDN (Punycode, ASCII Compatible Encoding) format.
     * If the input is an IP address or already an ASCII domain, returns the original string.
     *
     * @param domain The domain string to convert (can include non-ASCII internationalized characters).
     * @return The domain in ASCII-compatible (Punycode) format, or the original string if input is an IP or already ASCII.
     */
    fun toIdnDomain(domain: String): String {
        // Return as is if it's a pure IP address (IPv4 or IPv6)
        if (Utils.isPureIpAddress(domain)) {
            return domain
        }

        // Return as is if already ASCII (English domain or already punycode)
        if (domain.all { it.code < 128 }) {
            return domain
        }

        // Otherwise, convert to ASCII using IDN
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    /**
     * Resolves a hostname to an IP address, returns original input if it's already an IP
     *
     * @param host The hostname or IP address to resolve
     * @param ipv6Preferred Whether to prefer IPv6 addresses, defaults to false
     * @return The resolved IP address or the original input (if it's already an IP or resolution fails)
     */
    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        try {
            // If it's already an IP address, return it as a list
            if (Utils.isPureIpAddress(host)) {
                return null
            }

            // Get all IP addresses
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) {
                return null
            }

            // Sort addresses based on preference
            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedWith(compareByDescending { it is Inet6Address })
            } else {
                addresses.sortedWith(compareBy { it is Inet6Address })
            }

            val ipList = sortedAddresses.mapNotNull { it.hostAddress }

            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")

            return ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            return null
        }
    }


    /**
     * Retrieves the content of a URL as a string.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     */
    fun getUrlContent(request: UrlContentRequest): String? = try {
        getUrlContentInternal(request, includeCustomHeaders = false)
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get URL content (${e.javaClass.simpleName})")
        null
    }

    /**
     * Retrieves bounded subscription/config content. Caller-supplied headers and URL credentials
     * are forwarded only while the redirect remains on the original scheme/host/port.
     */
    @Throws(IOException::class)
    fun getUrlContentWithUserAgent(request: UrlContentRequest): String =
        getUrlContentInternal(request, includeCustomHeaders = true)

    private fun getUrlContentInternal(
        request: UrlContentRequest,
        includeCustomHeaders: Boolean,
    ): String {
        val initialUrl = request.url?.takeIf { it.isNotBlank() }
            ?: throw IOException("URL is missing")
        if (request.maxResponseBytes <= 0L) throw IOException("Invalid response size limit")
        var currentUrl = initialUrl
        val client = buildOkHttpClient(
            request.timeout,
            request.httpPort,
            request.proxyUsername,
            request.proxyPassword,
            followRedirects = false,
        )
        val userAgent = request.userAgent?.takeIf { it.isNotBlank() }
            ?: "v2rayNG/${BuildConfig.VERSION_NAME}"
        val suppliedHeaders = if (includeCustomHeaders) {
            JsonUtil.parseHeadersToMap(request.requestHeaders)
        } else {
            emptyMap()
        }

        for (redirectCount in 0..MAX_REDIRECTS) {
            val sameOrigin = sameOriginForSensitiveHeaders(initialUrl, currentUrl)
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-Agent", userAgent)
                .header("Connection", "close")

            if (includeCustomHeaders && sameOrigin) {
                // Use the original URL's user-info only while still on its exact origin.
                applyEmbeddedBasicAuthHeader(initialUrl, requestBuilder)
            }

            val forwardedHeaders = filterRequestHeadersForRedirect(suppliedHeaders, sameOrigin)
            for ((key, value) in forwardedHeaders) {
                // Never log values: subscription headers frequently contain API keys or tokens.
                LogUtil.d(AppConfig.TAG, "Adding request header: $key")
                try {
                    requestBuilder.header(key, value)
                } catch (_: IllegalArgumentException) {
                    // Ignore malformed optional headers; never weaken the origin boundary.
                }
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isRedirect) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Too many redirects")
                    val location = response.header("Location")
                        ?: throw IOException("Redirect location not found")
                    val nextUrl = resolveLocation(currentUrl, location)
                        ?: throw IOException("Failed to resolve redirect location")
                    if (isHttpsDowngrade(currentUrl, nextUrl)) {
                        throw IOException("HTTPS downgrade redirect rejected")
                    }
                    currentUrl = nextUrl
                } else if (response.isSuccessful) {
                    val body = response.body ?: return ""
                    return readBoundedBody(body, request.maxResponseBytes)
                } else {
                    throw IOException("Request failed with status code ${response.code}")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    internal fun sameOriginForSensitiveHeaders(originalUrl: String, nextUrl: String): Boolean {
        return try {
            val original = URL(originalUrl)
            val next = URL(nextUrl)
            original.protocol.equals(next.protocol, ignoreCase = true) &&
                original.host.equals(next.host, ignoreCase = true) &&
                effectivePort(original) == effectivePort(next)
        } catch (_: Exception) {
            false
        }
    }

    internal fun filterRequestHeadersForRedirect(
        headers: Map<String, String>,
        sameOrigin: Boolean,
    ): Map<String, String> {
        val originHeaders = headers.filterKeys { !it.equals("Proxy-Authorization", ignoreCase = true) }
        if (sameOrigin) return originHeaders
        val safeHeaderNames = setOf(
            "accept", "accept-encoding", "accept-language", "cache-control",
            "if-modified-since", "if-none-match", "user-agent",
        )
        return originHeaders.filterKeys { it.lowercase(Locale.ROOT) in safeHeaderNames }
    }

    internal fun isHttpsDowngrade(fromUrl: String, toUrl: String): Boolean {
        return try {
            URL(fromUrl).protocol.equals("https", ignoreCase = true) &&
                URL(toUrl).protocol.equals("http", ignoreCase = true)
        } catch (_: Exception) {
            true
        }
    }

    private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort

    private fun applyEmbeddedBasicAuthHeader(rawUrl: String, requestBuilder: Request.Builder) {
        val parsed = runCatching { URL(rawUrl) }.getOrNull() ?: return
        parsed.userInfo?.let { userInfo ->
            val colon = userInfo.indexOf(':')
            val user = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            }.getOrDefault(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            val pass = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(colon + 1) else "")
            }.getOrDefault(if (colon >= 0) userInfo.substring(colon + 1) else "")
            requestBuilder.header("Authorization", Credentials.basic(user, pass))
        }
    }

    private fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(false)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                            .build()
                    }
                }
            }
        }
        return builder.build()
    }

    private fun resolveLocation(baseUrl: String, raw: String): String? {
        return try {
            val location = URI(raw)
            val base = URI(baseUrl)
            val resolved = if (location.isAbsolute) location else base.resolve(location)
            resolved.toURL().toString()
        } catch (_: Exception) {
            try {
                URL(URL(baseUrl), raw).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
        if (maxBytes <= 0L) throw IOException("Invalid response size limit")
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
        copyBounded(input, output, maxBytes)
        return output.toByteArray()
    }

    internal fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        if (maxBytes <= 0L) throw IOException("Invalid response size limit")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read.toLong() > maxBytes - total) {
                throw IOException("Response exceeds configured size limit")
            }
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody, maxBytes: Long): String {
        if (body.contentLength() > maxBytes) throw IOException("Response exceeds configured size limit")
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val bytes = body.byteStream().use { readBoundedBytes(it, maxBytes) }
        return String(bytes, charset)
    }

    /** Downloads through a same-directory temporary file and atomically replaces the target. */
    fun downloadToFile(request: UrlContentRequest, targetFile: File): Boolean {
        val url = request.url?.takeIf { it.isNotBlank() } ?: return false
        if (request.maxResponseBytes <= 0L) return false
        val parent = targetFile.absoluteFile.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val tempFile = runCatching { File.createTempFile("vpn-download-", ".part", parent) }.getOrNull()
            ?: return false
        val client = buildOkHttpClient(
            request.timeout,
            request.httpPort,
            request.proxyUsername,
            request.proxyPassword,
            followRedirects = true,
        )
        return try {
            val requestBuilder = Request.Builder().url(url).get().header("Connection", "close")
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "File download failed, HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                if (body.contentLength() > request.maxResponseBytes) {
                    throw IOException("Download exceeds configured size limit")
                }
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        copyBounded(input, output, request.maxResponseBytes)
                        output.fd.sync()
                    }
                }
            }
            try {
                Files.move(
                    tempFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "File download failed (${e.javaClass.simpleName})")
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private const val MAX_REDIRECTS = 3

}
