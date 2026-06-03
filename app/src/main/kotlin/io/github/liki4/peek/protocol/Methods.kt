package io.github.liki4.peek.protocol

/**
 * Method byte (offset 11). OBSERVE uses GET method (0x01) — subscription intent
 * is signaled by opcode=0x61 + body prefix \x00\x55 + b18=0x04, NOT by the
 * method byte. (PROTOCOL.md §4)
 */
enum class KirinMethod(val v: Int) {
    GET(0x01),          // also used for OBSERVE
    PUT(0x03),
    RSP(0x45),
    REQ_BODY(0x65),     // PUT-with-body variant seen on 106/3 auth
    ERR_RSP(0x84),
    SUB_ACK(0xA1);      // bike's short-ACK after receiving a packet

    companion object {
        fun fromWire(v: Int): KirinMethod? = entries.firstOrNull { it.v == (v and 0xFF) }
    }
}

enum class KirinOpcode(val v: Int) {
    IDENTITY(0xB3),
    NORMAL(0xB5),
    SUBRES(0xB6),       // 106/21 AT
    OBSERVE(0x61),
    ERROR(0xFF);

    companion object {
        fun fromWire(v: Int): KirinOpcode? = entries.firstOrNull { it.v == (v and 0xFF) }
    }
}

/**
 * Wire values for `Machine.TrainingStatusMessage.status`. Java declaration order is
 * misleading — these are the actual observed semantics on CC_23 firmware:
 *
 *   wire | app sends → bike state            | bike pushes → meaning
 *   -----|-----------------------------------|----------------------
 *    1   | reset to ready                    | session ended
 *    2   | request start (bike → "press knob") | user pressed knob → actively training
 *    3   | end session                       | (not seen as push)
 *    4   | pause an active session           | user pressed knob during training
 */
enum class TrainingStatus(val wire: Int) {
    IDLE(1),
    TRAINING(2),
    STOPPED(3),
    PAUSED(4);

    companion object {
        fun fromWire(v: Int): TrainingStatus? = entries.firstOrNull { it.wire == v }
    }
}

/** Resource 106/10 PUT values. */
enum class DeviceCommand(val v: Int) {
    RESET(1),
    OTA_CHECK_UPGRADE(2),
    OTA_CHECK_DOWNGRADE(3),
}
