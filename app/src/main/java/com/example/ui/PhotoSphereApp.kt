package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path as AndroidPath
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Vibrator
import android.util.Log
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.model.PhotoSphereNode
import com.example.processing.StitchingPipeline
import com.example.sensor.SensorFusionTracker
import kotlinx.coroutines.launch
import kotlin.math.*

enum class AppScreen {
    DASHBOARD,
    CAPTURE,
    STITCHING,
    VIEWER
}

data class SavedPanorama(
    val id: String,
    val title: String,
    val dateString: String,
    val lens: String,
    val rawOutput: Boolean,
    val nodeCapturedCount: Int,
    val totalNodes: Int,
    val bitmap: Bitmap? = null,
    val defaultExposure: Float = 0f,
    val defaultHighlights: Float = 0f,
    val defaultShadows: Float = 0f,
    val defaultTemp: Float = 0f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSphereApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Navigation and screen state
    var currentScreen by remember { mutableStateFlowState(AppScreen.CAPTURE) }

    // Configuration / Specifications
    var selectedLens by remember { mutableStateFlowState("Wide (1x)") }
    var rawOutputEnabled by remember { mutableStateFlowState(true) }
    var autoCaptureEnabled by remember { mutableStateFlowState(true) }

    // Advanced Configurable Settings (GCam System)
    var hdPlusBurstCount by remember { mutableStateFlowState(9) }                     // 3, 9, 15 frame stacks
    var noiseReductionPower by remember { mutableStateFlowState(55f) }                // [0f, 100f]
    var detailEnhancementPower by remember { mutableStateFlowState(40f) }             // [0f, 100f]
    var toneCurveSelection by remember { mutableStateFlowState("Cinematic (GCam Modern)") } // Cinematic, Contrast, Astro, Balanced Flat
    var seamBlendingRadius by remember { mutableStateFlowState(64f) }                // [16f, 128f]
    var vibrationPower by remember { mutableStateFlowState("Subtle Pulse") }          // None, Subtle Pulse, Tactile snap
    var captureSoundEffect by remember { mutableStateFlowState("Standard Shutter") }  // Muted, Standard Shutter, Electronic Beep
    var autoCaptureDelayMs by remember { mutableStateFlowState(500) }                 // 200, 500, 1000, 2000 ms
    var slamGridDensity by remember { mutableStateFlowState("Normal") }               // Low, Normal, High, Max
    var alignmentTolerance by remember { mutableStateFlowState(1.8f) }                // 1.0f, 1.8f, 3.0f degrees

    // Active capture tracking
    var nodesList by remember { mutableStateFlowState(generateNodesForLens("Wide (1x)")) }
    var activeNodeIdInBurst by remember { mutableStateFlowState<String?>(null) }
    var activeBurstProgress by remember { mutableStateFlowState(0f) }

    // Sensor state
    val sensorFusionTracker = remember { SensorFusionTracker(context) }
    val currentYaw by sensorFusionTracker.yaw.collectAsState()
    val currentPitch by sensorFusionTracker.pitch.collectAsState()
    val currentRoll by sensorFusionTracker.roll.collectAsState()
    val isSensorActive by sensorFusionTracker.isSensorActive.collectAsState()
    var isManualSwipeMode by remember { mutableStateFlowState(false) }

    // CameraX Preview Setup & Permission
    var hasCameraPermission by remember {
        mutableStateFlowState(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var takePhotoAction by remember { mutableStateOf<((onPhotoCaptured: (Bitmap) -> Unit) -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    // Stitching Pipeline
    val pipeline = remember { StitchingPipeline() }
    val pipelineState by pipeline.state.collectAsState()
    var computedStitchedBitmap by remember { mutableStateFlowState<Bitmap?>(null) }
    var currentDevelopedBitmap by remember { mutableStateFlowState<Bitmap?>(null) }
    var processingProgress by remember { mutableStateFlowState(0f) }

    // RAW Adjustments (Linear DNG Editor)
    var dngExposure by remember { mutableStateFlowState(0f) }
    var dngHighlights by remember { mutableStateFlowState(0f) }
    var dngShadows by remember { mutableStateFlowState(0f) }
    var dngTemperature by remember { mutableStateFlowState(0f) } // Offset [-1, 1]
    var dngToneCurve by remember { mutableStateFlowState("Cinematic (GCam Modern)") }
    var dngLocalContrast by remember { mutableStateFlowState(35f) }
    var dngDetailBoost by remember { mutableStateFlowState(40f) }
    var dngVignette by remember { mutableStateFlowState(25f) }
    var isDevelopingOverlay by remember { mutableStateFlowState(false) }

    // Selected Panorama for inspection/viewer
    var viewerPanorama by remember { mutableStateFlowState<SavedPanorama?>(null) }
    var activeViewerMode by remember { mutableStateFlowState("360 Sphere") } // "360 Sphere", "Little Planet", "Flat Map"

    // Past histories
    var historyList by remember {
        mutableStateFlowState(
            listOf(
                SavedPanorama(
                    id = "p1",
                    title = "Yosemite Sunset Cap (RAW DNG)",
                    dateString = "June 15, 2026",
                    lens = "Wide (1x)",
                    rawOutput = true,
                    nodeCapturedCount = 36,
                    totalNodes = 36,
                    bitmap = null, // Will procedural render on demand
                    defaultExposure = -0.3f,
                    defaultHighlights = -25f,
                    defaultShadows = 40f,
                    defaultTemp = -0.1f
                ),
                SavedPanorama(
                    id = "p2",
                    title = "Tokyo Shibuya Intersect (Ultrawide)",
                    dateString = "June 12, 2026",
                    lens = "Ultrawide (0.5x)",
                    rawOutput = false,
                    nodeCapturedCount = 8,
                    totalNodes = 8,
                    bitmap = null
                )
            )
        )
    }

    // Effect for active sensor registration
    DisposableEffect(currentScreen) {
        if (currentScreen == AppScreen.CAPTURE) {
            sensorFusionTracker.startTracking()
            isManualSwipeMode = !isSensorActive
        } else {
            sensorFusionTracker.stopTracking()
        }
        onDispose {
            sensorFusionTracker.stopTracking()
        }
    }

    // Adapt nodes list on lens switcher update
    LaunchedEffect(selectedLens) {
        nodesList = generateNodesForLens(selectedLens)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1014)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main navigation router
            when (currentScreen) {
                AppScreen.DASHBOARD -> {
                    DashboardScreen(
                        selectedLens = selectedLens,
                        onLensChange = { selectedLens = it },
                        rawOutputEnabled = rawOutputEnabled,
                        onRawOutputToggle = { rawOutputEnabled = it },
                        autoCaptureEnabled = autoCaptureEnabled,
                        onAutoCaptureToggle = { autoCaptureEnabled = it },
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        historyList = historyList,
                        onSelectHistory = { pano ->
                            scope.launch {
                                isDevelopingOverlay = true
                                currentScreen = AppScreen.VIEWER
                                viewerPanorama = pano
                                
                                dngExposure = pano.defaultExposure
                                dngHighlights = pano.defaultHighlights
                                dngShadows = pano.defaultShadows
                                dngTemperature = pano.defaultTemp
                                
                                val rawBase = pipeline.stitchCapturedState(
                                    generateNodesForLens(pano.lens),
                                    pano.lens,
                                    pano.rawOutput
                                )
                                computedStitchedBitmap = rawBase
                                currentDevelopedBitmap = if (pano.rawOutput) {
                                    pipeline.developLinearDng(
                                        rawBase,
                                        pano.defaultExposure,
                                        pano.defaultHighlights,
                                        pano.defaultShadows,
                                        pano.defaultTemp
                                    )
                                } else {
                                    rawBase
                                }
                                isDevelopingOverlay = false
                            }
                        },
                        onStartCapture = {
                            if (!hasCameraPermission) {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            nodesList = generateNodesForLens(selectedLens)
                            currentScreen = AppScreen.CAPTURE
                        }
                    )
                }

                AppScreen.CAPTURE -> {
                    CaptureScreen(
                        selectedLens = selectedLens,
                        onLensChange = { selectedLens = it },
                        rawOutputEnabled = rawOutputEnabled,
                        autoCaptureEnabled = autoCaptureEnabled,
                        onAutoCaptureToggle = { autoCaptureEnabled = it },
                        hdPlusBurstCount = hdPlusBurstCount,
                        onHdPlusBurstCountChange = { hdPlusBurstCount = it },
                        noiseReductionPower = noiseReductionPower,
                        onNoiseReductionPowerChange = { noiseReductionPower = it },
                        detailEnhancementPower = detailEnhancementPower,
                        onDetailEnhancementPowerChange = { detailEnhancementPower = it },
                        vibrationPower = vibrationPower,
                        onVibrationPowerChange = { vibrationPower = it },
                        captureSoundEffect = captureSoundEffect,
                        onCaptureSoundEffectChange = { captureSoundEffect = it },
                        autoCaptureDelayMs = autoCaptureDelayMs,
                        onAutoCaptureDelayMsChange = { autoCaptureDelayMs = it },
                        slamGridDensity = slamGridDensity,
                        onSlamGridDensityChange = { slamGridDensity = it },
                        alignmentTolerance = alignmentTolerance,
                        onAlignmentToleranceChange = { alignmentTolerance = it },
                        nodes = nodesList,
                        currentYaw = currentYaw,
                        currentPitch = currentPitch,
                        currentRoll = currentRoll,
                        isSensorActive = isSensorActive,
                        isManualSwipeMode = isManualSwipeMode,
                        onToggleManualSwipe = { isManualSwipeMode = !isManualSwipeMode },
                        onSwipeDelta = { dy, dp ->
                            sensorFusionTracker.updateSimulation(dy, dp)
                        },
                        activeNodeIdInBurst = activeNodeIdInBurst,
                        activeBurstProgress = activeBurstProgress,
                        onTriggerBurstCapture = { node ->
                            scope.launch {
                                playShutterSoundCombined(context, captureSoundEffect)
                                playHapticVibe(context, vibrationPower)
                                activeNodeIdInBurst = node.id
                                
                                var capturedBmp: Bitmap? = null
                                // Attempt real ImageCapture via registered CameraX session
                                if (!isManualSwipeMode && takePhotoAction != null) {
                                    val deferred = kotlinx.coroutines.CompletableDeferred<Bitmap>()
                                    takePhotoAction?.invoke { bmp ->
                                        deferred.complete(bmp)
                                    }
                                    try {
                                        capturedBmp = kotlinx.coroutines.withTimeoutOrNull(3000) {
                                            deferred.await()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("PhotoSphereApp", "Real camera frame grab failed, utilizing fallback", e)
                                    }
                                }

                                // Fallback to realistic orientation-aware procedural render
                                if (capturedBmp == null) {
                                    capturedBmp = generateSimulatedPhotoAtState(
                                        yaw = node.targetYaw,
                                        pitch = node.targetPitch,
                                        lensName = selectedLens
                                    )
                                }

                                // Simulated HDR+ stack merging process
                                for (prog in 1..10) {
                                    delayLonger(40)
                                    activeBurstProgress = prog / 10f
                                }

                                nodesList = nodesList.map {
                                    if (it.id == node.id) {
                                        it.copy(
                                            captured = true,
                                            capturedProgress = 1f,
                                            capturedBitmap = capturedBmp
                                        )
                                    } else {
                                        it
                                    }
                                }
                                activeNodeIdInBurst = null
                                activeBurstProgress = 0f
                            }
                        },
                        onResetCapture = {
                            nodesList = generateNodesForLens(selectedLens)
                            sensorFusionTracker.resetSimulation()
                        },
                        onStartStitching = {
                            currentScreen = AppScreen.STITCHING
                            scope.launch {
                                val outBmp = pipeline.runStitchingPipelineAsync(
                                    nodesList,
                                    selectedLens,
                                    rawOutputEnabled,
                                    burstCount = hdPlusBurstCount,
                                    noisePower = noiseReductionPower,
                                    detailPower = detailEnhancementPower,
                                    toneCurve = toneCurveSelection,
                                    seamRadius = seamBlendingRadius
                                ) { prog ->
                                    processingProgress = prog
                                }
                                computedStitchedBitmap = outBmp
                                dngExposure = 0f
                                dngHighlights = 0f
                                dngShadows = 0f
                                dngTemperature = 0f
                                currentDevelopedBitmap = outBmp
                                
                                val label = "Capture ${historyList.size + 1} ($selectedLens)"
                                val newPano = SavedPanorama(
                                    id = "cap_${System.currentTimeMillis()}",
                                    title = label,
                                    dateString = "Just Now",
                                    lens = selectedLens,
                                    rawOutput = rawOutputEnabled,
                                    nodeCapturedCount = nodesList.filter { it.captured }.size,
                                    totalNodes = nodesList.size,
                                    bitmap = outBmp
                                )
                                viewerPanorama = newPano
                                historyList = listOf(newPano) + historyList
                                currentScreen = AppScreen.VIEWER
                            }
                        },
                        onBack = {
                            currentScreen = AppScreen.DASHBOARD
                        },
                        onUndoLastCapture = {
                            val lastCaptured = nodesList.filter { it.captured }.lastOrNull()
                            if (lastCaptured != null) {
                                nodesList = nodesList.map {
                                    if (it.id == lastCaptured.id) it.copy(captured = false, capturedProgress = 0f, capturedBitmap = null) else it
                                }
                            }
                        },
                        onImageCaptureRegistered = { action ->
                            takePhotoAction = action
                        }
                    )
                }

                AppScreen.STITCHING -> {
                    StitchingProgressScreen(
                        pipelineState = pipelineState,
                        progress = processingProgress
                    )
                }

                AppScreen.VIEWER -> {
                    Viewer360Screen(
                        panorama = viewerPanorama,
                        baseBitmap = computedStitchedBitmap,
                        developedBitmap = currentDevelopedBitmap,
                        selectedLens = selectedLens,
                        exposure = dngExposure,
                        highlights = dngHighlights,
                        shadows = dngShadows,
                        tempOffset = dngTemperature,
                        toneCurvePreset = dngToneCurve,
                        localContrast = dngLocalContrast,
                        detailBoost = dngDetailBoost,
                        vignette = dngVignette,
                        activeMode = activeViewerMode,
                        isDeveloping = isDevelopingOverlay,
                        onModeChange = { activeViewerMode = it },
                        onParamsChange = { exp, hl, sh, temp, curve, contrast, detail, vig ->
                            dngExposure = exp
                            dngHighlights = hl
                            dngShadows = sh
                            dngTemperature = temp
                            dngToneCurve = curve
                            dngLocalContrast = contrast
                            dngDetailBoost = detail
                            dngVignette = vig
                        },
                        onReprocess = {
                            scope.launch {
                                isDevelopingOverlay = true
                                computedStitchedBitmap?.let { base ->
                                    val dev = pipeline.developLinearDng(
                                        baseBitmap = base,
                                        exposure = dngExposure,
                                        highlights = dngHighlights,
                                        shadows = dngShadows,
                                        tempCelsius = dngTemperature,
                                        toneCurvePreset = dngToneCurve,
                                        localContrast = dngLocalContrast,
                                        detailBoost = dngDetailBoost,
                                        vignette = dngVignette
                                    )
                                    currentDevelopedBitmap = dev
                                }
                                isDevelopingOverlay = false
                            }
                        },
                        onBack = {
                            currentScreen = AppScreen.DASHBOARD
                        }
                    )
                }
            }
        }
    }
}

// Helper functions for state flow caching
fun <T> mutableStateFlowState(initial: T): MutableState<T> {
    return mutableStateOf(initial)
}

suspend fun delayLonger(millis: Long) {
    kotlinx.coroutines.delay(millis)
}

fun playShutterSoundCombined(context: Context, soundEffectType: String) {
    if (soundEffectType == "Muted") return
    try {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
        if (soundEffectType == "Electronic Beep") {
            tone.startTone(ToneGenerator.TONE_CDMA_PIP, 80)
        } else {
            // Standard Shutter beep
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
        }
    } catch (e: Exception) {
        Log.e("AudioError", "Shutter audio failed", e)
    }
}

fun playHapticVibe(context: Context, vibrationStyle: String) {
    if (vibrationStyle == "None") return
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        if (vibrator != null && vibrator.hasVibrator()) {
            val duration = if (vibrationStyle == "Tactile snap") 80L else 35L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    } catch (e: Exception) {
        Log.e("HapticError", "Haptic vibrator failed", e)
    }
}

fun generateNodesForLens(lens: String): List<PhotoSphereNode> {
    val list = mutableListOf<PhotoSphereNode>()
    when (lens) {
        "Ultrawide (0.5x)" -> {
            for (i in 0 until 6) {
                val yaw = -180f + 60f * i
                list.add(PhotoSphereNode("UW-H$i", yaw, 0f, elevationBand = PhotoSphereNode.ElevationBand.HORIZON))
            }
            list.add(PhotoSphereNode("UW-Z", 0f, 80f, elevationBand = PhotoSphereNode.ElevationBand.ZENITH))
            list.add(PhotoSphereNode("UW-N", 0f, -80f, elevationBand = PhotoSphereNode.ElevationBand.NADIR))
        }
        "Wide (1x)" -> {
            for (i in 0 until 12) {
                val yaw = -180f + 30f * i
                list.add(PhotoSphereNode("W-H$i", yaw, 0f, elevationBand = PhotoSphereNode.ElevationBand.HORIZON))
            }
            for (i in 0 until 9) {
                val yaw = -160f + 40f * i
                list.add(PhotoSphereNode("W-U$i", yaw, 36f, elevationBand = PhotoSphereNode.ElevationBand.UPPER_HEMISPHERE))
            }
            for (i in 0 until 9) {
                val yaw = -160f + 40f * i
                list.add(PhotoSphereNode("W-L$i", yaw, -36f, elevationBand = PhotoSphereNode.ElevationBand.LOWER_HEMISPHERE))
            }
            for (i in 0 until 3) {
                val yaw = -120f + 120f * i
                list.add(PhotoSphereNode("W-Z$i", yaw, 75f, elevationBand = PhotoSphereNode.ElevationBand.ZENITH))
            }
            for (i in 0 until 3) {
                val yaw = -120f + 120f * i
                list.add(PhotoSphereNode("W-N$i", yaw, -75f, elevationBand = PhotoSphereNode.ElevationBand.NADIR))
            }
        }
        "Telephoto (5x)" -> {
            // Simplified high-res layout (40 nodes) to maintain extreme performance
            for (i in 0 until 16) {
                val yaw = -180f + 22.5f * i
                list.add(PhotoSphereNode("T-H$i", yaw, 0f, elevationBand = PhotoSphereNode.ElevationBand.HORIZON))
            }
            for (i in 0 until 10) {
                val yaw = -180f + 36f * i
                list.add(PhotoSphereNode("T-U$i", yaw, 30f, elevationBand = PhotoSphereNode.ElevationBand.UPPER_HEMISPHERE))
            }
            for (i in 0 until 10) {
                val yaw = -180f + 36f * i
                list.add(PhotoSphereNode("T-L$i", yaw, -30f, elevationBand = PhotoSphereNode.ElevationBand.LOWER_HEMISPHERE))
            }
            for (i in 0 until 2) {
                val yaw = -90f + 180f * i
                list.add(PhotoSphereNode("T-Z$i", yaw, 70f, elevationBand = PhotoSphereNode.ElevationBand.ZENITH))
            }
            for (i in 0 until 2) {
                val yaw = -90f + 180f * i
                list.add(PhotoSphereNode("T-N$i", yaw, -70f, elevationBand = PhotoSphereNode.ElevationBand.NADIR))
            }
        }
    }
    return list
}

// ------------------ 1. DASHBOARD SCREEN ------------------
@Composable
fun DashboardScreen(
    selectedLens: String,
    onLensChange: (String) -> Unit,
    rawOutputEnabled: Boolean,
    onRawOutputToggle: (Boolean) -> Unit,
    autoCaptureEnabled: Boolean,
    onAutoCaptureToggle: (Boolean) -> Unit,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    historyList: List<SavedPanorama>,
    onSelectHistory: (SavedPanorama) -> Unit,
    onStartCapture: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header styled with elegant Material 3 typography and slate details
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF34A853)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Photo Sphere Main Icon",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "Photo Sphere",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.testTag("app_title")
                )
                Text(
                    text = "GCam Spherical Integration Engine",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        Divider(color = Color(0xFF262930), thickness = 1.dp)

        // Capture Mode Configuration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "STITCHING GRID SPECIFICATIONS",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4285F4),
                        letterSpacing = 1.sp
                    )
                )

                // Lens picker
                Column {
                    Text("Selected Calibration Lens", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Ultrawide (0.5x)", "Wide (1x)", "Telephoto (5x)").forEach { lens ->
                            val isSel = lens == selectedLens
                            Button(
                                onClick = { onLensChange(lens) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                    contentColor = if (isSel) Color.White else Color.LightGray
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lens_btn_${lens.replace(" ", "_").replace("(", "").replace(")", "")}")
                            ) {
                                Text(lens, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }

                // Selected Lens info / specs helper text
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF20232B))
                        .padding(10.dp)
                ) {
                    val specText = when (selectedLens) {
                        "Ultrawide (0.5x)" -> "⚡ Ultra-Fast Capture: Reduces entire sphere budget to only 8 hyper-hemispherical frames. Significantly reduces capture time but raises radial distortion coefficients."
                        "Wide (1x)" -> "🌐 Original GCam Standard: Implements a classic 36-frame geodesic polyhedron lattice distributed over 5 precise elevation bands. Balanced detail."
                        "Telephoto (5x)" -> "🔭 Gigapixel Sphere: Scaled layout (40 high-density segments). Sharp detail, but prone to high rotational drift. Motorized tripod recommended."
                        else -> ""
                    }
                    Text(specText, color = Color(0xFF9E9E9E), fontSize = 11.sp, lineHeight = 15.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("RAW Linear DNG Output", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Saves linear developed sensor state", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = rawOutputEnabled,
                        onCheckedChange = onRawOutputToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4285F4),
                            checkedTrackColor = Color(0xFF1E355A)
                        ),
                        modifier = Modifier.testTag("raw_output_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Automatic Overlap Shutter", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Snap automatically on zero-drift aligns", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoCaptureEnabled,
                        onCheckedChange = onAutoCaptureToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4285F4),
                            checkedTrackColor = Color(0xFF1E355A)
                        ),
                        modifier = Modifier.testTag("auto_capture_switch")
                    )
                }
            }
        }

        // Camera Permission Request Banner warning if lacking permission
        if (!hasCameraPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D181A)),
                border = BorderStroke(1.dp, Color(0xFF7A1E22)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Camera Warn", tint = Color(0xFFE57373))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Camera Access Restricted", color = Color(0xFFE57373), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("The camera viewfinder requires system permissions. Simulated fallback available.", color = Color.LightGray, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action launch button
        Button(
            onClick = onStartCapture,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("start_capture_button")
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Start Shutter")
            Spacer(modifier = Modifier.width(10.dp))
            Text("Launch Photo Sphere Viewer", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(6.dp))

        // History Captures List
        Text(
            text = "SAVED HISTORIC CAPTURES",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color.LightGray,
                letterSpacing = 1.sp
            )
        )

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No captured photo spheres found.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                historyList.forEach { pano ->
                    Card(
                        onClick = { onSelectHistory(pano) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF16181E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history_item_${pano.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Dummy visual spherical layout icon
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF262A35)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Splitscreen,
                                    contentDescription = "Pano Icon",
                                    tint = if (pano.rawOutput) Color(0xFFFFB74D) else Color(0xFF4285F4),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pano.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(pano.dateString, color = Color.Gray, fontSize = 11.sp)
                                    Text("•", color = Color.DarkGray, fontSize = 11.sp)
                                    Text(pano.lens, color = Color(0xFF4285F4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    if (pano.rawOutput) {
                                        Text("•", color = Color.DarkGray, fontSize = 11.sp)
                                        Text("RAW DNG", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Arrow right icon",
                                tint = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

      // ------------------ 3D PERSPECTIVE PROJECTION SYSTEM ------------------
fun project3DPoint(
    worldX: Float,
    worldY: Float,
    worldZ: Float,
    cyRad: Float,
    cpRad: Float,
    crRad: Float,
    width: Float,
    height: Float,
    focalScale: Float
): Offset? {
    // Negate the angles to perform the inverse Camera Transform matrix (World -> Camera Space conversion)
    val yaw = -cyRad
    val pitch = -cpRad
    val roll = -crRad

    // 1. Rotate around vertical Y center (yaw angle)
    val x1 = worldX * cos(yaw) + worldZ * sin(yaw)
    val y1 = worldY
    val z1 = -worldX * sin(yaw) + worldZ * cos(yaw)

    // 2. Rotate around horizontal X center (pitch angle)
    val x2 = x1
    val y2 = y1 * cos(pitch) + z1 * sin(pitch)
    val z2 = -y1 * sin(pitch) + z1 * cos(pitch)

    // 3. Rotate around Z axis (roll angle) - stabilizing screen coordinates against device roll
    val x3 = x2 * cos(roll) + y2 * sin(roll)
    val y3 = -x2 * sin(roll) + y2 * cos(roll)
    val z3 = z2

    if (z3 > 0.05f) {
        val px = (width / 2f) + (x3 * focalScale) / z3
        val py = (height / 2f) - (y3 * focalScale) / z3
        return Offset(px, py)
    }
    return null
}

// ------------------ 2. CAMERA CAPTURE VIEWFINDER ------------------
@Composable
fun CaptureScreen(
    selectedLens: String,
    onLensChange: (String) -> Unit,
    rawOutputEnabled: Boolean,
    autoCaptureEnabled: Boolean,
    onAutoCaptureToggle: (Boolean) -> Unit,
    hdPlusBurstCount: Int,
    onHdPlusBurstCountChange: (Int) -> Unit,
    noiseReductionPower: Float,
    onNoiseReductionPowerChange: (Float) -> Unit,
    detailEnhancementPower: Float,
    onDetailEnhancementPowerChange: (Float) -> Unit,
    vibrationPower: String,
    onVibrationPowerChange: (String) -> Unit,
    captureSoundEffect: String,
    onCaptureSoundEffectChange: (String) -> Unit,
    autoCaptureDelayMs: Int,
    onAutoCaptureDelayMsChange: (Int) -> Unit,
    slamGridDensity: String,
    onSlamGridDensityChange: (String) -> Unit,
    alignmentTolerance: Float,
    onAlignmentToleranceChange: (Float) -> Unit,
    nodes: List<PhotoSphereNode>,
    currentYaw: Float,
    currentPitch: Float,
    currentRoll: Float,
    isSensorActive: Boolean,
    isManualSwipeMode: Boolean,
    onToggleManualSwipe: () -> Unit,
    onSwipeDelta: (Float, Float) -> Unit,
    activeNodeIdInBurst: String?,
    activeBurstProgress: Float,
    onTriggerBurstCapture: (PhotoSphereNode) -> Unit,
    onResetCapture: () -> Unit,
    onStartStitching: () -> Unit,
    onBack: () -> Unit,
    onUndoLastCapture: () -> Unit,
    onImageCaptureRegistered: (((onPhotoCaptured: (Bitmap) -> Unit) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val totalCaptured = nodes.filter { it.captured }.size
    val totalProgress = totalCaptured.toFloat() / nodes.size
    var isSettingsOverlayOpen by remember { mutableStateOf(false) }

    // First frame logic: Show only start target if nothing is captured yet
    val activeNodes = if (totalCaptured == 0) {
        nodes.filter { it.targetYaw == 0f && it.targetPitch == 0f }
    } else {
        nodes
    }

    // Determine if we are locked onto any active uncaptured node
    val nextTargetNode = activeNodes.firstOrNull { node ->
        if (node.captured) return@firstOrNull false
        var diffYaw = node.targetYaw - currentYaw
        while (diffYaw > 180f) diffYaw -= 360f
        while (diffYaw < -180f) diffYaw += 360f
        val diffPitch = node.targetPitch - currentPitch
        abs(diffYaw) < alignmentTolerance && abs(diffPitch) < alignmentTolerance
    }

    // Alignment countdown progress [0f, 1f]
    var alignmentProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(nextTargetNode, activeNodeIdInBurst) {
        if (nextTargetNode != null && activeNodeIdInBurst == null) {
            alignmentProgress = 0f
            val totalDuration = autoCaptureDelayMs.toFloat()
            val stepDuration = 16L
            var elapsed = 0f
            while (elapsed < totalDuration) {
                kotlinx.coroutines.delay(stepDuration)
                elapsed += stepDuration
                alignmentProgress = (elapsed / totalDuration).coerceIn(0f, 1f)
            }
            if (autoCaptureEnabled) {
                onTriggerBurstCapture(nextTargetNode)
            }
            alignmentProgress = 0f
        } else {
            alignmentProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)) // Pure pitch black canvas
    ) {
        val cyRad = Math.toRadians(currentYaw.toDouble()).toFloat()
        val cpRad = Math.toRadians(currentPitch.toDouble()).toFloat()
        val crRad = Math.toRadians(currentRoll.toDouble()).toFloat()

        // --- 1. PERSPECTIVE 3D GROUND PLANE OF CROSSES ---
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val width = size.width
            val height = size.height
            val focalScale = width * 1.5f

            val stepSize = when (slamGridDensity) {
                "Low" -> 1.2f
                "High" -> 0.6f
                "Max" -> 0.4f
                else -> 0.8f
            }
            val bound = when (slamGridDensity) {
                "Low" -> 6
                "High" -> 15
                "Max" -> 20
                else -> 10
            }

            for (gx in -bound..bound) {
                for (gz in -bound..bound) {
                    val worldX = gx * stepSize
                    val worldZ = gz * stepSize
                    val worldY = -1.2f // 1.2 meters below camera

                    val pt = project3DPoint(worldX, worldY, worldZ, cyRad, cpRad, crRad, width, height, focalScale)
                    if (pt != null) {
                        val px = pt.x
                        val py = pt.y
                        if (px in 0f..width && py in 0f..height) {
                            val distance = sqrt(worldX * worldX + worldY * worldY + worldZ * worldZ)
                            val depthAlpha = (1.0f - (distance / 7.0f)).coerceIn(0.04f, 0.45f)
                            val crossHalfSize = 4.dp.toPx()

                            drawLine(
                                color = Color.White.copy(alpha = depthAlpha),
                                start = Offset(px - crossHalfSize, py),
                                end = Offset(px + crossHalfSize, py),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = depthAlpha),
                                start = Offset(px, py - crossHalfSize),
                                end = Offset(px, py + crossHalfSize),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                }
            }
        }

        // --- 2. ACTIVE CAMERA VIEWFINDER PORTRAIT CARD ---
        Box(
            modifier = Modifier
                .size(width = 240.dp, height = 360.dp)
                .align(Alignment.Center)
                .graphicsLayer {
                    // Apply immersive 3D perspective tilt relative to camera elevation and gravity roll
                    rotationX = -currentPitch * 0.25f
                    rotationY = currentYaw * 0.12f
                    rotationZ = -currentRoll * 0.5f
                    cameraDistance = 10f * density
                }
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(11.dp))
                .testTag("centered_camera_viewport")
        ) {
            if (!isManualSwipeMode && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                CameraXPreviewView(
                    modifier = Modifier.fillMaxSize(),
                    onImageCaptureRegistered = onImageCaptureRegistered
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F1015))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        val panX = (currentYaw * 4f) % w
                        val panY = (currentPitch * 4f) % h

                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E2638), Color(0xFF08090E))
                            )
                        )

                        val landscapePath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, h)
                            lineTo(w * 0.25f, h * 0.45f + panY)
                            lineTo(w * 0.65f, h * 0.65f + panY)
                            lineTo(w * 0.85f, h * 0.35f + panY)
                            lineTo(w, h)
                            close()
                        }
                        drawPath(
                            path = landscapePath,
                            color = Color(0xFF333E54).copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }

        // --- OVERLAPPING LENS SWITCHER PILL (Pixel style) ---
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 405.dp) // Offset directly below the viewport card height
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(".7" to "Ultrawide (0.5x)", "1x" to "Wide (1x)", "2" to "Telephoto (5x)").forEach { (label, lensName) ->
                val isSel = selectedLens == lensName
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSel) Color(0xFF4285F4) else Color.Transparent)
                        .clickable { onLensChange(lensName) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSel) Color.White else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- 3. FULL-SCREEN INTERACTIVE CANVAS (FLOATING PHOTO FRAMES AND TARGET NODES) ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isManualSwipeMode) {
                    if (isManualSwipeMode) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dy = dragAmount.x * 0.12f
                            val dp = -dragAmount.y * 0.12f
                            onSwipeDelta(dy, dp)
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height

            val cx = width / 2f
            val cy = height / 2f
            val focalScale = width * 1.5f

            // Project and draw the 3D target nodes
            activeNodes.forEach { node ->
                if (node.id == activeNodeIdInBurst) return@forEach

                // Node spherical directory to 3D unit coordinates
                val nyRad = Math.toRadians(node.targetYaw.toDouble()).toFloat()
                val npRad = Math.toRadians(node.targetPitch.toDouble()).toFloat()

                val nx = cos(npRad) * sin(nyRad)
                val ny = sin(npRad)
                val nz = cos(npRad) * cos(nyRad)

                val pt = project3DPoint(nx, ny, nz, cyRad, cpRad, crRad, width, height, focalScale)
                if (pt != null) {
                    val px = pt.x
                    val py = pt.y

                    var diffYaw = node.targetYaw - currentYaw
                    while (diffYaw > 180f) diffYaw -= 360f
                    while (diffYaw < -180f) diffYaw += 360f
                    val diffPitch = node.targetPitch - currentPitch

                    if (node.captured) {
                        // Floating Captured Image Frame in Air with 3D Depth Scaling
                        val yawTemp = -cyRad
                        val pitchTemp = -cpRad
                        val x1_z = nx * cos(yawTemp) + nz * sin(yawTemp)
                        val z1_z = -nx * sin(yawTemp) + nz * cos(yawTemp)
                        val z3 = (-ny * sin(pitchTemp) + z1_z * cos(pitchTemp)).coerceAtLeast(0.1f)

                        val depthScale = (1.0f / z3).coerceIn(0.4f, 2.2f)
                        val cardW = 140.dp.toPx() * depthScale
                        val cardH = 105.dp.toPx() * depthScale

                        if (node.capturedBitmap != null) {
                            drawImage(
                                image = node.capturedBitmap.asImageBitmap(),
                                dstOffset = IntOffset((px - cardW / 2f).toInt(), (py - cardH / 2f).toInt()),
                                dstSize = IntSize(cardW.toInt(), cardH.toInt()),
                                alpha = 0.88f
                            )
                        } else {
                            drawRect(
                                color = Color(0xFF1E212A).copy(alpha = 0.38f),
                                topLeft = Offset(px - cardW / 2f, py - cardH / 2f),
                                size = Size(cardW, cardH)
                            )
                        }

                        // Floating outer white border
                        drawRect(
                            color = Color.White.copy(alpha = 0.55f),
                            topLeft = Offset(px - cardW / 2f, py - cardH / 2f),
                            size = Size(cardW, cardH),
                            style = Stroke(width = 1.8.dp.toPx() * depthScale)
                        )

                        // Draw a stylish completion badge
                        drawCircle(
                            color = Color(0xFF34A853),
                            radius = 10.dp.toPx() * depthScale,
                            center = Offset(px, py)
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(px - 4.dp.toPx() * depthScale, py),
                            end = Offset(px - 1.dp.toPx() * depthScale, py + 3.dp.toPx() * depthScale),
                            strokeWidth = 2.dp.toPx() * depthScale
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(px - 1.dp.toPx() * depthScale, py + 3.dp.toPx() * depthScale),
                            end = Offset(px + 4.dp.toPx() * depthScale, py - 3.dp.toPx() * depthScale),
                            strokeWidth = 2.dp.toPx() * depthScale
                        )
                    } else {
                        // Guide point circle target
                        val isAligned = abs(diffYaw) < alignmentTolerance && abs(diffPitch) < alignmentTolerance
                        val activeDotColor = if (isAligned) Color(0xFF34A853) else Color(0xFF4285F4)

                        val dotRadius = if (totalCaptured == 0) {
                            if (isAligned) 18.dp.toPx() else 14.dp.toPx()
                        } else {
                            if (isAligned) 13.dp.toPx() else 9.dp.toPx()
                        }

                        // Orbiting ripple circle
                        val pulse = 1f + sin(System.currentTimeMillis() * 0.005f) * 0.12f
                        drawCircle(
                            color = activeDotColor.copy(alpha = 0.22f),
                            radius = (dotRadius + 10.dp.toPx()) * pulse,
                            center = Offset(px, py)
                        )
                        // Core dot
                        drawCircle(
                            color = activeDotColor,
                            radius = dotRadius,
                            center = Offset(px, py)
                        )
                    }
                }
            }

            // Stationary central reticle
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 32.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Dynamic progress ring filling up when aligning
            if (alignmentProgress > 0f) {
                drawArc(
                    color = Color(0xFF34A853),
                    startAngle = -90f,
                    sweepAngle = 360f * alignmentProgress,
                    useCenter = false,
                    topLeft = Offset(cx - 32.dp.toPx(), cy - 32.dp.toPx()),
                    size = Size(64.dp.toPx(), 64.dp.toPx()),
                    style = Stroke(width = 4.dp.toPx())
                )
            }

            // Central crosshair guide chevrons pointing towards nearest uncaptured dot
            val closestTarget = activeNodes.filter { !it.captured }.minByOrNull { node ->
                var dy = node.targetYaw - currentYaw
                while (dy > 180f) dy -= 360f
                while (dy < -180f) dy += 360f
                val dp = node.targetPitch - currentPitch
                dy * dy + dp * dp
            }

            closestTarget?.let { node ->
                var dy = node.targetYaw - currentYaw
                while (dy > 180f) dy -= 360f
                while (dy < -180f) dy += 360f
                val dp = node.targetPitch - currentPitch

                val dist = sqrt(dy * dy + dp * dp)
                if (dist > alignmentTolerance) {
                    val angleRad = atan2(-dp, dy)
                    val arrowRadius = 100.dp.toPx()
                    val arrowCenterX = cx + cos(angleRad) * arrowRadius
                    val arrowCenterY = cy + sin(angleRad) * arrowRadius
                    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                    rotate(degrees = angleDeg, pivot = Offset(arrowCenterX, arrowCenterY)) {
                        val arrowPath = AndroidPath().apply {
                            moveTo(arrowCenterX + 12.dp.toPx(), arrowCenterY)
                            lineTo(arrowCenterX - 8.dp.toPx(), arrowCenterY - 7.dp.toPx())
                            lineTo(arrowCenterX - 8.dp.toPx(), arrowCenterY + 7.dp.toPx())
                            close()
                        }
                        this.drawContext.canvas.nativeCanvas.drawPath(
                            arrowPath,
                            AndroidPaint().apply {
                                color = AndroidColor.rgb(66, 133, 244)
                                style = AndroidPaint.Style.FILL
                                isAntiAlias = true
                                setShadowLayer(8.dp.toPx(), 0f, 0f, AndroidColor.argb(180, 66, 133, 244))
                            }
                        )
                    }
                }
            }
        }

        // --- 4. TOP-LEFT TEXT INSTRUCTIONS HUD BANNER ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 54.dp, start = 24.dp)
        ) {
            val textGuide = if (totalCaptured == 0) {
                "To start, keep dot inside circle"
            } else {
                "Swipe screen or look around to map sectors"
            }
            Text(
                text = textGuide,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Captured: $totalCaptured / ${nodes.size} sectors",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // --- 5. CAMERA SHUTTER BLINK FADE OVERLAY ---
        if (activeNodeIdInBurst != null) {
            val flashAlpha = (1f - activeBurstProgress).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha * 0.55f))
            )
        }

        // --- 6. FLOATING HUD RECONSTRUCTION RADIAL DIALOG ---
        if (activeNodeIdInBurst != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF4285F4)),
                    modifier = Modifier.size(150.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            progress = { activeBurstProgress },
                            color = Color(0xFF4285F4),
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("HDR+ SHIFT FUSING...", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Linear composite exposures", color = Color.Gray, fontSize = 8.sp)
                    }
                }
            }
        }

        // --- 7. CONFIGURABLE PREFERENCES OVERLAY DRAWER ---
        if (isSettingsOverlayOpen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 68.dp, start = 14.dp, end = 14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF13151D).copy(alpha = 0.96f)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF4285F4).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preferences_drawer_card")
                ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GCAM VIEWFINDER PREFERENCES",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4285F4),
                                letterSpacing = 0.8.sp
                            )
                        )
                        IconButton(
                            onClick = { isSettingsOverlayOpen = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close overlay", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262930)))

                    // 1. Overlap auto-shutter switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto Overlap Snap", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Snaps automatically on zero-drift align", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(
                            checked = autoCaptureEnabled,
                            onCheckedChange = onAutoCaptureToggle,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4285F4)),
                            modifier = Modifier.scale(0.85f)
                        )
                    }

                    // 2. Alignment Delay ms
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("Target Lock Delay", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Delay pointed before capture", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf(200 to "200ms", 500 to "500ms", 1000 to "1.0s", 2000 to "2.0s").forEach { (ms, lbl) ->
                                val isSel = ms == autoCaptureDelayMs
                                Button(
                                    onClick = { onAutoCaptureDelayMsChange(ms) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text(lbl, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 3. Grid Cross Density
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("SLAM Grid Density", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Floor spatial crosses density", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf("Low", "Normal", "High", "Max").forEach { density ->
                                val isSel = density == slamGridDensity
                                Button(
                                    onClick = { onSlamGridDensityChange(density) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text(density, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 4. Locking Tolerance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("Lock Angle Tolerance", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Locking segment radius degree", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf(1.0f to "1.0°", 1.8f to "1.8°", 3.0f to "3.0°").forEach { (tol, lbl) ->
                                val isSel = abs(tol - alignmentTolerance) < 0.1f
                                Button(
                                    onClick = { onAlignmentToleranceChange(tol) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text(lbl, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 5. HDR+ Burst Strength
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("HDR+ Burst Frames", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Exposure index level integration", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf(3, 9, 15).forEach { frames ->
                                val isSel = frames == hdPlusBurstCount
                                Button(
                                    onClick = { onHdPlusBurstCountChange(frames) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text("${frames}F", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 6. Audio Trigger selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("Capture Click FX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Shutter audible audio", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf("Muted", "Standard", "Beep").forEach { sound ->
                                val label = when(sound) {
                                    "Standard" -> "Standard Shutter"
                                    "Beep" -> "Electronic Beep"
                                    else -> "Muted"
                                }
                                val isSel = label == captureSoundEffect
                                Button(
                                    onClick = { onCaptureSoundEffectChange(label) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text(sound, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 7. Vibration Haptic
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("Haptic Feedback", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Confirmation tactile snap", color = Color.Gray, fontSize = 9.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            listOf("None", "Subtle", "Snap").forEach { vibe ->
                                val label = when(vibe) {
                                    "Subtle" -> "Subtle Pulse"
                                    "Snap" -> "Tactile snap"
                                    else -> "None"
                                }
                                val isSel = label == vibrationPower
                                Button(
                                    onClick = { onVibrationPowerChange(label) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFF4285F4) else Color(0xFF242731),
                                        contentColor = if (isSel) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                ) {
                                    Text(vibe, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Noise Reduc:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(76.dp))
                        Slider(
                            value = noiseReductionPower,
                            onValueChange = onNoiseReductionPowerChange,
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text("${noiseReductionPower.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Detail Boost:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(76.dp))
                        Slider(
                            value = detailEnhancementPower,
                            onValueChange = onDetailEnhancementPowerChange,
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text("${detailEnhancementPower.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            onResetCapture()
                            isSettingsOverlayOpen = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(32.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Grid", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("RESET SPHERE CAPTURE GRID", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

        // --- 8. HUD MINI FLOATING HEADER UTILITIES ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, start = 14.dp, end = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (isSensorActive && !isManualSwipeMode) Color(0xFF34A853) else Color(0xFFFFB74D), CircleShape)
                    )
                    Text(
                        text = "Y: ${String.format("%.0f", currentYaw)}° P: ${String.format("%.0f", currentPitch)}° R: ${String.format("%.0f", currentRoll)}°",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleManualSwipe,
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (isManualSwipeMode) Color(0xFF4285F4) else Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(Icons.Default.Swipe, contentDescription = "Manual Swipe Emulator", tint = Color.White, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = { isSettingsOverlayOpen = !isSettingsOverlayOpen },
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (isSettingsOverlayOpen) Color(0xFF4285F4) else Color.Black.copy(alpha = 0.55f), CircleShape)
                        .testTag("viewfinder_settings_toggle")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings preferences", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        // --- 9. CENTRAL FORCE CAPTURE SHUTTER SNAPPERS ---
        if (nextTargetNode == null && totalCaptured < nodes.size) {
            Button(
                onClick = {
                    val closest = activeNodes.filter { !it.captured }.minByOrNull {
                        var dy = it.targetYaw - currentYaw
                        while (dy > 180f) dy -= 360f
                        while (dy < -180f) dy += 360f
                        dy * dy + (it.targetPitch - currentPitch) * (it.targetPitch - currentPitch)
                    }
                    closest?.let { onTriggerBurstCapture(it) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853).copy(alpha = 0.85f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp)
                    .testTag("manual_shutter_btn")
            ) {
                Icon(Icons.Default.Camera, contentDescription = "Snap Align", modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lock Nearest Sector", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // --- 10. GCAM CLASSIC THREE-BUTTON BOTTOM BAR CONSOLE (Floating round buttons) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp, start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Far Left: Undo Last Capture button
            IconButton(
                onClick = onUndoLastCapture,
                enabled = totalCaptured > 0,
                modifier = Modifier
                    .size(56.dp)
                    .border(1.5.dp, if (totalCaptured > 0) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .testTag("capture_undo_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Undo,
                    contentDescription = "Undo Last Captured Node",
                    tint = if (totalCaptured > 0) Color.White else Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Middle: Stitch and Done Checkmark button inside gorgeous blue circular ring
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (totalCaptured > 0) Color(0xFF4285F4) else Color(0x334285F4))
                    .clickable(enabled = totalCaptured > 0) { onStartStitching() }
                    .testTag("stitch_now_btn"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Confirm Stitching",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Far Right: Exit / Cancel button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(56.dp)
                    .border(1.5.dp, Color.White, CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .testTag("capture_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Capture",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun CameraXPreviewView(
    modifier: Modifier = Modifier,
    onImageCaptureRegistered: (((onPhotoCaptured: (Bitmap) -> Unit) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    LaunchedEffect(imageCapture) {
        onImageCaptureRegistered?.invoke { onPhotoCaptured ->
            val executor = ContextCompat.getMainExecutor(context)
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()
                        onPhotoCaptured(bitmap)
                    } catch (e: Exception) {
                        Log.e("CameraX", "Failed to convert ImageProxy to Bitmap", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Failed to capture image", exception)
                }
            })
        }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = CameraPreview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraXPreview", "Lifecycle binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

// Helper to convert CameraX ImageProxy back to standard Android Bitmap
fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
        return Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    }
    if (imageInfo.rotationDegrees != 0) {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}

// Generate highly detailed orientation-specific landscape/sky view to simulate real capture in simulation/fallback
fun generateSimulatedPhotoAtState(
    yaw: Float,
    pitch: Float,
    lensName: String
): Bitmap {
    val w = 640
    val h = 480
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = android.graphics.Paint()

    // Create a beautiful sky gradient mapped to vertical pitch
    // Tilting up shows darker blue skies, tilting down shows warmer sunset colors on the horizon
    val normalizedPitch = ((pitch + 90f) / 180f).coerceIn(0f, 1f)
    val skyColors = intArrayOf(
        android.graphics.Color.rgb(10, 15, 38),   // Zenith Space Blue
        android.graphics.Color.rgb(65, 45, 95),   // Dusk Twilight
        android.graphics.Color.rgb(215, 100, 50)  // Gold horizon glow
    )
    val skyPositions = floatArrayOf(0f, 0.5f, 1f)
    
    // Offset the gradient center dynamically with the pitch angle so looking up/down pans the sky!
    val gradientYOffset = h * 0.5f + (pitch / 90f) * h * 0.4f
    val skyGrad = android.graphics.LinearGradient(
        0f, gradientYOffset - h, 0f, gradientYOffset + h * 0.5f,
        skyColors, skyPositions, android.graphics.Shader.TileMode.CLAMP
    )
    paint.shader = skyGrad
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null

    // Draw procedural stars that translate realistically based on yaw & pitch!
    paint.color = android.graphics.Color.WHITE
    paint.style = android.graphics.Paint.Style.FILL
    
    // Constant seed per sector to make star positions identical whenever looking at this cell
    val sectorSeed = (yaw.toInt().toLong() * 37) + (pitch.toInt().toLong() * 23)
    val rand = java.util.Random(sectorSeed)
    for (i in 1..35) {
        // Translate stars dynamically relative to yaw and pitch sub-degrees to simulate panning
        val sx = (rand.nextFloat() * w * 1.5f - w * 0.25f)
        val sy = (rand.nextFloat() * h * 1.5f - h * 0.25f)
        paint.alpha = (rand.nextFloat() * 140 + 100).toInt()
        val sSize = rand.nextFloat() * 2.8f + 0.8f
        canvas.drawCircle(sx, sy, sSize, paint)
    }
    paint.alpha = 255

    // Draw glowing sun on the horizon if looking near yaw 0, pitch 0 (the default center)
    var diffSunYaw = yaw
    while (diffSunYaw > 180f) diffSunYaw -= 360f
    while (diffSunYaw < -180f) diffSunYaw += 360f
    
    val sunDistanceYaw = abs(diffSunYaw)
    val sunDistancePitch = abs(pitch - 10f) // Sun placed at 10 degrees elevation
    if (sunDistanceYaw < 50f && sunDistancePitch < 40f) {
        // Project sun coordinates onto this capture thumbnail
        val sx = w / 2f - (diffSunYaw / 50f) * (w / 2f)
        val sy = h / 2f + ((pitch - 10f) / 40f) * (h / 2f)
        
        val radialSun = android.graphics.RadialGradient(
            sx, sy, 120f,
            android.graphics.Color.argb(180, 255, 230, 160),
            android.graphics.Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = radialSun
        canvas.drawCircle(sx, sy, 120f, paint)
        paint.shader = null
        
        paint.color = android.graphics.Color.rgb(255, 245, 210)
        canvas.drawCircle(sx, sy, 25f, paint)
    }

    // Draw mountain ridge if near the horizon
    if (pitch in -40f..40f) {
        paint.color = android.graphics.Color.rgb(31, 23, 44)
        val mountPath = android.graphics.Path()
        mountPath.moveTo(0f, h.toFloat())
        
        var currentY = h * 0.72f + (pitch / 40f) * h * 0.3f
        mountPath.lineTo(0f, currentY)
        
        val ridgeSeed = (yaw.toInt().toLong() * 101)
        val ridgeRand = java.util.Random(ridgeSeed)
        for (x in 20..w step 20) {
            currentY += (ridgeRand.nextFloat() - 0.5f) * 35f
            currentY = currentY.coerceIn(h * 0.45f, h * 0.95f)
            mountPath.lineTo(x.toFloat(), currentY)
        }
        mountPath.lineTo(w.toFloat(), h.toFloat())
        mountPath.close()
        canvas.drawPath(mountPath, paint)
    }

    // Grid lines mapping spherical latitude and longitude (mathematical grid)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.color = android.graphics.Color.argb(60, 66, 133, 244)
    paint.strokeWidth = 1.5f
    canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, paint)
    canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), paint)

    // Stamp Lens diagnostics and coordinates (Authentic 1:1 camera overlay)
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.argb(200, 255, 255, 255)
    paint.textSize = 15f
    paint.isAntiAlias = true
    canvas.drawText("FIELD OF VIEW: $lensName", 24f, 40f, paint)
    canvas.drawText("PITCH: ${String.format("%.1f", pitch)}° | YAW: ${String.format("%.1f", yaw)}°", 24f, 62f, paint)

    paint.color = android.graphics.Color.argb(120, 255, 255, 255)
    paint.textSize = 12f
    canvas.drawText("HDR+ MULTI-BAND SPLINED FRAME", 24f, 82f, paint)

    // Glowing coordinate capture target
    paint.style = android.graphics.Paint.Style.STROKE
    paint.color = android.graphics.Color.argb(180, 52, 168, 83)
    paint.strokeWidth = 2.5f
    canvas.drawCircle(w / 2f, h / 2f, 28f, paint)

    return bitmap
}


// ------------------ 3. STITCHING PROGRESS SCREEN (CONSOLES) ------------------
@Composable
fun StitchingProgressScreen(
    pipelineState: StitchingPipeline.PipelineState,
    progress: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper status card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "STITCHING SECTOR ARCHIVE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Resolving APAP Projection Transform Splines",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    // Rotating processing loader spinner
                    CircularProgressIndicator(
                        color = Color(0xFF4285F4),
                        modifier = Modifier.size(24.dp)
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    color = Color(0xFF4285F4),
                    trackColor = Color(0xFF23252E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Current Stage: ${pipelineState.phase.name}",
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${(progress * 100).toInt()}% completed",
                        color = Color(0xFF4285F4),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Live computational grid visualizer
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1014)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF22252E)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "CERES GRAPH OPTIMIZATION RESIDUALS",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Beautiful custom canvas outputting warps & SIFT matches
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF07080B))
                ) {
                    val w = size.width
                    val h = size.height

                    // Draw APAP Mesh warping grid Lines
                    val gridCols = 16
                    val gridRows = 8
                    val dx = w / gridCols
                    val dy = h / gridRows

                    val paintMesh = AndroidPaint().apply {
                        color = AndroidColor.argb(30, 66, 133, 244)
                        style = AndroidPaint.Style.STROKE
                        strokeWidth = 1.5f
                    }

                    // Generate a deformed triangulation warping mesh representation
                    for (i in 0..gridCols) {
                        val path = AndroidPath()
                        val baseX = i * dx
                        path.moveTo(baseX, 0f)
                        for (j in 1..gridRows) {
                            val targetY = j * dy
                            // Apply sinusoidal local deformity to simulate non-rigid APAP transformation
                            val noiseX = baseX + sin((baseX + targetY + progress * 500f) * 0.02f) * 8f * progress
                            path.lineTo(noiseX, targetY)
                        }
                        this.drawContext.canvas.nativeCanvas.drawPath(path, paintMesh)
                    }

                    for (j in 0..gridRows) {
                        val path = AndroidPath()
                        val baseY = j * dy
                        path.moveTo(0f, baseY)
                        for (i in 1..gridCols) {
                            val targetX = i * dx
                            val noiseY = baseY + cos((targetX + baseY + progress * 500f) * 0.02f) * 6f * progress
                            path.lineTo(targetX, noiseY)
                        }
                        this.drawContext.canvas.nativeCanvas.drawPath(path, paintMesh)
                    }

                    // Plot matching SIFT keypoints with linking alignment residual lines
                    val random = java.util.Random(99L)
                    for (k in 0..25) {
                        val x1 = random.nextFloat() * w
                        val y1 = random.nextFloat() * h
                        val offsetScalar = (pipelineState.alignmentError / 35f).coerceIn(0f, 1f)
                        val x2 = x1 + (random.nextFloat() - 0.5f) * 35f * offsetScalar
                        val y2 = y1 + (random.nextFloat() - 0.5f) * 35f * offsetScalar

                        // Dot 1 (Reference)
                        drawCircle(Color(0xFF81C784), radius = 3f, center = Offset(x1, y1))
                        // Dot 2 (Candidate alignment destination)
                        drawCircle(Color(0xFFE57373), radius = 2f, center = Offset(x2, y2))
                        // Connector
                        drawLine(Color(0x77FFFFFF), Offset(x1, y1), Offset(x2, y2), 1f)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Ceres parameter readout console
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("CERES OPTIMIZER RESIDUALS", color = Color.Gray, fontSize = 9.sp)
                        Text(
                            text = "L2 Norm Error: ${String.format("%.4f", pipelineState.alignmentError)} px",
                            color = Color(0xFFF06292),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("SIFT OVERLAP CORRESPONDENCES", color = Color.Gray, fontSize = 9.sp)
                        Text(
                            text = "${pipelineState.sftKeypointsFound} Points Verified",
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Divider(color = Color(0xFF22252E), thickness = 1.dp)

                Spacer(modifier = Modifier.height(10.dp))

                // Real-time computational compiler logs terminal console
                Text(
                    text = "STITCHING SYSTEM PROCESS LOGS:",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                val lazyScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.5f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF040507))
                        .padding(8.dp)
                        .verticalScroll(lazyScrollState)
                ) {
                    val logs = pipelineState.consoleLog
                    // Automatically scroll console down
                    LaunchedEffect(logs.size) {
                        lazyScrollState.animateScrollTo(lazyScrollState.maxValue)
                    }
                    Column {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                color = if (log.contains("SUCCESS") || log.contains("Complete")) Color(0xFF81C784) else Color(0xFFD1D1D1),
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 3.dp),
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


// ------------------ 4. INTERACTIVE 360 PANORAMA VIEWER INTERFACES ------------------
@Composable
fun Viewer360Screen(
    panorama: SavedPanorama?,
    baseBitmap: Bitmap?,
    developedBitmap: Bitmap?,
    selectedLens: String,
    exposure: Float,
    highlights: Float,
    shadows: Float,
    tempOffset: Float,
    toneCurvePreset: String,
    localContrast: Float,
    detailBoost: Float,
    vignette: Float,
    activeMode: String, // "360 Sphere", "Little Planet", "Flat Map"
    isDeveloping: Boolean,
    onModeChange: (String) -> Unit,
    onParamsChange: (Float, Float, Float, Float, String, Float, Float, Float) -> Unit,
    onReprocess: () -> Unit,
    onBack: () -> Unit
) {
    var viewYaw by remember { mutableStateFlowState(0f) }
    var viewPitch by remember { mutableStateFlowState(0f) }
    var viewZoom by remember { mutableStateFlowState(85f) } // Field of view (degrees)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Nav Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color(0xFF1E212A), CircleShape)
                    .testTag("viewer_back_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Return", tint = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = panorama?.title ?: "Unresolved Sphere",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("GPano Equirectangular Projection Output", color = Color.Gray, fontSize = 10.sp)
            }

            IconButton(
                onClick = onReprocess,
                modifier = Modifier.background(Color(0xFF1E212A), CircleShape)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
        }

        // Viewing Projection Mode switcher tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF16181E))
                .padding(4.dp)
        ) {
            listOf("360 Sphere", "Little Planet", "Flat Map").forEach { mode ->
                val isSel = mode == activeMode
                Button(
                    onClick = { onModeChange(mode) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSel) Color(0xFF4285F4) else Color.Transparent,
                        contentColor = if (isSel) Color.White else Color.Gray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mode_tab_${mode.replace(" ", "_")}")
                ) {
                    Text(mode, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Panoramic render canvas viewport
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.8f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (developedBitmap != null && !isDeveloping) {
                    // Actual dynamic viewport rendering on Canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activeMode) {
                                if (activeMode == "360 Sphere") {
                                    // Pinch to zoom and Drag to yaw/pitch look around
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        // Update yaw of camera loop around
                                        viewYaw = (viewYaw - pan.x * 0.15f)
                                        if (viewYaw > 180f) viewYaw -= 360f
                                        if (viewYaw < -180f) viewYaw += 360f

                                        // Pitch bounded elevation limits
                                        viewPitch = (viewPitch + pan.y * 0.15f).coerceIn(-85f, 85f)
                                        
                                        // Zoom limits
                                        viewZoom = (viewZoom / zoom).coerceIn(30f, 130f)
                                    }
                                }
                            }
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height

                        val bmpW = developedBitmap.width
                        val bmpH = developedBitmap.height

                        when (activeMode) {
                            "Flat Map" -> {
                                // Classic Equirectangular flat representation scaled
                                val scaleWidth = canvasW
                                val scaleHeight = canvasW * (bmpH.toFloat() / bmpW.toFloat())
                                
                                val topOffset = (canvasH - scaleHeight) / 2f
                                
                                this.drawContext.canvas.nativeCanvas.drawBitmap(
                                    developedBitmap,
                                    null,
                                    AndroidRect(0, topOffset.toInt(), canvasW.toInt(), (topOffset + scaleHeight).toInt()),
                                    null
                                )
                            }

                            "360 Sphere" -> {
                                // High quality real-time cylindrical wrap viewpoint cropping renderer
                                val pxPerDegX = bmpW / 360f
                                val pxPerDegY = bmpH / 180f

                                val srcHeightDeg = viewZoom
                                val srcWidthDeg = viewZoom * (canvasW / canvasH)

                                val srcWidthPx = srcWidthDeg * pxPerDegX
                                val srcHeightPx = srcHeightDeg * pxPerDegY

                                // Center position coordinates corresponding to looking angles
                                val centerX = ((viewYaw + 180f) % 360f) * pxPerDegX
                                val centerY = (90f - viewPitch) * pxPerDegY

                                val left = (centerX - srcWidthPx / 2f)
                                val top = (centerY - srcHeightPx / 2f).coerceIn(0f, bmpH - srcHeightPx)

                                // Handle horizontal cyclic wrapping
                                if (left < 0) {
                                    // Part 1: Segment at the rightmost of the panorama
                                    val left1 = bmpW + left
                                    val right1 = bmpW.toFloat()
                                    val srcRect1 = AndroidRect(left1.toInt(), top.toInt(), bmpW, (top + srcHeightPx).toInt())
                                    
                                    val screenSplitX = (-left / srcWidthPx) * canvasW
                                    val dstRect1 = AndroidRect(0, 0, screenSplitX.toInt(), canvasH.toInt())
                                    this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, srcRect1, dstRect1, null)

                                    // Part 2: Segment at the leftmost of the panorama
                                    val left2 = 0f
                                    val right2 = left + srcWidthPx
                                    val srcRect2 = AndroidRect(left2.toInt(), top.toInt(), right2.toInt(), (top + srcHeightPx).toInt())
                                    val dstRect2 = AndroidRect(screenSplitX.toInt(), 0, canvasW.toInt(), canvasH.toInt())
                                    this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, srcRect2, dstRect2, null)
                                } else if (left + srcWidthPx > bmpW) {
                                    // Part 1: Segment at the leftmost of the panorama
                                    val left1 = left
                                    val srcRect1 = AndroidRect(left1.toInt(), top.toInt(), bmpW, (top + srcHeightPx).toInt())
                                    
                                    val visibleWidthLeft = bmpW - left
                                    val screenSplitX = (visibleWidthLeft / srcWidthPx) * canvasW
                                    val dstRect1 = AndroidRect(0, 0, screenSplitX.toInt(), canvasH.toInt())
                                    this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, srcRect1, dstRect1, null)

                                    // Part 2: Overlapping wrap segment back to left zero
                                    val right2 = (left + srcWidthPx) - bmpW
                                    val srcRect2 = AndroidRect(0, top.toInt(), right2.toInt(), (top + srcHeightPx).toInt())
                                    val dstRect2 = AndroidRect(screenSplitX.toInt(), 0, canvasW.toInt(), canvasH.toInt())
                                    this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, srcRect2, dstRect2, null)
                                } else {
                                    // Seamless simple center rectangle block
                                    val srcRect = AndroidRect(left.toInt(), top.toInt(), (left + srcWidthPx).toInt(), (top + srcHeightPx).toInt())
                                    val dstRect = AndroidRect(0, 0, canvasW.toInt(), canvasH.toInt())
                                    this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, srcRect, dstRect, null)
                                }

                                // Interactive navigation coordinates helper details drawn in viewport corners
                                val indicatorPaint = AndroidPaint().apply {
                                    color = AndroidColor.argb(160, 255, 255, 255)
                                    textSize = 24f
                                    textAlign = AndroidPaint.Align.RIGHT
                                    isAntiAlias = true
                                }
                                this.drawContext.canvas.nativeCanvas.drawText(
                                    "LOOK DIRECT: YAW ${String.format("%.1f", viewYaw)}° | PITCH ${String.format("%.1f", viewPitch)}°",
                                    canvasW - 20f, canvasH - 25f, indicatorPaint
                                )
                            }

                            "Little Planet" -> {
                                // High quality mathematically stereographic polar projection render representation
                                val radius = min(canvasW, canvasH) * 0.44f
                                val cx = canvasW / 2f
                                val cy = canvasH / 2f

                                // We draw a circular globe clipping where the background panorama is wrapped around a sphere
                                val globePaint = AndroidPaint().apply {
                                    isAntiAlias = true
                                }
                                
                                // Draw atmospheric space glow halo
                                val haloPaint = AndroidPaint().apply {
                                    color = AndroidColor.rgb(40, 30, 80)
                                    style = AndroidPaint.Style.STROKE
                                    strokeWidth = 24f
                                }
                                this.drawContext.canvas.nativeCanvas.drawCircle(cx, cy, radius + 12f, haloPaint)

                                // Dynamic rotation of the little planet
                                this.drawContext.canvas.nativeCanvas.save()
                                this.drawContext.canvas.nativeCanvas.rotate(viewYaw, cx, cy)

                                val srcRect = AndroidRect(0, (bmpH * 0.4f).toInt(), bmpW, bmpH)
                                val dstRect = AndroidRect((cx - radius).toInt(), (cy - radius).toInt(), (cx + radius).toInt(), (cy + radius).toInt())
                                
                                // Draw scenic elements clipping into circular radial planet coordinates mask
                                val clipPath = AndroidPath()
                                clipPath.addCircle(cx, cy, radius, AndroidPath.Direction.CCW)
                                this.drawContext.canvas.nativeCanvas.save()
                                this.drawContext.canvas.nativeCanvas.clipPath(clipPath)
                                this.drawContext.canvas.nativeCanvas.drawBitmap(developedBitmap, null, dstRect, globePaint)
                                this.drawContext.canvas.nativeCanvas.restore()

                                this.drawContext.canvas.nativeCanvas.restore()

                                // Ring highlight lines around outer bounds
                                val borderPaint = AndroidPaint().apply {
                                    color = AndroidColor.argb(100, 255, 255, 255)
                                    style = AndroidPaint.Style.STROKE
                                    strokeWidth = 3f
                                }
                                this.drawContext.canvas.nativeCanvas.drawCircle(cx, cy, radius, borderPaint)

                                val labelPaint = AndroidPaint().apply {
                                    color = AndroidColor.rgb(255, 170, 70)
                                    textSize = 28f
                                    textAlign = AndroidPaint.Align.CENTER
                                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                }
                                this.drawContext.canvas.nativeCanvas.drawText("LITTLE PLANET POLAR TRANS", cx, cy + radius + 32f, labelPaint)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF4285F4))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("DEVELOPING LINEAR DNG LATTICES...", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // --- RAW DEVELOPER SLIDERS CONTROL PANEL ---
        // Dynamically unlocks edit exposure/shadows/highlights in high dynamic range Linear DNG state!
        if (panorama?.rawOutput == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16181E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("raw_dev_panel")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Editor icon", tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                            Text(
                                text = "RAW LINEAR DNG DEVELOPER UNIT",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB74D),
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                        Button(
                            onClick = onReprocess,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Re-develop", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Exposure EV Slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Exposure: ", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = exposure,
                            onValueChange = { onParamsChange(it, highlights, shadows, tempOffset, toneCurvePreset, localContrast, detailBoost, vignette) },
                            valueRange = -3.0f..3.0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                                .testTag("exposure_slider")
                        )
                        Text("${if (exposure >= 0) "+" else ""}${String.format("%.1f", exposure)} EV", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // Highlight compression
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Highlights: ", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = highlights,
                            onValueChange = { onParamsChange(exposure, it, shadows, tempOffset, toneCurvePreset, localContrast, detailBoost, vignette) },
                            valueRange = -100f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Text("${highlights.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // Shadow Boost
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shadows: ", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = shadows,
                            onValueChange = { onParamsChange(exposure, highlights, it, tempOffset, toneCurvePreset, localContrast, detailBoost, vignette) },
                            valueRange = -100f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Text("${shadows.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // WB Temp adjustment
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Temp Offset:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Slider(
                            value = tempOffset,
                            onValueChange = { onParamsChange(exposure, highlights, shadows, it, toneCurvePreset, localContrast, detailBoost, vignette) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Text("${(tempOffset * 3000).toInt() + 5500} K", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // Tone Curve Preset selection row buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tone Curve:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            listOf("Cinematic", "Contrast", "Astro", "Flat").forEach { curve ->
                                val isSel = when (curve) {
                                    "Cinematic" -> toneCurvePreset.contains("Cinematic")
                                    "Contrast" -> toneCurvePreset.contains("Contrast")
                                    "Astro" -> toneCurvePreset.contains("Astro")
                                    else -> !toneCurvePreset.contains("Cinematic") && !toneCurvePreset.contains("Contrast") && !toneCurvePreset.contains("Astro")
                                }
                                val label = when(curve) {
                                    "Cinematic" -> "Cinematic (GCam Modern)"
                                    "Contrast" -> "HDR+ Deep Contrast"
                                    "Astro" -> "Astro Sight Glow"
                                    else -> "Balanced Flat Map"
                                }
                                Button(
                                    onClick = { onParamsChange(exposure, highlights, shadows, tempOffset, label, localContrast, detailBoost, vignette) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) Color(0xFFFFB74D) else Color(0xFF262930),
                                        contentColor = if (isSel) Color.Black else Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(24.dp)
                                ) {
                                    Text(curve, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Local Contrast Slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Contrast:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Slider(
                            value = localContrast,
                            onValueChange = { onParamsChange(exposure, highlights, shadows, tempOffset, toneCurvePreset, it, detailBoost, vignette) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text("${localContrast.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // Detail Sharpening Slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sharpening:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Slider(
                            value = detailBoost,
                            onValueChange = { onParamsChange(exposure, highlights, shadows, tempOffset, toneCurvePreset, localContrast, it, vignette) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text("${detailBoost.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }

                    // Radial Vignette Slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vignette:", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
                        Slider(
                            value = vignette,
                            onValueChange = { onParamsChange(exposure, highlights, shadows, tempOffset, toneCurvePreset, localContrast, detailBoost, it) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text("${vignette.toInt()}%", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                    }
                }
            }
        } else {
            // Standard static information cards for normal JPEG output
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16181E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Info icon", tint = Color(0xFF4285F4))
                    Column {
                        Text("Standard Dynamic Range mode active", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Captured via standard 8-bit pipeline. Re-shoot enabling 'RAW Linear DNG' to unlock complete highlight/shadow adjustment sliders.", color = Color.Gray, fontSize = 9.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }

        // GPano XML Metadata explorer card
        var expandMetaData by remember { mutableStateFlowState(false) }
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(
                onClick = { expandMetaData = !expandMetaData },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121318)),
                border = BorderStroke(1.dp, Color(0xFF22252F)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = "Meta icon", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        Text("EXPLAIN GPANO XML METADATA SCHEMA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        if (expandMetaData) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "exp",
                        tint = Color.Gray
                    )
                }
            }

            if (expandMetaData) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF040507))
                        .padding(10.dp)
                ) {
                    val xmlMeta = """
                        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
                          <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
                          <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
                          <GPano:PoseHeadingDegrees>${String.format("%.2f", viewYaw)}</GPano:PoseHeadingDegrees>
                          <GPano:PosePitchDegrees>${String.format("%.2f", viewPitch)}</GPano:PosePitchDegrees>
                          <GPano:PoseRollDegrees>0.00</GPano:PoseRollDegrees>
                          <GPano:FullPanoWidthPixels>2048</GPano:FullPanoWidthPixels>
                          <GPano:FullPanoHeightPixels>1024</GPano:FullPanoHeightPixels>
                          <GPano:CroppedAreaImageWidthPixels>2048</GPano:CroppedAreaImageWidthPixels>
                          <GPano:CroppedAreaImageHeightPixels>1024</GPano:CroppedAreaImageHeightPixels>
                          <GPano:SourceLens>${selectedLens}</GPano:SourceLens>
                        </rdf:Description>
                    """.trimIndent()
                    Text(
                        text = xmlMeta,
                        color = Color(0xFF9CCC65),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}
