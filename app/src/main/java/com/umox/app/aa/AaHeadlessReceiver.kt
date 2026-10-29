package com.umox.app.aa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.umox.app.stream.H264NaluSanitizer
import com.umox.app.stream.MediaEncoder
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
 * Mengadopsi protokol eksak dari `d0.java`, `l.java`, `g0.java`, `v.java`, dan `z.java` pada WayXAuto:
 * 1. Menghubungkan ke Android Auto Head Unit Server (`127.0.0.1:5277`) dengan fallback port `5288`.
 * 2. Mengirim `ServiceDiscoveryResponse` dengan `MediaSinkService.video_configs` (Field 4!)
 *    pada resolusi `1280x720 @ 60 FPS` (`width_margin = 160, height_margin = 360` -> area aktif `1120x360` widescreen).
 * 3. Mengirim urutan pembuka video lengkap: `MEDIA_SETUP_RESPONSE (0x8003)` -> `MEDIA_START_INDICATION (0x8001)` -> `VIDEO_FOCUS_INDICATION (0x8008)`.
 * 4. Mendekode stream H.264 Android Auto ke `SurfaceTexture` milik `EglSurfaceCropper` (Zero-CPU-Copy).
 * 5. Mendukung Touch Input langsung pada Live Preview (`sendTouch`), Key Event (`sendKey`), dan Rotary (`sendRotary`).
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

        // Channel IDs (persis d0.java: 0=Control, 1=Sensor, 2=Video, 3=Input)
        const val CH_CONTROL = 0
        const val CH_SENSOR = 1
        const val CH_VIDEO = 2
        const val CH_INPUT = 3

        // Frame Flags
        const val FLAG_FIRST = 0x01
        const val FLAG_LAST = 0x02
        const val FLAG_BULK = 0x03
        const val FLAG_CONTROL = 0x04
        const val FLAG_ENCRYPTED = 0x08

        // Android KeyCodes untuk Remote Control & Rotary
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
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
    private var hwDecoder: MediaCodec? = null
    private var decoderConfigured = false
    private var cachedSps = ByteArray(0)
    private var cachedPps = ByteArray(0)
    private val decoderLock = Any()

    private val fallbackSanitizer = H264NaluSanitizer { cleanPacket, _ ->
        onH264Frame(cleanPacket, 0, cleanPacket.size)
    }

    // Reassembly Buffers per Channel (`h0.java` di WayXAuto)
    private val rxBuffers = HashMap<Int, ByteArrayOutputStream>()

    fun start() {
        if (isRunning.getAndSet(true)) return
        executor.execute { connectionLoop() }
    }

    fun stop() {
        isRunning.set(false)
        // Kirim BYEBYE_REQUEST jika sesi masih hidup (`a0.java` line 90)
        try {
            if (isVideoStreaming) {
                val bye = AaProto.encode { writeVarint(1, 1) }
                sendEncryptedMessage(CH_CONTROL, false, 0x000F, bye)
            }
        } catch (_: Exception) {}

        isInputChannelOpen = false
        isVideoStreaming = false
        releaseHwDecoder()
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Menginisialisasi / mengonfigurasi ulang Hardware AVC Decoder (`g0.java` di WayXAuto).
     */
    private fun ensureHwDecoderConfigured(sps: ByteArray, pps: ByteArray): MediaCodec? {
        val surface = outputSurface ?: return null
        synchronized(decoderLock) {
            if (hwDecoder != null && decoderConfigured) {
                return hwDecoder
            }
            releaseHwDecoder()
            return try {
                val width = OmowayViewportConfig.aaSourceWidth
                val height = OmowayViewportConfig.aaSourceHeight
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    if (sps.isNotEmpty()) {
                        setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    }
                    if (pps.isNotEmpty()) {
                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    }
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder.configure(format, surface, null, 0)
                decoder.start()
                hwDecoder = decoder
                decoderConfigured = true
                Log.i(TAG, "✔ Hardware AVC Decoder (${width}x${height}) -> GL SurfaceTexture siap!")
                decoder
            } catch (e: Exception) {
                Log.w(TAG, "Gagal konfigurasi HW Decoder: ${e.message}")
                hwDecoder = null
                decoderConfigured = false
                null
            }
        }
    }

    /**
     * Mendekode paket H.264 dari Android Auto ke `SurfaceTexture` (`g0.a` & `g0.b` di WayXAuto).
     */
    private fun feedToHwDecoder(data: ByteArray, offset: Int, length: Int, isConfig: Boolean, ptsUs: Long) {
        val slice = data.copyOfRange(offset, offset + length)
        val normalized = MediaEncoder.normalizeAnnexBNalus(slice)
        if (normalized.isEmpty()) return

        val sps = MediaEncoder.extractNaluByType(7, normalized)
        val pps = MediaEncoder.extractNaluByType(8, normalized)
        val spsChanged = sps.isNotEmpty() && !sps.contentEquals(cachedSps)
        val ppsChanged = pps.isNotEmpty() && !pps.contentEquals(cachedPps)
        if (sps.isNotEmpty()) cachedSps = sps
        if (pps.isNotEmpty()) cachedPps = pps

        if (spsChanged || ppsChanged) {
            synchronized(decoderLock) {
                decoderConfigured = false
            }
        }

        val decoder = ensureHwDecoderConfigured(cachedSps, cachedPps)
        if (decoder == null) {
            if (!isConfig) {
                fallbackSanitizer.processAnnexB(normalized, 0, normalized.size)
            }
            return
        }

        synchronized(decoderLock) {
            try {
                val info = MediaCodec.BufferInfo()
                drainDecoderOutputs(decoder, info)

                var inIdx = decoder.dequeueInputBuffer(10_000L)
                if (inIdx < 0) {
                    drainDecoderOutputs(decoder, info)
                    inIdx = decoder.dequeueInputBuffer(10_000L)
                }

                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)
                    if (inBuf != null && inBuf.capacity() >= normalized.size) {
                        inBuf.clear()
                        inBuf.put(normalized)
                        val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        val safePts = if (ptsUs > 0L) ptsUs else (System.nanoTime() / 1000L)
                        decoder.queueInputBuffer(inIdx, 0, normalized.size, safePts, flags)
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    }
                }

                drainDecoderOutputs(decoder, info)
            } catch (e: Exception) {
                Log.w(TAG, "HW Decoder reset karena error: ${e.message}")
                releaseHwDecoder()
            }
        }
    }

    private fun drainDecoderOutputs(decoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIdx = decoder.dequeueOutputBuffer(info, 0L)
            if (outIdx >= 0) {
                val shouldRender = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                decoder.releaseOutputBuffer(outIdx, shouldRender)
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = decoder.outputFormat
                val decW = fmt.getInteger(MediaFormat.KEY_WIDTH)
                val decH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                Log.i(TAG, "Format output Decoder AA: ${decW}x${decH}")
            } else {
                break
            }
        }
    }

    private fun releaseHwDecoder() {
        synchronized(decoderLock) {
            decoderConfigured = false
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
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress("127.0.0.1", AA_PORT_LOCAL), 2500)
                onStatusChanged("✔ Terhubung ke Server Lokal Android Auto (5277)")
            } catch (_: Exception) {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null

                try {
                    if (serverSocket == null || serverSocket!!.isClosed) {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress("0.0.0.0", AA_PORT_INCOMING))
                        }
                    }
                    serverSocket?.soTimeout = 2000
                    onStatusChanged("Menunggu Server Unit Kepala Android Auto (127.0.0.1:5277)...")
                    socket = serverSocket?.accept()
                    socket?.tcpNoDelay = true
                    onStatusChanged("✔ Android Auto terhubung via Port 5288!")
                } catch (_: Exception) {}
            }

            if (socket != null && socket.isConnected) {
                activeSocket = socket
                try {
                    runSession(socket)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Sesi AA berakhir: ${e.message}")
                        onStatusChanged("Menyambungkan ulang ke Android Auto...")
                    }
                } finally {
                    isInputChannelOpen = false
                    isVideoStreaming = false
                    releaseHwDecoder()
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                    activeSocket = null
                }
            }

            if (isRunning.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun runSession(socket: Socket) {
        val tls = AaTlsEngine(context)
        tlsEngine = tls

        val dIn = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        rawOut = out
        rxBuffers.clear()

        // 1. Kirim Version Request (Major 1, Minor 1 - persis WayXAuto l.java line 113)
        onStatusChanged("Handshake protokol AAP v1.1...")
        val verReq = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x01)
        sendRawFrame(out, CH_CONTROL, FLAG_BULK, verReq)

        // Disable read timeout setelah handshake awal berjalan
        socket.soTimeout = 0

        // 2. Main Read Loop (`l.java` + `h0.java` di WayXAuto)
        val header = ByteArray(4)
        val totalLenBuf = ByteArray(4)

        while (isRunning.get() && !socket.isClosed) {
            dIn.readFully(header)
            val ch = header[0].toInt() and 0xFF
            val flags = header[1].toInt() and 0xFF
            val len = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

            val isFirst = (flags and FLAG_FIRST) != 0
            val isLast = (flags and FLAG_LAST) != 0
            if (isFirst && !isLast) {
                dIn.readFully(totalLenBuf)
            }

            val payload = ByteArray(len)
            dIn.readFully(payload)

            val decrypted = if ((flags and FLAG_ENCRYPTED) != 0) {
                tls.decrypt(payload)
            } else {
                payload
            }

            val completeMsg = reassembleFrame(ch, flags, decrypted)
            if (completeMsg != null) {
                handleCompleteMessage(ch, flags, completeMsg)
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

    private fun handleCompleteMessage(ch: Int, flags: Int, msg: ByteArray) {
        if (msg.size < 2) return
        val msgType = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        val bodyOffset = 2
        val bodyLen = msg.size - 2

        if (ch == CH_CONTROL) {
            handleControlMessage(msgType, msg, bodyOffset, bodyLen)
            return
        }

        when (ch) {
            CH_SENSOR -> handleSensorChannel(msgType, msg, bodyOffset, bodyLen)
            CH_VIDEO -> handleVideoChannel(msgType, msg, bodyOffset, bodyLen)
            CH_INPUT -> handleInputChannel(msgType)
            else -> {
                if ((flags and FLAG_CONTROL) != 0 && msgType == 0x0007) {
                    val resp = AaProto.encode { writeVarint(1, 0) }
                    sendEncryptedMessage(ch, true, 0x0008, resp)
                }
            }
        }
    }

    private fun handleControlMessage(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        val tls = tlsEngine ?: return
        val out = rawOut ?: return

        when (msgType) {
            0x0002 -> { // VERSION_RESPONSE (`d0.java` line 253)
                if (len >= 6) {
                    val status = ((msg[offset + 4].toInt() and 0xFF) shl 8) or (msg[offset + 5].toInt() and 0xFF)
                    if (status == 0xFFFF) {
                        throw IllegalStateException("Versi protokol Android Auto tidak kompatibel")
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
            0x0003 -> { // SSL_HANDSHAKE (`d0.java` line 268)
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
                    onStatusChanged("✔ Enkripsi TLS Android Auto Aktif!")
                    val authComplete = AaProto.encode { writeVarint(1, 0) }
                    val frame = ByteBuffer.allocate(2 + authComplete.size)
                        .putShort(0x0004.toShort())
                        .put(authComplete)
                        .array()
                    sendRawFrame(out, CH_CONTROL, FLAG_BULK, frame)
                }
            }
            0x0005 -> { // SERVICE_DISCOVERY_REQUEST (`d0.java` line 284)
                onStatusChanged("Mengirim profil layar widescreen 1280×720 (Active 1120×360) ke Android Auto...")
                val sdr = buildServiceDiscoveryResponse()
                sendEncryptedMessage(CH_CONTROL, false, 0x0006, sdr)
            }
            0x000B -> { // PING_REQUEST (`d0.java` line 289)
                val pingResp = AaProto.encode {
                    writeVarint(1, 1)
                    writeVarint(2, 0)
                }
                sendEncryptedMessage(CH_CONTROL, false, 0x000C, pingResp)
            }
            0x000D -> { // NAVIGATION_FOCUS_REQUEST (`d0.java` line 292 -> field 1 = 2!)
                val navResp = AaProto.encode { writeVarint(1, 2) }
                sendEncryptedMessage(CH_CONTROL, false, 0x000E, navResp)
            }
            0x000F -> { // BYEBYE_REQUEST (`d0.java` line 297)
                val byeResp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_CONTROL, false, 0x0010, byeResp)
            }
            0x0012 -> { // AUDIO_FOCUS_REQUEST (`d0.java` line 242 & `v.e`)
                val reqFields = AaProto.parseFields(msg, offset, len)
                val reqType = reqFields[1]?.firstOrNull() as? Long ?: 1L
                val state = if (reqType == 4L) 7L else 1L
                val afResp = AaProto.encode {
                    writeVarint(1, state)
                    writeVarint(2, 0)
                }
                sendEncryptedMessage(CH_CONTROL, false, 0x0013, afResp)
            }
        }
    }

    private fun handleSensorChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x0007 -> { // CHANNEL_OPEN_REQUEST (`d0.java` line 209)
                val resp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_SENSOR, true, 0x0008, resp)
            }
            0x8001 -> { // SENSOR_START_REQUEST (`d0.java` line 213)
                val startResp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_SENSOR, false, 0x8002, startResp)

                // Kirim DrivingStatus = UNRESTRICTED (0) (`d0.java` lines 217-223)
                val drivingEvent = AaProto.encode {
                    writeMessage(1) {
                        writeVarint(1, 0)
                    }
                }
                sendEncryptedMessage(CH_SENSOR, false, 0x8003, drivingEvent)
            }
        }
    }

    private fun handleVideoChannel(msgType: Int, msg: ByteArray, offset: Int, len: Int) {
        when (msgType) {
            0x0007 -> { // CHANNEL_OPEN_REQUEST (`d0.java` line 182)
                val resp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_VIDEO, true, 0x0008, resp)
                onStatusChanged("Saluran Video AA terbuka, mengaktifkan proyeksi...")
            }
            0x8000 -> { // MEDIA_SETUP_REQUEST (`d0.java` lines 186-198)
                // 1. Balas MEDIA_SETUP_RESPONSE (0x8003): status=2(OK), max_unacked=1, config_index=0
                val setupResp = AaProto.encode {
                    writeVarint(1, 2)
                    writeVarint(2, 1)
                    writeVarint(3, 0)
                }
                sendEncryptedMessage(CH_VIDEO, false, 0x8003, setupResp)

                // 2. WAJIB: Kirim MEDIA_START_INDICATION (0x8001): session=0, config=0 (`d0.java` line 195)
                videoSessionId = 0
                val startInd = AaProto.encode {
                    writeVarint(1, videoSessionId)
                    writeVarint(2, 0)
                }
                sendEncryptedMessage(CH_VIDEO, false, 0x8001, startInd)

                // 3. Kirim VIDEO_FOCUS_INDICATION (0x8008): mode=1(PROJECTED), unsolicited=0 (`d0.java` line 198)
                sendVideoFocusIndication(1)
                isVideoStreaming = true
                onStatusChanged("★ Streaming Android Auto Aktif (960×360 @ 60 FPS Zero-Copy)!")
            }
            0x8007 -> { // VIDEO_FOCUS_REQUEST (`d0.java` line 199)
                val fields = AaProto.parseFields(msg, offset, len)
                val focusMode = (fields[2]?.firstOrNull() as? Long ?: 1L).toInt()
                sendVideoFocusIndication(focusMode)
            }
            0x0000 -> { // MEDIA_WITH_TIMESTAMP (`d0.java` line 137)
                if (len > 8) {
                    val ptsUs = ByteBuffer.wrap(msg, offset, 8).long
                    feedToHwDecoder(msg, offset + 8, len - 8, false, ptsUs)
                    sendMediaAck(CH_VIDEO, videoSessionId, 1)
                }
            }
            0x0001 -> { // MEDIA_DATA / CODEC_CONFIG (`d0.java` line 158)
                if (len > 0) {
                    feedToHwDecoder(msg, offset, len, true, 0L)
                }
                sendMediaAck(CH_VIDEO, videoSessionId, 1)
            }
        }
    }

    private fun handleInputChannel(msgType: Int) {
        when (msgType) {
            0x0007 -> { // CHANNEL_OPEN_REQUEST (`d0.java` line 116)
                val resp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_INPUT, true, 0x0008, resp)
                isInputChannelOpen = true
            }
            0x8002 -> { // KEY_BINDING_REQUEST (`d0.java` line 123)
                val resp = AaProto.encode { writeVarint(1, 0) }
                sendEncryptedMessage(CH_INPUT, false, 0x8003, resp)
            }
        }
    }

    private fun sendVideoFocusIndication(mode: Int) {
        val vf = AaProto.encode {
            writeVarint(1, mode)
            writeVarint(2, 0)
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

    /**
     * Mengirim KeyEvent ke Android Auto (`z.java` & `v.d` di WayXAuto: Field 4 dari InputReport adalah `key_event`!).
     */
    fun sendKey(keyCode: Int) {
        if (!isRunning.get() || activeSocket == null) return
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                sendEncryptedMessage(CH_INPUT, false, 0x8001, buildKeyReport(nowNs, keyCode, true))
                sendEncryptedMessage(CH_INPUT, false, 0x8001, buildKeyReport(nowNs + 1_000_000L, keyCode, false))
            } catch (_: Exception) {}
        }
    }

    /**
     * Mengirim putaran Rotary Controller (`z.java` & `v.h` di WayXAuto: Field 6 dari InputReport adalah `relative_event`).
     */
    fun sendRotary(delta: Int) {
        if (!isRunning.get() || activeSocket == null) return
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                val report = AaProto.encode {
                    writeVarint(1, nowNs)
                    writeMessage(6) { // relative_event
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

    /**
     * Mengirim Touch Event langsung dari sentuhan jari pada Live Preview (`Field 3 = touch_event`).
     * @param normX posisi X relatif (0.0 .. 1.0)
     * @param normY posisi Y relatif (0.0 .. 1.0)
     * @param action 0 = ACTION_DOWN, 1 = ACTION_UP, 2 = ACTION_MOVE
     */
    fun sendTouch(normX: Float, normY: Float, action: Int) {
        if (!isRunning.get() || activeSocket == null) return
        val x = (normX.coerceIn(0f, 1f) * OmowayViewportConfig.aaActiveWidth).toInt()
        val y = (normY.coerceIn(0f, 1f) * OmowayViewportConfig.aaActiveHeight).toInt()
        inputExecutor.execute {
            try {
                val nowNs = System.nanoTime()
                val report = AaProto.encode {
                    writeVarint(1, nowNs)
                    writeMessage(3) { // touch_event
                        writeMessage(1) { // pointer_data
                            writeVarint(1, x)
                            writeVarint(2, y)
                            writeVarint(3, 0) // pointer_id = 0
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
        if (!isRunning.get() || activeSocket == null) return
        inputExecutor.execute {
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

    private fun buildKeyReport(timestampNs: Long, keyCode: Int, isDown: Boolean): ByteArray {
        return AaProto.encode {
            writeVarint(1, timestampNs)
            writeMessage(4) { // Field 4 = key_event (`v.d` di WayXAuto)
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
     * Membangun `ServiceDiscoveryResponse` persis seperti `d0.java` lines 314-380 di WayXAuto:
     * - Channel 1 (Sensor): DRIVING_STATUS (1)
     * - Channel 2 (MediaSink): available_type=3 (H264_BP), field 4 (video_configs) = 1280x720 @ 60fps
     *   dengan width_margin=160 & height_margin=360 (menghasilkan area aktif widescreen 1120x360!), field 5=1
     * - Channel 3 (InputSource): Keycodes + Touchscreen 1120x360 Capacitive
     */
    private fun buildServiceDiscoveryResponse(): ByteArray {
        val supportedKeys = intArrayOf(
            KEYCODE_HOME, KEYCODE_BACK,
            KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_CENTER,
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MUSIC, KEYCODE_NAVIGATION,
            KEYCODE_ROTARY, KEYCODE_CUSTOM_2
        )

        return AaProto.encode {
            // 1. Channel 1: SensorSourceService (`caVar12` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_SENSOR)
                writeMessage(2) {
                    writeMessage(1) {
                        writeVarint(1, 1) // SENSOR_DRIVING_STATUS_DATA = 1
                    }
                }
            }

            // 2. Channel 2: MediaSinkService (`caVar14` & `caVar15` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_VIDEO)
                writeMessage(3) {
                    writeVarint(1, 3) // MEDIA_CODEC_VIDEO_H264_BP = 3
                    // KRUSIAL: Field 4 dari MediaSinkService adalah `video_configs`!
                    writeMessage(4) {
                        writeVarint(1, 2) // codec_resolution = _1280x720 (2)
                        writeVarint(2, 1) // frame_rate = _60 (1)
                        writeVarint(3, OmowayViewportConfig.aaWidthMargin)  // 160 (1280 - 160 = 1120)
                        writeVarint(4, OmowayViewportConfig.aaHeightMargin) // 360 (720 - 360 = 360)
                        writeVarint(5, 160) // density = 160 dpi
                    }
                    writeVarint(5, 1) // available_while_in_call = true
                }
            }

            // 3. Channel 3: InputSourceService (`caVar17` & `caVar18` di d0.java)
            writeMessage(1) {
                writeVarint(1, CH_INPUT)
                writeMessage(4) {
                    for (kc in supportedKeys) {
                        writeVarint(1, kc)
                    }
                    writeMessage(2) { // Touchscreen 1120x360 Capacitive
                        writeVarint(1, OmowayViewportConfig.aaActiveWidth)
                        writeVarint(2, OmowayViewportConfig.aaActiveHeight)
                        writeVarint(3, 1) // CAPACITIVE = 1
                    }
                }
            }

            // Head Unit Metadata (`caVar19` di d0.java)
            writeString(2, "Riding Display")
            writeString(3, "Motorcycle")
            writeVarint(6, 0)
            writeString(7, "OMOWAY")
            writeString(8, "UMOX Auto")
            writeString(9, "2.0")
            writeVarint(10, 0)
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
