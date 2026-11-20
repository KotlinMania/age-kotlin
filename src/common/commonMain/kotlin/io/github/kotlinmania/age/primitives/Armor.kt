// port-lint: source primitives/armor.rs

package io.github.kotlinmania.age.primitives

import io.github.kotlinmania.age.LINE_ENDING
import io.github.kotlinmania.base64.DecodeSliceError
import io.github.kotlinmania.base64.prelude.BASE64_STANDARD
import io.github.kotlinmania.zeroize.zeroize

internal const val ARMORED_COLUMNS_PER_LINE: Int = 64
internal const val ARMORED_BYTES_PER_LINE: Int = ARMORED_COLUMNS_PER_LINE / 4 * 3
internal const val ARMORED_BEGIN_MARKER: String = "-----BEGIN AGE ENCRYPTED FILE-----"
internal const val ARMORED_END_MARKER: String = "-----END AGE ENCRYPTED FILE-----"

private const val MIN_ARMOR_LEN: Int = 36 // ARMORED_BEGIN_MARKER.length + 2
private const val MARKER_LEN: Int = MIN_ARMOR_LEN - 2
private const val BASE64_CHUNK_SIZE_COLUMNS: Int = 8 * 1024
private const val BASE64_CHUNK_SIZE_BYTES: Int = BASE64_CHUNK_SIZE_COLUMNS / 4 * 3
private const val LINE_BUFFER_CAPACITY: Int = 8 * 1024
private const val LINE_FLUSH_SLACK: Int = 1024
private const val DEFAULT_READ_BUF_CAPACITY: Int = 8 * 1024
private const val SEEK_SCRATCH_SIZE: Int = 4096

/**
 * Categories of I/O errors produced by the multiplatform armor streams.
 */
enum class IoErrorKind {
    InvalidData,
    UnexpectedEof,
    Interrupted,
    WriteZero,
    Other,
}

/**
 * Portable I/O error returned from armor reading, writing, and seeking operations.
 */
class IoError(
    val kind: IoErrorKind,
    override val message: String,
    val error: Any? = null,
) : Exception(message) {
    override fun toString(): String = message

    companion object {
        /** Constructs an [IoError] with [kind] and descriptive [message]. */
        fun new(
            kind: IoErrorKind,
            message: String,
        ): IoError = IoError(kind = kind, message = message)

        /** Constructs an [IoError] wrapping a domain [error] value. */
        fun new(
            kind: IoErrorKind,
            error: Any,
        ): IoError = IoError(kind = kind, message = error.toString(), error = error)
    }
}

/**
 * Enumeration of possible methods to seek within an I/O object.
 */
sealed class SeekFrom {
    /** Sets the offset to the provided number of bytes from the start of the stream. */
    data class Start(
        val offset: ULong,
    ) : SeekFrom()

    /** Sets the offset to the current position plus the specified signed number of bytes. */
    data class Current(
        val offset: Long,
    ) : SeekFrom()

    /** Sets the offset to the size of the stream plus the specified signed number of bytes. */
    data class End(
        val offset: Long,
    ) : SeekFrom()
}

/**
 * Readable byte source abstraction for age armor readers.
 */
interface Read {
    /** Reads up to [length] bytes into [buf] starting at [offset]. */
    fun read(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Result<Int>

    /** Reads the exact number of bytes required to fill `[offset, offset + length)` in [buf]. */
    fun readExact(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Result<Unit> {
        validateRange(buf.size, offset, length)
        var filled = 0
        while (filled < length) {
            val res = read(buf, offset + filled, length - filled)
            val err = res.exceptionOrNull()
            if (err != null) {
                if ((err as? IoError)?.kind == IoErrorKind.Interrupted) {
                    continue
                }
                return Result.failure(err)
            }
            val n = res.getOrThrow()
            if (n == 0) {
                return Result.failure(
                    IoError.new(IoErrorKind.UnexpectedEof, "failed to fill whole buffer"),
                )
            }
            filled += n
        }
        return Result.success(Unit)
    }

    /** Reads all remaining bytes until EOF and returns them as a [ByteArray]. */
    fun readToEnd(): Result<ByteArray> {
        val out = ByteVecWriter()
        val scratch = ByteArray(SEEK_SCRATCH_SIZE)
        while (true) {
            val res = read(scratch, 0, scratch.size)
            val err = res.exceptionOrNull()
            if (err != null) {
                if ((err as? IoError)?.kind == IoErrorKind.Interrupted) {
                    continue
                }
                return Result.failure(err)
            }
            val n = res.getOrThrow()
            if (n == 0) {
                break
            }
            out.writeAll(scratch, 0, n).getOrElse { return Result.failure(it) }
        }
        return Result.success(out.toByteArray())
    }
}

/**
 * Buffered byte source abstraction supporting line-oriented reading.
 */
interface BufRead : Read {
    /** Returns the internal buffer contents, refilling from the underlying reader if empty. */
    fun fillBuf(): Result<ByteArray>

    /** Marks [amt] bytes from the last [fillBuf] slice as having been read. */
    fun consume(amt: Int)

    /** Reads all bytes into a [ByteArray] until the delimiter [byte] or EOF is reached. */
    fun readUntil(byte: Byte): Result<ByteArray> {
        val out = ByteVecWriter()
        while (true) {
            val available =
                fillBuf().getOrElse { err ->
                    if ((err as? IoError)?.kind == IoErrorKind.Interrupted) {
                        ByteArray(0)
                        continue
                    }
                    return Result.failure(err)
                }
            if (available.isEmpty()) {
                break
            }
            val idx = available.indexOf(byte)
            val done = idx >= 0
            val used = if (done) idx + 1 else available.size
            out.writeAll(available, 0, used).getOrElse { return Result.failure(it) }
            consume(used)
            if (done) {
                break
            }
        }
        return Result.success(out.toByteArray())
    }

    /** Appends all bytes through the next `\n` (as UTF-8) into [buf], returning bytes read. */
    fun readLine(buf: StringBuilder): Result<Int> {
        val raw = readUntil('\n'.code.toByte()).getOrElse { return Result.failure(it) }
        val text =
            try {
                raw.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return Result.failure(
                    IoError.new(IoErrorKind.InvalidData, ArmoredReadError.InvalidUtf8),
                )
            }
        buf.append(text)
        return Result.success(raw.size)
    }
}

/**
 * Writable byte sink abstraction for age armor writers.
 */
interface Write {
    /** Writes up to [length] bytes from [buf] starting at [offset]. */
    fun write(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Result<Int>

    /** Flushes this output stream, ensuring all buffered contents reach their destination. */
    fun flush(): Result<Unit>

    /** Attempts to write the entire `[offset, offset + length)` range of [buf]. */
    fun writeAll(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Result<Unit> {
        validateRange(buf.size, offset, length)
        var pos = 0
        while (pos < length) {
            val res = write(buf, offset + pos, length - pos)
            val err = res.exceptionOrNull()
            if (err != null) {
                if ((err as? IoError)?.kind == IoErrorKind.Interrupted) {
                    continue
                }
                return Result.failure(err)
            }
            val n = res.getOrThrow()
            if (n == 0) {
                return Result.failure(
                    IoError.new(IoErrorKind.WriteZero, "failed to write whole buffer"),
                )
            }
            pos += n
        }
        return Result.success(Unit)
    }
}

/**
 * Seekable stream cursor abstraction.
 */
interface Seek {
    /** Seeks to an offset, in bytes, within this stream and returns the new position. */
    fun seek(pos: SeekFrom): Result<ULong>
}

/**
 * Buffered wrapper around any [Read] source, preserving [Seek] when [inner] is seekable.
 */
class BufReader<out R : Read>(
    private val inner: R,
    capacity: Int = DEFAULT_READ_BUF_CAPACITY,
) : BufRead,
    Seek {
    private val buf: ByteArray = ByteArray(capacity.coerceAtLeast(1))
    private var pos: Int = 0
    private var cap: Int = 0

    override fun fillBuf(): Result<ByteArray> {
        if (pos >= cap) {
            val n = inner.read(buf, 0, buf.size).getOrElse { return Result.failure(it) }
            pos = 0
            cap = n
        }
        return Result.success(buf.copyOfRange(pos, cap))
    }

    override fun consume(amt: Int) {
        val next = pos + amt
        check(next <= cap) { "cannot consume past buffered bytes" }
        pos = next
    }

    override fun read(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        if (pos >= cap && length >= this.buf.size) {
            pos = 0
            cap = 0
            return inner.read(buf, offset, length)
        }
        if (pos >= cap) {
            val n = inner.read(this.buf, 0, this.buf.size).getOrElse { return Result.failure(it) }
            pos = 0
            cap = n
        }
        val available = cap - pos
        val toRead = minOf(available, length)
        if (toRead > 0) {
            this.buf.copyInto(buf, offset, pos, pos + toRead)
            pos += toRead
        }
        return Result.success(toRead)
    }

    override fun seek(pos: SeekFrom): Result<ULong> {
        val seekable =
            inner as? Seek
                ?: return Result.failure(IoError.new(IoErrorKind.Other, "inner reader is not Seek"))
        val newPos =
            when (pos) {
                is SeekFrom.Current -> {
                    val remainder = (cap - this.pos).toLong()
                    seekable.seek(SeekFrom.Current(pos.offset - remainder))
                }

                is SeekFrom.Start,
                is SeekFrom.End,
                -> seekable.seek(pos)
            }.getOrElse { return Result.failure(it) }
        this.pos = 0
        this.cap = 0
        return Result.success(newPos)
    }

    companion object {
        /** Creates a new buffered reader wrapping [inner]. */
        fun <R : Read> new(inner: R): BufReader<R> = BufReader(inner)
    }
}

/**
 * In-memory [BufRead] and [Seek] cursor backed by a [ByteArray].
 */
class Cursor(
    private val data: ByteArray,
) : BufRead,
    Seek {
    private var pos: Int = 0

    override fun fillBuf(): Result<ByteArray> =
        if (pos >= data.size) {
            Result.success(ByteArray(0))
        } else {
            Result.success(data.copyOfRange(pos, data.size))
        }

    override fun consume(amt: Int) {
        val next = pos + amt
        check(next <= data.size) { "cannot consume past end of cursor" }
        pos = next
    }

    override fun read(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        if (pos >= data.size || length == 0) {
            return Result.success(0)
        }
        val toRead = minOf(data.size - pos, length)
        data.copyInto(buf, offset, pos, pos + toRead)
        pos += toRead
        return Result.success(toRead)
    }

    override fun seek(pos: SeekFrom): Result<ULong> {
        val target: Long =
            when (pos) {
                is SeekFrom.Start -> {
                    if (pos.offset > Int.MAX_VALUE.toULong()) {
                        return Result.failure(
                            IoError.new(IoErrorKind.InvalidData, "seek position overflow"),
                        )
                    }
                    pos.offset.toLong()
                }

                is SeekFrom.Current -> this.pos.toLong() + pos.offset

                is SeekFrom.End -> data.size.toLong() + pos.offset
            }
        if (target < 0L || target > Int.MAX_VALUE.toLong()) {
            return Result.failure(
                IoError.new(IoErrorKind.InvalidData, "invalid seek to a negative or overflowing position"),
            )
        }
        this.pos = target.toInt()
        return Result.success(target.toULong())
    }

    companion object {
        /** Creates a [Cursor] positioned at the start of [data]. */
        fun new(data: ByteArray): Cursor = Cursor(data)
    }
}

/**
 * In-memory growable [Write] sink backed by a dynamically resized [ByteArray].
 */
class ByteVecWriter(
    initialCapacity: Int = 256,
) : Write {
    private var storage: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
    private var len: Int = 0

    override fun write(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        if (length == 0) {
            return Result.success(0)
        }
        ensureCapacity(len + length)
        buf.copyInto(storage, len, offset, offset + length)
        len += length
        return Result.success(length)
    }

    override fun flush(): Result<Unit> = Result.success(Unit)

    /** Returns a copy of all written bytes. */
    fun toByteArray(): ByteArray = storage.copyOf(len)

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) {
            return
        }
        var nextCap = storage.size * 2
        while (nextCap < required) {
            nextCap *= 2
        }
        storage = storage.copyOf(nextCap)
    }
}

/**
 * Specifies the format that [ArmoredWriter] should apply to its output.
 */
sealed class Format {
    /** age binary format. */
    data object Binary : Format()

    /** ASCII armored format. */
    data object AsciiArmor : Format()
}

internal class LineEndingWriter<W : Write> private constructor(
    private val inner: W,
    private var buf: ByteArray = ByteArray(LINE_BUFFER_CAPACITY),
    private var bufLen: Int = 0,
    private var totalWritten: Int = 0,
) : Write {
    private fun appendBytes(
        src: ByteArray,
        offset: Int = 0,
        length: Int = src.size - offset,
    ) {
        if (length <= 0) {
            return
        }
        val required = bufLen + length
        if (required > buf.size) {
            var nextCap = buf.size * 2
            while (nextCap < required) {
                nextCap *= 2
            }
            buf = buf.copyOf(nextCap)
        }
        src.copyInto(buf, bufLen, offset, offset + length)
        bufLen += length
    }

    private fun flushBuffered(): Result<Unit> {
        inner.writeAll(buf, 0, bufLen).getOrElse { return Result.failure(it) }
        totalWritten += bufLen
        bufLen = 0
        return Result.success(Unit)
    }

    internal fun finish(): Result<W> {
        // Ensure all bytes have been written.
        flushBuffered().getOrElse { return Result.failure(it) }

        // Write the end marker
        inner.writeAll(LINE_ENDING_BYTES).getOrElse { return Result.failure(it) }
        inner.writeAll(END_MARKER_BYTES).getOrElse { return Result.failure(it) }
        inner.writeAll(LINE_ENDING_BYTES).getOrElse { return Result.failure(it) }

        return Result.success(inner)
    }

    override fun write(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        if (length == 0) {
            return Result.success(0)
        }
        var pos = 0
        while (pos < length) {
            val remaining = ARMORED_COLUMNS_PER_LINE - (totalWritten % ARMORED_COLUMNS_PER_LINE)

            // Write the next newline if we are at the end of the line.
            if (remaining == ARMORED_COLUMNS_PER_LINE && totalWritten > 0) {
                appendBytes(LINE_ENDING_BYTES)
            }
            val toWrite = minOf(remaining, length - pos)

            appendBytes(buf, offset + pos, toWrite)
            pos += toWrite
            totalWritten += toWrite
        }

        // Write the buffer to the inner writer, and drop the written bytes. We trigger
        // this when we are close to the buffer's capacity, to avoid reallocation.
        if (bufLen + LINE_FLUSH_SLACK > LINE_BUFFER_CAPACITY) {
            val innerWritten = inner.write(this.buf, 0, bufLen).getOrElse { return Result.failure(it) }
            if (innerWritten in 1 until bufLen) {
                this.buf.copyInto(this.buf, 0, innerWritten, bufLen)
            }
            bufLen -= innerWritten
        }

        // We always return the number of bytes we consumed, not how many we actually
        // wrote to the inner writer. Any discrepancy is handled in flush().
        return Result.success(length)
    }

    override fun flush(): Result<Unit> {
        flushBuffered().getOrElse { return Result.failure(it) }
        return inner.flush()
    }

    companion object {
        private val BEGIN_MARKER_BYTES: ByteArray = ARMORED_BEGIN_MARKER.encodeToByteArray()
        private val END_MARKER_BYTES: ByteArray = ARMORED_END_MARKER.encodeToByteArray()
        private val LINE_ENDING_BYTES: ByteArray = LINE_ENDING.encodeToByteArray()

        internal fun <W : Write> new(inner: W): Result<LineEndingWriter<W>> {
            // Write the begin marker
            inner.writeAll(BEGIN_MARKER_BYTES).getOrElse { return Result.failure(it) }
            inner.writeAll(LINE_ENDING_BYTES).getOrElse { return Result.failure(it) }
            return Result.success(LineEndingWriter(inner = inner))
        }
    }
}

private sealed class ArmorIs<out W : Write> {
    data class Enabled<W : Write>(
        val inner: LineEndingWriter<W>,
        val byteBuf: ByteArray = ByteArray(BASE64_CHUNK_SIZE_BYTES),
        var byteLen: Int = 0,
        val encodedBuf: ByteArray = ByteArray(BASE64_CHUNK_SIZE_COLUMNS),
    ) : ArmorIs<W>()

    data class Disabled<W : Write>(
        val inner: W,
    ) : ArmorIs<W>()
}

/**
 * Writer that optionally applies the age ASCII armor format.
 */
class ArmoredWriter<W : Write> private constructor(
    private val state: ArmorIs<W>,
) : Write {
    /**
     * Writes the end marker of the age file, if armoring was enabled.
     *
     * You **MUST** call [finish] when you are done writing, in order to finish the
     * armoring process. Failing to call [finish] will result in a truncated file
     * that will fail to decrypt.
     */
    fun finish(): Result<W> =
        when (state) {
            is ArmorIs.Enabled -> {
                val slice = state.byteBuf.copyOf(state.byteLen)
                val encoded =
                    BASE64_STANDARD
                        .encodeSlice(slice, state.encodedBuf)
                        .getOrElse { err ->
                            return Result.failure(
                                IoError.new(IoErrorKind.Other, err.toString()),
                            )
                        }
                state.inner.writeAll(state.encodedBuf, 0, encoded).getOrElse { return Result.failure(it) }
                state.inner.finish()
            }

            is ArmorIs.Disabled -> {
                Result.success(state.inner)
            }
        }

    override fun write(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        return when (state) {
            is ArmorIs.Enabled -> {
                var pos = 0
                while (true) {
                    val remainingInput = length - pos
                    val toWrite = minOf(BASE64_CHUNK_SIZE_BYTES - state.byteLen, remainingInput)

                    if (toWrite > 0) {
                        buf.copyInto(state.byteBuf, state.byteLen, offset + pos, offset + pos + toWrite)
                        state.byteLen += toWrite
                        pos += toWrite
                    }

                    // At this point, either remainingInput == 0, or we have a full chunk.
                    val left = length - pos
                    check(left == 0 || state.byteLen == BASE64_CHUNK_SIZE_BYTES)

                    // Only encode the chunk if we have more data to write, as the last
                    // (possibly-partial) line must be written in finish().
                    if (left == 0) {
                        break
                    } else {
                        val encoded =
                            BASE64_STANDARD
                                .encodeSlice(state.byteBuf, state.encodedBuf)
                                .getOrElse { err ->
                                    return Result.failure(
                                        IoError.new(IoErrorKind.Other, err.toString()),
                                    )
                                }
                        check(encoded == BASE64_CHUNK_SIZE_COLUMNS)
                        state.inner.writeAll(state.encodedBuf, 0, encoded).getOrElse {
                            return Result.failure(it)
                        }
                        state.byteLen = 0
                    }
                }
                Result.success(pos)
            }

            is ArmorIs.Disabled -> {
                state.inner.write(buf, offset, length)
            }
        }
    }

    override fun flush(): Result<Unit> =
        when (state) {
            is ArmorIs.Enabled -> state.inner.flush()
            is ArmorIs.Disabled -> state.inner.flush()
        }

    companion object {
        /** Wraps the given [output] in an [ArmoredWriter] applying [format]. */
        fun <W : Write> wrapOutput(
            output: W,
            format: Format,
        ): Result<ArmoredWriter<W>> =
            when (format) {
                Format.AsciiArmor -> {
                    LineEndingWriter.new(output).map { w ->
                        ArmoredWriter(ArmorIs.Enabled(inner = w))
                    }
                }

                Format.Binary -> {
                    Result.success(ArmoredWriter(ArmorIs.Disabled(inner = output)))
                }
            }
    }
}

/**
 * The various errors that can be returned while parsing the armored format.
 */
sealed class ArmoredReadError {
    /** An error occurred while parsing Base64. */
    data class Base64(
        val error: DecodeSliceError,
    ) : ArmoredReadError()

    /** The begin marker for the armor is invalid. */
    data object InvalidBeginMarker : ArmoredReadError()

    /** Invalid UTF-8 characters were encountered between the begin and end marker. */
    data object InvalidUtf8 : ArmoredReadError()

    /** A line of the armor contains a `\r` character. */
    data object LineContainsCr : ArmoredReadError()

    /** The final Base64 line is non-canonical. */
    data object MissingPadding : ArmoredReadError()

    /** The armor is not wrapped at 64 characters. */
    data object NotWrappedAt64Chars : ArmoredReadError()

    /** There is a short line in the middle of the armor (only the final line may be short). */
    data object ShortLineInMiddle : ArmoredReadError()

    /** There are trailing non-whitespace characters after the end marker. */
    data object TrailingGarbage : ArmoredReadError()

    /** Formats this error message cleanly for display. */
    fun fmt(): String =
        when (this) {
            is Base64 -> error.toString()
            InvalidBeginMarker -> "invalid armor begin marker"
            InvalidUtf8 -> "stream did not contain valid UTF-8"
            LineContainsCr -> "line contains CR"
            MissingPadding -> "invalid armor (last line is missing padding)"
            NotWrappedAt64Chars -> "invalid armor (not wrapped at 64 characters)"
            ShortLineInMiddle -> "invalid armor (short line in middle of encoding)"
            TrailingGarbage -> "invalid armor (non-whitespace characters after end marker)"
        }

    final override fun toString(): String = fmt()
}

private sealed class StartPos {
    data class Implicit(
        var offset: ULong,
    ) : StartPos()

    data class Explicit(
        val start: ULong,
    ) : StartPos()
}

/**
 * Reader that will parse the age ASCII armor format if detected.
 */
class ArmoredReader<R : BufRead> private constructor(
    internal val inner: R,
) : BufRead,
    AutoCloseable {
    private var start: StartPos = StartPos.Implicit(0uL)
    private var isArmored: Boolean? = null
    private val lineBuf: StringBuilder = StringBuilder(ARMORED_COLUMNS_PER_LINE + 2)
    private val byteBuf: ByteArray = ByteArray(ARMORED_BYTES_PER_LINE)
    private var byteStart: Int = ARMORED_BYTES_PER_LINE
    private var byteEnd: Int = ARMORED_BYTES_PER_LINE
    private var foundShortLine: Boolean = false
    private var foundEnd: Boolean = false
    private var dataLen: ULong? = null
    private var dataRead: Int = 0

    private fun countReaderBytes(read: Int): Int {
        val currentStart = start
        if (currentStart is StartPos.Implicit) {
            currentStart.offset += read.toULong()
        }
        return read
    }

    private fun detectArmor(): Result<Unit> {
        if (isArmored != null) {
            error("ArmoredReader.detectArmor() called twice")
        }

        val armored = BEGIN_MARKER_BYTES.indices.all { byteBuf[it] == BEGIN_MARKER_BYTES[it] }
        if (armored) {
            val firstPostMarker = byteBuf[MARKER_LEN]
            val secondPostMarker = byteBuf[MARKER_LEN + 1]
            when {
                firstPostMarker == '\n'.code.toByte() -> {
                    val extra =
                        try {
                            byteBuf.copyOfRange(MARKER_LEN + 1, MIN_ARMOR_LEN).decodeToString(
                                throwOnInvalidSequence = true,
                            )
                        } catch (_: CharacterCodingException) {
                            return Result.failure(
                                IoError.new(IoErrorKind.InvalidData, ArmoredReadError.InvalidUtf8),
                            )
                        }
                    lineBuf.append(extra)
                    countReaderBytes(1)
                }

                firstPostMarker == '\r'.code.toByte() && secondPostMarker == '\n'.code.toByte() -> {
                    // CRLF completes the exact MIN_ARMOR_LEN header probe.
                }

                else -> {
                    return Result.failure(
                        IoError.new(IoErrorKind.InvalidData, ArmoredReadError.InvalidBeginMarker),
                    )
                }
            }
        } else {
            byteStart = 0
            byteEnd = MIN_ARMOR_LEN
            countReaderBytes(MIN_ARMOR_LEN)
        }

        isArmored = armored
        return Result.success(Unit)
    }

    private fun parseArmorLine(): Result<Boolean> {
        val rawLine = lineBuf.toString()
        val line =
            when {
                rawLine.endsWith("\r\n") -> rawLine.removeSuffix("\r\n")
                rawLine.endsWith('\n') -> rawLine.removeSuffix("\n")
                else -> rawLine
            }
        if (line.contains('\r')) {
            return Result.failure(
                IoError.new(IoErrorKind.InvalidData, ArmoredReadError.LineContainsCr),
            )
        }

        if (line == ARMORED_END_MARKER) {
            foundEnd = true
            return Result.success(true)
        }

        when {
            !foundShortLine && line.length == ARMORED_COLUMNS_PER_LINE -> Unit

            !foundShortLine && line.length % 4 != 0 -> {
                return Result.failure(
                    IoError.new(IoErrorKind.InvalidData, ArmoredReadError.MissingPadding),
                )
            }

            !foundShortLine && line.length < ARMORED_COLUMNS_PER_LINE -> {
                foundShortLine = true
            }

            foundShortLine && line.length == ARMORED_COLUMNS_PER_LINE -> {
                return Result.failure(
                    IoError.new(IoErrorKind.InvalidData, ArmoredReadError.ShortLineInMiddle),
                )
            }

            else -> {
                return Result.failure(
                    IoError.new(IoErrorKind.InvalidData, ArmoredReadError.NotWrappedAt64Chars),
                )
            }
        }

        byteStart = 0
        byteEnd =
            BASE64_STANDARD
                .decodeSlice(line.encodeToByteArray(), byteBuf)
                .getOrElse { err ->
                    val sliceErr =
                        err as? DecodeSliceError
                            ?: DecodeSliceError.fromDecodeError(
                                io.github.kotlinmania.base64.DecodeError.InvalidPadding,
                            )
                    return Result.failure(
                        IoError.new(IoErrorKind.InvalidData, ArmoredReadError.Base64(sliceErr)),
                    )
                }

        lineBuf.zeroize()
        return Result.success(false)
    }

    private fun readNextArmorLine(): Result<Boolean> {
        check(isArmored == true)
        val read = inner.readLine(lineBuf).getOrElse { return Result.failure(it) }
        countReaderBytes(read)

        if (parseArmorLine().getOrElse { return Result.failure(it) }) {
            while (true) {
                val buf = inner.fillBuf().getOrElse { return Result.failure(it) }
                if (buf.isEmpty()) {
                    break
                }
                if (buf.any { !isAsciiWhitespace(it) }) {
                    return Result.failure(
                        IoError.new(IoErrorKind.InvalidData, ArmoredReadError.TrailingGarbage),
                    )
                }
                inner.consume(buf.size)
            }
            return Result.success(true)
        }
        return Result.success(false)
    }

    override fun fillBuf(): Result<ByteArray> {
        while (true) {
            when (isArmored) {
                null -> {
                    inner.readExact(byteBuf, 0, MIN_ARMOR_LEN).getOrElse { return Result.failure(it) }
                    detectArmor().getOrElse { return Result.failure(it) }
                }

                false -> {
                    if (byteStart >= byteEnd) {
                        val read = inner.read(byteBuf, 0, byteBuf.size).getOrElse { return Result.failure(it) }
                        byteStart = 0
                        byteEnd = read
                        countReaderBytes(read)
                    }
                    return Result.success(byteBuf.copyOfRange(byteStart, byteEnd))
                }

                true -> {
                    if (foundEnd) {
                        return Result.success(ByteArray(0))
                    }
                    if (byteStart >= byteEnd) {
                        if (readNextArmorLine().getOrElse { return Result.failure(it) }) {
                            return Result.success(ByteArray(0))
                        }
                    }
                    return Result.success(byteBuf.copyOfRange(byteStart, byteEnd))
                }
            }
        }
    }

    override fun consume(amt: Int) {
        byteStart += amt
        dataRead += amt
        check(byteStart <= byteEnd)
    }

    override fun read(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): Result<Int> {
        validateRange(buf.size, offset, length)
        var pos = 0
        while (pos < length) {
            val next = fillBuf().getOrElse { return Result.failure(it) }
            if (next.isEmpty()) {
                break
            }
            val read = minOf(next.size, length - pos)
            next.copyInto(buf, offset + pos, 0, read)
            consume(read)
            pos += read
        }
        return Result.success(pos)
    }

    internal fun ensureHeaderDetected(): Result<Boolean> {
        while (true) {
            val currentArmored = isArmored
            if (currentArmored != null) {
                return Result.success(currentArmored)
            }
            inner.readExact(byteBuf, 0, MIN_ARMOR_LEN).getOrElse { return Result.failure(it) }
            detectArmor().getOrElse { return Result.failure(it) }
        }
    }

    internal fun startOffsetFromCurrent(currentPos: ULong): ULong =
        when (val s = start) {
            is StartPos.Implicit -> {
                val resolved = currentPos - s.offset
                start = StartPos.Explicit(resolved)
                resolved
            }

            is StartPos.Explicit -> s.start
        }

    internal fun currentDataLen(computeEnd: () -> Result<ULong>): Result<ULong> {
        val cached = dataLen
        if (cached != null) {
            return Result.success(cached)
        }
        val computed = computeEnd().getOrElse { return Result.failure(it) }
        dataLen = computed
        return Result.success(computed)
    }

    internal fun readDataCount(): Int = dataRead

    internal fun resetBinarySeekState(pos: Int) {
        byteStart = 0
        byteEnd = 0
        dataRead = pos
    }

    internal fun resetArmorSeekState() {
        lineBuf.zeroize()
        byteStart = ARMORED_BYTES_PER_LINE
        byteEnd = ARMORED_BYTES_PER_LINE
        foundShortLine = false
        foundEnd = false
        dataRead = 0
    }

    override fun close() {
        lineBuf.zeroize()
        byteBuf.zeroize()
    }

    companion object {
        private val BEGIN_MARKER_BYTES: ByteArray = ARMORED_BEGIN_MARKER.encodeToByteArray()

        /** Wraps a [Read] source in a buffered [ArmoredReader]. */
        fun <R : Read> new(reader: R): ArmoredReader<BufReader<R>> = withBuffered(BufReader.new(reader))

        /** Wraps an already-buffered [BufRead] source in an [ArmoredReader]. */
        fun <R : BufRead> newBuffered(reader: R): ArmoredReader<R> = withBuffered(reader)

        internal fun <R : BufRead> withBuffered(inner: R): ArmoredReader<R> = ArmoredReader(inner)
    }
}

internal fun <R> ArmoredReader<R>.start(): Result<ULong> where R : BufRead, R : Seek {
    val current = inner.seek(SeekFrom.Current(0)).getOrElse { return Result.failure(it) }
    return Result.success(startOffsetFromCurrent(current))
}

/**
 * Seeks to a position within the unarmored plaintext data exposed by an [ArmoredReader].
 */
fun <R> ArmoredReader<R>.seek(pos: SeekFrom): Result<ULong> where R : BufRead, R : Seek {
    val armored = ensureHeaderDetected().getOrElse { return Result.failure(it) }
    val startPos = start().getOrElse { return Result.failure(it) }
    val targetPos =
        when (pos) {
            is SeekFrom.Start -> pos.offset

            is SeekFrom.Current -> {
                val res = readDataCount().toLong() + pos.offset
                if (res >= 0L) {
                    res.toULong()
                } else {
                    return Result.failure(
                        IoError.new(IoErrorKind.InvalidData, "cannot seek before the start"),
                    )
                }
            }

            is SeekFrom.End -> {
                val len =
                    currentDataLen {
                        val buf = ByteArray(SEEK_SCRATCH_SIZE)
                        while (true) {
                            val n = read(buf, 0, buf.size).getOrElse { return@currentDataLen Result.failure(it) }
                            if (n == 0) {
                                break
                            }
                        }
                        Result.success(readDataCount().toULong())
                    }.getOrElse { return Result.failure(it) }
                val res = len.toLong() + pos.offset
                if (res >= 0L) {
                    res.toULong()
                } else {
                    return Result.failure(
                        IoError.new(IoErrorKind.InvalidData, "cannot seek before the start"),
                    )
                }
            }
        }

    if (!armored) {
        inner.seek(SeekFrom.Start(startPos + targetPos)).getOrElse { return Result.failure(it) }
        resetBinarySeekState(targetPos.toInt())
        return Result.success(readDataCount().toULong())
    }

    inner.seek(SeekFrom.Start(startPos)).getOrElse { return Result.failure(it) }
    resetArmorSeekState()

    val buf = ByteArray(SEEK_SCRATCH_SIZE)
    var toRead = targetPos.toInt()
    while (toRead > buf.size) {
        readExact(buf, 0, buf.size).getOrElse { return Result.failure(it) }
        toRead -= buf.size
    }
    if (toRead > 0) {
        readExact(buf, 0, toRead).getOrElse { return Result.failure(it) }
    }

    return Result.success(targetPos)
}

/**
 * Outcome of decoding potentially armored age bytes.
 */
class ArmoredDecodeResult(
    val data: ByteArray?,
    val error: ArmoredReadError?,
    val errorMessage: String?,
) {
    /** True when decoding succeeded without error. */
    val isSuccess: Boolean
        get() = data != null && error == null
}

/**
 * Encodes [data] using [ArmoredWriter] with [format].
 */
fun armorEncode(
    data: ByteArray,
    format: Format,
): ByteArray {
    val writer = ArmoredWriter.wrapOutput(ByteVecWriter(), format).getOrThrow()
    writer.writeAll(data, 0, data.size).getOrThrow()
    return writer.finish().getOrThrow().toByteArray()
}

/**
 * Decodes [armored] bytes using [ArmoredReader].
 */
fun armorDecode(armored: ByteArray): ArmoredDecodeResult {
    val reader = ArmoredReader.new(Cursor.new(armored))
    val result = reader.readToEnd()
    reader.close()
    val err = result.exceptionOrNull()
    if (err != null) {
        val armorErr = (err as? IoError)?.error as? ArmoredReadError
        return ArmoredDecodeResult(
            data = null,
            error = armorErr,
            errorMessage = err.message ?: err.toString(),
        )
    }
    return ArmoredDecodeResult(
        data = result.getOrThrow(),
        error = null,
        errorMessage = null,
    )
}

private fun validateRange(
    size: Int,
    offset: Int,
    length: Int,
) {
    require(offset >= 0 && length >= 0 && offset + length <= size) {
        "slice range [$offset, ${offset + length}) out of bounds for size $size"
    }
}

private fun isAsciiWhitespace(b: Byte): Boolean =
    b == 0x20.toByte() ||
        b == 0x09.toByte() ||
        b == 0x0A.toByte() ||
        b == 0x0C.toByte() ||
        b == 0x0D.toByte()
