package com.codeassist.ai.ai

import com.codeassist.ai.ai.MemoryGuard.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryGuardTest {
    private fun kind(text: String): Kind = MemoryGuard.check(text).kind

    @Test fun secretsAreNeverStored() {
        for (t in listOf(
            "mera otp 482913 hai",
            "mera password hunter2",
            "UPI PIN 4821",
            "mera pin 1234",
            "mera card number 4111 1111 1111 1111",
            "aadhaar 1234 5678 9012",
            "mera pan ABCDE1234F hai",
            "bank account number 123456789012",
            "cvv 123",
            "my api key is abc123",
            "a/c no 5432"
        )) assertEquals(t, Kind.NEVER, kind(t))
    }

    @Test fun aBareLongNumberIsRefusedEvenWithoutAKeyword() {
        assertEquals(Kind.NEVER, kind("1234 5678 9012"))
        assertEquals(Kind.NEVER, kind("4111111111111111"))
        assertEquals(Kind.NEVER, kind("123456789012345678901"))
    }

    @Test fun phoneLikeNumbersAreOnlyTemporary() {
        assertEquals(Kind.TEMPORARY, kind("mera number 9876543210 hai"))
        assertEquals(Kind.TEMPORARY, kind("mera number 98765 43210 hai"))
        assertEquals(Kind.TEMPORARY, kind("call karne ka number +91 98765 43210"))
        assertEquals(Kind.TEMPORARY, kind("919876543210"))
        assertEquals(Kind.TEMPORARY, kind("landline 022 2345 6789"))
    }

    @Test fun ordinaryFactsAreFine() {
        assertEquals(Kind.OK, kind("meri bhanji ka naam Gaib hai"))
        assertEquals(Kind.OK, kind("mujhe cricket pasand hai"))
        assertEquals(Kind.OK, kind("meeting 12 10 2026 ko hai"))
        assertEquals(Kind.OK, kind("main Pune mein rehta hoon"))
    }

    @Test fun aPostalPinCodeIsNotAPin() {
        assertEquals(Kind.OK, kind("pin code 411001"))
        assertEquals(Kind.OK, kind("mera pincode 411001 hai"))
    }

    @Test fun digitsAreReadBackOneByOne() {
        assertEquals("9 8 7 6 5 4 3 2 1 0", MemoryGuard.readback("mera number 98765 43210 hai"))
        assertNull(MemoryGuard.readback("kuch nahi"))
    }
}
