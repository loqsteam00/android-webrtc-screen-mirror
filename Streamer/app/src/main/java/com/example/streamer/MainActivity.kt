package com.example.streamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.streamer.theme.StreamerTheme

class MainActivity : ComponentActivity() {
    private lateinit var nsdDiscovery: NsdDiscovery
    private lateinit var mediaProjectionManager: MediaProjectionManager

    data class ReceiverDevice(val name: String, val ip: String, val port: Int)
    
    private var discoveredTvs = mutableStateListOf<ReceiverDevice>()
    private var selectedTv by mutableStateOf<ReceiverDevice?>(null)
    private var isStreaming by mutableStateOf(false)

    // Settings
    private var selectedLayoutMode by mutableStateOf("FILL") // FILL = Crop to Fit, FIT = Letterbox
    private var selectedResolution by mutableStateOf("1080p") 
    private var selectedFps by mutableStateOf(60)
    private var selectedBitrate by mutableStateOf(15000f) // kbps

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.data!!)
            isStreaming = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        nsdDiscovery = NsdDiscovery(this, onServiceFound = { name, ip, port ->
            val device = ReceiverDevice(name, ip, port)
            if (discoveredTvs.none { it.ip == ip }) {
                discoveredTvs.add(device)
            }
        }, onServiceLost = { name ->
            discoveredTvs.removeAll { it.name == name }
            if (selectedTv?.name == name) selectedTv = null
        })
        nsdDiscovery.startDiscovery()

        enableEdgeToEdge()
        setContent {
            StreamerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (discoveredTvs.isNotEmpty()) {
                            if (!isStreaming) {
                                Text("Select Receiver TV:", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                discoveredTvs.forEach { tv ->
                                    Button(
                                        onClick = { selectedTv = tv },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selectedTv == tv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                        ),
                                        modifier = Modifier.padding(4.dp).fillMaxWidth()
                                    ) {
                                        Text("${tv.name} (${tv.ip})")
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                
                                // Layout Mode
                                Text("TV Display Mode: ${if (selectedLayoutMode == "FILL") "Crop to Fit" else if (selectedLayoutMode == "FIT") "Full Screen (Letterbox)" else "Hybrid"}")
                                Row {
                                    Button(onClick = { selectedLayoutMode = "FILL" }, modifier = Modifier.padding(4.dp)) { Text("Crop to Fit TV") }
                                    Button(onClick = { selectedLayoutMode = "HYBRID" }, modifier = Modifier.padding(4.dp)) { Text("Hybrid") }
                                    Button(onClick = { selectedLayoutMode = "FIT" }, modifier = Modifier.padding(4.dp)) { Text("Letterbox") }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                // Resolution Quality
                                Text("Quality Target: $selectedResolution")
                                Row {
                                    Button(onClick = { selectedResolution = "720p" }, modifier = Modifier.padding(4.dp)) { Text("720p") }
                                    Button(onClick = { selectedResolution = "1080p" }, modifier = Modifier.padding(4.dp)) { Text("1080p") }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // FPS
                                Text("Framerate: $selectedFps FPS")
                                Row {
                                    Button(onClick = { selectedFps = 30 }, modifier = Modifier.padding(4.dp)) { Text("30 FPS") }
                                    Button(onClick = { selectedFps = 60 }, modifier = Modifier.padding(4.dp)) { Text("60 FPS") }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Bitrate
                                Text("Video Bitrate: ${selectedBitrate.toInt()} kbps")
                                Slider(
                                    value = selectedBitrate,
                                    onValueChange = { selectedBitrate = it },
                                    valueRange = 1000f..40000f,
                                    steps = 39,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(32.dp))
                                
                                Button(
                                    onClick = { startScreenCapture() }, 
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    enabled = selectedTv != null
                                ) {
                                    Text("Start Streaming")
                                }
                            } else {
                                Text("Streaming to ${selectedTv?.name}", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Text("Live Layout Switcher (Instant):")
                                Row {
                                    Button(onClick = { sendDynamicLayout("FILL") }, modifier = Modifier.padding(4.dp)) { Text("Crop to Fit") }
                                    Button(onClick = { sendDynamicLayout("HYBRID") }, modifier = Modifier.padding(4.dp)) { Text("Hybrid") }
                                    Button(onClick = { sendDynamicLayout("FIT") }, modifier = Modifier.padding(4.dp)) { Text("Letterbox") }
                                }
                                
                                Spacer(modifier = Modifier.height(32.dp))
                                Button(onClick = { stopScreenCapture() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                    Text("Stop Streaming")
                                }
                            }
                        } else {
                            Text("Looking for Receivers on local network...")
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    private fun startScreenCapture() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(data: Intent) {
        val maxRes = if (selectedResolution == "1080p") 1920 else 1280

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("IP", selectedTv?.ip)
            putExtra("PORT", selectedTv?.port)
            putExtra("DATA", data)
            putExtra("LAYOUT_MODE", selectedLayoutMode)
            putExtra("MAX_RES", maxRes)
            putExtra("FPS", selectedFps)
            putExtra("BITRATE", selectedBitrate.toInt())
        }
        startService(serviceIntent)
    }

    private fun stopScreenCapture() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)
        isStreaming = false
    }

    private fun sendDynamicLayout(mode: String) {
        selectedLayoutMode = mode
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "CHANGE_LAYOUT"
            putExtra("LAYOUT_MODE", mode)
        }
        startService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdDiscovery.stopDiscovery()
    }
}
