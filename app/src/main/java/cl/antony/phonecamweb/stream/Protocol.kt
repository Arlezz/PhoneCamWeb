package cl.antony.phonecamweb.stream

/**
 * Un access unit H.264 ya codificado (Annex-B: 00 00 00 01 + NAL).
 * Si es keyframe, `data` ya incluye SPS/PPS al inicio.
 */
class EncodedFrame(
    val data: ByteArray,
    val ptsUs: Long,
    val isKeyFrame: Boolean,
)

/**
 * Formato de cada mensaje WebSocket (big-endian):
 *
 * | Bytes | Campo     | Valores                                   |
 * |-------|-----------|-------------------------------------------|
 * | 0     | Versión   | 1                                         |
 * | 1     | Tipo      | 1 = keyframe (con SPS/PPS), 2 = delta     |
 * | 2..9  | Timestamp | microsegundos, entero sin signo de 64 bits |
 * | 10..  | Payload   | H.264 Annex-B                             |
 */
object Protocol {
    const val VERSION: Byte = 1
    const val TYPE_KEYFRAME: Byte = 1
    const val TYPE_DELTA: Byte = 2
    const val HEADER_SIZE = 10

    fun encode(frame: EncodedFrame): ByteArray {
        val message = ByteArray(HEADER_SIZE + frame.data.size)
        message[0] = VERSION
        message[1] = if (frame.isKeyFrame) TYPE_KEYFRAME else TYPE_DELTA
        var pts = frame.ptsUs
        for (i in 9 downTo 2) {
            message[i] = (pts and 0xFF).toByte()
            pts = pts ushr 8
        }
        System.arraycopy(frame.data, 0, message, HEADER_SIZE, frame.data.size)
        return message
    }
}
