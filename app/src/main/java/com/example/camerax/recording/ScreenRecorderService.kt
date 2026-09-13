package com.example.camerax.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.camerax.MainActivity
import com.example.camerax.model.CameraFacing
import com.example.camerax.overlay.FloatingCameraOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

private const val TAG = "ScreenRecorderService"
private const val NOTIFICATION_CHANNEL_ID = "camerax_shorts_recording"
private const val NOTIFICATION_ID = 2001

class ScreenRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.camerax.ACTION_START_RECORDING"
        const val ACTION_STOP = "com.example.camerax.ACTION_STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CIRCLE_WIDTH_DP = "extra_circle_width_dp"
        const val EXTRA_ZOOM_LEVEL = "extra_zoom_level"
        const val EXTRA_CAMERA_FACING = "extra_camera_facing"
        const val EXTRA_SHOW_FLOATING_OVERLAY = "extra_show_floating_overlay"

        @Volatile
        var isRecording = false
            private set

        @Volatile
        var lastRecordedFilePath: String? = null
            private set
    }

    private val recordingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var floatingOverlay: FloatingCameraOverlay? = null
    private var currentOutputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                val circleWidth = intent.getIntExtra(EXTRA_CIRCLE_WIDTH_DP, 150)
                val zoomLevel = intent.getFloatExtra(EXTRA_ZOOM_LEVEL, 1.0f)
                val facingStr = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: CameraFacing.FRONT.name
                val facing = try { CameraFacing.valueOf(facingStr) } catch (_: Exception) { CameraFacing.FRONT }
                val showFloatingOverlay = intent.getBooleanExtra(EXTRA_SHOW_FLOATING_OVERLAY, true)

                if (resultCode != 0 && resultData != null) {
                    // Must be called synchronously on main thread to avoid ForegroundServiceDidNotStartInTimeException
                    startForegroundNotification()
                    recordingExecutor.execute {
                        startRecording(resultCode, resultData, circleWidth, zoomLevel, facing, showFloatingOverlay)
                    }
                } else {
                    Log.e(TAG, "Invalid result code or data for MediaProjection")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                recordingExecutor.execute {
                    stopRecording()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording Shorts...")
            .setContentText("Capturing screen with camera bubble")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        circleWidth: Int = 150,
        zoomLevel: Float = 1.0f,
        facing: CameraFacing = CameraFacing.FRONT,
        showFloatingOverlay: Boolean = true
    ) {
        if (isRecording) return

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Failed to obtain MediaProjection")
            stopSelf()
            return
        }
        mediaProjection = mp

        // Mandatory on Android 14+ (API 34): register callback before createVirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection session stopped by system")
                recordingExecutor.execute {
                    stopRecording()
                    stopSelf()
                }
            }
        }, mainHandler)

        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / 16) * 16
        val height = (metrics.heightPixels / 16) * 16
        val dpi = metrics.densityDpi

        // Setup output directory in Movies/CameraX
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CameraX").apply {
            if (!exists()) mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(dir, "Short_$timestamp.mp4")
        currentOutputFile = outputFile

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                // Attempt Audio + Video capture
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(width, height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoEncodingBitRate(6_000_000) // 6 Mbps high quality
                    setVideoFrameRate(30) // 30 FPS stable and hardware compliant
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    prepare()
                }
            } catch (audioEx: Exception) {
                Log.w(TAG, "Audio+Video prepare failed, falling back to Video-only: ${audioEx.message}")
                recorder.reset()
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(width, height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(6_000_000)
                    setVideoFrameRate(30)
                    prepare()
                }
            }
            mediaRecorder = recorder

            // Create virtual display feeding screen directly into hardware recorder surface
            virtualDisplay = mp.createVirtualDisplay(
                "ShortsScreenRecord",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            isRecording = true
            Log.i(TAG, "Recording active: ${outputFile.absolutePath} (${width}x$height @ 30fps)")

            // Show Floating Camera Bubble only if requested (e.g. for Shorts Mode)
            if (showFloatingOverlay) {
                mainHandler.post {
                    if (isRecording) {
                        floatingOverlay = FloatingCameraOverlay(
                            context = this,
                            initialWidthDp = circleWidth,
                            initialZoom = zoomLevel,
                            initialFacing = facing,
                            onStopRecordingRequested = {
                                recordingExecutor.execute {
                                    stopRecording()
                                    stopSelf()
                                }
                            }
                        ).also { it.show() }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
            stopRecording()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        mainHandler.post {
            floatingOverlay?.hide()
            floatingOverlay = null
        }

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping MediaRecorder: ${e.message}")
                }
                try {
                    reset()
                } catch (_: Exception) {}
                try {
                    release()
                } catch (_: Exception) {}
            }
            mediaRecorder = null

            try {
                mediaProjection?.stop()
            } catch (_: Exception) {}
            mediaProjection = null

            currentOutputFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    lastRecordedFilePath = file.absolutePath
                    Log.i(TAG, "Recording saved: ${file.absolutePath} (${file.length()} bytes)")
                    MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                } else {
                    Log.w(TAG, "Recording file empty or missing: ${file.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording: ${e.message}")
        } finally {
            mainHandler.post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingExecutor.execute {
            stopRecording()
        }
        recordingExecutor.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Shorts Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording controls and status for CameraX Shorts"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
