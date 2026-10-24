package com.umox.app.ble

data class MotorcycleTelemetry(
    var batteryPercent: Int? = null,
    var rangeKm: Int? = null,
    var odometerKm: Double? = null,
    var speedKmh: Int? = null,
    var isLocked: Boolean? = null,
    var temperatureStatus: String? = null,
    var wifiSsid: String? = null,
    var wifiPassword: String? = null,
    var wifiIp: String? = null,
    var rawHex: String = ""
)
