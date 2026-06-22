package com.example.receiver

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
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
    private var autoLaunchEnabled by mutableStateOf(false)
    private var pipEnabled by mutableStateOf(false)
    private var wakeEnabled by mutableStateOf(false)

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        autoLaunchEnabled = Settings.canDrawOverlays(this)
        getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE).edit().putBoolean("autoLaunch", autoLaunchEnabled).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        eglBase = EglBase.create()
        
        webRtcClient = WebRtcClient(this, eglBase, onVideoTrack = { track ->
            videoTrack = track
        }, onLayout = { mode ->
            layoutMode = mode
        })

        val prefs = getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE)
        autoLaunchEnabled = prefs.getBoolean("autoLaunch", false)
        pipEnabled = prefs.getBoolean("pipEnabled", false)
        wakeEnabled = prefs.getBoolean("wakeEnabled", false)
        if (autoLaunchEnabled && !Settings.canDrawOverlays(this)) {
            autoLaunchEnabled = false
            prefs.edit().putBoolean("autoLaunch", false).apply()
        }

        if (wakeEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

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
                    server.activeSocket?.let { socket ->
                        webRtcClient.setWebSocket(socket)
                    }
                    server.onActivityClientConnected = {
                        runOnUiThread {
                            server.activeSocket?.let { socket ->
                                webRtcClient.setWebSocket(socket)
                            }
                        }
                    }
                    server.onMessageReceived = { message, _ ->
                        runOnUiThread { webRtcClient.handleSignalingMessage(message) }
                    }
                    server.onClientDisconnected = {
                        runOnUiThread {
                            videoTrack = null
                            webRtcClient.setWebSocket(null)
                            webRtcClient.resetConnection()
                            finish()
                        }
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
                                
                                Spacer(modifier = Modifier.height(32.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = autoLaunchEnabled,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                                    overlayPermissionLauncher.launch(intent)
                                                } else {
                                                    autoLaunchEnabled = true
                                                    getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE).edit().putBoolean("autoLaunch", true).apply()
                                                }
                                            } else {
                                                autoLaunchEnabled = false
                                                getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE).edit().putBoolean("autoLaunch", false).apply()
                                            }
                                        }
                                    )
                                    Text("Allow Background Auto-Launch")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = pipEnabled,
                                        onCheckedChange = { checked ->
                                            pipEnabled = checked
                                            getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE).edit().putBoolean("pipEnabled", checked).apply()
                                        }
                                    )
                                    Text("Auto Picture-in-Picture on Leave")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = wakeEnabled,
                                        onCheckedChange = { checked ->
                                            wakeEnabled = checked
                                            getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE).edit().putBoolean("wakeEnabled", checked).apply()
                                            if (checked) {
                                                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                                            } else {
                                                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                                            }
                                        }
                                    )
                                    Text("Wake TV From Sleep on Stream")
                                }
                            }
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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
                                    view.requestLayout()
                                },
                                // Do NOT use fillMaxSize() here. We want SurfaceViewRenderer to measure itself based on the scaling type!
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEnabled && videoTrack != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val params = android.app.PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerManager.server?.onMessageReceived = null
        ServerManager.server?.onClientDisconnected = null
        webRtcClient.close()
        eglBase.release()
    }
}
