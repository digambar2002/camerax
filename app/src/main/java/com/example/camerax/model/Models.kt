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
 * Operating mode: PC Webcam vs Shorts Creator vs Dual Camera / Vlog
 */
enum class AppMode {
    WEBCAM,
    SHORTS_CREATOR,
    DUAL_CAMERA
}

/**
 * Dual Camera layout: Split Screen (50/50) vs Picture-in-Picture (PiP)
 */
enum class DualCamLayout {
    SPLIT_SCREEN,
    PIP
}

/**
 * PiP floating camera window shape
 */
enum class PipShape {
    CIRCLE,
    ROUNDED_SQUARE
}

/**
 * Snapshot of the entire application state, exposed as a [kotlinx.coroutines.flow.StateFlow]
 * from [com.example.camerax.viewmodel.CameraViewModel].
 *
 * @param appMode          Current active mode (Webcam vs Shorts vs Dual Camera).
 * @param isRecordingShorts Whether screen + camera bubble recording is currently active.
 * @param lastRecordedFilePath Path to the latest saved Shorts MP4 video file.
 * @param streamingState   Whether the app is idle, streaming, or in error.
 * @param isClientConnected Whether a PC client is currently receiving the stream.
 * @param selectedResolution The active encoding/preview resolution.
 * @param selectedFps      Target frames per second (30 or 60).
 * @param cameraFacing     Which camera lens is active.
 * @param errorMessage     Non-null when [streamingState] == [StreamingState.ERROR].
 * @param permissionsGranted Whether camera (and audio) permissions have been granted.
 * @param overlayPermissionGranted Whether SYSTEM_ALERT_WINDOW permission is granted.
 * @param shortsCircleWidthDp Circle size in dp for Shorts Mode.
 * @param shortsZoomLevel  Zoom ratio for Shorts Mode.
 * @param dualCamLayout    Layout mode for Dual Camera (Split Screen vs PiP).
 * @param pipShape         Shape of the floating window in PiP mode (Circle vs Rounded Square).
 * @param pipSizeDp        Size of the floating window in PiP mode.
 * @param isPrimaryRear    Whether the primary/top view is the Rear camera (true) or Front camera (false).
 * @param isRecordingDualCam Whether dual camera recording is currently active.
 * @param lastRecordedDualCamPath Path to the latest saved Dual Camera MP4 video file.
 * @param supportsConcurrentHardware Whether the device ISP supports concurrent hardware streaming.
 */
data class AppState(
    val appMode: AppMode = AppMode.WEBCAM,
    val isRecordingShorts: Boolean = false,
    val lastRecordedFilePath: String? = null,
    val streamingState: StreamingState = StreamingState.IDLE,
    val isClientConnected: Boolean = false,
    val selectedResolution: Resolution = Resolution.RES_720P,
    val selectedFps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.REAR,
    val errorMessage: String? = null,
    val permissionsGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val shortsCircleWidthDp: Int = 150,
    val shortsZoomLevel: Float = 1.0f,
    val dualCamLayout: DualCamLayout = DualCamLayout.SPLIT_SCREEN,
    val pipShape: PipShape = PipShape.CIRCLE,
    val pipSizeDp: Int = 140,
    val isPrimaryRear: Boolean = true,
    val isRecordingDualCam: Boolean = false,
    val lastRecordedDualCamPath: String? = null,
    val supportsConcurrentHardware: Boolean = false,
    val isProcessingVideo: Boolean = false,
    val videoProcessingProgress: Int = 0
)

/**
 * Persisted user preferences loaded from DataStore.
 */
data class AppSettings(
    val resolutionIndex: Int = 1,       // index into Resolution.ALL
    val fps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.REAR
)
