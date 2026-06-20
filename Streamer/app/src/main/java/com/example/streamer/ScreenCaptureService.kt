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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.net.URI

class ScreenCaptureService : Service() {
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var displayManager: DisplayManager? = null

    private var baseLayoutMode = "FILL"
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentMaxRes = 1080
    private var currentFps = 60

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            updateDynamicLayout()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
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
        currentMaxRes = intent.getIntExtra("MAX_RES", 1080)
        currentFps = intent.getIntExtra("FPS", 60)
        val bitrate = intent.getIntExtra("BITRATE", 20000)
        
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)

        signalingClient = SignalingClient(
            serverUri = serverUri,
            onConnected = {
                webRtcClient = WebRtcClient(this, signalingClient!!, data)
                val (w, h) = calculateDimensions(currentMaxRes)
                currentWidth = w
                currentHeight = h
                
                // Determine initial layout
                val initialLayout = getActiveLayoutMode()
                webRtcClient?.startStream(w, h, currentFps, bitrate, initialLayout)
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

    private fun calculateDimensions(maxRes: Int): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        val nativeWidth = displayMetrics.widthPixels
        val nativeHeight = displayMetrics.heightPixels
        
        var targetWidth = nativeWidth
        var targetHeight = nativeHeight

        // maxRes is now the maximum allowed length of the LONGEST side (e.g. 1920 for 1080p TV)
        val currentMax = maxOf(targetWidth, targetHeight)
        if (currentMax > maxRes) {
            val scale = maxRes.toFloat() / currentMax.toFloat()
            targetWidth = (targetWidth * scale).toInt()
            targetHeight = (targetHeight * scale).toInt()
        }
        
        // Ensure even dimensions
        if (targetWidth % 2 != 0) targetWidth -= 1
        if (targetHeight % 2 != 0) targetHeight -= 1

        return Pair(targetWidth, targetHeight)
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
        if (currentWidth == 0 || currentHeight == 0) return // Not initialized yet

        val (newW, newH) = calculateDimensions(currentMaxRes)
        if (newW != currentWidth || newH != currentHeight) {
            currentWidth = newW
            currentHeight = newH
            webRtcClient?.changeCaptureFormat(newW, newH, currentFps)
        }

        if (baseLayoutMode == "HYBRID") {
            val activeLayout = getActiveLayoutMode()
            webRtcClient?.sendLayout(activeLayout)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager?.unregisterDisplayListener(displayListener)
        webRtcClient?.close()
        signalingClient?.close()
    }
}
