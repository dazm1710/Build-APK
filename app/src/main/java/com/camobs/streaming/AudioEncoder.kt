package com.camobs.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.nio.ByteBuffer
import android.util.Log
import com.camobs.model.StreamConfig
import com.camobs.model.StreamStats
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio encoder using MediaCodec (AAC-LC)
 */
class AudioEncoder(private val config: StreamConfig, private val statsChannel: Channel<StreamStats>) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    private var mediaCodec: MediaCodec? = null
    private var isRunning = AtomicBoolean(false)
    private var presentationTimeUs = 0L
    private val frameSize = 1024 // AAC frame size

    var onEncodedData: ((ByteArray, Long) -> Unit)? = null

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return false

        return try {
            mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")

            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateKbps * 1000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSize * 4)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            launch { encoderLoop() }
            true
        } catch (e: Exception) {
            Log.e("AudioEncoder", "Failed to start", e)
            isRunning.set(false)
            mediaCodec?.release()
            mediaCodec = null
            false
        }
    }

    fun encode(inputBuffer: ByteBuffer, ptsUs: Long) {
        if (!isRunning.get()) return

        try {
            val inputIndex = mediaCodec?.dequeueInputBuffer(0) ?: return
            if (inputIndex >= 0) {
                val codecBuffer = mediaCodec?.getInputBuffer(inputIndex)
                codecBuffer?.clear()
                codecBuffer?.put(inputBuffer)
                mediaCodec?.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w("AudioEncoder", "Encode error", e)
        }
    }

    private suspend fun encoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        while (isRunning.get()) {
            val outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: continue

            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d("AudioEncoder", "Output format: ${mediaCodec?.outputFormat}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)

                            // Remove ADTS header if present, or keep for TS muxing
                            onEncodedData?.invoke(data, bufferInfo.presentationTimeUs)
                        }
                        mediaCodec?.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        job.cancel()
        mediaCodec?.signalEndOfInputStream()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }
}