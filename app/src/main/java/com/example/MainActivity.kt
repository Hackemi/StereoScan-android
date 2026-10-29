package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
import android.widget.Button
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

/**
 * MainActivity - Fotogrametría Estéreo con Camera2 API en OPPO Find X9 Pro (Android 16 / SDK 36).
 *
 * Características del dispositivo:
 * - Cámara lógica trasera: ID "0" (Soporta LOGICAL_MULTI_CAMERA).
 * - Cámaras físicas expuestas: IDs "2", "3" y "4".
 * - Nivel de hardware: LEVEL_3 (Control manual completo de AE, AF y flujos).
 *
 * Flujo principal:
 * 1. Verificación previa de combinaciones físicas mediante isSessionConfigurationSupported().
 * 2. Apertura de la cámara lógica ID 0.
 * 3. Enrutamiento de streams simultáneos a dos TextureViews usando OutputConfiguration.setPhysicalCameraId().
 * 4. Bloqueo estricto de AE y control de distancia focal fija (rango coche: 20cm a 80cm).
 * 5. Disparo de captura simultánea en una sola petición CaptureRequest guardando pares JPEG correlacionados.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StereoPhotogrammetry"
        private const val LOGICAL_CAMERA_ID = "0"

        // Resoluciones de preview y captura optimizadas para fotogrametría estéreo
        private val PREVIEW_SIZE = Size(640, 480)
        private val CAPTURE_SIZE = Size(1920, 1080)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    // Hilos de fondo para operaciones de cámara y I/O
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    // Estado de la cámara y sesión activa
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Combinaciones candidatas de cámaras físicas (2+3, 2+4, 3+4)
    private val candidatePairs = listOf(
        Pair("2", "3"),
        Pair("2", "4"),
        Pair("3", "4")
    )
    private val pairSupportMap = mutableMapOf<Pair<String, String>, Boolean>()
    private var selectedPair: Pair<String, String> = candidatePairs[0]

    // Superficies para TextureViews
    private var surfaceA: Surface? = null
    private var surfaceB: Surface? = null

    // ImageReaders para la captura de fotos sincronizadas de cada cámara física
    private var imageReaderA: ImageReader? = null
    private var imageReaderB: ImageReader? = null

    // Parámetros de control óptico y fotogrametría (distancia de trabajo 20cm - 80cm)
    private var isAeLocked = true
    private var isManualFocus = true
    // Distancia focal en Dioptrías (1 / distancia_en_metros). Por defecto 40 cm -> 1 / 0.4 = 2.5 Dp
    private var focusDistanceDiopters = 2.5f

    // Lanzador para solicitar permisos en tiempo de ejecución
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupCameraPipeline()
        } else {
            Toast.makeText(
                this,
                "Se requiere permiso de cámara para la fotogrametría estéreo.",
                Toast.LENGTH_LONG
            ).show()
            binding.tvVerificationStatus.text = "ERROR: Permiso de cámara denegado."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /**
     * Inicializa los hilos de fondo necesarios para no bloquear el hilo de interfaz (UI Thread).
     */
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
            Log.e(TAG, "Error deteniendo hilo de cámara: ${e.message}")
        }
    }

    /**
     * Configura los listeners de los botones de la interfaz:
     * - Selector de combinaciones de cámaras físicas.
     * - Bloqueo de exposición (AE Lock).
     * - Modo de enfoque y distancia focal fija para interior de vehículo.
     * - Botón de captura sincronizada.
     */
    private fun setupUIListeners() {
        // Selector de combinaciones
        binding.btnPair23.setOnClickListener { switchCameraPair(Pair("2", "3")) }
        binding.btnPair24.setOnClickListener { switchCameraPair(Pair("2", "4")) }
        binding.btnPair34.setOnClickListener { switchCameraPair(Pair("3", "4")) }

        // Bloqueo de Exposición (AE Lock)
        binding.btnToggleAeLock.setOnClickListener {
            isAeLocked = !isAeLocked
            updateAeButtonUi()
            applyPreviewSettings()
        }

        // Modo de Enfoque (AF Manual vs Continuo)
        binding.btnToggleAfMode.setOnClickListener {
            isManualFocus = !isManualFocus
            updateAfButtonUi()
            applyPreviewSettings()
        }

        // Presets de distancia para interiores de vehículos (20cm - 80cm)
        binding.btnDist20cm.setOnClickListener { setManualFocusDistance(0.20f, "20 cm") }
        binding.btnDist40cm.setOnClickListener { setManualFocusDistance(0.40f, "40 cm") }
        binding.btnDist60cm.setOnClickListener { setManualFocusDistance(0.60f, "60 cm") }
        binding.btnDist80cm.setOnClickListener { setManualFocusDistance(0.80f, "80 cm") }

        // Disparo de captura simultánea
        binding.btnCapture.setOnClickListener {
            triggerSynchronizedStereoCapture()
        }
    }

    private fun setManualFocusDistance(distanceInMeters: Float, label: String) {
        // En Camera2 la distancia de enfoque se expresa en dioptrías: 1 / distancia_en_metros
        focusDistanceDiopters = 1.0f / distanceInMeters
        binding.tvFocusDistanceLabel.text =
            "Distancia enfoque manual: $label (%.2f Dp)".format(focusDistanceDiopters)

        // Resaltar botón activo
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
            binding.btnToggleAeLock.background =
                ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        } else {
            binding.btnToggleAeLock.text = "🔓 AE: Auto (Desbloqueado)"
            binding.btnToggleAeLock.background =
                ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
        }
    }

    private fun updateAfButtonUi() {
        if (isManualFocus) {
            binding.btnToggleAfMode.text = "🎯 AF: Manual (Fijo)"
            binding.btnToggleAfMode.background =
                ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        } else {
            binding.btnToggleAfMode.text = "🔄 AF: Continuo (Auto)"
            binding.btnToggleAfMode.background =
                ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)
        }
    }

    /**
     * Inicializa el pipeline de la cámara:
     * 1. Verifica compatibilidad previa con isSessionConfigurationSupported().
     * 2. Configura los listeners de TextureView.
     * 3. Abre la cámara cuando ambas superficies estén listas.
     */
    private fun setupCameraPipeline() {
        // Verificar qué combinaciones físicas soportan streaming simultáneo
        verifyPhysicalCameraCombinations()

        // Configurar listeners para ambos TextureViews
        setupTextureViewListeners()
    }

    /**
     * PASO CRÍTICO 1: Verificación de soporte con isSessionConfigurationSupported().
     *
     * En Android 15+ (API 35/36), CameraManager.getCameraDeviceSetup(id) permite consultar
     * isSessionConfigurationSupported() ANTES de llamar a openCamera().
     * Para cada combinación física (2+3, 2+4, 3+4), creamos OutputConfigurations
     * ficticios con setPhysicalCameraId() y validamos si el HAL los admite simultáneamente.
     */
    private fun verifyPhysicalCameraCombinations() {
        binding.tvVerificationStatus.text = "Consultando HAL para combinaciones 2+3, 2+4, 3+4..."

        sessionExecutor.execute {
            val statusBuilder = StringBuilder("Resultados de compatibilidad:\n")

            try {
                // Verificar que la cámara lógica 0 expone las cámaras físicas
                val characteristics = cameraManager.getCameraCharacteristics(LOGICAL_CAMERA_ID)
                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.physicalCameraIds
                } else {
                    emptySet()
                }

                Log.d(TAG, "Cámaras físicas expuestas por ID 0: $physicalIds")

                // En Android 15/16 (API 35+), podemos usar CameraDeviceSetup
                val supportsSetup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    cameraManager.isCameraDeviceSetupSupported(LOGICAL_CAMERA_ID)
                } else {
                    false
                }

                for (pair in candidatePairs) {
                    val idA = pair.first
                    val idB = pair.second

                    var isSupported = false

                    if (supportsSetup && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        try {
                            val setup = cameraManager.getCameraDeviceSetup(LOGICAL_CAMERA_ID)

                            // Creamos ImageReaders temporales para validar el soporte de las dos salidas
                            val tempReaderA = ImageReader.newInstance(
                                PREVIEW_SIZE.width, PREVIEW_SIZE.height, ImageFormat.YUV_420_888, 1
                            )
                            val tempReaderB = ImageReader.newInstance(
                                PREVIEW_SIZE.width, PREVIEW_SIZE.height, ImageFormat.YUV_420_888, 1
                            )

                            // Configuración de salida asignando el ID físico de cada cámara
                            val outputA = OutputConfiguration(tempReaderA.surface).apply {
                                setPhysicalCameraId(idA)
                            }
                            val outputB = OutputConfiguration(tempReaderB.surface).apply {
                                setPhysicalCameraId(idB)
                            }

                            val testSessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(outputA, outputB),
                                sessionExecutor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {}
                                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                                }
                            )

                            isSupported = setup.isSessionConfigurationSupported(testSessionConfig)

                            tempReaderA.close()
                            tempReaderB.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Excepción validando par $idA + $idB vía CameraDeviceSetup: ${e.message}")
                            isSupported = false
                        }
                    } else {
                        // En dispositivos donde CameraDeviceSetup no esté disponible antes de abrir,
                        // asumimos como candidato válido los IDs físicos expuestos y se re-valida al abrir.
                        isSupported = physicalIds.contains(idA) && physicalIds.contains(idB)
                    }

                    pairSupportMap[pair] = isSupported
                    val symbol = if (isSupported) "✓ VÁLIDA" else "✗ NO SOPORTADA"
                    statusBuilder.append("• Par ($idA + $idB): $symbol\n")
                }

                // Elegir la primera combinación válida
                val firstValid = candidatePairs.firstOrNull { pairSupportMap[it] == true }
                if (firstValid != null) {
                    selectedPair = firstValid
                }

                runOnUiThread {
                    binding.tvVerificationStatus.text = statusBuilder.toString().trim()
                    updatePairButtonsUI()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error verificando combinaciones: ${e.message}", e)
                runOnUiThread {
                    binding.tvVerificationStatus.text =
                        "Error consultando soporte: ${e.localizedMessage}. Se usará par 2+3 por defecto."
                }
            }
        }
    }

    private fun updatePairButtonsUI() {
        val idA = selectedPair.first
        val idB = selectedPair.second

        // Actualizar badges de los viewfinders
        binding.tvBadgeCamA.text = "CAM ID: $idA (${getCamName(idA)})"
        binding.tvBadgeCamB.text = "CAM ID: $idB (${getCamName(idB)})"

        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_primary)
        val inactiveBg = ContextCompat.getDrawable(this, R.drawable.bg_btn_secondary)

        binding.btnPair23.background = if (selectedPair == Pair("2", "3")) activeBg else inactiveBg
        binding.btnPair24.background = if (selectedPair == Pair("2", "4")) activeBg else inactiveBg
        binding.btnPair34.background = if (selectedPair == Pair("3", "4")) activeBg else inactiveBg

        // Mostrar texto de estado en el botón
        binding.btnPair23.text = "2 + 3\n" + (if (pairSupportMap[Pair("2", "3")] == true) "✓ Válida" else "?")
        binding.btnPair24.text = "2 + 4\n" + (if (pairSupportMap[Pair("2", "4")] == true) "✓ Válida" else "?")
        binding.btnPair34.text = "3 + 4\n" + (if (pairSupportMap[Pair("3", "4")] == true) "✓ Válida" else "?")
    }

    private fun getCamName(id: String): String = when (id) {
        "2" -> "Ultra-Wide"
        "3" -> "Wide / Main"
        "4" -> "Telephoto"
        else -> "Física $id"
    }

    private fun switchCameraPair(newPair: Pair<String, String>) {
        if (newPair == selectedPair) return

        if (pairSupportMap[newPair] == false) {
            Toast.makeText(
                this,
                "Aviso: La combinación ${newPair.first}+${newPair.second} fue marcada como no soportada por el HAL.",
                Toast.LENGTH_SHORT
            ).show()
        }

        selectedPair = newPair
        updatePairButtonsUI()

        // Reiniciar la sesión de cámara con la nueva combinación
        if (cameraDevice != null) {
            createDualCameraSession()
        }
    }

    /**
     * Configura los SurfaceTextureListeners para ambos TextureViews.
     * Solo cuando ambas superficies estén creadas se abre la cámara.
     */
    private fun setupTextureViewListeners() {
        var textureAReady = false
        var textureBReady = false

        binding.textureViewA.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
                surfaceA = Surface(st)
                textureAReady = true
                if (textureBReady) {
                    openLogicalCamera()
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceA?.release()
                surfaceA = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        binding.textureViewB.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
                surfaceB = Surface(st)
                textureBReady = true
                if (textureAReady) {
                    openLogicalCamera()
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceB?.release()
                surfaceB = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /**
     * PASO CRÍTICO 2: Abrir la cámara lógica trasera ID "0".
     */
    @SuppressLint("MissingPermission")
    private fun openLogicalCamera() {
        try {
            Log.d(TAG, "Abriendo cámara lógica ID $LOGICAL_CAMERA_ID...")
            cameraManager.openCamera(LOGICAL_CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Cámara lógica ID 0 abierta correctamente.")
                    cameraDevice = camera
                    createDualCameraSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Cámara desconectada.")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Error abriendo cámara: código $error")
                    camera.close()
                    cameraDevice = null
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Error abriendo cámara ID 0 (código: $error)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Excepción accediendo a la cámara: ${e.message}", e)
        }
    }

    /**
     * PASO CRÍTICO 3: Crear sesión con OutputConfiguration y setPhysicalCameraId().
     *
     * Para fotogrametría estéreo de dos cámaras físicas en una sola cámara lógica:
     * - OutputConfiguration(surfaceA).setPhysicalCameraId(selectedPair.first)
     * - OutputConfiguration(surfaceB).setPhysicalCameraId(selectedPair.second)
     * - Se agregan además dos ImageReaders (uno por cada cámara física) para la captura estéreo simultánea.
     */
    private fun createDualCameraSession() {
        val device = cameraDevice ?: return
        val sA = surfaceA ?: return
        val sB = surfaceB ?: return

        // Cerrar sesión anterior si existe
        captureSession?.close()
        captureSession = null

        imageReaderA?.close()
        imageReaderB?.close()

        val idA = selectedPair.first
        val idB = selectedPair.second

        try {
            // Inicializar ImageReaders para captura de fotos de alta resolución
            imageReaderA = ImageReader.newInstance(
                CAPTURE_SIZE.width, CAPTURE_SIZE.height, ImageFormat.JPEG, 2
            )
            imageReaderB = ImageReader.newInstance(
                CAPTURE_SIZE.width, CAPTURE_SIZE.height, ImageFormat.JPEG, 2
            )

            // Configuración de Preview para Cámara Física A
            val previewOutputA = OutputConfiguration(sA).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPhysicalCameraId(idA)
                }
            }

            // Configuración de Preview para Cámara Física B
            val previewOutputB = OutputConfiguration(sB).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPhysicalCameraId(idB)
                }
            }

            // Configuración de Captura de Fotos para Cámara Física A
            val captureOutputA = OutputConfiguration(imageReaderA!!.surface).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPhysicalCameraId(idA)
                }
            }

            // Configuración de Captura de Fotos para Cámara Física B
            val captureOutputB = OutputConfiguration(imageReaderB!!.surface).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPhysicalCameraId(idB)
                }
            }

            val sessionOutputs = listOf(previewOutputA, previewOutputB, captureOutputA, captureOutputB)

            // Configuración de la sesión
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                sessionOutputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Sesión simultánea ($idA + $idB) configurada exitosamente.")
                        captureSession = session
                        applyPreviewSettings()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "onConfigureFailed para la combinación ($idA + $idB)")
                        runOnUiThread {
                            val suggestOther = candidatePairs.firstOrNull { it != selectedPair }
                            val msg = "Fallo al configurar sesión simultánea ($idA + $idB). " +
                                    (if (suggestOther != null) "Pruebe con (${suggestOther.first} + ${suggestOther.second})." else "")
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            binding.tvCaptureLog.text = "ALERTA: Sesión fallida para $idA + $idB.\n$msg"
                        }
                    }
                }
            )

            // Verificación previa en CameraDevice si la API lo soporta
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val supported = device.isSessionConfigurationSupported(sessionConfig)
                Log.d(TAG, "¿Sesión soportada por CameraDevice? $supported")
                if (!supported) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Advertencia: isSessionConfigurationSupported reportó falso para ($idA + $idB).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // Iniciar sesión
            device.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Error creando sesión de cámara estéreo: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Error creando sesión: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * PASO CRÍTICO 4: Bloqueo de Exposición (AE) y Enfoque Manual (AF) para fotogrametría.
     *
     * Durante la captura estéreo en vehículos (20-80cm):
     * - CONTROL_AE_LOCK = true evita variaciones lumínicas entre ambos ángulos.
     * - CONTROL_AF_MODE = CONTROL_AF_MODE_OFF junto a LENS_FOCUS_DISTANCE fija
     *   las lentes en una distancia milimétrica constante.
     */
    private fun applyPreviewSettings() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val sA = surfaceA ?: return
        val sB = surfaceB ?: return

        try {
            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                // Añadimos ambos targets de preview al mismo request repetitivo
                addTarget(sA)
                addTarget(sB)

                // Bloqueo de auto-exposición
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)

                // Modo y distancia de enfoque
                if (isManualFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }

            session.setRepeatingRequest(
                previewRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                    }
                },
                backgroundHandler
            )

            Log.d(TAG, "Preview repetitivo actualizado: AE_LOCK=$isAeLocked, AF_MANUAL=$isManualFocus (Dp=$focusDistanceDiopters)")

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error aplicando parámetros de preview: ${e.message}", e)
        }
    }

    /**
     * PASO CRÍTICO 5: Captura simultánea sincronizada en ambas cámaras físicas.
     *
     * Dispara una SOLA petición TEMPLATE_STILL_CAPTURE con las dos superficies
     * de ImageReader asignadas a cada cámara física. El hardware de la cámara
     * lógica dispara los dos sensores en el mismo ciclo temporal.
     */
    private fun triggerSynchronizedStereoCapture() {
        val session = captureSession
        val device = cameraDevice
        val readerA = imageReaderA
        val readerB = imageReaderB

        if (session == null || device == null || readerA == null || readerB == null) {
            Toast.makeText(this, "La sesión de cámara aún no está lista", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false
        binding.tvCaptureLog.text = "Iniciando disparo sincronizado estéreo..."

        val idA = selectedPair.first
        val idB = selectedPair.second
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

        // Configurar listeners de imagen para guardar cada archivo JPEG
        var imageASaved = false
        var imageBSaved = false
        var fileAPath = ""
        var fileBPath = ""

        readerA.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                fileAPath = saveJpegImage("stereo_${timestamp}_cam${idA}.jpg", bytes)
                imageASaved = true
                checkCaptureCompletion(imageASaved, imageBSaved, fileAPath, fileBPath, timestamp)
            }
        }, backgroundHandler)

        readerB.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                fileBPath = saveJpegImage("stereo_${timestamp}_cam${idB}.jpg", bytes)
                imageBSaved = true
                checkCaptureCompletion(imageASaved, imageBSaved, fileAPath, fileBPath, timestamp)
            }
        }, backgroundHandler)

        try {
            // Petición única de captura estéreo con ambos targets
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerA.surface)
                addTarget(readerB.surface)

                // Preservar el bloqueo estricto durante la toma
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)

                if (isManualFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                // Calidad JPEG al 95% para preservar texturas de fotogrametría
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }

            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        Log.d(TAG, "Disparo estéreo completado a nivel de hardware.")
                    }
                },
                backgroundHandler
            )

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error disparando captura estéreo: ${e.message}", e)
            binding.btnCapture.isEnabled = true
            Toast.makeText(this, "Error en disparo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Guarda el buffer JPEG en el almacenamiento de la aplicación y en MediaStore (Scoped Storage),
     * compatible con Android 10 a Android 16 (SDK 36) para acceso inmediato en la galería y PC.
     */
    private fun saveJpegImage(fileName: String, data: ByteArray): String {
        // 1. Guardar en almacenamiento privado de la app (getExternalFilesDir o filesDir)
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!outputDir.exists()) outputDir.mkdirs()

        val localFile = File(outputDir, fileName)
        FileOutputStream(localFile).use { fos ->
            fos.write(data)
            fos.flush()
        }
        Log.d(TAG, "Guardado archivo estéreo en almacenamiento app: ${localFile.absolutePath} (${data.size / 1024} KB)")

        // 2. Insertar en MediaStore (Scoped Storage compliant para Android 10 - 16 / SDK 36)
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StereoPhotogrammetry")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    outputStream.write(data)
                    outputStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(imageUri, contentValues, null, null)
                }
                Log.d(TAG, "Registrado en MediaStore: $imageUri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso al insertar en MediaStore: ${e.message}")
        }

        return localFile.absolutePath
    }

    private fun checkCaptureCompletion(
        savedA: Boolean,
        savedB: Boolean,
        pathA: String,
        pathB: String,
        timestamp: String
    ) {
        if (savedA && savedB) {
            runOnUiThread {
                binding.btnCapture.isEnabled = true
                val logText = """
                    ✓ PAR ESTÉREO CAPTURADO SINCRONIZADAMENTE
                    Timestamp: $timestamp
                    Cam A (ID ${selectedPair.first}): ${File(pathA).name} (${File(pathA).length() / 1024} KB)
                    Cam B (ID ${selectedPair.second}): ${File(pathB).name} (${File(pathB).length() / 1024} KB)
                    Ruta: ${File(pathA).parent}
                """.trimIndent()

                binding.tvCaptureLog.text = logText
                Toast.makeText(this, "¡Par estéreo guardado con éxito!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Cierra de forma ordenada los recursos de cámara.
     */
    private fun closeCamera() {
        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReaderA?.close()
        imageReaderA = null

        imageReaderB?.close()
        imageReaderB = null
    }
}
