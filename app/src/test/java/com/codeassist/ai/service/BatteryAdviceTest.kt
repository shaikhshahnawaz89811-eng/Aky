package com.codeassist.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAdviceTest {
    @Test fun makerNamesMapToKeys() {
        assertEquals(BatteryAdvice.XIAOMI, BatteryAdvice.oemKey("Xiaomi"))
        assertEquals(BatteryAdvice.XIAOMI, BatteryAdvice.oemKey("POCO"))
        assertEquals(BatteryAdvice.OPPO, BatteryAdvice.oemKey("realme"))
        assertEquals(BatteryAdvice.OPPO, BatteryAdvice.oemKey("OPPO"))
        assertEquals(BatteryAdvice.VIVO, BatteryAdvice.oemKey("vivo"))
        assertEquals(BatteryAdvice.VIVO, BatteryAdvice.oemKey("iQOO"))
        assertEquals(BatteryAdvice.SAMSUNG, BatteryAdvice.oemKey("samsung"))
        assertEquals(BatteryAdvice.ONEPLUS, BatteryAdvice.oemKey("OnePlus"))
        assertEquals(BatteryAdvice.HUAWEI, BatteryAdvice.oemKey("HONOR"))
    }

    @Test fun unknownOrMissingMakerIsOther() {
        assertEquals(BatteryAdvice.OTHER, BatteryAdvice.oemKey("Google"))
        assertEquals(BatteryAdvice.OTHER, BatteryAdvice.oemKey(""))
        assertEquals(BatteryAdvice.OTHER, BatteryAdvice.oemKey(null))
    }

    @Test fun everyKeyHasStepsAndLabel() {
        val keys = listOf(
            BatteryAdvice.XIAOMI, BatteryAdvice.OPPO, BatteryAdvice.VIVO, BatteryAdvice.SAMSUNG,
            BatteryAdvice.ONEPLUS, BatteryAdvice.HUAWEI, BatteryAdvice.OTHER
        )
        for (k in keys) {
            assertTrue(k, BatteryAdvice.steps(k).size >= 2)
            assertTrue(k, BatteryAdvice.label(k).isNotBlank())
        }
    }
}
