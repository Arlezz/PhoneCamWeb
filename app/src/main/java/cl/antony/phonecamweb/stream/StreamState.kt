package cl.antony.phonecamweb.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StreamErrorKind(val fatal: Boolean) {
    CAMERA(fatal = true),
    ENCODER(fatal = true),
    PORT(fatal = true),
    /** El cambio de cámara falló pero la transmisión sigue con la cámara anterior. */
    CAMERA_SWITCH(fatal = false),
}

data class StreamError(val kind: StreamErrorKind, val detail: String?)

data class StreamStatus(
    val running: Boolean = false,
    val clients: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val error: StreamError? = null,
    val frontCamera: Boolean = false,
    /** Veces que el encoder falló y la app lo reinició sola. */
    val restarts: Int = 0,
)

/** Estado compartido entre el servicio (escribe) y la pantalla (lee). */
object StreamState {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    fun update(transform: (StreamStatus) -> StreamStatus) = _status.update(transform)

    /** Vuelve al estado inicial. Con `keepError` se conserva el último error fatal para mostrarlo. */
    fun reset(keepError: Boolean = false) {
        _status.update { StreamStatus(error = if (keepError) it.error?.takeIf { e -> e.kind.fatal } else null) }
    }
}
