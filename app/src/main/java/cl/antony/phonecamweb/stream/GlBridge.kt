package cl.antony.phonecamweb.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Puente por GPU entre la cámara y el encoder:
 *
 *   cámara → [inputSurface] → textura OpenGL → dibujo → [superficie del encoder]
 *                                                     → [vista previa en pantalla, si hay]
 *
 * ¿Por qué? Algunos encoders (por ejemplo el de Qualcomm del Galaxy S25) suponen que
 * su superficie recibe imágenes RGBA dibujadas por la GPU. Si la cámara escribe ahí
 * directamente en YUV, el encoder interpreta mal los bytes y el video sale corrupto.
 * Dibujando con OpenGL, el encoder recibe siempre el formato que espera.
 *
 * Orientación: el video siempre sale horizontal (16:9) y derecho con el teléfono acostado.
 * La textura de la cámara normalmente viene girada para verse derecha con el teléfono
 * vertical; en ese caso la volvemos a girar 90° al dibujarla (si no, se vería estirada).
 *
 * Espejo: la cámara frontal llega espejada (como un espejo). Con [mirror] = true la
 * "desespejamos" para que quienes te ven lean los textos al derecho.
 */
class GlBridge(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val mirror: Boolean = false,
    /** true si el teléfono está acostado con su parte de arriba hacia la derecha. */
    private val isTopToRight: () -> Boolean = { false },
) {
    private val thread = HandlerThread("gl-bridge").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var uTexMatrix = 0
    private var uMirror = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private val texMatrix = FloatArray(16)
    private val size = IntArray(1)
    private var lastCameraRotated: Boolean? = null

    private var surfaceTexture: SurfaceTexture? = null

    /** Superficie que se le entrega a CameraX. */
    lateinit var inputSurface: Surface
        private set

    @Volatile
    private var released = false

    /** La pantalla agregó o retiró su vista previa. */
    private val previewListener: (Surface?) -> Unit = { surface ->
        if (surface != null) {
            handler.post { attachPreview(surface) }
        } else {
            // Hay que soltarla antes de volver: después de esto Android la destruye.
            val done = CountDownLatch(1)
            val posted = handler.post {
                detachPreview()
                done.countDown()
            }
            if (posted) done.await(500, TimeUnit.MILLISECONDS)
        }
    }

    init {
        val ready = CountDownLatch(1)
        var setupError: Throwable? = null
        handler.post {
            try {
                setup()
            } catch (t: Throwable) {
                setupError = t
            } finally {
                ready.countDown()
            }
        }
        ready.await()
        setupError?.let {
            release()
            throw it
        }
        PreviewOutput.setListener(previewListener)
    }

    private fun setup() {
        // 1. EGL: conecta OpenGL con la superficie del encoder.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay falló" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize falló" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, // la superficie la consume un encoder de video
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "eglChooseConfig no encontró una configuración"
        }
        val config = configs[0]!!
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falló" }

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface falló" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent falló" }

        // 2. Shaders: copian la textura de la cámara a la superficie de destino.
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uMirror = GLES20.glGetUniformLocation(program, "uMirror")

        // 3. Textura externa donde escribe la cámara.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = texture
        inputSurface = Surface(texture)
        Log.i(TAG, "Puente OpenGL listo (${width}x$height, espejo=$mirror)")
    }

    // ---- Vista previa ----

    private fun attachPreview(surface: Surface) {
        if (released) return
        detachPreview()
        try {
            val created = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (created == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "No se pudo conectar la vista previa (EGL 0x${Integer.toHexString(EGL14.eglGetError())})")
                return
            }
            previewSurface = created
            // Que la vista previa nunca haga esperar al encoder por la sincronización de pantalla.
            EGL14.eglMakeCurrent(eglDisplay, previewSurface, previewSurface, eglContext)
            EGL14.eglSwapInterval(eglDisplay, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo conectar la vista previa", e)
            previewSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun detachPreview() {
        if (previewSurface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        EGL14.eglDestroySurface(eglDisplay, previewSurface)
        previewSurface = EGL14.EGL_NO_SURFACE
    }

    // ---- Dibujo ----

    /** Se llama en el hilo de OpenGL cada vez que la cámara entrega un cuadro. */
    private fun drawFrame() {
        if (released) return
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            val vertices = verticesFor(texMatrix)

            // 1. Encoder (lo importante: primero, para no sumar latencia).
            drawQuad(vertices, width, height)
            // El encoder usa este timestamp (ns) como tiempo del cuadro.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, texture.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            // 2. Vista previa en pantalla, si está visible.
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, previewSurface, previewSurface, eglContext)
                EGL14.eglQuerySurface(eglDisplay, previewSurface, EGL14.EGL_WIDTH, size, 0)
                val previewWidth = size[0]
                EGL14.eglQuerySurface(eglDisplay, previewSurface, EGL14.EGL_HEIGHT, size, 0)
                drawQuad(vertices, previewWidth, size[0])
                EGL14.eglSwapBuffers(eglDisplay, previewSurface)
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
        } catch (e: Exception) {
            if (!released) Log.w(TAG, "No se pudo dibujar el cuadro", e)
        }
    }

    private fun drawQuad(vertices: FloatBuffer, viewportWidth: Int, viewportHeight: Int) {
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Elige cómo girar el rectángulo donde se dibuja la textura.
     * Si la matriz de la cámara ya incluye un giro de 90° (m00 ≈ 0), el contenido viene
     * vertical y hay que girarlo 90° hacia el lado que corresponda. Si no, viene en la
     * orientación del sensor, que ya es horizontal.
     */
    private fun verticesFor(matrix: FloatArray): FloatBuffer {
        val cameraRotated = abs(matrix[0]) < 0.5f
        if (cameraRotated != lastCameraRotated) {
            lastCameraRotated = cameraRotated
            Log.i(TAG, "La cámara entrega la imagen ${if (cameraRotated) "girada (vertical)" else "sin girar (horizontal)"}")
        }
        val topToRight = isTopToRight()
        return when {
            cameraRotated && !topToRight -> VERTICES_ROTATE_CCW
            cameraRotated -> VERTICES_ROTATE_CW
            !topToRight -> VERTICES
            else -> VERTICES_ROTATE_180
        }
    }

    /** Libera todo. Espera hasta 1 s a que el hilo de OpenGL termine. */
    fun release() {
        if (released) return
        released = true
        PreviewOutput.removeListener(previewListener)
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                if (::inputSurface.isInitialized) inputSurface.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    detachPreview()
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando OpenGL", e)
            } finally {
                eglDisplay = EGL14.EGL_NO_DISPLAY
                done.countDown()
                thread.quitSafely()
            }
        }
        if (posted) done.await(1, TimeUnit.SECONDS)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Error al enlazar shaders: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Error al compilar shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val TAG = "GlBridge"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // uMirror = 1 "desespeja" la cámara frontal. Ojo: el espejo que aplica Android se hace
        // sobre la imagen del sensor ANTES de girarla 90°, así que en este espacio (la imagen
        // ya girada) el espejo queda VERTICAL. Voltear en horizontal aquí equivalía a girar
        // 180° (probado en el Galaxy S25 Ultra: la frontal salía al revés en ambos lados).
        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uMirror;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 tc = vec2(aTexCoord.x, mix(aTexCoord.y, 1.0 - aTexCoord.y, uMirror));
                vTexCoord = (uTexMatrix * vec4(tc, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent() // #extension debe ir en la primera línea

        // Rectángulo que cubre toda la superficie (dos triángulos), y sus versiones giradas.
        // Las coordenadas de textura son siempre las mismas; lo que gira es dónde se dibuja.
        private val VERTICES = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val VERTICES_ROTATE_CCW = floatBuffer(1f, -1f, 1f, 1f, -1f, -1f, -1f, 1f) // 90° antihorario
        private val VERTICES_ROTATE_CW = floatBuffer(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f) // 90° horario
        private val VERTICES_ROTATE_180 = floatBuffer(1f, 1f, -1f, 1f, 1f, -1f, -1f, -1f)
        private val TEX_COORDS = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
