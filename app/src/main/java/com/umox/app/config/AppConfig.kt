package com.umox.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Konfigurasi umum jaringan, keamanan, dan identitas perangkat cluster motor.
 * Seluruh variabel di bawah ini bersifat umum dan dapat disesuaikan (configurable)
 * sesuai dengan spesifikasi dan identitas hardware masing-masing kendaraan.
 * Nilai spesifik perangkat disimpan secara lokal di SharedPreferences HP agar aman
 * dan tidak ter-commit ke repositori publik.
 */
object AppConfig {

    private const val PREFS_NAME = "umox_prefs"
    private const val KEY_BLE_MAC = "pref_target_ble_mac"
    private const val KEY_P2P_MAC = "pref_target_p2p_mac"

    /**
     * Target MAC Address Wi-Fi Direct (P2P).
     * Dapat diisi MAC address spesifik (format: "XX:XX:XX:XX:XX:XX")
     * atau dibiarkan kosong ("") untuk mengaktifkan pencarian otomatis berbasis SSID P2P.
     */
    var targetP2pMac: String = ""

    /**
     * Target MAC Address BLE.
     * Dapat diisi MAC BLE spesifik jika diketahui, atau dibiarkan kosong ("")
     * untuk mengandalkan auto-discovery berdasarkan Service UUID 0xFFF0 atau nama perangkat.
     */
    var targetBleMac: String = ""

    /**
     * Alamat IP gateway default cluster speedometer / headunit.
     */
    var defaultClusterIp: String = "192.168.2.1"

    /**
     * Port TCP Saluran Kontrol (Control Channel) Desay SV.
     */
    var defaultControlPort: Int = 9527

    /**
     * Port TCP Saluran Video Streaming H.264.
     */
    var defaultVideoPort: Int = 9528

    /**
     * Kata kunci nama perangkat yang dikenali saat pemindaian BLE / Wi-Fi.
     */
    val candidateNameKeywords: List<String> = listOf(
        "omoway",
        "omo",
        "pma",
        "desay",
        "ddka",
        "cmp",
        "bike",
        "cluster",
        "navi",
        "direct-omo"
    )

    /**
     * OUI prefix MAC address hardware vendor speedometer (Desay SV / Omoway).
     */
    val candidateOuis: List<String> = listOf(
        "68:12:68",
        "dc:0d:30"
    )

    /**
     * Memuat target MAC yang tersimpan di HP.
     */
    fun loadFromPreferences(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedBle = sp.getString(KEY_BLE_MAC, "") ?: ""
        val savedP2p = sp.getString(KEY_P2P_MAC, "") ?: ""
        if (savedBle.isNotBlank()) targetBleMac = savedBle
        if (savedP2p.isNotBlank()) targetP2pMac = savedP2p
    }

    /**
     * Menyimpan target MAC BLE ke penyimpanan lokal HP (tidak pernah ter-commit ke git).
     */
    fun saveBleMac(context: Context, mac: String) {
        if (mac.isBlank()) return
        targetBleMac = mac.trim().uppercase()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLE_MAC, targetBleMac)
            .apply()
    }

    /**
     * Menyimpan target MAC P2P ke penyimpanan lokal HP (tidak pernah ter-commit ke git).
     */
    fun saveP2pMac(context: Context, mac: String) {
        if (mac.isBlank()) return
        targetP2pMac = mac.trim().uppercase()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_P2P_MAC, targetP2pMac)
            .apply()
    }

    /**
     * Memeriksa apakah nama perangkat cocok dengan salah satu kata kunci kandidat cluster.
     */
    fun matchesCandidateName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return candidateNameKeywords.any { lower.contains(it) }
    }

    /**
     * Memeriksa apakah MAC address BLE cocok dengan target yang telah dikonfigurasi.
     */
    fun matchesTargetBleMac(address: String?): Boolean {
        if (address.isNullOrBlank() || targetBleMac.isBlank()) return false
        return address.equals(targetBleMac.trim(), ignoreCase = true)
    }

    /**
     * Memeriksa apakah MAC address P2P cocok dengan target yang telah dikonfigurasi.
     */
    fun matchesTargetP2pMac(address: String?): Boolean {
        if (address.isNullOrBlank() || targetP2pMac.isBlank()) return false
        return address.equals(targetP2pMac.trim(), ignoreCase = true)
    }

    /**
     * Memeriksa apakah MAC address cocok dengan OUI pabrikan cluster speedometer.
     */
    fun matchesCandidateOui(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val clean = address.trim().lowercase()
        return candidateOuis.any { clean.startsWith(it) }
    }

    /**
     * Payload frame status polling default (GetFunctionStatus)
     */
    val defaultStatusPollPayload: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x55.toByte(), 0x01, 0x17,
        0xF4.toByte(), 0x8C.toByte(), 0xA5.toByte(), 0x68,
        0xAE.toByte(), 0x89.toByte(), 0x3E, 0x9C.toByte(),
        0xB0.toByte(), 0x20, 0x80.toByte(), 0xE0.toByte(),
        0xE2.toByte(), 0xDF.toByte(), 0xB0.toByte(), 0x91.toByte(),
        0xEC.toByte(), 0xA4.toByte(), 0x06, 0xF1.toByte(),
        0x93.toByte(), 0x7E, 0xA2.toByte()
    )

    /**
     * Payload frame kueri kredensial Wi-Fi Direct motor (extSearchWifiInfo - Function ID 1569)
     * Langsung meminta SSID, Password WPA2, dan IP dari cluster motor via BLE.
     */
    val wifiQueryPayload: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x55.toByte(), 0x00, 0x47,
        0x08, 0x8D.toByte(), 0xC1.toByte(), 0x89.toByte(), 0xAF.toByte(), 0xD8.toByte(), 0xF6.toByte(), 0x8A.toByte(), 0xF8.toByte(), 0x59,
        0x1A, 0x37,
        0x08, 0x80.toByte(), 0xE0.toByte(), 0xE2.toByte(), 0x85.toByte(), 0xB1.toByte(), 0x91.toByte(), 0xEC.toByte(), 0xA4.toByte(), 0x06,
        0x10, 0xCB.toByte(), 0x97.toByte(), 0xF2.toByte(), 0xF8.toByte(), 0xEA.toByte(), 0x9E.toByte(), 0x95.toByte(), 0x21,
        0x1A, 0x18,
        0x08, 0x96.toByte(), 0xDF.toByte(), 0x9B.toByte(), 0x84.toByte(), 0xEB.toByte(), 0x9E.toByte(), 0x95.toByte(), 0x21,
        0x12, 0x0D, 0x72, 0xBA.toByte(), 0xE1.toByte(), 0x97.toByte(), 0xC7.toByte(), 0xC7.toByte(), 0x1B, 0x38, 0xD1.toByte(), 0xB5.toByte(), 0x54,
        0xBA.toByte(), 0x92.toByte(), 0x20, 0x80.toByte(), 0xE0.toByte(), 0xE2.toByte(), 0xDF.toByte(), 0xB0.toByte(), 0x91.toByte(), 0xEC.toByte(), 0xA4.toByte(), 0x06,
        0x13, 0xE7.toByte(), 0x02, 0x0D
    )
}

