package cl.antony.phonecamweb.stream

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Cola de mensajes de un cliente. Si el cliente se atrasa y la cola se llena,
 * se vacía y el cliente espera el siguiente keyframe: preferimos saltar
 * cuadros antes que acumular retraso.
 */
class Subscription internal constructor(capacity: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)

    @Volatile
    internal var waitingForKeyframe = true

    /** Bloquea hasta `timeoutMs`; devuelve null si no llegó nada. */
    fun poll(timeoutMs: Long): ByteArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    internal fun offer(message: ByteArray): Boolean = queue.offer(message)

    internal fun clear() = queue.clear()
}

/**
 * Reparte cada cuadro codificado a todos los clientes conectados.
 * Un solo encoder, N clientes.
 */
class FrameHub(
    private val requestKeyframe: () -> Unit,
    private val onSubscribersChanged: (Int) -> Unit = {},
    private val queueCapacity: Int = 15, // ~0,5 s a 30 fps
) {
    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    val subscriberCount: Int get() = subscriptions.size

    fun subscribe(): Subscription {
        val subscription = Subscription(queueCapacity)
        subscriptions.add(subscription)
        onSubscribersChanged(subscriptions.size)
        // El cliente nuevo no puede decodificar hasta recibir un keyframe.
        requestKeyframe()
        return subscription
    }

    fun unsubscribe(subscription: Subscription) {
        if (subscriptions.remove(subscription)) {
            onSubscribersChanged(subscriptions.size)
        }
    }

    fun publish(frame: EncodedFrame) {
        if (subscriptions.isEmpty()) return
        val message = Protocol.encode(frame)
        var needKeyframe = false
        for (subscription in subscriptions) {
            if (subscription.waitingForKeyframe) {
                if (!frame.isKeyFrame) continue
                subscription.waitingForKeyframe = false
            }
            if (!subscription.offer(message)) {
                // Cliente atrasado: descartamos lo pendiente y esperamos un keyframe.
                subscription.clear()
                subscription.waitingForKeyframe = true
                needKeyframe = true
            }
        }
        if (needKeyframe) requestKeyframe()
    }
}

/** Cuadros y kilobits por segundo, medidos sobre lo que sale del encoder. */
class RateMeter {
    private var frames = 0
    private var bytes = 0L
    private var windowStartNs = System.nanoTime()

    @Synchronized
    fun record(sizeBytes: Int) {
        frames++
        bytes += sizeBytes
    }

    /** Devuelve (fps, kbps) desde la última llamada y reinicia la ventana. */
    @Synchronized
    fun snapshot(): Pair<Int, Int> {
        val now = System.nanoTime()
        val seconds = ((now - windowStartNs) / 1e9).coerceAtLeast(0.001)
        val result = Pair((frames / seconds).toInt(), (bytes * 8 / 1000 / seconds).toInt())
        frames = 0
        bytes = 0
        windowStartNs = now
        return result
    }
}
