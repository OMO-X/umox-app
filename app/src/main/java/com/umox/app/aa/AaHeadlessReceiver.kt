package com.umox.app.aa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.umox.app.stream.H264NaluSanitizer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Auto Headless Receiver & Hardware GPU Bridge.
 *
 * Fitur Utama:
 * 1. Menghubungkan langsung ke Android Auto Head Unit Server (`127.0.0.1:5277`) atau
 *    menerima koneksi masuk di port `5288`.
 * 2. Negosiasi SSL/TLS (BoringSSL In-Memory BIO) dan Service Discovery Protobuf lengkap.
 * 3. **Hardware GPU Surface Bridge**: Mendekode stream H.264 Android Auto (`800x480`) langsung
 *    ke `Surface` input milik `MediaEncoder` (`960x360` @ 30 FPS CBR) tanpa menyentuh CPU (0% copy),
 *    dilengkapi fallback otomatis ke `H264NaluSanitizer`.
 * 4. Mendukung injeksi tombol remot (`sendKey`) dan sinkronisasi tema Siang/Malam (`sendNightMode`)
 *    otomatis dari sensor cahaya speedometer Omoway (`Channel 7, Msg 37`).
 */
class AaHeadlessReceiver(
    private val context: Context,
    private val outputSurface: Surface? = null,
    private val onH264Frame: (ByteArray, Int, Int) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "AaHeadlessReceiver"
        const val AA_PORT_LOCAL = 5277
        const val AA_PORT_INCOMING = 5288

        // Channel IDs
        const val CH_CONTROL = 0
        const val CH_SENSOR = 1
        const val CH_VIDEO = 2
        const val CH_INPUT = 3
        const val CH_AUDIO_MAIN = 4
        const val CH_AUDIO_SYS = 5

        // Frame Flags
        const val FLAG_FIRST = 0x01
        const val FLAG_LAST = 0x02
        const val FLAG_BULK = 0x03
        const val FLAG_CONTROL = 0x04
        const val FLAG_ENCRYPTED = 0x08

        // Android KeyCodes untuk Remote Control Cepat
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_NAVIGATION = 280
        const val KEYCODE_MUSIC = 209
    }

    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var activeSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var tlsEngine: AaTlsEngine? = null
    private var dataOut: DataOutputStream? = null

    private var videoSessionId = 0
    private var hwDecoder: MediaCodec? = null
    private val decoderLock = Any()

    @Volatile
    var isNightModeActive: Boolean = false
        private set

    // Fallback sanitizer jika outputSurface tidak digunakan
    private val fallbackSanitizer = H264NaluSanitizer { cleanPacket, _ ->
        onH264Frame(cleanPacket, 0, cleanPacket.size)
    }

    // Reassembly Buffers per Channel (max 16 channels)
    private val rxBuffers = Array(16) { ByteBuffer.allocate(512 * 1024) }

    fun start() {
        if (isRunning.getAndSet(true)) return
        executor.execute { connectionLoop() }
    }

    fun stop() {
        isRunning.set(false)
        releaseHwDecoder()
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun initHwDecoderIfNeeded() {
        val surface = outputSurface ?: return
        synchronized(decoderLock) {
            if (hwDecoder != null) return
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 800, 480).apply {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder.configure(format, surface, null, 0)
                decoder.start()
                hwDecoder = decoder
                Log.i(TAG, "Hardware AVC Decoder -> GPU Surface Bridge berhasil diinisialisasi")
            } catch (e: Exception) {
                Log.w(TAG, "Gagal inisialisasi HW Decoder ke Surface, beralih ke NALU Sanitizer langsung: ${e.message}")
                hwDecoder = null
            }
        }
    }

    private fun feedToHwDecoder(data: ByteArray, offset: Int, length: Int, isConfig: Boolean, ptsUs: Long) {
        val decoder = synchronized(decoderLock) { hwDecoder }
        if (decoder == null) {
            fallbackSanitizer.processAnnexB(data, offset, length)
            return
        }

        try {
            val inIdx = decoder.dequeueInputBuffer(8_000L)
            if (inIdx >= 0) {
                val inBuf = decoder.getInputBuffer(inIdx)
                inBuf?.clear()
                inBuf?.put(data, offset, length)
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                decoder.queueInputBuffer(inIdx, 0, length, ptsUs, flags)
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = decoder.dequeueOutputBuffer(info, 0L)
            while (outIdx >= 0) {
                // Render langsung ke GPU Surface milik MediaEncoder (960x360)
                decoder.releaseOutputBuffer(outIdx, true)
                outIdx = decoder.dequeueOutputBuffer(info, 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HW Decoder error, fallback ke direct NALU: ${e.message}")
            releaseHwDecoder()
            fallbackSanitizer.processAnnexB(data, offset, length)
        }
    }

    private fun releaseHwDecoder() {
        synchronized(decoderLock) {
            try {
                hwDecoder?.stop()
            } catch (_: Exception) {}
            try {
                hwDecoder?.release()
            } catch (_: Exception) {}
            hwDecoder = null
        }
    }

    private fun connectionLoop() {
        onStatusChanged("Menghubungkan ke Server Android Auto (127.0.0.1:5277)...")

        while (isRunning.get()) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", AA_PORT_LOCAL), 2000)
                onStatusChanged("✔ Terhubung ke Server Lokal Android Auto (5277)")
            } catch (_: Exception) {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null

                try {
                    if (serverSocket == null || serverSocket!!.isClosed) {
                        serverSocket = ServerSocket()
                        serverSocket?.reuseAddress = true
                        serverSocket?.bind(InetSocketAddress("0.0.0.0", AA_PORT_INCOMING))
                    }
                    serverSocket?.soTimeout = 2500
                    onStatusChanged("Menunggu Android Auto di port 5277 / 5288 (Pastikan 'Server unit kepala' aktif di AA)...")
                    socket = serverSocket?.accept()
                    socket?.tcpNoDelay = true
                    onStatusChanged("✔ Android Auto masuk via Wireless Port 5288!")
                } catch (_: Exception) {
                    // Timeout, ulangi loop cek 5277
                }
            }

            if (socket != null && socket.isConnected) {
                activeSocket = socket
                try {
                    runSession(socket)
                } catch (e: Exception) {
                    Log.e(TAG, "Sesi AA terputus: ${e.message}")
                    onStatusChanged("Sesi Android Auto terputus: ${e.message}")
                } finally {
                    releaseHwDecoder()
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                    activeSocket = null
                }
            }

            if (isRunning.get()) {
                Thread.sleep(1000)
            }
        }
    }

    private fun runSession(socket: Socket) {
        val tls = AaTlsEngine(context)
        tlsEngine = tls

        initHwDecoderIfNeeded()

        val dIn = DataInputStream(socket.getInputStream())
        val dOut = DataOutputStream(socket.getOutputStream())
        dataOut = dOut

        for (b in rxBuffers) b.clear()

        // 1. Kirim Version Request (Major 1, Minor 7)
        onStatusChanged("Handshake protokol AAP v1.7...")
        val verReq = ByteBuffer.allocate(6)
            .putShort(0x0001) // MSG_VERSION_REQUEST
            .putShort(1)      // Major = 1
            .putShort(7)      // Minor = 7
            .array()
        sendRawFrame(dOut, CH_CONTROL, FLAG_BULK, verReq)

        // 2. Main Read Loop
        while (isRunning.get() && !socket.isClosed) {
            val ch = dIn.readUnsignedByte()
            val flags = dIn.readUnsignedByte()
            val len = dIn.readUnsignedShort()

            val totalLen = if ((flags and FLAG_FIRST) != 0 && (flags and FLAG_LAST) == 0) {
                dIn.readInt()
            } else {
                0
            }
            if (totalLen < 0) continue

            val payload = ByteArray(len)
            dIn.readFully(payload)

            val decrypted = if ((flags and FLAG_ENCRYPTED) != 0) {
                tls.decrypt(payload)
            } else {
                payload
            }

            val buf = rxBuffers[ch and 0x0F]
            if ((flags and FLAG_FIRST) != 0) {
                buf.clear()
            }
            buf.put(decrypted)

            if ((flags and FLAG_LAST) != 0) {
                buf.flip()
                val msgBytes = ByteArray(buf.remaining())
                buf.get(msgBytes)
                handleCompleteMessage(ch, flags, msgBytes)
            }
        }
    }

    private fun handleCompleteMessage(ch: Int, flags: Int, msg: ByteArray) {
        if (msg.size < 2) return
        val msgType = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        val bodyOffset = 2
        val bodyLen = msg.size - 2

        if (ch == CH_CONTROL) {
            handleControlMessage(msgType, msg, bodyOffset, bodyLen)
            return
        }

        val isControlFlag = (flags and FLAG_CONTROL) != 0
        if (isControlFlag && msgType == 0x0007) {
            // CHANNEL_OPEN_REQUEST -> Balas CHANNEL_OPEN_RESPONSE (status = 0 OK)
            val resp = AaProto.encode { writeVarint(1, 0) }
            sendEncryptedMessage(ch, true, 0x0008, resp)

            if (ch == CH_SENSOR) {
                sendInitialSensors()
            } else if (ch == CH_VIDEO) {
                onStatusChanged("Saluran Video AA terbuka, meminta fokus proyeksi...")
                requestVideoFocus()
            }
            return
        }

        when (ch) {
            CH_SENSOR -> handleSensorChannel(msgType, msg, bodyOffset, bodyLen)
            CH_VIDEO -> handleVideoChannel(msgType, msg, bodyOffset, bodyLen)
            CH_AUDIO_MAIN, CH_AUDIO_SYS -> handleAudioChannel(ch, msgType, msg, bodyOffset, bodyLen)
        }
    }

    private fun handleControlMessage(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        val tls = tlsEngine ?: return
        val dOut = dataOut ?: return

        when (msgType) {
            0x0002 -> { // VERSION_RESPONSE
                onStatusChanged("Memulai jabat tangan SSL/TLS...")
                val clientHello = tls.processHandshake(null)
                val frame = ByteBuffer.allocate(2 + clientHello.size)
                    .putShort(0x0003.toShort())
                    .put(clientHello)
                    .array()
                sendRawFrame(dOut, CH_CONTROL, FLAG_BULK, frame)
            }
            0x0003 -> { // SSL_HANDSHAKE dari AA
                val slice = msg.copyOfRange(offset, offset + len)
                val reply = tls.processHandshake(slice)
                if (reply.isNotEmpty()) {
                    val frame = ByteBuffer.allocate(2 + reply.size)
                        .putShort(0x0003.toShort())
                        .put(reply)
                        .array()
                    sendRawFrame(dOut, CH_CONTROL, FLAG_BULK, frame)
                }
                if (tls.isHandshakeFinished) {
                    onStatusChanged("✔ Enkripsi TLS Android Auto Aktif!")
                    val authComplete = AaProto.encode { writeVarint(1, 0) }
                    sendRawFrame(
                        dOut,
                        CH_CONTROL,
                        FLAG_BULK,
                        ByteBuffer.allocate(2 + authComplete.size)
                            .putShort(0x0004.toShort())
                            .put(authComplete)
                            .array()
                    )
                }
            }
            0x0005 -> { // SERVICE_DISCOVERY_REQUEST
                onStatusChanged("Mengirim profil layar widescreen Omoway ke Android Auto...")
                val sdr = buildServiceDiscoveryResponse()
                sendEncryptedMessage(CH_CONTROL, false, 0x0006, sdr)
            }
            0x000B -> { // PING_REQUEST
                val slice = msg.copyOfRange(offset, offset + len)
                sendEncryptedMessage(CH_CONTROL, false, 0x000C, slice)
            }
            0x000D -> { // NAVIGATION_FOCUS_REQUEST
                val navResp = AaProto.encode { writeVarint(1, 1) } // NAVIGATION_FOCUS_PROJECTED
                sendEncryptedMessage(CH_CONTROL, false, 0x000E, navResp)
            }
            0x0012 -> { // AUDIO_FOCUS_REQUEST
                val reqFields = AaProto.parseFields(msg, offset, len)
                val reqType = reqFields[1]?.firstOrNull() as? Long ?: 1L
                val state = if (reqType == 4L) 7L else 1L // GAIN atau RELEASE
                val afResp = AaProto.encode { writeVarint(1, state) }
                sendEncryptedMessage(CH_CONTROL, false, 0x0013, afResp)
            }
        }
    }

    private fun handleSensorChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        if (msgType == 0x8001) { // SENSOR_START_REQUEST
            val resp = AaProto.encode { writeVarint(1, 0) } // STATUS_OK
            sendEncryptedMessage(CH_SENSOR, false, 0x8002, resp)
            sendInitialSensors()
        }
    }

    private fun sendInitialSensors() {
        // DrivingStatus = UNRESTRICTED (0)
        val drivingEvent = AaProto.encode {
            writeMessage(13) {
                writeVarint(1, 0)
            }
        }
        sendEncryptedMessage(CH_SENSOR, false, 0x8003, drivingEvent)

        // NightMode sesuai status saat ini
        sendNightMode(isNightModeActive)
    }

    /**
     * Sinkronisasi Tema Siang / Malam Android Auto (Google Maps otomatis berganti tema!).
     */
    fun sendNightMode(isNight: Boolean) {
        isNightModeActive = isNight
        if (!isRunning.get() || activeSocket == null) return
        executor.execute {
            try {
                val nightEvent = AaProto.encode {
                    writeMessage(10) {
                        writeBool(1, isNight)
                    }
                }
                sendEncryptedMessage(CH_SENSOR, false, 0x8003, nightEvent)
            } catch (_: Exception) {}
        }
    }

    /**
     * Mengirimkan event tombol (Keycode Android) ke Android Auto (Maps, Music, Back, Home, D-Pad).
     */
    fun sendKey(keyCode: Int) {
        if (!isRunning.get() || activeSocket == null) return
        executor.execute {
            try {
                sendKeyReport(keyCode, true)
                Thread.sleep(60)
                sendKeyReport(keyCode, false)
            } catch (_: Exception) {}
        }
    }

    private fun sendKeyReport(keyCode: Int, isDown: Boolean) {
        val nowUs = System.nanoTime() / 1000L
        val report = AaProto.encode {
            writeVarint(1, nowUs)
            writeMessage(3) { // KeyEvent
                writeMessage(1) { // Key
                    writeVarint(1, keyCode)
                    writeBool(2, isDown)
                    writeVarint(3, 0)
                    writeBool(4, false)
                }
            }
        }
        sendEncryptedMessage(CH_INPUT, false, 0x8001, report)
    }

    private fun handleVideoChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x0000 -> { // MEDIA_MESSAGE_DATA (8-byte timestamp + H.264 NALU)
                if (len > 8) {
                    val ptsUs = ByteBuffer.wrap(msg, offset, 8).long
                    // Segera balas ACK agar encoder Android Auto di HP tidak menahan frame berikutnya
                    sendMediaAck(CH_VIDEO, videoSessionId, 1)
                    // Kirim ke Hardware GPU Decoder -> Surface MediaEncoder (960x360)
                    feedToHwDecoder(msg, offset + 8, len - 8, false, ptsUs)
                }
            }
            0x0001 -> { // MEDIA_MESSAGE_CODEC_CONFIG (SPS / PPS)
                sendMediaAck(CH_VIDEO, videoSessionId, 1)
                if (len > 0) {
                    feedToHwDecoder(msg, offset, len, true, 0L)
                }
            }
            0x8000 -> { // MEDIA_SETUP_REQUEST
                val setupResp = AaProto.encode {
                    writeVarint(1, 2) // Status = OK (2)
                    writeVarint(2, 2) // Max unacked = 2
                    writeVarint(3, 0) // Config index = 0
                }
                sendEncryptedMessage(CH_VIDEO, false, 0x8003, setupResp)
                requestVideoFocus()
            }
            0x8001 -> { // MEDIA_START_REQUEST
                val fields = AaProto.parseFields(msg, offset, len)
                videoSessionId = (fields[1]?.firstOrNull() as? Long ?: 0L).toInt()
                onStatusChanged("★ Streaming Android Auto Aktif (960x360 @ 30 FPS)!")
            }
            0x8007 -> { // VIDEO_FOCUS_REQUEST
                requestVideoFocus()
            }
        }
    }

    private fun handleAudioChannel(ch: Int, msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x0000, 0x0001 -> {
                sendMediaAck(ch, 0, 1)
            }
            0x8000 -> {
                val setupResp = AaProto.encode {
                    writeVarint(1, 2)
                    writeVarint(2, 2)
                    writeVarint(3, 0)
                }
                sendEncryptedMessage(ch, false, 0x8003, setupResp)
            }
        }
    }

    private fun requestVideoFocus() {
        val vfNotif = AaProto.encode {
            writeVarint(1, 1) // VIDEO_FOCUS_PROJECTED = 1
            writeBool(2, false)
        }
        sendEncryptedMessage(CH_VIDEO, false, 0x8008, vfNotif)
    }

    private fun sendMediaAck(ch: Int, session: Int, ackCount: Int) {
        val ack = AaProto.encode {
            writeVarint(1, session)
            writeVarint(2, ackCount)
        }
        sendEncryptedMessage(ch, false, 0x8004, ack)
    }

    /**
     * Membangun ServiceDiscoveryResponse sesuai skema resmi `control.proto`:
     * - VideoConfiguration (Field 1=codec_resolution(1=_800x480), Field 2=frame_rate(1=_60),
     *   Field 3=margin_width(0), Field 4=margin_height(0), Field 5=density(130),
     *   Field 10=video_codec_type(3=H264_BP)).
     * - InputChannel dengan dukungan Touchscreen + Keycodes Navigasi/Media/D-Pad.
     */
    private fun buildServiceDiscoveryResponse(): ByteArray {
        val supportedKeys = intArrayOf(
            KEYCODE_HOME, KEYCODE_BACK,
            KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_CENTER,
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_NAVIGATION, KEYCODE_MUSIC
        )

        return AaProto.encode {
            // 1. Channel 1: Sensor Service
            writeMessage(1) {
                writeVarint(1, CH_SENSOR)
                writeMessage(2) {
                    writeMessage(1) { writeVarint(1, 13) } // DRIVING_STATUS
                    writeMessage(1) { writeVarint(1, 10) } // NIGHT_DATA
                }
            }
            // 2. Channel 2: Video Sink Service (MediaSinkService field 3)
            writeMessage(1) {
                writeVarint(1, CH_VIDEO)
                writeMessage(3) {
                    writeVarint(1, 3) // MEDIA_CODEC_VIDEO_H264_BP (3)
                    // VideoConfiguration (field 3) sesuai control.proto
                    writeMessage(3) {
                        writeVarint(1, 1)   // codec_resolution = _800x480 (1)
                        writeVarint(2, 1)   // frame_rate = _60 (1)
                        writeVarint(3, 0)   // width_margin = 0
                        writeVarint(4, 0)   // height_margin = 0
                        writeVarint(5, 130) // density = 130 dpi (pas untuk layar widescreen motor)
                        writeVarint(10, 3)  // video_codec_type = MEDIA_CODEC_VIDEO_H264_BP (3)
                    }
                }
            }
            // 3. Channel 3: Input Source Service (Touchscreen + Keycodes)
            writeMessage(1) {
                writeVarint(1, CH_INPUT)
                writeMessage(4) {
                    for (kc in supportedKeys) {
                        writeVarint(1, kc)
                    }
                    writeMessage(2) { // Touchscreen 800x480
                        writeVarint(1, 800)
                        writeVarint(2, 480)
                    }
                }
            }
            // 4. Channel 4: Media Audio Sink
            writeMessage(1) {
                writeVarint(1, CH_AUDIO_MAIN)
                writeMessage(3) {
                    writeVarint(1, 1) // PCM
                    writeVarint(2, 3) // AUDIO_STREAM_MEDIA
                    writeMessage(4) {
                        writeVarint(1, 48000)
                        writeVarint(2, 16)
                        writeVarint(3, 2)
                    }
                }
            }
            // 5. Channel 5: System / Navigation Guidance Audio Sink
            writeMessage(1) {
                writeVarint(1, CH_AUDIO_SYS)
                writeMessage(3) {
                    writeVarint(1, 1) // PCM
                    writeVarint(2, 1) // AUDIO_STREAM_GUIDANCE
                    writeMessage(4) {
                        writeVarint(1, 16000)
                        writeVarint(2, 16)
                        writeVarint(3, 1)
                    }
                }
            }

            // Head Unit Metadata
            writeString(2, "OMOWAY")
            writeString(3, "DesaySV-960x360")
            writeString(4, "2026")
            writeString(5, "UMOX-AA-2.0")
            writeBool(6, true) // Left-hand drive
            writeString(7, "OMOWAY")
            writeString(8, "UMOX Native Bridge")
            writeString(9, "2.0")
            writeBool(10, false)
            writeVarint(11, 2) // DISPLAY_TYPE_CLUSTER / AUXILIARY
        }
    }

    @Synchronized
    private fun sendEncryptedMessage(ch: Int, isControlFlag: Boolean, msgType: Int, payload: ByteArray) {
        val tls = tlsEngine ?: return
        val dOut = dataOut ?: return

        val plain = ByteBuffer.allocate(2 + payload.size)
            .putShort(msgType.toShort())
            .put(payload)
            .array()

        val encrypted = tls.encrypt(plain)
        var flags = FLAG_BULK or FLAG_ENCRYPTED
        if (isControlFlag) flags = flags or FLAG_CONTROL

        sendRawFrame(dOut, ch, flags, encrypted)
    }

    @Synchronized
    private fun sendRawFrame(dOut: DataOutputStream, ch: Int, flags: Int, data: ByteArray) {
        try {
            dOut.writeByte(ch and 0xFF)
            dOut.writeByte(flags and 0xFF)
            dOut.writeShort(data.size and 0xFFFF)
            dOut.write(data)
            dOut.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menulis frame ke AA: ${e.message}")
        }
    }
}
