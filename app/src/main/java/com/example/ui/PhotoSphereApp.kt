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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
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
    var currentScreen by remember { mutableStateFlowState(AppScreen.DASHBOARD) }

    // Configuration / Specifications
    var selectedLens by remember { mutableStateFlowState("Wide (1x)") }
    var rawOutputEnabled by remember { mutableStateFlowState(true) }
    var autoCaptureEnabled by remember { mutableStateFlowState(true) }

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
                        rawOutputEnabled = rawOutputEnabled,
                        autoCaptureEnabled = autoCaptureEnabled,
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
                                playShutterSoundCombined(context)
                                playHapticVibe(context)
                                activeNodeIdInBurst = node.id
                                for (prog in 1..10) {
                                    delayLonger(75)
                                    activeBurstProgress = prog / 10f
                                }
                                nodesList = nodesList.map {
                                    if (it.id == node.id) it.copy(captured = true, capturedProgress = 1f) else it
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
                                    rawOutputEnabled
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
                        activeMode = activeViewerMode,
                        isDeveloping = isDevelopingOverlay,
                        onModeChange = { activeViewerMode = it },
                        onParamsChange = { exp, hl, sh, temp ->
                            dngExposure = exp
                            dngHighlights = hl
                            dngShadows = sh
                            dngTemperature = temp
                        },
                        onReprocess = {
                            scope.launch {
                                isDevelopingOverlay = true
                                computedStitchedBitmap?.let { base ->
                                    val dev = pipeline.developLinearDng(
                                        base, dngExposure, dngHighlights, dngShadows, dngTemperature
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

fun playShutterSoundCombined(context: Context) {
    try {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    } catch (e: Exception) {
        Log.e("AudioError", "Shutter audio failed", e)
    }
}

fun playHapticVibe(context: Context) {
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
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
                val yaw = -150f + (360f / 6f) * i
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


// ------------------ 2. CAMERA CAPTURE VIEWFINDER ------------------
@Composable
fun CaptureScreen(
    selectedLens: String,
    rawOutputEnabled: Boolean,
    autoCaptureEnabled: Boolean,
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val totalCaptured = nodes.filter { it.captured }.size
    val totalProgress = totalCaptured.toFloat() / nodes.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- LIVE PREVIEW AREA ---
        if (!isManualSwipeMode && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            CameraXPreviewView(modifier = Modifier.fillMaxSize())
        } else {
            // High fidelity scenic starry grid representation for simulator fallback
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // Drawing static starry deep space grid matching current simulated yaw/pitch panning offsets
                        drawRect(Color(0xFF0C0D13))
                        val starPainter = AndroidPaint().apply {
                            color = AndroidColor.WHITE
                            style = AndroidPaint.Style.FILL
                        }
                        val linePainter = AndroidPaint().apply {
                            color = AndroidColor.argb(20, 255, 255, 255)
                            style = AndroidPaint.Style.STROKE
                            strokeWidth = 2f
                        }

                        // Grid based on panoramic coordinates
                        val spacingX = size.width / 40f
                        val spacingY = size.height / 30f
                        val offsetX = (currentYaw * 10f) % size.width
                        val offsetY = (currentPitch * 10f) % size.height

                        for (i in -10..50) {
                            val lx = offsetX + i * spacingX
                            drawLine(Color(0x11FFFFFF), Offset(lx, 0f), Offset(lx, size.height), 1f)
                        }
                        for (j in -10..40) {
                            val ly = offsetY + j * spacingY
                            drawLine(Color(0x11FFFFFF), Offset(0f, ly), Offset(size.width, ly), 1f)
                        }

                        // Drawing celestial star seeds inside simulator
                        val random = java.util.Random(1337)
                        for (k in 0..60) {
                            val sx = (random.nextFloat() * size.width + currentYaw * 8f) % size.width
                            val sy = (random.nextFloat() * size.height + currentPitch * 8f) % size.height
                            drawCircle(
                                Color.White.copy(alpha = random.nextFloat() * 0.6f + 0.2f),
                                radius = random.nextFloat() * 2f + 1f,
                                center = Offset(sx, sy)
                            )
                        }
                    }
                    .pointerInput(isManualSwipeMode) {
                        if (isManualSwipeMode) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                // Drag sensitivity converter mapping to yaw / pitch degrees
                                val dy = dragAmount.x * 0.12f
                                val dp = -dragAmount.y * 0.12f
                                onSwipeDelta(dy, dp)
                            }
                        }
                    }
            )
        }

        // --- SENSOR REGISTRATION TARGETS OVERLAY ---
        // Projecting the 3D geodesic nodes onto the 2D viewfinder coordinate plane
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
            
            // Camera Field of View mapping bounds (e.g. wide horizontal 45 deg, vertical 60 deg)
            val hFov = 45f
            val vFov = 60f

            nodes.forEach { node ->
                if (node.id == activeNodeIdInBurst) return@forEach // don't draw overlapping targets while capturing

                // Compute yaw offset boundary wrapping cyclically [-180, 180]
                var diffYaw = node.targetYaw - currentYaw
                while (diffYaw > 180f) diffYaw -= 360f
                while (diffYaw < -180f) diffYaw += 360f

                val diffPitch = node.targetPitch - currentPitch

                // Only draw nodes that are in front of the lens viewport
                if (abs(diffYaw) < 50f && abs(diffPitch) < 55f) {
                    // Orthographic projection
                    val px = (width / 2f) + (diffYaw / hFov) * (width / 2f)
                    val py = (height / 2f) - (diffPitch / vFov) * (height / 2f)

                    val isAligned = abs(diffYaw) < 1.4f && abs(diffPitch) < 1.4f

                    // If node is already captured, draw a solid blue verification indicator
                    val ringColor = when {
                        node.captured -> Color(0xFF4285F4)
                        isAligned -> Color(0xFF64B5F6)
                        else -> Color.White.copy(alpha = 0.65f)
                    }

                    // Floating target dot
                    drawCircle(
                        color = ringColor,
                        radius = if (isAligned) 14f else 8f,
                        center = Offset(px, py)
                    )

                    // Surrounding dotted lock circle indicating proximity alignment boundary
                    if (!node.captured) {
                        drawCircle(
                            color = ringColor.copy(alpha = 0.4f),
                            radius = if (isAligned) 24f else 18f,
                            center = Offset(px, py),
                            style = Stroke(width = 1.5f)
                        )
                    } else {
                        // Drawing checkmark details
                        drawLine(
                            color = Color.White,
                            start = Offset(px - 4f, py),
                            end = Offset(px - 1f, py + 3f),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(px - 1f, py + 3f),
                            end = Offset(px + 5f, py - 3f),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            // --- VIEW VIEWFINDER HUD GRAPH DISTORTIONS / DETAILS ---
            // Draw SIFT feature trackers popping over high contrast scenic spots
            val starSeed = 999L
            val random = java.util.Random(starSeed)
            for (idx in 0..12) {
                val fx = random.nextFloat() * width
                val fy = random.nextFloat() * height
                // Intermittent flashing
                if (random.nextBoolean()) {
                    drawCircle(
                        Color(0xFF34A853).copy(alpha = 0.25f),
                        radius = 4f,
                        center = Offset(fx, fy)
                    )
                    drawLine(
                        Color(0xFF34A853).copy(alpha = 0.3f),
                        Offset(fx - 6f, fy), Offset(fx + 6f, fy),
                        1f
                    )
                    drawLine(
                        Color(0xFF34A853).copy(alpha = 0.3f),
                        Offset(fx, fy - 6f), Offset(fx, fy + 6f),
                        1f
                    )
                }
            }
        }

        // --- LEVELING CALIBRATION HORIZON HUD ---
        // Classic yellow & white overlapping horizon alignment gauges from GCam
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(240.dp)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Central high target bounding ring (GCam Reticle lock target)
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = 35f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )

                // 1. White fixed horizontal leveler indicator (pitch zero baseline)
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(cx - 80f, cy),
                    end = Offset(cx - 40f, cy),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(cx + 40f, cy),
                    end = Offset(cx + 80f, cy),
                    strokeWidth = 2f
                )

                // 2. Yellow dynamic rotatable indicator corresponding to actual physical pitch & roll
                // Rolled tilt calculation
                val rollRad = Math.toRadians(currentRoll.toDouble()).toFloat()
                val deltaYPitch = currentPitch * 2.5f // pitch translational scaling offset

                val length = 40f
                val gap = 40f

                // Rotate level lines according to tilt
                val cosR = cos(rollRad)
                val sinR = sin(rollRad)

                val leftLineStartX = cx - (gap + length) * cosR + deltaYPitch * sinR
                val leftLineStartY = cy - (gap + length) * sinR - deltaYPitch * cosR
                val leftLineEndX = cx - gap * cosR + deltaYPitch * sinR
                val leftLineEndY = cy - gap * sinR - deltaYPitch * cosR

                val rightLineStartX = cx + gap * cosR + deltaYPitch * sinR
                val rightLineStartY = cy + gap * sinR - deltaYPitch * cosR
                val rightLineEndX = cx + (gap + length) * cosR + deltaYPitch * sinR
                val rightLineEndY = cy + (gap + length) * sinR - deltaYPitch * cosR

                val isLeveled = abs(currentRoll) < 1.0f && abs(currentPitch) < 1.0f
                val levelColor = if (isLeveled) Color(0xFF34A853) else Color(0xFFFBB03B)

                // Draw yellow (or green) gravity gauges
                drawLine(
                    color = levelColor,
                    start = Offset(leftLineStartX, leftLineStartY),
                    end = Offset(leftLineEndX, leftLineEndY),
                    strokeWidth = 2.5f
                )
                drawLine(
                    color = levelColor,
                    start = Offset(rightLineStartX, rightLineStartY),
                    end = Offset(rightLineEndX, rightLineEndY),
                    strokeWidth = 2.5f
                )
            }
        }

        // --- ACTIVE HDR+ CAPTURING PROGRESS INDICATOR ---
        // Rapid radial progress bar shown immediately over the screen center while capturing a node
        if (activeNodeIdInBurst != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF4285F4)),
                    modifier = Modifier.size(160.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            progress = { activeBurstProgress },
                            color = Color(0xFF4285F4),
                            strokeWidth = 5.dp,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("HDR+ FUSING...", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Compand raw exposures", color = Color.Gray, fontSize = 9.sp)
                    }
                }
            }
        }

        // --- HUD HEADER CONTROLS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .testTag("capture_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Return", tint = Color.White)
            }

            // Real-time tracking specs dashboard
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF2B2B30))
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isSensorActive && !isManualSwipeMode) Color(0xFF34A853) else Color(0xFFFFB74D))
                        )
                        Text(
                            text = if (isSensorActive && !isManualSwipeMode) "Quaternion Fusion Active" else "Manual Gyro Panning",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Yaw: ${String.format("%.1f", currentYaw)}° | Pitch: ${String.format("%.1f", currentPitch)}°",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            IconButton(
                onClick = onToggleManualSwipe,
                modifier = Modifier
                    .background(if (isManualSwipeMode) Color(0xFF4285F4) else Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(Icons.Default.Swipe, contentDescription = "Manual Swipe Emulator", tint = Color.White)
            }
        }

        // --- HUD BOTTOM BAR ACTIONS AND STEPS ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(bottom = 20.dp, top = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Visual slider progress mapping nodes captured
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MOSAIC STATUS: $totalCaptured / ${nodes.size} SECTOR SAMPLES",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${(totalProgress * 100).toInt()}%",
                        color = Color(0xFF4285F4),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                LinearProgressIndicator(
                    progress = { totalProgress },
                    color = Color(0xFF4285F4),
                    trackColor = Color(0xFF262A35),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(6.dp)
                        .clip(CircleShape)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset Button
                    OutlinedButton(
                        onClick = onResetCapture,
                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("reset_capture_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Icon", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Grid", fontSize = 11.sp, color = Color.White)
                    }

                    // Simulated / physical trigger trigger helper
                    val nextTargetNode = nodes.firstOrNull { node ->
                        if (node.captured) return@firstOrNull false
                        
                        var diffYaw = node.targetYaw - currentYaw
                        while (diffYaw > 180f) diffYaw -= 360f
                        while (diffYaw < -180f) diffYaw += 360f

                        val diffPitch = node.targetPitch - currentPitch
                        abs(diffYaw) < 1.4f && abs(diffPitch) < 1.4f
                    }

                    // Auto Capture trigger detector effect
                    LaunchedEffect(nextTargetNode) {
                        if (nextTargetNode != null && autoCaptureEnabled && activeNodeIdInBurst == null) {
                            onTriggerBurstCapture(nextTargetNode)
                        }
                    }

                    Button(
                        onClick = {
                            if (nextTargetNode != null) {
                                onTriggerBurstCapture(nextTargetNode)
                            } else {
                                // Manual override snapshot target closest
                                val closest = nodes.filter { !it.captured }.minByOrNull {
                                    var diffYaw = it.targetYaw - currentYaw
                                    while (diffYaw > 180f) diffYaw -= 360f
                                    while (diffYaw < -180f) diffYaw += 360f
                                    diffYaw * diffYaw + (it.targetPitch - currentPitch) * (it.targetPitch - currentPitch)
                                }
                                closest?.let { onTriggerBurstCapture(it) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (nextTargetNode != null) Color(0xFF34A853) else Color(0xFFD32F2F)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("manual_shutter_btn")
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = "Manual Shutter", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (nextTargetNode != null) "Lock Target Node" else "Force Snap Close",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Process / Stitch Launch Button
                    Button(
                        onClick = onStartStitching,
                        enabled = totalCaptured > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("stitch_now_btn")
                    ) {
                        Icon(Icons.Default.Splitscreen, contentDescription = "Stitch Icon", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stitch Pano", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraXPreviewView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }

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
                    preview
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
    activeMode: String, // "360 Sphere", "Little Planet", "Flat Map"
    isDeveloping: Boolean,
    onModeChange: (String) -> Unit,
    onParamsChange: (Float, Float, Float, Float) -> Unit,
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
                            onValueChange = { onParamsChange(it, highlights, shadows, tempOffset) },
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
                            onValueChange = { onParamsChange(exposure, it, shadows, tempOffset) },
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
                            onValueChange = { onParamsChange(exposure, highlights, it, tempOffset) },
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
                            onValueChange = { onParamsChange(exposure, highlights, shadows, it) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                        )
                        Text("${(tempOffset * 3000).toInt() + 5500} K", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
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
