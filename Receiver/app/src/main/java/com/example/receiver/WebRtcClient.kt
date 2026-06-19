package com.example.receiver

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.java_websocket.WebSocket
import org.webrtc.*

class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val onVideoTrack: (VideoTrack) -> Unit
) {
    private val gson = Gson()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null

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

    fun setWebSocket(socket: WebSocket?) {
        this.webSocket = socket
    }

    private fun createPeerConnection() {
        if (peerConnection != null) return

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

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                Log.d("WebRtcClient", "onAddTrack called")
                val track = receiver?.track()
                if (track is VideoTrack) {
                    onVideoTrack(track)
                }
            }
        })
    }

    fun handleSignalingMessage(messageString: String) {
        val message = gson.fromJson(messageString, SignalingMessage::class.java)
        
        when (message.type) {
            "offer" -> {
                createPeerConnection()
                val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, message.sdp)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
                
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                        sessionDescription?.let {
                            var optimizedSdp = preferH264(it.description)
                            // Assuming we want a default high limit for the receiver to accept
                            optimizedSdp = enforceBitrate(optimizedSdp, 40000) 
                            val newSessionDescription = SessionDescription(it.type, optimizedSdp)
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), newSessionDescription)
                            val answerMsg = SignalingMessage(type = "answer", sdp = optimizedSdp)
                            sendMessage(answerMsg)
                        }
                    }
                }, MediaConstraints())
            }
            "candidate" -> {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun sendMessage(message: SignalingMessage) {
        webSocket?.send(gson.toJson(message))
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
    }

    fun resetConnection() {
        Log.d("WebRtcClient", "Resetting PeerConnection")
        peerConnection?.close()
        peerConnection = null
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
