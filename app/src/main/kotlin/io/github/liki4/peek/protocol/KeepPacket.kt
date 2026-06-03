package io.github.liki4.peek.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the 20-byte header + body + CRC for one outbound Keep BLE packet.
 *
 * Holds per-direction monotonic counters (packetCount, seq, subSeq) that must
 * persist across calls; reset on disconnect by reinstantiating.
 *
 * @see PROTOCOL.md §3 for the byte layout.
 */
class KeepPacket(initialSeq: Int = 0x3678) {
    private var packetCount = 0
    private var seq = initialSeq and 0xFFFF
    private var subSeq = 0

    companion object {
        // b18 byte (offset 18) — empirical mapping from BTSnoop:
        //   plain GET (method 0x01, body=route only)          → b18=0x01
        //   PUT (method 0x03, body=route+0xff+payload)        → b18=0x02
        //   OBSERVE (method 0x01, body=\x00\x55+route, op=0x61) → b18=0x04
        const val B18_PLAIN_GET = 0x01
        const val B18_PUT = 0x02
        const val B18_OBSERVE = 0x04

        // OBSERVE channel uses a fixed sub_seq (NOT the incrementing counter).
        // The bike rejects subscriptions whose sub_seq isn't 0x65 (101 decimal).
        const val OBSERVE_SUB_SEQ = 0x65
    }

    /** The subSeq that the next [build] call will use (before any OBSERVE override). */
    fun peekNextSub(): Int = subSeq

    /**
     * Build an outbound packet.
     *
     * @return `(rawBytes, subSeqUsed)`. subSeqUsed is the request id callers
     * can match against the response's `subSeq`. For OBSERVE this is always
     * [OBSERVE_SUB_SEQ] (auto-detected from body prefix `\x00\x55`).
     */
    fun build(
        src: Int = Constants.PHONE_EP,
        dst: Int = Constants.BIKE_EP,
        method: Int,
        opcode: Int,
        body: ByteArray,
        b18Override: Int? = null,
        subSeqOverride: Int? = null,
    ): Pair<ByteArray, Int> {
        val nB3 = packetCount and 0xFF
        val nSeq = seq and 0xFFFF
        var nSub = subSeq
        packetCount++
        seq++
        subSeq++

        val isObserve = body.size >= 2 && body[0] == 0x00.toByte() && body[1] == 0x55.toByte()
        nSub = subSeqOverride ?: if (isObserve) OBSERVE_SUB_SEQ else nSub

        val b18 = b18Override ?: when {
            isObserve -> B18_OBSERVE
            method == KirinMethod.PUT.v -> B18_PUT
            else -> B18_PLAIN_GET
        }

        val length = 14 + body.size

        val total = 20 + body.size + 2
        val buf = ByteBuffer.allocate(total)

        buf.put(0xA5.toByte())                                  // magic
        buf.put(0xA5.toByte())
        buf.put(0xA0.toByte())                                  // framing
        buf.put((nB3 and 0xFF).toByte())                        // packet counter
        // length LE
        buf.put((length and 0xFF).toByte()); buf.put((length ushr 8 and 0xFF).toByte())
        // src BE
        buf.put((src ushr 8 and 0xFF).toByte()); buf.put((src and 0xFF).toByte())
        // dst BE
        buf.put((dst ushr 8 and 0xFF).toByte()); buf.put((dst and 0xFF).toByte())
        buf.put(0x55.toByte())                                  // constant
        buf.put((method and 0xFF).toByte())                     // method
        // seq BE
        buf.put((nSeq ushr 8 and 0xFF).toByte()); buf.put((nSeq and 0xFF).toByte())
        // sub-seq LE (uint32)
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(nSub).order(ByteOrder.BIG_ENDIAN)
        buf.put((b18 and 0xFF).toByte())                        // b18
        buf.put((opcode and 0xFF).toByte())                     // opcode
        buf.put(body)

        val withoutCrc = buf.array().copyOfRange(0, buf.position())
        val crc = Crc16.xmodem(withoutCrc)
        buf.put((crc and 0xFF).toByte()); buf.put((crc ushr 8 and 0xFF).toByte())

        return buf.array().copyOfRange(0, buf.position()) to nSub
    }
}

// ============================== BODY BUILDERS ==============================

object BodyBuilder {
    fun get(route: String): ByteArray = route.toByteArray(Charsets.US_ASCII)

    fun put(route: String, payload: ByteArray): ByteArray {
        val routeBytes = route.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(routeBytes.size + 1 + payload.size)
        System.arraycopy(routeBytes, 0, out, 0, routeBytes.size)
        out[routeBytes.size] = 0xFF.toByte()
        System.arraycopy(payload, 0, out, routeBytes.size + 1, payload.size)
        return out
    }

    fun observe(route: String): ByteArray {
        val routeBytes = route.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(2 + routeBytes.size)
        out[0] = 0x00; out[1] = 0x55
        System.arraycopy(routeBytes, 0, out, 2, routeBytes.size)
        return out
    }
}

// ============================== INBOUND PARSE ==============================

data class KeepFrame(
    val raw: ByteArray,
    val len: Int,
    val b3: Int,
    val pktLen: Int,
    val src: Int,
    val dst: Int,
    val b10: Int,
    val method: Int,
    val seq: Int,
    val subSeq: Int,
    val b18: Int,
    val opcode: Int?,            // null for short-ACK
    val crcRecv: Int,
    val crcCalc: Int,
    val crcOk: Boolean,
    val wrap: Wrap,
    val route: String,
    val payload: ByteArray,
    val error: String? = null,
) {
    enum class Wrap { NORMAL, OBSERVE, SHORT_ACK, INVALID }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

fun parsePacket(pkt: ByteArray): KeepFrame {
    if (pkt.size < 6 || pkt[0] != 0xA5.toByte() || pkt[1] != 0xA5.toByte()) {
        return KeepFrame(
            raw = pkt, len = pkt.size, b3 = 0, pktLen = 0, src = 0, dst = 0, b10 = 0,
            method = 0, seq = 0, subSeq = 0, b18 = 0, opcode = null,
            crcRecv = 0, crcCalc = 0, crcOk = false, wrap = KeepFrame.Wrap.INVALID,
            route = "", payload = ByteArray(0),
            error = "not a keep packet (no a5a5 magic or too short)",
        )
    }
    val b3 = pkt[3].toInt() and 0xFF
    val pktLen = (pkt[4].toInt() and 0xFF) or ((pkt[5].toInt() and 0xFF) shl 8)
    val expectedTotal = 6 + pktLen + 2
    if (expectedTotal < 21 || pkt.size != expectedTotal) {
        return KeepFrame(
            raw = pkt, len = pkt.size, b3 = b3, pktLen = pktLen,
            src = 0, dst = 0, b10 = 0, method = 0, seq = 0, subSeq = 0, b18 = 0, opcode = null,
            crcRecv = 0, crcCalc = 0, crcOk = false, wrap = KeepFrame.Wrap.INVALID,
            route = "", payload = ByteArray(0),
            error = "length mismatch (declared $pktLen → total $expectedTotal, got ${pkt.size})",
        )
    }

    val src = ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF)
    val dst = ((pkt[8].toInt() and 0xFF) shl 8) or (pkt[9].toInt() and 0xFF)
    val b10 = pkt[10].toInt() and 0xFF
    val method = pkt[11].toInt() and 0xFF
    val seq = ((pkt[12].toInt() and 0xFF) shl 8) or (pkt[13].toInt() and 0xFF)
    val subSeq = (pkt[14].toInt() and 0xFF) or
        ((pkt[15].toInt() and 0xFF) shl 8) or
        ((pkt[16].toInt() and 0xFF) shl 16) or
        ((pkt[17].toInt() and 0xFF) shl 24)
    val b18 = pkt[18].toInt() and 0xFF
    val crcRecv = (pkt[pkt.size - 2].toInt() and 0xFF) or ((pkt[pkt.size - 1].toInt() and 0xFF) shl 8)
    val crcCalc = Crc16.xmodem(pkt.copyOfRange(0, pkt.size - 2))
    val crcOk = crcRecv == crcCalc

    if (pktLen <= 13) {
        // Short ACK — no opcode byte, no body
        return KeepFrame(
            raw = pkt, len = pkt.size, b3 = b3, pktLen = pktLen, src = src, dst = dst,
            b10 = b10, method = method, seq = seq, subSeq = subSeq, b18 = b18, opcode = null,
            crcRecv = crcRecv, crcCalc = crcCalc, crcOk = crcOk, wrap = KeepFrame.Wrap.SHORT_ACK,
            route = "", payload = ByteArray(0),
        )
    }

    val opcode = pkt[19].toInt() and 0xFF
    val body = pkt.copyOfRange(20, pkt.size - 2)

    var wrap = KeepFrame.Wrap.NORMAL
    var route = ""
    var payload = ByteArray(0)

    if (body.size >= 2 && body[0] == 0x00.toByte() && body[1] == 0x55.toByte()) {
        wrap = KeepFrame.Wrap.OBSERVE
        val rest = body.copyOfRange(2, body.size)
        val sep = rest.indexOfByte(0xFF.toByte())
        if (sep > 0) {
            route = String(rest, 0, sep, Charsets.US_ASCII)
            payload = rest.copyOfRange(sep + 1, rest.size)
        } else {
            route = String(rest, Charsets.US_ASCII)
        }
    } else {
        val sep = body.indexOfByte(0xFF.toByte())
        when {
            sep > 0 -> {
                route = runCatching { String(body, 0, sep, Charsets.US_ASCII) }
                    .getOrElse { body.copyOfRange(0, sep).joinToString("") { "%02x".format(it) } }
                payload = body.copyOfRange(sep + 1, body.size)
            }
            body.isNotEmpty() && body.all { (it.toInt() and 0xFF) in 0x20..0x7E } -> {
                route = String(body, Charsets.US_ASCII)
            }
            else -> payload = body
        }
    }

    return KeepFrame(
        raw = pkt, len = pkt.size, b3 = b3, pktLen = pktLen, src = src, dst = dst,
        b10 = b10, method = method, seq = seq, subSeq = subSeq, b18 = b18, opcode = opcode,
        crcRecv = crcRecv, crcCalc = crcCalc, crcOk = crcOk, wrap = wrap,
        route = route, payload = payload,
    )
}

private fun ByteArray.indexOfByte(b: Byte): Int {
    for (i in indices) if (this[i] == b) return i
    return -1
}
