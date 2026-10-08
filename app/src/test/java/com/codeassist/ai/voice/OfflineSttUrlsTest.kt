package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSttUrlsTest {

    @Test
    fun huggingFaceRepoPageBecomesResolveFolder() {
        val p = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo")
        assertNotNull(p)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/model.int8.onnx", p!!.modelUrl)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/tokens.txt", p.tokensUrl)
    }

    @Test
    fun huggingFaceTreeLinkKeepsSubfolder() {
        val p = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo/tree/main/hi")
        assertNotNull(p)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/model.int8.onnx", p!!.modelUrl)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/tokens.txt", p.tokensUrl)
    }

    @Test
    fun blobFileLinkWithQueryBecomesDirectFile() {
        val p = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo/blob/main/hi/model.int8.onnx?download=true")
        assertNotNull(p)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/model.int8.onnx", p!!.modelUrl)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/tokens.txt", p.tokensUrl)
    }

    @Test
    fun directOnnxLinkTakesTokensFromSameFolder() {
        val p = OfflineSttUrls.plan("https://example.com/models/indic_hi.onnx")
        assertNotNull(p)
        assertEquals("https://example.com/models/indic_hi.onnx", p!!.modelUrl)
        assertEquals("https://example.com/models/tokens.txt", p.tokensUrl)
    }

    @Test
    fun plainFolderLinkGetsTheTwoDefaultNames() {
        val p = OfflineSttUrls.plan("  https://example.com/models/hi/  ")
        assertNotNull(p)
        assertEquals("https://example.com/models/hi/model.int8.onnx", p!!.modelUrl)
        assertEquals("https://example.com/models/hi/tokens.txt", p.tokensUrl)
    }

    @Test
    fun notALinkIsRejected() {
        assertNull(OfflineSttUrls.plan(""))
        assertNull(OfflineSttUrls.plan("IndicConformer Hinglish Swift - sherpa-onnx"))
        assertNull(OfflineSttUrls.plan("ftp://example.com/models/hi"))
        assertNull(OfflineSttUrls.plan("https://example.com"))
    }

    @Test
    fun shortLabelStaysShortAndKeepsTheEnd() {
        assertEquals("", OfflineSttUrls.short("   "))
        assertEquals("example.com/m", OfflineSttUrls.short("https://example.com/m"))
        val long = OfflineSttUrls.short("https://huggingface.co/some-org/some-very-long-repository-name-here/resolve/main/hi")
        assertTrue(long.length <= 34)
        assertTrue(long.endsWith("hi"))
    }
}
