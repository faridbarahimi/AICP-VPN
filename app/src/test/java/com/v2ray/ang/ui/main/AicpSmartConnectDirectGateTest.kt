package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpSmartConnectDirectGateTest {
    @Test fun directOutageBlocksSwitchingEvenForSlowTunnel() {
        assertFalse(AicpSmartConnectPolicy.mayFailOverWithDirect(false))
    }

    @Test fun onlyAConfirmedDirectProbeAllowsTheExistingDecision() {
        assertTrue(AicpSmartConnectPolicy.mayFailOverWithDirect(true))
        assertFalse(AicpSmartConnectPolicy.mayFailOverWithDirect(null))
        assertFalse(AicpSmartConnectPolicy.mayFailOverWithDirect(false))
    }
}
