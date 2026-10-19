package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufTest {
    private class Builder {
        val out = ByteArrayOutputStream()
        fun bytes(b: ByteArray) = out.write(b)
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun long(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            long(b.size.toLong())
            out.write(b)
        }
    }

    private fun header(version: Int, kvCount: Long, body: Builder.() -> Unit): ByteArray {
        val b = Builder()
        b.bytes("GGUF".toByteArray(Charsets.US_ASCII))
        b.int(version)
        b.long(0L) // tensor count
        b.long(kvCount)
        b.body()
        return b.out.toByteArray()
    }

    @Test fun readsArchitectureAndNameAfterSkippingOtherValues() {
        val data = header(3, 4) {
            str("general.quantization_version"); int(4); int(2) // type 4 = uint32
            str("tokenizer.ggml.tokens"); int(9); int(8); long(2) // array of 2 strings
            str("<a>"); str("<bb>")
            str("general.architecture"); int(8); str("qwen2")
            str("general.name"); int(8); str("Qwen2.5 1.5B Instruct")
        }
        val info = Gguf.readInfo(ByteArrayInputStream(data))
        assertEquals(3, info.version)
        assertEquals("qwen2", info.architecture)
        assertEquals("Qwen2.5 1.5B Instruct", info.name)
    }

    @Test fun fileWithoutArchitectureReportsNull() {
        val data = header(3, 1) {
            str("general.name"); int(8); str("x")
        }
        val info = Gguf.readInfo(ByteArrayInputStream(data))
        assertNull(info.architecture)
    }

    @Test fun otherArchitecturesAreReportedAsIs() {
        val data = header(3, 1) {
            str("general.architecture"); int(8); str("phi3")
        }
        assertEquals("phi3", Gguf.readInfo(ByteArrayInputStream(data)).architecture)
    }

    @Test fun nonGgufFileIsRejected() {
        try {
            Gguf.readInfo(ByteArrayInputStream("PK\u0003\u0004 not a model".toByteArray(Charsets.ISO_8859_1)))
            fail("expected IOException")
        } catch (_: IOException) {
            // ok
        }
    }

    @Test fun unsupportedVersionIsRejected() {
        val data = header(1, 0) {}
        try {
            Gguf.readInfo(ByteArrayInputStream(data))
            fail("expected IOException")
        } catch (_: IOException) {
            // ok
        }
    }
}
