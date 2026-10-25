package cl.antony.phonecamweb.stream

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.util.Size
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Servicio en primer plano (tipo `camera`): mantiene la cámara, el encoder y
 * el servidor WebSocket vivos aunque la pantalla se apague o cambies de app.
 *
 * Flujo: CameraX (Preview) → superficie del VideoEncoder → FrameHub → clientes.
 */
class StreamService : LifecycleService() {

    @Volatile
    private var encoder: VideoEncoder? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var statsJob: Job? = null
    private val meter = RateMeter()

    private val hub = FrameHub(
        requestKeyframe = { encoder?.requestKeyframe() },
        onSubscribersChanged = { count -> StreamState.update { it.copy(clients = count) } },
    )

    private val server = WebSocketServer(
        port = PORT,
        hub = hub,
        // Fase 1: solo localhost (USB con adb forward). En la fase 4 se abre a Wi‑Fi.
        log = { Log.i(TAG, it) },
    )

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        try {
            server.start()
        } catch (e: Exception) {
            fail("No se pudo abrir el puerto $PORT: ${e.message}")
            stopSelf()
            return
        }
        StreamState.update { it.copy(running = true, error = null) }
        startCamera()
        startStats()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statsJob?.cancel()
        cameraProvider?.unbindAll() // CameraX suelta la superficie y eso libera el encoder
        server.stop()
        StreamState.reset(keepError = true)
        super.onDestroy()
    }

    // ---- Cámara ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
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
                preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                    onSurfaceRequested(request)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                cameraProvider = provider
            } catch (e: Exception) {
                fail("No se pudo abrir la cámara: ${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** CameraX pide una superficie: creamos el encoder con la resolución que eligió. */
    private fun onSurfaceRequested(request: SurfaceRequest) {
        val size = request.resolution
        val newEncoder = try {
            VideoEncoder(
                width = size.width,
                height = size.height,
                onFrame = { frame ->
                    meter.record(frame.data.size)
                    hub.publish(frame)
                },
                onError = { e -> fail("Error del encoder: ${e.message}") },
            )
        } catch (e: Exception) {
            request.willNotProvideSurface()
            fail("No se pudo crear el encoder: ${e.message}")
            return
        }
        encoder = newEncoder
        StreamState.update { it.copy(width = size.width, height = size.height) }
        request.provideSurface(newEncoder.inputSurface, ContextCompat.getMainExecutor(this)) {
            // CameraX ya no usa la superficie: ahora sí se puede liberar el encoder.
            newEncoder.release()
            if (encoder === newEncoder) encoder = null
        }
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
