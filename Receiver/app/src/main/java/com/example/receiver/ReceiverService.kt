package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ReceiverService : Service() {
    private var nsdAdvertiser: NsdAdvertiser? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "ReceiverServiceChannel")
            .setContentTitle("Screen Mirroring Receiver")
            .setContentText("Listening for streams in the background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        startServer()
    }

    private fun startServer() {
        if (ServerManager.server != null) return

        val server = LocalSignalingServer(8888)
        server.onClientConnected = { socket ->
            Log.d("ReceiverService", "Client connected! Launching MainActivity.")
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        server.onClientDisconnected = {
            Log.d("ReceiverService", "Client disconnected.")
        }
        server.onMessageReceived = { _, _ -> }
        server.start()
        ServerManager.server = server

        nsdAdvertiser = NsdAdvertiser(this)
        nsdAdvertiser?.registerService(8888)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerManager.server?.stop()
        ServerManager.server = null
        nsdAdvertiser?.unregisterService()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ReceiverServiceChannel",
                "Receiver Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
