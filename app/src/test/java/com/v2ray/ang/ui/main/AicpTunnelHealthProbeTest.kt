package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpTunnelHealthProbeTest {
    @Test
    fun acceptsNoContentAndBodyProducingSuccessfulResponses() {
        assertTrue(AicpTunnelHealthProbe.isHealthyResponse(204, null, false))
        assertTrue(AicpTunnelHealthProbe.isHealthyResponse(200, "text/plain", true))
        assertTrue(AicpTunnelHealthProbe.isHealthyResponse(299, "application/octet-stream", true))
    }

    @Test
    fun choosesTheFastestSuccessfulProbeEndpoint() {
        assertTrue(AicpTunnelHealthProbe.selectProbeLatency(200L, 250L) == 200L)
        assertTrue(AicpTunnelHealthProbe.selectProbeLatency(500L, 200L) == 200L)
        assertTrue(AicpTunnelHealthProbe.selectProbeLatency(-1L, 250L) == 250L)
        assertTrue(AicpTunnelHealthProbe.selectProbeLatency(500L, 600L) == 500L)
        assertTrue(AicpTunnelHealthProbe.selectProbeLatency(-1L, -1L) == -1L)
    }

    @Test
    fun rejectsRedirectsErrorsEmptyOkResponsesAndCaptivePortalHtml() {
        assertFalse(AicpTunnelHealthProbe.isHealthyResponse(199, "text/plain", true))
        assertFalse(AicpTunnelHealthProbe.isHealthyResponse(300, "text/plain", true))
        assertFalse(AicpTunnelHealthProbe.isHealthyResponse(503, "text/plain", true))
        assertFalse(AicpTunnelHealthProbe.isHealthyResponse(200, "text/plain", false))
        assertFalse(AicpTunnelHealthProbe.isHealthyResponse(200, "text/html; charset=utf-8", true))
    }
}
