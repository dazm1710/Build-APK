package com.camobs.model

import androidx.camera.core.CameraSelector
import kotlinx.serialization.Serializable

@Serializable
data class StreamConfig(
    val srtUrl: String = "srt://192.168.1.100:8888?mode=caller",
    val resolution: Resolution = Resolution.HD_720P,
    val fps: Int = 30,
    val bitrateKbps: Int = 4000,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val keyframeIntervalSeconds: Int = 2,
    val latencyMode: LatencyMode = LatencyMode.ULTRA_LOW,
    val enableAudio: Boolean = true,
    val audioBitrateKbps: Int = 128
) {
    fun toSrtUri(): String = srtUrl

    companion object {
        fun default(): StreamConfig = StreamConfig()
    }
}

enum class Resolution(val width: Int, val height: Int, val label: String) {
    HD_480P(640, 480, "480p (640×480)"),
    HD_720P(1280, 720, "720p (1280×720)"),
    HD_1080P(1920, 1080, "1080p (1920×1080)"),
    HD_4K(3840, 2160, "4K (3840×2160)");

    fun toSize() = android.util.Size(width, height)
    fun cameraSelector(): Int = when (this) {
        HD_480P -> CameraSelector.DEFAULT_BACK_CAMERA
        HD_720P -> CameraSelector.DEFAULT_BACK_CAMERA
        HD_1080P -> CameraSelector.DEFAULT_BACK_CAMERA
        HD_4K -> CameraSelector.DEFAULT_BACK_CAMERA
    }
}

enum class CameraFacing(val label: String, val lensFacing: Int) {
    FRONT("Frontal", CameraSelector.LENS_FACING_FRONT),
    BACK("Trasera", CameraSelector.LENS_FACING_BACK),
    EXTERNAL("Externa", CameraSelector.LENS_FACING_EXTERNAL);

    fun toCameraSelector() = CameraSelector.Builder().requireLensFacing(lensFacing).build()
}

enum class VideoCodec(val mimeType: String, val label: String) {
    H264("video/avc", "H.264 (AVC)"),
    H265("video/hevc", "H.265 (HEVC)");
}

enum class LatencyMode(val bufferSize: Int, val label: String) {
    ULTRA_LOW(10, "Ultra-baja (~50-100ms)"),
    LOW(50, "Baja (~100-200ms)"),
    BALANCED(200, "Equilibrado (~300-500ms)");
}

@Serializable
data class StreamStats(
    val bytesSent: Long = 0,
    val packetsSent: Long = 0,
    val packetsLost: Long = 0,
    val rttMs: Long = 0,
    val currentBitrateKbps: Double = 0.0,
    val fps: Double = 0.0,
    val encodingTimeMs: Long = 0,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null
)