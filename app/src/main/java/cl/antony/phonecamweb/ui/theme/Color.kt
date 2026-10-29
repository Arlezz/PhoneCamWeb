package cl.antony.phonecamweb.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val primaryLight = Color(0xFF2350D8)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFE3EAFF)
val onPrimaryContainerLight = Color(0xFF0E2A7A)
val secondaryContainerLight = Color(0xFFDCE4F9)
val onSecondaryContainerLight = Color(0xFF12204A)
val surfaceLight = Color(0xFFF6F7F8)
val surfaceContainerLowLight = Color(0xFFFFFFFF)
val surfaceContainerLight = Color(0xFFEEF0F2)
val surfaceContainerHighLight = Color(0xFFE8EAED)
val surfaceContainerHighestLight = Color(0xFFDEE1E5)
val onSurfaceLight = Color(0xFF16181B)
val onSurfaceVariantLight = Color(0xFF4A5058)
val outlineLight = Color(0xFF737A83)
val outlineVariantLight = Color(0xFFD3D7DC)

val primaryDark = Color(0xFFB3C5FF)
val onPrimaryDark = Color(0xFF0A2463)
val primaryContainerDark = Color(0xFF1E3D9E)
val onPrimaryContainerDark = Color(0xFFDCE4FF)
val secondaryContainerDark = Color(0xFF35415F)
val onSecondaryContainerDark = Color(0xFFDCE4F9)
val surfaceDark = Color(0xFF202327)
val surfaceContainerLowDark = Color(0xFF2A2E33)
val surfaceContainerDark = Color(0xFF2E3238)
val surfaceContainerHighDark = Color(0xFF33383E)
val surfaceContainerHighestDark = Color(0xFF3A3F46)
val onSurfaceDark = Color(0xFFE6E8EB)
val onSurfaceVariantDark = Color(0xFFAEB4BB)
val outlineDark = Color(0xFF858C94)
val outlineVariantDark = Color(0xFF464B53)

/** Colores de la píldora de estado y de la marca que no tienen rol en el ColorScheme. */
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
