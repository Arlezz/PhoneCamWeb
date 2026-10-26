package cl.antony.phonecamweb.stream

import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Conecta la vista previa de la pantalla (MainActivity) con el puente OpenGL del servicio.
 *
 * La pantalla publica aquí su superficie cuando existe y la retira cuando desaparece
 * (app en segundo plano, pantalla apagada). GlBridge escucha y dibuja en ella además
 * de en el encoder.
 */
object PreviewOutput {
    @Volatile
    var surface: Surface? = null
        private set

    private val listeners = CopyOnWriteArraySet<(Surface?) -> Unit>()

    fun attach(surface: Surface) {
        this.surface = surface
        listeners.forEach { it(surface) }
    }

    /**
     * Retira la superficie. Bloquea hasta que nadie la esté usando: Android exige que,
     * al volver de SurfaceHolder.Callback.surfaceDestroyed, ya nadie dibuje en ella.
     */
    fun detach() {
        surface = null
        listeners.forEach { it(null) }
    }

    internal fun addListener(listener: (Surface?) -> Unit) = listeners.add(listener)

    internal fun removeListener(listener: (Surface?) -> Unit) = listeners.remove(listener)
}
