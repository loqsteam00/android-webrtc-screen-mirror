package com.example.streamer

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class SignalingClient(
    serverUri: URI,
    private val onConnected: () -> Unit,
    private val onMessageReceived: (String) -> Unit,
    private val onDisconnected: () -> Unit
) : WebSocketClient(serverUri) {

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d("SignalingClient", "Connected to signaling server")
        onConnected()
    }

    override fun onMessage(message: String) {
        Log.d("SignalingClient", "Message from server: $message")
        onMessageReceived(message)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.d("SignalingClient", "Disconnected from signaling server: $reason")
        onDisconnected()
    }

    override fun onError(ex: Exception?) {
        Log.e("SignalingClient", "Error occurred", ex)
    }
}
