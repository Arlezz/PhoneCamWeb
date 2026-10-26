package cl.antony.phonecamweb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.antony.phonecamweb.net.NetworkUtils
import cl.antony.phonecamweb.stream.PreviewOutput
import cl.antony.phonecamweb.stream.StreamService
import cl.antony.phonecamweb.stream.StreamSettings
import cl.antony.phonecamweb.stream.StreamState
import cl.antony.phonecamweb.stream.StreamStatus
import cl.antony.phonecamweb.ui.theme.PhoneCamWebTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneCamWebTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StreamScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun StreamScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by StreamState.status.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    val wifiIp = remember(status.running) { NetworkUtils.localIpv4() }

    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            permissionDenied = false
            StreamService.start(context)
        } else {
            permissionDenied = true
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PhoneCam Web", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (status.running) "Transmitiendo" else "Detenido",
            style = MaterialTheme.typography.titleMedium,
        )

        if (status.running) {
            CameraPreview()
            OutlinedButton(onClick = { StreamService.switchCamera(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.frontCamera) "Usar cámara trasera" else "Usar cámara frontal")
            }
            StatsCard(status)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conexión por USB", style = MaterialTheme.typography.titleSmall)
                Text("En la Mac, ejecuta:")
                Text("adb forward tcp:5000 tcp:5000", fontFamily = FontFamily.Monospace)
                Text("Después conéctate a:")
                Text("ws://127.0.0.1:${StreamService.PORT}", fontFamily = FontFamily.Monospace)
            }
        }

        WifiCard(wifiIp)

        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (permissionDenied) {
            Text("Sin permiso de cámara no se puede transmitir.", color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                when {
                    status.running -> StreamService.stop(context)
                    hasCameraPermission(context) -> StreamService.start(context)
                    else -> launcher.launch(permissions)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (status.running) "Detener" else "Iniciar")
        }
    }
}

@Composable
private fun StatsCard(status: StreamStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Clientes conectados", status.clients.toString())
            StatRow("Resolución", if (status.width > 0) "${status.width} × ${status.height}" else "—")
            StatRow("Cuadros por segundo", status.fps.toString())
            StatRow("Bitrate", "${status.kbps} kbps")
            StatRow("Cámara", if (status.frontCamera) "Frontal" else "Trasera")
            if (status.restarts > 0) StatRow("Reinicios automáticos", status.restarts.toString())
        }
    }
}

/**
 * Conexión por Wi‑Fi: interruptor, dirección con el código para pegar en la extensión,
 * y botón para cambiar el código (desconecta a quien usara el anterior).
 */
@Composable
private fun WifiCard(wifiIp: String?) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StreamSettings.wifiEnabled(context)) }
    var code by remember { mutableStateOf(StreamSettings.accessCode(context)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Conexión por Wi‑Fi", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        StreamSettings.setWifiEnabled(context, value)
                        StreamService.reloadNetwork(context)
                    },
                )
            }
            if (!enabled) {
                Text("Desactivada: solo se aceptan conexiones por cable.", style = MaterialTheme.typography.bodySmall)
            } else if (wifiIp == null) {
                Text("El teléfono no está conectado a una red Wi‑Fi.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("En el popup de la extensión, usa esta dirección:")
                SelectionContainer {
                    Text("ws://$wifiIp:${StreamService.PORT}/?code=$code", fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(
                    onClick = {
                        code = StreamSettings.regenerateCode(context)
                        StreamService.reloadNetwork(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Generar código nuevo") }
                Text(
                    "El código evita que otros se conecten, pero el video viaja sin cifrar por la red: " +
                        "usa Wi‑Fi solo en redes de confianza.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Vista previa de lo que se está enviando (16:9, sin espejo). Dibuja en ella el puente
 * OpenGL del servicio, no la cámara directamente: así ves exactamente lo mismo que Meet.
 */
@Composable
private fun CameraPreview() {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = PreviewOutput.attach(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = PreviewOutput.detach()
                })
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
