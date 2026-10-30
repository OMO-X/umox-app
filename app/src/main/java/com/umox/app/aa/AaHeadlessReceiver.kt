package com.umox.app.aa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.umox.app.stream.H264NaluSanitizer
import com.umox.app.stream.OmowayViewportConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Auto Headless Receiver & 1280x720 Widescreen Hardware Decoder.
 *
 * Mengadopsi protokol 1:1 dari `d0.java`, `l.java`, `g0.java`, `v.java`, dan `z.java` pada WayXAuto:
 * 1. Menghubungkan ke Android Auto Head Unit Server (`127.0.0.1:5277`) dengan fallback port `5288`.
 * 2. Mengirim `ServiceDiscoveryResponse` lengkap 5 Channel (1=Sensor[13,10], 2=Video 1280x720@60fps margin 160x360,
 *    3=Input+Touch 1120x360, 4=AudioSink, 5=MicSource) dan metadata field 2..11 yang presisi.
 * 3. Menangani `0x8000 (MEDIA_SETUP_REQUEST)` dengan `0x8003 (v.e(12))` + `0x8008 (v.l(true))`,
 *    serta `0x8001 (MEDIA_START_INDICATION)` dari ponsel dengan menyimpan `videoSessionId` dan membalas `0x8008 (v.l(false))`.
 * 4. Mendekode stream H.264 Android Auto menggunakan dedicated output thread (`g0.java`) ke `SurfaceTexture` OpenGL (`EglSurfaceCropper`).
 */
class AaHeadlessReceiver(
    private val context: Context,
    private val outputSurface: Surface? = null,
    private val targetHost: String = com.umox.app.config.AppConfig.serverHostIp.ifBlank { "127.0.0.1" },
    private val onH264Frame: (ByteArray, Int, Int) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "AaHeadlessReceiver"
        const val AA_PORT_LOCAL = 5277
        const val AA_PORT_INCOMING = 5288

        // Channel IDs (persis d0.java: 0=Control, 1=Sensor, 2=Video, 3=Input, 4=Audio, 5=Mic)
        const val CH_CONTROL = 0
        const val CH_SENSOR = 1
        const val CH_VIDEO = 2
        const val CH_INPUT = 3
        const val CH_AUDIO = 4
        const val CH_MIC = 5

        // Frame Flags
        const val FLAG_FIRST = 0x01
        const val FLAG_LAST = 0x02
        const val FLAG_BULK = 0x03
        const val FLAG_CONTROL = 0x04
        const val FLAG_ENCRYPTED = 0x08

        // Android KeyCodes untuk Remote Control & Rotary (`v.f` di WayXAuto)
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_CALL = 5
        const val KEYCODE_ENDCALL = 6
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_VOLUME_MUTE = 164
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MUSIC = 209
        const val KEYCODE_NAVIGATION = 280
        const val KEYCODE_ROTARY = 65536
        const val KEYCODE_CUSTOM_2 = 65538
    }

    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val inputExecutor = Executors.newSingleThreadExecutor()

    private var activeSocket: Socket? = null
    @Volatile
    private var activeTransport: AaProjectionTransport? = null
    val currentTransport: AaProjectionTransport? get() = activeTransport

    private var serverSocket: ServerSocket? = null
    private var tlsEngine: AaTlsEngine? = null
    private var rawOut: OutputStream? = null
    private val writeLock = Any()

    @Volatile
    var isInputChannelOpen: Boolean = false
        private set

    @Volatile
    var isVideoStreaming: Boolean = false
        private set

    @Volatile
    var isNightModeActive: Boolean = false
        private set

    private var videoSessionId = 0
    private var audioSessionId = 0
    private var decodedFrameCount = 0L

    private var hwDecoder: MediaCodec? = null
    private var decoderOutputThread: Thread? = null
    @Volatile
    private var decoderActive = false
    private val decoderLock = Any()

    private val fallbackSanitizer = H264NaluSanitizer { cleanPacket, _ ->
        onH264Frame(cleanPacket, 0, cleanPacket.size)
    }

    // Reassembly Buffers per Channel (`h0.java` di WayXAuto)
    private val rxBuffers = HashMap<Int, ByteArrayOutputStream>()

    fun start() {
        if (isRunning.getAndSet(true)) return
        startIncomingServerListener()
        executor.execute { connectionLoop() }
    }

    fun stop() {
        isRunning.set(false)
        // Kirim "ByeBye" di thread terpisah (maks 300 ms) agar main thread tidak pernah
        // terblokir oleh soket/TLS yang macet (penyebab ANR "not responding" di HP donor).
        if (activeTransport != null || activeSocket != null) {
            val byeThread = Thread({
                try {
                    if (tlsEngine?.isHandshakeFinished == true) {
                        val bye = AaProto.encode { writeVarint(1, 1) }
                        sendEncryptedMessage(CH_CONTROL, false, 0x000F, bye)
                    }
                } catch (_: Throwable) {}
            }, "UMOX-AaBye").apply { isDaemon = true; start() }
            try { byeThread.join(300) } catch (_: Exception) {}
        }

        isInputChannelOpen = false
        isVideoStreaming = false
        releaseHwDecoder()
        try {
            activeTransport?.close()
        } catch (_: Exception) {}
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            executor.shutdownNow()
        } catch (_: Exception) {}
        try {
            inputExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    /**
     * Memasukkan transport proyeksi khusus (misal: USB AOA 2.0 Bulk Endpoint)
     * untuk langsung mengambil alih aliran data proyeksi dari TCP.
     */
    fun attachTransport(transport: AaProjectionTransport) {
        if (!isRunning.get()) {
            start()
        }
        executor.execute {
            try {
                activeTransport?.close()
            } catch (_: Exception) {}
            try {
                activeSocket?.close()
            } catch (_: Exception) {}

            activeTransport = transport
            onStatusChanged("★ Mengalihkan proyeksi ke ${transport.name}...")
            try {
                runSession(transport)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Sesi transport berakhir: ${e.message}")
                    onStatusChanged("Sesi ${transport.name} terputus (${e.message})")
                }
            } finally {
                isInputChannelOpen = false
                isVideoStreaming = false
                releaseHwDecoder()
                transport.close()
                if (activeTransport == transport) {
                    activeTransport = null
                }
            }
        }
    }

    /**
     * Menginisialisasi Hardware AVC Decoder + Dedicated Output Thread persis `d0.java` lines 176-193 & `g0.java`.
     */
    private fun ensureHwDecoderStarted(): MediaCodec? {
        val surface = outputSurface ?: return null
        synchronized(decoderLock) {
            if (hwDecoder != null && decoderActive) {
                return hwDecoder
            }
            releaseHwDecoder()
            if (!surface.isValid) {
                Log.w(TAG, "Output surface belum valid untuk HW Decoder")
                return null
            }
            return try {
                val width = OmowayViewportConfig.aaSourceWidth
                val height = OmowayViewportConfig.aaSourceHeight
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_097_152)
                }
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder.configure(format, surface, null, 0)
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                decoder.start()
                hwDecoder = decoder
                decoderActive = true

                decoderOutputThread = Thread({
                    runDecoderOutputLoop(decoder)
                }, "UMOX-AaVideoOutput").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                Log.i(TAG, "✔ Hardware AVC Decoder (${width}x${height}) + Output Thread siap!")
                decoder
            } catch (e: Exception) {
                Log.e(TAG, "Gagal konfigurasi HW Decoder: ${e.message}", e)
                hwDecoder = null
                decoderActive = false
                null
            }
        }
    }

    private fun runDecoderOutputLoop(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (decoderActive && isRunning.get()) {
            try {
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val render = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        decodedFrameCount++
                        if (decodedFrameCount == 1L) {
                            onStatusChanged("★ Frame video pertama Android Auto berhasil didekode ke Live Preview!")
                        }
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = decoder.outputFormat
                    Log.i(TAG, "Output format Decoder AA: $fmt")
                }
            } catch (e: Exception) {
                if (decoderActive) {
                    Log.w(TAG, "Decoder output loop berhenti: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Memasukkan buffer H.264 ke input `MediaCodec` persis `g0.a(byte[], int, int, long, int)` di WayXAuto.
     */
    private fun queueToHwDecoder(data: ByteArray, offset: Int, length: Int, ptsUs: Long, codecFlags: Int) {
        val decoder = ensureHwDecoderStarted()
        if (decoder == null) {
            if (codecFlags == 0) {
                fallbackSanitizer.processAnnexB(data, offset, length)
            }
            return
        }

        for (attempt in 0 until 5) {
            if (!decoderActive) return
            try {
                val inIdx = decoder.dequeueInputBuffer(100_000L)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(data, offset, length)
                        decoder.queueInputBuffer(inIdx, 0, length, ptsUs, codecFlags)
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal queue video data ke HW Decoder: ${e.message}", e)
                return
            }
        }
        Log.w(TAG, "Input buffer HW Decoder penuh; melewati $length bytes")
    }

    private fun releaseHwDecoder() {
        decoderActive = false
        val outThread = decoderOutputThread
        decoderOutputThread = null
        if (outThread != null && outThread !== Thread.currentThread()) {
            try {
                outThread.join(400L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(decoderLock) {
            val dec = hwDecoder
            hwDecoder = null
            if (dec != null) {
                try {
                    dec.stop()
                } catch (_: Exception) {}
                try {
                    dec.release()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Listener Server Port 5288 Nirkabel WPP Permanen & Tanpa Jeda.
     * Siaga 24/7 menerima koneksi dari Android Auto (Gearhead) segera setelah handshake WPP.
     */
    private fun startIncomingServerListener() {
        executor.execute {
            try {
                if (serverSocket == null || serverSocket!!.isClosed) {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", AA_PORT_INCOMING))
                    }
                }
                Log.i(TAG, "✔ ServerSocket Android Auto SIAP & MENDENGARKAN pada 0.0.0.0:$AA_PORT_INCOMING (Wireless WPP)")
                onStatusChanged("★ Server Nirkabel AA Siap (0.0.0.0:$AA_PORT_INCOMING)...")

                while (isRunning.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        socket.tcpNoDelay = true
                        Log.i(TAG, "✔ Sambungan TCP Android Auto masuk dari ${socket.remoteSocketAddress}!")
                        onStatusChanged("✔ Android Auto terhubung dari ${socket.inetAddress.hostAddress} (Wireless WPP)!")
                        
                        val transport = AaSocketTransport(socket)
                        attachTransport(transport)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.d(TAG, "Exception accept ServerSocket: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal bind ServerSocket port $AA_PORT_INCOMING: ${e.message}")
            }
        }
    }

    private fun connectionLoop() {
        val isRemote = targetHost != "127.0.0.1" && targetHost != "localhost"
        val hostLabel = if (isRemote) targetHost else "127.0.0.1"

        while (isRunning.get()) {
            if (activeTransport != null && activeTransport!!.isConnected) {
                // Sesi USB, Wireless WPP, atau transport lain sedang aktif
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            // Hubungkan ke Server Android Auto (127.0.0.1:5277 untuk Standalone, atau IP Remote untuk Client)
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 12000
                socket.connect(InetSocketAddress(targetHost, AA_PORT_LOCAL), 2500)
                Log.i(TAG, "✔ Terhubung ke Server Android Auto ($hostLabel:$AA_PORT_LOCAL)")
                onStatusChanged("✔ Terhubung ke Server Android Auto ($hostLabel:$AA_PORT_LOCAL)")
                val transport = AaSocketTransport(socket)
                attachTransport(transport)
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
            }

            if (isRunning.get()) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun runSession(transport: AaProjectionTransport) {
        val tls = AaTlsEngine(context)
        tlsEngine = tls

        val dIn = DataInputStream(transport.inputStream)
        val out = transport.outputStream
        rawOut = out
        rxBuffers.clear()
        decodedFrameCount = 0L
        videoSessionId = 0
        audioSessionId = 0

        // 1. Kirim Version Request (Major 1, Minor 1 - persis WayXAuto l.java)
        onStatusChanged("Handshake protokol AAP v1.1 via ${transport.name}...")
        val verReq = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x01)
        sendRawFrame(out, CH_CONTROL, FLAG_BULK, verReq)

        // 2. Main Read Loop (`l.java` + `h0.java` di WayXAuto)
        val header = ByteArray(4)
        val totalLenBuf = ByteArray(4)

        while (isRunning.get() && transport.isConnected) {
            val b0 = dIn.read()
            if (b0 == -1) break
            header[0] = b0.toByte()
            dIn.readFully(header, 1, 3)
            val ch = header[0].toInt() and 0xFF
            val flags = header[1].toInt() and 0xFF
            val len = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

            val isFirst = (flags and FLAG_FIRST) != 0
            val isLast = (flags and FLAG_LAST) != 0
            if (isFirst && !isLast) {
                dIn.readFully(totalLenBuf)
            }

            val payload = ByteArray(len)
            if (len > 0) {
                dIn.readFully(payload)
            }

            val decrypted = if ((flags and FLAG_ENCRYPTED) != 0) {
                tls.decrypt(payload)
            } else {
                payload
            }

            val completeMsg = reassembleFrame(ch, flags, decrypted)
            if (completeMsg != null) {
                handleCompleteMessage(ch, completeMsg)
            }
        }
    }

    private fun reassembleFrame(ch: Int, flags: Int, chunk: ByteArray): ByteArray? {
        val isFirst = (flags and FLAG_FIRST) != 0
        val isLast = (flags and FLAG_LAST) != 0
        if (isFirst && isLast) {
            rxBuffers.remove(ch)
            return chunk
        }
        var stream = rxBuffers[ch]
        if (isFirst || stream == null) {
            stream = ByteArrayOutputStream(chunk.size * 4)
            rxBuffers[ch] = stream
        }
        stream.write(chunk, 0, chunk.size)
        if (!isLast) return null
        rxBuffers.remove(ch)
        return stream.toByteArray()
    }

    private fun handleCompleteMessage(ch: Int, msg: ByteArray) {
        if (msg.size < 2) return
        val msgType = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        val bodyOffset = 2
        val bodyLen = msg.size - 2

        // Persis d0.java lines 94-98: Setiap CHANNEL_OPEN_REQUEST (msgType == 7) di channel != 0 langsung dibalas!
        if (msgType == 0x0007 && ch != CH_CONTROL) {
            val openResp = AaProto.encode { writeVarint(1, 0) }
            sendEncryptedMessage(ch, true, 0x0008, openResp)
            if (ch == CH_VIDEO) {
                onStatusChanged("Saluran Video AA terbuka, menyiapkan dekoder...")
            } else if (ch == CH_INPUT) {
                isInputChannelOpen = true
            }
            return
        }

        when (ch) {
            CH_CONTROL -> handleControlMessage(msgType, msg, bodyOffset, bodyLen)
            CH_SENSOR -> handleSensorChannel(msgType, msg, bodyOffset, bodyLen)
            CH_VIDEO -> handleVideoChannel(msgType, msg, bodyOffset, bodyLen)
            CH_INPUT -> handleInputChannel(msgType)
            CH_AUDIO -> handleAudioChannel(msgType, msg, bodyOffset, bodyLen)
            CH_MIC -> handleMicChannel(msgType)
        }
    }

    private fun handleControlMessage(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        val tls = tlsEngine ?: return
        val out = rawOut ?: return

        when (msgType) {
            0x0002 -> { // VERSION_RESPONSE (`d0.java` lines 404-433)
                if (len >= 6) {
                    val status = ((msg[offset + 4].toInt() and 0xFF) shl 8) or (msg[offset + 5].toInt() and 0xFF)
                    if (status != 0) {
                        throw IllegalStateException("Versi protokol Android Auto ditolak ($status)")
                    }
                }
                onStatusChanged("Memulai negosiasi TLS Android Auto...")
                val clientHello = tls.processHandshake(null)
                val frame = ByteBuffer.allocate(2 + clientHello.size)
                    .putShort(0x0003.toShort())
                    .put(clientHello)
                    .array()
                sendRawFrame(out, CH_CONTROL, FLAG_BULK, frame)
            }
            0x0003 -> { // SSL_HANDSHAKE (`d0.java` lines 383-401)
                val slice = msg.copyOfRange(offset, offset + len)
                val reply = tls.processHandshake(slice)
                if (reply.isNotEmpty()) {
                    val frame = ByteBuffer.allocate(2 + reply.size)
                        .putShort(0x0003.toShort())
                        .put(reply)
                        .array()
                    sendRawFrame(out, CH_CONTROL, FLAG_BULK, frame)
                }
                if (tls.isHandshakeFinished) {
                    try {
                        activeSocket?.soTimeout = 0
                    } catch (_: Exception) {}
                    onStatusChanged("✔ Enkripsi TLS Android Auto Aktif!")
                    val authComplete = AaProto.encode { writeVarint(1, 0) }
                    val frame = ByteBuffer.allocate(2 + authComplete.size)
                        .putShort(0x0004.toShort())
                        .put(authComplete)
                        .array()
                    sendRawFrame(out, CH_CONTROL, FLAG_BULK, frame)
                }
            }
            0x0005 -> { // SERVICE_DISCOVERY_REQUEST (`d0.java` lines 279-381)
                onStatusChanged("Mengirim profil layar widescreen 1280×720 (Active 1120×360) ke Android Auto...")
                val sdr = buildServiceDiscoveryResponse()
                sendEncryptedMessage(CH_CONTROL, false, 0x0006, sdr)
            }
            0x000B -> { // PING_REQUEST (`d0.java` lines 238-243)
                val fields = AaProto.parseFields(msg, offset, len)
                val ts = fields[1]?.firstOrNull() as? Long ?: 0L
                val pingResp = AaProto.encode {
                    writeVarint(1, ts)
                }
                sendEncryptedMessage(CH_CONTROL, false, 0x000C, pingResp)
            }
            0x000D -> { // NAVIGATION_FOCUS_REQUEST (`d0.java` lines 246-250)
                val navResp = AaProto.encode { writeVarint(1, 2) }
                sendEncryptedMessage(CH_CONTROL, false, 0x000E, navResp)
            }
            0x000F -> { // BYEBYE_REQUEST (`d0.java` lines 253-258)
                sendEncryptedMessage(CH_CONTROL, false, 0x0010, ByteArray(0))
                activeTransport?.close()
                activeSocket?.close()
            }
            0x0010 -> { // BYEBYE_RESPONSE (`d0.java` lines 259-261)
                activeTransport?.close()
                activeSocket?.close()
            }
            0x0012 -> { // AUDIO_FOCUS_REQUEST (`d0.java` lines 264-272)
                val reqFields = AaProto.parseFields(msg, offset, len)
                val reqType = (reqFields[1]?.firstOrNull() as? Long ?: 1L).toInt()
                val focusState = if (reqType == 4) 3 else 1
                val afResp = AaProto.encode {
                    writeVarint(1, focusState)
                    writeVarint(2, 0)
                }
                sendEncryptedMessage(CH_CONTROL, false, 0x0013, afResp)
            }
        }
    }

    /**
     * Persis `d0.java` lines 213-232:
     * Mendukung sensor 13 (NIGHT_MODE) dan sensor 10 (DRIVING_STATUS).
     */
    private fun handleSensorChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        if (msgType == 0x8001) { // SENSOR_START_REQUEST
            val fields = AaProto.parseFields(msg, offset, len)
            val sensorType = (fields[1]?.firstOrNull() as? Long ?: -1L).toInt()
            val startResp = AaProto.encode { writeVarint(1, 0) }
            sendEncryptedMessage(CH_SENSOR, false, 0x8002, startResp)

            if (sensorType == 13) {
                val event13 = AaProto.encode {
                    writeMessage(13) {
                        writeVarint(1, 0)
                    }
                }
                sendEncryptedMessage(CH_SENSOR, false, 0x8003, event13)
            } else if (sensorType == 10) {
                val event10 = AaProto.encode {
                    writeMessage(10) {
                        writeVarint(1, if (isNightModeActive) 1 else 0)
                    }
                }
                sendEncryptedMessage(CH_SENSOR, false, 0x8003, event10)
            }
        }
    }

    /**
     * Persis `d0.java` lines 150-211:
     * - `0x8000 (32768)`: Balas `0x8003 (v.e(12))` + `0x8008 (v.l(true))` (`unsolicited = 1`).
     * - `0x8001 (32769)`: Simpan `videoSessionId` dari request, balas `0x8008 (v.l(false))`.
     * - `0x8007 (32775)`: Balas `0x8008 (v.l(false))`.
     * - `0x0001`: `MEDIA_CODEC_CONFIG` (`flags = 2`) -> start decoder & ack.
     * - `0x0000`: `MEDIA_WITH_TIMESTAMP` (8-byte timestamp BE) -> decode & ack.
     */
    private fun handleVideoChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x8000 -> { // MEDIA_SETUP_REQUEST (32768)
                val setupResp = AaProto.encode {
                    writeVarint(1, 2)
                    writeVarint(2, 12)
                    writeVarint(3, 0)
                }
                sendEncryptedMessage(CH_VIDEO, false, 0x8003, setupResp)
                sendVideoFocusIndication(projected = true, unsolicited = true)
                onStatusChanged("★ Setup Video AA diterima, meminta fokus proyeksi...")
            }
            0x8001 -> { // MEDIA_START_INDICATION (32769) dari ponsel!
                val fields = AaProto.parseFields(msg, offset, len)
                videoSessionId = (fields[1]?.firstOrNull() as? Long ?: 0L).toInt()
                isVideoStreaming = true
                sendVideoFocusIndication(projected = true, unsolicited = false)
                onStatusChanged("★ Streaming Android Auto Aktif (Sesi $videoSessionId • 960×360 @ 60 FPS)!")
            }
            0x8002 -> { // MEDIA_STOP_INDICATION (32770)
                onStatusChanged("Video dihentikan sementara oleh Android Auto")
            }
            0x8007 -> { // VIDEO_FOCUS_REQUEST (32775)
                sendVideoFocusIndication(projected = true, unsolicited = false)
            }
            0x0001 -> { // MEDIA_CODEC_CONFIG (SPS/PPS)
                if (len > 0) {
                    queueToHwDecoder(msg, offset, len, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                }
                sendMediaAck(CH_VIDEO, videoSessionId, 1)
            }
            0x0000 -> { // MEDIA_WITH_TIMESTAMP (8-byte timestamp + H.264 slice)
                if (len > 8) {
                    var ptsUs = 0L
                    for (i in 0 until 8) {
                        ptsUs = (ptsUs shl 8) or (msg[offset + i].toLong() and 0xFFL)
                    }
                    queueToHwDecoder(msg, offset + 8, len - 8, ptsUs, 0)
                }
                sendMediaAck(CH_VIDEO, videoSessionId, 1)
            }
        }
    }

    private fun handleInputChannel(msgType: Int) {
        if (msgType == 0x8002) { // KEY_BINDING_REQUEST (32770)
            val resp = AaProto.encode { writeVarint(1, 0) }
            sendEncryptedMessage(CH_INPUT, false, 0x8003, resp)
            isInputChannelOpen = true
        }
    }

    /**
     * Persis `d0.java` lines 123-140: Saluran Audio Sink (Channel 4).
     */
    private fun handleAudioChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x8000 -> { // MEDIA_SETUP_REQUEST (32768)
                val resp = AaProto.encode {
                    writeVarint(1, 2)
                    writeVarint(2, 16)
                    writeVarint(3, 0)
                }
                sendEncryptedMessage(CH_AUDIO, false, 0x8003, resp)
            }
            0x8001 -> { // MEDIA_START_INDICATION (32769)
                val fields = AaProto.parseFields(msg, offset, len)
                audioSessionId = (fields[1]?.firstOrNull() as? Long ?: 0L).toInt()
            }
            0x0000, 0x0001 -> {
                sendMediaAck(CH_AUDIO, audioSessionId, 1)
            }
        }
    }

    /**
     * Persis `d0.java` lines 109-121: Saluran Microphone Source (Channel 5).
     */
    private fun handleMicChannel(msgType: Int) {
        when (msgType) {
            0x8000 -> { // MEDIA_SETUP_REQUEST (32768)
                val resp = AaProto.encode {
                    writeVarint(1, 2)
                    writeVarint(2, 1)
                    writeVarint(3, 0)
                }
                sendEncryptedMessage(CH_MIC, false, 0x8003, resp)
            }
            0x8005 -> { // MICROPHONE_REQUEST (32773)
                val resp = AaProto.encode {
                    writeVarint(1, 0)
                    writeVarint(2, 0)
                }
                sendEncryptedMessage(CH_MIC, false, 0x8006, resp)
            }
        }
    }

    /**
     * Persis `v.l(boolean unsolicited)` di WayXAuto (`v.java` lines 364-373):
     * Field 1 = 1 (VIDEO_FOCUS_PROJECTED), Field 2 = 1 jika unsolicited, 0 jika respons.
     */
    private fun sendVideoFocusIndication(projected: Boolean, unsolicited: Boolean) {
        val vf = AaProto.encode {
            writeVarint(1, if (projected) 1 else 2)
            writeVarint(2, if (unsolicited) 1 else 0)
        }
        sendEncryptedMessage(CH_VIDEO, false, 0x8008, vf)
    }

    private fun sendMediaAck(ch: Int, session: Int, ackCount: Int) {
        val ack = AaProto.encode {
            writeVarint(1, session)
            writeVarint(2, ackCount)
        }
        sendEncryptedMessage(ch, false, 0x8004, ack)
    }

    fun sendKey(keyCode: Int) {
        if (!isRunning.get() || activeTransport == null) return
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                sendEncryptedMessage(CH_INPUT, false, 0x8001, buildKeyReport(nowNs, keyCode, true))
                sendEncryptedMessage(CH_INPUT, false, 0x8001, buildKeyReport(nowNs + 1_000_000L, keyCode, false))
            } catch (_: Exception) {}
        }
    }

    fun sendRotary(delta: Int) {
        if (!isRunning.get() || activeTransport == null) return
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                val report = AaProto.encode {
                    writeVarint(1, nowNs)
                    writeMessage(6) {
                        writeMessage(1) {
                            writeVarint(1, KEYCODE_ROTARY)
                            writeVarint(2, delta)
                        }
                    }
                }
                sendEncryptedMessage(CH_INPUT, false, 0x8001, report)
            } catch (_: Exception) {}
        }
    }

    fun sendTouch(normX: Float, normY: Float, action: Int) {
        if (!isRunning.get() || activeTransport == null) return
        val x = (normX.coerceIn(0f, 1f) * OmowayViewportConfig.aaActiveWidth).toInt()
        val y = (normY.coerceIn(0f, 1f) * OmowayViewportConfig.aaActiveHeight).toInt()
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                val report = AaProto.encode {
                    writeVarint(1, nowNs)
                    writeMessage(3) {
                        writeMessage(1) {
                            writeVarint(1, x)
                            writeVarint(2, y)
                            writeVarint(3, 0)
                        }
                        writeVarint(3, action)
                    }
                }
                sendEncryptedMessage(CH_INPUT, false, 0x8001, report)
            } catch (_: Exception) {}
        }
    }

    fun sendNightMode(isNight: Boolean) {
        isNightModeActive = isNight
        if (!isRunning.get() || activeTransport == null) return
        inputExecutor.execute {
            try {
                val nightEvent = AaProto.encode {
                    writeMessage(10) {
                        writeVarint(1, if (isNight) 1 else 0)
                    }
                }
                sendEncryptedMessage(CH_SENSOR, false, 0x8003, nightEvent)
            } catch (_: Exception) {}
        }
    }

    private fun buildKeyReport(timestampNs: Long, keyCode: Int, isDown: Boolean): ByteArray {
        return AaProto.encode {
            writeVarint(1, timestampNs)
            writeMessage(4) {
                writeMessage(1) {
                    writeVarint(1, keyCode)
                    writeVarint(2, if (isDown) 1 else 0)
                    writeVarint(3, 0)
                    writeVarint(4, 0)
                }
            }
        }
    }

    /**
     * Membangun `ServiceDiscoveryResponse` 100% identik dengan `d0.java` lines 307-380 di WayXAuto:
     * - Channel 1: SensorSourceService (13 = DRIVING_STATUS, 10 = NIGHT_MODE)
     * - Channel 2: MediaSinkService (Video H264_BP, Field 4 = 1280x720@60fps margin 160x360)
     * - Channel 3: InputSourceService (Keycodes + Capacitive Touchscreen 1120x360)
     * - Channel 4: MediaSinkService (Audio 16kHz 16-bit Mono)
     * - Channel 5: MediaSourceService (Mic 16kHz 16-bit Mono)
     * - Fields 2..11: Head Unit Metadata
     */
    private fun buildServiceDiscoveryResponse(): ByteArray {
        val supportedKeys = intArrayOf(
            KEYCODE_HOME, KEYCODE_BACK, KEYCODE_CALL, KEYCODE_ENDCALL,
            KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_CENTER,
            KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_MUTE,
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MUSIC, KEYCODE_NAVIGATION,
            KEYCODE_ROTARY, KEYCODE_CUSTOM_2
        )

        return AaProto.encode {
            // Channel 1: SensorSourceService (`caVar10`..`caVar12` & `caVar21` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_SENSOR)
                writeMessage(2) {
                    writeMessage(1) {
                        writeVarint(1, 13)
                    }
                    writeMessage(1) {
                        writeVarint(1, 10)
                    }
                }
            }

            // Channel 2: MediaSinkService Video (`caVar13`..`caVar14` & `caVar22` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_VIDEO)
                writeMessage(3) {
                    writeVarint(1, 3) // MEDIA_CODEC_VIDEO_H264_BP = 3
                    writeMessage(4) { // video_configs (Field 4)
                        writeVarint(1, 2) // 1280x720
                        writeVarint(2, 1) // 60 FPS
                        writeVarint(3, OmowayViewportConfig.aaWidthMargin)  // 160
                        writeVarint(4, OmowayViewportConfig.aaHeightMargin) // 360
                        writeVarint(5, 160) // 160 dpi
                    }
                    writeVarint(5, 1)
                }
            }

            // Channel 3: InputSourceService (`caVar19` & `caVar23` di d0.java) + Touchscreen
            writeMessage(1) {
                writeVarint(1, CH_INPUT)
                writeMessage(4) {
                    for (kc in supportedKeys) {
                        writeVarint(1, kc)
                    }
                    writeMessage(2) {
                        writeVarint(1, OmowayViewportConfig.aaActiveWidth)
                        writeVarint(2, OmowayViewportConfig.aaActiveHeight)
                        writeVarint(3, 1)
                    }
                }
            }

            // Channel 4: MediaSinkService Audio (`caVar15`..`caVar16` & `caVar24` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_AUDIO)
                writeMessage(3) {
                    writeVarint(1, 1) // MEDIA_CODEC_AUDIO_PCM = 1
                    writeVarint(2, 2) // AUDIO_STREAM_GUIDANCE = 2
                    writeMessage(3) { // audio_configs (Field 3)
                        writeVarint(1, 16000)
                        writeVarint(2, 16)
                        writeVarint(3, 1)
                    }
                }
            }

            // Channel 5: MediaSourceService Microphone (`caVar17`..`caVar18` & `caVar25` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_MIC)
                writeMessage(5) {
                    writeVarint(1, 1) // MEDIA_CODEC_AUDIO_PCM = 1
                    writeMessage(2) {
                        writeVarint(1, 16000)
                        writeVarint(2, 16)
                        writeVarint(3, 1)
                    }
                }
            }

            // Head Unit Metadata konsisten dengan WPP Bluetooth ("UMOX", "Universal HU")
            writeString(2, "UMOX")
            writeString(3, "Universal HU")
            writeString(4, "2026")
            writeString(5, "UMOX-001")
            writeVarint(6, 0)
            writeString(7, "UMOX")
            writeString(8, "Universal HU")
            writeString(9, "1")
            writeString(10, "1.0")
            writeVarint(11, 0)
        }
    }

    private fun sendEncryptedMessage(ch: Int, isControlFlag: Boolean, msgType: Int, payload: ByteArray) {
        val tls = tlsEngine ?: return
        val out = rawOut ?: return

        synchronized(writeLock) {
            val plain = ByteBuffer.allocate(2 + payload.size)
                .putShort(msgType.toShort())
                .put(payload)
                .array()

            val encrypted = tls.encrypt(plain)
            var flags = FLAG_BULK or FLAG_ENCRYPTED
            if (isControlFlag) flags = flags or FLAG_CONTROL

            sendRawFrame(out, ch, flags, encrypted)
        }
    }

    private fun sendRawFrame(out: OutputStream, ch: Int, flags: Int, data: ByteArray) {
        synchronized(writeLock) {
            try {
                val frame = ByteArray(4 + data.size)
                frame[0] = (ch and 0xFF).toByte()
                frame[1] = (flags or FLAG_BULK and 0xFF).toByte()
                frame[2] = ((data.size ushr 8) and 0xFF).toByte()
                frame[3] = (data.size and 0xFF).toByte()
                System.arraycopy(data, 0, frame, 4, data.size)
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal menulis frame ke AA: ${e.message}")
            }
        }
    }
}
