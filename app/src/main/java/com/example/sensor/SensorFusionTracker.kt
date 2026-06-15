package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SensorFusionTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // Quaternion components: q = [w, x, y, z]
    private val _quaternion = MutableStateFlow(floatArrayOf(1f, 0f, 0f, 0f))
    val quaternion: StateFlow<FloatArray> = _quaternion.asStateFlow()

    // Euler angles in degrees
    private val _yaw = MutableStateFlow(0f)      // Azimuth [-180, 180]
    val yaw: StateFlow<Float> = _yaw.asStateFlow()

    private val _pitch = MutableStateFlow(0f)    // Elevation [-90, 90]
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _roll = MutableStateFlow(0f)     // Roll [-180, 180]
    val roll: StateFlow<Float> = _roll.asStateFlow()

    private val _isSensorActive = MutableStateFlow(false)
    val isSensorActive: StateFlow<Boolean> = _isSensorActive.asStateFlow()

    // Rotation Matrix
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Manual simulation offsets (used as fallback or manual override)
    private var simulatedYaw = 0f
    private var simulatedPitch = 0f
    private var isSimulationMode = false

    fun startTracking() {
        // Try to register rotation vector sensor for fused orientation
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            _isSensorActive.value = true
            isSimulationMode = false
        } else {
            // Fallback to accelerometer + magnetometer combo or manual simulation
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accelerometer != null && magnetometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
                _isSensorActive.value = true
                isSimulationMode = false
            } else {
                Log.w("SensorFusionTracker", "No physical rotation sensors detected. Falling back to simulation mode.")
                _isSensorActive.value = false
                isSimulationMode = true
            }
        }
    }

    fun stopTracking() {
        sensorManager.unregisterListener(this)
        _isSensorActive.value = false
    }

    fun enableSimulationMode(enable: Boolean) {
        isSimulationMode = enable
        if (enable) {
            sensorManager.unregisterListener(this)
            _isSensorActive.value = false
        } else {
            startTracking()
        }
    }

    // Manual adjustments to simulated yaw/pitch when user swipes on viewfinder screen
    fun updateSimulation(deltaYaw: Float, deltaPitch: Float) {
        if (!isSimulationMode) {
            // Enable simulation if user starts interactive panning
            enableSimulationMode(true)
        }
        
        simulatedYaw = (simulatedYaw + deltaYaw)
        if (simulatedYaw > 180f) simulatedYaw -= 360f
        if (simulatedYaw < -180f) simulatedYaw += 360f

        simulatedPitch = (simulatedPitch + deltaPitch).coerceIn(-90f, 90f)

        _yaw.value = simulatedYaw
        _pitch.value = simulatedPitch
        _roll.value = 0f // Level the horizontal axis during simulation

        // Convert angles to synthetic unit quaternion
        val yRad = Math.toRadians(simulatedYaw.toDouble() / 2).toFloat()
        val pRad = Math.toRadians(simulatedPitch.toDouble() / 2).toFloat()

        val cy = cos(yRad)
        val sy = sin(yRad)
        val cp = cos(pRad)
        val sp = sin(pRad)

        // Assuming 0 roll
        val qw = cy * cp
        val qx = -sy * sp
        val qy = cy * sp
        val qz = sy * cp

        _quaternion.value = floatArrayOf(qw, qx, qy, qz)
    }

    fun resetSimulation() {
        simulatedYaw = 0f
        simulatedPitch = 0f
        updateSimulation(0f, 0f)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isSimulationMode) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Get rotation matrix
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            // Remap for landscape/portrait camera viewport
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )

            // Get Euler angles from the remapped matrix
            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            // Convert and update
            val radYaw = orientationAngles[0]
            val radPitch = orientationAngles[1]
            val radRoll = orientationAngles[2]

            _yaw.value = Math.toDegrees(radYaw.toDouble()).toFloat()
            // Invert pitch dynamically so tilting up increases pitch values consistently
            _pitch.value = -Math.toDegrees(radPitch.toDouble()).toFloat()
            _roll.value = Math.toDegrees(radRoll.toDouble()).toFloat()

            // Construct quaternion from rotation vector
            // Sensor vector: [x*sin(t/2), y*sin(t/2), z*sin(t/2), cos(t/2)]
            val q = FloatArray(4)
            if (event.values.size >= 4) {
                q[0] = event.values[3] // w (cos(t/2))
                q[1] = event.values[0] // x
                q[2] = event.values[1] // y
                q[3] = event.values[2] // z
            } else {
                // Approximate w if values[3] is absent on rare devices
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val sinHalfThetaSquare = x * x + y * y + z * z
                val cosHalfTheta = if (sinHalfThetaSquare < 1f) sqrt(1f - sinHalfThetaSquare) else 0f
                q[0] = cosHalfTheta
                q[1] = x
                q[2] = y
                q[3] = z
            }
            _quaternion.value = q
        } else {
            // Accelerometer & Magnetometer fusion logic
            // (Used as older device/compatibility fallback)
            val accelVals = FloatArray(3)
            val magVals = FloatArray(3)
            
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelVals, 0, 3)
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magVals, 0, 3)
            }

            val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelVals, magVals)
            if (success) {
                val remappedMatrix = FloatArray(9)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientationAngles)
                _yaw.value = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                _pitch.value = -Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                _roll.value = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                // Extract quaternion from rotation matrix
                // Tr = R00 + R11 + R22
                val t0 = rotationMatrix[0]
                val t1 = rotationMatrix[4]
                val t2 = rotationMatrix[8]
                val tr = t0 + t1 + t2
                
                val q = FloatArray(4)
                if (tr > 0) {
                    val s = sqrt(tr + 1.0f) * 2f // S=4*qw
                    q[0] = 0.25f * s
                    q[1] = (rotationMatrix[7] - rotationMatrix[5]) / s
                    q[2] = (rotationMatrix[2] - rotationMatrix[6]) / s
                    q[3] = (rotationMatrix[3] - rotationMatrix[1]) / s
                } else if ((t0 > t1) && (t0 > t2)) {
                    val s = sqrt(1.0f + t0 - t1 - t2) * 2f // S=4*qx
                    q[0] = (rotationMatrix[7] - rotationMatrix[5]) / s
                    q[1] = 0.25f * s
                    q[2] = (rotationMatrix[1] + rotationMatrix[3]) / s
                    q[3] = (rotationMatrix[2] + rotationMatrix[6]) / s
                } else if (t1 > t2) {
                    val s = sqrt(1.0f + t1 - t0 - t2) * 2f // S=4*qy
                    q[0] = (rotationMatrix[2] - rotationMatrix[6]) / s
                    q[1] = (rotationMatrix[1] + rotationMatrix[3]) / s
                    q[2] = 0.25f * s
                    q[3] = (rotationMatrix[5] + rotationMatrix[7]) / s
                } else {
                    val s = sqrt(1.0f + t2 - t0 - t1) * 2f // S=4*qz
                    q[0] = (rotationMatrix[3] - rotationMatrix[1]) / s
                    q[1] = (rotationMatrix[2] + rotationMatrix[6]) / s
                    q[2] = (rotationMatrix[5] + rotationMatrix[7]) / s
                    q[3] = 0.25f * s
                }

                _quaternion.value = q
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy state transitions if necessary
    }
}
