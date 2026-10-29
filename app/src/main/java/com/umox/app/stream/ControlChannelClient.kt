package com.umox.app.stream

import android.content.Context
import android.os.SystemClock
import com.umox.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyAgreement

/**
 * Klien Saluran Kontrol Desay SV (Port 9527) dengan Wi-Fi Direct Route Binding & Control Recovery.
 *
 * Mengadopsi arsitektur `t.java`, `q.java`, dan `ki.java` dari WayXAuto:
 * 1. Saat terhubung ke Port 9527, langsung mengirim:
 *    - `Ch 1, Msg 1`: `{"platform":"Android","screenWidth":"960","screenHeight":"360"}`
 *    - `Ch 7, Msg 41`: `{"foreground":"1"}`
 * 2. Menangani:
 *    - `Ch 1, Msg 9` (Heartbeat Ping): Echo `Ch 1, Msg 9` + pemicu `confirmReady()`.
 *    - `Ch 1, Msg 49` (ECDH Public Key): Balas `Ch 1, Msg 49` (91B ASN.1 P-256) + pemicu `confirmReady()`.
 *    - `Ch 1, Msg 40` (KeyFrame Request dari Cluster): Segera minta IDR KeyFrame baru ke encoder!
 *    - `Ch 7, Msg 10` (Display Shown): Tandai layar cluster terbuka & minta IDR KeyFrame baru!
 *    - `Ch 7, Msg 11` (Display Hidden): Jalankan pemulihan otomatis (`Msg 41` + `Msg 1`) bila stream aktif!
 *    - `Ch 7, Msg 37` (Mode Day/Night): Balas `Ch 7, Msg 39` `{"mode":"night"}`.
 */
class ControlChannelClient(
    private val context: Context? = null,
    private val targetIp: String = AppConfig.defaultClusterIp,
    private val targetPort: Int = AppConfig.defaultControlPort,
    private val onLog: (String) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: DataInputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    private val hasConfirmedReady = AtomicBoolean(false)

    val isProjectionControlReady: Boolean
        get() = hasConfirmedReady.get()

    @Volatile
    var connectedSinceElapsedMs: Long = 0L
        private set

    @Volatile
    var isClusterDisplayShown: Boolean = false
        private set

    @Volatile
    var hasSeenShowConfirmation: Boolean = false
        private set

    @Volatile
    var controlFramesReceived: Long = 0L
        private set

    private var listenJob: Job? = null
    private var recoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val ecdhManager = DesayEcdhManager()

    var onShowRequested: (() -> Unit)? = null
    var onHideRequested: (() -> Unit)? = null
    var onKeyFrameRequested: (() -> Unit)? = null
    var onClusterModeChanged: ((String) -> Unit)? = null
    var onConnectionLost: (() -> Unit)? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        disconnect(sendHide = false)

        try {
            onLog("Menghubungkan Saluran Kontrol ke $targetIp:$targetPort (Wi-Fi Direct Bound)...")
            val s = WifiDirectNetworkBinder.createBoundSocket(
                context = context,
                targetIp = targetIp,
                targetPort = targetPort,
                connectTimeoutMs = 2500
            )

            socket = s
            outputStream = s.getOutputStream()
            inputStream = DataInputStream(s.getInputStream())
            isConnected = true
            hasConfirmedReady.set(false)
            connectedSinceElapsedMs = SystemClock.elapsedRealtime()
            isClusterDisplayShown = false
            hasSeenShowConfirmation = false

            onLog("✔ Terhubung ke Saluran Kontrol Desay SV (Port $targetPort)!")

            // 1. Kirim deklarasi platform & resolusi 960x360 (Channel 1, Msg 1 - persis WayXAuto t.java line 193)
            sendControlJson(
                channelId = 1,
                msgId = 1,
                json = """{"platform":"Android","screenWidth":"960","screenHeight":"360"}"""
            )
            // 2. Kirim status Foreground aktif (Channel 7, Msg 41 - persis WayXAuto t.java line 194)
            sendForegroundSignal()
            onLog("► Mengirim Handshake Platform (Ch 1, Msg 1) & Foreground (Ch 7, Msg 41)")

            startListening()
            startControlRecoveryLoop()
            true
        } catch (e: Exception) {
            onLog("Peringatan kontrol soket (Port $targetPort): ${e.message}")
            isConnected = false
            false
        }
    }

    private fun confirmReady() {
        if (hasConfirmedReady.compareAndSet(false, true)) {
            onLog("★ Kontrol Proyeksi Motor Siap · Mengirim Ch 7, Msg 1 ({\"code\":0}) & memulai aliran video!")
            sendShowSignal()
            onShowRequested?.invoke()
            onKeyFrameRequested?.invoke()
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

                    if (plen < 2 || plen > 262144) {
                        throw IllegalStateException("Panjang paket kontrol tidak valid: $plen")
                    }

                    val payload = ByteArray(plen)
                    dis.readFully(payload)
                    val msgId = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val body = payload.copyOfRange(2, plen)
                    controlFramesReceived++

                    if (channelId == 1 && msgId == 9) {
                        // Heartbeat Ping dari Speedometer -> Echo balik Msg 9 & konfirmasi siap
                        sendPacket(channelId = 1, msgId = 9.toShort(), body = body)
                        confirmReady()
                    } else if (channelId == 1 && msgId == 49) {
                        // ECDH Public Key dari Speedometer (91 bytes ASN.1 P-256)
                        onLog("◄ [ECDH KEY CLUSTER] Menerima Public Key Speedometer (${body.size} B)")
                        try {
                            val localPub = ecdhManager.generateLocalKeyPair()
                            ecdhManager.computeSharedSecret(body)
                            sendPacket(channelId = 1, msgId = 49.toShort(), body = localPub)
                            onLog("► [ECDH KEY PHONE] Mengirim Public Key lokal (${localPub.size} B) -> Handshake OK!")
                        } catch (e: Exception) {
                            onLog("⚠ Kesalahan ECDH: ${e.message}")
                        }
                        confirmReady()
                    } else if (channelId == 1 && msgId == 40) {
                        // Cluster meminta IDR KeyFrame baru (`ki.java` line 14)
                        onKeyFrameRequested?.invoke()
                    } else if (channelId == 7 && msgId == 10) {
                        // Cluster mengonfirmasi layar proyeksi TAMPIL (`ki.java` line 19)
                        isClusterDisplayShown = true
                        hasSeenShowConfirmation = true
                        confirmReady()
                        onShowRequested?.invoke()
                        onKeyFrameRequested?.invoke()
                    } else if (channelId == 7 && msgId == 11) {
                        // Cluster melaporkan layar proyeksi TERTUTUP (`ki.java` line 25)
                        isClusterDisplayShown = false
                    } else if (channelId == 7 && msgId == 37) {
                        // Query Mode Day/Night dari sensor cahaya motor (`q.java` line 225)
                        val modeStr = String(body, Charsets.UTF_8).trim()
                        val replyBody = if (modeStr.startsWith("{")) modeStr else """{"mode":"night"}"""
                        sendControlJson(channelId = 7, msgId = 39, json = replyBody)
                        onClusterModeChanged?.invoke(replyBody)
                    } else if (channelId == 7 && msgId == 27) {
                        sendControlJson(channelId = 7, msgId = 27, json = """{"code":0}""")
                    } else {
                        val str = String(body, Charsets.UTF_8).trim()
                        if (str.contains("DS_MSG_HU_REQ_SHOW")) {
                            sendControlJson(
                                channelId = 1,
                                msgId = 2,
                                json = """{"msg_id":"DS_RESP_SHOW","result":0,"status":"STREAMING"}"""
                            )
                            confirmReady()
                            sendShowSignal()
                            onShowRequested?.invoke()
                            onKeyFrameRequested?.invoke()
                        } else if (str.contains("DS_MSG_HU_REQ_HIDE")) {
                            isClusterDisplayShown = false
                        }
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        isConnected = false
                        onLog("Koneksi saluran kontrol terputus: ${e.message}")
                        onConnectionLost?.invoke()
                    }
                    break
                }
            }
        }
    }

    /**
     * Loop pemulihan tampilan otomatis setiap 250ms (`q.java` lines 69-105 di WayXAuto):
     * Menjaga layar speedometer tetap membuka kanvas video apabila sempat tertutup oleh notifikasi cluster.
     */
    private fun startControlRecoveryLoop() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            var lastRecoveryMs = 0L
            val connectedAtMs = SystemClock.elapsedRealtime()
            while (isActive && isConnected) {
                delay(250L)
                if (!isConnected) break
                val nowMs = SystemClock.elapsedRealtime()
                val shouldRecover = if (hasSeenShowConfirmation) {
                    !isClusterDisplayShown && (nowMs - lastRecoveryMs >= 1200L)
                } else {
                    (nowMs - connectedAtMs >= 1500L) && (nowMs - lastRecoveryMs >= 2000L)
                }

                if (shouldRecover) {
                    lastRecoveryMs = nowMs
                    sendForegroundSignal()
                    sendShowSignal()
                    onKeyFrameRequested?.invoke()
                }
            }
        }
    }

    @Synchronized
    fun sendPacket(channelId: Int = 1, msgId: Short, body: ByteArray = ByteArray(0)) {
        if (!isConnected) return
        try {
            val totalLen = 2 + body.size
            val buf = ByteBuffer.allocate(8 + totalLen).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(channelId)
                putInt(totalLen)
                putShort(msgId)
                put(body)
            }
            outputStream?.let { os ->
                os.write(buf.array())
                os.flush()
            }
        } catch (_: Exception) {}
    }

    fun sendControlJson(channelId: Int = 1, msgId: Short, json: String) {
        sendPacket(channelId = channelId, msgId = msgId, body = json.toByteArray(Charsets.UTF_8))
    }

    fun sendForegroundSignal() {
        sendControlJson(channelId = 7, msgId = 41, json = """{"foreground":"1"}""")
    }

    fun sendShowSignal() {
        sendControlJson(channelId = 7, msgId = 1, json = """{"code":0}""")
    }

    fun sendHideSignal() {
        sendControlJson(channelId = 7, msgId = 2, json = """{"code":0}""")
    }

    fun disconnect(sendHide: Boolean = true) {
        if (isConnected && sendHide) {
            try {
                sendHideSignal()
            } catch (_: Exception) {}
        }

        isConnected = false
        listenJob?.cancel()
        listenJob = null
        recoveryJob?.cancel()
        recoveryJob = null

        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    class DesayEcdhManager {
        private var localKeyPair: KeyPair? = null
        var sharedSecret: ByteArray? = null
            private set

        fun generateLocalKeyPair(): ByteArray {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            localKeyPair = kp
            return kp.public.encoded
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
            } catch (_: Exception) {
                null
            }
        }
    }
}
