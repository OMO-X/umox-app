package com.umox.app.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * Mengikat (bind) soket TCP (Port 9527 & 9528) langsung ke antarmuka Wi-Fi Direct (`p2p-wlan0-*` / `192.168.2.x`)
 * agar trafik ke speedometer (`192.168.2.1`) tidak pernah dibelokkan ke Data Seluler (4G/5G).
 *
 * Mengadopsi mekanisme `t.b(int port, String ip)` dari WayXAuto ditambah fallback `NetworkInterface` P2P.
 */
object WifiDirectNetworkBinder {

    fun createBoundSocket(
        context: Context?,
        targetIp: String,
        targetPort: Int,
        connectTimeoutMs: Int = 2500,
        sendBufferBytes: Int = 0
    ): Socket {
        val targetAddr = InetAddress.getByName(targetIp)
        val socket = Socket()
        socket.tcpNoDelay = true
        try {
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
            if (sendBufferBytes > 0) {
                socket.sendBufferSize = sendBufferBytes
            }
        } catch (_: Exception) {}

        var bound = false

        // 1. PRIORITAS UTAMA: Cari IP lokal di antarmuka subnet yang sama (misal 192.168.2.x pada p2p0).
        // Wajib diikat (bind) ke interface lokal motor terlebih dahulu agar tidak dibajak oleh Hotspot Samsung (10.75.193.x).
        try {
            val targetPrefix = targetIp.substringBeforeLast('.', "")
            if (targetPrefix.isNotEmpty()) {
                val localMatch = findLocalSubnetAddress("$targetPrefix.")
                if (localMatch != null) {
                    socket.bind(InetSocketAddress(localMatch, 0))
                    bound = true
                }
            }
        } catch (_: Exception) {}

        // 2. Jika ada Network spesifik di ConnectivityManager yang rutenya benar-benar mengarah ke 192.168.2.x:
        // HANYA ikat jika rutenya cocok (JANGAN pernah fallback ke sembarang Wi-Fi karena bisa mengikat ke Hotspot Samsung).
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    var routeMatchedNet: Network? = null

                    for (net in cm.allNetworks) {
                        val caps = cm.getNetworkCapabilities(net) ?: continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

                        val linkProps = cm.getLinkProperties(net)
                        if (linkProps != null) {
                            for (route in linkProps.routes) {
                                try {
                                    if (!route.isDefaultRoute && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (route.destination.contains(targetAddr)) {
                                            routeMatchedNet = net
                                            break
                                        }
                                    } else if (!route.isDefaultRoute) {
                                        val destStr = route.destination.toString()
                                        if (destStr.startsWith("192.168.2.") || destStr.startsWith("192.168.49.")) {
                                            routeMatchedNet = net
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        if (routeMatchedNet != null) break
                    }

                    if (routeMatchedNet != null) {
                        try {
                            routeMatchedNet.bindSocket(socket)
                            bound = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        socket.connect(InetSocketAddress(targetAddr, targetPort), connectTimeoutMs)
        return socket
    }

    fun findLocalSubnetAddress(subnetPrefix: String = "192.168.2."): InetAddress? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = Collections.list(nif.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith(subnetPrefix)) {
                            return addr
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

