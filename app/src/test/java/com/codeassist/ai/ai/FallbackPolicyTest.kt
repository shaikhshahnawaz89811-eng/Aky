package com.codeassist.ai.ai

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---------- T2: heavy plan questions ----------

    @Test fun heavyPlanRequestsAreRecognised() {
        val heavy = listOf(
            "mere app ka poora plan banao", "Android assistant app ka phase wise plan likho",
            "write a detailed project plan for my app", "ek prompt likho jo mere AI app ka poora kaam samjhaye",
            "iss project ka complete roadmap batao",
            // T3: the real message of the 13 Oct test chat ("do" / "de" are "give it" words now)
            "Ek prompt do, poora plan, dusra AI coding kar de"
        )
        for (s in heavy) assertTrue(s, FallbackPolicy.isHeavyPlan(s))
    }

    @Test fun ordinaryMessagesAreNotHeavyPlans() {
        val light = listOf(
            "kal subah 6 baje alarm laga do", "plan banao", "mera plan kya hai", "REST API kya hai", "plans clear",
            "prompt kya hota hai batao", "ek chhota sa plan likh do", "mujhe kal ke din ka plan batao"
        )
        for (s in light) assertFalse(s, FallbackPolicy.isHeavyPlan(s))
    }

    @Test fun onlyThePhoneBrainWithAKeyAndTheSwitchEscalates() {
        val q = "mere app ka poora plan banao"
        assertTrue(FallbackPolicy.escalateHeavyPlan(q, "local", true, true))
        assertFalse(FallbackPolicy.escalateHeavyPlan(q, "gemini", true, true))
        assertFalse(FallbackPolicy.escalateHeavyPlan(q, "local", false, true))
        assertFalse(FallbackPolicy.escalateHeavyPlan(q, "local", true, false))
        assertFalse(FallbackPolicy.escalateHeavyPlan("kaise ho", "local", true, true))
    }
}
