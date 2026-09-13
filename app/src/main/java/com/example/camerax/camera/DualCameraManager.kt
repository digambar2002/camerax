package com.example.camerax.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.graphics.Bitmap
import android.os.Environment
import com.example.camerax.model.CameraFacing
import com.example.camerax.recording.VideoMerger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DualCameraManager"

/**
 * Manages dual camera operations (Back and Front cameras) for Vlog / Dual Camera Mode.
 *
 * Supports:
 * - Direct Camera Video Recording via MediaRecorder (no screen capture / no system prompts).
 * - Concurrent hardware detection and streaming on supported devices.
 * - Instant 1-tap position swapping on single-ISP devices.
 */
class DualCameraManager(private val context: Context) {

    private val cameraSystemManager =
        context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager

    private var primaryCameraDevice: CameraDevice? = null
    private var primaryCaptureSession: CameraCaptureSession? = null

    private var secondaryCameraDevice: CameraDevice? = null
    private var secondaryCaptureSession: CameraCaptureSession? = null

    private var bgThreadPrimary: HandlerThread? = null
    private var bgHandlerPrimary: Handler? = null

    private var bgThreadSecondary: HandlerThread? = null
    private var bgHandlerSecondary: Handler? = null

    private var currentPrimarySurface: Surface? = null
    private var currentSecondarySurface: Surface? = null
    private var currentIsPrimaryRear: Boolean = true
    private var currentPrimaryId: String? = null

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private val recordedSegments = mutableListOf<File>()
    private var segmentIndex: Int = 1
    private var currentBaseRecordingName: String? = null
    private var lastVideoWidth: Int = 1920
    private var lastVideoHeight: Int = 1080

    var isRecordingVideo: Boolean = false
        private set

    var onDualCameraStarted: (() -> Unit)? = null
    var onDualCameraError: ((String) -> Unit)? = null

    /**
     * Checks if the device officially supports concurrent camera streaming.
     */
    fun supportsConcurrentCameras(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                cameraSystemManager.concurrentCameraIds.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Error checking concurrent camera IDs: ${e.message}")
                false
            }
        } else {
            false
        }
    }

    /**
     * Starts dual camera preview.
     *
     * @param primarySurface Surface for the primary / top view
     * @param secondarySurface Surface for the secondary / bottom / PiP view (optional on single-ISP)
     * @param isPrimaryRear If true, primary view is Rear camera and secondary is Front camera
     */
    @SuppressLint("MissingPermission")
    fun startDualPreview(
        primarySurface: Surface,
        secondarySurface: Surface? = null,
        isPrimaryRear: Boolean = true
    ) {
        releaseDualCamera()

        val primaryFacing = if (isPrimaryRear) CameraFacing.REAR else CameraFacing.FRONT
        val secondaryFacing = if (isPrimaryRear) CameraFacing.FRONT else CameraFacing.REAR

        val primaryId = getCameraId(primaryFacing) ?: run {
            onDualCameraError?.invoke("Camera not found for $primaryFacing")
            return
        }

        currentPrimarySurface = primarySurface
        currentSecondarySurface = secondarySurface
        currentIsPrimaryRear = isPrimaryRear
        currentPrimaryId = primaryId

        startPrimaryBgThread()

        try {
            cameraSystemManager.openCamera(primaryId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    primaryCameraDevice = camera
                    createSession(camera, primarySurface, bgHandlerPrimary) { session ->
                        primaryCaptureSession = session
                        Log.i(TAG, "Primary camera session active ($primaryFacing)")
                        onDualCameraStarted?.invoke()
                    }

                    // If device supports concurrent hardware and secondary surface is provided, open secondary camera
                    if (supportsConcurrentCameras() && secondarySurface != null && secondarySurface.isValid) {
                        val secondaryId = getCameraId(secondaryFacing)
                        if (secondaryId != null && secondaryId != primaryId) {
                            startSecondaryCamera(secondaryId, secondarySurface, secondaryFacing)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Primary camera disconnected")
                    releaseDualCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Primary camera error: $error")
                    onDualCameraError?.invoke("Primary camera error: $error")
                    releaseDualCamera()
                }
            }, bgHandlerPrimary)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening primary camera: ${e.message}", e)
            onDualCameraError?.invoke("Failed to open primary camera: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSecondaryCamera(
        cameraId: String,
        surface: Surface,
        facing: CameraFacing
    ) {
        startSecondaryBgThread()
        try {
            cameraSystemManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    secondaryCameraDevice = camera
                    createSession(camera, surface, bgHandlerSecondary) { session ->
                        secondaryCaptureSession = session
                        Log.i(TAG, "Secondary concurrent camera session active ($facing)")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Secondary camera disconnected")
                    try {
                        secondaryCaptureSession?.close()
                        secondaryCameraDevice?.close()
                    } catch (_: Exception) {}
                    secondaryCaptureSession = null
                    secondaryCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Secondary camera error: $error")
                }
            }, bgHandlerSecondary)
        } catch (e: Exception) {
            Log.w(TAG, "Error opening secondary camera: ${e.message}")
        }
    }

    private fun createSession(
        camera: CameraDevice,
        surface: Surface,
        handler: Handler?,
        onConfiguredCallback: (CameraCaptureSession) -> Unit
    ) {
        try {
            val surfaces = listOf(surface)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                        onConfiguredCallback(session)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Dual preview session configure failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session: ${e.message}", e)
        }
    }

    /**
     * Starts direct Camera2 MediaRecorder video recording from the active camera lens.
     * Records audio + video per segment for fully synchronized, standalone clips.
     */
    fun startVideoRecording(
        outputFile: File,
        isPrimaryRear: Boolean,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        val camera = primaryCameraDevice ?: run {
            onError("Camera device is not open")
            return
        }
        val previewSurface = currentPrimarySurface ?: run {
            onError("Preview surface is not available")
            return
        }
        val primaryId = currentPrimaryId ?: run {
            onError("Camera ID not found")
            return
        }

        segmentIndex = 1
        recordedSegments.clear()
        currentBaseRecordingName = outputFile.nameWithoutExtension
        val moviesDir = outputFile.parentFile ?: File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CameraX"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val firstPartFile = File(moviesDir, "${currentBaseRecordingName}_part1.mp4")
        currentOutputFile = firstPartFile

        startRecordingOnCamera(
            camera = camera,
            primaryId = primaryId,
            previewSurface = previewSurface,
            outputFile = firstPartFile,
            isPrimaryRear = isPrimaryRear,
            onStarted = onStarted,
            onError = onError
        )
    }

    private fun startRecordingOnCamera(
        camera: CameraDevice,
        primaryId: String,
        previewSurface: Surface,
        outputFile: File,
        isPrimaryRear: Boolean,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)

            // Resolve supported video resolution
            val chars = cameraSystemManager.getCameraCharacteristics(primaryId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

            val chosenSize = supportedSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: supportedSizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: supportedSizes.firstOrNull()
                ?: Size(1280, 720)

            lastVideoWidth = chosenSize.width
            lastVideoHeight = chosenSize.height

            recorder.setVideoSize(chosenSize.width, chosenSize.height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(10_000_000)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(48000)
            recorder.setAudioEncodingBitRate(128000)

            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            recorder.setOrientationHint(sensorOrientation)

            recorder.prepare()
            val recordSurface = recorder.surface

            val surfaces = listOf(previewSurface, recordSurface)
            val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    primaryCaptureSession = session
                    try {
                        session.setRepeatingRequest(recordBuilder.build(), null, bgHandlerPrimary)
                        recorder.start()
                        mediaRecorder = recorder
                        currentOutputFile = outputFile
                        isRecordingVideo = true
                        Log.i(TAG, "Vlog direct camera recording started: ${outputFile.name}")
                        onStarted()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
                        try { recorder.release() } catch (_: Exception) {}
                        onError("Failed to start recorder: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Recording capture session configure failed")
                    try { recorder.release() } catch (_: Exception) {}
                    onError("Failed to configure camera session for recording")
                }
            }, bgHandlerPrimary)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting video recording: ${e.message}", e)
            onError("Recorder error: ${e.message}")
        }
    }

    /**
     * Swaps cameras (Rear <-> Front) while recording is actively in progress.
     * Safely finalizes the current segment, opens the switched camera, and resumes recording.
     */
    @SuppressLint("MissingPermission")
    fun swapCameraWhileRecording(
        primarySurface: Surface,
        secondarySurface: Surface?,
        newIsPrimaryRear: Boolean,
        snapshotBitmap: Bitmap? = null,
        onSegmentSaved: (File) -> Unit = {},
        onCameraSwapped: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isRecordingVideo) {
            startDualPreview(primarySurface, secondarySurface, newIsPrimaryRear)
            return
        }

        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CameraX"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val baseName = currentBaseRecordingName ?: "Vlog"

        // 1. Immediately stop repeating request & close old capture session
        val oldSession = primaryCaptureSession
        val oldDevice = primaryCameraDevice
        val oldRecorder = mediaRecorder
        primaryCaptureSession = null
        primaryCameraDevice = null
        mediaRecorder = null

        try { oldSession?.stopRepeating() } catch (_: Exception) {}
        try { oldSession?.close() } catch (_: Exception) {}

        // 2. Stop and release old MediaRecorder cleanly to ensure MP4 headers are finalized
        try {
            oldRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop old MediaRecorder: ${e.message}")
        }
        try {
            oldRecorder?.reset()
            oldRecorder?.release()
        } catch (_: Exception) {}

        try { oldDevice?.close() } catch (_: Exception) {}

        // 3. Save the finished clip segment
        val lastFile = currentOutputFile
        if (lastFile != null && lastFile.exists() && lastFile.length() > 0) {
            recordedSegments.add(lastFile)
            Log.i(TAG, "Part $segmentIndex recorded successfully: ${lastFile.name} (${lastFile.length()} bytes)")
            onSegmentSaved(lastFile)
        }

        // 4. Prepare next segment file
        segmentIndex++
        val nextFile = File(moviesDir, "${baseName}_part${segmentIndex}.mp4")
        currentOutputFile = nextFile

        // 5. Open the newly switched camera and resume recording
        currentIsPrimaryRear = newIsPrimaryRear
        currentPrimarySurface = primarySurface
        currentSecondarySurface = secondarySurface

        val primaryFacing = if (newIsPrimaryRear) CameraFacing.REAR else CameraFacing.FRONT
        val secondaryFacing = if (newIsPrimaryRear) CameraFacing.FRONT else CameraFacing.REAR
        val primaryId = getCameraId(primaryFacing) ?: run {
            onError("Camera not found for $primaryFacing")
            return
        }
        currentPrimaryId = primaryId

        try {
            cameraSystemManager.openCamera(primaryId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    primaryCameraDevice = camera
                    startRecordingOnCamera(
                        camera = camera,
                        primaryId = primaryId,
                        previewSurface = primarySurface,
                        outputFile = nextFile,
                        isPrimaryRear = newIsPrimaryRear,
                        onStarted = {
                            Log.i(TAG, "Recording seamlessly resumed on $primaryFacing camera (part $segmentIndex)")
                            onCameraSwapped()
                        },
                        onError = { error ->
                            Log.e(TAG, "Failed to start recording on swapped camera: $error")
                            onError(error)
                        }
                    )

                    // Also handle secondary preview if concurrent hardware is supported
                    if (supportsConcurrentCameras() && secondarySurface != null && secondarySurface.isValid) {
                        val secondaryId = getCameraId(secondaryFacing)
                        if (secondaryId != null && secondaryId != primaryId) {
                            startSecondaryCamera(secondaryId, secondarySurface, secondaryFacing)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    primaryCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    primaryCameraDevice = null
                    onError("Camera error opening $primaryFacing: $error")
                }
            }, bgHandlerPrimary)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening swapped camera: ${e.message}", e)
            onError("Camera open error: ${e.message}")
        }
    }

    /**
     * Stops direct Camera2 MediaRecorder recording and merges all recorded segments
     * into ONE single continuous video, emitting real-time percentage updates (0..100%).
     */
    fun stopVideoRecording(
        onProgress: (Int) -> Unit = {},
        onStopped: (String) -> Unit
    ) {
        if (!isRecordingVideo) {
            onStopped("")
            return
        }
        isRecordingVideo = false

        val camera = primaryCameraDevice
        val previewSurface = currentPrimarySurface

        try {
            primaryCaptureSession?.stopRepeating()
        } catch (_: Exception) {}

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop failed: ${e.message}")
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        // Save final segment
        val lastFile = currentOutputFile
        if (lastFile != null && lastFile.exists() && lastFile.length() > 0) {
            recordedSegments.add(lastFile)
            Log.i(TAG, "Final part $segmentIndex recorded: ${lastFile.name} (${lastFile.length()} bytes)")
        }
        currentOutputFile = null

        // Restore preview-only session
        if (camera != null && previewSurface != null && previewSurface.isValid) {
            createSession(camera, previewSurface, bgHandlerPrimary) { session ->
                primaryCaptureSession = session
            }
        }

        val baseName = currentBaseRecordingName ?: "Vlog"
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CameraX"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val finalDestination = File(moviesDir, "${baseName}.mp4")

        val partsToMerge = recordedSegments.toList()
        Log.i(TAG, "Finalizing vlog recording: ${partsToMerge.size} parts to merge into ${finalDestination.name}")

        VideoMerger.mergeVideos(
            context = context,
            inputFiles = partsToMerge,
            outputFile = finalDestination,
            onProgress = onProgress,
            onComplete = { mergedFile ->
                Log.i(TAG, "Continuous vlog video saved: ${mergedFile.absolutePath}")
                onStopped(mergedFile.absolutePath)
            },
            onError = { error ->
                Log.e(TAG, "VideoMerger error: $error")
                val fallbackPath = partsToMerge.firstOrNull()?.absolutePath ?: ""
                onStopped(fallbackPath)
            }
        )
    }

    fun releaseDualCamera() {
        if (isRecordingVideo) {
            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {}
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null

            isRecordingVideo = false

            val lastFile = currentOutputFile
            if (lastFile != null && lastFile.exists() && lastFile.length() > 0) {
                recordedSegments.add(lastFile)
            }
            currentOutputFile = null

            if (recordedSegments.isNotEmpty()) {
                val baseName = currentBaseRecordingName ?: "Vlog"
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "CameraX"
                )
                val finalDestination = File(moviesDir, "${baseName}.mp4")
                VideoMerger.mergeVideos(
                    context = context,
                    inputFiles = recordedSegments.toList(),
                    outputFile = finalDestination,
                    onProgress = {},
                    onComplete = {},
                    onError = {}
                )
            }
        }

        try {
            primaryCaptureSession?.close()
            primaryCaptureSession = null
            primaryCameraDevice?.close()
            primaryCameraDevice = null

            secondaryCaptureSession?.close()
            secondaryCaptureSession = null
            secondaryCameraDevice?.close()
            secondaryCameraDevice = null

            stopPrimaryBgThread()
            stopSecondaryBgThread()
            Log.i(TAG, "Dual cameras released cleanly")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing dual cameras: ${e.message}")
        }
    }

    private fun getCameraId(facing: CameraFacing): String? {
        val target = when (facing) {
            CameraFacing.REAR -> CameraCharacteristics.LENS_FACING_BACK
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }
        for (id in cameraSystemManager.cameraIdList) {
            val chars = cameraSystemManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == target) {
                return id
            }
        }
        return cameraSystemManager.cameraIdList.firstOrNull()
    }

    private fun startPrimaryBgThread() {
        stopPrimaryBgThread()
        bgThreadPrimary = HandlerThread("dual-cam-primary").also {
            it.start()
            bgHandlerPrimary = Handler(it.looper)
        }
    }

    private fun stopPrimaryBgThread() {
        bgThreadPrimary?.quitSafely()
        try {
            bgThreadPrimary?.join(500)
        } catch (_: Exception) {}
        bgThreadPrimary = null
        bgHandlerPrimary = null
    }

    private fun startSecondaryBgThread() {
        stopSecondaryBgThread()
        bgThreadSecondary = HandlerThread("dual-cam-secondary").also {
            it.start()
            bgHandlerSecondary = Handler(it.looper)
        }
    }

    private fun stopSecondaryBgThread() {
        bgThreadSecondary?.quitSafely()
        try {
            bgThreadSecondary?.join(500)
        } catch (_: Exception) {}
        bgThreadSecondary = null
        bgHandlerSecondary = null
    }
}
