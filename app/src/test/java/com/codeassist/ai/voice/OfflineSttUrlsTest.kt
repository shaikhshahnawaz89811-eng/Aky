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

    private val hinglishFolder =
        "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-swift"

    @Test
    fun defaultLinkIsTheHinglishWhisperFolder() {
        val p = OfflineSttUrls.plan(OfflineSttUrls.DEFAULT_URL)
        assertNotNull(p)
        assertTrue(p!!.folder)
        assertEquals("$hinglishFolder/encoder.int8.onnx", p.encoderUrl)
        assertEquals("$hinglishFolder/decoder.int8.onnx", p.decoderUrl)
        assertEquals("$hinglishFolder/tokens.txt", p.tokensUrl)
    }

    @Test
    fun folderLinkAlsoKeepsTheCtcLayoutAsFallback() {
        val p = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo/tree/main/hi")
        assertNotNull(p)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/encoder.int8.onnx", p!!.encoderUrl)
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/hi/model.int8.onnx", p.modelUrl)
    }

    @Test
    fun tokensFromOneFolderUpAreTheFallbackForALanguageFolder() {
        val folder = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo/tree/main/hi")
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/tokens.txt", folder!!.parentTokensUrl)
        val file = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo/blob/main/hi/model.int8.onnx")
        assertEquals("https://huggingface.co/some-org/some-repo/resolve/main/tokens.txt", file!!.parentTokensUrl)
    }

    @Test
    fun topOfARepoHasNoFolderAboveIt() {
        val p = OfflineSttUrls.plan("https://huggingface.co/some-org/some-repo")
        assertNull(p!!.parentTokensUrl)
    }

    @Test
    fun directEncoderLinkIsWhisperAndFindsTheDecoderBesideIt() {
        val p = OfflineSttUrls.plan("https://example.com/m/hinglish/encoder.int8.onnx")
        assertNotNull(p)
        assertEquals(false, p!!.folder)
        assertEquals("https://example.com/m/hinglish/encoder.int8.onnx", p.encoderUrl)
        assertEquals("https://example.com/m/hinglish/decoder.int8.onnx", p.decoderUrl)
        assertEquals("https://example.com/m/hinglish/tokens.txt", p.tokensUrl)
    }

    @Test
    fun directDecoderLinkWorksToo() {
        val p = OfflineSttUrls.plan("https://example.com/m/hinglish/decoder.int8.onnx")
        assertEquals("https://example.com/m/hinglish/encoder.int8.onnx", p!!.encoderUrl)
        assertEquals("https://example.com/m/hinglish/decoder.int8.onnx", p.decoderUrl)
    }

    @Test
    fun directCtcFileIsNotWhisper() {
        val p = OfflineSttUrls.plan("https://example.com/models/indic_hi.onnx")
        assertNull(p!!.encoderUrl)
        assertNull(p.decoderUrl)
    }
}
