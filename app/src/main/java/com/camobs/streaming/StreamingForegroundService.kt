package com.camobs.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import com.camobs.MainActivity
import com.camobs.R
import com.camobs.model.StreamConfig
import com.camobs.model.StreamState
import com.camobs.model.StreamStats
import kotlinx.coroutines.*

/**
 * Foreground Service for continuous streaming with camera/microphone access
 */
class StreamingForegroundService : Service() {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var streamingEngine: StreamingEngine? = null
    private var currentConfig: StreamConfig = StreamConfig.default()
    private val statsChannel = Channel<StreamStats>(Channel.CONFLATED)
    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            "START_STREAM" -> {
                val config = intent.getParcelableExtra<StreamConfig>("config") ?: StreamConfig.default()
                startStreaming(config)
            }
            "STOP_STREAM" -> stopStreaming()
            "PAUSE_STREAM" -> pauseStreaming()
            "RESUME_STREAM" -> resumeStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming(config: StreamConfig) {
        currentConfig = config
        streamingEngine = StreamingEngine(this, config, statsChannel)

        // Start stats monitoring
        statsJob = lifecycleScope.launch {
            statsChannel.consumeEach { stats ->
                updateNotification(stats)
                // Broadcast stats to UI if needed
            }
        }

        // Start streaming (needs PreviewView - use dummy for service)
        // For service-only streaming, we'd use a virtual display or SurfaceTexture
        // This is a simplified version - full implementation needs a Surface provider
        lifecycleScope.launch {
            // In service context, we'd create a virtual display or use MediaProjection
            // For now, this is a placeholder
        }
    }

    private fun stopStreaming() {
        statsJob?.cancel()
        streamingEngine?.stop()
        stopForeground(true)
        stopSelf()
    }

    private fun pauseStreaming() {
        streamingEngine?.pause()
    }

    private fun resumeStreaming() {
        streamingEngine?.resume()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "streaming_channel",
                "CamOBS Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones de estado de streaming"
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(stats: StreamStats) {
        val notification = buildNotification(stats)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun buildNotification(stats: StreamStats): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when {
            stats.isStreaming -> "🔴 Streaming ${stats.currentBitrateKbps.toInt()} Kbps | ${stats.fps.toInt()} fps | RTT ${stats.rttMs}ms"
            else -> "⏸️ Pausado"
        }

        return NotificationCompat.Builder(this, "streaming_channel")
            .setContentTitle("CamOBS Streaming")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopStreaming()
        super.onDestroy()
    }
}