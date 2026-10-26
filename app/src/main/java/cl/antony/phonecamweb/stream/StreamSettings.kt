package cl.antony.phonecamweb.stream

import android.content.Context
import androidx.core.content.edit
import cl.antony.phonecamweb.net.AccessCode

/** Preferencias que sobreviven al cerrar la app: Wi‑Fi activado y código de acceso. */
object StreamSettings {
    private const val PREFS = "phonecam"
    private const val KEY_WIFI = "wifi_enabled"
    private const val KEY_CODE = "access_code"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wifiEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI, false)

    fun setWifiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WIFI, enabled) }
    }

    /** El código se crea la primera vez y se mantiene hasta que pidas uno nuevo. */
    fun accessCode(context: Context): String =
        prefs(context).getString(KEY_CODE, null) ?: regenerateCode(context)

    fun regenerateCode(context: Context): String {
        val code = AccessCode.generate()
        prefs(context).edit { putString(KEY_CODE, code) }
        return code
    }
}
