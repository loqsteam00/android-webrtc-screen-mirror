package com.example.receiver

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
    private lateinit var signalingServer: LocalSignalingServer
    private lateinit var nsdAdvertiser: NsdAdvertiser

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

        signalingServer = LocalSignalingServer(
            port = 8888,
            onClientConnected = { socket ->
                webRtcClient.setWebSocket(socket)
            },
            onClientDisconnected = {
                videoTrack = null
                webRtcClient.setWebSocket(null)
                webRtcClient.resetConnection()
            },
            onMessageReceived = { message, _ ->
                webRtcClient.handleSignalingMessage(message)
            }
        )
        signalingServer.start()

        nsdAdvertiser = NsdAdvertiser(this)
        nsdAdvertiser.registerService(8888)

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
                        AndroidView(
                            factory = { context ->
                                SurfaceViewRenderer(context).apply {
                                    init(eglBase.eglBaseContext, null)
                                    setEnableHardwareScaler(true)
                                    setMirror(false)
                                    setScalingType(when(layoutMode) {
                                        "FILL" -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
                                        "FIT" -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                        else -> RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
                                    })
                                }
                            },
                            update = { view ->
                                view.setScalingType(when(layoutMode) {
                                    "FILL" -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
                                    "FIT" -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                    else -> RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
                                })
                                videoTrack?.addSink(view)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
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
        nsdAdvertiser.unregisterService()
        signalingServer.stop()
        webRtcClient.close()
        eglBase.release()
    }
}
