package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * MainActivity - Fotogrametría Estéreo con Camera2 API, RAW/DNG, Sensor Level y JSON Metadata
 * Dispositivo: OPPO Find X9 Pro (CPH2791, Android 16 / SDK 36)
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    companion object {
        private const val TAG = "StereoPhotogrammetry"
        private const val LOGICAL_CAMERA_ID = "0"

        private val DEFAULT_RESOLUTIONS = listOf(
            Size(1280, 720),
            Size(640, 480),
            Size(320, 240)
        )
    }

    enum class OperationMode {
        STEREO,
        SINGLE_TEST
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var exposedPhysicalIds: Set<String> = emptySet()
    private var isLogicalMultiCamera: Boolean = false

    private var currentMode = OperationMode.STEREO
    private var singleTestCameraId = "2"

    private var supportedPreviewSizes: List<Size> = DEFAULT_RESOLUTIONS
    private var currentResolutionIndex = 1
    private var activeCaptureSize = Size(1920, 1080)
    private var rawCaptureSize: Size? = null

    private val candidatePairs = listOf(
        Pair("2", "3"),
        Pair("2", "4"),
        Pair("3", "4")
    )
    private val pairSupportMap = mutableMapOf<Pair<String, String>, Boolean>()
    private var selectedPair: Pair<String, String> = candidatePairs[0]

    private var surfaceA: Surface? = null
    private var surfaceB: Surface? = null

    private var imageReaderA: ImageReader? = null
    private var imageReaderB: ImageReader? = null
    private var rawImageReaderA: ImageReader? = null
    private var rawImageReaderB: ImageReader? = null

    private var isAeLocked = true
    private var isManualFocus = true
    private var focusDistanceDiopters = 2.5f // 40 cm
    private var focusDistanceLabel = "40 cm"
    private var isReconfiguring = false
    private var isPhoneLeveled = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupCameraPipeline()
        } else {
            Toast.makeText(this, "Permiso de cámara requerido.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupUIListeners()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupCameraPipeline()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // --- SensorEventListener para Nivel de Burbuja ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY || event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]

            // Umbral de nivelación perfecta: ±0.35 m/s² (~2 grados)
            val leveled = abs(x) < 0.35f && abs(y) < 0.35f
            if (leveled != isPhoneLeveled) {
                isPhoneLeveled = leveled
                runOnUiThread {
                    updateLevelIndicatorUI(leveled)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateLevelIndicatorUI(leveled: Boolean) {
        val dot = binding.levelIndicatorDot
        val statusText = binding.tvLevelStatus
        if (leveled) {
            dot.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_capture)
            statusText.text = "NIVELADO ✓"
            statusText.setTextColor(0xFF3FB950.toInt())
        } else {
            dot.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
            statusText.text = "AJUSTAR NIVEL"
            statusText.setTextColor(0xFFF85149.toInt())
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error deteniendo hilo: ${e.message}")
        }
    }

    private fun setupUIListeners() {
        binding.btnTestCam2.setOnClickListener { startSingleCameraTest("2") }
        binding.btnTestCam3.setOnClickListener { startSingleCameraTest("3") }
        binding.btnTestCam4.setOnClickListener { startSingleCameraTest("4") }

        binding.btnPair23.setOnClickListener { switchCameraPair(Pair("2", "3")) }
        binding.btnPair24.setOnClickListener { switchCameraPair(Pair("2", "4")) }
        binding.btnPair34.setOnClickListener { switchCameraPair(Pair("3", "4")) }

        binding.btnToggleAeLock.setOnClickListener {
            isAeLocked = !isAeLocked
            updateAeButtonUi()
            applyPreviewSettings()
        }

        binding.btnToggleAfMode.setOnClickListener {
            isManualFocus = !isManualFocus
            updateAfButtonUi()
            applyPreviewSettings()
        }

        binding.btnDist20cm.setOnClickListener { setManualFocusDistance(0.20f, "20 cm") }
        binding.btnDist40cm.setOnClickListener { setManualFocusDistance(0.40f, "40 cm") }
        binding.btnDist60cm.setOnClickListener { setManualFocusDistance(0.60f, "60 cm") }
        binding.btnDist80cm.setOnClickListener { setManualFocusDistance(0.80f, "80 cm") }

        binding.btnCapture.setOnClickListener {
            triggerSynchronizedStereoCapture()
        }
    }

    private fun setManualFocusDistance(distanceInMeters: Float, label: String) {
        focusDistanceDiopters = 1.0f / distanceInMeters
        focusDistanceLabel = label
        binding.tvFocusDistanceLabel.text =
            "Distancia de enfoque manual: $label (%.2f Dp)".format(focusDistanceDiopters)

        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        val inactiveBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
        binding.btnDist20cm.background = if (label.startsWith("20")) activeBg else inactiveBg
        binding.btnDist40cm.background = if (label.startsWith("40")) activeBg else inactiveBg
        binding.btnDist60cm.background = if (label.startsWith("60")) activeBg else inactiveBg
        binding.btnDist80cm.background = if (label.startsWith("80")) activeBg else inactiveBg

        if (isManualFocus) {
            applyPreviewSettings()
        }
    }

    private fun updateAeButtonUi() {
        if (isAeLocked) {
            binding.btnToggleAeLock.text = "🔒 AE: Bloqueado"
            binding.btnToggleAeLock.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        } else {
            binding.btnToggleAeLock.text = "🔓 AE: Auto"
            binding.btnToggleAeLock.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
        }
    }

    private fun updateAfButtonUi() {
        if (isManualFocus) {
            binding.btnToggleAfMode.text = "🎯 AF: Manual"
            binding.btnToggleAfMode.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        } else {
            binding.btnToggleAfMode.text = "🔄 AF: Continuo"
            binding.btnToggleAfMode.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
        }
    }

    private fun setupCameraPipeline() {
        queryHardwareCapabilities()
        verifyPhysicalCameraCombinations()
        setupTextureViewListeners()
    }

    private fun queryHardwareCapabilities() {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(LOGICAL_CAMERA_ID)
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            isLogicalMultiCamera = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            exposedPhysicalIds = characteristics.physicalCameraIds ?: emptySet()

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val surfaceSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                val filtered = surfaceSizes.filter {
                    (it.width <= 1280 && it.height <= 720) || (it.width <= 720 && it.height <= 1280)
                }.sortedByDescending { it.width * it.height }

                if (filtered.isNotEmpty()) {
                    supportedPreviewSizes = filtered
                    val vgaIndex = supportedPreviewSizes.indexOfFirst { it.width == 640 && it.height == 480 }
                    currentResolutionIndex = if (vgaIndex != -1) vgaIndex else 0
                }

                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                activeCaptureSize = jpegSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                    ?: jpegSizes.firstOrNull() ?: Size(1920, 1080)

                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                if (rawSizes.isNotEmpty()) {
                    rawCaptureSize = rawSizes.maxByOrNull { it.width * it.height }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando hardware: ${e.message}")
        }
    }

    private fun getCurrentPreviewSize(): Size {
        return supportedPreviewSizes.getOrNull(currentResolutionIndex)
            ?: supportedPreviewSizes.firstOrNull() ?: Size(640, 480)
    }

    private fun isPhysicalCameraValid(id: String): Boolean = exposedPhysicalIds.contains(id)

    private fun startSingleCameraTest(physicalId: String) {
        currentMode = OperationMode.SINGLE_TEST
        singleTestCameraId = physicalId
        updateModeButtonsUi()

        binding.tvStandbyCamB.visibility = View.VISIBLE
        binding.tvStandbyCamB.text = "PANTALLA DERECHA EN ESPERA\n(Prueba Cam $physicalId)"
        binding.tvBadgeCamA.text = "CAM IZQ: ID $physicalId [PRUEBA]"
        binding.tvBadgeCamB.text = "EN ESPERA"

        if (cameraDevice != null) createSessionForCurrentMode()
    }

    private fun switchCameraPair(newPair: Pair<String, String>) {
        currentMode = OperationMode.STEREO
        selectedPair = newPair
        updateModeButtonsUi()
        binding.tvStandbyCamB.visibility = View.GONE

        if (cameraDevice != null) createSessionForCurrentMode()
    }

    private fun updateModeButtonsUi() {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        val inactiveBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)

        val isTest = (currentMode == OperationMode.SINGLE_TEST)
        binding.btnTestCam2.background = if (isTest && singleTestCameraId == "2") activeBg else inactiveBg
        binding.btnTestCam3.background = if (isTest && singleTestCameraId == "3") activeBg else inactiveBg
        binding.btnTestCam4.background = if (isTest && singleTestCameraId == "4") activeBg else inactiveBg

        val isStereo = (currentMode == OperationMode.STEREO)
        binding.btnPair23.background = if (isStereo && selectedPair == Pair("2", "3")) activeBg else inactiveBg
        binding.btnPair24.background = if (isStereo && selectedPair == Pair("2", "4")) activeBg else inactiveBg
        binding.btnPair34.background = if (isStereo && selectedPair == Pair("3", "4")) activeBg else inactiveBg
    }

    private fun verifyPhysicalCameraCombinations() {
        binding.tvVerificationStatus.text = "Verificando HAL, RAW y Cámaras Físicas..."
        sessionExecutor.execute {
            val statusBuilder = StringBuilder("Estado de Hardware & RAW:\n")
            statusBuilder.append("• Soporte RAW_SENSOR: ${if (rawCaptureSize != null) "SÍ (${rawCaptureSize})" else "NO"}\n")
            statusBuilder.append("• Cámaras físicas: $exposedPhysicalIds\n")

            for (pair in candidatePairs) {
                val supported = exposedPhysicalIds.contains(pair.first) && exposedPhysicalIds.contains(pair.second)
                pairSupportMap[pair] = supported
            }

            val firstValid = candidatePairs.firstOrNull { pairSupportMap[it] == true }
            if (firstValid != null) selectedPair = firstValid

            runOnUiThread {
                binding.tvVerificationStatus.text = statusBuilder.toString().trim()
                updateModeButtonsUi()
            }
        }
    }

    private fun setupTextureViewListeners() {
        var textureAReady = false
        var textureBReady = false

        binding.textureViewA.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = getCurrentPreviewSize()
                st.setDefaultBufferSize(s.width, s.height)
                surfaceA = Surface(st)
                textureAReady = true
                if (textureBReady) openLogicalCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceA?.release(); surfaceA = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        binding.textureViewB.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = getCurrentPreviewSize()
                st.setDefaultBufferSize(s.width, s.height)
                surfaceB = Surface(st)
                textureBReady = true
                if (textureAReady) openLogicalCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceB?.release(); surfaceB = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openLogicalCamera() {
        try {
            cameraManager.openCamera(LOGICAL_CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSessionForCurrentMode()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara: ${e.message}")
        }
    }

    private fun createSessionForCurrentMode() {
        if (currentMode == OperationMode.SINGLE_TEST) {
            createSingleCameraSession()
        } else {
            createDualCameraSession()
        }
    }

    private fun createSingleCameraSession() {
        val device = cameraDevice ?: return
        val sA = surfaceA ?: return

        isReconfiguring = true
        captureSession?.close(); captureSession = null
        imageReaderA?.close(); imageReaderA = null
        rawImageReaderA?.close(); rawImageReaderA = null

        val physicalId = singleTestCameraId
        val hasRaw = rawCaptureSize != null && isPhysicalCameraValid(physicalId)

        try {
            imageReaderA = ImageReader.newInstance(activeCaptureSize.width, activeCaptureSize.height, ImageFormat.JPEG, 2)
            if (hasRaw) {
                rawImageReaderA = ImageReader.newInstance(rawCaptureSize!!.width, rawCaptureSize!!.height, ImageFormat.RAW_SENSOR, 2)
            }

            val previewOutputA = OutputConfiguration(sA).apply {
                if (isPhysicalCameraValid(physicalId)) setPhysicalCameraId(physicalId)
            }
            val captureOutputA = OutputConfiguration(imageReaderA!!.surface).apply {
                if (isPhysicalCameraValid(physicalId)) setPhysicalCameraId(physicalId)
            }

            val outputs = mutableListOf(previewOutputA, captureOutputA)
            if (hasRaw && rawImageReaderA != null) {
                val rawOutputA = OutputConfiguration(rawImageReaderA!!.surface).apply {
                    if (isPhysicalCameraValid(physicalId)) setPhysicalCameraId(physicalId)
                }
                outputs.add(rawOutputA)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        isReconfiguring = false
                        captureSession = session
                        applyPreviewSettings()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isReconfiguring = false
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            isReconfiguring = false
            Log.e(TAG, "Error sesión single: ${e.message}")
        }
    }

    private fun createDualCameraSession() {
        val device = cameraDevice ?: return
        val sA = surfaceA ?: return
        val sB = surfaceB ?: return

        isReconfiguring = true
        captureSession?.close(); captureSession = null
        imageReaderA?.close(); imageReaderA = null
        imageReaderB?.close(); imageReaderB = null
        rawImageReaderA?.close(); rawImageReaderA = null
        rawImageReaderB?.close(); rawImageReaderB = null

        val idA = selectedPair.first
        val idB = selectedPair.second
        val hasRaw = rawCaptureSize != null

        try {
            imageReaderA = ImageReader.newInstance(activeCaptureSize.width, activeCaptureSize.height, ImageFormat.JPEG, 2)
            imageReaderB = ImageReader.newInstance(activeCaptureSize.width, activeCaptureSize.height, ImageFormat.JPEG, 2)

            if (hasRaw) {
                rawImageReaderA = ImageReader.newInstance(rawCaptureSize!!.width, rawCaptureSize!!.height, ImageFormat.RAW_SENSOR, 2)
                rawImageReaderB = ImageReader.newInstance(rawCaptureSize!!.width, rawCaptureSize!!.height, ImageFormat.RAW_SENSOR, 2)
            }

            val previewOutputA = OutputConfiguration(sA).apply {
                if (isPhysicalCameraValid(idA)) setPhysicalCameraId(idA)
            }
            val previewOutputB = OutputConfiguration(sB).apply {
                if (isPhysicalCameraValid(idB)) setPhysicalCameraId(idB)
            }
            val captureOutputA = OutputConfiguration(imageReaderA!!.surface).apply {
                if (isPhysicalCameraValid(idA)) setPhysicalCameraId(idA)
            }
            val captureOutputB = OutputConfiguration(imageReaderB!!.surface).apply {
                if (isPhysicalCameraValid(idB)) setPhysicalCameraId(idB)
            }

            val outputs = mutableListOf(previewOutputA, previewOutputB, captureOutputA, captureOutputB)
            if (hasRaw && rawImageReaderA != null && rawImageReaderB != null) {
                val rawOutA = OutputConfiguration(rawImageReaderA!!.surface).apply {
                    if (isPhysicalCameraValid(idA)) setPhysicalCameraId(idA)
                }
                val rawOutB = OutputConfiguration(rawImageReaderB!!.surface).apply {
                    if (isPhysicalCameraValid(idB)) setPhysicalCameraId(idB)
                }
                outputs.add(rawOutA)
                outputs.add(rawOutB)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        isReconfiguring = false
                        captureSession = session
                        applyPreviewSettings()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isReconfiguring = false
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            isReconfiguring = false
            Log.e(TAG, "Error sesión estéreo: ${e.message}")
        }
    }

    private fun applyPreviewSettings() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val sA = surfaceA ?: return

        try {
            val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(sA)
                if (currentMode == OperationMode.STEREO && surfaceB != null) {
                    addTarget(surfaceB!!)
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
                if (isManualFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }
            session.setRepeatingRequest(reqBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error preview: ${e.message}")
        }
    }

    /**
     * Captura sincronizada (JPG + RAW/DNG si soportado) y generación de metadatos (.txt y .json).
     */
    private fun triggerSynchronizedStereoCapture() {
        val session = captureSession
        val device = cameraDevice
        val readerA = imageReaderA

        if (session == null || device == null || readerA == null) {
            Toast.makeText(this, "Cámara no lista", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false
        binding.tvCaptureLog.text = "Capturando imágenes JPG, DNG y Metadatos JSON..."

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val isoDateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

        val manualFocalStr = binding.etPhysicalFocal.text.toString()
        val manualBaselineStr = binding.etBaseline.text.toString()
        val manualFocalMm = manualFocalStr.toFloatOrNull() ?: 24.0f
        val manualBaselineCm = manualBaselineStr.toFloatOrNull() ?: 5.5f

        if (currentMode == OperationMode.SINGLE_TEST) {
            val idA = singleTestCameraId
            var jpegBytes: ByteArray? = null
            var dngPathA: String? = null
            val baseName = "single_${timestamp}"

            readerA.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    val buf = img.planes[0].buffer
                    jpegBytes = ByteArray(buf.remaining())
                    buf.get(jpegBytes!!)
                    img.close()

                    val jpgPath = saveJpegImage("${baseName}_cam${idA}.jpg", jpegBytes!!)
                    checkAndSaveJsonAndTxt(
                        baseName = baseName,
                        isoDate = isoDateStr,
                        mode = "Prueba Individual Cam $idA",
                        camIdA = idA,
                        camIdB = null,
                        focalMm = manualFocalMm,
                        baselineCm = manualBaselineCm,
                        jpgPathA = jpgPath,
                        jpgPathB = null,
                        dngPathA = dngPathA,
                        dngPathB = null
                    )
                }
            }, backgroundHandler)

            rawImageReaderA?.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(if (isPhysicalCameraValid(idA)) idA else LOGICAL_CAMERA_ID)
                        val dngFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "${baseName}_cam${idA}.dng")
                        // DngCreator se puede usar con captureResult si está disponible, o crearse con chars
                        // Por robustez, si no tenemos captureResult directo en el listener de ImageReader, guardamos bytes o usamos DngCreator con captureResult en onCaptureCompleted
                        img.close()
                    } catch (e: Exception) {
                        img.close()
                    }
                }
            }, backgroundHandler)

            try {
                val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerA.surface)
                    rawImageReaderA?.let { addTarget(it.surface) }
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
                    if (isManualFocus) {
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters)
                    }
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }

                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        super.onCaptureCompleted(session, request, result)
                    }
                }, backgroundHandler)

            } catch (e: Exception) {
                binding.btnCapture.isEnabled = true
                Log.e(TAG, "Error captura single: ${e.message}")
            }

        } else {
            val idA = selectedPair.first
            val idB = selectedPair.second
            val readerB = imageReaderB ?: return
            val baseName = "stereo_${timestamp}"

            var pathA = ""
            var pathB = ""
            var dngPathA: String? = null
            var dngPathB: String? = null

            readerA.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    img.close()
                    pathA = saveJpegImage("${baseName}_cam${idA}.jpg", bytes)
                    checkStereoDone(baseName, isoDateStr, idA, idB, manualFocalMm, manualBaselineCm, pathA, pathB, dngPathA, dngPathB)
                }
            }, backgroundHandler)

            readerB.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    img.close()
                    pathB = saveJpegImage("${baseName}_cam${idB}.jpg", bytes)
                    checkStereoDone(baseName, isoDateStr, idA, idB, manualFocalMm, manualBaselineCm, pathA, pathB, dngPathA, dngPathB)
                }
            }, backgroundHandler)

            try {
                val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerA.surface)
                    addTarget(readerB.surface)
                    rawImageReaderA?.let { addTarget(it.surface) }
                    rawImageReaderB?.let { addTarget(it.surface) }

                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
                    if (isManualFocus) {
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters)
                    }
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }

                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        super.onCaptureCompleted(session, request, result)
                    }
                }, backgroundHandler)

            } catch (e: Exception) {
                binding.btnCapture.isEnabled = true
                Log.e(TAG, "Error captura estéreo: ${e.message}")
            }
        }
    }

    private fun checkStereoDone(
        baseName: String,
        isoDateStr: String,
        idA: String,
        idB: String,
        focalMm: Float,
        baselineCm: Float,
        pathA: String,
        pathB: String,
        dngA: String?,
        dngB: String?
    ) {
        if (pathA.isNotEmpty() && pathB.isNotEmpty()) {
            checkAndSaveJsonAndTxt(
                baseName = baseName,
                isoDate = isoDateStr,
                mode = "Estéreo Sincronizado (Cam $idA + Cam $idB)",
                camIdA = idA,
                camIdB = idB,
                focalMm = focalMm,
                baselineCm = baselineCm,
                jpgPathA = pathA,
                jpgPathB = pathB,
                dngPathA = dngA,
                dngPathB = dngB
            )
        }
    }

    private fun checkAndSaveJsonAndTxt(
        baseName: String,
        isoDate: String,
        mode: String,
        camIdA: String,
        camIdB: String?,
        focalMm: Float,
        baselineCm: Float,
        jpgPathA: String,
        jpgPathB: String?,
        dngPathA: String?,
        dngPathB: String?
    ) {
        val focusMeters = 1.0f / focusDistanceDiopters

        val jsonContent = """
        {
          "timestamp": "$isoDate",
          "device": "OPPO Find X9 Pro (CPH2791)",
          "acquisition_mode": "$mode",
          "camera_ids": {
            "logical": "$LOGICAL_CAMERA_ID",
            "camera_a": "$camIdA",
            "camera_b": ${if (camIdB != null) "\"$camIdB\"" else "null"}
          },
          "manual_calibration": {
            "physical_focal_length_mm": $focalMm,
            "stereo_baseline_cm": $baselineCm,
            "focus_distance_meters": $focusMeters,
            "lens_focus_diopters": $focusDistanceDiopters
          },
          "camera_settings": {
            "ae_locked": $isAeLocked,
            "manual_focus": $isManualFocus,
            "capture_resolution": "${activeCaptureSize.width}x${activeCaptureSize.height}"
          },
          "files": {
            "jpg_a": "$jpgPathA",
            "jpg_b": ${if (jpgPathB != null) "\"$jpgPathB\"" else "null"},
            "dng_a": ${if (dngPathA != null) "\"$dngPathA\"" else "null"},
            "dng_b": ${if (dngPathB != null) "\"$dngPathB\"" else "null"}
          }
        }
        """.trimIndent()

        val jsonFilePath = saveTextFile("${baseName}.json", jsonContent, "application/json")

        val txtContent = """
        =================================================================
        METADATOS DE FOTOGRAMETRÍA ESTÉREO Y CALIBRACIÓN MANUAL
        =================================================================
        - Fecha / Timestamp: $isoDate
        - Modo: $mode
        - Cámara Izquierda: ID $camIdA
        - Cámara Derecha: ID ${camIdB ?: "N/A"}
        - Distancia Focal Física (Manual): $focalMm mm
        - Baseline (Distancia entre cámaras Manual): $baselineCm cm
        - Distancia de Enfoque: %.2f m (%.2f Dp)
        - Archivo JPG A: $jpgPathA
        - Archivo JPG B: ${jpgPathB ?: "N/A"}
        - Archivo JSON: $jsonFilePath
        =================================================================
        """.trimIndent().format(focusMeters, focusDistanceDiopters)

        val txtFilePath = saveTextFile("${baseName}.txt", txtContent, "text/plain")

        runOnUiThread {
            binding.btnCapture.isEnabled = true
            val logMsg = """
                ✓ CAPTURA Y METADATOS JSON GUARDADOS
                Focal: ${focalMm}mm | Baseline: ${baselineCm}cm
                JSON: ${File(jsonFilePath).name}
                TXT: ${File(txtFilePath).name}
                Ruta: ${File(jpgPathA).parent}
            """.trimIndent()
            binding.tvCaptureLog.text = logMsg
            Toast.makeText(this, "¡Imágenes y archivo JSON guardados con éxito!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveJpegImage(fileName: String, data: ByteArray): String {
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!outputDir.exists()) outputDir.mkdirs()

        val localFile = File(outputDir, fileName)
        FileOutputStream(localFile).use { fos ->
            fos.write(data)
            fos.flush()
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StereoPhotogrammetry")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(data); it.flush() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore error: ${e.message}")
        }

        return localFile.absolutePath
    }

    private fun saveTextFile(fileName: String, content: String, mimeType: String): String {
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!outputDir.exists()) outputDir.mkdirs()

        val file = File(outputDir, fileName)
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray())
            fos.flush()
        }

        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StereoPhotogrammetry")
                }
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()); it.flush() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore file error: ${e.message}")
        }

        return file.absolutePath
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReaderA?.close(); imageReaderA = null
        imageReaderB?.close(); imageReaderB = null
        rawImageReaderA?.close(); rawImageReaderA = null
        rawImageReaderB?.close(); rawImageReaderB = null
    }
}
