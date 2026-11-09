package net.kollnig.missioncontrol.netbird

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Log
import eu.faircode.netguard.IPUtil
import eu.faircode.netguard.Util
import eu.faircode.netguard.VpnRoutes
import net.kollnig.missioncontrol.wg.WgEgress
import net.kollnig.missioncontrol.wgbridge.Wgbridge
import java.net.InetAddress

/**
 * Points d'accroche de NetBird dans ServiceSinkhole (fichier d'origine de TrackerControl).
 *
 * Toute la logique NetBird du service VPN vit ici : ServiceSinkhole n'a que des appels courts,
 * repérés par le commentaire « NetBird Edition », pour que les mises à jour amont fusionnent
 * sans conflit. NetBird prime sur WireGuard : s'il est activé, le profil WG est ignoré.
 */
object NetbirdVpn {
    private const val TAG = "NetbirdVpn"

    /** Résultat du démarrage natif côté NetBird. */
    enum class Start {
        /** NetBird désactivé (arrêté ici) : le démarrage WireGuard d'origine s'applique. */
        NOT_ENABLED,
        /** NetBird démarré : le démarrage WireGuard est sauté. */
        STARTED,
        /** Échec : le trafic reste bloqué, startNative doit renvoyer false. */
        FAILED
    }

    /**
     * Ce que NetBird impose au builder VPN, lu une fois dans les préférences netbird_*.
     * Hors NetBird actif, chaque méthode rend sa valeur d'entrée inchangée.
     */
    class BuilderOverrides internal constructor(private val prefs: SharedPreferences) {
        val enabled: Boolean = prefs.getBoolean(NetbirdEgress.PREF_ENABLED, false)
        private val address: String =
            if (enabled) prefs.getString(NetbirdEgress.PREF_ADDRESS, "") ?: "" else ""
        private val address6: String =
            if (enabled) prefs.getString(NetbirdEgress.PREF_ADDRESS6, "") ?: "" else ""
        private val routes: List<String> =
            if (enabled) NetbirdEgress.storedRoutes(prefs) else emptyList()

        /** NetBird activé et son adresse connue (mémorisée par configureInterface). */
        val active: Boolean = enabled && address.isNotEmpty()

        /** Routes NetBird à installer dans le VPN, ou null pour garder la logique d'origine. */
        fun routes(): List<IPUtil.CIDR>? =
            if (active && routes.isNotEmpty()) VpnRoutes.getRoutes(routes) else null

        /** Adresse IPv4 du VPN (sans préfixe, en /32 comme WG). */
        fun vpn4(current: String?): String? =
            if (active) address.split("/")[0].trim() else current

        /** Adresse IPv6 du VPN. */
        fun vpn6(current: String?): String? =
            if (active && address6.isNotEmpty()) address6.split("/")[0].trim() else current

        /**
         * Résolveur du VPN : celui annoncé par NetBird (il relaie les domaines NetBird et les
         * autres vers le DNS du réseau sous-jacent). Sans adresse exploitable, [current] reste.
         */
        fun dns(current: List<InetAddress>?): List<InetAddress>? {
            if (!active) return current
            val nbDns = prefs.getString(NetbirdEgress.PREF_DNS, "") ?: ""
            if (nbDns.isEmpty() || !Util.isNumericAddress(nbDns.trim())) return current
            return try {
                listOf(InetAddress.getByName(nbDns.trim()))
            } catch (ex: Throwable) {
                Log.w(TAG, "NetBird DNS ignored: $ex")
                null
            }
        }

        fun addSearchDomains(builder: VpnService.Builder) {
            if (!active) return
            val nbSearch = prefs.getString(NetbirdEgress.PREF_SEARCH_DOMAINS, "") ?: ""
            for (raw in nbSearch.split(";")) {
                val sd = raw.trim()
                if (sd.isEmpty()) continue
                try {
                    Log.i(TAG, "Using NetBird search domain=$sd")
                    builder.addSearchDomain(sd)
                } catch (ex: Throwable) {
                    Log.w(TAG, "addSearchDomain $sd: $ex")
                }
            }
        }

        /**
         * L'overlay IPv6 de NetBird est une plage ULA (fd00::/8), hors de 2000::/3 : ses routes
         * sont ajoutées explicitement. Une route ::/0 (nœud de sortie) n'ajoute rien à 2000::/3.
         */
        fun addIpv6Routes(builder: VpnService.Builder, ip6: Boolean) {
            if (!ip6 || !active) return
            for (route in routes) {
                val parts = route.trim().split("/")
                if (!parts[0].contains(":") || parts.size != 2) continue
                try {
                    val prefix = parts[1].toInt()
                    if (prefix > 0 && Util.isNumericAddress(parts[0])) {
                        builder.addRoute(parts[0], prefix)
                        Log.i(TAG, "NetBird IPv6 route ${parts[0]}/$prefix")
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, "NetBird IPv6 route $route: $ex")
                }
            }
        }

        fun mtu(current: Int): Int {
            if (!active) return current
            val nbMtu = prefs.getInt(NetbirdEgress.PREF_MTU, 0)
            return if (nbMtu in 1280..1500 && nbMtu < current) nbMtu else current
        }
    }

    /** Changement du réseau sous-jacent : prévient le moteur NetBird (reload du service). */
    @JvmStatic
    fun onUnderlyingNetworkChanged(ctx: Context) = NetbirdEgress.onUnderlyingNetworkChanged(ctx)

    @JvmStatic
    fun builderOverrides(prefs: SharedPreferences): BuilderOverrides = BuilderOverrides(prefs)

    /**
     * Démarrage natif : NetBird à la place de WgEgress s'il est activé, sinon arrête NetBird
     * (WgEgress démarre alors comme à l'origine).
     */
    @JvmStatic
    fun start(
        prefs: SharedPreferences,
        service: VpnService,
        vpn: ParcelFileDescriptor,
        startSocketpair: () -> Int,
        stopSocketpair: () -> Unit,
        setWireguardRequired: (Boolean) -> Unit
    ): Start {
        if (!prefs.getBoolean(NetbirdEgress.PREF_ENABLED, false)) {
            NetbirdEgress.stop(stopSocketpair)
            return Start.NOT_ENABLED
        }
        // NetBird prime sur WireGuard : WgEgress doit être arrêté.
        WgEgress.stop(stopSocketpair)
        val stored = NetbirdEgress.storedRoutes(prefs)
        setWireguardRequired(stored.isNotEmpty())
        try {
            Wgbridge.setPolicyAllowedIps(TextUtils.join(";", stored))
        } catch (ex: Throwable) {
            Log.w(TAG, "NetBird policy: $ex")
        }
        val ok = NetbirdEgress.startOrUpdate(true, service, vpn, startSocketpair, stopSocketpair)
        if (!ok) {
            Log.w(TAG, "NetBird egress failed to start; blocking traffic: ${NetbirdEgress.getLastError()}")
            return Start.FAILED
        }
        return Start.STARTED
    }
}
