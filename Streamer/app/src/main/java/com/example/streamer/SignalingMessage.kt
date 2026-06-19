package com.example.streamer

data class SignalingMessage(
    val type: String, // "offer", "answer", "candidate"
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)
