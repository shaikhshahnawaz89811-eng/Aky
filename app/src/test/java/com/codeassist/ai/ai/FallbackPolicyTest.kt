package com.codeassist.ai.ai

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackPolicyTest {
    @Test fun offlineNetworkFailuresUseL2() {
        assertEquals(FallbackPolicy.Level.L2_OFFLINE, FallbackPolicy.forNetworkError(UnknownHostException()))
        assertEquals(FallbackPolicy.Level.L2_OFFLINE, FallbackPolicy.forNetworkError(NoRouteToHostException()))
    }

    @Test fun transientNetworkFailuresUseL1() {
        assertEquals(FallbackPolicy.Level.L1_DEGRADED, FallbackPolicy.forNetworkError(SocketTimeoutException()))
        assertEquals(FallbackPolicy.Level.L1_DEGRADED, FallbackPolicy.forNetworkError(IOException()))
    }

    @Test fun onlyTransientHttpStatusesFallback() {
        assertEquals(FallbackPolicy.Level.L1_DEGRADED, FallbackPolicy.forHttpStatus(408))
        assertEquals(FallbackPolicy.Level.L1_DEGRADED, FallbackPolicy.forHttpStatus(429))
        assertEquals(FallbackPolicy.Level.L1_DEGRADED, FallbackPolicy.forHttpStatus(503))
        assertNull(FallbackPolicy.forHttpStatus(400))
        assertNull(FallbackPolicy.forHttpStatus(403))
    }

    @Test fun safetyAndUnknownErrorsDoNotFallback() {
        assertNull(FallbackPolicy.forNetworkError(IllegalArgumentException()))
        assertNull(FallbackPolicy.forHttpStatus(0))
    }
}
