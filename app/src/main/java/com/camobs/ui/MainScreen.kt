package com.camobs.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.icons.Icons
import androidx.compose.material3.icons.filled.CameraFront
import androidx.compose.material3.icons.filled.CameraRear
import androidx.compose.material3.icons.filled.Close
import androidx.compose.material3.icons.filled.Info
import androidx.compose.material3.icons.filled.PlayArrow
import androidx.compose.material3.icons.filled.Settings
import androidx.compose.material3.icons.filled.Stop
import androidx.compose.material3.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: StreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CamOBSTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: StreamViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val state by viewModel.state.observeAsState(StreamState.IDLE)
    val stats by viewModel.stats.observeAsState(StreamStats())
    val config by viewModel.config.observeAsState(StreamConfig.default())
    val errorMessage by viewModel.errorMessage.observeAsState(null)

    // PreviewView for camera
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    viewModel.setPreviewView(previewView)

    // Show error snackbar
    errorMessage?.let { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
        viewModel.errorMessage.value = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("CamOBS", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    IconButton(onClick = { /* Settings */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Camera Preview
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
                update = { it.updateLayoutParams(previewView.layoutParams) }
            )

            // Stats Overlay (top-right)
            if (state == StreamState.STREAMING) {
                StatsOverlay(stats = stats, modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                )
            }

            // Control Panel (bottom)
            ControlPanel(
                state = state,
                config = config,
                onStart = { viewModel.startStream() },
                onStop = { viewModel.stopStream() },
                onPause = { viewModel.pauseStream() },
                onResume = { viewModel.resumeStream() },
                onConfigChange = { viewModel.updateConfig(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun StatsOverlay(stats: StreamStats, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("🔴", "${stats.currentBitrateKbps.toInt()} Kbps")
                StatItem("▶", "${stats.fps.toInt()} fps")
                StatItem("⚡", "${stats.rttMs}ms")
            }
            if (stats.packetsLost > 0) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text("Pérdidas: ${stats.packetsLost}", color = Color.Red, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun StatItem(icon: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(icon, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ControlPanel(
    state: StreamState,
    config: StreamConfig,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConfigChange: (StreamConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                StatusIndicator(state)
                Text(
                    text = when (state) {
                        StreamState.IDLE -> "Listo"
                        StreamState.CONNECTING -> "Conectando..."
                        StreamState.STREAMING -> "🔴 EN VIVO"
                        StreamState.PAUSED -> "⏸️ Pausado"
                        StreamState.ERROR -> "❌ Error"
                        StreamState.STOPPING -> "Deteniendo..."
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        StreamState.STREAMING -> Color.Green
                        StreamState.ERROR -> Color.Red
                        StreamState.CONNECTING -> Color.Yellow
                        else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    }
                )
            )

            // SRT URL Input
            OutlinedTextField(
                value = config.srtUrl,
                onValueChange = { onConfigChange(config.copy(srtUrl = it)) },
                label = { Text("URL SRT") },
                placeholder = { Text("srt://192.168.1.100:8888?mode=caller") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Quick Settings Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Resolution dropdown
                ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                    // Simplified - using Text for now
                    Text("Res: ${config.resolution.label}", style = androidx.compose.material3.MaterialTheme.textStyle.bodySmall)
                }

                // FPS
                Text("FPS: ${config.fps}", style = androidx.compose.material3.MaterialTheme.textStyle.bodySmall)

                // Bitrate
                Text("${config.bitrateKbps} Kbps", style = androidx.compose.material3.MaterialTheme.textStyle.bodySmall)
            }

            // Bitrate Slider
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bitrate:", style = androidx.compose.material3.MaterialTheme.textStyle.bodySmall, modifier = Modifier.width(60.dp))
                Slider(
                    value = config.bitrateKbps.toFloat(),
                    onValueChange = { onConfigChange(config.copy(bitrateKbps = it.toInt())) },
                    valueRange = 500f..20000f,
                    steps = (20000 - 500) / 500,
                    modifier = Modifier.weight(1f)
                )
                Text("${config.bitrateKbps} Kbps", style = androidx.compose.material3.MaterialTheme.textStyle.bodySmall, modifier = Modifier.width(60.dp))
            }

            // Main Control Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    StreamState.IDLE, StreamState.ERROR -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Iniciar")
                            Text("INICIAR STREAM", fontWeight = FontWeight.Bold)
                        }
                    }
                    StreamState.STREAMING -> {
                        Button(
                            onClick = onPause,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color.Yellow.copy(alpha = 0.8f)
                            )
                        ) {
                            Text("PAUSAR", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Detener")
                            Text("DETENER", fontWeight = FontWeight.Bold)
                        }
                    }
                    StreamState.PAUSED -> {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color.Green
                            )
                        ) {
                            Text("REANUDAR", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            )
                        ) {
                            Text("DETENER", fontWeight = FontWeight.Bold)
                        }
                    }
                    StreamState.CONNECTING, StreamState.STOPPING -> {
                        CircularProgressIndicator(modifier = Modifier.weight(1f).height(56.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(state: StreamState) {
    val color = when (state) {
        StreamState.STREAMING -> Color.Green
        StreamState.CONNECTING -> Color.Yellow
        StreamState.ERROR -> Color.Red
        StreamState.PAUSED -> Color.Orange
        else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(12.dp)
    ) {
        drawCircle(color = color, radius = 6.dp)
    }
}

@Composable
fun CamOBSTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
        surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
        background = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}