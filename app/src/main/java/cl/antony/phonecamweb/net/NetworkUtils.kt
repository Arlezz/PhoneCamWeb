package cl.antony.phonecamweb.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * IPv4 de la red local (Wi‑Fi), o null si no hay. Prefiere las interfaces "wlan*":
     * la red móvil también puede tener una dirección privada y no sirve para la Mac.
     */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
