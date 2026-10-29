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

        // 1. Coba bind via ConnectivityManager Network yang memiliki rute langsung ke 192.168.2.1 (persis WayXAuto t.java)
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    var routeMatchedNet: Network? = null
                    var fallbackWifiNet: Network? = null

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
                        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            fallbackWifiNet = net
                        } else if (fallbackWifiNet == null) {
                            fallbackWifiNet = net
                        }
                    }

                    val chosenNet = routeMatchedNet ?: fallbackWifiNet
                    if (chosenNet != null) {
                        try {
                            chosenNet.bindSocket(socket)
                            bound = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback & penguat: Cari IP lokal di subnet yang sama (misal 192.168.2.x pada p2p-wlan0-0)
        // dan bind local address apabila belum terikat ke rute spesifik.
        try {
            val targetPrefix = targetIp.substringBeforeLast('.', "")
            if (targetPrefix.isNotEmpty()) {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                var localMatch: InetAddress? = null
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = Collections.list(nif.inetAddresses)
                    for (addr in addrs) {
                        if (addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("$targetPrefix.")) {
                                localMatch = addr
                                break
                            }
                        }
                    }
                    if (localMatch != null) break
                }
                if (localMatch != null && !socket.isBound) {
                    socket.bind(InetSocketAddress(localMatch, 0))
                    bound = true
                }
            }
        } catch (_: Exception) {}

        socket.connect(InetSocketAddress(targetAddr, targetPort), connectTimeoutMs)
        return socket
    }
}

