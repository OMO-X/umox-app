package com.umox.app.aa

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Compact ProtoBuf encoder and decoder for Android Auto Protocol (AAP).
 * Lightweight, zero-dependency replacement for heavy Google protobuf runtimes.
 */
class AaProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun writeVarint(fieldNumber: Int, value: Long): AaProtoWriter {
        writeTag(fieldNumber, 0)
        writeRawVarint(value)
        return this
    }

    fun writeVarint(fieldNumber: Int, value: Int): AaProtoWriter {
        return writeVarint(fieldNumber, value.toLong())
    }

    fun writeBytes(fieldNumber: Int, bytes: ByteArray): AaProtoWriter {
        writeTag(fieldNumber, 2)
        writeRawVarint(bytes.size.toLong())
        buffer.write(bytes, 0, bytes.size)
        return this
    }

    fun writeString(fieldNumber: Int, str: String): AaProtoWriter {
        return writeBytes(fieldNumber, str.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeRawVarint(((fieldNumber shl 3) or (wireType and 0x07)).toLong())
    }

    private fun writeRawVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and -0x80L) == 0L) {
                buffer.write(v.toInt())
                return
            } else {
                buffer.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

data class AaProtoField(
    val tag: Int,
    val wireType: Int,
    val varintValue: Long,
    val bytes: ByteArray?
)

class AaProtoReader(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
    val fields = mutableListOf<AaProtoField>()

    init {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val (tagAndType, nextPos) = readVarint(data, pos, end)
            pos = nextPos
            val tag = (tagAndType ushr 3).toInt()
            val wireType = (tagAndType and 0x07).toInt()

            when (wireType) {
                0 -> { // Varint
                    val (v, afterV) = readVarint(data, pos, end)
                    pos = afterV
                    fields.add(AaProtoField(tag, wireType, v, null))
                }
                1 -> { // Fixed64
                    if (pos + 8 > end) break
                    var v = 0L
                    for (b in 0 until 8) {
                        v = v or ((data[pos + b].toLong() and 0xFF) shl (b * 8))
                    }
                    pos += 8
                    fields.add(AaProtoField(tag, wireType, v, null))
                }
                2 -> { // Length-delimited
                    val (len, afterLen) = readVarint(data, pos, end)
                    pos = afterLen
                    val intLen = len.toInt()
                    if (pos + intLen > end) break
                    val bArr = ByteArray(intLen)
                    System.arraycopy(data, pos, bArr, 0, intLen)
                    pos += intLen
                    fields.add(AaProtoField(tag, wireType, intLen.toLong(), bArr))
                }
                5 -> { // Fixed32
                    if (pos + 4 > end) break
                    var v = 0L
                    for (b in 0 until 4) {
                        v = v or ((data[pos + b].toLong() and 0xFF) shl (b * 8))
                    }
                    pos += 4
                    fields.add(AaProtoField(tag, wireType, v, null))
                }
                else -> {
                    // Unknown wire type, cannot proceed safely
                    break
                }
            }
        }
    }

    fun getLong(tag: Int, default: Long = 0L): Long {
        return fields.firstOrNull { it.tag == tag && it.wireType == 0 }?.varintValue ?: default
    }

    fun getInt(tag: Int, default: Int = 0): Int {
        return getLong(tag, default.toLong()).toInt()
    }

    fun getBytes(tag: Int): ByteArray? {
        return fields.firstOrNull { it.tag == tag && it.wireType == 2 }?.bytes
    }

    fun getString(tag: Int): String? {
        val b = getBytes(tag) ?: return null
        return String(b, StandardCharsets.UTF_8)
    }

    fun getAll(tag: Int): List<AaProtoField> {
        return fields.filter { it.tag == tag }
    }

    companion object {
        private fun readVarint(data: ByteArray, offset: Int, end: Int): Pair<Long, Int> {
            var v = 0L
            var shift = 0
            var pos = offset
            while (pos < end && shift < 64) {
                val b = data[pos++].toInt()
                v = v or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return Pair(v, pos)
                }
                shift += 7
            }
            return Pair(v, pos)
        }
    }
}
