package com.umox.app.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import com.umox.app.config.AppConfig

class ControlChannelClient(
    private val targetIp: String = AppConfig.defaultClusterIp,
    private val targetPort: Int = AppConfig.defaultControlPort,
    private val onLog: (String) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: DataInputStream? = null
    private var isConnected = false
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val ecdhManager = DesayEcdhManager()

    var onShowRequested: (() -> Unit)? = null
    var onHideRequested: (() -> Unit)? = null
    var onKeyFrameRequested: (() -> Unit)? = null
    var onClusterModeChanged: ((String) -> Unit)? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()

        try {
            onLog("Menghubungkan Saluran Kontrol ke $targetIp:$targetPort...")
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(targetIp, targetPort), 3000)

            socket = s
            outputStream = s.getOutputStream()
            inputStream = DataInputStream(s.getInputStream())
            isConnected = true
            onLog("✔ Terhubung ke Saluran Kontrol Desay SV (Port $targetPort)!")

            // 1. Inisialisasi Device Info & Resolusi Layar ke Speedometer (Channel 7, Msg 41)
            val devInfo = """{"size":"960x360"}""".toByteArray(Charsets.UTF_8)
            sendPacket(channelId = 7, msgId = 41.toShort(), body = devInfo)
            onLog("► Mengirim Deklarasi Resolusi 960x360 ke Speedometer (Ch 7, Msg 41)")

            // Mulai pendengar paket protokol binary
            startListening()

        } catch (e: Exception) {
            onLog("Peringatan kontrol soket (Port $targetPort): ${e.message}")
        }
    }

    private fun startListening() {
        listenJob = scope.launch {
            val dis = inputStream ?: return@launch
            val headerBytes = ByteArray(8)

            while (isActive && isConnected) {
                try {
                    dis.readFully(headerBytes)
                    val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val channelId = bb.int
                    val plen = bb.int

                    if (plen in 2..65536) {
                        val payload = ByteArray(plen)
                        dis.readFully(payload)
                        val pbb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        val msgId = pbb.short
                        val body = payload.copyOfRange(2, plen)

                        when (msgId.toInt()) {
                            9 -> { // Heartbeat Ping dari Speedometer Motor (Channel 1)
                                // Desay SV membalas dengan MsgId 9 membawa kembali 12 byte (index + uptime timestamp)
                                sendPacket(channelId = 1, msgId = 9.toShort(), body = body)
                            }
                            37 -> { // Mode Day/Night (Channel 7, Msg 37)
                                val modeStr = String(body, Charsets.UTF_8).trim()
                                onLog("◄ [MODE DISPLAY MOTOR] $modeStr")
                                // Desay SV MapCast mewajibkan balasan API id=39 membawa body konfigurasi mode
                                sendPacket(channelId = 7, msgId = 39.toShort(), body = body)
                                onLog("► [ACK MODE] Mengirim konfirmasi mode motor (Ch 7, Msg 39)")
                                onClusterModeChanged?.invoke(modeStr)
                            }
                            49 -> { // Security Handshake / Pertukaran Kunci ECDH SECP256R1 (Channel 1, Msg 49 / 0x31)
                                onLog("◄ [ECDH KEY CLUSTER] Menerima Public Key Speedometer (${body.size} bytes)")
                                try {
                                    val localPub = ecdhManager.generateLocalKeyPair()
                                    val secret = ecdhManager.computeSharedSecret(body)
                                    if (secret != null) {
                                        onLog("✔ ECDH Shared Secret Berhasil Dihitung (${secret.size} bytes)!")
                                    }

                                    // Balas dengan Public Key lokal HP (total 101 bytes = 8 header + 2 msgId + 91 bytes ASN.1 EC key)
                                    sendPacket(channelId = 1, msgId = 49.toShort(), body = localPub)
                                    onLog("► [ECDH KEY PHONE] Mengirim Public Key lokal ke Speedometer (${localPub.size} bytes)")
                                    onLog("★ HANDSHAKE KEAMANAN SELESAI!")

                                    // Kirim MapProjectionSignal SHOW (Ch 7, Msg 1, {"code":0}) untuk membuka layar speedometer!
                                    sendShowSignal()
                                    onShowRequested?.invoke()
                                } catch (e: Exception) {
                                    onLog("⚠ Kesalahan pemrosesan kunci ECDH: ${e.message}")
                                }
                            }
                            11 -> { // I-Frame / KeyFrame Request dari Speedometer Motor (Channel 7, Msg 11)
                                onLog("◄ [KONTROL Ch 7, Msg 11] Speedometer meminta KeyFrame (I-Frame)!")
                                val ack = """{"code":0}""".toByteArray(Charsets.UTF_8)
                                sendPacket(channelId = 7, msgId = 11.toShort(), body = ack)
                                onKeyFrameRequested?.invoke()
                            }
                            27 -> { // Status query / screen request
                                onLog("◄ [KONTROL Ch 7, Msg 27] Status query dari Speedometer (${body.size} bytes)")
                                val ack = """{"code":0}""".toByteArray(Charsets.UTF_8)
                                sendPacket(channelId = 7, msgId = 27.toShort(), body = ack)
                            }
                            10 -> { // Subsystem ACK / sync
                                onLog("◄ [KONTROL Ch 7, Msg 10] Sinkronisasi dari Speedometer (${body.size} bytes)")
                                val ack = """{"code":0}""".toByteArray(Charsets.UTF_8)
                                sendPacket(channelId = 7, msgId = 10.toShort(), body = ack)
                            }
                            else -> {
                                val str = String(body, Charsets.UTF_8).trim()
                                val hexPreview = if (body.isNotEmpty()) " [Hex: " + body.take(8).joinToString(" ") { "%02X".format(it) } + "]" else ""
                                onLog("◄ [KONTROL Ch $channelId, Msg $msgId]$hexPreview $str")

                                if (str.contains("DS_MSG_HU_REQ_SHOW")) {
                                    onLog("★ SPEEDOMETER MEMINTA TAMPILAN DIBUKA (DS_MSG_HU_REQ_SHOW)!")
                                    val ack = """{"msg_id":"DS_RESP_SHOW","result":0,"status":"STREAMING"}"""
                                    sendControlJson(channelId = 1, msgId = 0x0002.toShort(), json = ack)
                                    sendShowSignal()
                                    onShowRequested?.invoke()
                                } else if (str.contains("DS_MSG_HU_REQ_HIDE")) {
                                    onLog("Speedometer meminta tampilan ditutup (DS_MSG_HU_REQ_HIDE).")
                                    onHideRequested?.invoke()
                                } else if (str.contains("DS_MSG_HU_REQ_ROUTE_INFO")) {
                                    val routeAck = """{"msg_id":"DS_RESP_ROUTE_INFO","remain_dist":1200,"remain_time":180}"""
                                    sendControlJson(channelId = 1, msgId = 0x0003.toShort(), json = routeAck)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        onLog("Koneksi saluran kontrol terputus: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    @Synchronized
    fun sendPacket(channelId: Int = 1, msgId: Short, body: ByteArray = ByteArray(0)) {
        if (!isConnected) return
        try {
            val totalLen = 2 + body.size
            val buf = ByteBuffer.allocate(8 + totalLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(channelId) // channel / subsystem ID (1 = system, 7 = telemetry)
            buf.putInt(totalLen)  // payload length (msgId + body)
            buf.putShort(msgId)   // command ID
            buf.put(body)

            outputStream?.let { os ->
                os.write(buf.array())
                os.flush()
            }
        } catch (e: Exception) {
            // Socket write error
        }
    }

    fun sendControlJson(channelId: Int = 1, msgId: Short, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        sendPacket(channelId = channelId, msgId = msgId, body = payload)
    }

    fun sendShowSignal() {
        val showPayload = """{"code":0}""".toByteArray(Charsets.UTF_8)
        sendPacket(channelId = 7, msgId = 1.toShort(), body = showPayload)
        onLog("► Mengirim MapProjectionSignal SHOW ke Speedometer (Ch 7, Msg 1)")
    }

    fun sendHideSignal() {
        val hidePayload = """{"code":0}""".toByteArray(Charsets.UTF_8)
        sendPacket(channelId = 7, msgId = 2.toShort(), body = hidePayload)
        onLog("► Mengirim MapProjectionSignal HIDE ke Speedometer (Ch 7, Msg 2)")
    }

    fun disconnect() {
        if (isConnected) {
            try {
                sendHideSignal()
            } catch (e: Exception) {}
        }

        isConnected = false
        listenJob?.cancel()
        listenJob = null

        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            // Cleanup
        }
        inputStream = null
        outputStream = null
        socket = null
    }

    /**
     * Generator dan negosiator kunci kriptografi ECDH SECP256R1 (NIST P-256)
     * yang diwajibkan oleh cluster Desay SV sebelum mengaktifkan framebuffer layar HUD.
     */
    class DesayEcdhManager {
        private var localKeyPair: KeyPair? = null
        var sharedSecret: ByteArray? = null
            private set

        fun generateLocalKeyPair(): ByteArray {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            localKeyPair = kp
            return kp.public.encoded // Persis 91 bytes X.509 SubjectPublicKeyInfo
        }

        fun computeSharedSecret(remotePublicKeyBytes: ByteArray): ByteArray? {
            return try {
                val kf = KeyFactory.getInstance("EC")
                val remoteKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(remotePublicKeyBytes))
                val ka = KeyAgreement.getInstance("ECDH")
                ka.init(localKeyPair?.private)
                ka.doPhase(remoteKey, true)
                val secret = ka.generateSecret()
                sharedSecret = secret
                secret
            } catch (e: Exception) {
                null
            }
        }
    }
}
