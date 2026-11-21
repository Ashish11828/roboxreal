package com.example.xr

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.robotics.Vec3Mm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.tan

private const val XR_TAG = "XrealSdkBridge"

// Known XREAL (Nreal) USB Vendor IDs for Air / Air 2 / Air 2 Pro / Air 2 Ultra / One / Light
private val XREAL_USB_VENDOR_IDS = setOf(0x3318, 0x0486)

data class XrealDeviceState(
    val xrealUsbConnected: Boolean = false,
    val externalHudDisplayConnected: Boolean = false,
    val connectedDeviceName: String = "Android XR / XREAL Optical Preview",
    val displayResolution: String = "1920x1080 @ 60Hz (Birdbath HUD)",
    val imuTrackingActive: Boolean = false,
    val headYawDeg: Float = 0f,
    val headPitchDeg: Float = -18f,
    val headRollDeg: Float = 0f,
    val raycastCursorMm: Vec3Mm = Vec3Mm(260.0, 195.0, 185.0),
    val voiceListening: Boolean = false,
    val lastVoiceStatus: String = "Ready for voice command"
)

/**
 * Layer 1: XREAL SDK & Android SDK Hardware Bridge.
 * - Detects tethered XREAL USB-C optical glasses via UsbManager (VID 0x3318 / 0x0486)
 *   and external secondary Presentation Display via DisplayManager.
 * - Renders pure-black optical see-through HUD Presentation directly onto XREAL lenses when attached.
 * - Reads real 3DoF/6DoF IMU rotation vector from SensorManager to drive spatial raycast cursor (mm).
 * - Captures shopfloor voice commands via Android SpeechRecognizer.
 * - Triggers haptic feedback via Vibrator on spatial pinch anchor locks and protective stops.
 */
class XrealSdkBridge(private val context: Context) : SensorEventListener {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _deviceState = MutableStateFlow(XrealDeviceState())
    val deviceState: StateFlow<XrealDeviceState> = _deviceState.asStateFlow()

    private var activePresentation: XrealOpticalHudPresentation? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var baseYawRad: Float? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshHardwareState()
        override fun onDisplayRemoved(displayId: Int) = refreshHardwareState()
        override fun onDisplayChanged(displayId: Int) = refreshHardwareState()
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        refreshHardwareState()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        setImuTrackingEnabled(false)
        stopVoiceRecognition()
        dismissExternalHud()
    }

    fun refreshHardwareState() {
        val xrealUsbDevice = runCatching {
            usbManager.deviceList.values.firstOrNull { device ->
                device.vendorId in XREAL_USB_VENDOR_IDS ||
                    (device.manufacturerName?.contains("XREAL", ignoreCase = true) == true) ||
                    (device.manufacturerName?.contains("Nreal", ignoreCase = true) == true)
            }
        }.getOrNull()

        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val externalDisplay = presentationDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        val deviceLabel = when {
            xrealUsbDevice != null -> xrealUsbDevice.productName ?: "XREAL Air / Ultra Glasses (USB-C)"
            externalDisplay != null -> "XREAL Optical Display #${externalDisplay.displayId}"
            else -> "Android XR / XREAL Optical Preview"
        }

        val resolutionLabel = externalDisplay?.mode?.let { mode ->
            "${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz"
        } ?: "1920x1080 @ 60Hz (Simulated Optics)"

        _deviceState.update {
            it.copy(
                xrealUsbConnected = xrealUsbDevice != null,
                externalHudDisplayConnected = externalDisplay != null,
                connectedDeviceName = deviceLabel,
                displayResolution = resolutionLabel
            )
        }

        if (externalDisplay != null) {
            attachExternalHud(externalDisplay)
        } else {
            dismissExternalHud()
        }
    }

    fun setImuTrackingEnabled(enabled: Boolean) {
        if (enabled) {
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor != null) {
                baseYawRad = null
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                _deviceState.update { it.copy(imuTrackingActive = true) }
            } else {
                _deviceState.update {
                    it.copy(
                        imuTrackingActive = false,
                        lastVoiceStatus = "IMU sensor unavailable on emulator — use 3D touch raycast"
                    )
                }
            }
        } else {
            sensorManager.unregisterListener(this)
            _deviceState.update { it.copy(imuTrackingActive = false) }
        }
    }

    fun recenterImu() {
        baseYawRad = null
    }

    fun updateExternalHudContent(
        taskName: String,
        statusLine: String,
        pickLabel: String,
        placeLabel: String,
        isProtectiveStop: Boolean
    ) {
        activePresentation?.updateHud(
            taskName = taskName,
            statusLine = statusLine,
            pickLabel = pickLabel,
            placeLabel = placeLabel,
            isProtectiveStop = isProtectiveStop
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val rawYaw = orientation[0]
        if (baseYawRad == null) baseYawRad = rawYaw
        val relYawRad = rawYaw - (baseYawRad ?: 0f)
        val pitchRad = orientation[1]
        val rollRad = orientation[2]

        // Project 3DoF XREAL head ray onto CNC worktable workspace (X: 120..680mm, Y: -320..320mm, Z: 50..360mm)
        val rayX = (360.0 + tan(relYawRad.toDouble().coerceIn(-0.7, 0.7)) * 340.0).coerceIn(120.0, 680.0)
        val rayY = (tan(rollRad.toDouble().coerceIn(-0.7, 0.7)) * 320.0).coerceIn(-300.0, 300.0)
        val rayZ = (195.0 - tan(pitchRad.toDouble().coerceIn(-0.6, 0.6)) * 220.0).coerceIn(45.0, 360.0)

        _deviceState.update {
            it.copy(
                headYawDeg = Math.toDegrees(relYawRad.toDouble()).toFloat(),
                headPitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat(),
                headRollDeg = Math.toDegrees(rollRad.toDouble()).toFloat(),
                raycastCursorMm = Vec3Mm(rayX, rayY, rayZ)
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun startVoiceRecognition(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val msg = "On-device SpeechRecognizer unavailable — type or select a quick intent chip."
            _deviceState.update { it.copy(voiceListening = false, lastVoiceStatus = msg) }
            onError(msg)
            return
        }

        stopVoiceRecognition()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _deviceState.update { it.copy(voiceListening = true, lastVoiceStatus = "Listening for operator voice intent...") }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                _deviceState.update { it.copy(voiceListening = false, lastVoiceStatus = "Processing speech...") }
            }
            override fun onError(error: Int) {
                val msg = "Voice capture status ($error) — tap mic again or use intent presets"
                _deviceState.update { it.copy(voiceListening = false, lastVoiceStatus = msg) }
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull().orEmpty()
                _deviceState.update {
                    it.copy(
                        voiceListening = false,
                        lastVoiceStatus = if (transcript.isNotBlank()) "Captured: \"$transcript\"" else "No speech detected"
                    )
                }
                if (transcript.isNotBlank()) {
                    onResult(transcript)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak CNC robot task intent")
        }
        runCatching {
            recognizer.startListening(intent)
        }.onFailure { e ->
            Log.e(XR_TAG, "Failed to start SpeechRecognizer", e)
            _deviceState.update { it.copy(voiceListening = false, lastVoiceStatus = "Voice input unavailable") }
            onError(e.localizedMessage ?: "Voice capture failed")
        }
    }

    fun stopVoiceRecognition() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        _deviceState.update { it.copy(voiceListening = false) }
    }

    fun pulseHaptic(isAlert: Boolean = false) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isAlert) {
                    VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), -1)
                } else {
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (isAlert) 180L else 35L)
            }
        }
    }

    private fun attachExternalHud(display: Display) {
        if (activePresentation?.display?.displayId == display.displayId && activePresentation?.isShowing == true) {
            return
        }
        dismissExternalHud()
        runCatching {
            val hud = XrealOpticalHudPresentation(context, display)
            hud.show()
            activePresentation = hud
            Log.i(XR_TAG, "Attached XREAL Optical See-Through HUD to display #${display.displayId}")
        }.onFailure { e ->
            Log.e(XR_TAG, "Unable to attach Presentation to external display", e)
        }
    }

    private fun dismissExternalHud() {
        runCatching { activePresentation?.dismiss() }
        activePresentation = null
    }
}

/**
 * Pure-black Optical See-Through HUD rendered directly to tethered XREAL USB-C glasses.
 * On XREAL OLED Birdbath optics, RGB(0,0,0) pixels are optically transparent so only the
 * cyan/emerald/crimson industrial telemetry floats in front of the real CNC machine cell.
 */
private class XrealOpticalHudPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var coordsView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(AndroidColor.BLACK)
            setPadding(48, 36, 48, 36)
        }

        titleView = TextView(context).apply {
            text = "ROBOXR AI · XREAL OPTICAL HUD"
            textSize = 18f
            setTextColor(AndroidColor.parseColor("#00E5FF"))
        }
        statusView = TextView(context).apply {
            text = "ISO 10218 STANDBY · READY FOR PINCH TEACH"
            textSize = 14f
            setTextColor(AndroidColor.parseColor("#10B981"))
            setPadding(0, 12, 0, 12)
        }
        coordsView = TextView(context).apply {
            text = "PICK: (240, 210, 185) mm   |   PLACE: (520, -160, 120) mm"
            textSize = 13f
            setTextColor(AndroidColor.parseColor("#F1F5F9"))
        }

        root.addView(titleView)
        root.addView(statusView)
        root.addView(coordsView)
        setContentView(root)
    }

    fun updateHud(
        taskName: String,
        statusLine: String,
        pickLabel: String,
        placeLabel: String,
        isProtectiveStop: Boolean
    ) {
        titleView?.text = "ROBOXR AI · $taskName"
        statusView?.text = statusLine
        statusView?.setTextColor(
            AndroidColor.parseColor(if (isProtectiveStop) "#F43F5E" else "#10B981")
        )
        coordsView?.text = "PICK: $pickLabel mm   |   PLACE: $placeLabel mm"
    }
}
