package com.umox.app.stream

import java.io.ByteArrayOutputStream

/**
 * Sanitizer NALU H.264 Annex-B khusus untuk Decoder Linux Desay SV (Port 9528 Omoway).
 *
 * Aturan wajib Desay SV ([video-streaming.md]):
 * 1. Semua start code WAJIB dinormalisasi ke 4-byte Annex-B (`00 00 00 01`).
 * 2. NALU `SPS (type 7)` dan `PPS (type 8)` TIDAK BOLEH dikirim terpisah (standalone),
 *    melainkan wajib disimpan (cache) dan ditempelkan langsung di depan setiap `IDR KeyFrame (type 5)`
 *    menjadi satu paket utuh: `[00 00 00 01 SPS][00 00 00 01 PPS][00 00 00 01 IDR]`.
 * 3. NALU `SEI (type 6)`, `AUD (type 9)`, dan `Filler (type 12)` wajib dibuang agar parser
 *    hardware cluster tidak mengalami buffer drop atau artefak warna.
 */
class H264NaluSanitizer(
    private val onCleanAccessUnit: (packet: ByteArray, isKeyFrame: Boolean) -> Unit
) {
    private val startCode4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    @Volatile
    var cachedSps: ByteArray? = null
        private set

    @Volatile
    var cachedPps: ByteArray? = null
        private set

    @Volatile
    var lastIdrPacket: ByteArray? = null
        private set

    private val outBuffer = ByteArrayOutputStream(65536)

    /**
     * Memproses output buffer dari MediaCodec atau Android Auto dan mengekstrak
     * paket H.264 bersih siap kirim ke Port 9528.
     */
    @Synchronized
    fun processAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        val end = offset + length

        // Cari semua posisi start code (00 00 01 atau 00 00 00 01)
        var pos = findNextStartCode(data, offset, end)
        if (pos < 0) {
            // Jika tidak ada start code sama sekali, anggap seluruh buffer adalah 1 raw NALU
            handleSingleRawNalu(data, offset, end)
            return
        }

        outBuffer.reset()
        var hasIdrInAu = false
        var hasVclInAu = false

        while (pos in 0 until end) {
            val scLen = if (pos + 3 < end &&
                data[pos] == 0.toByte() &&
                data[pos + 1] == 0.toByte() &&
                data[pos + 2] == 0.toByte() &&
                data[pos + 3] == 1.toByte()
            ) 4 else 3

            val naluStart = pos + scLen
            val nextPos = findNextStartCode(data, naluStart, end)
            val naluEnd = if (nextPos >= 0) nextPos else end
            val naluLen = naluEnd - naluStart

            if (naluLen > 0) {
                val nalType = data[naluStart].toInt() and 0x1F
                when (nalType) {
                    7 -> {
                        // SPS: Cache dengan 4-byte start code
                        val sps = ByteArray(4 + naluLen)
                        System.arraycopy(startCode4, 0, sps, 0, 4)
                        System.arraycopy(data, naluStart, sps, 4, naluLen)
                        cachedSps = sps
                    }
                    8 -> {
                        // PPS: Cache dengan 4-byte start code
                        val pps = ByteArray(4 + naluLen)
                        System.arraycopy(startCode4, 0, pps, 0, 4)
                        System.arraycopy(data, naluStart, pps, 4, naluLen)
                        cachedPps = pps
                    }
                    6, 9, 12 -> {
                        // SEI (6), AUD (9), Filler (12): Buang!
                    }
                    5 -> {
                        // IDR KeyFrame: Tempelkan SPS + PPS tepat sekali di awal IDR
                        if (!hasIdrInAu) {
                            cachedSps?.let { outBuffer.write(it) }
                            cachedPps?.let { outBuffer.write(it) }
                            hasIdrInAu = true
                        }
                        outBuffer.write(startCode4)
                        outBuffer.write(data, naluStart, naluLen)
                        hasVclInAu = true
                    }
                    1 -> {
                        // Non-IDR Slice (P-Frame)
                        outBuffer.write(startCode4)
                        outBuffer.write(data, naluStart, naluLen)
                        hasVclInAu = true
                    }
                    else -> {
                        // NALU VCL lainnya (2, 3, 4)
                        if (nalType in 2..4) {
                            outBuffer.write(startCode4)
                            outBuffer.write(data, naluStart, naluLen)
                            hasVclInAu = true
                        }
                    }
                }
            }

            pos = nextPos
        }

        if (hasVclInAu && outBuffer.size() > 0) {
            val packet = outBuffer.toByteArray()
            if (hasIdrInAu) {
                lastIdrPacket = packet
            }
            onCleanAccessUnit(packet, hasIdrInAu)
        }
    }

    private fun handleSingleRawNalu(data: ByteArray, start: Int, end: Int) {
        val len = end - start
        if (len <= 0) return
        val nalType = data[start].toInt() and 0x1F
        when (nalType) {
            7 -> {
                val sps = ByteArray(4 + len)
                System.arraycopy(startCode4, 0, sps, 0, 4)
                System.arraycopy(data, start, sps, 4, len)
                cachedSps = sps
            }
            8 -> {
                val pps = ByteArray(4 + len)
                System.arraycopy(startCode4, 0, pps, 0, 4)
                System.arraycopy(data, start, pps, 4, len)
                cachedPps = pps
            }
            5 -> {
                outBuffer.reset()
                cachedSps?.let { outBuffer.write(it) }
                cachedPps?.let { outBuffer.write(it) }
                outBuffer.write(startCode4)
                outBuffer.write(data, start, len)
                val pkt = outBuffer.toByteArray()
                lastIdrPacket = pkt
                onCleanAccessUnit(pkt, true)
            }
            1 -> {
                val pkt = ByteArray(4 + len)
                System.arraycopy(startCode4, 0, pkt, 0, 4)
                System.arraycopy(data, start, pkt, 4, len)
                onCleanAccessUnit(pkt, false)
            }
        }
    }

    private fun findNextStartCode(data: ByteArray, fromIndex: Int, endIndex: Int): Int {
        var i = fromIndex
        val limit = endIndex - 3
        while (i <= limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    return i
                }
                if (i + 3 < endIndex && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }
}

