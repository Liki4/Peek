package io.github.liki4.peek.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Schema-less protobuf encode + decode for the Keep wire layer.
 *
 * The bike's `.proto` definitions are packed inside `libdatajar.so` and not
 * accessible. Field numbers are known empirically (per PROTOCOL.md §5); we
 * encode/decode by field number with the standard wire types.
 */
object Protobuf {

    private const val TAG = "Protobuf"

    // ============================== ENCODING ==============================

    fun varint(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = n
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    fun varintField(fieldNum: Int, n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNum.toLong() shl 3))) // wire type 0
        out.write(varint(n))
        return out.toByteArray()
    }

    fun varintField(fieldNum: Int, n: Int): ByteArray = varintField(fieldNum, n.toLong())

    fun stringField(fieldNum: Int, s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNum.toLong() shl 3) or 2L))
        out.write(varint(raw.size.toLong()))
        out.write(raw)
        return out.toByteArray()
    }

    fun bytesField(fieldNum: Int, b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNum.toLong() shl 3) or 2L))
        out.write(varint(b.size.toLong()))
        out.write(b)
        return out.toByteArray()
    }

    fun fixed32FloatField(fieldNum: Int, f: Float): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNum.toLong() shl 3) or 5L))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array())
        return out.toByteArray()
    }

    // ============================== DECODING ==============================

    /**
     * Schema-less decode. Returns `Map<fieldNum, Any>` where `Any` is one of:
     *   - `Long` (varint, fixed64)
     *   - `Float` (fixed32)
     *   - `ByteArray` (length-delimited, non-printable) OR `String` (printable ASCII)
     *
     * For repeated fields, only the LAST occurrence is kept (matches the Python
     * reference). Callers that need all repetitions (e.g. parsing
     * TrainLogSegmentListMessage's `segments`) should walk the bytes manually
     * via [readVarintAt].
     */
    fun decode(b: ByteArray): Map<Int, Any> {
        val out = LinkedHashMap<Int, Any>()
        var pos = 0
        try {
            while (pos < b.size) {
                val (tag, after) = readVarintAt(b, pos)
                pos = after
                val fnum = (tag ushr 3).toInt()
                val wtype = (tag and 7L).toInt()
                when (wtype) {
                    0 -> {
                        val (v, p) = readVarintAt(b, pos); pos = p
                        out[fnum] = v
                    }
                    1 -> {
                        val v = ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
                        pos += 8
                        out[fnum] = v
                    }
                    2 -> {
                        val (ln, p) = readVarintAt(b, pos); pos = p
                        val sub = b.copyOfRange(pos, pos + ln.toInt())
                        pos += ln.toInt()
                        out[fnum] = if (sub.isNotEmpty() && sub.all { (it.toInt() and 0xFF) in 0x20..0x7E }) {
                            String(sub, Charsets.US_ASCII)
                        } else {
                            sub
                        }
                    }
                    5 -> {
                        val v = ByteBuffer.wrap(b, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
                        pos += 4
                        out[fnum] = v
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            // Partial decode — log the position and total size so corrupted
            // payloads leave a trace instead of silently producing incomplete data.
            Log.w(TAG, "partial protobuf decode at pos $pos of ${b.size} bytes: ${e.message}")
        }
        return out
    }

    fun readVarintAt(b: ByteArray, posStart: Int): Pair<Long, Int> {
        var pos = posStart
        var r = 0L
        var s = 0
        while (pos < b.size) {
            val x = b[pos].toInt() and 0xFF
            pos++
            r = r or ((x and 0x7F).toLong() shl s)
            if ((x and 0x80) == 0) return r to pos
            s += 7
        }
        throw IllegalStateException("truncated varint")
    }
}
