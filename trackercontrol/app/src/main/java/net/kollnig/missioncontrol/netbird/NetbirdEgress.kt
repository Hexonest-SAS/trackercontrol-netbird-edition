package net.kollnig.missioncontrol.netbird

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.preference.PreferenceManager
import eu.faircode.netguard.ServiceSinkhole
import io.netbird.gomobile.android.Android
import io.netbird.gomobile.android.Client
import io.netbird.gomobile.android.DNSList
import io.netbird.gomobile.android.DnsReadyListener
import io.netbird.gomobile.android.IFaceDiscover
import io.netbird.gomobile.android.NetworkChangeListener
import io.netbird.gomobile.android.PlatformFiles
import io.netbird.gomobile.android.TunAdapter
import net.kollnig.missioncontrol.wgbridge.Wgbridge
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * Moteur NetBird embarque, parallele a WgEgress et exclusif avec lui.
 *
 * Le moteur Go (AAR patche) lit les paquets sortants sur l'extremite de lecture du socketpair de
 * TrackerControl et ecrit les paquets entrants dans le TUN via un second fd. Les parametres
 * recus par configureInterface (adresse, routes, DNS, MTU) sont memorises dans les preferences
 * pour que ServiceSinkhole construise le TUN en consequence ; s'ils changent, un reload est
 * planifie et le nouveau TUN arrive par startOrUpdate -> renewTun.
 */
object NetbirdEgress {
    private const val TAG = "NetbirdEgress"

    const val PREF_ENABLED = "netbird_enabled"
    const val PREF_ADDRESS = "netbird_address"
    const val PREF_ADDRESS6 = "netbird_address6"
    const val PREF_ROUTES = "netbird_routes"
    const val PREF_DNS = "netbird_dns"
    const val PREF_SEARCH_DOMAINS = "netbird_search_domains"
    const val PREF_MTU = "netbird_mtu"

    private const val STOP_JOIN_MS = 5000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Etat protege par `lock`.
    private var client: Client? = null
    private var thread: Thread? = null
    private var service: VpnService? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var stopSocketpairFn: (() -> Unit)? = null
    private var startSocketpairFn: (() -> Int)? = null
    private var tunConfigured = false

    @Volatile private var lastError: String? = null

    fun getLastError(): String? = lastError

    fun isRunning(): Boolean = synchronized(lock) { thread?.isAlive == true }

    /** Routes NetBird memorisees (separees par ";"), sans entree vide. */
    @JvmStatic
    fun storedRoutes(prefs: SharedPreferences): List<String> =
        splitList(prefs.getString(PREF_ROUTES, ""))

    private fun splitList(s: String?): List<String> =
        (s ?: "").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    fun configFile(context: Context): File = File(File(context.filesDir, "netbird"), "netbird.cfg")

    fun startOrUpdate(
        enabled: Boolean,
        vpnService: VpnService,
        vpnFd: ParcelFileDescriptor,
        startSocketpair: () -> Int,
        stopSocketpair: () -> Unit
    ): Boolean {
        if (!enabled) {
            stop(stopSocketpair)
            return true
        }
        lastError = null

        var renew = false
        var startEngine = false
        val c: Client
        synchronized(lock) {
            service = vpnService
            startSocketpairFn = startSocketpair
            stopSocketpairFn = stopSocketpair
            val running = thread?.isAlive == true
            if (running && tunPfd === vpnFd) {
                Log.v(TAG, "startOrUpdate: meme TUN, no-op")
                return true
            }
            tunPfd = vpnFd
            if (running) {
                if (!tunConfigured) {
                    // Le moteur n'a pas encore appele configureInterface : il prendra ce TUN.
                    Log.i(TAG, "TUN change avant la premiere configuration, rien a renouveler")
                    return true
                }
                renew = true
                c = client!!
            } else {
                if (!configFile(vpnService).exists()) {
                    lastError = "NetBird non configuré"
                    Log.w(TAG, "aucune configuration NetBird, moteur non lance")
                    return true
                }
                c = client ?: createClient(vpnService).also { client = it }
                startEngine = true
            }
        }

        if (renew) return renewTun(c, vpnFd, startSocketpair, stopSocketpair)
        if (startEngine) startThread(c, vpnService)
        return true
    }

    private fun renewTun(
        c: Client,
        vpnFd: ParcelFileDescriptor,
        startSocketpair: () -> Int,
        stopSocketpair: () -> Unit
    ): Boolean {
        Log.i(TAG, "TUN change — renouvellement")
        try {
            stopSocketpair()
            val rx = startSocketpair()
            if (rx < 0) {
                lastError = "Could not create NetBird packet socket"
                return false
            }
            Android.setSocketpairTunWriteFd(dupTun(vpnFd).toLong())
            c.renewTun(rx.toLong()) // le moteur prend possession de rx
            return true
        } catch (e: Throwable) {
            lastError = "NetBird renewTun failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "renewTun", e)
            return false
        }
    }

    private fun dupTun(pfd: ParcelFileDescriptor): Int =
        ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()

    private fun createClient(ctx: Context): Client =
        Android.newClient(
            Build.VERSION.SDK_INT.toLong(),
            Build.MODEL ?: "android",
            "trackercontrol-netbird",
            tunAdapter,
            ifaceDiscover,
            networkListener
        )

    private fun startThread(c: Client, ctx: Context) {
        val dir = File(ctx.filesDir, "netbird").apply { mkdirs() }
        val files = object : PlatformFiles {
            override fun configurationFilePath(): String = File(dir, "netbird.cfg").absolutePath
            override fun stateFilePath(): String = File(dir, "state.json").absolutePath
            override fun cacheDir(): String = ctx.cacheDir.absolutePath
        }
        val t = Thread({
            try {
                Log.i(TAG, "moteur NetBird demarre")
                c.runWithoutLogin(files, DNSList(), object : DnsReadyListener {
                    override fun onReady() {}
                }, Android.newEnvList())
            } catch (e: Throwable) {
                lastError = "NetBird: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, "runWithoutLogin", e)
            } finally {
                Log.i(TAG, "moteur NetBird arrete")
                synchronized(lock) {
                    if (thread === Thread.currentThread()) tunConfigured = false
                }
            }
        }, "netbird-engine")
        synchronized(lock) {
            thread = t
            tunConfigured = false
        }
        t.start()
    }

    /** Arret idempotent. Ne tient jamais le verrou en attendant le thread du moteur. */
    fun stop(stopSocketpair: () -> Unit) {
        val c: Client?
        val t: Thread?
        synchronized(lock) {
            c = client
            t = thread
            thread = null
            tunPfd = null
            tunConfigured = false
        }
        if (t != null) {
            try { c?.stop() } catch (e: Throwable) { Log.w(TAG, "client.stop", e) }
            if (Thread.currentThread() !== t) {
                try {
                    t.join(STOP_JOIN_MS)
                    if (t.isAlive) Log.w(TAG, "le moteur NetBird n'a pas fini en ${STOP_JOIN_MS} ms")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        if (t != null) {
            try { Wgbridge.setPolicyAllowedIps("") } catch (e: Throwable) { Log.w(TAG, "policy reset", e) }
            // Seulement si le moteur tournait : sinon le socketpair appartient peut-etre a WgEgress.
            stopSocketpair()
        }
        synchronized(lock) {
            if (t != null) { service = null; startSocketpairFn = null; stopSocketpairFn = null }
        }
    }

    // ---- Etat memorise et reload ------------------------------------------------------------

    private fun prefs(ctx: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun scheduleReload() {
        val ctx = synchronized(lock) { service } ?: return
        mainHandler.post {
            try {
                ServiceSinkhole.reload("netbird config changed", ctx, false)
            } catch (e: Throwable) {
                Log.w(TAG, "reload", e)
            }
        }
    }

    private fun applyPolicy(routes: String) {
        Wgbridge.setPolicyAllowedIps(splitList(routes).joinToString(";"))
    }

    private val tunAdapter = object : TunAdapter {
        override fun configureInterface(
            address: String?, addressV6: String?, mtu: Long,
            dns: String?, searchDomains: String?, routes: String?
        ): Long {
            val (ctx, pfd, start, stop) = synchronized(lock) {
                Quad(service, tunPfd, startSocketpairFn, stopSocketpairFn)
            }
            if (ctx == null || pfd == null || start == null || stop == null)
                throw IllegalStateException("TUN de TrackerControl indisponible")
            val a = address ?: ""
            val a6 = addressV6 ?: ""
            val r = routes ?: ""
            val d = dns ?: ""
            val sd = searchDomains ?: ""

            applyPolicy(r)

            val p = prefs(ctx)
            val changed = p.getString(PREF_ADDRESS, "") != a ||
                p.getString(PREF_ADDRESS6, "") != a6 ||
                p.getString(PREF_ROUTES, "") != r ||
                p.getString(PREF_DNS, "") != d
            p.edit()
                .putString(PREF_ADDRESS, a).putString(PREF_ADDRESS6, a6)
                .putString(PREF_ROUTES, r).putString(PREF_DNS, d)
                .putString(PREF_SEARCH_DOMAINS, sd).putInt(PREF_MTU, mtu.toInt())
                .apply()

            var rx = -1
            try {
                stop()
                rx = start()
                if (rx < 0) throw IllegalStateException("socketpair TrackerControl indisponible")
                Android.setSocketpairTunWriteFd(dupTun(pfd).toLong())
            } catch (e: Throwable) {
                if (rx >= 0) {
                    try { ParcelFileDescriptor.adoptFd(rx).close() } catch (_: Throwable) {}
                }
                try { stop() } catch (_: Throwable) {}
                lastError = "NetBird configureInterface: ${e.message ?: e.javaClass.simpleName}"
                throw e
            }
            synchronized(lock) { tunConfigured = true }
            if (changed) scheduleReload()
            return rx.toLong()
        }

        override fun updateAddr(addr: String?) {
            val ctx = synchronized(lock) { service } ?: return
            val a = addr ?: ""
            val p = prefs(ctx)
            val changed = p.getString(PREF_ADDRESS, "") != a
            p.edit().putString(PREF_ADDRESS, a).apply()
            if (changed) scheduleReload()
        }

        override fun protectSocket(fd: Int): Boolean {
            val s = synchronized(lock) { service } ?: return true
            return s.protect(fd)
        }
    }

    private val ifaceDiscover = object : IFaceDiscover {
        override fun iFaces(): String {
            val sb = StringBuilder()
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    sb.append(String.format("%s %d %d %b %b %b %b %b |",
                        nif.name, nif.index, nif.mtu, nif.isUp, nif.supportsMulticast(),
                        nif.isLoopback, nif.isPointToPoint, nif.supportsMulticast()))
                    for (ia in nif.interfaceAddresses)
                        sb.append(String.format("%s/%d ", ia.address.hostAddress, ia.networkPrefixLength))
                } catch (e: Exception) {
                    continue
                }
                sb.append("\n")
            }
            return sb.toString()
        }
    }

    private val networkListener = object : NetworkChangeListener {
        override fun onNetworkChanged(routes: String?) {
            val ctx = synchronized(lock) { service } ?: return
            val r = routes ?: ""
            try { applyPolicy(r) } catch (e: Throwable) { Log.w(TAG, "policy", e) }
            val p = prefs(ctx)
            val changed = p.getString(PREF_ROUTES, "") != r
            p.edit().putString(PREF_ROUTES, r).apply()
            if (changed) scheduleReload()
        }

        override fun setInterfaceIP(ip: String?) {
            Log.v(TAG, "setInterfaceIP $ip")
        }

        override fun setInterfaceIPv6(ip: String?) {
            Log.v(TAG, "setInterfaceIPv6 $ip")
        }
    }

    private data class Quad(
        val a: VpnService?, val b: ParcelFileDescriptor?,
        val c: (() -> Int)?, val d: (() -> Unit)?
    )
}
