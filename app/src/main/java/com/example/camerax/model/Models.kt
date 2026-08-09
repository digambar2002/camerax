package com.example.camerax.model

/**
 * Represents a supported video resolution for streaming.
 *
 * @param width  Frame width in pixels.
 * @param height Frame height in pixels.
 * @param label  Human-readable label shown in the UI.
 * @param bitrateBps Recommended H.264 encoding bitrate for this resolution.
 */
data class Resolution(
    val width: Int,
    val height: Int,
    val label: String,
    val bitrateBps: Int
) {
    companion object {
        val RES_480P  = Resolution(640,  480,  "480p",  2_000_000)
        val RES_720P  = Resolution(1280, 720,  "720p",  4_000_000)
        val RES_1080P = Resolution(1920, 1080, "1080p", 8_000_000)

        /** All resolutions in ascending order. */
        val ALL = listOf(RES_480P, RES_720P, RES_1080P)
    }
}

/**
 * Which camera lens is currently active.
 */
enum class CameraFacing {
    FRONT,
    REAR;

    fun opposite(): CameraFacing = if (this == FRONT) REAR else FRONT
}

/**
 * High-level streaming state machine.
 */
enum class StreamingState {
    /** App is idle; server is not running. */
    IDLE,

    /** Server socket is open, encoder is running, waiting for / serving a client. */
    STREAMING,

    /** An unrecoverable error stopped streaming. */
    ERROR
}

/**
 * Snapshot of the entire application state, exposed as a [kotlinx.coroutines.flow.StateFlow]
 * from [com.example.camerax.viewmodel.CameraViewModel].
 *
 * @param streamingState   Whether the app is idle, streaming, or in error.
 * @param isClientConnected Whether a PC client is currently receiving the stream.
 * @param selectedResolution The active encoding/preview resolution.
 * @param selectedFps      Target frames per second (30 or 60).
 * @param cameraFacing     Which camera lens is active.
 * @param errorMessage     Non-null when [streamingState] == [StreamingState.ERROR].
 * @param permissionsGranted Whether camera (and audio) permissions have been granted.
 */
data class AppState(
    val streamingState: StreamingState = StreamingState.IDLE,
    val isClientConnected: Boolean = false,
    val selectedResolution: Resolution = Resolution.RES_720P,
    val selectedFps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.REAR,
    val errorMessage: String? = null,
    val permissionsGranted: Boolean = false
)

/**
 * Persisted user preferences loaded from DataStore.
 */
data class AppSettings(
    val resolutionIndex: Int = 1,       // index into Resolution.ALL
    val fps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.REAR
)
