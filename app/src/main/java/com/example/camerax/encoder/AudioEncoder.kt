package com.example.camerax.encoder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioEncoder"
private const val MIME_TYPE = "audio/mp4a-latm" // AAC
private const val SAMPLE_RATE = 44100
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val BIT_RATE = 128000 // 128 kbps AAC

/**
 * Low-latency AAC hardware audio encoder.
 * Captures microphone PCM audio via [AudioRecord] on a dedicated thread and streams ADTS AAC packets.
 */
class AudioEncoder {

    var onAudioData: ((data: ByteArray, presentationUs: Long) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var recordThread: Thread? = null
    private var outputThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun initialize() {
        release()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("AudioRecord initialization failed")
                return
            }

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
            codec = mediaCodec
            audioRecord?.startRecording()
            isRunning.set(true)

            // Thread 1: Mic PCM Reader -> Codec Input Queue
            recordThread = Thread({ runRecordLoop(bufferSize) }, "audio-record-loop").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }

            // Thread 2: Codec Output Queue -> ADTS Packetizer
            outputThread = Thread({ runOutputLoop() }, "audio-output-loop").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }

            Log.i(TAG, "AudioEncoder initialized (44.1kHz Stereo AAC)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioEncoder: ${e.message}", e)
            onError?.invoke("AudioEncoder init failed: ${e.message}")
        }
    }

    fun release() {
        if (!isRunning.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        recordThread?.join(300)
        recordThread = null

        outputThread?.join(300)
        outputThread = null

        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        Log.i(TAG, "AudioEncoder released")
    }

    private fun runRecordLoop(bufferSize: Int) {
        val pcmBuffer = ByteArray(bufferSize)
        while (isRunning.get()) {
            val record = audioRecord ?: break
            val readBytes = record.read(pcmBuffer, 0, bufferSize)
            if (readBytes > 0) {
                val c = codec ?: break
                try {
                    val inputIndex = c.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = c.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, readBytes)
                            val ptsUs = System.nanoTime() / 1000L
                            c.queueInputBuffer(inputIndex, 0, readBytes, ptsUs, 0)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w(TAG, "Record loop error: ${e.message}")
                }
            }
        }
    }

    private fun runOutputLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val c = codec ?: break
            try {
                val outputIndex = c.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex >= 0) {
                    val buffer = c.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                        val pcmData = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(pcmData)

                        // Prepend ADTS header to AAC packet
                        val adtsPacket = ByteArray(7 + pcmData.size)
                        addAdtsPacketHeader(adtsPacket, adtsPacket.size)
                        System.arraycopy(pcmData, 0, adtsPacket, 7, pcmData.size)

                        onAudioData?.invoke(adtsPacket, info.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.w(TAG, "Audio output loop error: ${e.message}")
            }
        }
    }

    private fun addAdtsPacketHeader(packet: ByteArray, packetLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44.1kHz
        val chanCfg = 2 // Stereo
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }
}
