package com.termux.app.fleet

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BoundedUtf8LineReaderTest {
    @Test
    fun readsMultipleUtf8FramesWithinTheByteLimit() {
        BoundedUtf8LineReader(ByteArrayInputStream("é\nsecond\n".toByteArray()), 8).use { reader ->
            assertEquals("é", reader.readLine())
            assertEquals("second", reader.readLine())
            assertNull(reader.readLine())
        }
    }

    @Test
    fun rejectsANonTerminatedLineBeforeAllocatingPastTheLimit() {
        BoundedUtf8LineReader(ByteArrayInputStream(ByteArray(1_000_000) { 'a'.code.toByte() }), 1_024).use { reader ->
            try {
                reader.readLine()
                fail("oversized line was accepted")
            } catch (_: BoundedLineException) {
                // Expected.
            }
        }
    }

    @Test
    fun rejectsMalformedUtf8AndAcceptsCrLfFrames() {
        BoundedUtf8LineReader(ByteArrayInputStream("frame\r\n".toByteArray()), 16).use { reader ->
            assertEquals("frame", reader.readLine())
        }
        BoundedUtf8LineReader(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, 0x0a)), 16).use { reader ->
            try {
                reader.readLine()
                fail("malformed UTF-8 was accepted")
            } catch (_: BoundedLineException) {
                // Expected.
            }
        }
    }
}
