package com.example.camerax

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camerax.model.AppMode
import com.example.camerax.recording.ScreenRecorderService
import com.example.camerax.ui.screens.MainScreen
import com.example.camerax.ui.theme.CameraXTheme
import com.example.camerax.viewmodel.CameraViewModel

/**
 * Single-activity entry point for USB Webcam & Shorts Creator.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()
    private var previewSurface: Surface? = null
    private var isRecordingStarting = false

    private var dualPrimarySurface: Surface? = null
    private var dualSecondarySurface: Surface? = null

    /** Handles CAMERA and RECORD_AUDIO permission results */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        viewModel.onPermissionsResult(granted = cameraGranted)
    }

    /** Handles Screen Capture (MediaProjection) prompt result for Shorts Mode */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            isRecordingStarting = true
            viewModel.stopShortsPreview()
            viewModel.updateRecordingStatus(isRecording = true)

            val serviceIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_START
                putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecorderService.EXTRA_CIRCLE_WIDTH_DP, viewModel.uiState.value.shortsCircleWidthDp)
                putExtra(ScreenRecorderService.EXTRA_ZOOM_LEVEL, viewModel.uiState.value.shortsZoomLevel)
                putExtra(ScreenRecorderService.EXTRA_CAMERA_FACING, viewModel.uiState.value.cameraFacing.name)
                putExtra(ScreenRecorderService.EXTRA_SHOW_FLOATING_OVERLAY, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            isRecordingStarting = false
            viewModel.updateRecordingStatus(isRecording = false)
            // User cancelled permission; restore in-app preview
            if (viewModel.uiState.value.appMode == AppMode.SHORTS_CREATOR) {
                previewSurface?.let { viewModel.startShortsPreview(it) }
            }
        }
    }


    /** Handles SYSTEM_ALERT_WINDOW settings result */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val canDraw = Settings.canDrawOverlays(this)
        viewModel.updateOverlayPermission(canDraw)
        if (canDraw) {
            startScreenCapturePrompt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            CameraXTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                MainScreen(
                    state = uiState,
                    lifecycleOwner = lifecycleOwner,
                    onSurfaceReady = { surface ->
                        previewSurface = surface
                        if (uiState.appMode == AppMode.SHORTS_CREATOR && !uiState.isRecordingShorts) {
                            viewModel.startShortsPreview(surface)
                        }
                    },
                    onSurfaceDestroyed = {
                        previewSurface = null
                        viewModel.stopShortsPreview()
                    },
                    onDualPrimarySurfaceReady = { surface ->
                        dualPrimarySurface = surface
                        if (uiState.appMode == AppMode.DUAL_CAMERA) {
                            viewModel.startDualPreview(surface, dualSecondarySurface)
                        }
                    },
                    onDualPrimarySurfaceDestroyed = {
                        dualPrimarySurface = null
                        viewModel.stopDualPreview()
                    },
                    onDualSecondarySurfaceReady = { surface ->
                        dualSecondarySurface = surface
                        if (uiState.appMode == AppMode.DUAL_CAMERA && dualPrimarySurface != null) {
                            viewModel.startDualPreview(dualPrimarySurface, surface)
                        }
                    },
                    onDualSecondarySurfaceDestroyed = {
                        dualSecondarySurface = null
                    },
                    onSelectMode = { mode ->
                        if (uiState.streamingState == com.example.camerax.model.StreamingState.STREAMING) {
                            viewModel.stopStreaming()
                        }
                        viewModel.stopShortsPreview()
                        viewModel.stopDualPreview()
                        viewModel.setAppMode(mode)
                        if (mode == AppMode.SHORTS_CREATOR && previewSurface != null) {
                            viewModel.startShortsPreview(previewSurface)
                        } else if (mode == AppMode.DUAL_CAMERA && dualPrimarySurface != null) {
                            viewModel.startDualPreview(dualPrimarySurface, dualSecondarySurface)
                        }
                    },
                    onStartStreaming = {
                        viewModel.startStreaming(lifecycleOwner, previewSurface = null)
                    },
                    onStopStreaming = {
                        viewModel.stopStreaming()
                    },
                    onStartShortsRecording = {
                        startShortsRecording()
                    },
                    onStopShortsRecording = {
                        stopShortsRecording()
                    },
                    onStartDualRecording = {
                        startDualRecording()
                    },
                    onStopDualRecording = {
                        stopDualRecording()
                    },
                    onSwitchCamera = {
                        viewModel.switchCamera(lifecycleOwner, previewSurface = null)
                    },
                    onSwitchShortsCamera = {
                        viewModel.switchShortsCamera(previewSurface)
                    },
                    onCircleWidthChange = { width ->
                        viewModel.setShortsCircleWidth(width)
                    },
                    onZoomChange = { zoom ->
                        viewModel.setShortsZoom(zoom)
                    },
                    onSelectDualLayout = { layout ->
                        viewModel.setDualCamLayout(layout)
                    },
                    onSelectPipShape = { shape ->
                        viewModel.setPipShape(shape)
                    },
                    onPipSizeChange = { size ->
                        viewModel.setPipSize(size)
                    },
                    onSwapDualCameras = { bitmap ->
                        viewModel.swapDualCameras(dualPrimarySurface, dualSecondarySurface, bitmap)
                    },
                    onSelectResolution = { resolution ->
                        viewModel.setResolution(resolution, lifecycleOwner, previewSurface = null)
                    },
                    onSelectFps = { fps ->
                        viewModel.setFps(fps, lifecycleOwner, previewSurface = null)
                    },
                    onOpenSettings = {
                        // Reserved for a future settings screen.
                    }
                )
            }
        }

        requestAppPermissions()
    }

    override fun onResume() {
        super.onResume()
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionsResult(granted = cameraGranted)
        viewModel.updateOverlayPermission(Settings.canDrawOverlays(this))

        val currentlyRecording = ScreenRecorderService.isRecording
        if (currentlyRecording) {
            isRecordingStarting = false
        }
        viewModel.updateRecordingStatus(currentlyRecording, ScreenRecorderService.lastRecordedFilePath)

        if (cameraGranted && !currentlyRecording) {
            if (viewModel.uiState.value.appMode == AppMode.SHORTS_CREATOR && !isRecordingStarting) {
                previewSurface?.let { viewModel.startShortsPreview(it) }
            } else if (viewModel.uiState.value.appMode == AppMode.DUAL_CAMERA && !viewModel.uiState.value.isRecordingDualCam) {
                dualPrimarySurface?.let { viewModel.startDualPreview(it, dualSecondarySurface) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopShortsPreview()
        viewModel.stopDualPreview()
        if (viewModel.uiState.value.appMode == AppMode.WEBCAM) {
            viewModel.stopStreaming()
        }
    }

    private fun startShortsRecording() {
        // Release camera preview so floating window can access hardware
        viewModel.stopShortsPreview()

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        isRecordingStarting = true
        startScreenCapturePrompt()
    }

    private fun startScreenCapturePrompt() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun stopShortsRecording() {
        isRecordingStarting = false
        val serviceIntent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        startService(serviceIntent)
        viewModel.updateRecordingStatus(isRecording = false, filePath = ScreenRecorderService.lastRecordedFilePath)
        if (viewModel.uiState.value.appMode == AppMode.SHORTS_CREATOR) {
            previewSurface?.let { viewModel.startShortsPreview(it) }
        }
    }

    private fun startDualRecording() {
        viewModel.startDualVideoRecording()
    }

    private fun stopDualRecording() {
        viewModel.stopDualVideoRecording()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}