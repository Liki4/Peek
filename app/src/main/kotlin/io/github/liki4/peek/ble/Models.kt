package io.github.liki4.peek.ble

data class DiscoveredBike(
    val address: String,
    val name: String,
    val rssi: Int,
)

data class DiscoveredHrBelt(
    val address: String,
    val name: String,
    val rssi: Int,
)
