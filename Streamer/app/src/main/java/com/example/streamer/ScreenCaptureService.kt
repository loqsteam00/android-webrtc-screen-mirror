package com.example.streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.URI

class ScreenCaptureService : Service() {
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null

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
        val width = intent.getIntExtra("WIDTH", 1280)
        val height = intent.getIntExtra("HEIGHT", 720)
        val fps = intent.getIntExtra("FPS", 30)
        val bitrate = intent.getIntExtra("BITRATE", 2000)
        
        signalingClient = SignalingClient(
            serverUri = serverUri,
            onConnected = {
                webRtcClient = WebRtcClient(this, signalingClient!!, data)
                webRtcClient?.startStream(width, height, fps, bitrate)
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

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.close()
        signalingClient?.close()
    }
}
