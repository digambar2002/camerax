package com.example.camerax.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.camerax.model.CameraFacing

import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.widget.TextView

private const val TAG = "FloatingCameraOverlay"

/**
 * Floating circular camera bubble that renders on top of all applications, games, and screens.
 *
 * Key features:
 * - Fluid drag-and-drop touch listener to move the bubble anywhere on screen.
 * - Hardware Camera2 preview inside a hardware-clipped circular Surface.
 * - Tap to resize (cycle width: 110dp / 140dp / 175dp / 215dp) + zoom adjustments (1x / 1.5x / 2x / 3x).
 * - Flip camera (front <-> rear).
 * - Quick stop button to finish screen recording.
 */
class FloatingCameraOverlay(
    private val context: Context,
    private val initialWidthDp: Int = 150,
    private val initialZoom: Float = 1.0f,
    private val initialFacing: CameraFacing = CameraFacing.FRONT,
    private val onStopRecordingRequested: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cameraSystemManager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager

    private var rootView: FrameLayout? = null
    private var textureView: TextureView? = null
    private var controlsLayout: LinearLayout? = null
    private var zoomTextView: TextView? = null
    private var sizeTextView: TextView? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var currentFacing = initialFacing
    private var currentZoom = initialZoom
    private var currentWidthDp = initialWidthDp

    private val zoomPresets = listOf(1.0f, 1.5f, 2.0f, 3.0f)
    private val sizePresets = listOf(110, 140, 175, 215)

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isControlsVisible = false

    fun show() {
        if (rootView != null) return

        startBackgroundThread()
        val density = context.resources.displayMetrics.density
        val initialSizePx = (currentWidthDp * density).toInt()

        val params = WindowManager.LayoutParams(
            initialSizePx,
            initialSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (20 * density).toInt()
            y = (100 * density).toInt()
        }
        layoutParams = params

        val root = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        // Circular Camera Viewfinder
        val tv = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    st.setDefaultBufferSize(1920, 1080)
                    adjustAspectRatio(width, height)
                    post { adjustAspectRatio(width, height) }
                    openCamera(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    adjustAspectRatio(width, height)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        textureView = tv
        root.addView(tv, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // Overlay Action Controls (Flip / Zoom / Resize / Stop)
        val controls = createControlsOverlay(density)
        controlsLayout = controls
        controls.visibility = View.GONE
        root.addView(controls, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // Dragging & Tapping Touch Listener
        setupTouchListener(root)

        rootView = root
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}", e)
        }
    }

    fun hide() {
        closeCamera()
        stopBackgroundThread()
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
        }
        rootView = null
        textureView = null
        controlsLayout = null
        zoomTextView = null
        sizeTextView = null
    }

    private fun createControlsOverlay(density: Float): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B3000000")) // Semi-transparent dark scrim

            // 1. Flip Camera Icon
            val flipIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(Color.WHITE)
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    toggleCameraFacing()
                }
            }
            addView(flipIcon, (32 * density).toInt(), (32 * density).toInt())

            // 2. Zoom Button Pill
            val zoomBtn = TextView(context).apply {
                text = "${currentZoom}x"
                setTextColor(Color.parseColor("#00C8FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#3300C8FF"))
                val padH = (6 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    cycleZoom()
                }
            }
            zoomTextView = zoomBtn
            addView(zoomBtn)

            // Spacing
            val spacer1 = View(context)
            addView(spacer1, (4 * density).toInt(), 1)

            // 3. Size Button Pill
            val sizeBtn = TextView(context).apply {
                text = "${currentWidthDp}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                val padH = (6 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    cycleBubbleSize()
                }
            }
            sizeTextView = sizeBtn
            addView(sizeBtn)

            // 4. Stop Recording Icon
            val stopIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_media_pause)
                setColorFilter(Color.parseColor("#FF5252")) // Red stop button
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    onStopRecordingRequested()
                }
            }
            addView(stopIcon, (32 * density).toInt(), (32 * density).toInt())
        }
    }

    private fun setupTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Tap toggles floating action controls
                            isControlsVisible = !isControlsVisible
                            controlsLayout?.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun cycleBubbleSize() {
        val currentIndex = sizePresets.indexOfFirst { it >= currentWidthDp }.let { if (it == -1) 0 else it }
        val nextIndex = (currentIndex + 1) % sizePresets.size
        currentWidthDp = sizePresets[nextIndex]
        sizeTextView?.text = "${currentWidthDp}"

        val density = context.resources.displayMetrics.density
        val newSizePx = (currentWidthDp * density).toInt()

        layoutParams?.let { params ->
            params.width = newSizePx
            params.height = newSizePx
            try {
                rootView?.invalidateOutline()
                windowManager.updateViewLayout(rootView, params)
                textureView?.post { adjustAspectRatio(newSizePx, newSizePx) }
            } catch (_: Exception) {}
        }
    }

    private fun cycleZoom() {
        val currentIndex = zoomPresets.indexOfFirst { it >= currentZoom }.let { if (it == -1) 0 else it }
        val nextIndex = (currentIndex + 1) % zoomPresets.size
        val nextZoom = zoomPresets[nextIndex]
        setZoom(nextZoom)
    }

    private fun toggleCameraFacing() {
        currentFacing = currentFacing.opposite()
        textureView?.surfaceTexture?.let { st ->
            closeCamera()
            openCamera(st)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture) {
        val cameraId = getCameraId(currentFacing) ?: return
        try {
            cameraSystemManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, surfaceTexture)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Floating camera error: $error")
                    closeCamera()
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera for overlay: ${e.message}", e)
        }
    }

    private fun createSession(camera: CameraDevice, st: SurfaceTexture) {
        try {
            st.setDefaultBufferSize(640, 480)
            val surface = Surface(st)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                applyZoom(this, currentZoom)
            }

            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, bgHandler)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Overlay session configure failed")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay session: ${e.message}", e)
        }
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom
        zoomTextView?.text = "${zoom}x"
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val st = textureView?.surfaceTexture ?: return
        try {
            val surface = Surface(st)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                applyZoom(this, zoom)
            }
            session.setRepeatingRequest(builder.build(), null, bgHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed repeating zoom request: ${e.message}")
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoom: Float) {
        val cameraId = getCameraId(currentFacing) ?: return
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

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing overlay camera: ${e.message}")
        }
    }

    private fun getCameraId(facing: CameraFacing): String? {
        val target = when (facing) {
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraFacing.REAR -> CameraCharacteristics.LENS_FACING_BACK
        }
        for (id in cameraSystemManager.cameraIdList) {
            val chars = cameraSystemManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == target) {
                return id
            }
        }
        return cameraSystemManager.cameraIdList.firstOrNull()
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("floating-camera-bg").also {
            it.start()
            bgHandler = Handler(it.looper)
        }
    }

    private fun adjustAspectRatio(viewWidth: Int, viewHeight: Int) {
        val tv = textureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val targetAspect = 9f / 16f
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (viewAspect > targetAspect) {
            scaleX = 1f
            scaleY = (viewWidth.toFloat() / targetAspect) / viewHeight.toFloat()
        } else {
            scaleX = (viewHeight.toFloat() * targetAspect) / viewWidth.toFloat()
            scaleY = 1f
        }
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        tv.setTransform(matrix)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try {
            bgThread?.join(500)
            bgThread = null
            bgHandler = null
        } catch (_: Exception) {}
    }
}
