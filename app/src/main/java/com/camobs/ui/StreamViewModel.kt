package com.camobs.ui

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.camobs.model.CameraFacing
import com.camobs.model.LatencyMode
import com.camobs.model.Resolution
import com.camobs.model.StreamConfig
import com.camobs.model.StreamState
import com.camobs.model.StreamStats
import com.camobs.model.VideoCodec
import com.camobs.streaming.StreamingEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val statsChannel = Channel<StreamStats>(Channel.CONFLATED)
    private var streamingEngine: StreamingEngine? = null

    // UI State
    private val _state = MutableLiveData<StreamState>(StreamState.IDLE)
    val state: LiveData<StreamState> = _state

    private val _stats = MutableLiveData<StreamStats>(StreamStats())
    val stats: LiveData<StreamStats> = _stats

    private val _config = MutableLiveData<StreamConfig>(StreamConfig.default())
    val config: LiveData<StreamConfig> = _config

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // PreviewView reference (set by UI)
    private var previewView: androidx.camera.view.PreviewView? = null

    fun setPreviewView(previewView: androidx.camera.view.PreviewView) {
        this.previewView = previewView
    }

    fun startStream() {
        val currentConfig = _config.value ?: StreamConfig.default()
        _errorMessage.value = null
        _state.value = StreamState.CONNECTING

        viewModelScope.launch {
            streamingEngine = StreamingEngine(getApplication(), currentConfig, statsChannel)
            streamingEngine?.onStateChange = { newState ->
                _state.postValue(newState)
            }
            streamingEngine?.onError = { error ->
                _errorMessage.postValue(error)
                _state.postValue(StreamState.ERROR)
            }

            val preview = previewView ?: return@launch
            val success = streamingEngine?.start(preview) ?: false

            if (!success) {
                _state.postValue(StreamState.ERROR)
            }
        }

        // Collect stats
        viewModelScope.launch {
            statsChannel.consumeEach { stats ->
                _stats.postValue(stats)
            }
        }
    }

    fun stopStream() {
        viewModelScope.launch {
            _state.value = StreamState.STOPPING
            streamingEngine?.stop()
            streamingEngine = null
            _state.value = StreamState.IDLE
            _stats.value = StreamStats()
        }
    }

    fun pauseStream() {
        viewModelScope.launch {
            streamingEngine?.pause()
        }
    }

    fun resumeStream() {
        viewModelScope.launch {
            streamingEngine?.resume()
        }
    }

    fun updateConfig(newConfig: StreamConfig) {
        _config.value = newConfig
        // Most changes require restart
        if (_state.value == StreamState.STREAMING) {
            // Could implement hot-swap for bitrate, but restart for resolution/fps
        }
    }

    fun updateSrtUrl(url: String) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(srtUrl = url)
    }

    fun updateResolution(resolution: Resolution) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(resolution = resolution)
    }

    fun updateFps(fps: Int) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(fps = fps)
    }

    fun updateBitrate(bitrateKbps: Int) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(bitrateKbps = bitrateKbps)
    }

    fun updateCameraFacing(facing: CameraFacing) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(cameraFacing = facing)
    }

    fun updateVideoCodec(codec: VideoCodec) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(videoCodec = codec)
    }

    fun updateLatencyMode(mode: LatencyMode) {
        val current = _config.value ?: StreamConfig.default()
        _config.value = current.copy(latencyMode = mode)
    }

    override fun onCleared() {
        streamingEngine?.stop()
        super.onCleared()
    }
}