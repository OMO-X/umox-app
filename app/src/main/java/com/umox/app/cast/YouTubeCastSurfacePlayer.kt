package com.umox.app.cast

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pemutar Video YouTube berbasis OpenGL Surface & VirtualDisplay Presentation.
 *
 * Merender video YouTube langsung ke Surface Encoder (960x360 @ 60 FPS) tanpa menggunakan
 * layar ponsel (Screen Mirroring). Mendukung kontrol penuh dari tombol fisik motor / joystick UMOX.
 */
class YouTubeCastSurfacePlayer(
    private val context: Context,
    private val outputSurface: Surface,
    private val onVideoTitleChanged: (String) -> Unit = {},
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "YouTubeCastPlayer"
        const val WIDTH = 960
        const val HEIGHT = 360
        const val DPI = 160
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private val isReady = AtomicBoolean(false)
    private var pendingVideoId: String? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var currentTitle: String = "YouTube Video"
        private set

    fun start(initialVideoId: String? = null) {
        pendingVideoId = initialVideoId
        mainHandler.post {
            try {
                setupPresentation()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memulai Presentation YouTube: ${e.message}", e)
                onLog("✖ Gagal memuat player YouTube: ${e.message}")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                webView?.destroy()
                webView = null
                presentation?.dismiss()
                presentation = null
                virtualDisplay?.release()
                virtualDisplay = null
                isReady.set(false)
                onLog("⏹ Pemutar YouTube Cast dihentikan.")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping YouTube player: ${e.message}")
            }
        }
    }

    fun loadVideo(videoId: String) {
        pendingVideoId = videoId
        mainHandler.post {
            if (isReady.get()) {
                webView?.evaluateJavascript("loadVideo('$videoId');", null)
                onLog("🎬 Memutar video YouTube: $videoId")
            }
        }
    }

    fun play() {
        mainHandler.post {
            webView?.evaluateJavascript("play();", null)
        }
    }

    fun pause() {
        mainHandler.post {
            webView?.evaluateJavascript("pause();", null)
        }
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun seekForward(seconds: Float = 10f) {
        mainHandler.post {
            webView?.evaluateJavascript("forward($seconds);", null)
            onLog("⏩ Maju $seconds detik")
        }
    }

    fun seekRewind(seconds: Float = 10f) {
        mainHandler.post {
            webView?.evaluateJavascript("rewind($seconds);", null)
            onLog("⏪ Mundur $seconds detik")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPresentation() {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val vd = displayManager.createVirtualDisplay(
            "UMOX-YouTubeDisplay",
            WIDTH,
            HEIGHT,
            DPI,
            outputSurface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        virtualDisplay = vd

        val pres = object : Presentation(context, vd.display) {
            override fun onCreate(savedInstanceState: android.os.Bundle?) {
                super.onCreate(savedInstanceState)
                window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                window?.setLayout(WIDTH, HEIGHT)

                val root = FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(WIDTH, HEIGHT)
                    setBackgroundColor(0xFF000000.toInt())
                }

                val wv = WebView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF000000.toInt())
                    settings.apply {
                        javaScriptEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        domStorageEnabled = true
                        allowFileAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            onLog("✔ WebView YouTube Player siap.")
                        }
                    }
                    addJavascriptInterface(JsBridge(), "AndroidBridge")
                }

                root.addView(wv)
                setContentView(root)
                webView = wv

                val vid = pendingVideoId ?: "dQw4w9WgXcQ"
                wv.loadDataWithBaseURL("https://www.youtube.com", buildHtml(vid), "text/html", "UTF-8", null)
            }
        }
        presentation = pres
        pres.show()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onReady() {
            isReady.set(true)
            onLog("★ YouTube IFrame Player AKTIF (960x360 @ 60 FPS)!")
            pendingVideoId?.let {
                mainHandler.post {
                    loadVideo(it)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            // 1 = PLAYING, 2 = PAUSED, 0 = ENDED, 3 = BUFFERING
            val playing = (state == 1)
            isPlaying = playing
            mainHandler.post {
                onPlaybackStateChanged(playing)
            }
        }

        @JavascriptInterface
        fun onTitle(title: String) {
            currentTitle = title
            mainHandler.post {
                onVideoTitleChanged(title)
                onLog("🎵 Memutar: $title")
            }
        }
    }

    private fun buildHtml(videoId: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body, html { width: 100%; height: 100%; background-color: #000; overflow: hidden; }
                #player { width: 100vw; height: 100vh; position: absolute; top: 0; left: 0; }
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                var player;
                var pendingId = '$videoId';
                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    videoId: pendingId,
                    playerVars: {
                      'autoplay': 1,
                      'controls': 0,
                      'modestbranding': 1,
                      'rel': 0,
                      'fs': 0,
                      'playsinline': 1,
                      'iv_load_policy': 3,
                      'enablejsapi': 1
                    },
                    events: {
                      'onReady': onPlayerReady,
                      'onStateChange': onPlayerStateChange
                    }
                  });
                }
                function onPlayerReady(event) {
                  event.target.playVideo();
                  if (window.AndroidBridge) AndroidBridge.onReady();
                }
                function onPlayerStateChange(event) {
                  if (window.AndroidBridge) AndroidBridge.onStateChange(event.data);
                  if (event.data === 1 && player && player.getVideoData) {
                    try {
                      var d = player.getVideoData();
                      if (d && d.title && window.AndroidBridge) AndroidBridge.onTitle(d.title);
                    } catch(e){}
                  }
                }
                function loadVideo(id) {
                  if (player && player.loadVideoById) {
                    player.loadVideoById(id);
                  } else {
                    pendingId = id;
                  }
                }
                function play() { if (player && player.playVideo) player.playVideo(); }
                function pause() { if (player && player.pauseVideo) player.pauseVideo(); }
                function forward(sec) { if (player && player.getCurrentTime) player.seekTo(player.getCurrentTime() + sec, true); }
                function rewind(sec) { if (player && player.getCurrentTime) player.seekTo(Math.max(0, player.getCurrentTime() - sec), true); }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
