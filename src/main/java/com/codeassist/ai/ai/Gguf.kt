package com.codeassist.ai.ai

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GGUF header reader. It only walks the metadata key/value table far enough to find
 * `general.architecture` and `general.name`, so an import can be rejected in milliseconds
 * (before copying the whole file) when the picked file is not the expected GGUF model.
 */
object Gguf {
    class Info(val version: Int, val architecture: String?, val name: String?)

    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val MAX_STRING = 64L * 1024 * 1024

    @Throws(IOException::class)
    fun readInfo(source: InputStream): Info {
        val input = DataInputStream(BufferedInputStream(source, 64 * 1024))
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "GGUF") throw IOException("Ye GGUF file nahi hai")
        val version = readInt(input)
        if (version < 2 || version > 3) throw IOException("GGUF version $version support nahi")
        readLong(input) // tensor count
        val kvCount = readLong(input)

        var architecture: String? = null
        var name: String? = null
        var index = 0L
        while (index < kvCount && index < 512 && (architecture == null || name == null)) {
            val key = readString(input)
            val type = readInt(input)
            if (type == TYPE_STRING) {
                val value = readString(input)
                if (key == "general.architecture") architecture = value
                if (key == "general.name") name = value
            } else {
                skipValue(input, type)
            }
            index++
        }
        return Info(version, architecture, name)
    }

    private fun readInt(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLong(input: DataInputStream): Long {
        val b = ByteArray(8)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readString(input: DataInputStream): String {
        val length = readLong(input)
        if (length < 0 || length > MAX_STRING) throw IOException("GGUF header kharab hai")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(input: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) throw IOException("GGUF file adhoori hai")
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun scalarSize(type: Int): Int = when (type) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        10, 11, 12 -> 8
        else -> throw IOException("GGUF value type $type samajh nahi aaya")
    }

    private fun skipValue(input: DataInputStream, type: Int) {
        when (type) {
            TYPE_STRING -> {
                val length = readLong(input)
                if (length < 0) throw IOException("GGUF header kharab hai")
                skipFully(input, length)
            }
            TYPE_ARRAY -> {
                val elementType = readInt(input)
                val count = readLong(input)
                if (count < 0) throw IOException("GGUF header kharab hai")
                if (elementType == TYPE_STRING) {
                    var i = 0L
                    while (i < count) {
                        val length = readLong(input)
                        if (length < 0) throw IOException("GGUF header kharab hai")
                        skipFully(input, length)
                        i++
                    }
                } else if (elementType == TYPE_ARRAY) {
                    throw IOException("Nested GGUF arrays support nahi")
                } else {
                    skipFully(input, count * scalarSize(elementType))
                }
            }
            else -> skipFully(input, scalarSize(type).toLong())
        }
    }
}
