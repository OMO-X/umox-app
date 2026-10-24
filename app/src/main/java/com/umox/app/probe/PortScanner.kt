package com.umox.app.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PortScanResult(
    val port: Int,
    val isOpen: Boolean,
    val description: String
)

class PortScanner(
    private val onLog: (String) -> Unit
) {
    // Port populer yang sering dipakai untuk streaming / IoT otomotif
    private val targetPorts = mapOf(
        554 to "RTSP Standard (H.264/H.265 Streaming)",
        8554 to "RTSP Alternative Stream",
        8080 to "HTTP Web Server / MJPEG Video",
        8000 to "HTTP Alternative Port",
        7000 to "CarPlay / AirPlay Mirroring Port",
        9000 to "Custom Media / Socket Stream",
        1883 to "MQTT IoT Broker",
        8883 to "MQTT SSL IoT Broker",
        1900 to "SSDP / UPnP Discovery",
        5353 to "mDNS Device Discovery",
        5000 to "Custom Control Socket",
        3000 to "Node / Web Service"
    )

    suspend fun scanHost(hostIp: String, customPorts: List<Int> = emptyList()): List<PortScanResult> =
        withContext(Dispatchers.IO) {
            onLog("Memulai pemindaian port pada $hostIp...")
            val portsToScan = (targetPorts.keys + customPorts).distinct()

            coroutineScope {
                val deferreds = portsToScan.map { port ->
                    async {
                        val isOpen = testPort(hostIp, port, timeoutMs = 400)
                        val desc = targetPorts[port] ?: "Custom Port"
                        if (isOpen) {
                            onLog("★ PORT TERBUKA DITEMUKAN: $port ($desc)")
                        }
                        PortScanResult(port, isOpen, desc)
                    }
                }
                val results = deferreds.awaitAll()
                val openCount = results.count { it.isOpen }
                onLog("Pemindaian port selesai. Ditemukan $openCount port aktif di $hostIp.")
                results.filter { it.isOpen }
            }
        }

    private fun testPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
