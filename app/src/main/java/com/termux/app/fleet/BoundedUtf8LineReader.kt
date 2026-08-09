package com.termux.app.fleet

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal class BoundedLineException(message: String) : IllegalArgumentException(message)

/**
 * Reads newline-delimited protocol records without allowing the platform
 * BufferedReader to allocate an unbounded line before the caller can reject it.
 */
internal class BoundedUtf8LineReader(
    input: InputStream,
    private val maximumBytes: Int
) : Closeable {
    private val input = BufferedInputStream(input, 8 * 1024)
    private val bytes = ByteArray(maximumBytes)

    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
    }

    fun readLine(): String? {
        var size = 0
        while (true) {
            when (val value = input.read()) {
                -1 -> return if (size == 0) null else decode(size)
                0x0a -> return decode(if (size > 0 && bytes[size - 1] == '\r'.code.toByte()) size - 1 else size)
                else -> {
                    if (size >= maximumBytes) throw BoundedLineException("Protocol line exceeded the safety limit")
                    bytes[size++] = value.toByte()
                }
            }
        }
    }

    private fun decode(size: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, size))
            .toString()
    } catch (_: CharacterCodingException) {
        throw BoundedLineException("Protocol line was not valid UTF-8")
    }

    override fun close() {
        input.close()
    }
}
