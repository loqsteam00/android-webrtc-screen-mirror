package com.example.receiver

import android.util.Log
import com.google.gson.Gson
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class LocalSignalingServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    var onClientConnected: ((WebSocket) -> Unit)? = null
    var onClientDisconnected: ((WebSocket) -> Unit)? = null
    private var _onMessageReceived: ((String, WebSocket) -> Unit)? = null
    var onMessageReceived: ((String, WebSocket) -> Unit)?
        get() = _onMessageReceived
        set(value) {
            _onMessageReceived = value
            if (value != null) {
                // Flush queue
                while (messageQueue.isNotEmpty()) {
                    val msg = messageQueue.removeFirst()
                    msg.socket?.let { value(msg.content, it) }
                }
            }
        }
        
    var activeSocket: WebSocket? = null
    private val messageQueue = mutableListOf<QueuedMessage>()
    
    private data class QueuedMessage(val content: String, val socket: WebSocket?)

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("SignalingServer", "New client connected: ${conn.remoteSocketAddress}")
        activeSocket = conn
        onClientConnected?.invoke(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d("SignalingServer", "Client disconnected: ${conn.remoteSocketAddress}")
        if (activeSocket == conn) activeSocket = null
        onClientDisconnected?.invoke(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d("SignalingServer", "Message from client: $message")
        if (_onMessageReceived != null) {
            _onMessageReceived?.invoke(message, conn)
        } else {
            Log.d("SignalingServer", "No listener attached, queuing message.")
            messageQueue.add(QueuedMessage(message, conn))
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("SignalingServer", "Error occurred", ex)
    }

    override fun onStart() {
        Log.d("SignalingServer", "Server started successfully on port $port")
    }
}
