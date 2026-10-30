package com.umox.app.aa

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager

/**
 * Handles TLS wrapping and unwrapping for Android Auto Protocol.
 */
class AaTlsEngine(context: Context) {

    private val engine: SSLEngine
    private var rxResidual: ByteBuffer = ByteBuffer.allocate(0)
    var isHandshakeFinished: Boolean = false
        private set

    init {
        val sslContext = SSLContext.getInstance("TLS")
        val km = SingleKeyKeyManager(context)
        val tm = NoCheckTrustManager()
        sslContext.init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(tm), SecureRandom())

        engine = sslContext.createSSLEngine()
        engine.useClientMode = true
        engine.beginHandshake()
    }

    /**
     * Feeds incoming TLS handshake bytes from Android Auto (Type 3) and produces
     * any outgoing handshake bytes that must be sent back.
     */
    @Synchronized
    fun processHandshake(incomingBytes: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val dummyApp = ByteBuffer.allocate(0)
        val appBuf = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val netBuf = ByteBuffer.allocate(engine.session.packetBufferSize)

        if (incomingBytes != null && incomingBytes.isNotEmpty()) {
            val totalCap = rxResidual.remaining() + incomingBytes.size
            val merged = ByteBuffer.allocate(totalCap)
            merged.put(rxResidual)
            merged.put(incomingBytes)
            merged.flip()
            rxResidual = merged
        }

        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netBuf.clear()
                    val wrapRes = engine.wrap(dummyApp, netBuf)
                    out.write(netBuf.array(), 0, netBuf.position())
                    if (wrapRes.status == SSLEngineResult.Status.CLOSED) {
                        throw SSLException("TLS closed during handshake wrap")
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!rxResidual.hasRemaining()) {
                        compactResidual()
                        return out.toByteArray()
                    }
                    appBuf.clear()
                    val unwrapRes = engine.unwrap(rxResidual, appBuf)
                    if (unwrapRes.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        compactResidual()
                        return out.toByteArray()
                    }
                    if (unwrapRes.status == SSLEngineResult.Status.CLOSED) {
                        throw SSLException("TLS closed during handshake unwrap")
                    }
                    if (unwrapRes.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                        isHandshakeFinished = true
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) {
                        val task = engine.delegatedTask ?: break
                        task.run()
                    }
                }
                else -> {
                    // FINISHED or NOT_HANDSHAKING
                    isHandshakeFinished = true
                    compactResidual()
                    return out.toByteArray()
                }
            }
        }
    }

    /**
     * Decrypts an incoming encrypted AAP payload.
     */
    @Synchronized
    fun decrypt(encryptedBytes: ByteArray): ByteArray {
        val totalCap = rxResidual.remaining() + encryptedBytes.size
        val merged = ByteBuffer.allocate(totalCap)
        merged.put(rxResidual)
        merged.put(encryptedBytes)
        merged.flip()
        rxResidual = merged

        val out = ByteArrayOutputStream(encryptedBytes.size)
        var appBuf = ByteBuffer.allocate(engine.session.applicationBufferSize)

        while (rxResidual.hasRemaining()) {
            appBuf.clear()
            val unwrapRes = engine.unwrap(rxResidual, appBuf)
            out.write(appBuf.array(), 0, appBuf.position())

            if (unwrapRes.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break
            }
            if (unwrapRes.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                appBuf = ByteBuffer.allocate(appBuf.capacity() * 2)
            } else {
                if (unwrapRes.status != SSLEngineResult.Status.OK) {
                    throw SSLException("TLS unwrap failed: ${unwrapRes.status}")
                }
                if (unwrapRes.bytesConsumed() == 0 && unwrapRes.bytesProduced() == 0) {
                    break
                }
            }
        }

        compactResidual()
        return out.toByteArray()
    }

    /**
     * Encrypts an outgoing AAP payload with TLS.
     */
    @Synchronized
    fun encrypt(plainBytes: ByteArray): ByteArray {
        // Jangan pernah enkripsi sebelum handshake TLS selesai: SSLEngine.wrap() akan
        // mengembalikan OK tanpa mengonsumsi byte -> loop tak berujung (ANR di HP donor).
        if (!isHandshakeFinished) {
            throw SSLException("TLS handshake belum selesai, pesan dibatalkan")
        }
        val wrap = ByteBuffer.wrap(plainBytes)
        val out = ByteArrayOutputStream(plainBytes.size + 64)
        val netBuf = ByteBuffer.allocate(engine.session.packetBufferSize)

        var stalledRounds = 0
        while (wrap.hasRemaining()) {
            netBuf.clear()
            val res = engine.wrap(wrap, netBuf)
            if (res.status != SSLEngineResult.Status.OK) {
                throw SSLException("TLS wrap failed: ${res.status}")
            }
            if (res.bytesConsumed() == 0) {
                if (++stalledRounds > 4) {
                    throw SSLException("TLS wrap macet (handshake=${res.handshakeStatus})")
                }
            } else {
                stalledRounds = 0
            }
            out.write(netBuf.array(), 0, netBuf.position())
        }

        return out.toByteArray()
    }

    private fun compactResidual() {
        if (!rxResidual.hasRemaining()) {
            rxResidual = ByteBuffer.allocate(0)
        }
    }
}
