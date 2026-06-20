package com.example.streamer

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import org.webrtc.*
import java.io.File
import java.util.Timer
import java.util.TimerTask

class WebRtcClient(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val mediaProjectionPermissionResultData: Intent
) {
    private val gson = Gson()
    private val eglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var statsTimer: Timer? = null

    init {
        initWebRTC()
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    fun startStream(width: Int, height: Int, fps: Int, bitrateKbps: Int, layoutMode: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val message = SignalingMessage(
                    type = "candidate",
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
                sendMessage(message)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        videoCapturer = createScreenCapturer()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory?.createVideoSource(false) // false = prioritize framerate over text clarity
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(width, height, fps)

        val videoTrack = peerConnectionFactory?.createVideoTrack("video_track", videoSource)
        peerConnection?.addTrack(videoTrack)

        peerConnection?.senders?.forEach { sender ->
            if (sender.track()?.kind() == "video") {
                val parameters = sender.parameters
                parameters.degradationPreference = RtpParameters.DegradationPreference.DISABLED
                parameters.encodings.forEach { encoding ->
                    encoding.maxBitrateBps = bitrateKbps * 1000
                }
                sender.parameters = parameters
            }
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let {
                    var optimizedSdp = preferH264(it.description)
                    optimizedSdp = enforceBitrate(optimizedSdp, bitrateKbps)
                    val newSessionDescription = SessionDescription(it.type, optimizedSdp)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), newSessionDescription)
                    val offerMsg = SignalingMessage(type = "offer", sdp = optimizedSdp)
                    sendMessage(offerMsg)
                    
                    // Send layout command right after offer
                    val layoutMsg = SignalingMessage(type = "layout", candidate = layoutMode)
                    sendMessage(layoutMsg)
                }
            }
        }, MediaConstraints())
        
        startStatsLogger()
    }

    private fun startStatsLogger() {
        statsTimer = Timer()
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val timestamp = System.currentTimeMillis()
        val logFile = File(downloadsDir, "webrtc_diagnostics_$timestamp.log")
        try {
            logFile.writeText("--- WebRTC Diagnostics Started ---\n")
        } catch (e: Exception) {
            Log.e("WebRtcDiagnostics", "Failed to create log file in Downloads", e)
        }
        
        var lastBytesSent = 0L
        var lastTime = System.currentTimeMillis()

        statsTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                peerConnection?.getStats { report ->
                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastTime
                    lastTime = now

                    var currentBytesSent = lastBytesSent
                    var frameWidth = 0L
                    var frameHeight = 0L
                    var fps = 0.0

                    for (stats in report.statsMap.values) {
                        if (stats.type == "outbound-rtp" && stats.members["kind"] == "video") {
                            currentBytesSent = (stats.members["bytesSent"] as? java.math.BigInteger)?.toLong() ?: (stats.members["bytesSent"] as? Long) ?: currentBytesSent
                            frameWidth = (stats.members["frameWidth"] as? Long) ?: 0L
                            frameHeight = (stats.members["frameHeight"] as? Long) ?: 0L
                            fps = (stats.members["framesPerSecond"] as? Double) ?: 0.0
                        }
                    }

                    val deltaBytes = currentBytesSent - lastBytesSent
                    val currentKbps = if (deltaMs > 0) (deltaBytes * 8) / deltaMs else 0
                    lastBytesSent = currentBytesSent

                    val logLine = "Time: $now | Res: ${frameWidth}x${frameHeight} | FPS: $fps | Bitrate: $currentKbps kbps\n"
                    Log.d("WebRtcDiagnostics", logLine.trim())
                    try {
                        logFile.appendText(logLine)
                    } catch (e: Exception) {
                        Log.e("WebRtcDiagnostics", "Failed to write log", e)
                    }
                }
            }
        }, 2000, 2000)
    }

    private fun createScreenCapturer(): VideoCapturer? {
        return ScreenCapturerAndroid(mediaProjectionPermissionResultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e("WebRtcClient", "User revoked screen capture permission")
            }
        })
    }

    fun handleSignalingMessage(messageString: String) {
        val message = gson.fromJson(messageString, SignalingMessage::class.java)
        
        when (message.type) {
            "answer" -> {
                val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
            }
            "candidate" -> {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun sendMessage(message: SignalingMessage) {
        signalingClient.send(gson.toJson(message))
    }

    fun sendLayout(mode: String) {
        val layoutMsg = SignalingMessage(type = "layout", candidate = mode)
        sendMessage(layoutMsg)
    }

    fun stopStream() {
        statsTimer?.cancel()
        statsTimer = null
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error stopping capture", e)
        }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnection = null
    }

    fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        try {
            videoCapturer?.changeCaptureFormat(width, height, fps)
        } catch (e: Exception) {
            Log.e("WebRtcClient", "Error changing capture format", e)
        }
    }

    fun close() {
        stopStream()
        eglBase.release()
    }

    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var mLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("m=video")) {
                mLineIndex = i
                break
            }
        }
        if (mLineIndex == -1) return sdp

        val mLineParts = lines[mLineIndex].split(" ").toMutableList()
        val h264PayloadTypes = lines
            .filter { it.startsWith("a=rtpmap:") && it.contains("H264") }
            .map { it.substringAfter("a=rtpmap:").substringBefore(" ") }

        if (h264PayloadTypes.isEmpty()) return sdp

        val newMLineParts = mLineParts.take(3).toMutableList()
        newMLineParts.addAll(h264PayloadTypes)
        mLineParts.drop(3).forEach { payload ->
            if (!h264PayloadTypes.contains(payload)) {
                newMLineParts.add(payload)
            }
        }
        lines[mLineIndex] = newMLineParts.joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun enforceBitrate(sdp: String, bitrateKbps: Int): String {
        val lines = sdp.split("\r\n").toMutableList()
        var mLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("m=video")) {
                mLineIndex = i
                break
            }
        }
        if (mLineIndex == -1) return sdp

        // Inject b=AS limit immediately after m=video
        lines.add(mLineIndex + 1, "b=AS:$bitrateKbps")

        // Inject x-google-start-bitrate to force aggressive ramp-up
        for (i in lines.indices) {
            if (lines[i].startsWith("a=fmtp:")) {
                lines[i] = "${lines[i]};x-google-min-bitrate=1000;x-google-start-bitrate=$bitrateKbps;x-google-max-bitrate=$bitrateKbps"
            }
        }

        return lines.joinToString("\r\n")
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }
}
