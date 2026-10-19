package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointingTest {
    @Test fun trailingPostpositionIsIncomplete() {
        assertTrue(Endpointing.isIncomplete("kal subah 8 baje ka"))
        assertTrue(Endpointing.isIncomplete("mujhe batao ki"))
        assertTrue(Endpointing.isIncomplete("कल सुबह आठ बजे का और"))
    }

    @Test fun finishedCommandsAreComplete() {
        assertFalse(Endpointing.isIncomplete("kal subah 8 baje ka alarm laga do"))
        assertFalse(Endpointing.isIncomplete("battery kitni hai"))
        assertFalse(Endpointing.isIncomplete("time kya hua"))
        assertFalse(Endpointing.isIncomplete("timer 5 minute"))
    }

    @Test fun singleWordIsNeverExtended() {
        assertFalse(Endpointing.isIncomplete("torch"))
        assertFalse(Endpointing.isIncomplete("aur"))
    }

    @Test fun halfSaidTimeGetsLongerWait() {
        assertEquals(Endpointing.EXTEND_NUMBER_MS, Endpointing.extraWaitMs("alarm laga do kal subah 8"))
        assertEquals(Endpointing.EXTEND_NUMBER_MS, Endpointing.extraWaitMs("call 98765"))
    }

    @Test fun fullPhoneNumberIsComplete() {
        assertEquals(0L, Endpointing.extraWaitMs("call 9876543210"))
    }

    @Test fun verbDoIsNotTheNumberTwo() {
        assertEquals(0L, Endpointing.extraWaitMs("alarm laga do"))
    }

    @Test fun onAtTheEndIsAFinishedCommand() {
        // Tier-0 accepts "torch on" / "flashlight on": the pause guard must not add 1.3 s to the fast path
        assertEquals(0L, Endpointing.extraWaitMs("torch on"))
        assertEquals(0L, Endpointing.extraWaitMs("flashlight on"))
        assertEquals(0L, Endpointing.extraWaitMs("torch off"))
    }

    @Test fun otherEnglishConnectorsStillCount() {
        assertTrue(Endpointing.isIncomplete("set an alarm for"))
        assertTrue(Endpointing.isIncomplete("call mummy and"))
    }
}
