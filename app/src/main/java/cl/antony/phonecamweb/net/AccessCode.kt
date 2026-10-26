package cl.antony.phonecamweb.net

import java.net.InetAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Código de acceso para conexiones por Wi‑Fi, con formato "K7QM-2XPA".
 * 8 caracteres de un alfabeto de 32 (sin 0/O ni 1/I/L para no confundirlos):
 * 32^8 ≈ 10^12 combinaciones, imposible de adivinar con el bloqueo de [AccessGuard].
 */
object AccessCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(): String {
        val chars = CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars, 0, 4) + "-" + String(chars, 4, 4)
    }

    /** Ignora mayúsculas, espacios y guiones: "k7qm 2xpa" vale lo mismo que "K7QM-2XPA". */
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    /** Comparación en tiempo constante (no revela cuántos caracteres coinciden). */
    fun matches(expected: String, given: String?): Boolean {
        if (given == null) return false
        return MessageDigest.isEqual(
            normalize(expected).toByteArray(Charsets.UTF_8),
            normalize(given).toByteArray(Charsets.UTF_8),
        )
    }

    /** Lee un parámetro de la ruta HTTP, por ejemplo code de "/?code=K7QM-2XPA". */
    fun queryParam(path: String, name: String): String? {
        val query = path.substringAfter('?', missingDelimiterValue = "")
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    }
}

/**
 * Bloquea por un rato a quien falla el código varias veces seguidas,
 * para que no se pueda probar código tras código.
 */
class AccessGuard(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = HashMap<InetAddress, ArrayDeque<Long>>()

    @Synchronized
    fun isBlocked(address: InetAddress): Boolean = recent(address).size >= maxFailures

    @Synchronized
    fun recordFailure(address: InetAddress) {
        recent(address).addLast(now())
    }

    @Synchronized
    fun recordSuccess(address: InetAddress) {
        failures.remove(address)
    }

    private fun recent(address: InetAddress): ArrayDeque<Long> {
        val list = failures.getOrPut(address) { ArrayDeque() }
        val limit = now() - windowMs
        while (list.isNotEmpty() && list.first() < limit) list.removeFirst()
        return list
    }
}
