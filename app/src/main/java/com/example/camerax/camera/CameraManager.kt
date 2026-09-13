package com.example.camerax.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.example.camerax.model.CameraFacing
import com.example.camerax.model.Resolution

private const val TAG = "CameraManager"

/**
 * Camera2 manager providing a zero-copy dual-Surface streaming pipeline.
 *
 * Operating in Camera2 Surface mode ensures:
 * - 0ms frame copy overhead.
 * - Hardware color space conversion.
 * - Smooth 1080p @ 60 FPS real-time webcam operation.
 */
class CameraManager(private val context: Context) {

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    var onCameraStarted: (() -> Unit)? = null
    var onCameraStopped: (() -> Unit)? = null
    var onCameraError: ((message: String) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Private state
    // -------------------------------------------------------------------------

    private val cameraSystemManager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentCameraId: String? = null
    private var currentRequestBuilder: CaptureRequest.Builder? = null
    private var currentZoom: Float = 1.0f
    private var currentFacing: CameraFacing = CameraFacing.REAR

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startCamera(
        previewSurface: Surface?,
        encoderSurface: Surface,
        resolution: Resolution,
        fps: Int,
        facing: CameraFacing
    ) {
        stopBackgroundThread()
        startBackgroundThread()

        val cameraId = getCameraId(facing) ?: run {
            onCameraError?.invoke("No camera found for facing $facing")
            return
        }
        currentCameraId = cameraId
        currentFacing = facing

        try {
            cameraSystemManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, previewSurface, encoderSurface, resolution, fps)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    releaseCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device error: $error")
                    onCameraError?.invoke("Camera device error: $error")
                    releaseCamera()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}", e)
            onCameraError?.invoke("Failed to open camera: ${e.message}")
        }
    }

    /**
     * Starts camera preview for in-app viewfinder without encoding pipeline.
     */
    @SuppressLint("MissingPermission")
    fun startPreview(
        previewSurface: Surface,
        facing: CameraFacing,
        zoom: Float = 1.0f
    ) {
        releaseCamera()
        startBackgroundThread()

        val cameraId = getCameraId(facing) ?: run {
            onCameraError?.invoke("No camera found for facing $facing")
            return
        }
        currentCameraId = cameraId
        currentFacing = facing
        currentZoom = zoom

        try {
            cameraSystemManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(camera, previewSurface, zoom)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    releaseCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    releaseCamera()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening preview camera: ${e.message}", e)
        }
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom
        val session = captureSession ?: return
        val builder = currentRequestBuilder ?: return
        try {
            applyZoom(builder, zoom)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply zoom: ${e.message}")
        }
    }

    fun switchCamera(
        previewSurface: Surface?,
        encoderSurface: Surface,
        resolution: Resolution,
        fps: Int,
        currentFacing: CameraFacing
    ) {
        releaseCamera()
        startCamera(previewSurface, encoderSurface, resolution, fps, currentFacing.opposite())
    }

    fun releaseCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            currentRequestBuilder = null
            currentCameraId = null
            stopBackgroundThread()
            onCameraStopped?.invoke()
            Log.i(TAG, "Camera released")
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing camera: ${e.message}")
        }
    }

    fun shutdown() {
        releaseCamera()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun createCaptureSession(
        camera: CameraDevice,
        previewSurface: Surface?,
        encoderSurface: Surface,
        resolution: Resolution,
        fps: Int
    ) {
        try {
            val surfaces = mutableListOf<Surface>(encoderSurface)
            if (previewSurface != null && previewSurface.isValid) {
                surfaces.add(previewSurface)
            }

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoderSurface)
                if (previewSurface != null && previewSurface.isValid) {
                    addTarget(previewSurface)
                }
                
                // Real-time camera tuning
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                
                // Targeted FPS range for low latency
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Zero-copy Camera2 session active (${resolution.width}x${resolution.height} @ ${fps}fps)")
                        onCameraStarted?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request: ${e.message}", e)
                        onCameraError?.invoke("Failed to start camera capture stream")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                    onCameraError?.invoke("Session config failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}", e)
            onCameraError?.invoke("Failed to create capture session: ${e.message}")
        }
    }

    private fun createPreviewSession(
        camera: CameraDevice,
        previewSurface: Surface,
        zoom: Float
    ) {
        try {
            val surfaces = listOf(previewSurface)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyZoom(this, zoom)
            }
            currentRequestBuilder = builder

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Camera2 preview active with zoom $zoom")
                        onCameraStarted?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed repeating request for preview: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera preview session configuration failed")
                    onCameraError?.invoke("Preview session config failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preview session: ${e.message}", e)
            onCameraError?.invoke("Failed to create preview session: ${e.message}")
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoom: Float) {
        val cameraId = currentCameraId ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val chars = cameraSystemManager.getCameraCharacteristics(cameraId)
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                val clamped = zoom.coerceIn(range.lower, range.upper)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                return
            }
        }
        val chars = cameraSystemManager.getCameraCharacteristics(cameraId)
        val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val clampedZoom = maxOf(1.0f, zoom)
        val cropW = (rect.width() / clampedZoom).toInt()
        val cropH = (rect.height() / clampedZoom).toInt()
        val cropX = rect.left + (rect.width() - cropW) / 2
        val cropY = rect.top + (rect.height() - cropH) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    private fun getCameraId(facing: CameraFacing): String? {
        val targetLens = when (facing) {
            CameraFacing.REAR -> CameraCharacteristics.LENS_FACING_BACK
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }

        for (id in cameraSystemManager.cameraIdList) {
            val characteristics = cameraSystemManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == targetLens) {
                return id
            }
        }
        return cameraSystemManager.cameraIdList.firstOrNull()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("camera-bg", Thread.MAX_PRIORITY).also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.w(TAG, "Interrupted stopping bg thread: ${e.message}")
        }
    }
}
