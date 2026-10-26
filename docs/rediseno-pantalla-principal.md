# PhoneCam Web — Pantalla principal

Especificación de implementación. La pantalla deja de ser un visor de depuración
y pasa a responder tres preguntas a simple vista: ¿está transmitiendo?, ¿alguien
me está viendo? y ¿cómo me conecto? El cambio central es una **píldora de estado**
derivada de los datos, debajo de la vista previa, con la acción principal pegada
a ella. Todo lo técnico (fps, bitrate, comandos adb) se va a una sección plegable.

Archivos a modificar:

- `app/src/main/java/cl/antony/phonecamweb/MainActivity.kt` (pantalla completa)
- `app/src/main/java/cl/antony/phonecamweb/stream/StreamState.kt` (tipo del error)
- `app/src/main/java/cl/antony/phonecamweb/stream/StreamService.kt` (llamadas a `fail`)
- `app/src/main/java/cl/antony/phonecamweb/ui/theme/Color.kt` y `Theme.kt` (paleta de marca)
- `app/src/main/res/values/strings.xml` (textos)
- `app/src/main/res/drawable/` (íconos nuevos)

Documento hermano: ninguno. La extensión de Chrome no cambia (ver "Fuera de alcance").

Todo el texto de interfaz va en **español neutro** (tú, nunca vos): "Conecta",
"Ciérrala", "ejecuta".

Usuarios: el autor y gente técnica. Términos como "adb" o "puerto" están
permitidos, pero solo dentro de "Detalles técnicos" o en una línea de ayuda; nunca
como contenido principal.

---

## 1. El problema

Hoy `StreamScreen` apila, en una columna con scroll: título, "Transmitiendo" o
"Detenido" en texto plano, la vista previa, el botón de cámara, una tarjeta con
seis métricas (clientes, resolución, fps, bitrate, cámara, reinicios), una tarjeta
fija de USB con el comando `adb forward`, la tarjeta de Wi‑Fi y, al final, el
botón Iniciar/Detener.

Números del diagnóstico (Galaxy S25 Ultra, 412 dp de ancho, ~840 dp útiles en
vertical):

- Transmitiendo, la columna mide **~1.060 dp**. El botón **Detener queda ~220 dp
  por debajo del borde**: para detener hay que hacer scroll.
- El estado se comunica solo con texto. Lo que el usuario quiere saber ("¿me están
  viendo?") está en la fila "Clientes conectados", la primera de una tabla de seis.
- La dirección Wi‑Fi `ws://192.168.100.100:5000/?code=ABCD-EFGH` son 41
  caracteres monoespaciados (**~344 dp**) en un ancho útil de **~332 dp**: se
  parte en dos líneas y copiarla a mano es propenso a errores.
- La tarjeta de USB ocupa **~150 dp siempre**, aunque se use Wi‑Fi.
- En horizontal (teléfono en un soporte), la vista previa a ancho completo mide
  **851 × 479 dp** en una pantalla de **~340 dp** de alto: no cabe ni la vista
  previa sola.
- Los errores se muestran como texto rojo con el mensaje de la excepción
  (`"No se pudo crear el encoder: …"`), sin decir qué hacer.

---

## 2. Precondiciones

### 2.1 El error deja de ser un `String` — **bloqueante**

La pantalla necesita saber **qué tipo** de error ocurrió para mostrar un título
amigable y una ayuda, y mandar el texto técnico a "Detalles técnicos". Hoy
`StreamStatus.error` es un `String` armado con el mensaje de la excepción.

Si no se resuelve: la UI tendría que interpretar el texto en español con
`contains(...)`, que se rompe con el primer cambio de redacción.

En `stream/StreamState.kt`:

```kotlin
enum class StreamErrorKind(val fatal: Boolean) {
    CAMERA(fatal = true),
    ENCODER(fatal = true),
    PORT(fatal = true),
    /** El cambio de cámara falló pero la transmisión sigue con la cámara anterior. */
    CAMERA_SWITCH(fatal = false),
}

data class StreamError(val kind: StreamErrorKind, val detail: String?)

data class StreamStatus(
    // … campos actuales sin cambios …
    val error: StreamError? = null,   // antes: String?
    // …
)
```

En `StreamService.kt`, `fail` cambia de firma y todas las llamadas se mapean así
(los números de línea son los actuales):

```kotlin
private fun fail(kind: StreamErrorKind, detail: String?) {
    Log.e(TAG, "$kind: $detail")
    StreamState.update { it.copy(error = StreamError(kind, detail)) }
}
```

| Línea | Hoy | Nuevo | ¿Detiene el servicio? |
| --- | --- | --- | --- |
| 71 | "No se pudo abrir el puerto…" | `PORT` | Sí (ya tiene `stopSelf`) |
| 127 | "No se pudo reabrir el puerto…" | `PORT` | Sí (ya tiene `stopSelf`) |
| 141 | "No se pudo abrir la cámara…" | `CAMERA` | Sí (ya tiene `stopSelf`) |
| 179 | "Este teléfono no tiene esa cámara" | `CAMERA_SWITCH` | No |
| 186 | "No se pudo cambiar de cámara…" | ver 2.3 | No, salvo que falle el rescate |
| 199 | "El encoder falló N veces…" | `ENCODER` | Sí (ya tiene `stopSelf`) |
| 211 | "No se pudo reiniciar la cámara…" | `CAMERA` | Sí (ya tiene `stopSelf`) |
| 235 | "No se pudo crear el encoder…" | `ENCODER` | **Agregar `stopSelf()`** |
| 244 | "No se pudo iniciar OpenGL…" | `ENCODER` | **Agregar `stopSelf()`** |

`detail` lleva el texto técnico que hoy va en el mensaje (por ejemplo
`"No se pudo crear el encoder: ${e.message}"`): se sigue viendo, pero en
"Detalles técnicos".

### 2.2 Todo error fatal detiene el servicio — **bloqueante**

Hoy las líneas 235 y 244 marcan el error pero dejan `running = true` sin video.
Con la píldora nueva eso se vería como **"Iniciando…" para siempre**. Regla: si
`kind.fatal`, el servicio llama `stopSelf()`; `onDestroy` ya hace
`StreamState.reset(keepError = true)`, así que la pantalla queda en
`running = false` con el error visible.

`reset` conserva solo errores fatales, para que un aviso de cambio de cámara no
aparezca como error después de un "Detener" normal:

```kotlin
fun reset(keepError: Boolean = false) {
    _status.update { StreamStatus(error = if (keepError) it.error?.takeIf { e -> e.kind.fatal } else null) }
}
```

### 2.3 El cambio de cámara fallido no deja la transmisión muerta

`bindCamera()` llama `provider.unbindAll()` antes de `bindToLifecycle`. Si
`bindToLifecycle` lanza una excepción (línea 186), hoy no queda ninguna cámara
conectada y el video se corta sin aviso claro. Nuevo comportamiento:

```kotlin
try {
    bindCamera()
} catch (e: Exception) {
    useFrontCamera = !useFrontCamera          // volver a la cámara anterior
    try {
        bindCamera()
        fail(StreamErrorKind.CAMERA_SWITCH, "No se pudo cambiar de cámara: ${e.message}")
    } catch (e2: Exception) {
        fail(StreamErrorKind.CAMERA, "No se pudo recuperar la cámara: ${e2.message}")
        stopSelf()
    }
}
```

Y al final de `bindCamera()`, si quedó bien, se limpia el aviso no fatal:

```kotlin
StreamState.update {
    it.copy(frontCamera = front, error = it.error?.takeIf { e -> e.kind.fatal })
}
```

---

## 3. Estado y datos derivados

### 3.1 Estado de la pantalla (en `StreamScreen`)

```kotlin
val status by StreamState.status.collectAsState()
var permissionDenied by rememberSaveable { mutableStateOf(false) }
var wifiEnabled by rememberSaveable { mutableStateOf(StreamSettings.wifiEnabled(context)) }
var accessCode by rememberSaveable { mutableStateOf(StreamSettings.accessCode(context)) }
var detailsExpanded by rememberSaveable { mutableStateOf(false) }
var confirmNewCode by rememberSaveable { mutableStateOf(false) }
val wifiIp = remember(status.running, wifiEnabled) { NetworkUtils.localIpv4() }
val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
```

`wifiEnabled` y `accessCode` suben desde `WifiCard` a `StreamScreen` porque ahora
los leen dos layouts (vertical y horizontal). `rememberSaveable` porque la
actividad se recrea al girar.

- `permissionDenied`: se pone en `true` cuando el launcher devuelve `CAMERA = false`;
  se pone en `false` al tocar "Reintentar" y cuando el servicio arranca.
- `confirmNewCode`: se pone en `true` al tocar "Nuevo código" con
  `status.clients > 0`; se limpia al cerrar el diálogo.

### 3.2 Estado visible derivado

Un solo valor decide la píldora, el banner y la acción principal. Calcúlalo con
una función pura (fácil de probar):

```kotlin
sealed interface ScreenState {
    data object Off : ScreenState
    data object Starting : ScreenState
    data object Waiting : ScreenState
    data class Live(val browsers: Int) : ScreenState
    data class Failed(val kind: FailureKind) : ScreenState
}

enum class FailureKind { PERMISSION, CAMERA, ENCODER, PORT }

fun screenState(status: StreamStatus, permissionDenied: Boolean): ScreenState = when {
    status.running && status.width == 0 -> ScreenState.Starting
    status.running && status.clients == 0 -> ScreenState.Waiting
    status.running -> ScreenState.Live(status.clients)
    permissionDenied -> ScreenState.Failed(FailureKind.PERMISSION)
    status.error?.kind?.fatal == true -> ScreenState.Failed(FailureKind.valueOf(status.error.kind.name))
    else -> ScreenState.Off
}
```

`PERMISSION` vive solo en la UI (el servicio nunca llega a arrancar), por eso
`FailureKind` es un enum aparte de `StreamErrorKind`.

El aviso no fatal se lee aparte: `val switchFailed = status.running &&
status.error?.kind == StreamErrorKind.CAMERA_SWITCH`.

### 3.3 Quién lee qué

| Consumidor | Lee |
| --- | --- |
| Píldora / banner | `screenState(...)` |
| Acción principal | `screenState(...)` |
| Aviso "No se pudo cambiar de cámara" | `switchFailed` |
| Vista previa (SurfaceView o marcador) | `status.running` |
| Tarjeta de conexión | `wifiEnabled`, `wifiIp`, `accessCode` |
| Detalles técnicos | `status` completo, incluido `status.error?.detail` |

---

## 4. Estructura y render

### 4.1 Vertical (anatomía)

```
┌──────────────────────────────────────────┐  padding: 16 dp lados, 12 dp arriba
│ PhoneCam Web                             │  titleLarge
│                                          │  16 dp
│ ┌──────────────────────────────────────┐ │
│ │                                      │ │  Vista previa 16:9, esquinas 16 dp
│ │           (cámara o marcador)        │ │  ancho completo (~380 × 214 dp)
│ │                                      │ │
│ └──────────────────────────────────────┘ │
│                                          │  16 dp
│ ( ● En vivo · 1 navegador )              │  Píldora  ─ o ─  Banner de error
│                                          │  12 dp
│ ┌────────────────────────────┐  ┌──────┐ │
│ │          Detener           │  │  ⟲   │ │  56 dp de alto; gap 12 dp
│ └────────────────────────────┘  └──────┘ │  (detenido: "Iniciar cámara" a ancho completo)
│                                          │  16 dp
│ ┌ Conexión ─────────── [Cable | Wi‑Fi] ┐ │  Card, padding 16 dp, gap 12 dp
│ │ Dirección                            │ │
│ │ 192.168.1.34:5000                    │ │
│ │ Código                               │ │
│ │ ABCD-EFGH                            │ │
│ │ [⧉ Copiar dirección] [↻ Nuevo código]│ │
│ │ El video viaja sin cifrar: usa …     │ │
│ └──────────────────────────────────────┘ │
│                                          │  8 dp
│ Detalles técnicos                     ⌄  │  fila de 48 dp, plegada por defecto
└──────────────────────────────────────────┘
```

Alto estimado transmitiendo por Wi‑Fi: **~740 dp**. Cabe en ~840 dp sin scroll.
Se mantiene `verticalScroll` en la columna para pantallas chicas y para cuando
"Detalles técnicos" está abierto.

### 4.2 Horizontal (anatomía)

```
┌─────────────────────────────────────────────────────────────────────┐
│ ┌───────────────────────────────────────────┐  PhoneCam Web         │
│ │                                           │  ( ● Esperando … )    │
│ │        Vista previa 16:9                  │  [ Detener ] [ ⟲ ]    │
│ │        alto completo (~600 × 340 dp)      │  ┌ Wi‑Fi ─────────┐   │
│ │                                           │  │ 192.168.1.34:… │   │
│ │                                           │  │ Código ABCD-…  │   │
│ └───────────────────────────────────────────┘  └────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

- `Row` con padding 16 dp y gap 16 dp.
- Vista previa: `Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)`.
  El `matchHeightConstraintsFirst` es lo que hace que el alto mande; sin él, toma
  el ancho y se sale por abajo.
- Columna derecha: `weight(1f)`, `verticalScroll`, gap 12 dp. Contiene título,
  píldora o banner, acción principal y tarjeta de conexión **compacta**.
- La tarjeta compacta no tiene selector Cable/Wi‑Fi ni botones: muestra
  "Wi‑Fi" + dirección + "Código ABCD-EFGH", o "Cable" + la línea de ayuda del
  cable. Cambiar de modo se hace en vertical.
- No hay "Detalles técnicos" en horizontal.

### 4.3 Vista previa

| Estado | Contenido |
| --- | --- |
| `!status.running` | `Box` con fondo `surfaceContainerHighest`, ícono `ic_videocam_off` 40 dp en `onSurfaceVariant` y texto "Cámara apagada" (`bodyMedium`) debajo, 8 dp de gap, centrado |
| `status.running` | `CameraPreview()` actual (SurfaceView), con fondo negro mientras llega el primer cuadro |

Ambos con `clip(RoundedCornerShape(16.dp))`.

### 4.4 Píldora de estado

`Surface(shape = CircleShape)`, alto 32 dp, padding horizontal 12 dp, punto de
8 dp + gap 8 dp + texto `labelLarge`.

| Estado | Texto | Fondo (claro / oscuro) | Texto (claro / oscuro) | Punto (claro / oscuro) |
| --- | --- | --- | --- | --- |
| `Off` | Apagada | `surfaceContainerHighest` | `onSurfaceVariant` | `#888780` / `#888780` |
| `Starting` | Iniciando… | `surfaceContainerHighest` | `onSurfaceVariant` | `onSurfaceVariant` |
| `Waiting` | Esperando conexión | `#FAEEDA` / `#633806` | `#633806` / `#FAC775` | `#BA7517` / `#EF9F27` |
| `Live(n)` | En vivo · n navegador(es) | `primaryContainer` | `onPrimaryContainer` | `#1D9E75` / `#5DCAA5` |

Los colores fijos (ámbar y puntos) van en `Color.kt` como `StatusColors` (ver 4.9),
no sueltos en `MainActivity.kt`.

### 4.5 Banner de error (estado `Failed`)

Ocupa el lugar de la píldora. `Surface(color = errorContainer, shape =
RoundedCornerShape(16.dp))`, padding 16 dp, `Row` con ícono `ic_error` 24 dp
(`onErrorContainer`) + gap 12 dp + `Column` (título `titleSmall`, ayuda
`bodyMedium`, ambos `onErrorContainer`).

| `FailureKind` | Título | Ayuda | Acción extra |
| --- | --- | --- | --- |
| `PERMISSION` | Falta el permiso de cámara. | Sin él, la app no puede transmitir. | `TextButton` "Abrir ajustes" dentro del banner |
| `CAMERA` | No se pudo abrir la cámara. | Puede estar en uso por otra app. Ciérrala y reintenta. | — |
| `ENCODER` | El video dejó de funcionar. | Reintenta. Si se repite, reinicia el teléfono. | — |
| `PORT` | No se pudo abrir la conexión. | Otra app está usando el puerto 5000. Ciérrala y reintenta. | — |

"Abrir ajustes" lanza:

```kotlin
Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
```

Aviso no fatal (`switchFailed`): una línea "No se pudo cambiar de cámara." en
`bodySmall`, color del texto ámbar de `Waiting`, justo debajo de la acción
principal. No usa `errorContainer`: la transmisión sigue funcionando.

### 4.6 Acción principal

| Estado | Contenido |
| --- | --- |
| `Off` | `Button` "Iniciar cámara", ancho completo, alto 56 dp |
| `Failed(*)` | `Button` "Reintentar", ancho completo, alto 56 dp (misma acción que Iniciar) |
| `Starting`, `Waiting`, `Live` | `Row`: `OutlinedButton` "Detener" (`weight(1f)`, alto 56 dp) + gap 12 dp + `FilledTonalIconButton` 56 × 56 dp con `ic_cameraswitch` 24 dp |

El botón de cámara usa `contentDescription` "Usar cámara frontal" o "Usar cámara
trasera" según `status.frontCamera`, y está deshabilitado mientras
`status.width == 0` (no hay cámara conectada todavía).

### 4.7 Tarjeta de conexión (vertical)

`Card` con color `surfaceContainerLow`, padding 16 dp, gap 12 dp.

Encabezado: `Row` con "Conexión" (`titleMedium`) a la izquierda y
`SingleChoiceSegmentedButtonRow` con dos `SegmentedButton`: "Cable" y "Wi‑Fi", a
la derecha. Reemplaza al `Switch`.

| Modo | Contenido |
| --- | --- |
| Cable | Una línea `bodyMedium`: "Conecta el cable y ejecuta tools/usb-forward.sh en la Mac." (`tools/usb-forward.sh` en monoespaciada vía `AnnotatedString`) |
| Wi‑Fi, con `wifiIp` | Etiqueta "Dirección" (`labelMedium`, `onSurfaceVariant`) + `"$wifiIp:5000"` (monoespaciada, 15 sp). Etiqueta "Código" + `accessCode` (monoespaciada, 15 sp). `Row` con dos `OutlinedButton` de alto 40 dp e ícono 18 dp: "Copiar dirección" (`ic_content_copy`) y "Nuevo código" (`ic_refresh`). Al final, en `bodySmall` `onSurfaceVariant`: "El video viaja sin cifrar: usa Wi‑Fi solo en redes de confianza." |
| Wi‑Fi, `wifiIp == null` | Solo la línea "Conecta el teléfono a la misma red Wi‑Fi que la Mac." en `bodyMedium`, color del texto ámbar de `Waiting`. No es un error rojo: es una condición del entorno, no una falla de la app. |

La dirección y el código se muestran también con la cámara apagada, para poder
prepararlos en la Mac antes de iniciar.

### 4.8 Detalles técnicos (solo vertical)

Fila clickeable de 48 dp: "Detalles técnicos" (`titleSmall`) + `ic_expand_more`
24 dp que rota 180° al abrir. Contenido con `AnimatedVisibility`, reusa
`StatRow`:

| Etiqueta | Valor |
| --- | --- |
| Navegadores | `status.clients` |
| Resolución | `"${width} × ${height}"` o "—" |
| Cuadros por segundo | `status.fps` o "—" si no está corriendo |
| Bitrate | `"${kbps} kbps"` o "—" |
| Cámara | "Frontal" / "Trasera" |
| Reinicios automáticos | `status.restarts` (solo si > 0) |
| Puerto | 5000 |
| Último error | `status.error?.detail` (solo si existe) |

Debajo, en monoespaciada y dentro de `SelectionContainer`:

- "Cable manual: adb forward tcp:5000 tcp:5000"
- Si Wi‑Fi con IP: "URL completa: ws://IP:5000/?code=CODE"

### 4.9 Tema de marca

En `Color.kt`, borrar los colores de la plantilla y definir la paleta teal.
`dynamicColor` pasa a `false` y se quita el parámetro (el usuario eligió color de
marca propio).

| Rol | Claro | Oscuro |
| --- | --- | --- |
| primary | `0xFF0F6E56` | `0xFF5DCAA5` |
| onPrimary | `0xFFFFFFFF` | `0xFF04342C` |
| primaryContainer | `0xFFE1F5EE` | `0xFF085041` |
| onPrimaryContainer | `0xFF085041` | `0xFF9FE1CB` |
| secondaryContainer | `0xFFCDE8DF` | `0xFF334B44` |
| onSecondaryContainer | `0xFF07201A` | `0xFFCDE8DF` |
| background, surface | `0xFFF7FAF8` | `0xFF0F1513` |
| surfaceContainerLow | `0xFFF1F5F3` | `0xFF171D1B` |
| surfaceContainer | `0xFFEBF0EE` | `0xFF1B211F` |
| surfaceContainerHighest | `0xFFE0E6E3` | `0xFF303634` |
| onSurface, onBackground | `0xFF181D1B` | `0xFFDEE4E1` |
| onSurfaceVariant | `0xFF3F4945` | `0xFFBFC9C4` |
| outline | `0xFF6F7975` | `0xFF89938F` |
| outlineVariant | `0xFFBFC9C4` | `0xFF3F4945` |

Las superficies se definen explícitamente porque las de `lightColorScheme()` por
defecto tienen tinte morado. Los roles de error quedan con los valores por defecto
de Material 3.

Colores de estado, fuera del `ColorScheme`:

```kotlin
@Immutable
data class StatusColors(
    val waitingContainer: Color, val onWaiting: Color, val waitingDot: Color,
    val liveDot: Color, val offDot: Color,
)

val LightStatusColors = StatusColors(Color(0xFFFAEEDA), Color(0xFF633806), Color(0xFFBA7517), Color(0xFF1D9E75), Color(0xFF888780))
val DarkStatusColors  = StatusColors(Color(0xFF633806), Color(0xFFFAC775), Color(0xFFEF9F27), Color(0xFF5DCAA5), Color(0xFF888780))

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
```

`PhoneCamWebTheme` provee `LocalStatusColors` según `darkTheme`.

### 4.10 Íconos

`material3` 1.4 ya no trae `material-icons-core` y no se agrega una dependencia
por seis íconos. Van como VectorDrawable en `res/drawable/`, convertidos desde
Material Symbols Outlined, peso 400, 24 px (paquete npm `@material-symbols/svg-400`,
carpeta `outlined/`), y se usan con `painterResource`:

`ic_videocam_off`, `ic_cameraswitch`, `ic_content_copy`, `ic_refresh`,
`ic_error`, `ic_expand_more`.

No agregues `android:tint` a los vectores: el color lo pone el `Icon` de Compose
con su `tint`.

---

## 5. Reglas de comportamiento

1. **El orden no cambia entre estados.** Vista previa → píldora/banner → acción →
   conexión → detalles. Así el pulgar encuentra "Detener" siempre en el mismo
   lugar.
2. **La acción principal queda siempre dentro de la primera pantalla**, sin
   scroll, en vertical y en horizontal. Es el motivo del rediseño.
3. **"Iniciar cámara" y "Reintentar"** hacen lo mismo: `permissionDenied = false`;
   si hay permiso → `StreamService.start`; si no → `launcher.launch(permissions)`.
4. **Cambiar Cable/Wi‑Fi**: actualiza `wifiEnabled`, llama
   `StreamSettings.setWifiEnabled` y `StreamService.reloadNetwork` (que no hace
   nada si está detenido). Sin confirmación: es una elección visible y
   reversible. En modo Wi‑Fi el cable sigue funcionando (el servidor escucha en
   todas las interfaces y el loopback no pide código); no hace falta decirlo en
   pantalla.
5. **"Copiar dirección"** copia `ws://$wifiIp:5000/?code=$accessCode` con
   `ClipboardManager` del sistema (`context.getSystemService`). Muestra un `Toast`
   "Dirección copiada" solo si `Build.VERSION.SDK_INT < 33`: desde Android 13 el
   sistema ya muestra su propia confirmación y dos avisos se ven torpes. Se copia
   la URL completa porque el popup de la extensión espera ese formato.
6. **"Nuevo código"**: si `status.clients > 0`, abre un `AlertDialog` (ver
   textos); si no, regenera directo. Regenerar = `accessCode =
   StreamSettings.regenerateCode(context)` + `StreamService.reloadNetwork(context)`.
   La confirmación existe porque desconecta a quien esté mirando por Wi‑Fi.
7. **Plurales**: "En vivo · 1 navegador", "En vivo · 2 navegadores". El 0 nunca
   llega a la píldora en vivo (es `Waiting`).
8. **Horizontal no tiene controles de configuración** (selector, nuevo código,
   detalles): el teléfono está en un soporte y la pantalla sirve para mirar.
9. **Al girar el teléfono** la actividad se recrea; el servicio y los navegadores
   conectados no se enteran. El estado de la UI sobrevive por `rememberSaveable`.
10. **Movimiento**: solo la rotación de la flecha de detalles y la expansión
    (`AnimatedVisibility`, duración por defecto). La píldora cambia sin animación.
11. **Accesibilidad**: la píldora y el banner se agrupan con
    `Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }`,
    para que TalkBack anuncie "Esperando conexión" → "En vivo · 1 navegador".

---

## 6. Lo que se borra

En `MainActivity.kt`:

- `StatsCard` completo (su contenido pasa a "Detalles técnicos"). `StatRow` se
  conserva.
- `WifiCard` completo (su estado sube a `StreamScreen`; su UI se reparte entre la
  tarjeta de conexión y el diálogo).
- El `Card` "Conexión por USB" con el comando `adb forward` y `ws://127.0.0.1`
  (el comando pasa a detalles; la ayuda del cable menciona `usb-forward.sh`).
- `Text(if (status.running) "Transmitiendo" else "Detenido")`.
- `status.error?.let { Text(it, color = …error) }` y el `Text` de
  `permissionDenied`: los reemplaza el banner.
- El botón inferior "Iniciar"/"Detener" y el `OutlinedButton` "Usar cámara
  frontal/trasera": los reemplaza la acción principal.
- Imports que quedan huérfanos: `androidx.compose.material3.Switch` y,
  según cómo quede, `mutableStateOf`/`remember` (pasan a `rememberSaveable`).

En `ui/theme/Color.kt`: `Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`,
`PurpleGrey40`, `Pink40`.

En `ui/theme/Theme.kt`: el parámetro `dynamicColor`, la rama
`dynamicDarkColorScheme`/`dynamicLightColorScheme` y sus imports
(`android.app.Activity`, `android.os.Build`, `dynamicDarkColorScheme`,
`dynamicLightColorScheme`, `LocalContext`).

En `stream/StreamService.kt`: la firma `fail(message: String)`.

---

## 7. Textos

Todos en `res/values/strings.xml`, nada en duro en Kotlin.

| Clave | Texto |
| --- | --- |
| `camera_off` | Cámara apagada |
| `status_off` | Apagada |
| `status_starting` | Iniciando… |
| `status_waiting` | Esperando conexión |
| `action_start` | Iniciar cámara |
| `action_retry` | Reintentar |
| `action_stop` | Detener |
| `cd_use_front` | Usar cámara frontal |
| `cd_use_back` | Usar cámara trasera |
| `connection_title` | Conexión |
| `mode_cable` | Cable |
| `mode_wifi` | Wi‑Fi |
| `cable_help` | Conecta el cable y ejecuta %1$s en la Mac. |
| `wifi_address` | Dirección |
| `wifi_code` | Código |
| `wifi_copy` | Copiar dirección |
| `wifi_copied` | Dirección copiada |
| `wifi_new_code` | Nuevo código |
| `wifi_unencrypted` | El video viaja sin cifrar: usa Wi‑Fi solo en redes de confianza. |
| `wifi_no_network` | Conecta el teléfono a la misma red Wi‑Fi que la Mac. |
| `new_code_title` | ¿Generar un código nuevo? |
| `new_code_body` | Los navegadores conectados por Wi‑Fi se desconectarán hasta que uses el código nuevo. |
| `new_code_confirm` | Generar |
| `cancel` | Cancelar |
| `switch_failed` | No se pudo cambiar de cámara. |
| `err_permission_title` | Falta el permiso de cámara. |
| `err_permission_hint` | Sin él, la app no puede transmitir. |
| `err_open_settings` | Abrir ajustes |
| `err_camera_title` | No se pudo abrir la cámara. |
| `err_camera_hint` | Puede estar en uso por otra app. Ciérrala y reintenta. |
| `err_encoder_title` | El video dejó de funcionar. |
| `err_encoder_hint` | Reintenta. Si se repite, reinicia el teléfono. |
| `err_port_title` | No se pudo abrir la conexión. |
| `err_port_hint` | Otra app está usando el puerto %1$d. Ciérrala y reintenta. |
| `details_title` | Detalles técnicos |
| `details_browsers` | Navegadores |
| `details_resolution` | Resolución |
| `details_fps` | Cuadros por segundo |
| `details_bitrate` | Bitrate |
| `details_camera` | Cámara |
| `details_front` | Frontal |
| `details_back` | Trasera |
| `details_restarts` | Reinicios automáticos |
| `details_port` | Puerto |
| `details_last_error` | Último error |
| `details_adb` | Cable manual: adb forward tcp:%1$d tcp:%1$d |
| `details_full_url` | URL completa: %1$s |

Plural (usar `pluralStringResource`; español tiene `one`, `many` y `other`, y
`many` se define igual que `other` para números grandes):

```xml
<plurals name="status_live">
    <item quantity="one">En vivo · %d navegador</item>
    <item quantity="many">En vivo · %d navegadores</item>
    <item quantity="other">En vivo · %d navegadores</item>
</plurals>
```

Se reusa: `app_name` ("PhoneCam Web") para el título; `StreamService.PORT` para
el puerto (no escribir `5000` en duro).

---

## 8. Criterios de aceptación

1. En vertical, con la cámara apagada y modo Wi‑Fi, se ven: el marcador "Cámara
   apagada", la píldora gris "Apagada", el botón "Iniciar cámara", y la tarjeta
   con dirección `IP:5000` y código en líneas separadas. Fuera de "Detalles
   técnicos" no aparece ninguno de estos textos: `ws://`, `adb`, `kbps`, `fps`.
2. Al tocar "Iniciar cámara", la píldora pasa por "Iniciando…" y luego a
   "Esperando conexión" en ámbar. El botón "Detener" se ve sin hacer scroll en el
   S25 Ultra, en vertical y en horizontal.
3. Con Meet abierto en la Mac, la píldora dice "En vivo · 1 navegador"; con dos
   pestañas, "En vivo · 2 navegadores"; al cerrar ambas vuelve a "Esperando
   conexión".
4. "Copiar dirección" y pegar en el popup de la extensión conecta sin editar
   nada.
5. Con un navegador conectado, "Nuevo código" pide confirmación. "Cancelar" deja
   el código igual y Meet sigue viendo video. "Generar" muestra el código nuevo y
   el navegador ve el error de código incorrecto hasta que se actualiza.
6. Con el modo Wi‑Fi y el teléfono sin Wi‑Fi, la tarjeta muestra solo "Conecta el
   teléfono a la misma red Wi‑Fi que la Mac." en ámbar, sin dirección ni código.
7. Al negar el permiso de cámara aparece el banner "Falta el permiso de cámara."
   con "Abrir ajustes" y el botón "Reintentar". Con la cámara ocupada por otra app,
   aparece "No se pudo abrir la cámara." y el texto de la excepción aparece solo
   en "Detalles técnicos" → "Último error".
8. En horizontal, la vista previa ocupa todo el alto en 16:9 y los controles
   quedan a la derecha sin selector Cable/Wi‑Fi ni detalles. Girar el teléfono
   mientras Meet está conectado no corta el video ni cambia el contador.
9. "Detalles técnicos" está plegado al abrir la app; si se abre, sigue abierto
   después de girar a horizontal y volver a vertical.
10. En modo oscuro y en modo claro la app usa la paleta teal; no aparece ningún
    morado (botones, segmentado, superficies), incluso con el fondo de pantalla
    del teléfono en otro color.

---

## Fuera de alcance

- **Pantalla de ajustes (⚙️).** Apareció en una vista previa pero no tiene
  contenido todavía. No agregar el ícono hasta que haya algo que configurar.
- **Popup de la extensión con campos separados para IP y código.** "Copiar
  dirección" copia la URL completa, así que el popup actual sigue funcionando
  sin cambios.
- **Rediseño de la notificación** del servicio en primer plano.
- **Actualizar la IP en vivo** si el teléfono cambia de red con la app abierta.
  Hoy se recalcula al iniciar/detener y al cambiar de modo; eso basta.
- **Fondo de ventana en modo oscuro** (`themes.xml` hereda de `Light`): puede
  verse un destello blanco al abrir la app.
