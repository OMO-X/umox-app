# UMOX (Unofficial Omoway OMO X App)

> **UMOX** = **U**nofficial O**m**oway **O**MO **X** App

**UMOX** adalah aplikasi Android Native (Kotlin) ringan yang dikembangkan sebagai pengganti aplikasi resmi OMOWAY untuk ekosistem kendaraan cerdas (Desay SV / Omoway hardware). 

Proyek ini dibangun untuk mengatasi masalah konsumsi daya dan *overheating* ekstrem dari aplikasi resmi, serta menyediakan jembatan (*bridge*) untuk memproyeksikan **Google Maps / Waze / Android Auto** menggantikan peta AMap ke layar speedometer kendaraan.

---

## 🚀 Fitur Utama UMOX

1. **BLE Hardware Radar & Sniffer**
   * Memindai sinyal Bluetooth Low Energy (BLE) di sekitar kendaraan tanpa membebani CPU.
   * Filter cerdas untuk mendeteksi modul *Desay SV, Omoway, DDKA, CMP, dan Speedometer*.
   * Ekstraksi Raw Manufacturer Data, RSSI sinyal, dan Service UUIDs.
2. **GATT Explorer**
   * Menginspeksi struktur service GATT dan karakteristik komunikasi BLE kendaraan.
3. **Wi-Fi Direct (P2P) Probe**
   * Mendeteksi koneksi Wi-Fi Direct saat berkomunikasi dengan speedometer.
   * Menangkap status Group Owner dan IP address speedometer (biasanya `192.168.49.1`).
4. **Automotive Stream Port Scanner**
   * Memindai port video dan telemetri lokal:
     * Port 554 & 8554 (RTSP H.264/H.265 stream)
     * Port 8080 & 8000 (HTTP / MJPEG video)
     * Port 7000 (Mirroring socket)
     * Port 1883 & 8883 (MQTT telemetry)
     * Port 1900 & 5353 (SSDP / mDNS)
5. **Cyberpunk HUD Dark Terminal**
   * Desain modern true AMOLED black (`#080B10`) dengan aksen Neon Cyan & Emerald Green.
   * Telemetry quick strip (BLE target, Host IP, Streaming port).
   * Fitur salin log satu ketukan (*one-tap copy to clipboard*).

---

## 🛠️ Kompilasi & Instalasi Cepat

### Via Terminal (ADB Wireless):
```powershell
# Build APK
& "..\tools\gradle-8.7\bin\gradle.bat" assembleDebug

# Hubungkan ADB Wireless & Install ke HP
& "..\tools\platform-tools\adb.exe" connect 192.168.x.x:PORT
& "..\tools\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 📁 Struktur Proyek
* `app/src/main/java/com/umox/app/config/` : Konfigurasi terpusat (MAC address, IP, Port, dan parameter keamanan umum)
* `app/src/main/java/com/umox/app/ble/` : Modul pemindai sinyal BLE & GATT
* `app/src/main/java/com/umox/app/wifi/` : Modul Wi-Fi Direct P2P probe
* `app/src/main/java/com/umox/app/probe/` : Modul Port scanner & packet listener
* `app/src/main/res/layout/` : Antarmuka HUD modern

---

## ⚙️ Konfigurasi Perangkat (AppConfig)

Seluruh parameter identitas perangkat, MAC address, alamat IP, dan port bersifat umum dan dapat disesuaikan pada file [`AppConfig.kt`](app/src/main/java/com/umox/app/config/AppConfig.kt):

* `targetP2pMac`: MAC address Wi-Fi Direct P2P (opsional, biarkan kosong untuk mode auto-discovery).
* `targetBleMac`: MAC address BLE cluster (opsional, biarkan kosong untuk auto-detect via Service UUID 0xFFF0).
* `defaultClusterIp`: IP gateway cluster speedometer (default: `192.168.2.1`).
* `defaultControlPort`: Port TCP saluran kontrol Desay SV (default: `9527`).
* `defaultVideoPort`: Port TCP streaming video H.264 (default: `9528`).
