package cl.antony.phonecamweb.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamStatus(
    val running: Boolean = false,
    val clients: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val error: String? = null,
)

/** Estado compartido entre el servicio (escribe) y la pantalla (lee). */
object StreamState {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    fun update(transform: (StreamStatus) -> StreamStatus) = _status.update(transform)

    /** Vuelve al estado inicial. Con `keepError` se conserva el último error para mostrarlo. */
    fun reset(keepError: Boolean = false) {
        _status.update { StreamStatus(error = if (keepError) it.error else null) }
    }
}
