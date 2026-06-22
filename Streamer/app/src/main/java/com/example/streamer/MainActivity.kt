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
    private var selectedLayoutMode by mutableStateOf("HYBRID") // FILL = Crop to Fit, FIT = Letterbox, HYBRID
    private var selectedCodec by mutableStateOf("H264")
    private var selectedResolution by mutableStateOf("1080p") 
    private var selectedFps by mutableStateOf(60)
    private var selectedBitrateRange by mutableStateOf(2000f..15000f) // kbps
    private var enableLogging by mutableStateOf(false)

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

        val prefs = getSharedPreferences("streamer_prefs", Context.MODE_PRIVATE)
        selectedCodec = prefs.getString("selectedCodec", "H264") ?: "H264"
        selectedResolution = prefs.getString("selectedResolution", "1080p") ?: "1080p"

        enableEdgeToEdge()
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var tvToRename by remember { mutableStateOf<ReceiverDevice?>(null) }
            var newTvName by remember { mutableStateOf("") }

            StreamerTheme {
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text("Screen Mirroring") },
                            actions = {
                                IconButton(onClick = { showSettings = true }) {
                                    Text("⚙️", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
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
                                    val customName = prefs.getString("tv_name_${tv.ip}", tv.name) ?: tv.name
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { selectedTv = tv },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (selectedTv == tv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                            ),
                                            modifier = Modifier.weight(1f).padding(4.dp)
                                        ) {
                                            Text("$customName (${tv.ip})")
                                        }
                                        IconButton(onClick = {
                                            newTvName = customName
                                            tvToRename = tv
                                        }) {
                                            Text("✏️", style = MaterialTheme.typography.titleLarge)
                                        }
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
                                // FPS
                                Text("Framerate: $selectedFps FPS")
                                Row {
                                    Button(onClick = { selectedFps = 30 }, modifier = Modifier.padding(4.dp)) { Text("30 FPS") }
                                    Button(onClick = { selectedFps = 60 }, modifier = Modifier.padding(4.dp)) { Text("60 FPS") }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Bitrate
                                Text("Target Bitrate Range: ${selectedBitrateRange.start.toInt()} - ${selectedBitrateRange.endInclusive.toInt()} kbps")
                                RangeSlider(
                                    value = selectedBitrateRange,
                                    onValueChange = { selectedBitrateRange = it },
                                    valueRange = 1000f..40000f,
                                    steps = 38,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = enableLogging,
                                        onCheckedChange = { enableLogging = it }
                                    )
                                    Text("Enable Debug Logging")
                                }
                                
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
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Text("Live Bitrate Range: ${selectedBitrateRange.start.toInt()} - ${selectedBitrateRange.endInclusive.toInt()} kbps")
                                RangeSlider(
                                    value = selectedBitrateRange,
                                    onValueChange = { 
                                        selectedBitrateRange = it
                                        sendDynamicBitrate(it.start.toInt(), it.endInclusive.toInt())
                                    },
                                    valueRange = 1000f..40000f,
                                    steps = 38,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                
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

                    if (showSettings) {
                        AlertDialog(
                            onDismissRequest = { showSettings = false },
                            title = { Text("Stream Settings") },
                            text = {
                                Column {
                                    Text("Resolution Quality")
                                    Row {
                                        Button(onClick = { selectedResolution = "720p"; prefs.edit().putString("selectedResolution", "720p").apply() }, modifier = Modifier.padding(4.dp)) { Text("720p") }
                                        Button(onClick = { selectedResolution = "1080p"; prefs.edit().putString("selectedResolution", "1080p").apply() }, modifier = Modifier.padding(4.dp)) { Text("1080p") }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Video Codec")
                                    Row {
                                        Button(onClick = { selectedCodec = "H264"; prefs.edit().putString("selectedCodec", "H264").apply() }, modifier = Modifier.padding(2.dp)) { Text("H264") }
                                        Button(onClick = { selectedCodec = "VP8"; prefs.edit().putString("selectedCodec", "VP8").apply() }, modifier = Modifier.padding(2.dp)) { Text("VP8") }
                                        Button(onClick = { selectedCodec = "VP9"; prefs.edit().putString("selectedCodec", "VP9").apply() }, modifier = Modifier.padding(2.dp)) { Text("VP9") }
                                        Button(onClick = { selectedCodec = "AV1"; prefs.edit().putString("selectedCodec", "AV1").apply() }, modifier = Modifier.padding(2.dp)) { Text("AV1") }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSettings = false }) { Text("Close") }
                            }
                        )
                    }

                    if (tvToRename != null) {
                        AlertDialog(
                            onDismissRequest = { tvToRename = null },
                            title = { Text("Rename Receiver") },
                            text = {
                                TextField(
                                    value = newTvName,
                                    onValueChange = { newTvName = it },
                                    label = { Text("Custom Name") }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    prefs.edit().putString("tv_name_${tvToRename!!.ip}", newTvName).apply()
                                    tvToRename = null
                                }) { Text("Save") }
                            },
                            dismissButton = {
                                TextButton(onClick = { tvToRename = null }) { Text("Cancel") }
                            }
                        )
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
            putExtra("CODEC", selectedCodec)
            putExtra("MIN_BITRATE", selectedBitrateRange.start.toInt())
            putExtra("MAX_BITRATE", selectedBitrateRange.endInclusive.toInt())
            putExtra("ENABLE_LOGGING", enableLogging)
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

    private fun sendDynamicBitrate(minKbps: Int, maxKbps: Int) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "CHANGE_BITRATE"
            putExtra("MIN_BITRATE", minKbps)
            putExtra("MAX_BITRATE", maxKbps)
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdDiscovery.stopDiscovery()
    }
}
