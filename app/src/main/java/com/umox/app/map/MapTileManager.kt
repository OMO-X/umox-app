package com.umox.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Manajer Tile Peta Visual Nyata (Satellite & Vector Road Map) untuk UMOX HUD.
 * Mengunduh dan merangkai peta visual resolusi tinggi secara headless (tanpa screen mirroring)
 * pada resolusi asli 960x360 piksel. Baterai HP hemat, layar HP bisa mati total.
 */
class MapTileManager(
    private val context: Context,
    private val onMapBitmapUpdated: (Bitmap) -> Unit
) {
    companion object {
        private const val TAG = "MapTileManager"
        private const val DEFAULT_ZOOM = 16 // Zoom level ideal untuk navigasi kendaraan kota (detail jalan terlihat jelas)
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var downloadJob: Job? = null

    // Cache memori untuk 50 tile gambar 256x256 (~6 MB RAM)
    private val memoryCache = object : LruCache<String, Bitmap>(50) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val diskCacheDir = File(context.cacheDir, "map_tiles").apply { mkdirs() }

    // Bitmap gabungan 960x360 yang akan disematkan ke kanvas HUD
    private val composedBitmap: Bitmap = Bitmap.createBitmap(960, 360, Bitmap.Config.ARGB_8888)
    private val composeCanvas = Canvas(composedBitmap)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var lastRenderedLat: Double = 0.0
    private var lastRenderedLon: Double = 0.0

    /**
     * Memperbarui koordinat GPS dan memicu render peta visual di latar belakang.
     */
    fun updateLocation(lat: Double, lon: Double, isSatellite: Boolean = true) {
        // Abaikan jika koordinat belum bergeser signifikan (< 3 meter)
        val distMoved = sqrt((lat - lastRenderedLat).pow(2.0) + (lon - lastRenderedLon).pow(2.0)) * 111_000
        if (distMoved < 3.0 && lastRenderedLat != 0.0) return

        lastRenderedLat = lat
        lastRenderedLon = lon

        downloadJob?.cancel()
        downloadJob = scope.launch {
            renderMapTiles(lat, lon, DEFAULT_ZOOM, isSatellite)
        }
    }

    private fun renderMapTiles(lat: Double, lon: Double, zoom: Int, isSatellite: Boolean) {
        val n = 1 shl zoom
        val centerTileXDouble = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val centerTileYDouble = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n

        val centerTileX = centerTileXDouble.toInt()
        val centerTileY = centerTileYDouble.toInt()

        // Hitung offset piksel sub-tile agar pergerakan motor halus
        val subTileOffsetX = ((centerTileXDouble - centerTileX) * 256).toInt()
        val subTileOffsetY = ((centerTileYDouble - centerTileY) * 256).toInt()

        // Posisi motor di layar (berada di koordinat X: 300, Y: 210 untuk memberi ruang kartu navigasi)
        val vehicleScreenX = 300
        val vehicleScreenY = 210

        // Bersihkan kanvas komposisi
        composeCanvas.drawColor(android.graphics.Color.rgb(10, 15, 26))

        // Unduh dan pasang grid 4x3 tile (lingkup 1024 x 768 piksel untuk menutup 960x360)
        for (dx in -1..2) {
            for (dy in -1..1) {
                val tileX = centerTileX + dx
                val tileY = centerTileY + dy

                val tileBitmap = getTileBitmap(tileX, tileY, zoom, isSatellite)
                if (tileBitmap != null) {
                    val drawX = (vehicleScreenX - subTileOffsetX + (dx * 256)).toFloat()
                    val drawY = (vehicleScreenY - subTileOffsetY + (dy * 256)).toFloat()
                    composeCanvas.drawBitmap(tileBitmap, drawX, drawY, tilePaint)
                }
            }
        }

        onMapBitmapUpdated(composedBitmap)
    }

    private fun getTileBitmap(x: Int, y: Int, zoom: Int, isSatellite: Boolean): Bitmap? {
        val cacheKey = "${if (isSatellite) "sat" else "dark"}_${zoom}_${x}_${y}"

        // 1. Cek Memory Cache
        memoryCache.get(cacheKey)?.let { return it }

        // 2. Cek Disk Cache Lokal
        val diskFile = File(diskCacheDir, "$cacheKey.img")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return bmp
                }
            } catch (_: Exception) {}
        }

        // 3. Unduh dari Server Tile (Esri Satellite / Carto Dark)
        val tileUrl = if (isSatellite) {
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$y/$x.jpg"
        } else {
            "https://a.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png"
        }

        return try {
            val url = URL(tileUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "UMOX-Cockpit/1.0 (Android; DesaySV HeadUnit)")
            }

            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    val bytes = input.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        memoryCache.put(cacheKey, bmp)
                        try {
                            FileOutputStream(diskFile).use { it.write(bytes) }
                        } catch (_: Exception) {}
                        bmp
                    } else null
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
