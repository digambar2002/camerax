package com.example.camerax.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.camerax.model.AppState
import com.example.camerax.model.CameraFacing
import com.example.camerax.model.Resolution
import com.example.camerax.model.StreamingState

// ---------------------------------------------------------------------------
// Colour palette — premium dark theme
// ---------------------------------------------------------------------------
private val Background    = Color(0xFF0A0A0F)
private val Surface1      = Color(0xFF13131A)
private val Surface2      = Color(0xFF1C1C28)
private val AccentCyan    = Color(0xFF00C8FF)
private val AccentCyanDim = Color(0xFF007A9C)
private val SuccessGreen  = Color(0xFF00E676)
private val ErrorRed      = Color(0xFFFF5252)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF888899)
private val ChipSelected  = Color(0xFF1E3A45)

/**
 * Full-screen Compose UI for the USB Webcam app.
 *
 * Structured in three vertical zones:
 *  1. **Status bar** — streaming state + client connection + USB info.
 *  2. **Camera preview** — live [PreviewView] filling available space.
 *  3. **Control panel** — camera/resolution/fps pickers + start/stop button.
 */
@Composable
fun MainScreen(
    state: AppState,
    @Suppress("UNUSED_PARAMETER") lifecycleOwner: LifecycleOwner,
    onPreviewViewReady: (PreviewView) -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSelectResolution: (Resolution) -> Unit,
    onSelectFps: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error messages in a Snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // -----------------------------------------------------------------
            // 1. Status Bar
            // -----------------------------------------------------------------
            StatusBar(state = state)

            // -----------------------------------------------------------------
            // 2. Live Camera Preview (fills all remaining vertical space)
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                CameraPreview(
                    onPreviewViewReady = onPreviewViewReady,
                    modifier = Modifier.fillMaxSize()
                )

                // Resolution + FPS badge overlay
                ResolutionBadge(
                    resolution = state.selectedResolution,
                    fps = state.selectedFps,
                    isStreaming = state.streamingState == StreamingState.STREAMING,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )

                // Camera switch button overlay
                if (state.permissionsGranted) {
                    IconButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Surface2.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Permission-denied overlay
                if (!state.permissionsGranted) {
                    PermissionOverlay()
                }
            }

            // -----------------------------------------------------------------
            // 3. Control Panel
            // -----------------------------------------------------------------
            ControlPanel(
                state = state,
                onStartStreaming = onStartStreaming,
                onStopStreaming = onStopStreaming,
                onSelectResolution = onSelectResolution,
                onSelectFps = onSelectFps
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ---------------------------------------------------------------------------
// Status Bar
// ---------------------------------------------------------------------------

@Composable
private fun StatusBar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Streaming state chip
        StreamingStateChip(state.streamingState)

        // Client connected indicator
        ClientIndicator(isConnected = state.isClientConnected)

        // USB informational chip
        UsbChip()
    }
}

@Composable
private fun StreamingStateChip(state: StreamingState) {
    val (label, color) = when (state) {
        StreamingState.STREAMING -> "● LIVE" to SuccessGreen
        StreamingState.IDLE      -> "○ IDLE" to TextSecondary
        StreamingState.ERROR     -> "✕ ERROR" to ErrorRed
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == StreamingState.STREAMING) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "blink"
    )

    Text(
        text = label,
        color = if (state == StreamingState.STREAMING) color.copy(alpha = alpha) else color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ClientIndicator(isConnected: Boolean) {
    val dotColor by animateColorAsState(
        targetValue = if (isConnected) SuccessGreen else TextSecondary,
        animationSpec = tween(300),
        label = "clientDot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isConnected) "Client" else "No Client",
            color = dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun UsbChip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Surface2)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Usb,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "USB",
            color = AccentCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Camera Preview
// ---------------------------------------------------------------------------

@Composable
private fun CameraPreview(
    onPreviewViewReady: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onPreviewViewReady(this)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ResolutionBadge(
    resolution: Resolution,
    fps: Int,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isStreaming) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Surface2.copy(alpha = 0.75f)
    ) {
        Text(
            text = "${resolution.label} · ${fps}fps",
            color = AccentCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PermissionOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Camera Permission Required",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Grant camera access to start streaming",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Control Panel
// ---------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    state: AppState,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onSelectResolution: (Resolution) -> Unit,
    onSelectFps: (Int) -> Unit
) {
    val isStreaming = state.streamingState == StreamingState.STREAMING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Resolution selector
            ControlSection(label = "RESOLUTION") {
                ResolutionSelector(
                    selected = state.selectedResolution,
                    enabled = !isStreaming,
                    onSelect = onSelectResolution
                )
            }

            // FPS selector
            ControlSection(label = "FRAME RATE") {
                FpsSelector(
                    selected = state.selectedFps,
                    enabled = !isStreaming,
                    onSelect = onSelectFps
                )
            }

            // Start / Stop button
            StreamButton(
                streamingState = state.streamingState,
                permissionsGranted = state.permissionsGranted,
                onStart = onStartStreaming,
                onStop = onStopStreaming
            )

            // ADB hint
            AdbHint(isStreaming = isStreaming)
        }
    }
}

@Composable
private fun ControlSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        content()
    }
}

@Composable
private fun ResolutionSelector(
    selected: Resolution,
    enabled: Boolean,
    onSelect: (Resolution) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Resolution.ALL.forEach { resolution ->
            val isSelected = selected == resolution
            FilterChip(
                selected = isSelected,
                onClick = { if (enabled) onSelect(resolution) },
                label = {
                    Text(
                        text = resolution.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyanDim,
                    selectedLabelColor = AccentCyan,
                    containerColor = Surface2,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun FpsSelector(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 60).forEach { fps ->
            val isSelected = selected == fps
            FilterChip(
                selected = isSelected,
                onClick = { if (enabled) onSelect(fps) },
                label = {
                    Text(
                        text = "${fps}fps",
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyanDim,
                    selectedLabelColor = AccentCyan,
                    containerColor = Surface2,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun StreamButton(
    streamingState: StreamingState,
    permissionsGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    AnimatedContent(
        targetState = streamingState,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "streamButton"
    ) { targetState ->
        when (targetState) {
            StreamingState.STREAMING -> {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, ErrorRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Streaming", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            else -> {
                Button(
                    onClick = onStart,
                    enabled = permissionsGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Background,
                        disabledContainerColor = AccentCyanDim.copy(alpha = 0.4f),
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.RadioButtonChecked,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (permissionsGranted) "Start Streaming" else "Grant Camera Permission",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AdbHint(isStreaming: Boolean) {
    if (!isStreaming) return
    Text(
        text = "PC: adb forward tcp:5000 tcp:5000\nVLC: tcp/h264://127.0.0.1:5000",
        color = TextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(10.dp)
    )
}
