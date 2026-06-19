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

    private var targetIp by mutableStateOf<String?>(null)
    private var targetPort by mutableStateOf(8888)
    private var isStreaming by mutableStateOf(false)

    // Settings
    private var selectedResolution by mutableStateOf("1280x720")
    private var selectedFps by mutableStateOf(30)
    private var selectedBitrate by mutableStateOf(5000f) // kbps

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
        
        nsdDiscovery = NsdDiscovery(this) { ip, port ->
            targetIp = ip
            targetPort = port
        }
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
                        if (targetIp != null) {
                            Text("Found Receiver at $targetIp:$targetPort", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            if (!isStreaming) {
                                // Resolution
                                Text("Resolution: $selectedResolution")
                                Row {
                                    Button(onClick = { selectedResolution = "1280x720" }, modifier = Modifier.padding(4.dp)) { Text("720p") }
                                    Button(onClick = { selectedResolution = "1920x1080" }, modifier = Modifier.padding(4.dp)) { Text("1080p") }
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
                                    valueRange = 1000f..15000f,
                                    steps = 14,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(32.dp))
                                
                                Button(onClick = { startScreenCapture() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text("Start Streaming")
                                }
                            } else {
                                Button(onClick = { stopScreenCapture() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                    Text("Stop Streaming")
                                }
                            }
                        } else {
                            Text("Looking for Receiver on local network...")
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
        val width = selectedResolution.split("x")[0].toInt()
        val height = selectedResolution.split("x")[1].toInt()
        
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("IP", targetIp)
            putExtra("PORT", targetPort)
            putExtra("DATA", data)
            putExtra("WIDTH", width)
            putExtra("HEIGHT", height)
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

    override fun onDestroy() {
        super.onDestroy()
        nsdDiscovery.stopDiscovery()
    }
}
