package com.codeassist.ai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyGatePhase3Test {
    @Test fun t0AndT1MayRunWithoutATap() {
        assertTrue(PolicyGate.mayExecute("time_now", false))
        assertTrue(PolicyGate.mayExecute("torch_set", false))
        assertTrue(PolicyGate.mayExecute("alarm_set", false))
    }

    @Test fun t2NeedsTheTap() {
        assertFalse(PolicyGate.mayExecute("call_dial", false))
        assertTrue(PolicyGate.mayExecute("call_dial", true))
    }

    @Test fun unknownToolNeverRuns() {
        assertFalse(PolicyGate.mayExecute("send_money", true))
        assertFalse(PolicyGate.mayExecute("", true))
    }
}
