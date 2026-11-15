package com.v2ray.ang.dto.entities

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val testDelayMillis: Long = 0L,
    val downloadBitsPerSecond: Long = 0L,
    val uploadBitsPerSecond: Long = 0L,
)
