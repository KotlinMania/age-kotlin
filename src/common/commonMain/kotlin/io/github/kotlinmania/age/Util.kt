// port-lint: source util.rs

package io.github.kotlinmania.age

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Platform-independent line ending constant.
 */
internal const val LINE_ENDING: String = "\n"

/**
 * Decodes a Bech32-encoded string into its human-readable part (HRP) and data bytes.
 *
 * @param s the Bech32 string to decode
 * @return a pair of human-readable part and data bytes, or null if decoding fails
 */
internal fun parseBech32(s: String): Pair<String, ByteArray>? {
    if (s.isEmpty()) return null

    var hasLower = false
    var hasUpper = false
    for (ch in s) {
        if (ch in 'a'..'z') hasLower = true
        if (ch in 'A'..'Z') hasUpper = true
        if (hasLower && hasUpper) return null
    }

    val lower = s.lowercase()
    val pos = lower.lastIndexOf('1')
    if (pos < 1 || pos + 7 > lower.length) return null

    val hrp = lower.substring(0, pos)
    for (ch in hrp) {
        val code = ch.code
        if (code < 33 || code > 126) return null
    }

    val dataPart = lower.substring(pos + 1)
    val values = IntArray(dataPart.length)
    for (i in dataPart.indices) {
        val ch = dataPart[i]
        val v = BECH32_CHARSET.indexOf(ch)
        if (v == -1) return null
        values[i] = v
    }

    val hrpExpanded = hrpExpand(hrp)
    val combined = IntArray(hrpExpanded.size + values.size)
    hrpExpanded.copyInto(combined, 0)
    values.copyInto(combined, hrpExpanded.size)

    if (polymod(combined) != 1) return null

    val dataValues = IntArray(values.size - 6)
    values.copyInto(dataValues, 0, 0, values.size - 6)

    val converted = convertBits(dataValues, 5, 8, false) ?: return null
    return Pair(hrp, converted)
}

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

private fun polymod(values: IntArray): Int {
    var chk = 1
    for (v in values) {
        val top = chk ushr 25
        chk = ((chk and 0x1ffffff) shl 5) xor v
        for (i in 0 until 5) {
            if (((top ushr i) and 1) != 0) {
                chk = chk xor BECH32_GEN[i]
            }
        }
    }
    return chk
}

private fun hrpExpand(hrp: String): IntArray {
    val ret = IntArray(hrp.length * 2 + 1)
    for (i in hrp.indices) {
        ret[i] = hrp[i].code ushr 5
    }
    ret[hrp.length] = 0
    for (i in hrp.indices) {
        ret[hrp.length + 1 + i] = hrp[i].code and 31
    }
    return ret
}

private fun convertBits(
    data: IntArray,
    fromBits: Int,
    toBits: Int,
    pad: Boolean,
): ByteArray? {
    var acc = 0
    var bits = 0
    val maxv = (1 shl toBits) - 1
    val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
    val out = ArrayList<Byte>()
    for (value in data) {
        if (value < 0 || (value shr fromBits) != 0) {
            return null
        }
        acc = ((acc shl fromBits) or value) and maxAcc
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            out.add(((acc shr bits) and maxv).toByte())
        }
    }
    if (pad) {
        if (bits > 0) {
            out.add(((acc shl (toBits - bits)) and maxv).toByte())
        }
    } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
        return null
    }
    return out.toByteArray()
}

/**
 * Parsing helpers for reading age format fields.
 */
internal object UtilRead {
    @OptIn(ExperimentalEncodingApi::class)
    private val base64NoPad = Base64.Default.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * Decodes an unpadded base64 argument checking for exact expected byte length.
     *
     * @param arg input base64 string
     * @param expectedLength expected number of decoded bytes
     * @return decoded byte array if valid and matching expected length, null otherwise
     */
    fun base64Arg(
        arg: String,
        expectedLength: Int,
    ): ByteArray? =
        try {
            val decoded = base64NoPad.decode(arg)
            if (decoded.size == expectedLength) decoded else null
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Decodes an unpadded base64 argument byte slice checking for exact expected byte length.
     *
     * @param arg input bytes representing ASCII base64
     * @param expectedLength expected number of decoded bytes
     * @return decoded byte array if valid and matching expected length, null otherwise
     */
    fun base64Arg(
        arg: ByteArray,
        expectedLength: Int,
    ): ByteArray? =
        try {
            val decoded = base64NoPad.decode(arg)
            if (decoded.size == expectedLength) decoded else null
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Parses a decimal number composed only of digits with no leading zeros.
     *
     * @param arg string to parse
     * @return parsed long integer, or null if invalid or has leading zero
     */
    fun decimalDigitArg(arg: String): Long? {
        if (arg.isEmpty() || arg.startsWith('0') || !arg.all { it.isDigit() }) {
            return null
        }
        return arg.toLongOrNull()
    }

    /**
     * Parses a decimal integer composed only of digits with no leading zeros.
     *
     * @param arg string to parse
     * @return parsed integer, or null if invalid or has leading zero
     */
    fun decimalDigitArgInt(arg: String): Int? {
        if (arg.isEmpty() || arg.startsWith('0') || !arg.all { it.isDigit() }) {
            return null
        }
        return arg.toIntOrNull()
    }

    /**
     * Parses a decimal unsigned byte composed only of digits with no leading zeros.
     *
     * @param arg string to parse
     * @return parsed byte as UByte, or null if invalid or has leading zero
     */
    fun decimalDigitArgUByte(arg: String): UByte? {
        if (arg.isEmpty() || arg.startsWith('0') || !arg.all { it.isDigit() }) {
            return null
        }
        return arg.toUByteOrNull()
    }

    /**
     * Decodes a fixed-count encoded string slice.
     *
     * @param count raw payload byte count
     * @param input source string
     * @return pair of remaining input and decoded bytes, or null if input too short or invalid
     */
    fun encodedStr(
        count: Int,
        input: String,
    ): Pair<String, ByteArray>? {
        val encodedCount = ((4 * count) + 2) / 3
        if (input.length < encodedCount) return null
        val dataStr = input.substring(0, encodedCount)
        val remaining = input.substring(encodedCount)
        return try {
            val decoded = base64NoPad.decode(dataStr)
            Pair(remaining, decoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Decodes a base64 encoded sequence while characters match base64.
     *
     * @param input source string
     * @return pair of remaining input and decoded bytes, or null if invalid
     */
    fun strWhileEncoded(input: String): Pair<String, ByteArray>? {
        var end = 0
        while (end < input.length && isBase64Char(input[end])) {
            end++
        }
        if (end == 0) return null
        val dataStr = input.substring(0, end)
        val remaining = input.substring(end)
        return try {
            val decoded = base64NoPad.decode(dataStr)
            Pair(remaining, decoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Decodes a base64 encoded sequence wrapped across multiple lines.
     *
     * @param input source string
     * @return pair of remaining input and decoded bytes, or null if invalid
     */
    fun wrappedStrWhileEncoded(input: String): Pair<String, ByteArray>? {
        val lines = input.split("\r\n", "\n")
        val joined = StringBuilder()
        var lineCount = 0
        for (line in lines) {
            if (line.isEmpty() || !line.all { isBase64Char(it) }) break
            joined.append(line)
            lineCount++
        }
        if (joined.isEmpty()) return null
        val totalConsumed = lines.take(lineCount).joinToString("\n").length
        val remaining = if (totalConsumed < input.length) input.substring(totalConsumed) else ""
        return try {
            val decoded = base64NoPad.decode(joined.toString())
            Pair(remaining, decoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
}

/**
 * Serialization helpers for writing age format fields.
 */
internal object UtilWrite {
    @OptIn(ExperimentalEncodingApi::class)
    private val base64NoPad = Base64.Default.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * Encodes byte array as unpadded standard base64 string.
     *
     * @param data bytes to encode
     * @return unpadded base64 encoded string
     */
    fun encodedData(data: ByteArray): String = base64NoPad.encode(data)
}
