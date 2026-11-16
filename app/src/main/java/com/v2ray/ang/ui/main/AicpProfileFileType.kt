package com.v2ray.ang.ui.main

/** File formats accepted by the local profile import picker. */
internal enum class AicpProfileFileType {
    OPENVPN, NPVT, NPVS, TEXT;

    companion object {
        fun detect(displayName: String?): AicpProfileFileType = when {
            displayName.orEmpty().trim().lowercase().endsWith(".ovpn") -> OPENVPN
            displayName.orEmpty().trim().lowercase().endsWith(".npvt") -> NPVT
            displayName.orEmpty().trim().lowercase().endsWith(".npvs") -> NPVS
            else -> TEXT
        }
    }
}
