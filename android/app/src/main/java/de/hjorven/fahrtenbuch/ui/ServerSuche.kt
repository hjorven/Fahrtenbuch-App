package de.hjorven.fahrtenbuch.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sucht den Fahrtenbuch-Server im WLAN über mDNS (_fahrtenbuch._tcp, Avahi auf dem Pi,
 * Roadmap Nr. 3). Liefert z. B. "http://192.168.1.10:8090" oder null nach [wartenMs].
 * Zweiter Weg (Nachtplan 5c): Die Adresse lässt sich im Login weiter von Hand eintragen.
 */
@Suppress("DEPRECATION")
suspend fun serverSuchen(context: Context, wartenMs: Long = 5000): String? {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return null
    val ergebnis = CompletableDeferred<String?>()
    val aufloesen = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo) {
            // Auf dem Testhandy kam zuerst nur IPv6 und die Suche lief ins Leere: IPv4 bevorzugen
            // (ab Android 14 alle Adressen), sonst IPv6 in eckigen Klammern ohne Zonen-Angabe.
            val adressen = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            val v4 = adressen.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            val url = if (v4 != null) "http://$v4:${info.port}" else {
                val v6 = adressen.firstOrNull()?.hostAddress?.substringBefore('%') ?: return
                "http://[$v6]:${info.port}"
            }
            ergebnis.complete(url)
        }
    }
    val suche = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(typ: String, code: Int) { ergebnis.complete(null) }
        override fun onStopDiscoveryFailed(typ: String, code: Int) {}
        override fun onDiscoveryStarted(typ: String) {}
        override fun onDiscoveryStopped(typ: String) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            try { nsd.resolveService(info, aufloesen) } catch (_: Exception) {}
        }
    }
    return try {
        nsd.discoverServices("_fahrtenbuch._tcp", NsdManager.PROTOCOL_DNS_SD, suche)
        withTimeoutOrNull(wartenMs) { ergebnis.await() }
    } catch (_: Exception) {
        null
    } finally {
        try { nsd.stopServiceDiscovery(suche) } catch (_: Exception) {}
    }
}
