package cl.antony.phonecamweb.stream

import android.view.Surface

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

    /** Solo un puente dibuja en la vista previa: una Surface acepta un único productor a la vez. */
    @Volatile
    private var listener: ((Surface?) -> Unit)? = null

    fun attach(surface: Surface) {
        this.surface = surface
        listener?.invoke(surface)
    }

    /**
     * Retira la superficie. Bloquea hasta que nadie la esté usando: Android exige que,
     * al volver de SurfaceHolder.Callback.surfaceDestroyed, ya nadie dibuje en ella.
     */
    fun detach() {
        surface = null
        listener?.invoke(null)
    }

    /**
     * El puente nuevo (por ejemplo, al cambiar de cámara) reemplaza al anterior. El anterior suelta
     * la superficie primero; si no, Android rechaza la conexión del nuevo y la vista previa se congela.
     */
    internal fun setListener(newListener: (Surface?) -> Unit) {
        listener?.invoke(null)
        listener = newListener
        surface?.let(newListener)
    }

    internal fun removeListener(oldListener: (Surface?) -> Unit) {
        if (listener === oldListener) listener = null
    }
}
