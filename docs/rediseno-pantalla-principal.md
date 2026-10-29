# PhoneCam Web — Pantalla principal

Especificación de implementación. La pantalla deja de ser un visor de depuración
y pasa a responder tres preguntas a simple vista: ¿está transmitiendo?, ¿alguien
me está viendo? y ¿cómo me conecto? El cambio central es una **píldora de estado**
derivada de los datos, debajo de la vista previa, con la acción principal pegada
a ella. Todo lo técnico (fps, bitrate, comandos adb) se va a una sección plegable.

Identidad visual: dirección **D · Directo** del lienzo de branding (base grafito,
cobalto para las acciones y la luz roja de transmisión solo para "En vivo").

Archivos a modificar:

- `app/src/main/java/cl/antony/phonecamweb/MainActivity.kt` (pantalla completa)
- `app/src/main/java/cl/antony/phonecamweb/stream/StreamState.kt` (tipo del error)
- `app/src/main/java/cl/antony/phonecamweb/stream/StreamService.kt` (llamadas a `fail`)
- `app/src/main/java/cl/antony/phonecamweb/ui/theme/Color.kt`, `Theme.kt` y `Type.kt` (paleta y fuentes de marca)
- `app/src/main/res/values/strings.xml` (textos)
- `app/src/main/res/drawable/` (íconos nuevos y capas del ícono de la app)
- `app/src/main/res/font/` (Space Grotesk y JetBrains Mono)
- `app/src/main/res/mipmap-*/` (ícono de la app)
- `extension/manifest.json`, `extension/popup.html` y `extension/icons/` (ícono y colores de la extensión)

Documento hermano: ninguno. La lógica de la extensión de Chrome no cambia; solo su
ícono y sus colores (ver 4.12).

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
┌──────────────────────────────────────────┐
│ PhoneCam [● Web]           [Cable|Wi‑Fi] │  Encabezado: padding 16 dp lados, 12 dp arriba y abajo
├──────────────────────────────────────────┤
│ (● En vivo · 1 navegador)                │  Vista previa 16:9 a todo el ancho, sin esquinas
│                                          │  (~412 × 232 dp). Píldora encima, a 12 dp del
│           (cámara o marcador)            │  borde superior y del izquierdo
│                                          │
├──────────────────────────────────────────┤
│                                          │  16 dp. Desde aquí, padding 16 dp a los lados
│ [ Banner de error, solo en Failed ]      │  + 12 dp hasta la acción
│ ┌────────────────────────────┐  ┌──────┐ │
│ │          Detener           │  │  ⟲   │ │  56 dp de alto; gap 12 dp
│ └────────────────────────────┘  └──────┘ │  (detenido: "Iniciar cámara" a ancho completo)
│                                          │  16 dp
│ ┌──────────────────────────────────────┐ │  Tarjeta "ticket": padding 20 dp, gap 16 dp
│ │ DIRECCIÓN                       [⧉]  │ │
│ │ 192.168.1.34:5000                    │ │
│ │ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ │ │
│ │ CÓDIGO                               │ │
│ │ [ABCD-EFGH]          ↻ Nuevo código  │ │
│ │ El video viaja sin cifrar: usa …     │ │
│ └──────────────────────────────────────┘ │
│                                          │  8 dp
│ Detalles técnicos                     ⌄  │  fila de 48 dp, plegada por defecto
└──────────────────────────────────────────┘
```

Alto estimado transmitiendo por Wi‑Fi: **~670 dp**. Cabe en ~840 dp sin scroll.
Se mantiene `verticalScroll` en la columna para pantallas chicas y para cuando
"Detalles técnicos" está abierto.

Encabezado: a la izquierda la marca (ver 4.11): "PhoneCam" y una etiqueta
`primaryContainer` (esquinas 8 dp, padding 7 × 1 dp) con un punto de 6 dp en
`StatusColors.tally` y "Web". La marca se agrupa con
`Modifier.semantics(mergeDescendants = true) { heading() }`. A la derecha, el
selector Cable/Wi‑Fi (ver 4.7).

### 4.2 Horizontal (anatomía)

```
┌─────────────────────────────────────────────────────────────────────┐
│ ┌───────────────────────────────────────────┐  PhoneCam [● Web]     │
│ │ (● Esperando conexión)                    │  [ Detener ] [ ⟲ ]    │
│ │        Vista previa 16:9                  │  ┌ Wi‑Fi ─────────┐   │
│ │        alto completo (~600 × 340 dp)      │  │ 192.168.1.34:… │   │
│ │                                           │  │ CÓDIGO ABCD-…  │   │
│ └───────────────────────────────────────────┘  └────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

- `Row` con padding 16 dp y gap 16 dp.
- Vista previa: `Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)`,
  esquinas de 12 dp y la píldora encima, igual que en vertical.
  El `matchHeightConstraintsFirst` es lo que hace que el alto mande; sin él, toma
  el ancho y se sale por abajo.
- Columna derecha: `weight(1f)`, `verticalScroll`, gap 12 dp. Contiene la marca
  (sin selector), el banner si hay error, la acción principal y la tarjeta de
  conexión **compacta**.
- La tarjeta compacta usa el estilo del ticket (4.7) pero no tiene selector ni
  botones: muestra "Wi‑Fi" + dirección + "CÓDIGO ABCD-EFGH", "Wi‑Fi" + el aviso
  ámbar si no hay red, o "Cable" + la línea de ayuda del cable. Cambiar de modo se
  hace en vertical.
- No hay "Detalles técnicos" en horizontal.

### 4.3 Vista previa

| Estado | Contenido |
| --- | --- |
| `!status.running` | `Box` con fondo `surfaceContainerHighest`, ícono `ic_videocam_off` 40 dp en `onSurfaceVariant` y texto "Cámara apagada" (`bodyMedium`) debajo, 8 dp de gap, centrado |
| `status.running` | `CameraPreview()` actual (SurfaceView), con fondo negro mientras llega el primer cuadro |

En vertical va a todo el ancho y sin esquinas; en horizontal, con
`clip(RoundedCornerShape(12.dp))`. En todos los estados salvo `Failed`, la píldora
(4.4) se dibuja encima, alineada arriba a la izquierda con 12 dp de margen.

### 4.4 Píldora de estado

`Surface(shape = RoundedCornerShape(8.dp))`, alto 32 dp, padding 10 dp al inicio y
12 dp al final, punto de 8 dp + gap 8 dp + texto Space Grotesk SemiBold 14 sp.
Siempre va sobre la vista previa, así que su fondo es sólido en todos los estados.

| Estado | Texto | Fondo (claro / oscuro) | Texto (claro / oscuro) | Punto (claro / oscuro) |
| --- | --- | --- | --- | --- |
| `Off` | Apagada | `surface` | `onSurfaceVariant` | `#8A9099` / `#8A9099` |
| `Starting` | Iniciando… | `surface` | `onSurfaceVariant` | `onSurfaceVariant` |
| `Waiting` | Esperando conexión | `#FFF1D6` / `#4A3200` | `#6B4500` / `#FFD58A` | `#D98E04` / `#F2B33D` |
| `Live(n)` | En vivo · n navegador(es) | `#1F2328` / `#F2F3F5` | `#FFFFFF` / `#16181B` | `#FF5F61` / `#E5383B` |

La píldora en vivo va invertida respecto del tema (oscura en claro, clara en
oscuro): así el rojo de transmisión no se confunde con el banner de error, que es
un rectángulo rosado con ícono. Los colores fijos van en `Color.kt` como
`StatusColors` (ver 4.9), no sueltos en `MainActivity.kt`.

### 4.5 Banner de error (estado `Failed`)

Va debajo de la vista previa y antes de la acción principal, con 12 dp de
separación; en `Failed` no se dibuja la píldora. `Surface(color = errorContainer,
shape = RoundedCornerShape(16.dp))`, padding 16 dp, `Row` con ícono `ic_error` 24 dp
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

Los tres llevan `RoundedCornerShape(12.dp)` y el texto en Space Grotesk SemiBold
15 sp.

El botón de cámara usa `contentDescription` "Usar cámara frontal" o "Usar cámara
trasera" según `status.frontCamera`, y está deshabilitado mientras
`status.width == 0` (no hay cámara conectada todavía).

### 4.7 Selector de modo y tarjeta "ticket" (vertical)

Selector: `SingleChoiceSegmentedButtonRow` con dos `SegmentedButton`, "Cable" y
"Wi‑Fi", en el encabezado (4.1). Reemplaza al `Switch` de la versión anterior.

Tarjeta: `Card` con color `surfaceContainerLow`, borde de 1 dp en `outlineVariant`,
esquinas de 16 dp, padding 20 dp y gap 16 dp. No tiene título.

Las etiquetas "Dirección" y "Código" van en JetBrains Mono 11 sp, en mayúsculas,
con espaciado de 0.08 em y color `onSurfaceVariant`.

| Modo | Contenido |
| --- | --- |
| Cable | Una línea `bodyMedium`: "Conecta el cable y ejecuta tools/usb-forward.sh en la Mac." (`tools/usb-forward.sh` en JetBrains Mono vía `AnnotatedString`) |
| Wi‑Fi, con `wifiIp` | Fila: etiqueta "Dirección" + `"$wifiIp:5000"` (JetBrains Mono Medium 19 sp) a la izquierda, y `FilledIconButton` de 48 dp, esquinas de 12 dp, con `ic_content_copy` y `contentDescription` "Copiar dirección" a la derecha. Línea punteada de 1 dp en `outlineVariant`. Fila: etiqueta "Código" + el código en un `Surface` `primaryContainer` (esquinas 10 dp, padding 12 × 6 dp, JetBrains Mono Medium 19 sp, espaciado 0.08 em) a la izquierda, y `TextButton` con `ic_refresh` 18 dp y "Nuevo código" a la derecha. Al final, en `bodySmall` `onSurfaceVariant`: "El video viaja sin cifrar: usa Wi‑Fi solo en redes de confianza." |
| Wi‑Fi, `wifiIp == null` | Solo la línea "Conecta el teléfono a la misma red Wi‑Fi que la Mac." en `bodyMedium`, color del texto ámbar de `Waiting`. No es un error rojo: es una condición del entorno, no una falla de la app. |

La dirección y el código se muestran también con la cámara apagada, para poder
prepararlos en la Mac antes de iniciar.

### 4.8 Detalles técnicos (solo vertical)

Fila clickeable de 48 dp: "Detalles técnicos" (Space Grotesk SemiBold 14 sp) +
`ic_expand_more` 24 dp que rota 180° al abrir. Contenido con `AnimatedVisibility`,
reusa `StatRow` (valores en JetBrains Mono):

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

Debajo, en JetBrains Mono y dentro de `SelectionContainer`:

- "Cable manual: adb forward tcp:5000 tcp:5000"
- Si Wi‑Fi con IP: "URL completa: ws://IP:5000/?code=CODE"

### 4.9 Tema de marca

En `Color.kt` va la paleta D. `dynamicColor` sigue en `false` y sin parámetro (el
usuario eligió color de marca propio).

| Rol | Claro | Oscuro |
| --- | --- | --- |
| primary | `0xFF2350D8` | `0xFFB3C5FF` |
| onPrimary | `0xFFFFFFFF` | `0xFF0A2463` |
| primaryContainer | `0xFFE3EAFF` | `0xFF1E3D9E` |
| onPrimaryContainer | `0xFF0E2A7A` | `0xFFDCE4FF` |
| secondaryContainer | `0xFFDCE4F9` | `0xFF35415F` |
| onSecondaryContainer | `0xFF12204A` | `0xFFDCE4F9` |
| background, surface | `0xFFF6F7F8` | `0xFF202327` |
| surfaceContainerLow (tarjeta) | `0xFFFFFFFF` | `0xFF2A2E33` |
| surfaceContainer | `0xFFEEF0F2` | `0xFF2E3238` |
| surfaceContainerHigh (diálogo) | `0xFFE8EAED` | `0xFF33383E` |
| surfaceContainerHighest | `0xFFDEE1E5` | `0xFF3A3F46` |
| onSurface, onBackground | `0xFF16181B` | `0xFFE6E8EB` |
| onSurfaceVariant | `0xFF4A5058` | `0xFFAEB4BB` |
| outline | `0xFF737A83` | `0xFF858C94` |
| outlineVariant | `0xFFD3D7DC` | `0xFF464B53` |

`surfaceContainerLow` es blanco en claro y un tono más claro que el fondo en
oscuro: en los dos modos la tarjeta se ve por encima del fondo. El modo oscuro es
gris grafito, no negro. Las superficies se definen explícitamente porque las de
`lightColorScheme()` por defecto tienen tinte morado. Los roles de error quedan con
los valores por defecto de Material 3.

Colores de estado, fuera del `ColorScheme`:

```kotlin
@Immutable
data class StatusColors(
    val waitingContainer: Color, val onWaiting: Color, val waitingDot: Color,
    val liveContainer: Color, val onLive: Color, val liveDot: Color,
    val offDot: Color,
    /** Punto rojo de la marca, en el encabezado. */
    val tally: Color,
)

val LightStatusColors = StatusColors(
    Color(0xFFFFF1D6), Color(0xFF6B4500), Color(0xFFD98E04),
    Color(0xFF1F2328), Color(0xFFFFFFFF), Color(0xFFFF5F61),
    Color(0xFF8A9099), Color(0xFFE5383B),
)
val DarkStatusColors = StatusColors(
    Color(0xFF4A3200), Color(0xFFFFD58A), Color(0xFFF2B33D),
    Color(0xFFF2F3F5), Color(0xFF16181B), Color(0xFFE5383B),
    Color(0xFF8A9099), Color(0xFFFF5F61),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
```

`PhoneCamWebTheme` provee `LocalStatusColors` según `darkTheme`.

### 4.10 Íconos

Íconos de interfaz: `material3` 1.4 ya no trae `material-icons-core` y no se agrega
una dependencia por seis íconos. Van como VectorDrawable en `res/drawable/`,
convertidos desde Material Symbols Outlined, peso 400 (paquete npm
`@material-symbols/svg-400`, carpeta `outlined/`; el paquete solo trae el tamaño
óptico de 48 px), y se usan con `painterResource`:

`ic_videocam_off`, `ic_cameraswitch`, `ic_content_copy`, `ic_refresh`,
`ic_error`, `ic_expand_more` (este último sale de `keyboard_arrow_down`: el paquete
no trae `expand_more`).

No agregues `android:tint` a los vectores: el color lo pone el `Icon` de Compose
con su `tint`.

Ícono de la app (ícono adaptable, `mipmap-anydpi-v26/ic_launcher.xml` e
`ic_launcher_round.xml`), en la grilla de 108 dp:

| Capa | Contenido |
| --- | --- |
| Fondo (`ic_launcher_background`) | Color sólido `#30353C` |
| Primer plano (`ic_launcher_foreground`) | Teléfono acostado: cuerpo en 24,37 de 60 × 34 con esquinas de 8, en `#E6E8EB`. Pantalla: 33,40 de 47 × 28 con esquinas de 3, en `#2350D8`. Luz roja junto a la cámara: círculo en 28.5,54, radio 2.6, `#FF4D4F`. Persona en la pantalla, en `#DCE4FF`: cabeza en 56.5,51.5, radio 5.5, y hombros desde 45,68 hasta 68,68 con la parte alta en 56.5,59 |
| Monocromo (`ic_launcher_monochrome`) | El teléfono con recortes (pantalla y luz roja) y la persona sólida dentro de la pantalla, en un solo color. Android 13+ lo tiñe en el ícono temático |

Se descartó una ventana cuadrada con una lente centrada: junto con la luz roja en
la esquina, se confundía con el ícono de Instagram.

Android 7.1 y anteriores (minSdk 23) no usan el ícono adaptable: los
`mipmap-*/ic_launcher.png` e `ic_launcher_round.png` se generan desde la misma
geometría (48, 72, 96, 144 y 192 px), en cuadrado redondeado y en círculo.
Reemplazan a los `.webp` de la plantilla.

### 4.11 Tipografía

Fuentes incluidas en `res/font/` desde Google Fonts (licencia OFL):
`space_grotesk_semibold.ttf`, `jetbrains_mono_regular.ttf` y
`jetbrains_mono_medium.ttf` (~293 KB en total). En `Type.kt` se exponen como
`SpaceGrotesk` y `JetBrainsMono`. El resto de la interfaz sigue en Roboto, la
fuente del sistema.

| Uso | Fuente |
| --- | --- |
| Marca del encabezado | Space Grotesk SemiBold 20 sp ("Web": 14 sp) |
| Píldora, acción principal, "Detalles técnicos" | Space Grotesk SemiBold (14, 15 y 14 sp) |
| Etiquetas "Dirección" y "Código" | JetBrains Mono Regular 11 sp, mayúsculas |
| Dirección, código, valores de detalles y comandos | JetBrains Mono (Medium 19 sp en el ticket) |

### 4.12 Extensión de Chrome

- Ícono: la misma geometría que el primer plano de la app sobre el fondo
  `#30353C`, en cuadrado redondeado (radio de 22 % del lado). PNG de 16, 32, 48 y
  128 px en `extension/icons/`; en el de 16 px el teléfono, la luz roja y la persona se
  agrandan un poco para que se lean. `manifest.json` los declara en `icons` y en `action.default_icon`.
- Popup: mismos colores que la app, claro u oscuro según el sistema. El botón
  "Guardar" usa `primary` / `onPrimary`. Punto de estado: `conectado`
  ("Transmitiendo") en el rojo de transmisión, `conectando` y `reconectando` en
  ámbar, `desconectado` en `#8A9099`, y `error` como anillo hueco en el color de
  error de Material 3, para que no dependa solo del color distinguirlo de
  "Transmitiendo".
- Las fuentes no se incluyen en la extensión: usa `system-ui` y `ui-monospace`.

---

## 5. Reglas de comportamiento

1. **El orden no cambia entre estados.** Vista previa (con la píldora encima) →
   banner (solo con error) → acción → conexión → detalles. Así el pulgar encuentra
   "Detener" siempre en el mismo lugar.
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

### 6.1 Al pasar a la identidad D

- En `ui/theme/Color.kt`: la paleta teal y los `StatusColors` anteriores (los
  reemplaza la paleta D).
- En `MainActivity.kt`: el título `titleLarge` (lo reemplaza la marca del
  encabezado), el título "Conexión" de la tarjeta y el selector dentro de ella
  (pasa al encabezado), y los usos de `FontFamily.Monospace` (pasan a
  `JetBrainsMono`).
- En `strings.xml`: `connection_title`.
- `res/drawable-v24/ic_launcher_foreground.xml`, el `ic_launcher_background.xml` de
  la plantilla y los `mipmap-*/*.webp` (ver 4.10).

---

## 7. Textos

Todos en `res/values/strings.xml`, nada en duro en Kotlin.

| Clave | Texto |
| --- | --- |
| `wordmark_main` | PhoneCam (`translatable="false"`) |
| `wordmark_suffix` | Web (`translatable="false"`) |
| `camera_off` | Cámara apagada |
| `status_off` | Apagada |
| `status_starting` | Iniciando… |
| `status_waiting` | Esperando conexión |
| `action_start` | Iniciar cámara |
| `action_retry` | Reintentar |
| `action_stop` | Detener |
| `cd_use_front` | Usar cámara frontal |
| `cd_use_back` | Usar cámara trasera |
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

Se reusa: `StreamService.PORT` para el puerto (no escribir `5000` en duro). La
marca del encabezado usa `wordmark_main` y `wordmark_suffix`, no `app_name`.

---

## 8. Criterios de aceptación

1. En vertical, con la cámara apagada y modo Wi‑Fi, se ven: el marcador "Cámara
   apagada" con la píldora "Apagada" encima, el botón "Iniciar cámara", y la
   tarjeta con dirección `IP:5000` y código en líneas separadas. Fuera de
   "Detalles técnicos" no aparece ninguno de estos textos: `ws://`, `adb`, `kbps`,
   `fps`.
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
8. En horizontal, la vista previa ocupa todo el alto en 16:9 con la píldora
   encima, y los controles quedan a la derecha sin selector Cable/Wi‑Fi ni
   detalles. Girar el teléfono mientras Meet está conectado no corta el video ni
   cambia el contador.
9. "Detalles técnicos" está plegado al abrir la app; si se abre, sigue abierto
   después de girar a horizontal y volver a vertical.
10. En modo oscuro y en modo claro la app usa la paleta D (grafito, cobalto y el
    rojo solo en "En vivo"); no aparece morado ni teal (botones, segmentado,
    superficies, diálogo), incluso con el fondo de pantalla del teléfono en otro
    color. El modo oscuro es gris grafito, no negro.
11. El ícono de la app muestra el teléfono acostado con la persona en pantalla y la
    luz roja en el lanzador. Con los íconos temáticos activados (Android 13+) se ve la versión
    monocroma.
12. La marca del encabezado, la píldora y la acción principal usan Space Grotesk;
    la dirección y el código usan JetBrains Mono, también sin conexión a internet.
13. La extensión muestra el ícono nuevo en la barra de Chrome y en
    `chrome://extensions`. El popup usa los colores de D en claro y oscuro, y un
    error se distingue de "Transmitiendo" sin depender solo del color.

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
