package com.example.camerax.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camerax.model.Resolution
import java.nio.ByteBuffer

private const val TAG = "MediaCodecEncoder"
private const val MIME_TYPE = "video/avc"

/**
 * Hardware H.264 encoder using MediaCodec in Surface input mode.
 *
 * Why Surface mode instead of YUV buffer mode?
 * - Zero-copy: the camera HAL writes directly to the encoder's input Surface.
 * - Correct colors: the HAL handles all YUV → encoder format conversion internally.
 * - Real-time: no per-frame ByteArray allocation or pixel-by-pixel copying.
 * - Works at all resolutions: 480p, 720p, 1080p with no extra CPU overhead.
 *
 * [inputSurface] is the Surface to add as a Camera2 output target.
 *
 * Output (encoded NAL units) is delivered via async [MediaCodec.Callback] on a
 * dedicated HandlerThread. SPS/PPS is cached and prepended to every IDR frame
 * so late-joining clients (VLC, ffplay) can decode immediately.
 */
class MediaCodecEncoder {

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    /** Delivers a complete, Annex-B-formatted H.264 NAL unit ready to send over TCP. */
    var onEncodedData: ((data: ByteArray, isConfig: Boolean, isKeyFrame: Boolean, presentationUs: Long) -> Unit)? = null

    /** Called when the encoder encounters an unrecoverable error. */
    var onEncoderError: ((message: String) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * The encoder's input Surface. Pass this as an output target to Camera2's
     * [android.hardware.camera2.CameraDevice.createCaptureSession].
     * Non-null after [initialize] and null after [release].
     */
    var inputSurface: Surface? = null
        private set

    // -------------------------------------------------------------------------
    // Private state
    // -------------------------------------------------------------------------

    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null

    @Volatile
    private var cachedSpsBuffer: ByteArray? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Configures and starts the H.264 encoder.
     * Creates [inputSurface] for Camera2 to write frames into.
     *
     * @param resolution Target encoding resolution.
     * @param fps        Target frame rate (30 or 60).
     */
    fun initialize(resolution: Resolution, fps: Int) {
        release()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, resolution.width, resolution.height).apply {
            // Surface mode: camera writes directly, no color conversion needed
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, resolution.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Ultra-low latency tuning:
            // 1. Set I-Frame interval to 0.2s (every 6 frames at 30fps) for instant decoder synchronization
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.2f)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
            
            // 2. Force H.264 Baseline Profile (Profile 66 / Baseline) — completely disables B-frames & reordering delay
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 0) // Disable hardware codec input/output buffering
            }
        }

        val thread = HandlerThread("encoder-callback", Thread.MAX_PRIORITY).also { it.start() }
        callbackThread = thread
        val handler = Handler(thread.looper)

        try {
            val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)

            mediaCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface mode: camera fills input buffers — we never touch them
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    handleOutputBuffer(codec, index, info)
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Output format changed: $format")
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error: ${e.diagnosticInfo}", e)
                    onEncoderError?.invoke("Codec error: ${e.message}")
                }
            }, handler)

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec.createInputSurface()   // Camera2 writes here
            mediaCodec.start()
            codec = mediaCodec

            Log.i(TAG, "Encoder ready — ${resolution.width}x${resolution.height}" +
                    " @ ${fps}fps, ${resolution.bitrateBps / 1_000_000}Mbps CBR [Surface mode]")
        } catch (e: Exception) {
            Log.e(TAG, "Encoder init failed: ${e.message}", e)
            onEncoderError?.invoke("Encoder init failed: ${e.message}")
        }
    }

    /**
     * Requests the encoder to produce an IDR (key) frame immediately.
     * Call this when a new streaming client connects to send fresh SPS/PPS+IDR
     * instead of waiting up to [MediaFormat.KEY_I_FRAME_INTERVAL] seconds.
     */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            Log.d(TAG, "IDR key frame requested")
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyFrame failed: ${e.message}")
        }
    }

    /**
     * Signals EOS, stops and releases all MediaCodec resources.
     * Safe to call multiple times or from any thread.
     */
    fun release() {
        val surface = inputSurface
        inputSurface = null
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try {
            codec?.stop()
            codec?.release()
            Log.i(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing codec: ${e.message}")
        } finally {
            codec = null
        }
        try { surface?.release() } catch (_: Exception) {}
        callbackThread?.quitSafely()
        callbackThread = null
        cachedSpsBuffer = null
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun handleOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

        if (info.size <= 0 && !isConfig) {
            codec.releaseOutputBuffer(index, false)
            return
        }

        val buffer = codec.getOutputBuffer(index) ?: run {
            codec.releaseOutputBuffer(index, false)
            return
        }

        if (isConfig) {
            // Cache SPS+PPS — will be prepended to every IDR frame
            cachedSpsBuffer = buffer.toByteArray(info)
            Log.d(TAG, "SPS/PPS cached — ${cachedSpsBuffer?.size} bytes")
            onEncodedData?.invoke(cachedSpsBuffer!!, true, false, info.presentationTimeUs)
            codec.releaseOutputBuffer(index, false)
            return
        }

        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val frameData  = buffer.toByteArray(info)
        codec.releaseOutputBuffer(index, false)

        // Prepend SPS+PPS to IDR → any client that connects mid-stream can decode immediately
        val output = if (isKeyFrame && cachedSpsBuffer != null) {
            ByteArray(cachedSpsBuffer!!.size + frameData.size).also { out ->
                cachedSpsBuffer!!.copyInto(out)
                frameData.copyInto(out, destinationOffset = cachedSpsBuffer!!.size)
            }
        } else {
            frameData
        }

        onEncodedData?.invoke(output, false, isKeyFrame, info.presentationTimeUs)
    }

    private fun ByteBuffer.toByteArray(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }
}
