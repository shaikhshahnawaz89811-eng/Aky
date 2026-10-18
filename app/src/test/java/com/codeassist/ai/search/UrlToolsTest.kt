package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsTest {
    @Test fun hostIsLowerCaseWithoutWww() {
        assertEquals("example.com", UrlTools.host("https://www.Example.com/a"))
        assertEquals("docs.python.org", UrlTools.host("http://docs.python.org:8080/3/"))
    }

    @Test fun nonWebTextHasNoHost() {
        assertEquals("", UrlTools.host("ftp://example.com/file"))
        assertEquals("", UrlTools.host("not a url"))
        assertEquals("", UrlTools.host("javascript:alert(1)"))
        assertEquals("", UrlTools.host(""))
    }

    @Test fun webUrlAndHttpsChecks() {
        assertTrue(UrlTools.isWebUrl("https://a.com"))
        assertTrue(UrlTools.isWebUrl("http://a.com/x"))
        assertFalse(UrlTools.isWebUrl("ftp://a.com"))
        assertFalse(UrlTools.isWebUrl("a.com"))
        assertTrue(UrlTools.isHttps("https://a.com"))
        assertFalse(UrlTools.isHttps("http://a.com"))
    }

    @Test fun normalizeMakesTheSamePageTheSameText() {
        val expected = "https://example.com/a/b?id=5"
        assertEquals(expected, UrlTools.normalize("http://www.Example.com:80/a/b/?utm_source=x&id=5&fbclid=z#frag"))
        assertEquals(expected, UrlTools.normalize("https://example.com/a/b?id=5&utm_medium=mail"))
    }

    @Test fun normalizeKeepsRealPortsAndRootIsEmptyPath() {
        assertEquals("https://example.com", UrlTools.normalize("https://example.com/"))
        assertEquals("https://example.com:8443/x", UrlTools.normalize("https://example.com:8443/x"))
    }

    @Test fun normalizeLeavesGarbageAlone() {
        assertEquals("garbage", UrlTools.normalize("  garbage "))
    }

    @Test fun registrableDomainKnowsTwoPartSuffixes() {
        assertEquals("example.co.uk", UrlTools.registrableDomain("docs.example.co.uk"))
        assertEquals("example.com", UrlTools.registrableDomain("a.b.example.com"))
        assertEquals("example.com", UrlTools.registrableDomain("example.com"))
        assertEquals("bbc.co.uk", UrlTools.registrableDomain("news.bbc.co.uk"))
        assertEquals("localhost", UrlTools.registrableDomain("localhost"))
        assertEquals("", UrlTools.registrableDomain(""))
    }

    @Test fun ipAddressesStayWhole() {
        assertTrue(UrlTools.isIpLiteral("192.168.1.1"))
        assertTrue(UrlTools.isIpLiteral("[::1]"))
        assertFalse(UrlTools.isIpLiteral("example.com"))
        assertEquals("192.168.1.1", UrlTools.registrableDomain("192.168.1.1"))
    }

    @Test fun sameSiteComparesRegistrableDomains() {
        assertTrue(UrlTools.sameSite("https://docs.python.org/3/", "https://www.python.org"))
        assertFalse(UrlTools.sameSite("https://python.org", "https://python.com"))
        assertFalse(UrlTools.sameSite("nonsense", "nonsense"))
    }
}
