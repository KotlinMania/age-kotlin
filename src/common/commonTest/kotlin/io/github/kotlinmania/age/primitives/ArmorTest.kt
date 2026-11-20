// port-lint: tests primitives/armor.rs

package io.github.kotlinmania.age.primitives

import io.github.kotlinmania.base64.DecodeError
import io.github.kotlinmania.base64.DecodeSliceError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// armored_async_round_trip and armored_async_cross_check require the `async` feature
// (`futures::io::{AsyncRead, AsyncWrite}` + `memchr`), which is deferred pending `memchr-kotlin` Maven publication.
class ArmorTest {
    @Test
    fun armoredRoundTrip() {
        val maxLen = ARMORED_BYTES_PER_LINE * 50
        val data = ArrayList<Byte>(maxLen)

        for (i in 0 until maxLen) {
            data.add(i.toByte())
            val plain = data.toByteArray()

            val sink = ByteVecWriter()
            val out = ArmoredWriter.wrapOutput(sink, Format.AsciiArmor).getOrThrow()
            out.writeAll(plain).getOrThrow()
            val finishedSink = out.finish().getOrThrow()
            val encoded = finishedSink.toByteArray()

            val reader = ArmoredReader.new(Cursor.new(encoded))
            val decoded = reader.readToEnd().getOrThrow()
            reader.close()

            assertContentEquals(plain, decoded)
        }
    }

    @Test
    fun binarySeeking() {
        val data = ByteArray(100 * 100) { i -> i.toByte() }

        val sink = ByteVecWriter()
        val writer = ArmoredWriter.wrapOutput(sink, Format.Binary).getOrThrow()
        writer.writeAll(data).getOrThrow()
        val written = writer.finish().getOrThrow().toByteArray()
        assertContentEquals(data, written)

        val reader = ArmoredReader.new(Cursor.new(written))

        // Read part-way into the first chunk
        val buf = ByteArray(100)
        reader.readExact(buf, 0, 5).getOrThrow()
        assertContentEquals(data.copyOfRange(0, 5), buf.copyOfRange(0, 5))

        // Seek back to the beginning
        reader.seek(SeekFrom.Start(0uL)).getOrThrow()

        // Read into the middle of the data
        for (i in 0 until 70) {
            reader.readExact(buf).getOrThrow()
            assertContentEquals(data.copyOfRange(100 * i, 100 * (i + 1)), buf)
        }

        // Seek back into the first line
        reader.seek(SeekFrom.Start(5uL)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(5, 105), buf)

        // Seek forwards from the current position
        reader.seek(SeekFrom.Current(500)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(605, 705), buf)

        // Seek backwards from the end
        reader.seek(SeekFrom.End(-1337)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(data.size - 1337, data.size - 1237), buf)

        reader.close()
    }

    @Test
    fun armoredSeeking() {
        val data = ByteArray(100 * 100) { i -> i.toByte() }

        val sink = ByteVecWriter()
        val writer = ArmoredWriter.wrapOutput(sink, Format.AsciiArmor).getOrThrow()
        writer.writeAll(data).getOrThrow()
        val armored = writer.finish().getOrThrow().toByteArray()

        val reader = ArmoredReader.new(Cursor.new(armored))

        // Read part-way into the first line
        val buf = ByteArray(100)
        reader.readExact(buf, 0, 5).getOrThrow()
        assertContentEquals(data.copyOfRange(0, 5), buf.copyOfRange(0, 5))

        // Seek back to the beginning
        reader.seek(SeekFrom.Start(0uL)).getOrThrow()

        // Read into the middle of the data
        for (i in 0 until 70) {
            reader.readExact(buf).getOrThrow()
            assertContentEquals(data.copyOfRange(100 * i, 100 * (i + 1)), buf)
        }

        // Seek back into the first line
        reader.seek(SeekFrom.Start(5uL)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(5, 105), buf)

        // Seek forwards from the current position
        reader.seek(SeekFrom.Current(500)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(605, 705), buf)

        // Seek backwards from the end
        reader.seek(SeekFrom.End(-1337)).getOrThrow()
        reader.readExact(buf).getOrThrow()
        assertContentEquals(data.copyOfRange(data.size - 1337, data.size - 1237), buf)

        reader.close()
    }

    @Test
    fun emptyPayloadAndCrlfMarkersRoundTrip() {
        val emptyArmored = armorEncode(ByteArray(0), Format.AsciiArmor)
        val emptyResult = armorDecode(emptyArmored)
        assertEquals(ArmoredReadError.MissingPadding, emptyResult.error)

        val singleByteArmored = armorEncode(byteArrayOf(42), Format.AsciiArmor)
        val singleByteResult = armorDecode(singleByteArmored)
        assertTrue(singleByteResult.isSuccess)
        assertContentEquals(byteArrayOf(42), singleByteResult.data!!)

        val payload = "hello age armor!".encodeToByteArray()
        val crlfArmored =
            (
                "$ARMORED_BEGIN_MARKER\r\n" +
                    "aGVsbG8gYWdlIGFybW9yIQ==\r\n" +
                    "$ARMORED_END_MARKER\r\n   \t\n"
            ).encodeToByteArray()
        val noFinalNewline =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "aGVsbG8gYWdlIGFybW9yIQ==\n" +
                    ARMORED_END_MARKER
            ).encodeToByteArray()

        val crlfResult = armorDecode(crlfArmored)
        assertTrue(crlfResult.isSuccess)
        assertContentEquals(payload, crlfResult.data!!)

        val noNewlineResult = armorDecode(noFinalNewline)
        assertTrue(noNewlineResult.isSuccess)
        assertContentEquals(payload, noNewlineResult.data!!)
    }

    @Test
    fun rejectInvalidBeginMarkerAndInvalidUtf8InArmor() {
        val badBegin =
            (
                "$ARMORED_BEGIN_MARKER  \n" +
                    "aGVsbG8=\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        val badBeginRes = armorDecode(badBegin)
        assertEquals(ArmoredReadError.InvalidBeginMarker, badBeginRes.error)
        assertEquals("invalid armor begin marker", badBeginRes.error?.fmt())

        val badFirstBodyByte =
            byteArrayOf(
                *ARMORED_BEGIN_MARKER.encodeToByteArray(),
                '\n'.code.toByte(),
                0xFF.toByte(),
                '\n'.code.toByte(),
                *ARMORED_END_MARKER.encodeToByteArray(),
            )
        val utf8FirstRes = armorDecode(badFirstBodyByte)
        assertEquals(ArmoredReadError.InvalidUtf8, utf8FirstRes.error)
        assertEquals("stream did not contain valid UTF-8", utf8FirstRes.error?.fmt())

        val badSecondLineUtf8 =
            byteArrayOf(
                *ARMORED_BEGIN_MARKER.encodeToByteArray(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                0xFF.toByte(),
                '\n'.code.toByte(),
                *ARMORED_END_MARKER.encodeToByteArray(),
            )
        assertEquals(ArmoredReadError.InvalidUtf8, armorDecode(badSecondLineUtf8).error)
    }

    @Test
    fun rejectCrMissingPaddingShortMiddleLongLineBase64AndTrailingGarbage() {
        val strayCr =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "aGVsb\rG8=\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        assertEquals(ArmoredReadError.LineContainsCr, armorDecode(strayCr).error)

        val missingPad =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "aGVsbG8\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        assertEquals(ArmoredReadError.MissingPadding, armorDecode(missingPad).error)

        val line64 = "A".repeat(64)
        val shortMiddle =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "AAAA\n" +
                    "$line64\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        assertEquals(ArmoredReadError.ShortLineInMiddle, armorDecode(shortMiddle).error)

        val longLine =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "AAAA".repeat(17) +
                    "\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        assertEquals(ArmoredReadError.NotWrappedAt64Chars, armorDecode(longLine).error)

        val badBase64 =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "!!!!\n" +
                    "$ARMORED_END_MARKER\n"
            ).encodeToByteArray()
        val badB64Res = armorDecode(badBase64)
        val b64Err = assertIs<ArmoredReadError.Base64>(badB64Res.error)
        assertTrue(b64Err.fmt().isNotEmpty())

        val trailing =
            (
                "$ARMORED_BEGIN_MARKER\n" +
                    "aGVsbG8=\n" +
                    "$ARMORED_END_MARKER\n" +
                    "extra\n"
            ).encodeToByteArray()
        assertEquals(ArmoredReadError.TrailingGarbage, armorDecode(trailing).error)
        assertEquals(
            "invalid armor (non-whitespace characters after end marker)",
            ArmoredReadError.TrailingGarbage.toString(),
        )
        val invalidPadErr =
            ArmoredReadError.Base64(DecodeSliceError.fromDecodeError(DecodeError.InvalidPadding))
        assertEquals("DecodeError: Invalid padding", invalidPadErr.fmt())
        assertEquals("DecodeError: Invalid padding", invalidPadErr.toString())
    }
}
