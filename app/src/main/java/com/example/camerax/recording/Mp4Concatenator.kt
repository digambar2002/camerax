package com.example.camerax.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "Mp4Concatenator"

/**
 * Concatenates multiple MP4 files into a single continuous MP4 using
 * [MediaExtractor] + [MediaMuxer].
 *
 * This approach copies raw encoded samples (H.264 / AAC) without re-encoding,
 * making it extremely fast and reliable across all devices.
 */
object Mp4Concatenator {

    /**
     * Concatenate [inputFiles] into [outputFile].
     *
     * @param context       Android context for MediaStore scanning
     * @param inputFiles    Ordered list of MP4 segment files to concatenate
     * @param outputFile    Destination file for the merged result
     * @param onProgress    Callback with percentage 0..100
     * @param onComplete    Called with the output file on success
     * @param onError       Called with error message on failure
     */
    fun concatenate(
        context: Context,
        inputFiles: List<File>,
        outputFile: File,
        onProgress: (Int) -> Unit = {},
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                concatenateSync(inputFiles, outputFile, onProgress)

                // Clean up part files
                inputFiles.forEach { file ->
                    if (file.absolutePath != outputFile.absolutePath && file.exists()) {
                        file.delete()
                        Log.d(TAG, "Deleted temp segment: ${file.name}")
                    }
                }

                // Also clean up any gap image files in the same directory
                val dir = outputFile.parentFile
                if (dir != null && dir.exists()) {
                    val baseName = outputFile.nameWithoutExtension
                    dir.listFiles()?.forEach { f ->
                        if (f.name.contains(baseName) && f.name.contains("_gap") && f.name.endsWith(".jpg")) {
                            f.delete()
                            Log.d(TAG, "Deleted gap image: ${f.name}")
                        }
                    }
                }

                // Index in MediaStore
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )

                Log.i(TAG, "Successfully concatenated ${inputFiles.size} segments into: ${outputFile.name} (${outputFile.length()} bytes)")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(100)
                    onComplete(outputFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Concatenation failed: ${e.message}", e)
                // Fallback: index individual clips so user doesn't lose footage
                inputFiles.forEach { file ->
                    if (file.exists() && file.length() > 0) {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onError("Concatenation failed: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Synchronous concatenation using MediaExtractor + MediaMuxer.
     * Copies raw encoded samples from each input file sequentially.
     */
    private fun concatenateSync(
        inputFiles: List<File>,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            throw IllegalArgumentException("No valid input files to concatenate")
        }

        // If only one file, just rename/copy it
        if (validFiles.size == 1) {
            val single = validFiles[0]
            if (single.absolutePath != outputFile.absolutePath) {
                single.copyTo(outputFile, overwrite = true)
            }
            onProgress(100)
            return
        }

        // Determine output format from the first file
        val firstExtractor = MediaExtractor()
        firstExtractor.setDataSource(validFiles[0].absolutePath)

        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until firstExtractor.trackCount) {
            val format = firstExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoFormat == null) {
                videoFormat = format
            } else if (mime.startsWith("audio/") && audioFormat == null) {
                audioFormat = format
            }
        }
        firstExtractor.release()

        if (videoFormat == null) {
            throw IllegalArgumentException("No video track found in first segment")
        }

        // Create muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            try {
                val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                muxer.setOrientationHint(rotation)
            } catch (_: Exception) {}
        }

        val muxerVideoTrack = muxer.addTrack(videoFormat)
        val muxerAudioTrack = if (audioFormat != null) muxer.addTrack(audioFormat) else -1

        muxer.start()

        val bufferSize = 1024 * 1024 // 1MB buffer
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        var videoTimeOffsetUs: Long = 0
        var audioTimeOffsetUs: Long = 0
        var lastVideoTimestampUs: Long = 0
        var lastAudioTimestampUs: Long = 0

        val totalFiles = validFiles.size

        for ((fileIndex, file) in validFiles.withIndex()) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)

                // Find video and audio track indices in this particular file
                var fileVideoTrack = -1
                var fileAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && fileVideoTrack == -1) {
                        fileVideoTrack = i
                    } else if (mime.startsWith("audio/") && fileAudioTrack == -1) {
                        fileAudioTrack = i
                    }
                }

                if (fileVideoTrack >= 0) extractor.selectTrack(fileVideoTrack)
                if (fileAudioTrack >= 0 && muxerAudioTrack >= 0) extractor.selectTrack(fileAudioTrack)

                var maxVideoTs: Long = 0
                var maxAudioTs: Long = 0

                // Interleaved sample copying preserves proper chronological order for MediaMuxer
                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break

                    val isVideo = trackIndex == fileVideoTrack
                    val isAudio = trackIndex == fileAudioTrack

                    val targetMuxerTrack = when {
                        isVideo -> muxerVideoTrack
                        isAudio -> muxerAudioTrack
                        else -> -1
                    }

                    if (targetMuxerTrack >= 0) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize > 0) {
                            val sampleTime = extractor.sampleTime
                            val offset = if (isVideo) videoTimeOffsetUs else audioTimeOffsetUs

                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = sampleTime + offset
                            bufferInfo.flags = extractor.sampleFlags

                            if (isVideo && bufferInfo.presentationTimeUs > maxVideoTs) {
                                maxVideoTs = bufferInfo.presentationTimeUs
                            } else if (isAudio && bufferInfo.presentationTimeUs > maxAudioTs) {
                                maxAudioTs = bufferInfo.presentationTimeUs
                            }

                            muxer.writeSampleData(targetMuxerTrack, buffer, bufferInfo)
                        }
                    }

                    if (!extractor.advance()) break
                }

                if (fileVideoTrack >= 0) extractor.unselectTrack(fileVideoTrack)
                if (fileAudioTrack >= 0 && muxerAudioTrack >= 0) extractor.unselectTrack(fileAudioTrack)

                // Synchronize video and audio offsets to common progression
                val frameGapUs = 33_333L
                val segmentEndUs = maxOf(maxVideoTs, maxAudioTs)
                if (segmentEndUs > 0) {
                    lastVideoTimestampUs = maxVideoTs
                    lastAudioTimestampUs = maxAudioTs
                    videoTimeOffsetUs = segmentEndUs + frameGapUs
                    audioTimeOffsetUs = segmentEndUs + frameGapUs
                }

                // Report progress
                val percent = ((fileIndex + 1) * 100) / totalFiles
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(percent.coerceIn(0, 99))
                }

                Log.d(TAG, "Processed segment ${fileIndex + 1}/$totalFiles: ${file.name} " +
                        "(videoOffset=${videoTimeOffsetUs}us, audioOffset=${audioTimeOffsetUs}us)")

            } finally {
                extractor.release()
            }
        }

        muxer.stop()
        muxer.release()

        Log.i(TAG, "Muxer finalized. Total video duration: ${lastVideoTimestampUs / 1000}ms, " +
                "audio duration: ${lastAudioTimestampUs / 1000}ms")
    }
}
