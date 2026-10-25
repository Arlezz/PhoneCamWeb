package cl.antony.phonecamweb.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encoder H.264 por hardware con superficie de entrada: CameraX dibuja en
 * [inputSurface] y nosotros recibimos los access units ya codificados.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 4_000_000,
    private val frameRate: Int = 30,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("video-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val inputSurface: Surface

    /** SPS + PPS en Annex-B. Se antepone a cada keyframe. */
    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var released = false

    @Volatile
    private var lastKeyframeRequestMs = 0L

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // No se usa: la entrada es una superficie.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buffer.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codecConfig = bytes
                    } else {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val config = codecConfig
                        val data = if (isKey && config != null) config + bytes else bytes
                        onFrame(EncodedFrame(data, info.presentationTimeUs, isKey))
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                if (!released) onError(e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Algunos encoders entregan SPS/PPS aquí en vez de un buffer CODEC_CONFIG.
            val sps = format.getByteBuffer("csd-0")
            val pps = format.getByteBuffer("csd-1")
            if (sps != null && pps != null) codecConfig = sps.toBytes() + pps.toBytes()
            Log.i(TAG, "Formato de salida: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!released) onError(e)
        }
    }

    init {
        codec.setCallback(callback, handler)
        try {
            codec.configure(buildFormat(withProfile = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Algunos encoders rechazan el perfil/nivel explícito: reintentamos sin ellos.
            Log.w(TAG, "El encoder rechazó perfil Baseline, reintentando sin perfil", e)
            codec.reset()
            codec.setCallback(callback, handler)
            codec.configure(buildFormat(withProfile = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        Log.i(TAG, "Encoder ${codec.name} iniciado en ${width}x$height")
    }

    private fun buildFormat(withProfile: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Si la escena no cambia, repite el cuadro: el navegador siempre recibe video.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            if (supportsCbr()) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                val level = if (width * height > 1280 * 720) {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4
                } else {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                }
                setInteger(MediaFormat.KEY_LEVEL, level)
            }
        }

    private fun supportsCbr(): Boolean = runCatching {
        codec.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities
            ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
    }.getOrDefault(false)

    /** Pide un keyframe (máximo uno cada 500 ms). */
    fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (released || now - lastKeyframeRequestMs < 500) return
        lastKeyframeRequestMs = now
        handler.post {
            if (!released) {
                runCatching {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
            }
        }
    }

    fun release() {
        released = true
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface.release() }
            thread.quitSafely()
        }
    }

    private fun ByteBuffer.toBytes(): ByteArray {
        val copy = duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
