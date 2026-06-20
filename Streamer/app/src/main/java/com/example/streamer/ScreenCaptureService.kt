package com.example.streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.URI

class ScreenCaptureService : Service() {
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var displayManager: DisplayManager? = null

    private var baseLayoutMode = "FILL"
    private var currentCapW = 0
    private var currentCapH = 0
    private var currentOutW = 0
    private var currentOutH = 0
    private var currentFps = 60
    private var currentMaxRes = 1920 // e.g. 1080p -> longest side 1920
    private var lastActiveLayout = "FILL"

    private var rotationJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            rotationJob?.cancel()
            rotationJob = CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                updateDynamicLayout()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == "CHANGE_LAYOUT") {
            val mode = intent.getStringExtra("LAYOUT_MODE")
            if (mode != null) {
                baseLayoutMode = mode
                updateDynamicLayout()
            }
            return START_NOT_STICKY
        } else if (intent?.action == "CHANGE_BITRATE") {
            val minKbps = intent.getIntExtra("MIN_BITRATE", 2000)
            val maxKbps = intent.getIntExtra("MAX_BITRATE", 15000)
            webRtcClient?.changeBitrate(minKbps, maxKbps)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "ScreenCaptureChannel")
            .setContentTitle("Screen Mirroring")
            .setContentText("Streaming your screen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val ip = intent?.getStringExtra("IP") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("PORT", 8888)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            intent.getParcelableExtra("DATA")
        } ?: return START_NOT_STICKY

        val serverUri = URI("ws://$ip:$port")
        baseLayoutMode = intent.getStringExtra("LAYOUT_MODE") ?: "FILL"
        currentMaxRes = intent.getIntExtra("MAX_RES", 1920)
        currentFps = intent.getIntExtra("FPS", 60)
        val minBitrate = intent.getIntExtra("MIN_BITRATE", 2000)
        val maxBitrate = intent.getIntExtra("MAX_BITRATE", 15000)
        val enableLogging = intent.getBooleanExtra("ENABLE_LOGGING", false)
        
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)

        signalingClient = SignalingClient(
            serverUri = serverUri,
            onConnected = {
                webRtcClient = WebRtcClient(this, signalingClient!!, data, enableLogging)
                val initialLayout = getActiveLayoutMode()
                lastActiveLayout = initialLayout
                val dims = calculateDimensions(currentMaxRes, initialLayout)
                currentCapW = dims[0]
                currentCapH = dims[1]
                currentOutW = dims[2]
                currentOutH = dims[3]
                webRtcClient?.startStream(currentCapW, currentCapH, currentOutW, currentOutH, currentFps, minBitrate, maxBitrate, initialLayout)
            },
            onMessageReceived = { msg ->
                webRtcClient?.handleSignalingMessage(msg)
            },
            onDisconnected = {
                stopSelf()
            }
        )
        signalingClient?.connect()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ScreenCaptureChannel",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun calculateDimensions(maxRes: Int, activeLayout: String): IntArray {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        val nativeW = displayMetrics.widthPixels
        val nativeH = displayMetrics.heightPixels
        val isLandscape = nativeW > nativeH

        // We want the TV (Landscape 16:9) to always receive exactly what it expects.
        // For 1080p TV, standard 16:9 is 1920x1080.
        val outW = maxRes
        val outH = maxRes * 9 / 16

        if (activeLayout == "FIT") {
            // We want black bars. We set the VirtualDisplay to exactly 16:9.
            // Android OS will automatically letterbox the screen within this VirtualDisplay.
            return intArrayOf(outW, outH, outW, outH)
        } else {
            // FILL mode. We want WebRTC to crop.
            // We set the VirtualDisplay to the phone's native aspect ratio so the OS DOES NOT draw black bars.
            // We capture at full native resolution to ensure WebRTC has enough pixels to crop to 1080p without downscaling.
            var capW = nativeW
            var capH = nativeH

            // Ensure even
            if (capW % 2 != 0) capW -= 1
            if (capH % 2 != 0) capH -= 1

            // Then we tell WebRTC to crop this pure screen into a 16:9 window via adaptOutputFormat.
            return intArrayOf(capW, capH, outW, outH)
        }
    }

    private fun getActiveLayoutMode(): String {
        if (baseLayoutMode != "HYBRID") return baseLayoutMode
        
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
        return if (isLandscape) "FILL" else "FIT"
    }

    private fun updateDynamicLayout() {
        if (currentCapW == 0 || currentCapH == 0) return // Not initialized yet

        val activeLayout = getActiveLayoutMode()
        val dims = calculateDimensions(currentMaxRes, activeLayout)
        val newCapW = dims[0]
        val newCapH = dims[1]
        val newOutW = dims[2]
        val newOutH = dims[3]

        if (newCapW != currentCapW || newCapH != currentCapH || activeLayout != lastActiveLayout) {
            currentCapW = newCapW
            currentCapH = newCapH
            currentOutW = newOutW
            currentOutH = newOutH
            lastActiveLayout = activeLayout
            webRtcClient?.changeCaptureFormat(newCapW, newCapH, newOutW, newOutH, currentFps)
            webRtcClient?.sendLayout(activeLayout) // Still send it so Receiver shows the diagnostic text
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager?.unregisterDisplayListener(displayListener)
        webRtcClient?.close()
        signalingClient?.close()
    }
}
