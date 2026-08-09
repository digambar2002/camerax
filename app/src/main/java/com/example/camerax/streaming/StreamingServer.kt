package com.example.camerax.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StreamingServer"
private const val PORT = 5000

/**
 * Lightweight raw TCP server that streams H.264 NAL units to a connected PC client.
 *
 * Design decisions:
 * - Runs on a dedicated Java [Thread] (not a coroutine dispatcher) for the tightest
 *   possible control over blocking I/O and thread priorities.
 * - Accepts one client at a time; a new connection immediately replaces the old one.
 * - The caller pushes data via [sendData]; this method is thread-safe.
 * - The server stays alive (listening for reconnects) until [stop] is called.
 *
 * PC setup:
 *   adb forward tcp:5000 tcp:5000
 *   vlc tcp/h264://127.0.0.1:5000
 *   # or: ffplay -f h264 tcp://127.0.0.1:5000
 *
 * Future extensibility: add a second channel for audio (e.g., port 5001).
 */
class StreamingServer {

    // -------------------------------------------------------------------------
    // Callbacks (set before calling [start])
    // -------------------------------------------------------------------------

    /** Invoked on the server thread when a new PC client connects. */
    var onClientConnected: (() -> Unit)? = null

    /** Invoked on the server thread when the current client disconnects. */
    var onClientDisconnected: (() -> Unit)? = null

    /** Invoked when the server itself encounters a fatal error. */
    var onServerError: ((message: String) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Private state
    // -------------------------------------------------------------------------

    private val isRunning = AtomicBoolean(false)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null

    private var acceptThread: Thread? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts the TCP server on port [PORT].
     * The server loop runs on a dedicated background thread.
     *
     * @throws IllegalStateException if the server is already running.
     */
    fun start() {
        check(!isRunning.get()) { "StreamingServer is already running" }
        isRunning.set(true)

        acceptThread = Thread({
            runServerLoop()
        }, "StreamingServer-Accept").also { thread ->
            thread.priority = Thread.MAX_PRIORITY
            thread.start()
        }

        Log.i(TAG, "Server started on port $PORT")
    }

    /**
     * Stops the server, disconnects any active client, and releases the port.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping server…")
        closeClientSocket()
        try { serverSocket?.close() } catch (e: Exception) { /* ignored */ }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "Server stopped")
    }

    /**
     * Sends raw H.264 bytes to the currently connected client.
     *
     * Thread-safe: may be called from the encoder callback thread.
     * If no client is connected, the data is silently dropped.
     *
     * @param data Byte array containing one or more complete NAL units.
     */
    fun sendData(data: ByteArray) {
        val stream = outputStream ?: return
        try {
            stream.write(data)
            // Flush is NOT called on every frame to reduce syscall overhead.
            // The OS will flush automatically when the buffer fills, or when
            // the kernel decides to send the segment. For latency-critical
            // scenarios consider calling flush() every N frames or on IDR frames.
        } catch (e: SocketException) {
            Log.w(TAG, "Client write failed (disconnected?): ${e.message}")
            handleClientDisconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error writing to client: ${e.message}")
            handleClientDisconnect()
        }
    }

    /**
     * Flushes the output stream to the client.
     * Call this after sending an IDR frame for lowest latency.
     */
    fun flush() {
        try {
            outputStream?.flush()
        } catch (e: Exception) {
            // Client may have disconnected; handleClientDisconnect will be called on next write.
        }
    }

    /** Returns `true` if a client is currently connected. */
    val isClientConnected: Boolean
        get() = outputStream != null

    // -------------------------------------------------------------------------
    // Internal server loop
    // -------------------------------------------------------------------------

    /**
     * Creates the [ServerSocket], then loops — accepting a new client each time
     * the previous one disconnects — until [stop] is called.
     */
    private fun runServerLoop() {
        val server = try {
            ServerSocket(PORT).also { serverSocket = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind port $PORT: ${e.message}")
            onServerError?.invoke("Failed to bind port $PORT: ${e.message}")
            isRunning.set(false)
            return
        }

        Log.i(TAG, "Listening on port $PORT — run: adb forward tcp:$PORT tcp:$PORT on your PC")

        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for client connection…")
                val client = server.accept()  // blocks until a client connects
                handleNewClient(client)
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.w(TAG, "ServerSocket closed unexpectedly: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in accept loop: ${e.message}")
                }
                break
            }
        }

        Log.d(TAG, "Accept loop exited")
    }

    /**
     * Sets up the new client connection, replacing any previous one.
     */
    private fun handleNewClient(client: Socket) {
        // Evict existing client
        closeClientSocket()

        // Configure the socket for low-latency streaming
        client.tcpNoDelay = true          // disable Nagle's algorithm
        client.setSoTimeout(0)            // no read timeout — we are write-only
        client.setPerformancePreferences(0, 1, 0) // prioritise latency

        clientSocket = client
        outputStream = client.getOutputStream()

        Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}:${client.port}")
        onClientConnected?.invoke()
    }

    /**
     * Closes and nulls the current client socket and output stream.
     */
    private fun closeClientSocket() {
        outputStream = null
        try { clientSocket?.close() } catch (e: Exception) { /* ignored */ }
        clientSocket = null
    }

    /**
     * Called when a write to the client fails, indicating a disconnect.
     */
    private fun handleClientDisconnect() {
        if (clientSocket != null) {
            Log.i(TAG, "Client disconnected")
            closeClientSocket()
            onClientDisconnected?.invoke()
        }
    }
}
