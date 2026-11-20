// port-lint: tests util.rs

package io.github.kotlinmania.age

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilTest {
    @Test
    fun testLineEnding() {
        assertTrue(LINE_ENDING == "\n" || LINE_ENDING == "\r\n")
    }

    @Test
    fun testParseBech32ValidAndInvalid() {
        val valid = "AGE-SECRET-KEY-1GQ9778VQXMMJVE8SK7J6VT8UJ4HDQAJUVSFCWCM02D8GEWQ72PVQ2Y5J33"
        val parsed = parseBech32(valid)
        assertNotNull(parsed)
        assertEquals("age-secret-key-", parsed.first)
        assertEquals(32, parsed.second.size)

        assertNull(parseBech32(""))
        assertNull(parseBech32("MixedCase1gq9778vqxmmjve8sk7j6vt8uj4hdqajuvsfcwcm02d8gewq72pvq2y5j33"))
        assertNull(parseBech32("no_separator"))
        assertNull(parseBech32("age1invalidchecksum000000"))
    }

    @Test
    fun testBase64Arg() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val encoded = UtilWrite.encodedData(raw)
        val decoded = UtilRead.base64Arg(encoded, 4)
        assertNotNull(decoded)
        assertContentEquals(raw, decoded)

        assertNull(UtilRead.base64Arg(encoded, 5))
        assertNull(UtilRead.base64Arg("invalid!base64", 4))

        val decodedFromBytes = UtilRead.base64Arg(encoded.encodeToByteArray(), 4)
        assertNotNull(decodedFromBytes)
        assertContentEquals(raw, decodedFromBytes)
    }

    @Test
    fun testDecimalDigitArg() {
        assertEquals(12345L, UtilRead.decimalDigitArg("12345"))
        assertNull(UtilRead.decimalDigitArg("0123"))
        assertNull(UtilRead.decimalDigitArg("0"))
        assertNull(UtilRead.decimalDigitArg("-5"))
        assertNull(UtilRead.decimalDigitArg("abc"))
        assertNull(UtilRead.decimalDigitArg(""))

        assertEquals(42, UtilRead.decimalDigitArgInt("42"))
        assertNull(UtilRead.decimalDigitArgInt("042"))

        assertEquals(18u.toUByte(), UtilRead.decimalDigitArgUByte("18"))
        assertNull(UtilRead.decimalDigitArgUByte("018"))
    }

    @Test
    fun testEncodedStrAndStream() {
        val raw = byteArrayOf(10, 20, 30)
        val encoded = UtilWrite.encodedData(raw)
        val res = UtilRead.encodedStr(3, encoded + "extra")
        assertNotNull(res)
        assertEquals("extra", res.first)
        assertContentEquals(raw, res.second)

        val whileEncoded = UtilRead.strWhileEncoded(encoded + " extra")
        assertNotNull(whileEncoded)
        assertEquals(" extra", whileEncoded.first)
        assertContentEquals(raw, whileEncoded.second)
    }
}
