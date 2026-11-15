package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelayTestUrlPolicyTest {
    private val bodyProbe = "https://www.google.com/robots.txt"

    @Test fun blankPreferenceUsesBodyProducingDefault() {
        assertEquals(bodyProbe, DelayTestUrlPolicy.resolve("  ", bodyProbe))
        assertEquals(bodyProbe, DelayTestUrlPolicy.resolve(null, bodyProbe))
    }

    @Test fun legacyGoogle204PreferenceMigratesToBodyProducingDefault() {
        assertEquals(bodyProbe, DelayTestUrlPolicy.resolve("https://www.google.com/generate_204", bodyProbe))
    }

    @Test fun legacyGstatic204PreferenceMigratesToBodyProducingDefault() {
        assertEquals(bodyProbe, DelayTestUrlPolicy.resolve("https://www.gstatic.com/generate_204", bodyProbe))
    }

    @Test fun customProbePreferenceIsPreserved() {
        assertEquals("https://example.org/probe.txt", DelayTestUrlPolicy.resolve(" https://example.org/probe.txt ", bodyProbe))
    }

    @Test fun newDefaultPreferenceIsPreserved() {
        assertEquals(bodyProbe, DelayTestUrlPolicy.resolve(bodyProbe, bodyProbe))
    }
}
