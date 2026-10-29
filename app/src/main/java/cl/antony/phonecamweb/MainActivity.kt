package cl.antony.phonecamweb

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cl.antony.phonecamweb.net.NetworkUtils
import cl.antony.phonecamweb.stream.PreviewOutput
import cl.antony.phonecamweb.stream.StreamErrorKind
import cl.antony.phonecamweb.stream.StreamService
import cl.antony.phonecamweb.stream.StreamSettings
import cl.antony.phonecamweb.stream.StreamState
import cl.antony.phonecamweb.stream.StreamStatus
import cl.antony.phonecamweb.ui.theme.JetBrainsMono
import cl.antony.phonecamweb.ui.theme.LocalStatusColors
import cl.antony.phonecamweb.ui.theme.PhoneCamWebTheme
import cl.antony.phonecamweb.ui.theme.SpaceGrotesk

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

/** Lo que la pantalla muestra en la píldora o el banner y en la acción principal. */
sealed interface ScreenState {
    data object Off : ScreenState
    data object Starting : ScreenState
    data object Waiting : ScreenState
    data class Live(val browsers: Int) : ScreenState
    data class Failed(val kind: FailureKind) : ScreenState
}

/** `PERMISSION` vive solo en la UI: sin permiso el servicio nunca llega a arrancar. */
enum class FailureKind { PERMISSION, CAMERA, ENCODER, PORT }

fun screenState(status: StreamStatus, permissionDenied: Boolean): ScreenState = when {
    status.running && status.width == 0 -> ScreenState.Starting
    status.running && status.clients == 0 -> ScreenState.Waiting
    status.running -> ScreenState.Live(status.clients)
    permissionDenied -> ScreenState.Failed(FailureKind.PERMISSION)
    status.error?.kind?.fatal == true -> ScreenState.Failed(FailureKind.valueOf(status.error.kind.name))
    else -> ScreenState.Off
}

// TalkBack anuncia los cambios de la píldora y del banner: "Esperando conexión" → "En vivo · 1 navegador".
private val LiveRegion = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

private val TicketValue = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 28.sp)

@Composable
fun StreamScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by StreamState.status.collectAsState()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var wifiEnabled by rememberSaveable { mutableStateOf(StreamSettings.wifiEnabled(context)) }
    var accessCode by rememberSaveable { mutableStateOf(StreamSettings.accessCode(context)) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmNewCode by rememberSaveable { mutableStateOf(false) }
    val wifiIp = remember(status.running, wifiEnabled) { NetworkUtils.localIpv4() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    val state = screenState(status, permissionDenied)
    val switchFailed = status.running && status.error?.kind == StreamErrorKind.CAMERA_SWITCH
    val fullUrl = if (wifiEnabled && wifiIp != null) "ws://$wifiIp:${StreamService.PORT}/?code=$accessCode" else null

    val start = {
        permissionDenied = false
        if (hasCameraPermission(context)) StreamService.start(context) else launcher.launch(permissions)
    }
    val regenerateCode = {
        accessCode = StreamSettings.regenerateCode(context)
        StreamService.reloadNetwork(context)
    }

    if (landscape) {
        Row(modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PreviewWithStatus(
                state,
                status.running,
                Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(12.dp)),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Wordmark()
                if (state is ScreenState.Failed) ErrorBanner(state.kind, LiveRegion)
                PrimaryAction(state, status, onStart = start)
                if (switchFailed) SwitchFailedNotice()
                CompactConnectionCard(wifiEnabled, wifiIp, accessCode)
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark()
                ModeSelector(wifiEnabled) { wifi ->
                    // Tocar el segmento ya elegido no debe reiniciar el servidor.
                    if (wifi != wifiEnabled) {
                        wifiEnabled = wifi
                        StreamSettings.setWifiEnabled(context, wifi)
                        StreamService.reloadNetwork(context)
                    }
                }
            }
            PreviewWithStatus(state, status.running, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                if (state is ScreenState.Failed) {
                    ErrorBanner(state.kind, LiveRegion)
                    Spacer(Modifier.height(12.dp))
                }
                PrimaryAction(state, status, onStart = start)
                if (switchFailed) {
                    Spacer(Modifier.height(8.dp))
                    SwitchFailedNotice()
                }
                Spacer(Modifier.height(16.dp))
                TicketCard(
                    wifiEnabled = wifiEnabled,
                    wifiIp = wifiIp,
                    accessCode = accessCode,
                    onCopy = { fullUrl?.let { copyAddress(context, it) } },
                    onNewCode = { if (status.clients > 0) confirmNewCode = true else regenerateCode() },
                )
                Spacer(Modifier.height(8.dp))
                TechnicalDetails(status, detailsExpanded, onToggle = { detailsExpanded = !detailsExpanded }, fullUrl)
            }
        }
    }

    if (confirmNewCode) {
        AlertDialog(
            onDismissRequest = { confirmNewCode = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewCode = false
                    regenerateCode()
                }) { Text(stringResource(R.string.new_code_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewCode = false }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.new_code_title)) },
            text = { Text(stringResource(R.string.new_code_body)) },
        )
    }
}

/** Marca del encabezado: "PhoneCam" y la etiqueta con la luz roja y "Web". */
@Composable
private fun Wordmark() {
    Row(
        Modifier.semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.wordmark_main),
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            letterSpacing = (-0.02).em,
        )
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(LocalStatusColors.current.tally, CircleShape))
                Text(stringResource(R.string.wordmark_suffix), fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ModeSelector(wifiEnabled: Boolean, onModeChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = !wifiEnabled,
            onClick = { onModeChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.mode_cable)) }
        SegmentedButton(
            selected = wifiEnabled,
            onClick = { onModeChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.mode_wifi)) }
    }
}

/** Vista previa con la píldora encima. Con error no hay píldora: el banner va debajo. */
@Composable
private fun PreviewWithStatus(state: ScreenState, running: Boolean, modifier: Modifier) {
    Box(modifier) {
        StreamPreview(running, Modifier.fillMaxSize())
        if (state !is ScreenState.Failed) {
            StatusPill(state, Modifier.align(Alignment.TopStart).padding(12.dp).then(LiveRegion))
        }
    }
}

@Composable
private fun StreamPreview(running: Boolean, modifier: Modifier) {
    if (running) {
        CameraPreview(modifier.background(Color.Black))
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_videocam_off),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.camera_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(state: ScreenState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    val container: Color
    val content: Color
    val dot: Color
    val text: String
    when (state) {
        ScreenState.Off -> {
            container = scheme.surface; content = scheme.onSurfaceVariant; dot = colors.offDot
            text = stringResource(R.string.status_off)
        }
        ScreenState.Starting -> {
            container = scheme.surface; content = scheme.onSurfaceVariant; dot = scheme.onSurfaceVariant
            text = stringResource(R.string.status_starting)
        }
        ScreenState.Waiting -> {
            container = colors.waitingContainer; content = colors.onWaiting; dot = colors.waitingDot
            text = stringResource(R.string.status_waiting)
        }
        is ScreenState.Live -> {
            container = colors.liveContainer; content = colors.onLive; dot = colors.liveDot
            text = pluralStringResource(R.plurals.status_live, state.browsers, state.browsers)
        }
        is ScreenState.Failed -> return
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Row(
            Modifier.height(32.dp).padding(start = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(text, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ErrorBanner(kind: FailureKind, modifier: Modifier) {
    val context = LocalContext.current
    val (title, hint) = when (kind) {
        FailureKind.PERMISSION -> stringResource(R.string.err_permission_title) to stringResource(R.string.err_permission_hint)
        FailureKind.CAMERA -> stringResource(R.string.err_camera_title) to stringResource(R.string.err_camera_hint)
        FailureKind.ENCODER -> stringResource(R.string.err_encoder_title) to stringResource(R.string.err_encoder_hint)
        FailureKind.PORT -> stringResource(R.string.err_port_title) to stringResource(R.string.err_port_hint, StreamService.PORT)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null, modifier = Modifier.size(24.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                if (kind == FailureKind.PERMISSION) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text(stringResource(R.string.err_open_settings)) }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(state: ScreenState, status: StreamStatus, onStart: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    when (state) {
        ScreenState.Off, is ScreenState.Failed -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp), shape = shape) {
            Text(
                stringResource(if (state is ScreenState.Failed) R.string.action_retry else R.string.action_start),
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { StreamService.stop(context) }, modifier = Modifier.weight(1f).height(56.dp), shape = shape) {
                Text(stringResource(R.string.action_stop), fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            // Sin cámara conectada todavía (width == 0) no hay nada que cambiar.
            FilledTonalIconButton(
                onClick = { StreamService.switchCamera(context) },
                enabled = status.width > 0,
                modifier = Modifier.size(56.dp),
                shape = shape,
            ) {
                Icon(
                    painterResource(R.drawable.ic_cameraswitch),
                    contentDescription = stringResource(if (status.frontCamera) R.string.cd_use_back else R.string.cd_use_front),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** La transmisión sigue con la cámara anterior: es un aviso, no un error. */
@Composable
private fun SwitchFailedNotice() {
    Text(
        stringResource(R.string.switch_failed),
        style = MaterialTheme.typography.bodySmall,
        color = LocalStatusColors.current.onWaiting,
    )
}

@Composable
private fun TicketCard(
    wifiEnabled: Boolean,
    wifiIp: String?,
    accessCode: String,
    onCopy: () -> Unit,
    onNewCode: () -> Unit,
) {
    ConnectionCard(padding = 20.dp, gap = 16.dp) {
        when {
            !wifiEnabled -> CableHelp()
            wifiIp == null -> NoWifiNotice()
            else -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TicketLabel(stringResource(R.string.wifi_address))
                        Text("$wifiIp:${StreamService.PORT}", style = TicketValue)
                    }
                    FilledIconButton(onClick = onCopy, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_content_copy),
                            contentDescription = stringResource(R.string.wifi_copy),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                DashedDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TicketLabel(stringResource(R.string.wifi_code))
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                accessCode,
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = TicketValue.copy(letterSpacing = 0.08.em),
                            )
                        }
                    }
                    TextButton(onClick = onNewCode) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.wifi_new_code))
                    }
                }
                Text(
                    stringResource(R.string.wifi_unencrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Versión horizontal: solo lectura. Cambiar de modo o de código se hace en vertical. */
@Composable
private fun CompactConnectionCard(wifiEnabled: Boolean, wifiIp: String?, accessCode: String) {
    ConnectionCard(padding = 16.dp, gap = 8.dp) {
        Text(
            stringResource(if (wifiEnabled) R.string.mode_wifi else R.string.mode_cable),
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        when {
            !wifiEnabled -> CableHelp()
            wifiIp == null -> NoWifiNotice()
            else -> {
                Text("$wifiIp:${StreamService.PORT}", style = TicketValue.copy(fontSize = 15.sp, lineHeight = 22.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TicketLabel(stringResource(R.string.wifi_code))
                    Text(accessCode, style = TicketValue.copy(fontSize = 15.sp, lineHeight = 22.sp))
                }
            }
        }
    }
}

/** Tarjeta de conexión: borde fino y fondo que se separa del de la pantalla en ambos modos. */
@Composable
private fun ConnectionCard(padding: Dp, gap: Dp, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(gap)) { content() }
    }
}

@Composable
private fun TicketLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = JetBrainsMono,
        fontSize = 11.sp,
        letterSpacing = 0.08.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DashedDivider() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        val dash = 4.dp.toPx()
        drawLine(
            color,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
    }
}

@Composable
private fun CableHelp() {
    val script = "tools/usb-forward.sh"
    val text = stringResource(R.string.cable_help, script)
    val start = text.indexOf(script)
    Text(
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(fontFamily = JetBrainsMono), start, start + script.length)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Condición del entorno, no una falla de la app: ámbar, no rojo. */
@Composable
private fun NoWifiNotice() {
    Text(
        stringResource(R.string.wifi_no_network),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalStatusColors.current.onWaiting,
    )
}

@Composable
private fun TechnicalDetails(status: StreamStatus, expanded: Boolean, onToggle: () -> Unit, fullUrl: String?) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "details")
    Column {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.details_title), fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(24.dp).rotate(rotation))
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow(stringResource(R.string.details_browsers), status.clients.toString())
                StatRow(stringResource(R.string.details_resolution), if (status.width > 0) "${status.width} × ${status.height}" else "—")
                StatRow(stringResource(R.string.details_fps), if (status.running) status.fps.toString() else "—")
                StatRow(stringResource(R.string.details_bitrate), if (status.running) "${status.kbps} kbps" else "—")
                StatRow(
                    stringResource(R.string.details_camera),
                    stringResource(if (status.frontCamera) R.string.details_front else R.string.details_back),
                )
                if (status.restarts > 0) StatRow(stringResource(R.string.details_restarts), status.restarts.toString())
                StatRow(stringResource(R.string.details_port), StreamService.PORT.toString())
                status.error?.detail?.let { StatRow(stringResource(R.string.details_last_error), it) }
                SelectionContainer {
                    Column {
                        Text(stringResource(R.string.details_adb, StreamService.PORT), fontFamily = JetBrainsMono)
                        fullUrl?.let { Text(stringResource(R.string.details_full_url, it), fontFamily = JetBrainsMono) }
                    }
                }
            }
        }
    }
}

/**
 * Vista previa de lo que se está enviando (16:9, sin espejo). Dibuja en ella el puente
 * OpenGL del servicio, no la cámara directamente: así ves exactamente lo mismo que Meet.
 */
@Composable
private fun CameraPreview(modifier: Modifier) {
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
        modifier = modifier,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontFamily = JetBrainsMono)
    }
}

/** Android 13+ ya muestra su propia confirmación al copiar; antes, un Toast. */
private fun copyAddress(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.wifi_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
