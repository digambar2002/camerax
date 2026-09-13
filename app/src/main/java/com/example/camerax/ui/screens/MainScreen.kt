package com.example.camerax.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.example.camerax.model.DualCamLayout
import com.example.camerax.model.PipShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.camerax.model.AppMode
import com.example.camerax.model.AppState
import com.example.camerax.model.CameraFacing
import com.example.camerax.model.Resolution
import com.example.camerax.model.StreamingState
import java.util.Locale

// ---------------------------------------------------------------------------
// Colour palette — premium dark theme
// ---------------------------------------------------------------------------
private val Background    = Color(0xFF0A0A0F)
private val Surface1      = Color(0xFF13131A)
private val Surface2      = Color(0xFF1C1C28)
private val Surface3      = Color(0xFF262638)
private val AccentCyan    = Color(0xFF00C8FF)
private val AccentCyanDim = Color(0xFF007A9C)
private val SuccessGreen  = Color(0xFF00E676)
private val ErrorRed      = Color(0xFFFF5252)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF888899)
private val ChipSelected  = Color(0xFF1E3A45)

/**
 * Full-screen Compose UI for the USB Webcam & Shorts Creator app.
 */
@Composable
fun MainScreen(
    state: AppState,
    @Suppress("UNUSED_PARAMETER") lifecycleOwner: LifecycleOwner,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDualPrimarySurfaceReady: (Surface) -> Unit = {},
    onDualPrimarySurfaceDestroyed: () -> Unit = {},
    onDualSecondarySurfaceReady: (Surface) -> Unit = {},
    onDualSecondarySurfaceDestroyed: () -> Unit = {},
    onSelectMode: (AppMode) -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onStartShortsRecording: () -> Unit,
    onStopShortsRecording: () -> Unit,
    onStartDualRecording: () -> Unit = {},
    onStopDualRecording: () -> Unit = {},
    onSwitchCamera: () -> Unit,
    onSwitchShortsCamera: () -> Unit,
    onCircleWidthChange: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSelectDualLayout: (DualCamLayout) -> Unit = {},
    onSelectPipShape: (PipShape) -> Unit = {},
    onPipSizeChange: (Int) -> Unit = {},
    onSwapDualCameras: (Bitmap?) -> Unit = {},
    onSelectResolution: (Resolution) -> Unit,
    onSelectFps: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var primaryTextureViewRef by remember { mutableStateOf<TextureView?>(null) }

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
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // -----------------------------------------------------------------
            // Mode Selector Bar (Webcam vs Shorts vs Dual Cam)
            // -----------------------------------------------------------------
            ModeSwitcher(
                currentMode = state.appMode,
                onSelectMode = onSelectMode
            )

            // -----------------------------------------------------------------
            // 1. Status Bar
            // -----------------------------------------------------------------
            when (state.appMode) {
                AppMode.WEBCAM -> StatusBar(state = state)
                AppMode.SHORTS_CREATOR -> ShortsStatusBar(state = state)
                AppMode.DUAL_CAMERA -> DualCamStatusBar(state = state)
            }

            // -----------------------------------------------------------------
            // 2. Center Viewfinder / Preview
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (state.appMode) {
                    AppMode.WEBCAM -> {
                        // PC Webcam Viewfinder
                    if (state.streamingState == StreamingState.STREAMING) {
                        // Battery Saver Mode when streaming to desktop
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(horizontal = 28.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Surface2),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EnergySavingsLeaf,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Text(
                                    text = "BATTERY SAVER ACTIVE",
                                    color = SuccessGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Screen preview is turned OFF to save battery, keep your phone cool, and eliminate GPU load while streaming to PC.",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 17.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Surface2
                                    ) {
                                        Text(
                                            text = "● VIDEO 5000",
                                            color = AccentCyan,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Surface2
                                    ) {
                                        Text(
                                            text = "● AUDIO 5001",
                                            color = SuccessGreen,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Idle Webcam Viewfinder
                        CameraPreview(
                            resolution = state.selectedResolution,
                            onSurfaceReady = onSurfaceReady,
                            onSurfaceDestroyed = onSurfaceDestroyed,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Resolution + FPS badge overlay
                        ResolutionBadge(
                            resolution = state.selectedResolution,
                            fps = state.selectedFps,
                            isStreaming = false,
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
                    }
                    }
                    AppMode.SHORTS_CREATOR -> {
                        // 📱 Shorts Creator Viewfinder
                    if (!state.isRecordingShorts) {
                        // Interactive Live Circular Camera Viewfinder Preview
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(state.shortsCircleWidthDp.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 3.5.dp,
                                        brush = Brush.sweepGradient(
                                            listOf(
                                                AccentCyan,
                                                Color(0xFF7C4DFF),
                                                Color(0xFF00E5FF),
                                                AccentCyan
                                            )
                                        ),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                CircleCameraPreview(
                                    onSurfaceReady = onSurfaceReady,
                                    onSurfaceDestroyed = onSurfaceDestroyed,
                                    isFrontCamera = state.cameraFacing == CameraFacing.FRONT,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Overlay Zoom Badge on circle preview
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xDD000000),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.1fx Zoom", state.shortsZoomLevel),
                                        color = AccentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Camera Circle Preview (${state.shortsCircleWidthDp} dp)",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // Shorts Recording in progress screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(ErrorRed.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FiberManualRecord,
                                        contentDescription = null,
                                        tint = ErrorRed,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Text(
                                    text = "SHORTS RECORDING IN PROGRESS",
                                    color = ErrorRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Screen & Audio are recording.\nFloating camera bubble is active over all apps & games.",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                AppMode.DUAL_CAMERA -> {
                    // 🎥 Dual Camera / Vlog Viewport
                    DualCamViewport(
                        state = state,
                        onPrimarySurfaceReady = onDualPrimarySurfaceReady,
                        onPrimarySurfaceDestroyed = onDualPrimarySurfaceDestroyed,
                        onSecondarySurfaceReady = onDualSecondarySurfaceReady,
                        onSecondarySurfaceDestroyed = onDualSecondarySurfaceDestroyed,
                        onSwapDualCameras = onSwapDualCameras,
                        onTextureViewCreated = { primaryTextureViewRef = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Permission-denied overlay
            if (!state.permissionsGranted) {
                PermissionOverlay()
            }
        }

        // -----------------------------------------------------------------
        // 3. Control Panel (Webcam vs Shorts vs Dual Cam)
        // -----------------------------------------------------------------
        when (state.appMode) {
            AppMode.WEBCAM -> {
                ControlPanel(
                    state = state,
                    onStartStreaming = onStartStreaming,
                    onStopStreaming = onStopStreaming,
                    onSelectResolution = onSelectResolution,
                    onSelectFps = onSelectFps
                )
            }
            AppMode.SHORTS_CREATOR -> {
                ShortsControlPanel(
                    state = state,
                    onStartRecording = onStartShortsRecording,
                    onStopRecording = onStopShortsRecording,
                    onCircleWidthChange = onCircleWidthChange,
                    onZoomChange = onZoomChange,
                    onSwitchCamera = onSwitchShortsCamera
                )
            }
            AppMode.DUAL_CAMERA -> {
                DualCamControlPanel(
                    state = state,
                    onSelectLayout = onSelectDualLayout,
                    onSelectPipShape = onSelectPipShape,
                    onPipSizeChange = onPipSizeChange,
                    onSwapDualCameras = { onSwapDualCameras(primaryTextureViewRef?.bitmap) },
                    onStartRecording = onStartDualRecording,
                    onStopRecording = onStopDualRecording
                )
            }
        }

        // -----------------------------------------------------------------
        // 4. Video Processing / Saving Progress Modal
        // -----------------------------------------------------------------
        if (state.isProcessingVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .padding(16.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface1),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentCyan.copy(alpha = 0.6f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { state.videoProcessingProgress / 100f },
                                modifier = Modifier.size(76.dp),
                                color = AccentCyan,
                                trackColor = Surface3,
                                strokeWidth = 6.dp
                            )
                            Text(
                                text = "${state.videoProcessingProgress}%",
                                color = AccentCyan,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = "Saving Vlog Video...",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )

                        LinearProgressIndicator(
                            progress = { state.videoProcessingProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentCyan,
                            trackColor = Surface3
                        )

                        Text(
                            text = "Combining camera angles with continuous audio.\nVideo will appear in your gallery.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}
}

// ---------------------------------------------------------------------------
// Mode Switcher (Webcam vs Shorts vs Dual Cam)
// ---------------------------------------------------------------------------

@Composable
private fun ModeSwitcher(
    currentMode: AppMode,
    onSelectMode: (AppMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = currentMode == AppMode.WEBCAM,
            onClick = { onSelectMode(AppMode.WEBCAM) },
            label = {
                Text(
                    text = "🖥️ PC",
                    fontWeight = if (currentMode == AppMode.WEBCAM) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentCyanDim,
                selectedLabelColor = AccentCyan,
                containerColor = Surface2,
                labelColor = TextSecondary
            )
        )
        FilterChip(
            selected = currentMode == AppMode.SHORTS_CREATOR,
            onClick = { onSelectMode(AppMode.SHORTS_CREATOR) },
            label = {
                Text(
                    text = "📱 Shorts",
                    fontWeight = if (currentMode == AppMode.SHORTS_CREATOR) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentCyanDim,
                selectedLabelColor = AccentCyan,
                containerColor = Surface2,
                labelColor = TextSecondary
            )
        )
        FilterChip(
            selected = currentMode == AppMode.DUAL_CAMERA,
            onClick = { onSelectMode(AppMode.DUAL_CAMERA) },
            label = {
                Text(
                    text = "🎥 Dual Cam",
                    fontWeight = if (currentMode == AppMode.DUAL_CAMERA) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AccentCyanDim,
                selectedLabelColor = AccentCyan,
                containerColor = Surface2,
                labelColor = TextSecondary
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Shorts Status Bar
// ---------------------------------------------------------------------------

@Composable
private fun ShortsStatusBar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (label, color) = if (state.isRecordingShorts) {
            "● RECORDING" to ErrorRed
        } else {
            "○ READY" to SuccessGreen
        }

        val infiniteTransition = rememberInfiniteTransition(label = "pulseShorts")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (state.isRecordingShorts) 0.3f else 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "blinkShorts"
        )

        Text(
            text = label,
            color = if (state.isRecordingShorts) color.copy(alpha = alpha) else color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.overlayPermissionGranted) SuccessGreen else AccentCyan)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.overlayPermissionGranted) "Overlay Active" else "Floating Bubble",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shorts Control Panel
// ---------------------------------------------------------------------------

@Composable
private fun ShortsControlPanel(
    state: AppState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCircleWidthChange: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSwitchCamera: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.isRecordingShorts) {
                // 1. Circle Width Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CIRCLE WIDTH",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "${state.shortsCircleWidthDp} dp",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { onCircleWidthChange((state.shortsCircleWidthDp - 15).coerceAtLeast(90)) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Surface2, CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Slider(
                        value = state.shortsCircleWidthDp.toFloat(),
                        onValueChange = { onCircleWidthChange(it.toInt()) },
                        valueRange = 90f..240f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = Surface2
                        )
                    )
                    IconButton(
                        onClick = { onCircleWidthChange((state.shortsCircleWidthDp + 15).coerceAtMost(240)) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Surface2, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                }

                // Preset width chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(110 to "Small", 150 to "Med", 190 to "Large", 230 to "Max").forEach { (size, label) ->
                        FilterChip(
                            selected = (state.shortsCircleWidthDp in (size - 15)..(size + 15)),
                            onClick = { onCircleWidthChange(size) },
                            label = { Text(text = "$label ${size}dp", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyanDim,
                                selectedLabelColor = AccentCyan,
                                containerColor = Surface2,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                // 2. Camera Zoom Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CAMERA ZOOM",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "%.1fx", state.shortsZoomLevel),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f).forEach { zoom ->
                        FilterChip(
                            selected = (Math.abs(state.shortsZoomLevel - zoom) < 0.15f),
                            onClick = { onZoomChange(zoom) },
                            label = { Text(text = "${zoom}x", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyanDim,
                                selectedLabelColor = AccentCyan,
                                containerColor = Surface2,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Slider(
                    value = state.shortsZoomLevel,
                    onValueChange = { onZoomChange(it) },
                    valueRange = 1.0f..5.0f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = Surface2
                    )
                )

                // 3. Switch Camera Lens
                OutlinedButton(
                    onClick = onSwitchCamera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyanDim)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Flip Camera (${if (state.cameraFacing == CameraFacing.FRONT) "Front" else "Rear"})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Big Start / Stop Record Button
            if (state.isRecordingShorts) {
                OutlinedButton(
                    onClick = onStopRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
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
                    Text("Stop Shorts Recording", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = onStartRecording,
                    enabled = state.permissionsGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
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
                        text = if (state.permissionsGranted) "Start Shorts Recording" else "Grant Permissions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // Recent Saved File Badge (if any)
            state.lastRecordedFilePath?.let { path ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SuccessGreen.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Saved: ${path.substringAfterLast("/")}",
                        color = SuccessGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status Bar (Webcam Mode)
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
// Camera Preview & Circular Viewfinder
// ---------------------------------------------------------------------------

/**
 * Configures TextureView transformation matrix so camera preview frames
 * (1920x1080 landscape buffer) are center-cropped into the viewport normally without stretching.
 * Handles front camera selfie mirroring when [isFrontCamera] is true.
 */
private fun configureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    previewWidth: Int = 1920,
    previewHeight: Int = 1080,
    isFrontCamera: Boolean = false
) {
    if (viewWidth <= 0 || viewHeight <= 0) return

    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    // In portrait orientation on phone, camera buffer is rotated 90 or 270 degrees,
    // so the buffer's portrait width is 1080 and portrait height is 1920
    val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

    val scale = Math.max(
        viewHeight.toFloat() / previewWidth.toFloat(),
        viewWidth.toFloat() / previewHeight.toFloat()
    )
    matrix.postScale(scale, scale, centerX, centerY)

    if (isFrontCamera) {
        // Selfie mirror reflection around center
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    textureView.setTransform(matrix)
}

@Composable
private fun CircleCameraPreview(
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    isFrontCamera: Boolean = false,
    onTextureViewCreated: ((TextureView) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var surfaceTextureRef by remember { mutableStateOf<SurfaceTexture?>(null) }

    LaunchedEffect(isFrontCamera) {
        val tv = textureViewRef ?: return@LaunchedEffect
        val st = surfaceTextureRef ?: return@LaunchedEffect
        st.setDefaultBufferSize(1920, 1080)
        configureTransform(tv, tv.width, tv.height, isFrontCamera = isFrontCamera)
    }

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                textureViewRef = this
                onTextureViewCreated?.invoke(this)
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surfaceTextureRef = st
                        st.setDefaultBufferSize(1920, 1080)
                        configureTransform(this@apply, width, height, isFrontCamera = isFrontCamera)
                        post {
                            configureTransform(this@apply, width, height, isFrontCamera = isFrontCamera)
                        }
                        onSurfaceReady(Surface(st))
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        configureTransform(this@apply, width, height, isFrontCamera = isFrontCamera)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        surfaceTextureRef = null
                        textureViewRef = null
                        onSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { textureView ->
            textureViewRef = textureView
            onTextureViewCreated?.invoke(textureView)
            configureTransform(textureView, textureView.width, textureView.height, isFrontCamera = isFrontCamera)
        },
        modifier = modifier
    )
}

@Composable
private fun CameraPreview(
    resolution: Resolution,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    LaunchedEffect(resolution) {
        surfaceViewRef?.holder?.setFixedSize(resolution.width, resolution.height)
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFixedSize(resolution.width, resolution.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceReady(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onSurfaceReady(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
                surfaceViewRef = this
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
        text = "PC Ports: 5000 (Video) · 5001 (Audio)\nOBS Media Source: tcp://127.0.0.1:5000 (h264)\nOBS Audio Source: tcp://127.0.0.1:5001 (aac)",
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

// ---------------------------------------------------------------------------
// Dual Camera Status Bar
// ---------------------------------------------------------------------------

@Composable
private fun DualCamStatusBar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (label, color) = if (state.isRecordingDualCam) {
            "● VLOG RECORDING" to ErrorRed
        } else {
            "○ VLOG READY" to SuccessGreen
        }

        val infiniteTransition = rememberInfiniteTransition(label = "pulseDual")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (state.isRecordingDualCam) 0.3f else 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "blinkDual"
        )

        Text(
            text = label,
            color = if (state.isRecordingDualCam) color.copy(alpha = alpha) else color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentCyan)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.dualCamLayout == DualCamLayout.SPLIT_SCREEN) "Split 50/50" else "PiP Floating",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dual Camera Viewport (Split Screen & PiP)
// ---------------------------------------------------------------------------

@Composable
private fun DualCamViewport(
    state: AppState,
    onPrimarySurfaceReady: (Surface) -> Unit,
    onPrimarySurfaceDestroyed: () -> Unit,
    onSecondarySurfaceReady: (Surface) -> Unit,
    onSecondarySurfaceDestroyed: () -> Unit,
    onSwapDualCameras: (Bitmap?) -> Unit,
    onTextureViewCreated: (TextureView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var primaryTextureView by remember { mutableStateOf<TextureView?>(null) }

    if (state.dualCamLayout == DualCamLayout.SPLIT_SCREEN) {
        // -----------------------------------------------------------------
        // 1. Split Screen Layout (50/50 Top & Bottom)
        // -----------------------------------------------------------------
        Column(modifier = modifier.fillMaxSize()) {
            // TOP PANE (Primary Camera)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircleCameraPreview(
                    onSurfaceReady = onPrimarySurfaceReady,
                    onSurfaceDestroyed = onPrimarySurfaceDestroyed,
                    isFrontCamera = !state.isPrimaryRear,
                    onTextureViewCreated = {
                        primaryTextureView = it
                        onTextureViewCreated(it)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top Pane Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (state.isPrimaryRear) "📷 TOP: REAR CAM (MAIN)" else "🤳 TOP: FRONT CAM (FACE)",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // MIDDLE GLOWING DIVIDER & SWAP BUTTON
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Surface1),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, AccentCyan, Color(0xFF7C4DFF), Color.Transparent)
                            )
                        )
                )

                Button(
                    onClick = { onSwapDualCameras(primaryTextureView?.bitmap) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2,
                        contentColor = AccentCyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyanDim),
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Swap Top and Bottom",
                        tint = AccentCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SWAP",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // BOTTOM PANE (Secondary Camera / Interactive Toggle)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF111118)),
                contentAlignment = Alignment.Center
            ) {
                if (state.supportsConcurrentHardware) {
                    CircleCameraPreview(
                        onSurfaceReady = onSecondarySurfaceReady,
                        onSurfaceDestroyed = onSecondarySurfaceDestroyed,
                        isFrontCamera = state.isPrimaryRear,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Surface2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = if (state.isPrimaryRear) "FRONT CAMERA (FACE)" else "REAR CAMERA (MAIN)",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap SWAP above to toggle active camera instantly into view",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Bottom Pane Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (state.isPrimaryRear) "🤳 BOTTOM: FRONT CAM (FACE)" else "📷 BOTTOM: REAR CAM (MAIN)",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    } else {
        // -----------------------------------------------------------------
        // 2. Picture-in-Picture (PiP) Layout
        // -----------------------------------------------------------------
        var offsetX by remember { mutableStateOf(30f) }
        var offsetY by remember { mutableStateOf(80f) }

        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            // FULL BACKGROUND PRIMARY CAMERA
            CircleCameraPreview(
                onSurfaceReady = onPrimarySurfaceReady,
                onSurfaceDestroyed = onPrimarySurfaceDestroyed,
                isFrontCamera = !state.isPrimaryRear,
                onTextureViewCreated = {
                    primaryTextureView = it
                    onTextureViewCreated(it)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Primary Camera Badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xCC000000),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = if (state.isPrimaryRear) "📷 FULL: REAR CAMERA" else "🤳 FULL: FRONT CAMERA",
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // FLOATING SECONDARY CAMERA WINDOW (Draggable & Resizable)
            val pipShape = if (state.pipShape == PipShape.CIRCLE) CircleShape else RoundedCornerShape(20.dp)

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(state.pipSizeDp.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .clip(pipShape)
                    .background(Surface2)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            listOf(AccentCyan, Color(0xFF7C4DFF), Color(0xFF00E5FF), AccentCyan)
                        ),
                        shape = pipShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (state.supportsConcurrentHardware) {
                    CircleCameraPreview(
                        onSurfaceReady = onSecondarySurfaceReady,
                        onSurfaceDestroyed = onSecondarySurfaceDestroyed,
                        isFrontCamera = state.isPrimaryRear,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    ) {
                        IconButton(onClick = { onSwapDualCameras(primaryTextureView?.bitmap) }) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Swap Camera",
                                tint = AccentCyan,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Text(
                            text = if (state.isPrimaryRear) "FACE" else "REAR",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap to Swap",
                            color = TextSecondary,
                            fontSize = 9.sp
                        )
                    }
                }

                // PiP Label Pill at bottom of floating window
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xDD000000),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = if (state.isPrimaryRear) "FACE CAM" else "REAR CAM",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dual Camera Control Panel
// ---------------------------------------------------------------------------

@Composable
private fun DualCamControlPanel(
    state: AppState,
    onSelectLayout: (DualCamLayout) -> Unit,
    onSelectPipShape: (PipShape) -> Unit,
    onPipSizeChange: (Int) -> Unit,
    onSwapDualCameras: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.isRecordingDualCam && !state.isProcessingVideo) {
                // 1. Layout Mode Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VLOG LAYOUT",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.dualCamLayout == DualCamLayout.SPLIT_SCREEN,
                            onClick = { onSelectLayout(DualCamLayout.SPLIT_SCREEN) },
                            label = { Text("Split 50/50", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyanDim,
                                selectedLabelColor = AccentCyan,
                                containerColor = Surface2,
                                labelColor = TextSecondary
                            )
                        )
                        FilterChip(
                            selected = state.dualCamLayout == DualCamLayout.PIP,
                            onClick = { onSelectLayout(DualCamLayout.PIP) },
                            label = { Text("PiP Floating", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyanDim,
                                selectedLabelColor = AccentCyan,
                                containerColor = Surface2,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                // 2. If PiP: Shape & Size Controls
                if (state.dualCamLayout == DualCamLayout.PIP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FLOATING SHAPE",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = state.pipShape == PipShape.CIRCLE,
                                onClick = { onSelectPipShape(PipShape.CIRCLE) },
                                label = { Text("● Circle", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyanDim,
                                    selectedLabelColor = AccentCyan,
                                    containerColor = Surface2,
                                    labelColor = TextSecondary
                                )
                            )
                            FilterChip(
                                selected = state.pipShape == PipShape.ROUNDED_SQUARE,
                                onClick = { onSelectPipShape(PipShape.ROUNDED_SQUARE) },
                                label = { Text("■ Square", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyanDim,
                                    selectedLabelColor = AccentCyan,
                                    containerColor = Surface2,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }

                    // Floating Size Stepper & Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BUBBLE SIZE",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { onPipSizeChange(state.pipSizeDp - 15) },
                                modifier = Modifier.size(32.dp).clip(CircleShape).background(Surface2)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "${state.pipSizeDp} dp",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            IconButton(
                                onClick = { onPipSizeChange(state.pipSizeDp + 15) },
                                modifier = Modifier.size(32.dp).clip(CircleShape).background(Surface2)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase", tint = AccentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // 3. Swap Cameras Button
                OutlinedButton(
                    onClick = onSwapDualCameras,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyanDim)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Swap Cameras (${if (state.isPrimaryRear) "Rear ↔ Front" else "Front ↔ Rear"})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 4. Big Start / Stop Record Button & Processing State
            if (state.isProcessingVideo) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Surface2,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = AccentCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Saving Vlog (${state.videoProcessingProgress}%)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            } else if (state.isRecordingDualCam) {
                // Live Camera Swap Button during recording!
                Button(
                    onClick = onSwapDualCameras,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2,
                        contentColor = AccentCyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyanDim)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Swap Camera", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Swap Camera (${if (state.isPrimaryRear) "Rear ➔ Front" else "Front ➔ Rear"})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onStopRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, ErrorRed)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Vlog Recording", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = onStartRecording,
                    enabled = state.permissionsGranted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Background,
                        disabledContainerColor = AccentCyanDim.copy(alpha = 0.4f),
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.permissionsGranted) "Start Vlog Recording" else "Grant Permissions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // 5. Recent Saved File Badge (if any)
            state.lastRecordedDualCamPath?.let { path ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SuccessGreen.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Saved: ${path.substringAfterLast("/")}",
                        color = SuccessGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
