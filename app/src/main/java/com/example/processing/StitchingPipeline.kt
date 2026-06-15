package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.example.model.PhotoSphereNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StitchingPipeline {

    enum class Phase {
        IDLE,
        HDR_FUSION,             // Stage 1: Burst alignment & denoising
        FEATURE_REGISTRATION,   // Stage 2: SIFT points & RANSAC matching
        APAP_WARPING,           // Stage 3: APAP projective grid warping & Ceres Solver optimization
        GRAPH_CUT_SEAMS,        // Stage 4: MRF Graph-Cut Seam elimination
        PYRAMID_BLENDING,       // Stage 5: Burt-Adelson Spline pyramid blending & Poisson correction
        SERIALIZATION           // Stage 6: Embedded GPano Metadata & Linear DNG container formatting
    }

    data class PipelineState(
        val phase: Phase = Phase.IDLE,
        val progress: Float = 0f,
        val consoleLog: List<String> = emptyList(),
        val alignmentError: Float = 0f,
        val processedNodes: Int = 0,
        val totalNodes: Int = 0,
        val currentCeresIter: Int = 0,
        val sftKeypointsFound: Int = 0,
        val activeLinearDng: Boolean = false
    )

    private var InstanceState = PipelineState()
        set(value) {
            field = value
            _state.value = value
        }

    private val _state = MutableStateFlow(InstanceState)
    val state = _state.asStateFlow()

    fun addLog(msg: String) {
        val updatedLogs = InstanceState.consoleLog.toMutableList()
        updatedLogs.add(msg)
        if (updatedLogs.size > 80) updatedLogs.removeAt(0)
        InstanceState = InstanceState.copy(consoleLog = updatedLogs)
    }

    // High fidelity procedural panorama generation corresponding to captured sector orientations
    fun stitchCapturedState(
        nodes: List<PhotoSphereNode>,
        selectedLens: String,
        rawOutputEnabled: Boolean
    ): Bitmap {
        val width = 2048
        val height = 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // 1. Draw Background Sky and Sunset
        val skyGradient = LinearGradient(
            0f, 0f, 0f, height.toFloat() * 0.65f,
            intArrayOf(
                Color.rgb(15, 20, 48),  // Deep Zenith Space
                Color.rgb(40, 30, 80),  // Indigo Upper Canopy
                Color.rgb(130, 60, 110), // Crimson clouds
                Color.rgb(220, 110, 60), // Golden hour horizon
                Color.rgb(255, 170, 70)  // Sun level glow
            ),
            floatArrayOf(0.0f, 0.35f, 0.65f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // 2. Draw Stars in the upper hemisphere
        paint.color = Color.WHITE
        val starSeed = 42L
        val random = java.util.Random(starSeed)
        for (i in 0..120) {
            val sx = random.nextFloat() * width
            val sy = random.nextFloat() * (height * 0.45f)
            val size = random.nextFloat() * 2.5f + 0.5f
            paint.alpha = (random.nextFloat() * 155 + 100).toInt()
            canvas.drawCircle(sx, sy, size, paint)
        }
        paint.alpha = 255

        // 3. Draw a gorgeous Glowing Sun at the horizon center
        val sunX = width.toFloat() * 0.5f
        val sunY = height.toFloat() * 0.55f
        val sunRadial = RadialGradientShaderHolder(sunX, sunY, 150f, Color.argb(180, 255, 230, 140), Color.TRANSPARENT)
        paint.shader = sunRadial
        canvas.drawCircle(sunX, sunY, 150f, paint)
        paint.shader = null

        paint.color = Color.rgb(255, 245, 200)
        canvas.drawCircle(sunX, sunY, 40f, paint)

        // 4. Draw Mountain Range overlapping layers (Procedural geometry)
        drawProceduralMountains(canvas, width, height, scale = 1.0f)

        // 5. Draw Forescape / Ground grid (Nadir region)
        val groundGradient = LinearGradient(
            0f, height.toFloat() * 0.64f, 0f, height.toFloat(),
            Color.rgb(32, 28, 26), Color.rgb(8, 8, 8),
            Shader.TileMode.CLAMP
        )
        paint.shader = groundGradient
        canvas.drawRect(0f, height.toFloat() * 0.64f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Draw structural Grid lines corresponding to mathematical Equirectangular parallels/meridians
        paint.color = Color.argb(20, 255, 255, 255)
        paint.strokeWidth = 1f
        for (i in 1..23) {
            val longitudeX = (width / 24f) * i
            canvas.drawLine(longitudeX, 0f, longitudeX, height.toFloat(), paint)
        }
        for (j in 1..11) {
            val latitudeY = (height / 12f) * j
            canvas.drawLine(0f, latitudeY, width.toFloat(), latitudeY, paint)
        }

        // 6. Draw local specific details based on actually captured nodes!
        // We simulate holes (pitch-black circular regions) for UNCAPTURED nodes to demonstrate how the GCam multi-frame
        // capture updates the final stitched mosaic. If all nodes are captured, it's a seamless 360 view!
        paint.color = Color.rgb(10, 10, 10)
        paint.shader = null
        paint.style = Paint.Style.FILL

        nodes.forEach { node ->
            if (!node.captured) {
                // Map the spherical (yaw, pitch) coordinate to equirectangular coordinates (x, y)
                // targetYaw: [-180, 180] -> x: [0, width]
                // targetPitch: [-90, 90] -> y: [height, 0] (height maps to -90, 0 maps to 90)
                val x = ((node.targetYaw + 180f) / 360f) * width
                val y = ((90f - node.targetPitch) / 180f) * height
                
                // Draw a dark uncaptured sector placeholder
                paint.color = Color.argb(230, 12, 12, 16)
                canvas.drawCircle(x, y, 75f, paint)

                paint.color = Color.argb(160, 255, 100, 100)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(x, y, 75f, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(255, 120, 120)
                paint.textSize = 18f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("Node Missing", x, y + 6f, paint)
            }
        }

        // Add a signature GCam Photo Sphere logo banner at the nadir polar spot
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(160, 20, 20, 25)
        canvas.drawCircle(width / 2f, height - 120f, 80f, paint)
        paint.color = Color.argb(220, 255, 255, 255)
        paint.textSize = 14f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("GCAM SPHERE", width / 2f, height - 130f, paint)
        paint.textSize = 10f
        paint.color = Color.argb(180, (if (rawOutputEnabled) 140 else 255), 255, 255)
        canvas.drawText(if (rawOutputEnabled) "LINEAR DNG WORKFLOW" else "STITCHED JPEG 8-BIT", width / 2f, height - 110f, paint)
        canvas.drawText("LENS: $selectedLens", width / 2f, height - 95f, paint)

        return bitmap
    }

    private fun drawProceduralMountains(canvas: Canvas, width: Int, height: Int, scale: Float) {
        val paint = Paint()
        paint.style = Paint.Style.FILL

        // Layer 1: Far mountains (indigo silhouette)
        paint.color = Color.rgb(45, 32, 70)
        val path1 = Path()
        path1.moveTo(0f, height.toFloat())
        
        val pointsCount = 40
        val segmentWidth = width.toFloat() / pointsCount
        val seed = 127L
        val rand = java.util.Random(seed)

        var currentY = height * 0.52f
        path1.lineTo(0f, currentY)
        for (i in 1..pointsCount) {
            val targetX = i * segmentWidth
            currentY += (rand.nextFloat() - 0.5f) * 110f
            currentY = currentY.coerceIn(height * 0.45f, height * 0.58f)
            path1.lineTo(targetX, currentY)
        }
        path1.lineTo(width.toFloat(), height.toFloat())
        path1.close()
        canvas.drawPath(path1, paint)

        // Layer 2: Midground Ridge (slate dark purple)
        paint.color = Color.rgb(31, 23, 44)
        val path2 = Path()
        path2.moveTo(0f, height.toFloat())
        
        currentY = height * 0.57f
        path2.lineTo(0f, currentY)
        val rand2 = java.util.Random(seed * 3)
        for (i in 1..pointsCount) {
            val targetX = i * segmentWidth
            currentY += (rand2.nextFloat() - 0.5f) * 70f
            currentY = currentY.coerceIn(height * 0.52f, height * 0.65f)
            path2.lineTo(targetX, currentY)
        }
        path2.lineTo(width.toFloat(), height.toFloat())
        path2.close()
        canvas.drawPath(path2, paint)
    }

    private fun RadialGradientShaderHolder(
        x: Float, y: Float, radius: Float, colorStart: Int, colorEnd: Int
    ): Shader {
        return android.graphics.RadialGradient(
            x, y, radius, colorStart, colorEnd, Shader.TileMode.CLAMP
        )
    }

    // Fully animation-driven simulation of stitching algorithms matching technical pipelines
    suspend fun runStitchingPipelineAsync(
        nodes: List<PhotoSphereNode>,
        selectedLens: String,
        rawOutput: Boolean,
        userUpdateProgress: (Float) -> Unit
    ): Bitmap {
        InstanceState = PipelineState(
            phase = Phase.HDR_FUSION,
            progress = 0f,
            totalNodes = nodes.size,
            processedNodes = 0,
            activeLinearDng = rawOutput
        )

        // ------------------ PHASE 1: HDR+ FUSION ------------------
        addLog("Initializing real-time multi-frame HDR+ Processing Engine...")
        addLog("System parameter: Selected lens is $selectedLens (scaling coefficients updated).")
        delay(600)
        
        val totalCapNodes = nodes.filter { it.captured }.size
        addLog("Checking geodesic capture nodes: $totalCapNodes / ${nodes.size} captured.")
        
        for (i in 1..nodes.size) {
            val node = nodes.getOrNull(i - 1)
            val code = node?.id ?: "N-${i}"
            val wasCap = node?.captured ?: false
            
            if (wasCap) {
                InstanceState = InstanceState.copy(processedNodes = InstanceState.processedNodes + 1)
                addLog(" -> HDR+ Merging burst frames for Node $code (Merging 12 linear exposures)...")
                delay(120)
                addLog(" -> Applied subpixel shift alignment, Super-Resolution details established.")
                addLog(" -> Anisotropic Gaussian noise coefficients parsed: kernel sigma = 1.34")
            } else {
                addLog(" -> Node $code remains vacant. Processing skipped. Linear interpolations will bridge.")
            }
            
            val p1Progress = (i.toFloat() / nodes.size) * 0.15f
            InstanceState = InstanceState.copy(progress = p1Progress)
            userUpdateProgress(p1Progress)
        }

        InstanceState = InstanceState.copy(phase = Phase.FEATURE_REGISTRATION)
        addLog("PHASE 1 Complete! Direct HDR+ Linear Fusion resolved standard exposures.")
        addLog("")
        addLog("----------------------------------------------------------------")
        addLog("PHASE 2: Scale-Invariant Keypoint Detection (SIFT) & Pairing")
        addLog("----------------------------------------------------------------")
        delay(700)

        // ------------------ PHASE 2: SIFT & RANSAC ------------------
        var currentSftPoints = 0
        for (i in 1..8) {
            val iterProgress = 0.15f + (i.toFloat() / 8) * 0.15f
            currentSftPoints += (250..450).random()
            InstanceState = InstanceState.copy(
                progress = iterProgress,
                sftKeypointsFound = currentSftPoints
            )
            addLog("Extracting scale-space extrema (Difference of Gaussians) over Sector Overlap $i...")
            delay(150)
            val outliersRejected = (30..80).random()
            addLog(" -> SIFT descriptors established. RANSAC validated. Outliers trimmed: $outliersRejected.")
            userUpdateProgress(iterProgress)
        }

        InstanceState = InstanceState.copy(phase = Phase.APAP_WARPING)
        addLog("PHASE 2 Complete! Extracted a total of $currentSftPoints robust SIFT point matches.")
        addLog("")
        addLog("----------------------------------------------------------------")
        addLog("PHASE 3: Spatially-Varying As-Projective-As-Possible (APAP) Warping")
        addLog("----------------------------------------------------------------")
        delay(700)

        // ------------------ PHASE 3: APAP WARPING & CERES ------------------
        addLog("Evaluating boundary translational offsets (Handheld parallax detected).")
        addLog("Configuring spline-based flow field grid (Sub-division: 32x16).")
        
        for (iter in 1..15) {
            val iterProgress = 0.30f + (iter.toFloat() / 15) * 0.20f
            val errVal = 42.5f / (iter * iter * 0.8f + 1f) + 0.35f
            InstanceState = InstanceState.copy(
                progress = iterProgress,
                currentCeresIter = iter,
                alignmentError = errVal
            )
            addLog(" -> Ceres Solver Iteration #$iter: Residual alignment error L2 norm = ${String.format("%.4f", errVal)} px")
            delay(100)
            userUpdateProgress(iterProgress)
        }

        InstanceState = InstanceState.copy(phase = Phase.GRAPH_CUT_SEAMS)
        addLog("PHASE 3 Complete! Global spline constraints resolved. Moving DLT projectively locked.")
        addLog("")
        addLog("----------------------------------------------------------------")
        addLog("PHASE 4: Markov Random Field (MRF) Graph-Cut Seam Optimization")
        addLog("----------------------------------------------------------------")
        delay(700)

        // ------------------ PHASE 4: GRAPH CUT SEAMS ------------------
        addLog("Tracing overlapping boundary energy pathways...")
        for (i in 1..5) {
            val iterProgress = 0.50f + (i.toFloat() / 5) * 0.15f
            InstanceState = InstanceState.copy(progress = iterProgress)
            addLog(" -> MRF Level $i: Balancing color similarities (Es) vs registration confidence (Ew).")
            delay(200)
            addLog(" -> Seams generated. Double-imaging 'ghosting' checks finalized successfully.")
            userUpdateProgress(iterProgress)
        }

        InstanceState = InstanceState.copy(phase = Phase.PYRAMID_BLENDING)
        addLog("PHASE 4 Complete! Smooth transitions established across overlapping visual borders.")
        addLog("")
        addLog("----------------------------------------------------------------")
        addLog("PHASE 5: Burt-Adelson Multi-Resolution Spline & Poisson Blending")
        addLog("----------------------------------------------------------------")
        delay(700)

        // ------------------ PHASE 5: BLENDING ------------------
        addLog("Decomposing panorama into multi-band Gaussian and Laplacian Pyramids...")
        for (level in 1..4) {
            val iterProgress = 0.65f + (level.toFloat() / 4) * 0.15f
            InstanceState = InstanceState.copy(progress = iterProgress)
            addLog(" -> Splining Pyramid Level $level (Frequency range: ${1600 / (level * level)} Hz band).")
            delay(200)
            addLog(" -> Performing local Poisson gradient equilibrium to balance white balance differences.")
            userUpdateProgress(iterProgress)
        }

        InstanceState = InstanceState.copy(phase = Phase.SERIALIZATION)
        addLog("PHASE 5 Complete! Seamless color and pixel blending finalized.")
        addLog("")
        addLog("----------------------------------------------------------------")
        addLog("PHASE 6: GPano Metadata Injection & File Output")
        addLog("----------------------------------------------------------------")
        delay(600)

        // ------------------ PHASE 6: GPano SERIALIZATION ------------------
        addLog("Serializing W3C RDF XML GPano metadata schema inside JPEG APP1 marker...")
        addLog(" -> ProjectionType: equirectangular")
        addLog(" -> UsePanoramaViewer: True")
        addLog(" -> PoseHeadingDegrees: 0.00")
        addLog(" -> PosePitchDegrees: 0.00")
        addLog(" -> PoseRollDegrees: 0.00")
        addLog(" -> FullPanoWidthPixels: 2048")
        addLog(" -> FullPanoHeightPixels: 1024")
        delay(300)

        if (rawOutput) {
            addLog("Executing high-dynamic-range RAW encoding...")
            addLog("Creating Linear DNG 16-bit float container.")
            addLog("Copying unmodified demosaiced Linear RGB exposure lattices...")
            addLog("RAW header parsed: Black level [512], White level [16383], Illumi matrix applied.")
            delay(450)
        }

        val stitchedBitmap = stitchCapturedState(nodes, selectedLens, rawOutput)
        
        InstanceState = InstanceState.copy(
            phase = Phase.IDLE,
            progress = 1.0f
        )
        addLog("STITCHING SUCCESSFUL! Output file finalized.")
        userUpdateProgress(1.0f)

        return stitchedBitmap
    }

    // High fidelity Linear DNG dynamic raw processing:
    // Develops a rendering from 16-bit linear bitmap with adjusted parameters
    fun developLinearDng(
        baseBitmap: Bitmap,
        exposure: Float,          // [-3.0f, +3.0f]
        highlights: Float,         // [-100f, +100f]
        shadows: Float,            // [-100f, +100f]
        tempCelsius: Float         // [2000K, 10000K] (scaled -1 to 1 for offset)
    ): Bitmap {
        val width = baseBitmap.width
        val height = baseBitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Access target pixels
        val pixels = IntArray(width * height)
        baseBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val expMultiple = Math.pow(2.0, exposure.toDouble()).toFloat()
        
        // Highlight recovery and shadow boosting coefficients
        val hlCoeff = 1.0f - (highlights / 200f) // If highlights = -100, we pull down high lights
        val shCoeff = 1.0f + (shadows / 200f)    // If shadows = +100, we boost low values

        // Simple white balance multipliers based on temperature (Celsius/Kelvin)
        // Red multiplier is higher at low temps (warm), Blue is higher at high temps (cool)
        val rWB = 1.0f - (tempCelsius * 0.15f)
        val bWB = 1.0f + (tempCelsius * 0.15f)

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = Color.alpha(color)
            var r = Color.red(color).toFloat()
            var g = Color.green(color).toFloat()
            var b = Color.blue(color).toFloat()

            // 1. Convert to a simulated 16-bit Linear float workspace [0f, 255f]
            // We apply an inverse gamma curve to work in linear space!
            r = Math.pow((r / 255.0), 2.2).toFloat() * 255f
            g = Math.pow((g / 255.0), 2.2).toFloat() * 255f
            b = Math.pow((b / 255.0), 2.2).toFloat() * 255f

            // 2. Apply White Balance multipliers in Linear Space
            r *= rWB
            b *= bWB

            // 3. Apply linear exposure gain
            r *= expMultiple
            g *= expMultiple
            b *= expMultiple

            // 4. Highlight Recovery (Luminance based compression)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (lum > 170f) {
                val compress = (170f + (lum - 170f) * hlCoeff) / lum
                r *= compress
                g *= compress
                b *= compress
            }

            // 5. Shadow Boosting
            if (lum < 60f && lum > 0f) {
                // Boost deep shadows
                val boost = (lum + (60f - lum) * (shCoeff - 1f)) / lum
                r *= boost
                g *= boost
                b *= boost
            }

            // 6. Convert back to sRGB Gamma space
            r = Math.pow((r.coerceIn(0f, 255f) / 255.0), 1.0 / 2.2).toFloat() * 255f
            g = Math.pow((g.coerceIn(0f, 255f) / 255.0), 1.0 / 2.2).toFloat() * 255f
            b = Math.pow((b.coerceIn(0f, 255f) / 255.0), 1.0 / 2.2).toFloat() * 255f

            pixels[i] = Color.argb(a, r.toInt(), g.toInt(), b.toInt())
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}
