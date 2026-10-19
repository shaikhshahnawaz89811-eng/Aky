package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Net search stage S3: the app-written words of a link answer, and the card formatting helpers. */
class LinkAnswerTest {
    private fun card(
        role: String = "file", domain: String = "github.com", detail: String? = "b1 \u00B7 1 MB", checksum: String? = null,
        runnable: Boolean = false
    ) = WebCard(role, "x", "https://$domain/x", domain, "badge", "KNOWN_HOST", detail, "x", 1_000_000L, "application/zip", checksum, role == "file", runnable)

    private val repo = GitHubRepo("ggml-org", "llama.cpp", "d", 5, false, false, "https://github.com/ggml-org/llama.cpp", "MIT", "2026-10-12T10:00:00Z")
    private val release = GitHubRelease("b1", "Build", "2026-10-10T08:00:00Z", false, "https://github.com/ggml-org/llama.cpp/releases/tag/b1", emptyList())

    private fun out(
        summary: LinkSummary,
        cards: List<WebCard> = emptyList(),
        repo: GitHubRepo? = null,
        release: GitHubRelease? = null,
        hidden: Int = 0,
        wanted: List<String> = emptyList(),
        gh: SearchFailure? = null,
        extract: SearchFailure? = null,
        rejects: Int = 0
    ) = LinkOutcome(summary, cards, repo, release, hidden, wanted, gh, extract, 0, 0, 0, 0, rejects)

    // ---------- formatting ----------

    @Test fun daysAreCutFromIsoTimes() {
        assertEquals("2026-10-10", LinkFmt.day("2026-10-10T08:00:00Z"))
        assertEquals("2026-10-10", LinkFmt.day(" 2026-10-10 "))
    }

    @Test fun sizesUseDecimalUnits() {
        assertEquals("2.60 GB", LinkFmt.size(2_600_000_000L))
        assertEquals("12 MB", LinkFmt.size(12_345_678L))
        assertEquals("1 MB", LinkFmt.size(1_000_000L))
        assertEquals("999 KB", LinkFmt.size(999_999L))
        assertEquals("1 KB", LinkFmt.size(1_500L))
        assertEquals("900 B", LinkFmt.size(900L))
    }

    @Test fun cleanRemovesLinksHiddenCharactersAndExtraSpace() {
        assertEquals("See now ok", LinkFmt.clean("See https://x.com/a now\n\n ok\u200B", 50))
        assertEquals("a b", LinkFmt.clean("a\u0007 www.evil.example/x b", 50))
    }

    @Test fun cleanCutsWithAnEllipsis() {
        val c = LinkFmt.clean("a".repeat(300), 10)
        assertEquals(10, c.length)
        assertTrue(c.endsWith("\u2026"))
        assertEquals("short", LinkFmt.clean("short", 10))
    }

    // ---------- no address, no promise of safety ----------

    @Test fun noReplyTextHoldsAnAddressOrPromisesSafety() {
        val cards = listOf(card(), card(runnable = true, detail = "9 MB \u00B7 checked: https"))
        for (s in LinkSummary.values()) {
            val t = LinkAnswer.text(out(s, cards, repo, release, 3, listOf("apk"), SearchFailure(FailureKind.SERVER), SearchFailure(FailureKind.SERVER), 2))
            val lower = t.lowercase()
            assertFalse(s.name, Regex("https?://").containsMatchIn(lower))
            assertFalse(s.name, lower.contains("www."))
            assertFalse(s.name, lower.contains("verified"))
            assertFalse(s.name, lower.contains("100%"))
        }
    }

    // ---------- official link ----------

    @Test fun anOfficialAnswerSaysLikelyAndNotSure() {
        val t = LinkAnswer.text(out(LinkSummary.OFFICIAL, listOf(card("primary", "tavily.com"), card("alternate", "github.com"))))
        assertTrue(t.contains("tavily.com"))
        assertTrue(t.contains("likely official"))
        assertTrue(t.contains("Pakka nahi hai"))
        assertTrue(t.contains("1 aur option"))
    }

    @Test fun anUnconfirmedOfficialAnswerSaysSo() {
        val t = LinkAnswer.text(out(LinkSummary.OFFICIAL_UNCONFIRMED, listOf(card("primary", "x.com"))))
        assertTrue(t.contains("pakki nahi hui"))
    }

    // ---------- download pages ----------

    @Test fun pageAnswersMentionLowTrustMirrors() {
        assertTrue(LinkAnswer.text(out(LinkSummary.PAGES)).contains("low trust"))
        assertTrue(LinkAnswer.text(out(LinkSummary.PAGES_LOW_ONLY)).contains("Bharosemand download page nahi mila"))
    }

    // ---------- files ----------

    @Test fun aFileAnswerNamesTheCountTheTypeAndTheTap() {
        val files = listOf(card(), card(runnable = true))
        val t = LinkAnswer.text(out(LinkSummary.FILES, files, repo, release, hidden = 3, wanted = listOf("apk")))
        assertTrue(t.startsWith("2 file (.apk) mili."))
        assertTrue(t.contains("Release b1"))
        assertTrue(t.contains("GitHub API"))
        assertTrue(t.contains("Checksum wahi dikhaya jo publisher ne diya"))
        assertTrue(t.contains("Aur 3 file hain"))
        assertTrue(t.contains(LinkAnswer.INSTALL_NOTE))
        assertTrue(t.contains("chalne wali file"))
        assertFalse(t.contains(LinkAnswer.CHECKED_NOTE))
    }

    @Test fun aCheckedFileAddsTheMeaningOfChecked() {
        val t = LinkAnswer.text(out(LinkSummary.FILES, listOf(card(domain = "tavily.com", detail = "9 MB \u00B7 checked: https"))))
        assertTrue(t.contains(LinkAnswer.CHECKED_NOTE))
        assertFalse(t.contains("GitHub API"))
    }

    @Test fun aFileWithAChecksumDoesNotGetTheNoChecksumSentence() {
        val t = LinkAnswer.text(out(LinkSummary.FILES, listOf(card(checksum = "sha256:" + "a".repeat(64))), repo, release))
        assertFalse(t.contains("Checksum wahi dikhaya"))
    }

    @Test fun noFileOfTheNamedTypeSaysWhichType() {
        val t = LinkAnswer.text(out(LinkSummary.FILES_NONE_OF_TYPE, listOf(card("release")), repo, release, wanted = listOf("apk")))
        assertTrue(t.contains("Latest release (b1) mein .apk file nahi hai"))
    }

    @Test fun aPageOnlyAnswerRefusesToGuessAnAddress() {
        val t = LinkAnswer.text(out(LinkSummary.FILES_PAGE_ONLY, listOf(card("primary", "tavily.com")), rejects = 2, extract = SearchFailure(FailureKind.TIMEOUT)))
        assertTrue(t.contains("Direct file link pakka nahi mila"))
        assertTrue(t.contains("andaza lagana galat ho sakta hai"))
        assertTrue(t.contains("2 file link jaanch mein fail hue"))
        assertTrue(t.contains(LinkAnswer.EXTRACT_NOTE))
        val plain = LinkAnswer.text(out(LinkSummary.FILES_PAGE_ONLY, listOf(card("primary", "tavily.com"))))
        assertFalse(plain.contains("jaanch mein fail"))
        assertFalse(plain.contains(LinkAnswer.EXTRACT_NOTE))
    }

    // ---------- GitHub ----------

    @Test fun aGithubAnswerGivesRepoTagDateAndFiles() {
        val t = LinkAnswer.text(out(LinkSummary.GITHUB, listOf(card("repo"), card("release"), card(), card()), repo, release))
        assertTrue(t.startsWith("ggml-org/llama.cpp GitHub par mila."))
        assertTrue(t.contains("Latest release: b1 (2026-10-10)"))
        assertTrue(t.contains("GitHub API"))
        assertTrue(t.contains("2 file neeche hain"))
    }

    @Test fun aRepoWithNoReleaseSaysSo() {
        val t = LinkAnswer.text(out(LinkSummary.GITHUB_NO_RELEASE, listOf(card("repo")), repo))
        assertTrue(t.contains("koi published stable release nahi hai"))
    }

    @Test fun aMissingRepoSaysSoWithOrWithoutCandidates() {
        assertEquals("GitHub par ye repo nahi mila.", LinkAnswer.text(out(LinkSummary.GITHUB_NOT_FOUND)))
        assertTrue(LinkAnswer.text(out(LinkSummary.GITHUB_NOT_FOUND, listOf(card("primary", "x.com")))).contains("pakke nahi hain"))
    }

    @Test fun githubErrorsHaveTheirOwnWords() {
        assertEquals(LinkAnswer.GITHUB_LIMIT_TEXT, LinkAnswer.text(out(LinkSummary.GITHUB_ERROR, gh = SearchFailure(FailureKind.RATE_LIMITED))))
        assertEquals(LinkAnswer.GITHUB_DOWN_TEXT, LinkAnswer.text(out(LinkSummary.GITHUB_ERROR, gh = SearchFailure(FailureKind.SERVER))))
        assertTrue(LinkAnswer.text(out(LinkSummary.GITHUB_ERROR, listOf(card("repo")), repo, gh = SearchFailure(FailureKind.SERVER))).contains("Repo ka page neeche hai"))
        assertEquals(LinkAnswer.GITHUB_DOWN_TEXT, LinkAnswer.githubErrorText(null))
    }

    @Test fun nothingFoundHasTheStandardLine() {
        assertEquals(LinkAnswer.NOTHING_TEXT, LinkAnswer.text(out(LinkSummary.NOTHING)))
        assertTrue(LinkAnswer.NOTHING_TEXT.contains("andaza nahi laga raha"))
    }
}
