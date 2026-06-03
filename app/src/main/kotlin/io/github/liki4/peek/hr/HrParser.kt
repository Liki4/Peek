package io.github.liki4.peek.hr

/**
 * Parse a Heart Rate Measurement notification per Bluetooth SIG HRS spec.
 *
 * Byte 0: flags
 *   bit 0: HR value format (0 = uint8, 1 = uint16 LE)
 *   bit 1: sensor contact detected
 *   bit 2: sensor contact supported
 *   bit 3: Energy Expended present (uint16 LE, kJ)
 *   bit 4: RR-Interval present (each uint16 LE in 1/1024 sec)
 *
 * Ported from `hr_protocol.py:parse_hr_measurement`.
 */
fun parseHrMeasurement(data: ByteArray): HrSample? {
    if (data.isEmpty()) return null
    val flags = data[0].toInt() and 0xFF
    val isU16 = (flags and 0x01) != 0
    val contactDet = (flags and 0x02) != 0
    val contactSup = (flags and 0x04) != 0
    val eePresent = (flags and 0x08) != 0
    val rrPresent = (flags and 0x10) != 0

    var pos = 1
    val hr = if (isU16) {
        if (data.size < pos + 2) return null
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        v
    } else {
        if (data.size < pos + 1) return null
        val v = data[pos].toInt() and 0xFF
        pos += 1
        v
    }

    var ee: Int? = null
    if (eePresent && data.size >= pos + 2) {
        ee = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
    }

    val rrs = mutableListOf<Float>()
    if (rrPresent) {
        while (pos + 2 <= data.size) {
            val raw = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            rrs += raw / 1024.0f
            pos += 2
        }
    }

    return HrSample(
        heartRate = hr,
        sensorContactSupported = contactSup,
        sensorContactDetected = contactDet,
        energyExpendedKj = ee,
        rrIntervalsS = rrs,
    )
}
