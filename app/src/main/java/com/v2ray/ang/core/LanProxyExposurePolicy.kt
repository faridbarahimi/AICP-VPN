package com.v2ray.ang.core

/** Fail-closed policy for exposing the local SOCKS/HTTP proxy to the LAN. */
internal object LanProxyExposurePolicy {
    fun hasCredentials(username: String?, password: String?): Boolean =
        !username.isNullOrBlank() && !password.isNullOrBlank()

    fun shouldExpose(
        requested: Boolean,
        username: String?,
        password: String?,
        localNetworkPermissionGranted: Boolean,
    ): Boolean = requested && hasCredentials(username, password) && localNetworkPermissionGranted
}
