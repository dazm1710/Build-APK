package com.camobs.streaming

import android.util.Log
import com.camobs.model.StreamConfig
import com.camobs.model.StreamStats
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SRT Client wrapper using libsrt-android (JNI bindings)
 * Handles MPEG-TS muxing and SRT protocol
 */
class SrtClient(private val config: StreamConfig, private val statsChannel: Channel<StreamStats>) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private var srtSocket: Long = 0 // Native pointer
    private var isConnected = AtomicBoolean(false)
    private var isRunning = AtomicBoolean(false)

    // MPEG-TS muxing state
    private val pcrPid = 0x1FFF
    private val videoPid = 0x100
    private val audioPid = 0x101
    private val pmtPid = 0x1000
    private val patPid = 0x0000
    private var continuityCounter = IntArray(8192) { 0 }
    private var firstVideoFrame = true
    private var firstAudioFrame = true

    // Stats
    private val bytesSent = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    private val packetsLost = AtomicLong(0)
    private val startTimeMs = AtomicLong(System.currentTimeMillis())

    // Load native library
    init {
        System.loadLibrary("srt")
    }

    external fun nativeSrtCreateSocket(): Long
    external fun nativeSrtConnect(socket: Long, ip: String, port: Int, mode: Int): Int
    external fun nativeSrtSend(socket: Long, data: ByteArray, len: Int, flags: Int): Int
    external fun nativeSrtClose(socket: Long): Int
    external fun nativeSrtGetStats(socket: Long): SrtStats
    external fun nativeSrtSetOption(socket: Long, optName: Int, value: Any): Int

    // SRT socket options
    companion object {
        const val SRTO_RCVLATENCY = 0x0001
        const val SRTO_PEERLATENCY = 0x0002
        const val SRTO_PACKETFILTER = 0x0003
        const val SRTO_LOSSMAX = 0x0004
        const val SRTO_MINVERSION = 0x0005
        const val SRTO_STREAMID = 0x0006
        const val SRTO_CONNTIMEO = 0x0007
        const val SRTO_TLPKTDROP = 0x0008
        const val SRTO_SNDBUF = 0x0009
        const val SRTO_RCVBUF = 0x000A
        const val SRTO_RCVSYN = 0x000B
        const val SRTO_SENDER = 0x000C
        const val SRTO_MSS = 0x000D
        const val SRTO_TSBPDMODE = 0x000E
        const val SRTO_TSBPDDELAY = 0x000F
        const val SRTO_TSBPDHIST = 0x0010
        const val SRTO_SENDERBW = 0x0011
        const val SRTO_RECVBW = 0x0012
    }

    data class SrtStats(
        val bytesSent: Long,
        val packetsSent: Long,
        val packetsLost: Long,
        val rttMs: Long,
        val mbpsSendRate: Double
    )

    fun connect(): Boolean {
        if (isConnected.get()) return true

        return try {
            // Parse SRT URL: srt://host:port?mode=caller
            val uri = android.net.Uri.parse(config.srtUrl)
            val host = uri.host ?: throw IllegalArgumentException("Invalid SRT URL: no host")
            val port = uri.port.ifNegative { 8888 }
            val mode = uri.getQueryParameter("mode")?.lowercase() ?: "caller"

            srtSocket = nativeSrtCreateSocket()
            if (srtSocket == 0L) throw RuntimeException("Failed to create SRT socket")

            // Configure socket options for ultra-low latency
            configureSocketOptions(mode)

            val connectMode = when (mode) {
                "caller" -> 1 // SRTO_SENDER = 1
                "listener" -> 0
                "rendezvous" -> 2
                else -> 1
            }

            val result = nativeSrtConnect(srtSocket, host, port, connectMode)
            if (result != 0) {
                throw RuntimeException("SRT connect failed: $result")
            }

            isConnected.set(true)
            isRunning.set(true)
            launch { statsLoop() }
            launch { keepAliveLoop() }

            Log.d("SrtClient", "Connected to $host:$port as $mode")
            true
        } catch (e: Exception) {
            Log.e("SrtClient", "Connection failed", e)
            disconnect()
            false
        }
    }

    private fun configureSocketOptions(mode: String) {
        // Ultra-low latency settings
        val latencyMs = when (config.latencyMode) {
            com.camobs.model.LatencyMode.ULTRA_LOW -> 20
            com.camobs.model.LatencyMode.LOW -> 100
            com.camobs.model.LatencyMode.BALANCED -> 200
        }

        nativeSrtSetOption(srtSocket, SRTO_RCVLATENCY, latencyMs)
        nativeSrtSetOption(srtSocket, SRTO_PEERLATENCY, latencyMs)
        nativeSrtSetOption(srtSocket, SRTO_TLPKTDROP, 1) // Drop late packets
        nativeSrtSetOption(srtSocket, SRTO_SNDBUF, 1024 * 1024) // 1MB send buffer
        nativeSrtSetOption(srtSocket, SRTO_RCVBUF, 1024 * 1024)
        nativeSrtSetOption(srtSocket, SRTO_CONNTIMEO, 5000) // 5s connection timeout

        if (mode == "caller") {
            nativeSrtSetOption(srtSocket, SRTO_SENDER, 1)
        }
    }

    fun sendVideoFrame(nalData: ByteArray, ptsUs: Long): Boolean {
        if (!isConnected.get()) return false

        try {
            // Wrap NAL in MPEG-TS packets
            val tsPackets = muxVideoToTs(nalData, ptsUs, firstVideoFrame)
            firstVideoFrame = false

            for (packet in tsPackets) {
                val result = nativeSrtSend(srtSocket, packet, packet.size, 0)
                if (result < 0) {
                    Log.w("SrtClient", "SRT send failed: $result")
                    return false
                }
                bytesSent.addAndGet(result.toLong())
                packetsSent.incrementAndGet()
            }
            return true
        } catch (e: Exception) {
            Log.e("SrtClient", "Send video frame failed", e)
            return false
        }
    }

    fun sendAudioFrame(audioData: ByteArray, ptsUs: Long): Boolean {
        if (!isConnected.get() || !config.enableAudio) return false

        try {
            val tsPackets = muxAudioToTs(audioData, ptsUs, firstAudioFrame)
            firstAudioFrame = false

            for (packet in tsPackets) {
                val result = nativeSrtSend(srtSocket, packet, packet.size, 0)
                if (result < 0) return false
                bytesSent.addAndGet(result.toLong())
                packetsSent.incrementAndGet()
            }
            return true
        } catch (e: Exception) {
            Log.e("SrtClient", "Send audio frame failed", e)
            return false
        }
    }

    private fun muxVideoToTs(nalData: ByteArray, ptsUs: Long, isFirst: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val payload = buildVideoPesPacket(nalData, ptsUs, isFirst)

        // Split PES into 188-byte TS packets
        var offset = 0
        var firstPacket = true
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val payloadLen = minOf(184, remaining) // 188 - 4 byte header
            val tsPacket = ByteArray(188)

            // TS Header
            tsPacket[0] = 0x47 // Sync byte
            tsPacket[1] = (if (firstPacket) 0x40 else 0x00).toByte() // PUSI on first
            tsPacket[1] = (tsPacket[1].toInt() or ((videoPid shr 8) and 0x1F)).toByte()
            tsPacket[2] = (videoPid and 0xFF).toByte()
            tsPacket[3] = (0x10 or continuityCounter[videoPid]).toByte() // No adaptation, payload only
            continuityCounter[videoPid] = (continuityCounter[videoPid] + 1) % 16

            // Payload
            payload.copyInto(tsPacket, 4, offset, offset + payloadLen)

            // Padding if needed
            if (payloadLen < 184) {
                for (i in (4 + payloadLen) until 188) {
                    tsPacket[i] = 0xFF.toByte()
                }
            }

            packets.add(tsPacket)
            offset += payloadLen
            firstPacket = false
        }

        return packets
    }

    private fun buildVideoPesPacket(nalData: ByteArray, ptsUs: Long, isFirst: Boolean): ByteArray {
        val pesHeader = ByteArray(19) // Minimum PES header with PTS
        var pos = 0

        // PES start code: 0x00 0x00 0x01 0xE0 (video stream)
        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x01
        pesHeader[pos++] = 0xE0

        // PES packet length (0 = unbounded)
        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x00

        // Flags
        pesHeader[pos++] = 0x80.toByte() // PTS_DTS_flags = 10 (PTS only)
        pesHeader[pos++] = 0x80.toByte() // PES_header_data_length = 5 (PTS) + 3 = 8? Let's use 10

        // PTS (33 bits, 90kHz clock)
        val pts90k = (ptsUs * 90 / 1000).toLong()
        pesHeader[pos++] = 0x21.toByte() // '0010' + PTS[32..30] + '1'
        pesHeader[pos++] = ((pts90k shr 25) and 0xFF).toByte()
        pesHeader[pos++] = ((pts90k shr 17) and 0xFF).toByte() | 0x01
        pesHeader[pos++] = ((pts90k shr 9) and 0xFF).toByte()
        pesHeader[pos++] = ((pts90k shr 1) and 0xFF).toByte() | 0x01

        // Add NAL start code prefix
        val nalWithStartCode = ByteArray(nalData.size + 4)
        nalWithStartCode[0] = 0x00
        nalWithStartCode[1] = 0x00
        nalWithStartCode[2] = 0x00
        nalWithStartCode[3] = 0x01
        nalData.copyInto(nalWithStartCode, 4)

        return pesHeader + nalWithStartCode
    }

    private fun muxAudioToTs(audioData: ByteArray, ptsUs: Long, isFirst: Boolean): List<ByteArray> {
        // Simplified AAC ADTS -> TS muxing
        val packets = mutableListOf<ByteArray>()
        val payload = buildAudioPesPacket(audioData, ptsUs, isFirst)

        var offset = 0
        var firstPacket = true
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val payloadLen = minOf(184, remaining)
            val tsPacket = ByteArray(188)

            tsPacket[0] = 0x47
            tsPacket[1] = (if (firstPacket) 0x40 else 0x00).toByte()
            tsPacket[1] = (tsPacket[1].toInt() or ((audioPid shr 8) and 0x1F)).toByte()
            tsPacket[2] = (audioPid and 0xFF).toByte()
            tsPacket[3] = (0x10 or continuityCounter[audioPid]).toByte()
            continuityCounter[audioPid] = (continuityCounter[audioPid] + 1) % 16

            payload.copyInto(tsPacket, 4, offset, offset + payloadLen)
            if (payloadLen < 184) {
                for (i in (4 + payloadLen) until 188) tsPacket[i] = 0xFF.toByte()
            }

            packets.add(tsPacket)
            offset += payloadLen
            firstPacket = false
        }

        return packets
    }

    private fun buildAudioPesPacket(audioData: ByteArray, ptsUs: Long, isFirst: Boolean): ByteArray {
        val pesHeader = ByteArray(14) // PES with PTS only
        var pos = 0

        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x01
        pesHeader[pos++] = 0xC0 // Audio stream ID

        pesHeader[pos++] = 0x00
        pesHeader[pos++] = 0x00

        pesHeader[pos++] = 0x80.toByte() // PTS only
        pesHeader[pos++] = 0x05.toByte() // Header length = 5

        val pts90k = (ptsUs * 90 / 1000).toLong()
        pesHeader[pos++] = 0x21.toByte()
        pesHeader[pos++] = ((pts90k shr 25) and 0xFF).toByte()
        pesHeader[pos++] = ((pts90k shr 17) and 0xFF).toByte() | 0x01
        pesHeader[pos++] = ((pts90k shr 9) and 0xFF).toByte()
        pesHeader[pos++] = ((pts90k shr 1) and 0xFF).toByte() | 0x01

        return pesHeader + audioData
    }

    private fun statsLoop() = launch {
        while (isRunning.get()) {
            delay(2000)
            try {
                val nativeStats = nativeSrtGetStats(srtSocket)
                val elapsed = System.currentTimeMillis() - startTimeMs.get()
                val bitrateKbps = if (elapsed > 0) (bytesSent.get() * 8.0 / elapsed) else 0.0

                statsChannel.send(
                    StreamStats(
                        bytesSent = bytesSent.get(),
                        packetsSent = packetsSent.get(),
                        packetsLost = packetsLost.get(),
                        rttMs = nativeStats.rttMs,
                        currentBitrateKbps = bitrateKbps,
                        fps = 0.0, // Updated by VideoEncoder
                        isStreaming = isConnected.get()
                    )
                )

                packetsLost.set(nativeStats.packetsLost)
            } catch (e: Exception) {
                Log.w("SrtClient", "Stats error", e)
            }
        }
    }

    private fun keepAliveLoop() = launch {
        while (isRunning.get()) {
            delay(1000)
            // Send null packet to keep connection alive
            if (isConnected.get()) {
                try {
                    val nullPacket = ByteArray(188)
                    nullPacket[0] = 0x47
                    nullPacket[1] = 0x1F.toByte() // Null packet PID = 0x1FFF
                    nullPacket[2] = 0xFF.toByte()
                    nullPacket[3] = (0x10 or continuityCounter[pcrPid]).toByte()
                    continuityCounter[pcrPid] = (continuityCounter[pcrPid] + 1) % 16
                    for (i in 4 until 188) nullPacket[i] = 0xFF.toByte()

                    nativeSrtSend(srtSocket, nullPacket, 188, 0)
                } catch (e: Exception) {
                    Log.w("SrtClient", "Keep-alive failed")
                }
            }
        }
    }

    fun disconnect() {
        isRunning.set(false)
        isConnected.set(false)
        job.cancel()

        if (srtSocket != 0L) {
            nativeSrtClose(srtSocket)
            srtSocket = 0
        }
    }

    companion object {
        private const val TAG = "SrtClient"
    }
}