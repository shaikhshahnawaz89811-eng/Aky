package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryGuardTest {
    private fun ok(q: String): QueryGuard.Prepared.Ok {
        val p = QueryGuard.prepare(q)
        assertTrue("expected Ok for '$q'", p is QueryGuard.Prepared.Ok)
        return p as QueryGuard.Prepared.Ok
    }

    private fun blocked(q: String): QueryGuard.Prepared.Blocked {
        val p = QueryGuard.prepare(q)
        assertTrue("expected Blocked for '$q'", p is QueryGuard.Prepared.Blocked)
        return p as QueryGuard.Prepared.Blocked
    }

    @Test fun anOrdinaryQuestionPassesUnchanged() {
        val p = ok("gemma 4 latest version")
        assertEquals("gemma 4 latest version", p.query)
        assertFalse(p.stripped)
        assertEquals("gemma 4 vs qwen 2.5 context 8192 tokens", ok("gemma 4 vs qwen 2.5 context 8192 tokens").query)
    }

    @Test fun secretWordsAloneAreFineToSearch() {
        assertEquals("best password manager 2026", ok("best password manager 2026").query)
        assertEquals("how to reset my pin on phone", ok("how to reset my pin on phone").query)
    }

    @Test fun aSecretWordWithDigitsIsBlocked() {
        blocked("my otp is 482913")
        blocked("4111 1111 1111 1111 card number")
    }

    @Test fun cardAndPanShapesAreBlocked() {
        assertEquals("card or account number", blocked("4111 1111 1111 1111").reason)
        assertEquals("pan", blocked("ABCDE1234F pan details").reason)
    }

    @Test fun keysAreBlocked() {
        assertEquals("key", blocked("tvly-abc123def456ghi789").reason)
        assertEquals("key", blocked("AIzaSyD1234567890abcdefghijklmnopqrstuv").reason)
        assertEquals("key-like text", blocked("a1b2c3d4e5f6g7h8i9j0k1l2").reason)
    }

    @Test fun fileNamesWithHyphensAreNotKeys() {
        assertEquals("gemma-4-e2b-it-litert-lm download", ok("gemma-4-e2b-it-litert-lm download").query)
    }

    @Test fun aPhoneNumberIsStrippedAndTheSearchGoesOn() {
        val p = ok("call 9876543210 pricing")
        assertEquals("call pricing", p.query)
        assertTrue(p.stripped)
    }

    @Test fun aQueryThatIsOnlyANumberIsBlocked() {
        assertEquals("only a number", blocked("9876543210").reason)
    }

    @Test fun aPostalCodeIsNotAPhoneNumber() {
        val p = ok("pincode 110001 near me restaurants")
        assertEquals("pincode 110001 near me restaurants", p.query)
        assertFalse(p.stripped)
    }

    @Test fun emptyQueriesAreBlocked() {
        assertEquals("empty", blocked("").reason)
        assertEquals("empty", blocked("   ").reason)
    }

    @Test fun theBlockedMessageIsShortHinglish() {
        assertTrue(QueryGuard.BLOCKED_MESSAGE.isNotEmpty())
        assertTrue(QueryGuard.BLOCKED_MESSAGE.length < 200)
    }
}
