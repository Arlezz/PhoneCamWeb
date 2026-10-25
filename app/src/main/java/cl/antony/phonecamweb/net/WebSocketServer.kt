package cl.antony.phonecamweb.net

import cl.antony.phonecamweb.stream.FrameHub
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor WebSocket mínimo (RFC 6455), sin dependencias.
 *
 * Solo hace lo que necesitamos: el servidor envía frames binarios y del cliente
 * únicamente atiende `close` y `ping`. Cada cliente usa dos hilos: uno escribe
 * los mensajes del [FrameHub] y otro lee los frames de control.
 *
 * Una petición HTTP normal (sin upgrade) recibe un texto de estado, útil para
 * probar con `curl http://localhost:5000`.
 */
class WebSocketServer(
    private val port: Int,
    private val hub: FrameHub,
    // 127.0.0.1 explícito: en Android getLoopbackAddress() puede devolver ::1 (IPv6)
    // y adb forward se conecta por IPv4.
    private val bindAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
    private val log: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<Socket>()

    @Volatile
    private var running = false

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        running = true
        thread(name = "ws-accept", isDaemon = true) { acceptLoop(socket) }
        log("Servidor escuchando en ${bindAddress.hostAddress}:$port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break // stop() cerró el socket
            }
            thread(name = "ws-client", isDaemon = true) { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val request = readHttpRequest(input) ?: return
            val key = request.headers["sec-websocket-key"]
            val isUpgrade = request.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            if (key == null || !isUpgrade) {
                writeStatusPage(output)
                return
            }
            writeHandshake(output, key)
            log("Cliente conectado: ${socket.remoteSocketAddress}")
            serveWebSocket(socket, input, output)
        } catch (e: IOException) {
            log("Error en la conexión: ${e.message}")
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun serveWebSocket(socket: Socket, input: InputStream, output: OutputStream) {
        val subscription = hub.subscribe()
        val closed = AtomicBoolean(false)

        // Hilo lector: atiende ping y close del cliente.
        thread(name = "ws-reader", isDaemon = true) {
            try {
                while (!closed.get()) {
                    val frame = readFrame(input)
                    when (frame.opcode) {
                        OPCODE_CLOSE -> {
                            synchronized(output) { writeFrame(output, OPCODE_CLOSE, ByteArray(0)) }
                            break
                        }
                        OPCODE_PING -> synchronized(output) { writeFrame(output, OPCODE_PONG, frame.payload) }
                        else -> Unit // el cliente no necesita enviarnos datos
                    }
                }
            } catch (e: IOException) {
                // EOF o socket cerrado
            } finally {
                closed.set(true)
                runCatching { socket.close() }
            }
        }

        // Hilo actual: escribe los cuadros.
        try {
            while (!closed.get()) {
                val message = subscription.poll(500) ?: continue
                synchronized(output) { writeFrame(output, OPCODE_BINARY, message) }
            }
        } catch (e: IOException) {
            // El cliente cerró la conexión mientras escribíamos.
        } finally {
            closed.set(true)
            hub.unsubscribe(subscription)
            log("Cliente desconectado: ${socket.remoteSocketAddress}")
        }
    }

    // ---- HTTP ----

    private class HttpRequest(val path: String, val headers: Map<String, String>)

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return HttpRequest(parts[1], headers)
    }

    /** Lee una línea terminada en CRLF (máx. 8 KB). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Línea HTTP demasiado larga")
        }
        return sb.toString()
    }

    private fun writeHandshake(output: OutputStream, key: String) {
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeStatusPage(output: OutputStream) {
        val body = "PhoneCam Web: transmitiendo. Conéctate por WebSocket a ws://<host>:$port/\n"
            .toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    // ---- Frames WebSocket ----

    private class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode) // FIN + opcode; el servidor no enmascara
        val length = payload.size
        when {
            length < 126 -> output.write(length)
            length <= 0xFFFF -> {
                output.write(126)
                output.write(length ushr 8)
                output.write(length and 0xFF)
            }
            else -> {
                output.write(127)
                val len = length.toLong()
                for (shift in 56 downTo 0 step 8) output.write(((len ushr shift) and 0xFF).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readFrame(input: InputStream): WsFrame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length > MAX_CLIENT_PAYLOAD) throw IOException("Frame del cliente demasiado grande")
        val mask = if (masked) ByteArray(4) { readByte(input).toByte() } else null
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n == -1) throw EOFException()
            read += n
        }
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return WsFrame(opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b == -1) throw EOFException()
        return b
    }

    companion object {
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val MAX_CLIENT_PAYLOAD = 64 * 1024
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Sec-WebSocket-Accept = base64(sha1(key + GUID)). */
        internal fun acceptKey(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
            return base64(sha1)
        }

        // java.util.Base64 requiere API 26 y la app soporta desde API 23.
        private fun base64(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
                sb.append(alphabet[b0 shr 2])
                sb.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
                sb.append(if (b1 >= 0) alphabet[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
                sb.append(if (b2 >= 0) alphabet[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }
    }
}
