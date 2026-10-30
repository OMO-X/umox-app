# UMOX Auto 2.0 (Unofficial OMOWAY OMO X Engine)

> **UMOX Auto 2.0** — Native Wireless Android Auto, Hardware GPU Bridge, & Multi-Device Streamer untuk Speedometer Cerdas **OMOWAY (Desay SV Linux Cluster `960×360`)**.

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%201.9-purple.svg)](https://kotlinlang.org)
[![Android Auto](https://img.shields.io/badge/Protokol-AAP%20v1.7%20Native-blue.svg)](https://android.com/auto)
[![Resolution](https://img.shields.io/badge/Resolusi-960%C3%97360%20%40%2060Hz-orange.svg)](#)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

---

## 🌟 Mengapa UMOX Auto Berbeda?

Aplikasi bawaan OMOWAY resmi sangat berat, panas, dan terbatas hanya pada navigasi peta lokal AMap (Tiongkok). **UMOX Auto 2.0** adalah aplikasi native buatan komunitas yang dirancang khusus untuk memproyeksikan **Android Auto (Google Maps, Waze, Spotify, YouTube Music)** langsung ke layar speedometer motor dengan performa tinggi, latency nol, dan fleksibilitas penuh.

---

## ⚡ Fitur-Fitur Unggulan

### 1. 📱 Fleksibilitas Mode: Standalone vs Client-Server (Bagi Beban Anti-Panas)
* **Mode Standalone (1 Ponsel):**
  * Satu ponsel menangani Android Auto sekaligus terhubung langsung ke Wi-Fi motor. Sangat praktis untuk perjalanan harian atau jarak dekat.
* **Mode Client-Server (Dual Ponsel — Rekomendasi Touring Jauh):**
  * **HP Utama (Server / di saku):** Menjalankan aplikasi Android Auto, navigasi berat, musik, GPS, dan panggilan telepon.
  * **HP Donor (Client / di dashboard motor):** Khusus bertindak sebagai penerima video & display driver yang menyemburkan aliran H.264 ke speedometer OMOWAY.
  * **Hasil:** Beban pemrosesan dan suhu terbagi rata. HP utama tetap dingin di saku, baterai awet, dan bebas dari risiko *overheating* di bawah terik matahari!

```mermaid
flowchart LR
    subgraph Mode Dual Phone (Anti-Panas)
        HP1["📱 HP Utama (Server)<br/>Android Auto / Maps / Spotify"] -- "Hotspot 5GHz / USB OTG" --> HP2["📲 HP Donor (Client)<br/>Display Bridge Driver"]
        HP2 -- "Wi-Fi Direct (192.168.2.1:9528)" --> Cluster["🏍️ Speedometer OMOWAY<br/>960×360 @ 60 FPS"]
    end
```

---

### 2. 🔌 Konektivitas Client-Server: USB AOA 2.0 & Wireless Hotspot 5GHz

* **Metode Kabel USB (AOA 2.0 / OTG Type-C) — *Paling Praktis!***
  * **Tinggal colok kabel!** Begitu HP Utama dihubungkan ke HP Client via kabel Type-C/OTG, Android Auto otomatis aktif seketika tanpa perlu membuka menu pengembang (*Developer Settings*) dan tanpa perlu menyalakan *Server Unit Kepala*.
* **Metode Wireless (Wi-Fi Hotspot):**
  * **HP Utama mengaktifkan Hotspot Wi-Fi**, lalu **HP Client tersambung ke Hotspot** tersebut.
  * Di HP Utama, cukup aktifkan menu *"Mulai server unit kepala"* di Android Auto sekali saat awal koneksi. Setelah itu HP Utama sangat ringan dan hemat daya.
  * **Syarat Penting:** HP Client **wajib mendukung Wi-Fi 5 GHz** agar dapat terhubung stabil ke Hotspot HP Utama tanpa menimbulkan gangguan frekuensi (*interference*) dengan Wi-Fi Direct motor.

---

### 3. 👆 Layar Sentuh Interaktif di Aplikasi (Virtual Touchscreen)
* Tampilan **Live Preview** di dashboard aplikasi UMOX bukan hanya video pasif, melainkan **layar sentuh interaktif**.
* Anda dapat menyentuh langsung pratinjau di layar ponsel untuk berinteraksi dengan Android Auto: mengetuk ikon aplikasi, memilih rute di Google Maps, menekan tombol navigasi, hingga menggulir daftar lagu. Koordinat sentuhan dikonversi secara real-time ke virtual display Android Auto.

---

### 4. 🕹️ Smart Joystick & Remote Routing (Tombol Musik Menjadi Kontrol Navigasi)
* Tombol joystick atau knob stang motor yang aslinya hanya didesain pabrikan untuk kontrol media (Play, Pause, Next, Previous, Volume +/-) secara cerdas **di-routing ulang oleh UMOX menjadi kontrol navigasi arah Android Auto**:
  * **Putar Kiri / Prev** ➔ Menggeser fokus navigasi ke kiri / atas (D-Pad Left/Up / Rotary Knob).
  * **Putar Kanan / Next** ➔ Menggeser fokus navigasi ke kanan / bawah (D-Pad Right/Down).
  * **Klik / Play-Pause** ➔ Memilih menu yang disorot (Enter / D-Pad Center).
* Anda dapat mengoperasikan seluruh menu Android Auto langsung dari stang motor tanpa perlu melepaskan tangan atau menyentuh layar HP saat berkendara.

---

### 5. ☀️ Boost Anti-Redup & Margin Aman HUD (Lawan Overlay Gelap OMOWAY)
* **Melawan Lapisan Gelap Speedometer:**
  Speedometer OMOWAY memiliki lapisan transparan hitam bawaan pabrik yang membuat tampilan peta redup di siang hari. UMOX menyematkan **OpenGL ES 2.0 Fragment Shader**:
  * **Gamma & Contrast Boost:** Mengangkat kecerahan bayangan dan detail peta tanpa membuat layar silau di malam hari (tersedia slider 0% hingga +50% Terik Maksimal).
  * **Adaptive GPU Edge Sharpening (Laplacian 3×3):** Mempertajam teks nama jalan, angka kecepatan, dan marka jalan agar tidak buram akibat *downscaling*.
* **Margin Aman HUD (Anti-Tertutup Indikator Motor):**
  * Atur margin atas, bawah, kiri, dan kanan agar teks navigasi Google Maps tidak tertutup oleh jarum speedometer digital `0 km/h` di sisi kanan atau bar indikator baterai di bagian bawah.

---

### 6. 🚀 5-Level Kualitas Stream (Hingga 16 Mbps Super HD @ 60 FPS)
Sesuaikan bitrate transmisi video ke speedometer sesuai kebutuhan:
* **Level 1 (Hemat Daya):** 1.5 Mbps, 30 FPS — Sangat dingin dan hemat baterai.
* **Level 2 (Normal):** 3.5 Mbps, 45 FPS — Seimbang untuk harian.
* **Level 3 (Halus / Standar):** 6.0 Mbps, 60 FPS — Animasi peta 60 Hz mulus.
* **Level 4 (Tinggi):** 10.0 Mbps, 60 FPS — Detail tajam minim kompresi.
* **Level 5 (Super HD):** 16.0 Mbps, 60 FPS — Kualitas visual kristal maksimal dengan buffer kernel TCP 64 KB High-Throughput.

---

### 7. 📺 Fitur Casting Layar & Roadmap YouTube Cast
* **Screen Mirroring (Screencast):** Memproyeksikan seluruh layar ponsel ke speedometer motor secara instan dengan latency rendah.
* **Test Video Baseline:** Uji coba jalur transmisi Port 9528 menggunakan video H.264 internal untuk memastikan koneksi cluster normal.
* **Roadmap YouTube Cast (DIAL & Kode TV):** Mendukung pemutaran video YouTube langsung ke speedometer motor (tanpa screencast) via tombol Cast resmi atau menu Bagikan (Share).

---

## 📊 Spesifikasi Protokol Cluster OMOWAY

| Komponen | Spesifikasi Teknis | Keterangan |
| :--- | :--- | :--- |
| **Hardware Cluster** | **Desay SV Automotive Linux Embedded** | Tampilan Widescreen |
| **IP Gateway Cluster** | **`192.168.2.1`** | Speedometer bertindak sebagai Wi-Fi Direct Group Owner |
| **Port Kontrol** | **TCP `9527`** | Binary Header 8-byte LE (`[Channel][Length]`) + ECDH NIST P-256 (`Msg 49`) |
| **Port Video** | **TCP `9528`** | Binary Header (`Channel 2`) + H.264 Annex-B Stream + Heartbeat `Channel 0` |
| **Resolusi Native** | **`960 × 360` piksel** | Rasio ultra-wide khusus dashboard motor |
| **BLE Service UUID** | **`0000fff0-0000-1000-8000-00805f9b34fb`** | Write: `0xFFFD` (Framing `AA 55`), Notify: `0xFFFE` (ACK `AA 56 00`) |

---

## 📖 Panduan Penggunaan Lengkap

### Skenario A: Mode Standalone (1 Ponsel Saja)
1. Buka aplikasi **UMOX Auto**.
2. Pada Langkah 1, ketuk ikon roda gigi ➔ buka pengaturan Android Auto ➔ ketuk titik tiga (⋮) di pojok kanan atas ➔ pilih **"Mulai server unit kepala"** (*Start head unit server*).
3. Pastikan aplikasi resmi OMOWAY sudah di-*Force Stop* (agar tidak memonopoli port motor).
4. Tekan **PILIH MOTOR** pada Langkah 2 untuk menghubungkan BLE dan Wi-Fi Direct motor (`192.168.2.1`).
5. Tekan tombol **`🚀 MULAI ANDROID AUTO (60 FPS)`** ➔ Navigasi siap di speedometer!

---

### Skenario B: Mode Client-Server via Kabel USB OTG (Paling Mudah & Otomatis)
1. **HP Client (Realme / HP di motor):** Buka UMOX ➔ set Mode ke **`CLIENT`** ➔ hubungkan ke Wi-Fi motor (`192.168.2.1`).
2. **HP Utama (Samsung / HP harian):** Cukup colokkan kabel USB Type-C OTG dari HP Utama ke HP Client.
3. Android Auto di HP Utama akan otomatis mendeteksi sambungan AOA 2.0 dan memproyeksikan video langsung ke HP Client tanpa menyalakan server pengembang secara manual!

---

### Skenario C: Mode Client-Server via Wireless Hotspot 5GHz (Bebas Kabel)
1. **HP Utama (Server):**
   * Nyalakan **Hotspot Pribadi (Frekuensi 5 GHz)**.
   * Buka UMOX ➔ set Mode ke **`SERVER`**.
   * Buka Android Auto ➔ Titik tiga (⋮) ➔ **"Mulai server unit kepala"**. (Setelah menyala, Anda bisa menutup layar atau menyimpan HP di saku).
2. **HP Client (di motor):**
   * Sambungkan Wi-Fi HP Client ke Hotspot HP Utama.
   * Buka UMOX ➔ set Mode ke **`CLIENT`**.
   * Hubungkan ke motor (`192.168.2.1`) pada Langkah 2.
   * Tekan tombol **`🚀 MULAI ANDROID AUTO`** ➔ Video H.264 akan diterima dari HP Utama dan ditembakkan secara mulus ke speedometer OMOWAY!

---

## 🛠️ Kompilasi dari Kode Sumber

Proyek ini dibangun menggunakan **Android Gradle Plugin** murni tanpa ketergantungan library pihak ketiga yang rumit.

```powershell
# 1. Pindah ke direktori proyek
cd umox-app

# 2. Kompilasi APK Debug (untuk pengujian)
..\tools\gradle-8.7\bin\gradle.bat assembleDebug

# 3. Kompilasi APK Release (produksi siap pakai)
..\tools\gradle-8.7\bin\gradle.bat assembleRelease

# 4. Pasang ke HP via ADB
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## ⚠️ Tips Tampilan Optimal di Speedometer
* **Gunakan Mode Peta Vektor:** Di Google Maps, gunakan tampilan standar (hindari mode Satelit/foto udara saat malam hari karena tekstur foto satelit yang di-scale ke 960×360 menghasilkan bintik noise).
* **Atur Boost Sesuai Waktu:** Di malam hari, atur Boost Anti-Redup ke **Normal (0%)** agar warna hitam OLED tetap pekat dan kontras tajam. Di siang hari terik, geser ke **30% - 50%**.
* **Matikan Aplikasi OMOWAY Resmi:** Selalu pastikan aplikasi resmi OMOWAY di ponsel dalam status *Force Stop* sebelum menghubungkan UMOX, agar tidak terjadi perebutan soket TCP Port 9527.

---

## 📄 Lisensi & Kontribusi
Proyek ini dikembangkan secara independen untuk komunitas pengendara motor OMOWAY. Kontribusi kode, laporan bug, dan pengajuan fitur sangat terbuka melalui GitHub Pull Requests & Issues.
