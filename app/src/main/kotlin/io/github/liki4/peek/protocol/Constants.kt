package io.github.liki4.peek.protocol

import java.util.UUID

object Constants {
    val KEEP_SERVICE: UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    val KEEP_CHAR: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "Keep_CC"

    const val PHONE_EP = 0x3216
    const val BIKE_EP = 0xEF23

    const val MTU = 185
}
