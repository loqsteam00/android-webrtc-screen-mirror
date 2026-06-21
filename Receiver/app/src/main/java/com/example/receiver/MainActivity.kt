package com.example.receiver

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.receiver.theme.ReceiverTheme
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var eglBase: EglBase
    private lateinit var webRtcClient: WebRtcClient

    private var videoTrack by mutableStateOf<VideoTrack?>(null)
    private var layoutMode by mutableStateOf("FILL")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        eglBase = EglBase.create()
        
        webRtcClient = WebRtcClient(this, eglBase, onVideoTrack = { track ->
            videoTrack = track
        }, onLayout = { mode ->
            layoutMode = mode
        })

        // Start the background service
        val serviceIntent = Intent(this, ReceiverService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Attach to the server
        Thread {
            while (ServerManager.server == null) {
                Thread.sleep(100)
            }
            runOnUiThread {
                ServerManager.server?.let { server ->
                    server.onMessageReceived = { message, _ ->
                        runOnUiThread { webRtcClient.handleSignalingMessage(message) }
                    }
                    server.onClientDisconnected = {
                        runOnUiThread {
                            videoTrack = null
                            webRtcClient.setWebSocket(null)
                            webRtcClient.resetConnection()
                            finishAndRemoveTask()
                        }
                    }
                    server.activeSocket?.let { socket ->
                        webRtcClient.setWebSocket(socket)
                    }
                }
            }
        }.start()

        val localIp = getLocalIpAddress()

        enableEdgeToEdge()
        setContent {
            ReceiverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (videoTrack == null) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Waiting for connection...")
                                Text("IP: $localIp:8888")
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { context ->
                                    SurfaceViewRenderer(context).apply {
                                        init(eglBase.eglBaseContext, null)
                                        setEnableHardwareScaler(false) // Fix for IPTV box forcing stretch
                                        setMirror(false)
                                        setScalingType(when(layoutMode) {
                                            "FILL" -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
                                            "FIT" -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                            else -> RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
                                        })
                                        videoTrack?.addSink(this) // Only add sink once on creation
                                    }
                                },
                                update = { view ->
                                    view.setScalingType(when(layoutMode) {
                                        "FILL" -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
                                        "FIT" -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                        else -> RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
                                    })
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("MainActivity", "Error getting IP", ex)
        }
        return "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerManager.server?.onMessageReceived = null
        ServerManager.server?.onClientDisconnected = null
        webRtcClient.close()
        eglBase.release()
    }
}
