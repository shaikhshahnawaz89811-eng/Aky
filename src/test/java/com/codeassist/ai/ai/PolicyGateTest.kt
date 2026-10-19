package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyGateTest {
    private fun task(tool: String, vararg args: Pair<String, String>) = PlanTask("t1", tool, mapOf(*args))

    @Test fun readOnlyRunsAtOnce() {
        assertTrue(PolicyGate.check(task("battery_level")) === PolicyGate.Decision.Run)
        assertTrue(PolicyGate.check(task("app_open", "name" to "WhatsApp")) === PolicyGate.Decision.Run)
    }

    @Test fun reversibleRunsWithUndo() {
        assertTrue(PolicyGate.check(task("torch_set", "on" to "true")) === PolicyGate.Decision.RunWithUndo)
        assertTrue(PolicyGate.check(task("alarm_set", "hour" to "8", "minute" to "0")) === PolicyGate.Decision.RunWithUndo)
    }

    @Test fun dialNeedsAConfirmWithTheNumberRead() {
        val d = PolicyGate.check(task("call_dial", "number" to "+91 98765 43210"))
        assertTrue(d is PolicyGate.Decision.Confirm)
        assertTrue((d as PolicyGate.Decision.Confirm).readback.contains("+919876543210"))
    }

    @Test fun unknownToolIsDenied() {
        assertTrue(PolicyGate.check(task("send_money")) is PolicyGate.Decision.Deny)
    }

    @Test fun onlyT2MayRunAfterATap() {
        assertTrue(PolicyGate.canRunAfterConfirm("call_dial"))
        assertFalse(PolicyGate.canRunAfterConfirm("alarm_set"))
        assertFalse(PolicyGate.canRunAfterConfirm("nope"))
    }

    @Test fun pendingConfirmExpires() {
        assertFalse(PolicyGate.pendingExpired(1_000L, 1_000L + 9 * 60 * 1000L))
        assertTrue(PolicyGate.pendingExpired(1_000L, 1_000L + 11 * 60 * 1000L))
        assertEquals(10 * 60 * 1000L, PolicyGate.PENDING_TTL_MS)
    }
}
