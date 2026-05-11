package dev.chuds.stillcal.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IoActionsBoundedReadTest {

    @Test
    fun readsPayloadsUnderTheCapVerbatim() {
        val payload = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
        val stream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))

        val result = readBoundedText(stream, MAX_IMPORT_BYTES)

        assertTrue(result is ReadResult.Ok)
        assertEquals(payload, (result as ReadResult.Ok).text)
    }

    @Test
    fun returnsTooLargeWhenPayloadExceedsCap() {
        val cap = 1024L
        val payload = ByteArray(cap.toInt() + 1) { 'a'.code.toByte() }
        val stream = ByteArrayInputStream(payload)

        val result = readBoundedText(stream, cap)

        assertTrue(result is ReadResult.TooLarge)
    }

    @Test
    fun abortsEarlyOnHugeStreamWithoutBufferingItAll() {
        val cap = 64L
        val giant = ByteArray(10 * 1024 * 1024) { 'x'.code.toByte() }
        val stream = ByteArrayInputStream(giant)

        val result = readBoundedText(stream, cap)

        assertTrue(result is ReadResult.TooLarge)
        // After aborting, the rest of the stream is still readable — proves we didn't
        // drain it entirely into memory.
        assertTrue(stream.available() > giant.size / 2)
    }

    @Test
    fun acceptsExactlyTheCap() {
        val cap = 1024L
        val payload = ByteArray(cap.toInt()) { 'b'.code.toByte() }
        val stream = ByteArrayInputStream(payload)

        val result = readBoundedText(stream, cap)

        assertTrue(result is ReadResult.Ok)
        assertEquals(cap.toInt(), (result as ReadResult.Ok).text.length)
    }
}
