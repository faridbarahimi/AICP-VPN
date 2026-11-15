package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkCallbackStateTest {
    @Test
    fun staleLossCannotClearNewerUpstream() {
        val state = NetworkCallbackState<String>()
        assertNull(state.onAvailable("wifi-A"))
        assertTrue(state.onAvailable("cell-B") == "wifi-A")
        assertFalse(state.onLost("wifi-A"))
        assertTrue(state.isCurrent("cell-B"))
        assertTrue(state.onLost("cell-B"))
        assertFalse(state.isCurrent("cell-B"))
    }

    @Test
    fun clearingStateMakesLateCapabilitiesAndLossInert() {
        val state = NetworkCallbackState<String>()
        state.onAvailable("wifi-A")
        state.clear()
        assertFalse(state.isCurrent("wifi-A"))
        assertFalse(state.onLost("wifi-A"))
    }
}
