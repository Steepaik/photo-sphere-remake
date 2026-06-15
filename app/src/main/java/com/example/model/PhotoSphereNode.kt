package com.example.model

import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.sin

data class PhotoSphereNode(
    val id: String,
    val targetYaw: Float,    // Azimuth in degrees [-180, 180]
    val targetPitch: Float,  // Pitch in degrees [-90, 90]
    val captured: Boolean = false,
    val capturedProgress: Float = 0f, // 0 to 1 for HDR+ burst capture progress
    val thumbnailId: Int? = null,
    val elevationBand: ElevationBand,
    val capturedBitmap: Bitmap? = null // Real captured image preview bitmap
) {
    enum class ElevationBand {
        ZENITH,
        UPPER_HEMISPHERE,
        HORIZON,
        LOWER_HEMISPHERE,
        NADIR
    }

    // Convert spherical coordinates to 3D Cartesian coordinates on unit sphere
    fun toVector3D(): Vector3 {
        val yawRad = Math.toRadians(targetYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(targetPitch.toDouble()).toFloat()
        
        // standard spherical coordination translation:
        // x = cos(pitch) * sin(yaw)
        // y = sin(pitch)
        // z = cos(pitch) * cos(yaw)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)

        return Vector3(
            (cosPitch * sinYaw).toFloat(),
            sinPitch,
            (cosPitch * cosYaw).toFloat()
        )
    }
}

data class Vector3(val x: Float, val y: Float, val z: Float) {
    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z
    fun length(): Float = kotlin.math.sqrt(x * x + y * y + z * z)
    fun normalize(): Vector3 {
        val len = length()
        return if (len > 0f) Vector3(x / len, y / len, z / len) else this
    }
}
