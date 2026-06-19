package com.example.receiver

import android.util.Log
import com.google.gson.Gson
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class LocalSignalingServer(
    port: Int,
    private val onMessageReceived: (String, WebSocket) -> Unit,
    private val onClientConnected: (WebSocket) -> Unit,
    private val onClientDisconnected: (WebSocket) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("SignalingServer", "New client connected: ${conn.remoteSocketAddress}")
        onClientConnected(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d("SignalingServer", "Client disconnected: ${conn.remoteSocketAddress}")
        onClientDisconnected(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d("SignalingServer", "Message from client: $message")
        onMessageReceived(message, conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("SignalingServer", "Error occurred", ex)
    }

    override fun onStart() {
        Log.d("SignalingServer", "Server started successfully on port $port")
    }
}
