package com.v2ray.ang.core

/** Fail-closed policy for exposing the local SOCKS/HTTP proxy to the LAN. */
internal object LanProxyExposurePolicy {
    fun shouldExpose(
        requested: Boolean,
        username: String?,
        password: String?,
        localNetworkPermissionGranted: Boolean,
    ): Boolean = requested && !username.isNullOrBlank() && !password.isNullOrBlank() && localNetworkPermissionGranted
}
