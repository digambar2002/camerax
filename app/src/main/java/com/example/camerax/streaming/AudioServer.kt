package com.example.camerax.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioServer"
private const val AUDIO_PORT = 5001

/**
 * Lightweight TCP server listening on port 5001 for low-latency AAC audio streaming.
 */
class AudioServer {

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onServerError: ((message: String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<Socket, OutputStream>()
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    fun start() {
        if (isRunning.get()) return

        try {
            val ss = ServerSocket(AUDIO_PORT).apply { reuseAddress = true }
            serverSocket = ss
            isRunning.set(true)

            acceptThread = Thread({ acceptLoop(ss) }, "audio-server-accept").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
            Log.i(TAG, "AudioServer listening on port $AUDIO_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioServer: ${e.message}", e)
            onServerError?.invoke("AudioServer start failed: ${e.message}")
        }
    }

    fun sendAudioData(data: ByteArray) {
        if (!isRunning.get() || clients.isEmpty()) return
        clients.forEach { (socket, outputStream) ->
            try {
                outputStream.write(data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write audio to client, closing: ${e.message}")
                disconnectClient(socket)
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        clients.keys.forEach { disconnectClient(it) }
        clients.clear()

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "AudioServer stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning.get()) {
            try {
                val socket = ss.accept().apply {
                    tcpNoDelay = true // Disable Nagle's algorithm for instant packet delivery
                    sendBufferSize = 64 * 1024
                }
                Log.i(TAG, "Audio Client connected: ${socket.remoteSocketAddress}")
                clients[socket] = socket.getOutputStream()
                onClientConnected?.invoke()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "AudioServer accept error: ${e.message}")
                }
            }
        }
    }

    private fun disconnectClient(socket: Socket) {
        clients.remove(socket)?.let { os ->
            try { os.close() } catch (_: Exception) {}
        }
        try { socket.close() } catch (_: Exception) {}
        onClientDisconnected?.invoke()
    }
}
