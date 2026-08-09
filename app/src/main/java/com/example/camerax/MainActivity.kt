package com.example.camerax

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camerax.ui.screens.MainScreen
import com.example.camerax.ui.theme.CameraXTheme
import com.example.camerax.viewmodel.CameraViewModel

/**
 * Single-activity entry point for the USB Webcam application.
 *
 * Responsibilities:
 * 1. Keep the screen on during streaming ([WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]).
 * 2. Request [Manifest.permission.CAMERA] and [Manifest.permission.RECORD_AUDIO] permissions.
 * 3. Host the Compose content tree.
 * 4. Pass a [PreviewView] reference from the Compose UI down to [CameraViewModel].
 *
 * All business logic lives in [CameraViewModel]; this activity is intentionally thin.
 *
 * Key fix: ViewModel is obtained via [viewModels] delegate so it is initialized
 * before [onResume] is ever called — avoiding UninitializedPropertyAccessException.
 */
class MainActivity : ComponentActivity() {

    /**
     * ViewModel initialized eagerly by the [viewModels] delegate.
     * Guaranteed to be non-null before any lifecycle callback fires.
     */
    private val viewModel: CameraViewModel by viewModels()

    /** Set when the Compose [PreviewView] is first created inside [MainScreen]. */
    private var previewView: PreviewView? = null

    /** Handles both CAMERA and RECORD_AUDIO permission results in a single dialog. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        viewModel.onPermissionsResult(granted = cameraGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep display on while the app is in the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()

        setContent {
            CameraXTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                MainScreen(
                    state = uiState,
                    lifecycleOwner = lifecycleOwner,
                    onPreviewViewReady = { pv ->
                        previewView = pv
                    },
                    onStartStreaming = {
                        val pv = previewView ?: return@MainScreen
                        viewModel.startStreaming(lifecycleOwner, pv)
                    },
                    onStopStreaming = {
                        viewModel.stopStreaming()
                    },
                    onSwitchCamera = {
                        val pv = previewView ?: return@MainScreen
                        viewModel.switchCamera(lifecycleOwner, pv)
                    },
                    onSelectResolution = { resolution ->
                        val pv = previewView
                        viewModel.setResolution(resolution, lifecycleOwner, pv)
                    },
                    onSelectFps = { fps ->
                        val pv = previewView
                        viewModel.setFps(fps, lifecycleOwner, pv)
                    },
                    onOpenSettings = {
                        // Reserved for a future settings screen.
                    }
                )
            }
        }

        // Request permissions immediately on first launch.
        requestAppPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions on resume — user may have changed them in system Settings.
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionsResult(granted = cameraGranted)
    }

    override fun onPause() {
        super.onPause()
        // Release camera and stop streaming when backgrounded.
        viewModel.stopStreaming()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestAppPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }
}