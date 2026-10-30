package com.umox.app.config

import android.content.Context
import android.content.SharedPreferences
import com.umox.app.stream.OmowayViewportConfig

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
    private const val KEY_BLE_NAME = "pref_target_ble_name"
    private const val KEY_P2P_MAC = "pref_target_p2p_mac"
    private const val KEY_P2P_NAME = "pref_target_p2p_name"
    private const val KEY_APP_MODE = "pref_app_mode"
    private const val KEY_SERVER_HOST = "pref_server_host"
    private const val KEY_SERVER_NAME = "pref_server_name"
    private const val KEY_HOTSPOT_SSID = "pref_hotspot_ssid"
    private const val KEY_HOTSPOT_PASS = "pref_hotspot_pass"
    private const val KEY_PRIMARY_PHONE_BT_MAC = "pref_primary_phone_bt_mac"
    private const val KEY_PRIMARY_PHONE_BT_NAME = "pref_primary_phone_bt_name"

    enum class AppMode {
        STANDALONE,
        SERVER,
        CLIENT
    }

    /**
     * Mode operasional aplikasi UMOX:
     * - STANDALONE: 1 HP mandiri terhubung langsung ke motor.
     * - SERVER: Berjalan di HP Utama (Host Android Auto & Radar Hotspot).
     * - CLIENT: Berjalan di HP Layar Tambahan / HP Donor (Penerima Video & Streamer ke Motor).
     */
    var appMode: AppMode = AppMode.STANDALONE

    /**
     * Alamat host server Android Auto.
     * Standalone: "127.0.0.1"
     * Client: IP HP Utama (terdeteksi otomatis secara dinamis atau dipilih oleh pengguna).
     */
    var serverHostIp: String = "127.0.0.1"
    var serverHostName: String = ""

    /**
     * Target MAC Address Wi-Fi Direct (P2P).
     * Dapat dipilih melalui dialog pemindai perangkat atau diisi secara manual.
     */
    var targetP2pMac: String = ""
    var targetP2pName: String = ""

    /**
     * Target MAC Address BLE.
     * Disimpan setelah pengguna memilih motor dari daftar pemindaian BLE.
     */
    var targetBleMac: String = ""
    var targetBleName: String = ""

    /**
     * Kredensial Hotspot Wi-Fi untuk Android Auto Wireless WPP & Multi-Device
     */
    var hotspotSsid: String = "UMOX_AUTO"
    var hotspotPass: String = "umox1234"

    /**
     * Identitas Bluetooth HP Utama untuk Auto-Wake Poke Android Auto
     */
    var primaryPhoneBtMac: String = ""
    var primaryPhoneBtName: String = ""

    /**
     * Mengarahkan 5 tombol media joystick stang motor (Vol+, Vol-, Prev, Next, Play/Pause)
     * menjadi D-Pad navigasi Android Auto (Atas, Bawah, Kiri, Kanan, Select).
     */
    var joystickAsDpad: Boolean = true

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
     * Memuat target MAC serta pengaturan Margin HUD & Boost Warna yang tersimpan di HP.
     */
    fun loadFromPreferences(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedBle = sp.getString(KEY_BLE_MAC, "") ?: ""
        val savedBleName = sp.getString(KEY_BLE_NAME, "") ?: ""
        val savedP2p = sp.getString(KEY_P2P_MAC, "") ?: ""
        val savedP2pName = sp.getString(KEY_P2P_NAME, "") ?: ""
        if (savedBle.isNotBlank()) {
            targetBleMac = savedBle
            targetBleName = savedBleName
        }
        if (savedP2p.isNotBlank()) {
            targetP2pMac = savedP2p
            targetP2pName = savedP2pName
        }

        hotspotSsid = sp.getString(KEY_HOTSPOT_SSID, "UMOX_AUTO") ?: "UMOX_AUTO"
        hotspotPass = sp.getString(KEY_HOTSPOT_PASS, "umox1234") ?: "umox1234"
        primaryPhoneBtMac = sp.getString(KEY_PRIMARY_PHONE_BT_MAC, "") ?: ""
        primaryPhoneBtName = sp.getString(KEY_PRIMARY_PHONE_BT_NAME, "") ?: ""

        val savedModeStr = sp.getString(KEY_APP_MODE, AppMode.STANDALONE.name) ?: AppMode.STANDALONE.name
        appMode = try { AppMode.valueOf(savedModeStr) } catch (_: Exception) { AppMode.STANDALONE }
        val savedServerHost = sp.getString(KEY_SERVER_HOST, "") ?: ""
        val savedServerName = sp.getString(KEY_SERVER_NAME, "") ?: ""
        if (savedServerHost.isNotBlank()) {
            serverHostIp = savedServerHost
            serverHostName = savedServerName
        } else if (appMode == AppMode.STANDALONE) {
            serverHostIp = "127.0.0.1"
            serverHostName = "Lokal"
        }

        OmowayViewportConfig.useClusterSafeMargins = sp.getBoolean("pref_safe_margins_enabled", true)
        OmowayViewportConfig.marginLeft = sp.getInt("pref_margin_left", 0)
        OmowayViewportConfig.marginRight = sp.getInt("pref_margin_right", 120)
        OmowayViewportConfig.marginTop = sp.getInt("pref_margin_top", 45)
        OmowayViewportConfig.marginBottom = sp.getInt("pref_margin_bottom", 45)

        OmowayViewportConfig.colorBoostEnabled = sp.getBoolean("pref_color_boost_enabled", true)
        OmowayViewportConfig.boostBrightness = sp.getFloat("pref_boost_brightness", 0.14f)
        OmowayViewportConfig.boostContrast = sp.getFloat("pref_boost_contrast", 1.32f)
        OmowayViewportConfig.boostGamma = sp.getFloat("pref_boost_gamma", 0.68f)
        OmowayViewportConfig.boostSaturation = sp.getFloat("pref_boost_saturation", 1.35f)

        joystickAsDpad = sp.getBoolean("pref_joystick_as_dpad", true)
    }

    fun setJoystickAsDpad(context: Context, enabled: Boolean) {
        joystickAsDpad = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pref_joystick_as_dpad", enabled)
            .apply()
    }

    /**
     * Menyimpan pengaturan Margin HUD & Rekayasa Warna/Kecerahan ke SharedPreferences.
     */
    fun saveViewportAndBoostSettings(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pref_safe_margins_enabled", OmowayViewportConfig.useClusterSafeMargins)
            .putInt("pref_margin_left", OmowayViewportConfig.marginLeft)
            .putInt("pref_margin_right", OmowayViewportConfig.marginRight)
            .putInt("pref_margin_top", OmowayViewportConfig.marginTop)
            .putInt("pref_margin_bottom", OmowayViewportConfig.marginBottom)
            .putBoolean("pref_color_boost_enabled", OmowayViewportConfig.colorBoostEnabled)
            .putFloat("pref_boost_brightness", OmowayViewportConfig.boostBrightness)
            .putFloat("pref_boost_contrast", OmowayViewportConfig.boostContrast)
            .putFloat("pref_boost_gamma", OmowayViewportConfig.boostGamma)
            .putFloat("pref_boost_saturation", OmowayViewportConfig.boostSaturation)
            .apply()
    }

    /**
     * Menyimpan target MAC & Nama BLE ke penyimpanan lokal HP (tidak pernah ter-commit ke git).
     */
    fun saveBleMac(context: Context, mac: String, name: String = "") {
        if (mac.isBlank()) return
        targetBleMac = mac.trim().uppercase()
        if (name.isNotBlank()) targetBleName = name.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLE_MAC, targetBleMac)
            .putString(KEY_BLE_NAME, targetBleName)
            .apply()
    }

    /**
     * Menyimpan target MAC & Nama P2P ke penyimpanan lokal HP (tidak pernah ter-commit ke git).
     */
    fun saveP2pMac(context: Context, mac: String, name: String = "") {
        if (mac.isBlank()) return
        targetP2pMac = mac.trim().uppercase()
        if (name.isNotBlank()) targetP2pName = name.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_P2P_MAC, targetP2pMac)
            .putString(KEY_P2P_NAME, targetP2pName)
            .apply()
    }

    /**
     * Menghapus pasangan motor tersimpan agar pengguna dapat memindai dan memilih motor baru.
     */
    fun resetMotorPairing(context: Context) {
        targetBleMac = ""
        targetBleName = ""
        targetP2pMac = ""
        targetP2pName = ""
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BLE_MAC)
            .remove(KEY_BLE_NAME)
            .remove(KEY_P2P_MAC)
            .remove(KEY_P2P_NAME)
            .apply()
    }

    /**
     * Menyimpan mode aplikasi (STANDALONE, SERVER, CLIENT) dan Host IP Server.
     */
    fun saveAppMode(context: Context, mode: AppMode, serverIp: String = "", serverName: String = "") {
        appMode = mode
        if (serverIp.isNotBlank()) {
            serverHostIp = serverIp.trim()
        } else if (mode == AppMode.STANDALONE) {
            serverHostIp = "127.0.0.1"
        }
        if (serverName.isNotBlank()) {
            serverHostName = serverName.trim()
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_MODE, mode.name)
            .putString(KEY_SERVER_HOST, serverHostIp)
            .putString(KEY_SERVER_NAME, serverHostName)
            .apply()
    }

    /**
     * Menyimpan kredensial Hotspot Wi-Fi untuk aktivasi Android Auto Wireless WPP.
     */
    fun saveHotspotCredentials(context: Context, ssid: String, pass: String) {
        hotspotSsid = ssid.trim()
        hotspotPass = pass.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOTSPOT_SSID, hotspotSsid)
            .putString(KEY_HOTSPOT_PASS, hotspotPass)
            .apply()
    }

    /**
     * Menyimpan MAC address Bluetooth HP Utama untuk auto-wake poke Android Auto.
     */
    fun savePrimaryPhoneBt(context: Context, mac: String, name: String = "") {
        primaryPhoneBtMac = mac.trim()
        primaryPhoneBtName = name.trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRIMARY_PHONE_BT_MAC, primaryPhoneBtMac)
            .putString(KEY_PRIMARY_PHONE_BT_NAME, primaryPhoneBtName)
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

