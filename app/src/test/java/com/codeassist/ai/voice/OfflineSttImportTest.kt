package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSttImportTest {

    private fun e(name: String, size: Long = 1000L) = OfflineSttImport.Entry(name, size)

    @Test
    fun modelAndTokensAreFoundWhateverTheOrder() {
        val p = OfflineSttImport.classify(listOf(e("tokens.txt", 5_000L), e("model.int8.onnx", 150_000_000L)))
        assertNull(p.error)
        assertEquals(1, p.model)
        assertEquals(0, p.tokens)
        assertEquals(0, p.ignored)
    }

    @Test
    fun anyOnnxNameIsAcceptedAsModel() {
        val p = OfflineSttImport.classify(listOf(e("hindi_ctc_final.onnx", 150_000_000L), e("vocab_tokens.txt", 5_000L)))
        assertNull(p.error)
        assertEquals(0, p.model)
        assertEquals(1, p.tokens)
    }

    @Test
    fun severalOnnxFilesPreferTheInt8NamedOne() {
        val p = OfflineSttImport.classify(
            listOf(e("model.onnx", 600_000_000L), e("model.int8.onnx", 150_000_000L), e("tokens.txt"))
        )
        assertNull(p.error)
        assertEquals(1, p.model)
        assertEquals(2, p.tokens)
    }

    @Test
    fun severalOnnxFilesWithoutHintPickTheBiggest() {
        val p = OfflineSttImport.classify(listOf(e("a.onnx", 10L), e("b.onnx", 99L), e("tokens.txt")))
        assertEquals(1, p.model)
    }

    @Test
    fun onlyTheModelPickedIsAllowed() {
        val p = OfflineSttImport.classify(listOf(e("model.int8.onnx", 150_000_000L)))
        assertNull(p.error)
        assertEquals(0, p.model)
        assertNull(p.tokens)
    }

    @Test
    fun unrelatedFilesAreCountedAsIgnored() {
        val p = OfflineSttImport.classify(listOf(e("model.int8.onnx"), e("tokens.txt"), e("README.md"), e("photo.jpg")))
        assertNull(p.error)
        assertEquals(2, p.ignored)
    }

    @Test
    fun nothingUsefulGivesAMessage() {
        val p = OfflineSttImport.classify(listOf(e("photo.jpg"), e("notes.pdf")))
        assertNotNull(p.error)
        assertNull(p.model)
        assertNull(p.tokens)
    }

    @Test
    fun manyTextFilesWithoutTokensNameAskForARename() {
        val p = OfflineSttImport.classify(listOf(e("a.txt"), e("b.txt")))
        assertNotNull(p.error)
    }

    @Test
    fun emptyPickGivesAMessage() {
        assertNotNull(OfflineSttImport.classify(emptyList()).error)
    }
}
