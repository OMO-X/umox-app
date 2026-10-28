# UMOX Auto 2.0 (Unofficial Omoway OMO X App)

> **UMOX Auto 2.0** — Native Wireless Android Auto & Hardware GPU Stream Bridge untuk Speedometer Cerdas **OMOWAY (Desay SV Linux Cluster `960×360`)**

**UMOX** adalah aplikasi Android Native (Kotlin) ultra-ringan yang dirancang untuk menggantikan aplikasi bawaan OMOWAY yang berat dan terbatas pada peta AMap (Tiongkok). Dengan **UMOX Auto 2.0**, Anda dapat langsung memproyeksikan **Android Auto (Google Maps, Waze, Spotify, YouTube Music)** maupun **Mirroring Layar HP** ke layar speedometer motor OMOWAY secara *full wireless* tanpa memerlukan aplikasi pihak ketiga seperti Open Head Unit.

---

## ⚡ Keunggulan & Arsitektur UMOX Auto 2.0

### 1. Native Android Auto Receiver (`127.0.0.1:5277` & `5288`)
* Terhubung langsung ke **Server Unit Kepala (Head Unit Server)** bawaan Android Auto di dalam ponsel yang sama (*loopback*).
* Implementasi mandiri protokol **AAP v1.7 (Android Auto Protocol)** lengkap dengan:
  * **In-Memory TLS 1.2/1.3 Handshake** ([`AaTlsEngine.kt`](app/src/main/java/com/umox/app/aa/AaTlsEngine.kt) & [`AaKeyManager.kt`](app/src/main/java/com/umox/app/aa/AaKeyManager.kt)) menggunakan sertifikat otentikasi Head Unit.
  * **Zero-Dependency Protobuf Encoder/Decoder** ([`AaProto.kt`](app/src/main/java/com/umox/app/aa/AaProto.kt)) untuk negosiasi resolusi *widescreen*, sensor cahaya, fokus navigasi/audio, dan input tombol.
  * **Sinkronisasi Tema Siang/Malam Otomatis**: Menerima sinyal sensor cahaya dari speedometer motor (`Channel 7, Msg 37`) dan otomatis mengubah tema Google Maps Android Auto antara *Day* dan *Night*.
  * **Quick Remote Control**: Tombol pintas di aplikasi untuk berpindah cepat ke **🗺️ Google Maps**, **🎵 Musik**, **⏯ Play/Pause**, dan **🌓 Tema**.

### 2. Zero-Copy Hardware GPU Surface Bridge (`960×360` @ 30 FPS CBR)
* Aliran video H.264 dari Android Auto (`800×480` VFR) didekode oleh **Hardware AVC Decoder** langsung ke **`InputSurface` GPU milik Hardware AVC Encoder (`960×360`)** ([`MediaEncoder.kt`](app/src/main/java/com/umox/app/stream/MediaEncoder.kt)):
  * **0% CPU Pixel Copy**: Seluruh proses *decoding*, *scaling* ke rasio `960×360`, dan *re-encoding* berjalan murni di hardware GPU/VPU ponsel (bebas panas dan hemat baterai).
  * **Anti-Freeze Frame Repeat (`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 33_333 µs`)**: Menjamin aliran konstan **30 FPS** meskipun tampilan peta sedang diam, serta merespons permintaan *KeyFrame* (`Msg 11`) dari speedometer dalam **< 33 ms**.

### 3. Desay SV H.264 Annex-B NALU Sanitizer ([`H264NaluSanitizer.kt`](app/src/main/java/com/umox/app/stream/H264NaluSanitizer.kt))
Decoder hardware Linux Desay SV pada Port `9528` memiliki aturan parser yang ketat. `H264NaluSanitizer` memastikan:
1. Seluruh *start code* dinormalisasi menjadi **4-byte Annex-B (`00 00 00 01`)**.
2. NALU **`SPS (type 7)`** dan **`PPS (type 8)`** tidak pernah dikirim terpisah (*standalone*), melainkan disimpan (*cached*) dan **ditempelkan langsung di depan setiap `IDR KeyFrame (type 5)`** menjadi satu paket utuh:
   `[00 00 00 01 SPS] + [00 00 00 01 PPS] + [00 00 00 01 IDR]`.
3. NALU sampah seperti **`SEI (type 6)`**, **`AUD (type 9)`**, dan **`Filler (type 12)`** otomatis dibuang agar gambar bebas artefak dan tidak pernah mengalami *buffer drop*.

### 4. Auto-Discovery BLE GATT & Wi-Fi Direct (P2P)
* **BLE Sniffer & GATT Explorer (`Service 0xFFF0`)**:
  * Memindai sinyal BLE motor secara otomatis, menyambung ke karakteristik `0xFFFD` (Write) dan `0xFFFE` (Notify), lalu mengirim kueri *Function ID 1569* (`extSearchWifiInfo`) untuk mengekstrak **SSID, Password WPA2, MAC P2P, dan IP Gateway (`192.168.2.1`)** secara langsung dari motor.
  * Membaca telemetri *real-time* motor: **Persentase Baterai (%)**, **Estimasi Jarak Tempuh (km)**, **Kecepatan (km/h)**, dan **Odometer**.
* **Wi-Fi Direct Client (`groupOwnerIntent = 0`)**:
  * Menyambungkan ponsel sebagai *P2P Client* ke grup Wi-Fi Direct speedometer (`DIRECT-OMO-*` pada `192.168.2.1`) sambil mempertahankan koneksi internet seluler **4G/5G** di ponsel.

---

## 📊 Ringkasan Spesifikasi Protokol Speedometer OMOWAY

| Parameter | Spesifikasi Teknis | Keterangan |
| :--- | :--- | :--- |
| **Vendor Cluster** | **Desay SV Automotive** | OS Linux Embedded |
| **IP Gateway Cluster** | **`192.168.2.1`** | Speedometer bertindak sebagai Wi-Fi Direct Group Owner |
| **Port Kontrol (TCP)** | **`9527`** | Binary Header 8-Byte LE (`[Channel][Length]`) + Handshake ECDH (`Msg 49`) & JSON Control (`Channel 7`) |
| **Port Video (TCP)** | **`9528`** | Binary Header 8-Byte LE (`Channel 2`) + H.264 Annex-B Stream + Heartbeat `Channel 0` tiap 800 ms |
| **Resolusi & Codec** | **`960 × 360` piksel** | H.264 Baseline Profile Level 3.1, **30 FPS**, **2.5 Mbps CBR**, Keyframe tiap 1 detik |
| **BLE Service UUID** | **`0000fff0-0000-1000-8000-00805f9b34fb`** | Write: `0xFFFD` (Framing `AA 55`), Notify: `0xFFFE` (ACK `AA 56 00`) |

---

## 📱 Cara Penggunaan (3 Langkah Mudah)

1. **Aktifkan Server Unit Kepala Android Auto (`127.0.0.1:5277`)**
   * Tekan tombol **`Buka AA`** pada Langkah 1 di aplikasi UMOX.
   * Di halaman pengaturan Android Auto yang terbuka, ketuk menu **Titik Tiga (⋮)** di pojok kanan atas ➔ pilih **"Mulai server unit kepala"** (*Start head unit server*).
   * Indikator Langkah 1 di UMOX akan otomatis berubah menjadi **Hijau (✔ Aktif)**.
2. **Hubungkan ke Motor (`Auto-Link`)**
   * Nyalakan motor OMOWAY (pastikan aplikasi resmi OMOWAY sudah ditutup / *Force Stop* agar tidak memonopoli port `9527`).
   * Tekan tombol **`Auto-Link`** pada Langkah 2. UMOX akan memindai BLE motor, mengambil kredensial Wi-Fi Direct, dan menyambung ke `192.168.2.1`.
3. **Mulai Streaming!**
   * Tekan tombol utama **`🚀 MULAI ANDROID AUTO`** (atau pilih **📱 Mirror Layar HP** / **🎬 Test Video 960×360**).
   * Navigasi Google Maps & kontrol musik Android Auto akan langsung tampil secara mulus di layar speedometer motor!

---

## 📁 Struktur Direktori Kode Sumber

```text
umox-app/app/src/main/java/com/umox/app/
├── MainActivity.kt                  # Dashboard utama Glassmorphic & orkestrasi UI/telemetri
├── aa/
│   ├── AaHeadlessReceiver.kt        # Native AAP v1.7 Client + Hardware AVC Decoder -> GPU Surface
│   ├── AaTlsEngine.kt               # In-memory SSLEngine TLS wrapper/unwrapper untuk enkripsi AAP
│   ├── AaKeyManager.kt              # X509 Certificate & RSA Private Key loader (res/raw/cert & privkey)
│   └── AaProto.kt                   # Lightweight Protobuf Wire Format reader & writer
├── stream/
│   ├── ScreenCastService.kt         # Foreground Service pengelola sesi Android Auto, Mirror, & Test Video
│   ├── MediaEncoder.kt              # Hardware H.264 Baseline Encoder (960x360 @ 30fps CBR, Surface Input)
│   ├── H264NaluSanitizer.kt         # Normalisasi Annex-B 4-byte, strip SEI/AUD, & gabung SPS+PPS+IDR
│   ├── ControlChannelClient.kt      # Klien TCP Port 9527 (ECDH NIST P-256 Msg 49, Heartbeat, SHOW/HIDE)
│   ├── SocketStreamer.kt            # Klien TCP Port 9528 (Zero-allocation framing, TCP_NODELAY, FPS meter)
│   └── H264AssetPlayer.kt           # Pemutar file uji test_baseline.h264 untuk verifikasi jalur video
├── ble/
│   ├── BleSniffer.kt                # Pemindai BLE Low-Latency dengan deteksi Service 0xFFF0 & OUI Desay SV
│   ├── GattExplorer.kt              # Klien GATT (Auto-query kredensial Wi-Fi 1569 & parser telemetri)
│   └── MotorcycleTelemetry.kt       # Model data telemetri baterai, jarak tempuh, kecepatan, & odometer
├── wifi/
│   └── WifiP2pProbe.kt              # Manajer koneksi Wi-Fi Direct (P2P Client groupOwnerIntent = 0)
└── config/
    └── AppConfig.kt                 # Konfigurasi default IP (192.168.2.1), Port (9527/9528), & payload BLE
```

---

## 🛠️ Kompilasi & Instalasi via Terminal (ADB)

```powershell
# 1. Kompilasi APK Debug dengan Gradle
C:\Users\armadeas\UMOX\tools\gradle-8.7\bin\gradle.bat assembleDebug -p c:\Users\armadeas\UMOX\umox-app

# 2. Instal ke HP via ADB (USB atau Wireless Debugging)
C:\Users\armadeas\UMOX\tools\platform-tools\adb.exe install -r c:\Users\armadeas\UMOX\umox-app\app\build\outputs\apk\debug\app-debug.apk

# 3. Jalankan Aplikasi UMOX Auto 2.0
C:\Users\armadeas\UMOX\tools\platform-tools\adb.exe shell am start -n com.umox.app/.MainActivity
```
