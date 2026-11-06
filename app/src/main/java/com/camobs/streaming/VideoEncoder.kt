package com.camobs.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.nio.ByteBuffer
import android.util.Log
import android.util.Size
import com.camobs.model.LatencyMode
import com.camobs.model.StreamConfig
import com.camobs.model.StreamStats
import com.camobs.model.VideoCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Video encoder using MediaCodec (hardware accelerated H.264/H.265)
 * Outputs Annex B NAL units ready for SRT MPEG-TS muxing
 */
class VideoEncoder(private val config: StreamConfig, private val statsChannel: Channel<StreamStats>) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    private var mediaCodec: MediaCodec? = null
    private var isRunning = AtomicBoolean(false)
    private val presentationTimeUs = AtomicLong(0)
    private val frameCount = AtomicLong(0)
    private val startTimeMs = AtomicLong(System.currentTimeMillis())

    // SRT output callback
    private var onEncodedData: ((ByteArray, Long) -> Unit)? = null

    fun setOnEncodedData(callback: (ByteArray, Long) -> Unit) {
        onEncodedData = callback
    }

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return false

        val mimeType = config.videoCodec.mimeType
        val width = config.resolution.width
        val height = config.resolution.height
        val bitrate = config.bitrateKbps * 1000
        val fps = config.fps
        val keyframeInterval = config.keyframeIntervalSeconds

        return try {
            mediaCodec = MediaCodec.createEncoderByType(mimeType)

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeInterval)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)

            // Latency tuning
            when (config.latencyMode) {
                LatencyMode.ULTRA_LOW -> {
                    format.setInteger("priority", 1) // Realtime priority
                    // Request low latency tuning if available
                }
                LatencyMode.LOW -> {
                    format.setInteger("priority", 0)
                }
                LatencyMode.BALANCED -> {}
            }

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            launch { encoderLoop() }
            launch { statsLoop() }

            true
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to start encoder", e)
            isRunning.set(false)
            mediaCodec?.release()
            mediaCodec = null
            false
        }
    }

    fun getInputSurface(): android.view.Surface? {
        return mediaCodec?.createInputSurface()
    }

    private suspend fun encoderLoop() {
        val timeoutUs = 10000L // 10ms timeout

        while (isRunning.get()) {
            val bufferIndex = mediaCodec?.dequeueOutputBuffer(MediaCodec.BufferInfo(), timeoutUs) ?: continue

            when (bufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = mediaCodec?.outputFormat
                    Log.d("VideoEncoder", "Output format changed: $newFormat")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                else -> {
                    if (bufferIndex >= 0) {
                        val bufferInfo = MediaCodec.BufferInfo()
                        val outputBuffer = mediaCodec?.getOutputBuffer(bufferIndex)

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Convert to Annex B NAL units
                            val nals = extractAnnexBNals(outputBuffer, bufferInfo)
                            nals.forEach { nal ->
                                onEncodedData?.invoke(nal, bufferInfo.presentationTimeUs)
                            }

                            frameCount.incrementAndGet()
                            presentationTimeUs.set(bufferInfo.presentationTimeUs)
                        }

                        mediaCodec?.releaseOutputBuffer(bufferIndex, false)
                    }
                }
            }
        }
    }

    private fun extractAnnexBNals(buffer: ByteBuffer, info: MediaCodec.BufferInfo): List<ByteArray> {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val data = ByteArray(info.size)
        buffer.get(data)

        // Find NAL unit boundaries (start codes: 0x00 0x00 0x01 or 0x00 0x00 0x00 0x01)
        val nals = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size - 4) {
            // Check for start code
            val isStartCode = (data[i] == 0 && data[i+1] == 0 &&
                ((data[i+2] == 1) || (data[i+2] == 0 && data[i+3] == 1)))

            if (isStartCode) {
                val startCodeLen = if (data[i+2] == 1) 3 else 4
                val nalStart = i + startCodeLen
                var nalEnd = nalStart

                // Find next start code
                while (nalEnd < data.size - 4) {
                    val nextIsStart = (data[nalEnd] == 0 && data[nalEnd+1] == 0 &&
                        ((data[nalEnd+2] == 1) || (data[nalEnd+2] == 0 && data[nalEnd+3] == 1)))
                    if (nextIsStart) break
                    nalEnd++
                }

                val nalData = data.copyOfRange(nalStart, nalEnd)
                if (nalData.isNotEmpty()) {
                    nals.add(nalData)
                }
                i = nalEnd
            } else {
                i++
            }
        }

        return nals
    }

    private fun statsLoop() = launch {
        while (isRunning.get()) {
            delay(1000)
            val elapsed = System.currentTimeMillis() - startTimeMs.get()
            if (elapsed > 0) {
                val currentFps = (frameCount.get() * 1000.0) / elapsed
                val currentBitrate = 0.0 // Calculated from bytes sent in SRT layer

                try {
                    statsChannel.send(
                        StreamStats(
                            fps = currentFps,
                            isStreaming = true,
                            encodingTimeMs = 0
                        )
                    )
                } catch (e: Exception) {
                    Log.w("VideoEncoder", "Stats channel closed")
                }
            }
        }
    }

    fun signalEndOfStream() {
        mediaCodec?.signalEndOfInputStream()
    }

    fun stop() {
        isRunning.set(false)
        job.cancel()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}