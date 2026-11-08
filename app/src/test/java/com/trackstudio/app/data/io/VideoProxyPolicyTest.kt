package com.trackstudio.app.data.io

import com.trackstudio.app.data.model.ProxyResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoProxyPolicyTest {
    @Test
    fun short1080pVideoUsesHalfResolution() {
        assertEquals(
            ProxyResolution.HALF,
            VideoProxyPolicy.recommendedResolution(
                width = 1920, height = 1080, durationUs = 30_000_000L,
                frameCount = 900, sizeBytes = 120_000_000L
            )
        )
    }

    @Test
    fun long4kVideoUsesQuarterResolution() {
        assertEquals(
            ProxyResolution.QUARTER,
            VideoProxyPolicy.recommendedResolution(
                width = 3840, height = 2160, durationUs = 90_000_000L,
                frameCount = 2_700, sizeBytes = 500_000_000L
            )
        )
    }

    @Test
    fun veryLong1080pVideoUsesQuarterResolution() {
        assertEquals(
            ProxyResolution.QUARTER,
            VideoProxyPolicy.recommendedResolution(
                width = 1920, height = 1080, durationUs = 360_000_000L,
                frameCount = 8_640, sizeBytes = 700_000_000L
            )
        )
    }
}
