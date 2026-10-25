package cl.antony.phonecamweb.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Primera IPv4 de red local (Wi‑Fi), o null si no hay. */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
