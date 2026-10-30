package com.umox.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DIAL (Discovery and Launch) Server v1.7.2 untuk Android Auto / Motorcycle Speedometer.
 *
 * Mengimplementasikan:
 * 1. SSDP Multicast responder (239.255.255.250:1900) untuk penemuan otomatis tombol "Cast" di aplikasi YouTube resmi.
 * 2. HTTP REST Service (Port 8008) murni menggunakan ServerSocket (100% kompatibel di semua versi Android tanpa com.sun.net).
 */
class DialServer(
    private val context: Context,
    private val getLocalIp: () -> String,
    private val onYouTubeLaunch: (videoId: String?, pairingCode: String?) -> Unit,
    private val onYouTubeStop: () -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DialServer"
        private const val MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        const val HTTP_PORT = 8008
        private const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"
    }

    private val deviceUuid = UUID.nameUUIDFromBytes("UMOX-OMOWAY-DIAL-2026".toByteArray()).toString()
    private val isRunning = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdpSocket: MulticastSocket? = null
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()

    @Volatile
    var isPlaying: Boolean = false

    fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("UMOX_DIAL_LOCK")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            onLog("⚠ Gagal mengaktifkan MulticastLock: ${e.message}")
        }

        startHttpServer()
        startSsdpResponder()
        onLog("✔ DIAL Server & YouTube Cast Receiver aktif di port $HTTP_PORT (SSDP 1900)!")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            ssdpSocket?.close()
            ssdpSocket = null
        } catch (_: Exception) {}

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        onLog("⏹ DIAL Server dihentikan.")
    }

    private fun startHttpServer() {
        threadPool.execute {
            try {
                val ss = ServerSocket(HTTP_PORT).apply {
                    reuseAddress = true
                }
                serverSocket = ss

                while (isRunning.get()) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        continue
                    }
                    threadPool.execute {
                        handleHttpClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Gagal membuka HTTP Server: ${e.message}", e)
                    onLog("✖ HTTP Server DIAL gagal dibuka di port $HTTP_PORT: ${e.message}")
                }
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val path = parts[1]

            // Baca headers
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                val lower = header.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = header.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Baca body jika ada
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r < 0) break
                    read += r
                }
                body = String(buf, 0, read)
            }

            val myIp = getLocalIp()

            when {
                path == "/dd.xml" && method == "GET" -> {
                    val xml = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <root xmlns="urn:schemas-upnp-org:device-1-0">
                            <specVersion>
                                <major>1</major>
                                <minor>0</minor>
                            </specVersion>
                            <device>
                                <deviceType>urn:schemas-upnp-org:device:dial:1</deviceType>
                                <friendlyName>UMOX Display (OMOWAY)</friendlyName>
                                <manufacturer>UMOX Technologies</manufacturer>
                                <modelName>UMOX Speedometer Cast</modelName>
                                <modelNumber>2.0</modelNumber>
                                <UDN>uuid:$deviceUuid</UDN>
                                <serviceList>
                                    <service>
                                        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
                                        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
                                        <controlURL>/apps</controlURL>
                                        <eventSubURL></eventSubURL>
                                        <SCPDURL></SCPDURL>
                                    </service>
                                </serviceList>
                            </device>
                        </root>
                    """.trimIndent()
                    sendHttpResponse(
                        out,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        xml.toByteArray(Charsets.UTF_8),
                        listOf("Application-URL: http://$myIp:$HTTP_PORT/apps/")
                    )
                }
                path == "/apps/YouTube" && method == "GET" -> {
                    val state = if (isPlaying) "running" else "stopped"
                    val xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <service xmlns="urn:dial-multiscreen-org:schemas:dial">
                            <name>YouTube</name>
                            <options allowStop="true"/>
                            <state>$state</state>
                            <link rel="run" href="run"/>
                        </service>
                    """.trimIndent()
                    sendHttpResponse(
                        out,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        xml.toByteArray(Charsets.UTF_8),
                        listOf("Application-URL: http://$myIp:$HTTP_PORT/apps/")
                    )
                }
                path == "/apps/YouTube" && method == "POST" -> {
                    onLog("📥 Menerima permintaan Cast YouTube via DIAL: $body")

                    var videoId: String? = null
                    var pairingCode: String? = null

                    val pairs = body.split("&")
                    for (pair in pairs) {
                        val p = pair.split("=")
                        if (p.size == 2) {
                            try {
                                val key = URLDecoder.decode(p[0], "UTF-8")
                                val value = URLDecoder.decode(p[1], "UTF-8")
                                when (key) {
                                    "v" -> videoId = value
                                    "pairingCode" -> pairingCode = value
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    isPlaying = true
                    onYouTubeLaunch(videoId, pairingCode)

                    sendHttpResponse(
                        out,
                        201,
                        "Created",
                        "text/plain",
                        ByteArray(0),
                        listOf("Location: http://$myIp:$HTTP_PORT/apps/YouTube/run")
                    )
                }
                (path == "/apps/YouTube" || path == "/apps/YouTube/run") && method == "DELETE" -> {
                    isPlaying = false
                    onYouTubeStop()
                    sendHttpResponse(out, 200, "OK", "text/plain", ByteArray(0))
                }
                method == "OPTIONS" -> {
                    sendHttpResponse(
                        out,
                        200,
                        "OK",
                        "text/plain",
                        ByteArray(0),
                        listOf(
                            "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS",
                            "Access-Control-Allow-Headers: Content-Type"
                        )
                    )
                }
                else -> {
                    sendHttpResponse(out, 404, "Not Found", "text/plain", ByteArray(0))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendHttpResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        content: ByteArray,
        extraHeaders: List<String> = emptyList()
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${content.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n")
        for (h in extraHeaders) {
            sb.append("$h\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (content.isNotEmpty()) {
            out.write(content)
        }
        out.flush()
    }

    private fun startSsdpResponder() {
        threadPool.execute {
            try {
                val group = InetAddress.getByName(MULTICAST_IP)
                val socket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    joinGroup(group)
                    timeToLive = 4
                }
                ssdpSocket = socket

                val buf = ByteArray(2048)
                while (isRunning.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        continue
                    }

                    val msg = String(packet.data, 0, packet.length)
                    if (msg.contains("M-SEARCH", ignoreCase = true) &&
                        (msg.contains(DIAL_ST, ignoreCase = true) || msg.contains("ssdp:all", ignoreCase = true))
                    ) {
                        val senderIp = packet.address
                        val senderPort = packet.port
                        val myIp = getLocalIp()

                        val responseStr = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("CACHE-CONTROL: max-age=1800\r\n")
                            append("EXT:\r\n")
                            append("LOCATION: http://$myIp:$HTTP_PORT/dd.xml\r\n")
                            append("SERVER: Android/14 UPnP/1.1 UMOX/2.0\r\n")
                            append("ST: $DIAL_ST\r\n")
                            append("USN: uuid:$deviceUuid::$DIAL_ST\r\n")
                            append("BOOTID.UPNP.ORG: 1\r\n")
                            append("CONFIGID.UPNP.ORG: 1\r\n")
                            append("\r\n")
                        }

                        val respBytes = responseStr.toByteArray(Charsets.US_ASCII)
                        val respPacket = DatagramPacket(respBytes, respBytes.size, senderIp, senderPort)
                        try {
                            socket.send(respPacket)
                        } catch (e: Exception) {
                            Log.w(TAG, "Gagal mengirim respon SSDP: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kesalahan pada SSDP responder: ${e.message}", e)
            }
        }
    }
}
