package cl.antony.phonecamweb.stream

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cl.antony.phonecamweb.MainActivity
import cl.antony.phonecamweb.net.WebSocketServer
import java.net.InetAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Servicio en primer plano (tipo `camera`): mantiene la cámara, el encoder y
 * el servidor WebSocket vivos aunque la pantalla se apague o cambies de app.
 *
 * Flujo: CameraX (Preview) → GlBridge (GPU) → superficie del VideoEncoder → FrameHub → clientes.
 */
class StreamService : LifecycleService() {

    @Volatile
    private var encoder: VideoEncoder? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var statsJob: Job? = null
    private val meter = RateMeter()
    private var orientationListener: OrientationEventListener? = null

    /** Lado hacia el que está acostado el teléfono. Lo lee GlBridge en cada cuadro. */
    @Volatile
    private var topToRight = false

    private var useFrontCamera = false

    /** Momentos de los últimos reinicios automáticos, para no reintentar sin fin. */
    private val restartTimes = ArrayDeque<Long>()

    private val hub = FrameHub(
        requestKeyframe = { encoder?.requestKeyframe() },
        onSubscribersChanged = { count -> StreamState.update { it.copy(clients = count) } },
    )

    private var server: WebSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        try {
            startServer()
        } catch (e: Exception) {
            fail("No se pudo abrir el puerto $PORT: ${e.message}")
            stopSelf()
            return
        }
        StreamState.update { it.copy(running = true, error = null) }
        startCamera()
        startStats()
        startOrientationTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_RELOAD_NETWORK -> reloadServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statsJob?.cancel()
        orientationListener?.disable()
        cameraProvider?.unbindAll() // CameraX suelta la superficie y eso libera el encoder
        server?.stop()
        StreamState.reset(keepError = true)
        super.onDestroy()
    }

    // ---- Red ----

    /**
     * Sin Wi‑Fi escucha solo en 127.0.0.1 (el cable, vía adb forward). Con Wi‑Fi escucha en
     * todas las interfaces y exige el código de acceso a quien no venga del propio teléfono.
     */
    private fun startServer() {
        val wifi = StreamSettings.wifiEnabled(this)
        val address = if (wifi) InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) else LOOPBACK
        val newServer = WebSocketServer(
            port = PORT,
            hub = hub,
            bindAddress = address,
            accessCode = { if (wifi) StreamSettings.accessCode(this) else null },
            log = { Log.i(TAG, it) },
        )
        newServer.start()
        server = newServer
    }

    /** Cambió la opción de Wi‑Fi o el código: reabrimos el servidor (los clientes reconectan). */
    private fun reloadServer() {
        server?.stop()
        server = null
        try {
            startServer()
        } catch (e: Exception) {
            fail("No se pudo reabrir el puerto $PORT: ${e.message}")
            stopSelf()
        }
    }

    // ---- Cámara ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                fail("No se pudo abrir la cámara: ${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Conecta la cámara elegida (trasera o frontal). Desconectar la anterior hace que
     * CameraX suelte su superficie, y eso libera el puente y el encoder viejos; la nueva
     * conexión pide otra superficie y se crea un encoder nuevo. El servidor y los clientes
     * no se enteran: solo reciben un keyframe nuevo.
     */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        val front = useFrontCamera
        preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
            onSurfaceRequested(request, mirror = front)
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview)
        StreamState.update { it.copy(frontCamera = front) }
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return
        val target = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!provider.hasCamera(target)) {
            fail("Este teléfono no tiene esa cámara")
            return
        }
        useFrontCamera = !useFrontCamera
        try {
            bindCamera()
        } catch (e: Exception) {
            fail("No se pudo cambiar de cámara: ${e.message}")
        }
    }

    /**
     * El encoder falló: reiniciamos cámara y encoder. Si falla demasiadas veces seguidas,
     * nos detenemos para no dejar la cámara del sistema en mal estado.
     */
    private fun recoverFromEncoderError(error: Throwable) {
        val now = System.currentTimeMillis()
        restartTimes.addLast(now)
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) restartTimes.removeFirst()
        if (restartTimes.size > MAX_RESTARTS) {
            fail("El encoder falló ${restartTimes.size} veces en un minuto: ${error.message}")
            stopSelf()
            return
        }
        Log.w(TAG, "Error del encoder, reiniciando cámara (${restartTimes.size}/$MAX_RESTARTS)", error)
        StreamState.update { it.copy(restarts = it.restarts + 1) }
        cameraProvider?.unbindAll()
        lifecycleScope.launch {
            delay(RESTART_DELAY_MS.milliseconds)
            try {
                bindCamera()
            } catch (e: Exception) {
                fail("No se pudo reiniciar la cámara: ${e.message}")
                stopSelf()
            }
        }
    }

    /** CameraX pide una superficie: creamos el encoder con la resolución que eligió. */
    private fun onSurfaceRequested(request: SurfaceRequest, mirror: Boolean) {
        val size = request.resolution
        val newEncoder = try {
            VideoEncoder(
                width = size.width,
                height = size.height,
                onFrame = { frame ->
                    meter.record(frame.data.size)
                    hub.publish(frame)
                },
                onError = { e ->
                    // Un encoder caído no se recupera solo: hay que crear otro.
                    ContextCompat.getMainExecutor(this).execute { recoverFromEncoderError(e) }
                },
            )
        } catch (e: Exception) {
            request.willNotProvideSurface()
            fail("No se pudo crear el encoder: ${e.message}")
            return
        }
        // La cámara no escribe directo en el encoder: pasa por la GPU (ver GlBridge).
        val bridge = try {
            GlBridge(newEncoder.inputSurface, size.width, size.height, mirror = mirror, isTopToRight = { topToRight })
        } catch (e: Exception) {
            newEncoder.release()
            request.willNotProvideSurface()
            fail("No se pudo iniciar OpenGL: ${e.message}")
            return
        }
        encoder = newEncoder
        StreamState.update { it.copy(width = size.width, height = size.height) }
        request.provideSurface(bridge.inputSurface, ContextCompat.getMainExecutor(this)) {
            // CameraX ya no usa la superficie: primero se detiene el dibujo y luego el encoder.
            bridge.release()
            newEncoder.release()
            if (encoder === newEncoder) encoder = null
        }
    }

    /**
     * El video siempre es horizontal; solo falta saber hacia qué lado está acostado el
     * teléfono para no mostrarlo al revés. Vertical o boca arriba: se mantiene el último lado.
     */
    private fun startOrientationTracking() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                when (orientation) {
                    in 60..120 -> topToRight = true // lado izquierdo arriba: la parte de arriba apunta a la derecha
                    in 240..300 -> topToRight = false // lado derecho arriba: la parte de arriba apunta a la izquierda
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    // ---- Estado y notificación ----

    private fun startStats() {
        statsJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                val (fps, kbps) = meter.snapshot()
                StreamState.update { it.copy(fps = fps, kbps = kbps) }
            }
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        StreamState.update { it.copy(error = message) }
    }

    private fun startInForeground() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Transmisión de cámara")
                .build(),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PhoneCam Web transmitiendo")
            .setContentText("Puerto $PORT")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Detener", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val PORT = 5000
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "cl.antony.phonecamweb.STOP"
        private const val ACTION_SWITCH_CAMERA = "cl.antony.phonecamweb.SWITCH_CAMERA"
        private const val ACTION_RELOAD_NETWORK = "cl.antony.phonecamweb.RELOAD_NETWORK"
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 60_000L
        private const val RESTART_DELAY_MS = 500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }

        /** Aplica cambios de Wi‑Fi o del código. Si el servicio no está corriendo, no hace nada. */
        fun reloadNetwork(context: Context) {
            if (!StreamState.status.value.running) return
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_RELOAD_NETWORK))
        }

        /** Alterna entre la cámara trasera y la frontal (el servicio debe estar corriendo). */
        fun switchCamera(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_SWITCH_CAMERA))
        }
    }
}
