package com.example.camerax.viewmodel

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
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

private const val TAG = "CameraViewModel"

/**
 * MVVM ViewModel managing dual-stream zero-latency streaming:
 * - Video: Camera2 → Encoder Surface → H.264 Encoder → TCP Server (5000) → ADB → PC
 * - Audio: Mic PCM → AudioRecord → AAC Encoder → TCP AudioServer (5001) → ADB → PC
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo  = SettingsRepository(application)
    private val cameraManager = CameraManager(application)
    private val encoder       = MediaCodecEncoder()
    private val server        = StreamingServer()

    private val audioEncoder  = AudioEncoder()
    private val audioServer   = AudioServer()

    private val _uiState = MutableStateFlow(AppState())
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

    fun startStreaming(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
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

        // 4. Start Camera2 hardware stream
        cameraManager.startCamera(
            previewView     = previewView,
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

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val state = _uiState.value
        val newFacing = state.cameraFacing.opposite()

        _uiState.update { it.copy(cameraFacing = newFacing) }

        if (state.streamingState == StreamingState.STREAMING) {
            val surface = encoder.inputSurface ?: return
            cameraManager.switchCamera(
                previewView    = previewView,
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
        previewView: PreviewView? = null
    ) {
        val wasStreaming = _uiState.value.streamingState == StreamingState.STREAMING
        _uiState.update { it.copy(selectedResolution = resolution) }
        viewModelScope.launch { settingsRepo.saveResolutionIndex(Resolution.ALL.indexOf(resolution)) }
        if (wasStreaming && lifecycleOwner != null && previewView != null) {
            stopStreaming()
            startStreaming(lifecycleOwner, previewView)
        }
    }

    fun setFps(
        fps: Int,
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null
    ) {
        val wasStreaming = _uiState.value.streamingState == StreamingState.STREAMING
        _uiState.update { it.copy(selectedFps = fps) }
        viewModelScope.launch { settingsRepo.saveFps(fps) }
        if (wasStreaming && lifecycleOwner != null && previewView != null) {
            stopStreaming()
            startStreaming(lifecycleOwner, previewView)
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        cameraManager.shutdown()
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
