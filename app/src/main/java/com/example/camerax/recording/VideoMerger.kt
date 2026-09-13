package com.example.camerax.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

private const val TAG = "VideoMerger"

/**
 * Represents an item in the continuous video timeline.
 */
sealed class VideoTimelineItem {
    data class VideoClip(val file: File) : VideoTimelineItem()
    data class TransitionGap(val imageFile: File, val durationUs: Long) : VideoTimelineItem()
}

/**
 * Merges multiple video clips recorded during camera switching into a single continuous MP4 file.
 *
 * Uses [Mp4Concatenator] for ultra-fast, lossless sample copying when orientations match,
 * and Media3 [Transformer] for automatic rotation normalization when orientations differ (Rear 90° vs Front 270°).
 */
object VideoMerger {

    fun mergeContinuousVlog(
        context: Context,
        timelineItems: List<VideoTimelineItem>,
        masterAudioFile: File? = null,
        outputFile: File,
        onProgress: (Int) -> Unit = {},
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val clips = timelineItems.filterIsInstance<VideoTimelineItem.VideoClip>().map { it.file }
        if (clips.isEmpty()) {
            onError("No clips to merge")
            return
        }

        mergeVideos(
            context = context,
            inputFiles = clips,
            outputFile = outputFile,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
    }

    /**
     * Merges a list of video clips into [outputFile].
     */
    fun mergeVideos(
        context: Context,
        inputFiles: List<File>,
        outputFile: File,
        onProgress: (Int) -> Unit = {},
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val validClips = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validClips.isEmpty()) {
            onError("No valid clips to merge")
            return
        }

        // Single clip: copy to output destination directly
        if (validClips.size == 1) {
            val single = validClips[0]
            if (single.absolutePath != outputFile.absolutePath) {
                try {
                    single.copyTo(outputFile, overwrite = true)
                    single.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Error moving single file: ${e.message}")
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            onProgress(100)
            onComplete(outputFile)
            return
        }

        // Check if clips have differing orientation hints (e.g. 90 vs 270)
        var hasDifferingOrientations = false
        var firstRotation: Int? = null
        for (file in validClips) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            format.getInteger(MediaFormat.KEY_ROTATION)
                        } else {
                            0
                        }
                        if (firstRotation == null) {
                            firstRotation = rot
                        } else if (firstRotation != rot) {
                            hasDifferingOrientations = true
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking rotation of ${file.name}: ${e.message}")
            } finally {
                extractor.release()
            }
            if (hasDifferingOrientations) break
        }

        if (!hasDifferingOrientations) {
            // All clips have the same orientation: use ultra-fast Mp4Concatenator
            Log.i(TAG, "Merging ${validClips.size} clips directly with Mp4Concatenator")
            Mp4Concatenator.concatenate(
                context = context,
                inputFiles = validClips,
                outputFile = outputFile,
                onProgress = onProgress,
                onComplete = onComplete,
                onError = { error ->
                    Log.w(TAG, "Mp4Concatenator failed, trying Transformer fallback: $error")
                    mergeWithTransformer(context, validClips, outputFile, onProgress, onComplete, onError)
                }
            )
        } else {
            // Differing orientations (Rear 90° vs Front 270°): Media3 Transformer normalizes rotation
            Log.i(TAG, "Clips have differing orientations ($firstRotation° vs others), using Media3 Transformer")
            mergeWithTransformer(
                context = context,
                inputFiles = validClips,
                outputFile = outputFile,
                onProgress = onProgress,
                onComplete = onComplete,
                onError = { error ->
                    Log.w(TAG, "Transformer failed, falling back to Mp4Concatenator: $error")
                    Mp4Concatenator.concatenate(
                        context = context,
                        inputFiles = validClips,
                        outputFile = outputFile,
                        onProgress = onProgress,
                        onComplete = onComplete,
                        onError = onError
                    )
                }
            )
        }
    }

    private fun mergeWithTransformer(
        context: Context,
        inputFiles: List<File>,
        outputFile: File,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val mediaItems = inputFiles.map { file ->
                    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build()
                }
                val sequence = EditedMediaItemSequence(mediaItems)
                val composition = Composition.Builder(listOf(sequence)).build()

                val progressHolder = ProgressHolder()
                val progressHandler = Handler(Looper.getMainLooper())
                var transformerInstance: Transformer? = null

                val progressRunnable = object : Runnable {
                    override fun run() {
                        val trans = transformerInstance ?: return
                        val state = trans.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val percent = progressHolder.progress.coerceIn(0, 99)
                            onProgress(percent)
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            progressHandler.postDelayed(this, 100)
                        }
                    }
                }

                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                            progressHandler.removeCallbacks(progressRunnable)
                            onProgress(100)
                            Log.i(TAG, "Media3 successfully merged into: ${outputFile.name}")

                            // Delete temporary clip files
                            inputFiles.forEach { file ->
                                if (file.absolutePath != outputFile.absolutePath && file.exists()) {
                                    file.delete()
                                }
                            }

                            // Index final merged video in Android MediaStore
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(outputFile.absolutePath),
                                arrayOf("video/mp4"),
                                null
                            )
                            onComplete(outputFile)
                        }

                        override fun onError(
                            comp: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            progressHandler.removeCallbacks(progressRunnable)
                            Log.e(TAG, "Media3 merge failed: ${exportException.message}", exportException)
                            onError("Media3 merge failed: ${exportException.message}")
                        }
                    })
                    .build()

                transformerInstance = transformer
                progressHandler.post(progressRunnable)
                transformer.start(composition, outputFile.absolutePath)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting Media3 Transformer: ${e.message}", e)
                onError("Failed to start Media3 merge: ${e.message}")
            }
        }
    }
}
