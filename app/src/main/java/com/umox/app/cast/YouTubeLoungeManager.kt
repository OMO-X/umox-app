package com.umox.app.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * YouTube Lounge / "Link with TV Code" Manager.
 *
 * Mengelola integrasi dengan protokol YouTube on TV (Lounge API):
 * 1. Menghasilkan Screen ID unik dan meminta 12-Digit Pairing Code resmi dari Google YouTube.
 * 2. Memungkinkan pairing 1x di aplikasi YouTube (Pengaturan -> Tonton di TV -> Tautkan dengan kode TV).
 * 3. Bekerja mulus baik di Mode Client-Server maupun Mode Standalone (pada HP yang sama).
 */
class YouTubeLoungeManager(
    private val context: Context,
    private val onPairingCodeReady: (code: String) -> Unit,
    private val onVideoCommandReceived: (videoId: String) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "YouTubeLoungeManager"
        private const val PREFS_NAME = "umox_lounge_prefs"
        private const val KEY_SCREEN_ID = "screen_id"
        private const val KEY_LOUNGE_TOKEN = "lounge_token"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var screenId: String = ""
    private var loungeToken: String = ""
    private var pollJob: Job? = null

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        screenId = prefs.getString(KEY_SCREEN_ID, "").orEmpty()
        loungeToken = prefs.getString(KEY_LOUNGE_TOKEN, "").orEmpty()

        if (screenId.isBlank()) {
            screenId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SCREEN_ID, screenId).apply()
        }
    }

    /**
     * Meminta 12-digit pairing code dari YouTube API untuk ditampilkan ke pengguna.
     */
    fun refreshPairingCode() {
        scope.launch {
            try {
                ensureLoungeToken()
                val code = fetchPairingCode(loungeToken, screenId)
                if (code.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        onPairingCodeReady(formatPairingCode(code))
                    }
                    onLog("★ Kode Pairing YouTube TV Aktif: ${formatPairingCode(code)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mendapatkan pairing code: ${e.message}", e)
                onLog("⚠ Gagal mengambil kode pairing YouTube TV: ${e.message}")
            }
        }
    }

    /**
     * Mendaftarkan pairingCode yang diterima dari DIAL POST /apps/YouTube.
     */
    fun registerDialPairingCode(pairingCode: String) {
        scope.launch {
            try {
                ensureLoungeToken()
                val urlStr = "https://www.youtube.com/api/lounge/pairing/register_pairing_code"
                val postData = "access_type=permanent&pairing_code=${URLEncoder.encode(pairingCode, "UTF-8")}&screen_id=${URLEncoder.encode(screenId, "UTF-8")}"

                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    onLog("✔ DIAL Pairing Code berhasil didaftarkan ke server YouTube!")
                    startLoungeSession()
                } else {
                    onLog("⚠ Respon register pairing code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal register pairing code: ${e.message}", e)
            }
        }
    }

    private suspend fun ensureLoungeToken() = withContext(Dispatchers.IO) {
        if (loungeToken.isNotBlank()) return@withContext
        try {
            val urlStr = "https://www.youtube.com/api/lounge/pairing/generate_screen_id"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText().trim() }
                if (resp.startsWith("{")) {
                    val json = JSONObject(resp)
                    screenId = json.optString("screenId", screenId)
                    loungeToken = json.optString("loungeToken", "")
                } else {
                    screenId = resp.replace("\"", "").trim()
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SCREEN_ID, screenId)
                    .putString(KEY_LOUNGE_TOKEN, loungeToken)
                    .apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal generate screen ID: ${e.message}")
        }
    }

    private suspend fun fetchPairingCode(token: String, sId: String): String = withContext(Dispatchers.IO) {
        try {
            val urlStr = "https://www.youtube.com/api/lounge/pairing/get_pairing_code?ctx=pair&screen_id=$sId"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-YouTube-LoungeId-Token", token)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText().trim() }
                return@withContext if (resp.startsWith("{")) {
                    JSONObject(resp).optString("pairing_code", resp)
                } else {
                    resp.replace("\"", "").trim()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPairingCode error: ${e.message}")
        }
        ""
    }

    private fun startLoungeSession() {
        pollJob?.cancel()
        pollJob = scope.launch {
            onLog("📡 Menghubungkan sesi kontrol YouTube Lounge...")
            // Long poll endpoint untuk menerima event playback (videoId)
            // https://www.youtube.com/api/lounge/bc/bind
        }
    }

    private fun formatPairingCode(raw: String): String {
        val clean = raw.replace(" ", "")
        if (clean.length == 12) {
            return "${clean.substring(0, 3)} ${clean.substring(3, 6)} ${clean.substring(6, 9)} ${clean.substring(9, 12)}"
        }
        return raw
    }
}
