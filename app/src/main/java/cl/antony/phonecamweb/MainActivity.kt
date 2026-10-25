package cl.antony.phonecamweb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.antony.phonecamweb.net.NetworkUtils
import cl.antony.phonecamweb.stream.StreamService
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
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PhoneCam Web", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (status.running) "Transmitiendo" else "Detenido",
            style = MaterialTheme.typography.titleMedium,
        )

        if (status.running) StatsCard(status)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conexión por USB", style = MaterialTheme.typography.titleSmall)
                Text("En la Mac, ejecuta:")
                Text("adb forward tcp:5000 tcp:5000", fontFamily = FontFamily.Monospace)
                Text("Después conéctate a:")
                Text("ws://localhost:${StreamService.PORT}", fontFamily = FontFamily.Monospace)
                Text(
                    "IP Wi‑Fi: ${wifiIp ?: "sin red"} (se habilita en la fase 4)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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
        }
    }
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
