package com.example.camerax.viewmodel

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.camerax.camera.CameraManager
import com.example.camerax.encoder.AudioEncoder
import com.example.camerax.encoder.MediaCodecEncoder
import com.example.camerax.model.AppState
import com.example.camerax.model.CameraFacing
import com.example.camerax.model.Resolution
import com.example.camerax.model.StreamingState
import com.example.camerax.repository.SettingsRepository
import com.example.camerax.streaming.AudioServer
import com.example.camerax.streaming.StreamingServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.graphics.Bitmap
import android.os.Environment
import com.example.camerax.camera.DualCameraManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.camerax.model.DualCamLayout
import com.example.camerax.model.PipShape

private const val TAG = "CameraViewModel"

/**
 * MVVM ViewModel managing dual-stream zero-latency streaming:
 * - Video: Camera2 → Encoder Surface + Local Preview Surface → H.264 Encoder → TCP Server (5000) → ADB → PC
 * - Audio: Mic PCM → AudioRecord → AAC Encoder → TCP AudioServer (5001) → ADB → PC
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo       = SettingsRepository(application)
    private val cameraManager      = CameraManager(application)
    val dualCameraManager          = DualCameraManager(application)
    private val encoder            = MediaCodecEncoder()
    private val server             = StreamingServer()

    private val audioEncoder  = AudioEncoder()
    private val audioServer   = AudioServer()

    private val _uiState = MutableStateFlow(AppState(supportsConcurrentHardware = dualCameraManager.supportsConcurrentCameras()))
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                _uiState.update { current ->
                    current.copy(
                        selectedResolution = Resolution.ALL[settings.resolutionIndex],
                        selectedFps        = settings.fps,
                        cameraFacing       = settings.cameraFacing
                    )
                }
            }
        }
        setupCallbacks()
    }

    fun startStreaming(lifecycleOwner: LifecycleOwner, previewSurface: Surface?) {
        val state = _uiState.value
        if (state.streamingState == StreamingState.STREAMING) return

        Log.i(TAG, "Starting zero-copy video & audio stream")

        // 1. Initialize Surface mode Video Encoder
        encoder.initialize(state.selectedResolution, state.selectedFps)
        val surface = encoder.inputSurface
        if (surface == null) {
            setError("Encoder input surface is null")
            return
        }

        // 2. Initialize AAC Audio Encoder
        audioEncoder.initialize()

        // 3. Start TCP servers (5000 Video, 5001 Audio)
        try {
            server.start()
            audioServer.start()
        } catch (e: Exception) {
            setError("Failed to start streaming servers: ${e.message}")
            encoder.release()
            audioEncoder.release()
            return
        }

        // 4. Start Camera2 hardware stream (with local preview surface + encoder surface)
        cameraManager.startCamera(
            previewSurface  = previewSurface,
            encoderSurface  = surface,
            resolution      = state.selectedResolution,
            fps             = state.selectedFps,
            facing          = state.cameraFacing
        )

        _uiState.update { it.copy(streamingState = StreamingState.STREAMING, errorMessage = null) }
    }

    fun stopStreaming() {
        Log.i(TAG, "Stopping streams")
        cameraManager.releaseCamera()
        encoder.release()
        audioEncoder.release()
        server.stop()
        audioServer.stop()
        _uiState.update {
            it.copy(
                streamingState   = StreamingState.IDLE,
                isClientConnected = false,
                errorMessage     = null
            )
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewSurface: Surface?) {
        val state = _uiState.value
        val newFacing = state.cameraFacing.opposite()

        _uiState.update { it.copy(cameraFacing = newFacing) }

        if (state.streamingState == StreamingState.STREAMING) {
            val surface = encoder.inputSurface ?: return
            cameraManager.switchCamera(
                previewSurface = previewSurface,
                encoderSurface = surface,
                resolution     = state.selectedResolution,
                fps            = state.selectedFps,
                currentFacing  = state.cameraFacing
            )
        }
        viewModelScope.launch { settingsRepo.saveCameraFacing(newFacing) }
    }

    fun setResolution(
        resolution: Resolution,
        lifecycleOwner: LifecycleOwner? = null,
        previewSurface: Surface? = null
    ) {
        val wasStreaming = _uiState.value.streamingState == StreamingState.STREAMING
        _uiState.update { it.copy(selectedResolution = resolution) }
        viewModelScope.launch { settingsRepo.saveResolutionIndex(Resolution.ALL.indexOf(resolution)) }
        if (wasStreaming && lifecycleOwner != null) {
            stopStreaming()
            startStreaming(lifecycleOwner, previewSurface)
        }
    }

    fun setFps(
        fps: Int,
        lifecycleOwner: LifecycleOwner? = null,
        previewSurface: Surface? = null
    ) {
        val wasStreaming = _uiState.value.streamingState == StreamingState.STREAMING
        _uiState.update { it.copy(selectedFps = fps) }
        viewModelScope.launch { settingsRepo.saveFps(fps) }
        if (wasStreaming && lifecycleOwner != null) {
            stopStreaming()
            startStreaming(lifecycleOwner, previewSurface)
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun setAppMode(mode: com.example.camerax.model.AppMode) {
        _uiState.update { it.copy(appMode = mode) }
    }

    fun updateRecordingStatus(isRecording: Boolean, filePath: String? = null) {
        _uiState.update {
            it.copy(
                isRecordingShorts = isRecording,
                lastRecordedFilePath = filePath ?: it.lastRecordedFilePath
            )
        }
    }

    fun updateOverlayPermission(granted: Boolean) {
        _uiState.update { it.copy(overlayPermissionGranted = granted) }
    }

    fun startShortsPreview(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        if (_uiState.value.isRecordingShorts) return
        cameraManager.startPreview(
            previewSurface = surface,
            facing = _uiState.value.cameraFacing,
            zoom = _uiState.value.shortsZoomLevel
        )
    }

    fun stopShortsPreview() {
        if (_uiState.value.streamingState != StreamingState.STREAMING) {
            cameraManager.releaseCamera()
        }
    }

    fun setShortsZoom(zoom: Float) {
        val clamped = zoom.coerceIn(1.0f, 5.0f)
        _uiState.update { it.copy(shortsZoomLevel = clamped) }
        cameraManager.setZoom(clamped)
    }

    fun setShortsCircleWidth(widthDp: Int) {
        val clamped = widthDp.coerceIn(80, 260)
        _uiState.update { it.copy(shortsCircleWidthDp = clamped) }
    }

    fun switchShortsCamera(previewSurface: Surface?) {
        val newFacing = _uiState.value.cameraFacing.opposite()
        _uiState.update { it.copy(cameraFacing = newFacing) }
        viewModelScope.launch { settingsRepo.saveCameraFacing(newFacing) }
        if (previewSurface != null && previewSurface.isValid && !_uiState.value.isRecordingShorts) {
            cameraManager.startPreview(
                previewSurface = previewSurface,
                facing = newFacing,
                zoom = _uiState.value.shortsZoomLevel
            )
        }
    }

    // -------------------------------------------------------------------------
    // Dual Camera / Vlog Mode
    // -------------------------------------------------------------------------

    fun setDualCamLayout(layout: DualCamLayout) {
        _uiState.update { it.copy(dualCamLayout = layout) }
    }

    fun setPipShape(shape: PipShape) {
        _uiState.update { it.copy(pipShape = shape) }
    }

    fun setPipSize(sizeDp: Int) {
        val clamped = sizeDp.coerceIn(90, 260)
        _uiState.update { it.copy(pipSizeDp = clamped) }
    }

    fun swapDualCameras(primarySurface: Surface?, secondarySurface: Surface?, snapshotBitmap: Bitmap? = null) {
        val newPrimaryIsRear = !_uiState.value.isPrimaryRear
        _uiState.update { it.copy(isPrimaryRear = newPrimaryIsRear) }
        if (primarySurface != null && primarySurface.isValid) {
            if (_uiState.value.isRecordingDualCam) {
                dualCameraManager.swapCameraWhileRecording(
                    primarySurface = primarySurface,
                    secondarySurface = secondarySurface,
                    newIsPrimaryRear = newPrimaryIsRear,
                    snapshotBitmap = snapshotBitmap,
                    onSegmentSaved = { file ->
                        Log.i(TAG, "Vlog clip segment saved: ${file.name}")
                    },
                    onCameraSwapped = {
                        Log.i(TAG, "Camera swapped during recording successfully")
                    },
                    onError = { error ->
                        Log.e(TAG, "Camera swap error during recording: $error")
                        _uiState.update { it.copy(errorMessage = error) }
                    }
                )
            } else {
                startDualPreview(primarySurface, secondarySurface)
            }
        }
    }

    fun startDualPreview(primarySurface: Surface?, secondarySurface: Surface? = null) {
        if (primarySurface == null || !primarySurface.isValid) return
        dualCameraManager.startDualPreview(
            primarySurface = primarySurface,
            secondarySurface = secondarySurface,
            isPrimaryRear = _uiState.value.isPrimaryRear
        )
    }

    fun stopDualPreview() {
        dualCameraManager.releaseDualCamera()
    }

    fun updateDualRecordingStatus(isRecording: Boolean, filePath: String? = null) {
        _uiState.update {
            it.copy(
                isRecordingDualCam = isRecording,
                lastRecordedDualCamPath = filePath ?: it.lastRecordedDualCamPath
            )
        }
    }

    /**
     * Starts direct Camera2 MediaRecorder recording from the vlog camera.
     * Saves directly to /sdcard/Movies/CameraX/Vlog_YYYYMMDD_HHmmss.mp4 without screen capture or system prompts.
     */
    fun startDualVideoRecording(onStarted: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CameraX"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(moviesDir, "Vlog_${timestamp}.mp4")

        dualCameraManager.startVideoRecording(
            outputFile = outputFile,
            isPrimaryRear = _uiState.value.isPrimaryRear,
            onStarted = {
                _uiState.update { it.copy(isRecordingDualCam = true) }
                onStarted()
            },
            onError = { error ->
                _uiState.update { it.copy(errorMessage = error, isRecordingDualCam = false) }
                onError(error)
            }
        )
    }

    /**
     * Stops direct Camera2 MediaRecorder recording, tracks progress, and updates UI state with saved file path.
     */
    fun stopDualVideoRecording(onStopped: (String) -> Unit = {}) {
        _uiState.update { it.copy(isProcessingVideo = true, videoProcessingProgress = 0) }
        dualCameraManager.stopVideoRecording(
            onProgress = { progress ->
                _uiState.update { it.copy(videoProcessingProgress = progress) }
            },
            onStopped = { savedPath ->
                _uiState.update {
                    it.copy(
                        isRecordingDualCam = false,
                        isProcessingVideo = false,
                        videoProcessingProgress = 100,
                        lastRecordedDualCamPath = if (savedPath.isNotEmpty()) savedPath else it.lastRecordedDualCamPath
                    )
                }
                onStopped(savedPath)
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        cameraManager.shutdown()
        dualCameraManager.releaseDualCamera()
    }

    private fun setupCallbacks() {
        encoder.onEncodedData = { data, _, isKeyFrame, _ ->
            server.sendData(data)
            if (isKeyFrame) server.flush()
        }

        encoder.onEncoderError = { message ->
            setError("Video Encoder error: $message")
        }

        audioEncoder.onAudioData = { data, _ ->
            audioServer.sendAudioData(data)
        }

        audioEncoder.onError = { message ->
            Log.w(TAG, "Audio error: $message")
        }

        server.onClientConnected = {
            encoder.requestKeyFrame()
            _uiState.update { it.copy(isClientConnected = true) }
        }

        server.onClientDisconnected = {
            _uiState.update { it.copy(isClientConnected = false) }
        }

        server.onServerError = { message ->
            setError("Server error: $message")
        }
    }

    private fun setError(message: String) {
        viewModelScope.launch {
            stopStreaming()
            _uiState.update {
                it.copy(streamingState = StreamingState.ERROR, errorMessage = message)
            }
        }
    }
}
