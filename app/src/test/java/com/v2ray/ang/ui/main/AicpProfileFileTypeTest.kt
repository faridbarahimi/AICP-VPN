package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AicpProfileFileTypeTest {
    @Test fun detectsOpenVpnProfilesCaseInsensitively() {
        assertEquals(AicpProfileFileType.OPENVPN, AicpProfileFileType.detect("home.ovpn"))
        assertEquals(AicpProfileFileType.OPENVPN, AicpProfileFileType.detect("HOME.OVPN"))
    }

    @Test fun detectsNapsternetVFormatsSeparately() {
        assertEquals(AicpProfileFileType.NPVT, AicpProfileFileType.detect("node.npvt"))
        assertEquals(AicpProfileFileType.NPVS, AicpProfileFileType.detect("node.npvs"))
    }

    @Test fun unknownAndMissingNamesRemainTextImports() {
        assertEquals(AicpProfileFileType.TEXT, AicpProfileFileType.detect("subscription.txt"))
        assertEquals(AicpProfileFileType.TEXT, AicpProfileFileType.detect(null))
    }
}
