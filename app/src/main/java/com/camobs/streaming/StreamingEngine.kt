package com.camobs.streaming

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.VideoCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.camobs.model.CameraFacing
import com.camobs.model.LatencyMode
import com.camobs.model.StreamConfig
import com.camobs.model.StreamStats
import com.camobs.model.VideoCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main streaming engine orchestrating CameraX, encoders, and SRT transport
 */
class StreamingEngine(
    private val context: Context,
    private val config: StreamConfig,
    private val statsChannel: Channel<StreamStats>
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val audioExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null

    private val videoEncoder = VideoEncoder(config, statsChannel)
    private val audioEncoder = AudioEncoder(config, statsChannel)
    private val srtClient = SrtClient(config, statsChannel)

    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Audio capture
    private var audioRecord: AudioRecord? = null
    private val audioBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder())

    // Callbacks
    var onStateChange: ((StreamState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    enum class StreamState {
        IDLE, CONNECTING, STREAMING, PAUSED, ERROR, STOPPING
    }

    suspend fun start(previewView: PreviewView): Boolean {
        if (isStreaming.getAndSet(true)) return false

        this.previewView = previewView
        _setState(StreamState.CONNECTING)

        return try {
            // 1. Connect SRT
            if (!srtClient.connect()) {
                throw RuntimeException("SRT connection failed")
            }

            // 2. Start video encoder
            if (!videoEncoder.start()) {
                throw RuntimeException("Video encoder start failed")
            }

            // 3. Start audio encoder if enabled
            if (config.enableAudio) {
                if (!audioEncoder.start()) {
                    Log.w("StreamingEngine", "Audio encoder failed, continuing video-only")
                }
            }

            // 4. Setup camera
            if (!setupCamera()) {
                throw RuntimeException("Camera setup failed")
            }

            // 5. Start audio capture
            if (config.enableAudio) {
                startAudioCapture()
            }

            // Wire encoder outputs to SRT
            videoEncoder.setOnEncodedData { nalData, ptsUs ->
                if (!isPaused.get()) srtClient.sendVideoFrame(nalData, ptsUs)
            }

            audioEncoder.onEncodedData = { audioData, ptsUs ->
                if (!isPaused.get()) srtClient.sendAudioFrame(audioData, ptsUs)
            }

            _setState(StreamState.STREAMING)
            true
        } catch (e: Exception) {
            Log.e("StreamingEngine", "Start failed", e)
            _setState(StreamState.ERROR)
            onError?.invoke(e.message ?: "Unknown error")
            stop()
            false
        }
    }

    private fun setupCamera(): Boolean {
        try {
            cameraProvider = ProcessCameraProvider.getInstance(context).await()

            val preview = Preview.Builder()
                .setTargetResolution(config.resolution.toSize())
                .setTargetRotation(previewView?.display?.rotation ?: 0)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(config.resolution.toSize())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            // Use encoder's input surface for direct GPU-to-encoder path
            val encoderSurface = videoEncoder.getInputSurface()
            val videoCapture = if (encoderSurface != null) {
                VideoCapture.Builder()
                    .setTargetResolution(config.resolution.toSize())
                    .setVideoFrameRate(config.fps)
                    .setTargetRotation(previewView?.display?.rotation ?: 0)
                    .build()
            } else {
                null
            }

            val cameraSelector = config.cameraFacing.toCameraSelector()

            // Bind use cases
            camera = cameraProvider?.bindToLifecycle(
                (context as androidx.lifecycle.LifecycleOwner),
                cameraSelector,
                preview,
                imageAnalysis,
                videoCapture
            ) ?: return false

            preview.setSurfaceProvider(previewView?.surfaceProvider)

            // Feed frames to encoder via ImageAnalysis
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isStreaming.get() && !isPaused.get()) {
                    feedFrameToEncoder(imageProxy)
                }
                imageProxy.close()
            }

            // If using VideoCapture with encoder surface, also feed from there
            videoCapture?.surfaceProvider?.let { surfaceProvider ->
                // VideoCapture handles encoding internally when using Surface
                // We'd need MediaCodec callback approach instead
            }

            return true
        } catch (e: Exception) {
            Log.e("StreamingEngine", "Camera setup failed", e)
            return false
        }
    }

    private fun feedFrameToEncoder(image: ImageProxy) {
        // Convert YUV_420_888 to encoder surface via MediaCodec
        // This is the software path - for hardware path use VideoCapture with encoder surface
        val encoderSurface = videoEncoder.getInputSurface()
        if (encoderSurface != null) {
            // Draw image to encoder surface using OpenGL or RenderScript
            // For simplicity, we'll use the VideoCapture path in production
        }
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            48000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(4096)
        )

        audioRecord?.startRecording()

        audioExecutor.execute {
            while (isStreaming.get()) {
                val read = audioRecord?.read(audioBuffer, audioBuffer.capacity(), AudioRecord.READ_BLOCKING) ?: 0
                if (read > 0) {
                    audioBuffer.flip()
                    val ptsUs = System.currentTimeMillis() * 1000
                    audioEncoder.encode(audioBuffer, ptsUs)
                    audioBuffer.clear()
                }
            }
        }
    }

    suspend fun pause() {
        isPaused.set(true)
        _setState(StreamState.PAUSED)
    }

    suspend fun resume() {
        isPaused.set(false)
        _setState(StreamState.STREAMING)
    }

    suspend fun stop() {
        if (!isStreaming.getAndSet(false)) return
        _setState(StreamState.STOPPING)

        // Stop camera
        cameraProvider?.unbindAll()
        camera = null

        // Stop audio
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Stop encoders
        videoEncoder.stop()
        audioEncoder.stop()

        // Disconnect SRT
        srtClient.disconnect()

        cameraExecutor.shutdown()
        audioExecutor.shutdown()

        _setState(StreamState.IDLE)
    }

    private fun _setState(state: StreamState) {
        launch { onStateChange?.invoke(state) }
    }

    fun getConfig(): StreamConfig = config

    fun updateConfig(newConfig: StreamConfig) {
        // For runtime config changes (bitrate, resolution, etc.)
        // Requires restart for most changes
    }
}