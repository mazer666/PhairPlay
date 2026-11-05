package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PlistCodecHardeningTest — the negative tests for the property list decoder.
 *
 * WHY: the body of an RTSP request is network input, and `POST /feedback` decodes a
 * plist **without any pairing** — PIN authentication is off by default, so any device
 * in the local network reaches this parser. The library walks the object graph
 * recursively, so a crafted plist with tens of thousands of nested dictionaries
 * exhausts the stack of the request thread. A `StackOverflowError` is an `Error` and
 * not an `Exception`: it slips through every `catch (e: Exception)` in the request
 * path and kills the thread instead of refusing the request.
 *
 * WHAT WE TEST:
 * - a body above the size limit is refused before the parser is started,
 * - a deeply nested plist is refused with an `IllegalArgumentException` — and not with
 *   a `StackOverflowError` that would escape the request path,
 * - a legitimate plist still decodes (the control that proves the rule does not break
 *   the handshake).
 */
class PlistCodecHardeningTest {

    @Test
    fun `a plist above the size limit is refused`() {
        val failure = runCatching {
            PlistCodec.decode(ByteArray(PlistCodec.MAX_PLIST_BYTES + 1))
        }.exceptionOrNull()

        assertNotNull("the oversized plist must be refused", failure)
        val cause = requireNotNull(failure)
        assertTrue(
            "the refusal must be an IllegalArgumentException, not a ${cause.javaClass.name}",
            cause is IllegalArgumentException
        )
    }

    @Test
    fun `a deeply nested plist is refused instead of exhausting the stack`() {
        // 80 000 nested dictionaries — the parser walks them recursively, and the
        // request thread of the receiver has a stack of about one megabyte.
        val bomb = nestedBinaryPlist(depth = 80_000)

        val failure = runCatching { PlistCodec.decode(bomb) }.exceptionOrNull()

        assertNotNull("the nested plist must be refused", failure)
        val cause = requireNotNull(failure)
        assertTrue(
            "a StackOverflowError must not escape the decoder, but a ${cause.javaClass.name} did",
            cause is IllegalArgumentException
        )
    }

    @Test
    fun `a legitimate plist still decodes`() {
        val decoded = PlistCodec.decode(PlistCodec.encode(mapOf("name" to "PhairPlay")))

        assertEquals("PhairPlay", decoded["name"])
    }

    /**
     * Builds a binary plist whose root is a chain of [depth] nested dictionaries.
     *
     * WHY the hand-written bytes: the library has no "write a deeply nested plist"
     * helper — and a chain that deep cannot be built with a map either, because the
     * encoder would recurse while it writes it. The bytes below follow the binary
     * plist format: the header `bplist00`, the objects, the offset table and the
     * 32-byte trailer.
     */
    private fun nestedBinaryPlist(depth: Int): ByteArray {
        val objectCount = depth + 2
        val refSize = refSizeFor(objectCount)
        val offsetSize = 4

        val objects = ArrayList<ByteArray>(objectCount)
        // Object 0: the key of every dictionary.
        objects += byteArrayOf(0x51, 'k'.code.toByte())
        // Objects 1..depth: the nested dictionaries, each pointing at the next one.
        for (index in 1..depth) {
            val dictionary = ByteArray(1 + 2 * refSize)
            dictionary[0] = 0xD1.toByte()
            writeRef(dictionary, 1, 0, refSize)
            writeRef(dictionary, 1 + refSize, index + 1, refSize)
            objects += dictionary
        }
        // The last object: the empty string the chain ends in.
        objects += byteArrayOf(0x50)

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objectCount)
        for (index in 0 until objectCount) {
            offsets[index] = out.size()
            out.write(objects[index])
        }
        val offsetTableStart = out.size()
        for (offset in offsets) {
            out.write(
                byteArrayOf(
                    ((offset ushr 24) and 0xFF).toByte(),
                    ((offset ushr 16) and 0xFF).toByte(),
                    ((offset ushr 8) and 0xFF).toByte(),
                    (offset and 0xFF).toByte()
                )
            )
        }
        // The trailer: five unused bytes, the sort version, the two integer sizes,
        // the number of objects, the root object and the offset of the offset table.
        out.write(ByteArray(5))
        out.write(0)
        out.write(offsetSize)
        out.write(refSize)
        out.write(longBytes(objectCount.toLong()))
        out.write(longBytes(1L))
        out.write(longBytes(offsetTableStart.toLong()))
        return out.toByteArray()
    }

    /** The number of bytes an object reference needs for [objectCount] objects. */
    private fun refSizeFor(objectCount: Int): Int {
        var size = 1
        var largest = 0xFF
        while (objectCount - 1 > largest) {
            size++
            largest = (largest shl 8) or 0xFF
        }
        return size
    }

    /** Writes [value] as a big-endian reference of [size] bytes at [offset]. */
    private fun writeRef(buffer: ByteArray, offset: Int, value: Int, size: Int) {
        // The binary plist format is big endian: the most significant byte comes first.
        for (index in 0 until size) {
            val shift = 8 * (size - 1 - index)
            buffer[offset + index] = ((value ushr shift) and 0xFF).toByte()
        }
    }

    /** Eight big-endian bytes. */
    private fun longBytes(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * (7 - index))) and 0xFF).toByte()
    }
}
