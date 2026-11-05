package com.phairplay.airplay.handshake

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * PlistCodec — encodes/decodes Apple binary property lists ("bplist00") used throughout
 * the AirPlay 2 handshake (GET /info, SETUP request/response bodies).
 *
 * WHY: macOS exchanges capability and stream-setup data as binary plists. This bridges
 * dd-plist's NSObject tree to plain Kotlin maps/lists/primitives so handlers work with
 * ordinary types instead of NSObject everywhere.
 *
 * WHY THE DECODER IS BOUNDED: the body of an RTSP request is network input that any
 * device in the local network may send — `POST /feedback` alone decodes a plist without
 * any pairing. The parser of the library walks the object graph **recursively**, so a
 * crafted plist with tens of thousands of nested dictionaries exhausts the stack of the
 * request thread. A `StackOverflowError` is an `Error` and not an `Exception`: it would
 * slip through every `catch (e: Exception)` in the request path and kill the thread
 * instead of refusing the request.
 *
 * The decoder therefore does two things:
 * - it refuses a body that is larger than [MAX_PLIST_BYTES] before the parser is
 *   started at all — the limit equals the RTSP message budget, so no legitimate
 *   request is affected,
 * - it turns **every** failure of the parse — a malformed plist, a stack overflow, a
 *   structure the library cannot represent — into an [IllegalArgumentException]. The
 *   caller then answers `400 Bad Request` and the connection thread stays alive.
 */
object PlistCodec {

    /**
     * Largest plist body the decoder accepts.
     *
     * WHY 1 MB: the RTSP reader refuses a message above 1 MB before the body is even
     * allocated, so a plist can never be larger than that in the AirPlay path. The
     * limit here is the second line of defence for a call site that forgets the budget.
     */
    const val MAX_PLIST_BYTES = 1024 * 1024

    /** Encodes a map to a binary plist. The result begins with the ASCII magic "bplist00". */
    fun encode(map: Map<String, Any?>): ByteArray =
        BinaryPropertyListWriter.writeToArray(toNs(map))

    /**
     * Encodes a map to an XML property list (`text/x-apple-plist+xml`). AirPlay video clients expect
     * the `GET /playback-info` body in this legacy XML form rather than binary.
     */
    fun encodeXml(map: Map<String, Any?>): ByteArray =
        toNs(map).toXMLPropertyList().toByteArray(Charsets.UTF_8)

    /**
     * Decodes a binary (or XML) plist into a String-keyed map.
     *
     * @throws IllegalArgumentException when the bytes are not a usable plist: the body
     *   is too large, the parser failed, the nesting exhausted the stack, or the root
     *   element is not a dictionary.
     */
    fun decode(bytes: ByteArray): Map<String, Any?> {
        require(bytes.size <= MAX_PLIST_BYTES) {
            "plist too large: ${bytes.size} bytes"
        }
        val root = try {
            PropertyListParser.parse(bytes)
        } catch (error: StackOverflowError) {
            // A nested plist is parsed recursively. The error is not an Exception, so
            // without this the request thread would die instead of refusing the body.
            throw IllegalArgumentException("plist nesting is too deep")
        } catch (error: Exception) {
            throw IllegalArgumentException("plist cannot be parsed: ${error.javaClass.simpleName}")
        }
        @Suppress("UNCHECKED_CAST")
        val decoded = try {
            fromNs(root)
        } catch (error: StackOverflowError) {
            // The same argument applies to the conversion into the Kotlin tree: it
            // recurses over the structure the parser just built.
            throw IllegalArgumentException("plist nesting is too deep")
        }
        return (decoded as? Map<String, Any?>)
            ?: throw IllegalArgumentException("plist root is not a dictionary")
    }

    private fun toNs(value: Any?): NSObject = when (value) {
        null -> NSString("")
        is NSObject -> value
        is String -> NSString(value)
        is Boolean -> NSNumber(value)
        is Int -> NSNumber(value.toLong())
        is Long -> NSNumber(value)
        is Double -> NSNumber(value)
        is ByteArray -> NSData(value)
        is Map<*, *> -> NSDictionary().apply {
            value.forEach { (k, v) -> put(k.toString(), toNs(v)) }
        }
        is List<*> -> NSArray(*value.map { toNs(it) }.toTypedArray())
        else -> throw IllegalArgumentException("Unsupported plist value type: ${value::class.java}")
    }

    private fun fromNs(obj: NSObject?): Any? = when (obj) {
        null -> null
        is NSDictionary -> obj.allKeys().associateWith { fromNs(obj.objectForKey(it)) }
        is NSArray -> obj.array.map { fromNs(it) }
        is NSData -> obj.bytes()
        is NSNumber -> when {
            obj.isBoolean -> obj.boolValue()
            obj.isInteger -> obj.longValue()
            else -> obj.doubleValue()
        }
        is NSString -> obj.content
        else -> obj.toString()
    }
}
