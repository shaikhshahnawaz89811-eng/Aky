package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    private fun u8(b: Byte) = b.toInt() and 0xFF

    @Test fun headerDescribesMono16BitPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = WavHeader.wrap(pcm, 24_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(40, u8(wav[4])) // 36 + 4 payload bytes
        assertEquals(1, u8(wav[20])) // PCM
        assertEquals(1, u8(wav[22])) // mono
        // 24000 = 0x5DC0, little endian
        assertEquals(0xC0, u8(wav[24]))
        assertEquals(0x5D, u8(wav[25]))
        // byte rate 48000 = 0xBB80
        assertEquals(0x80, u8(wav[28]))
        assertEquals(0xBB, u8(wav[29]))
        assertEquals(16, u8(wav[34]))
        assertEquals(4, u8(wav[40]))
    }

    @Test fun payloadIsCopiedAfterTheHeader() {
        val pcm = byteArrayOf(9, 8, 7, 6, 5, 4)
        val wav = WavHeader.wrap(pcm, 24_000)
        assertEquals(listOf<Byte>(9, 8, 7, 6, 5, 4), wav.copyOfRange(44, 50).toList())
    }
}
