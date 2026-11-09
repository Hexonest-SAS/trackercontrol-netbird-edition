package net.kollnig.missioncontrol.netbird

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import eu.faircode.netguard.ActivitySettings
import eu.faircode.netguard.PendingIntentCompat
import eu.faircode.netguard.ServiceSinkhole
import eu.faircode.netguard.Util
import net.kollnig.missioncontrol.R
import io.netbird.gomobile.android.Android
import io.netbird.gomobile.android.DNSInspector
import io.netbird.gomobile.android.Client
import io.netbird.gomobile.android.ConnectionListener
import io.netbird.gomobile.android.DNSList
import io.netbird.gomobile.android.DnsReadyListener
import io.netbird.gomobile.android.IFaceDiscover
import io.netbird.gomobile.android.NetworkChangeListener
import io.netbird.gomobile.android.PlatformFiles
import io.netbird.gomobile.android.TunAdapter
import net.kollnig.missioncontrol.wgbridge.DnsPacketInspector
import net.kollnig.missioncontrol.wgbridge.DnsRecorder
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
    private const val NOTIFY_RELOGIN = 17

    // Reconnexion automatique : arret demande (stop()) ou non, tentative de relance en cours.
    @Volatile private var stopRequested = false
    @Volatile private var restartAttempt = 0
    private val restartRunnable = Runnable { onRestartDue() }
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "netbird-network").apply { isDaemon = true }
    }

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
    private var dnsInspector: DnsPacketInspector? = null

    @Volatile private var lastError: String? = null

    fun getLastError(): String? = lastError

    private fun setError(e: String?) {
        if (lastError == e) return
        lastError = e
        notifyStateChanged()
    }

    // ---- Etat observe par l'interface (onglet VPN) -------------------------------------------

    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2

    data class NetbirdPeer(
        val name: String,
        val fqdn: String,
        val ip: String,
        val connStatus: Int,
        val relayed: Boolean,
        val latency: String
    )

    @Volatile private var connState = STATE_DISCONNECTED
    @Volatile private var fqdn: String = ""
    @Volatile private var address: String = ""
    @Volatile private var peerCount = 0L
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    fun getConnectionState(): Int = connState
    fun getFqdn(): String = fqdn
    fun getAddress(): String = address
    fun getPeerCount(): Long = peerCount

    fun addStateListener(l: Runnable) { stateListeners.addIfAbsent(l) }
    fun removeStateListener(l: Runnable) { stateListeners.remove(l) }

    /** Previent les ecouteurs sur le thread principal (les rappels du moteur viennent de threads Go). */
    private fun notifyStateChanged() {
        mainHandler.post {
            for (l in stateListeners) try { l.run() } catch (e: Throwable) { Log.w(TAG, "listener threw", e) }
        }
    }

    private fun setConnState(s: Int) {
        if (s == STATE_CONNECTED) {
            restartAttempt = 0
            cancelReloginNotification()
        }
        if (connState == s) return
        connState = s
        notifyStateChanged()
    }

    private val connectionListener = object : ConnectionListener {
        override fun onStateChanged(state: Long) {}
        override fun onConnected() = setConnState(STATE_CONNECTED)
        override fun onDisconnected() = setConnState(STATE_DISCONNECTED)
        override fun onConnecting() = setConnState(STATE_CONNECTING)
        override fun onDisconnecting() = setConnState(STATE_DISCONNECTED)
        override fun onAddressChanged(fqdn: String?, address: String?) {
            this@NetbirdEgress.fqdn = fqdn ?: ""
            this@NetbirdEgress.address = address ?: ""
            notifyStateChanged()
        }
        override fun onPeersListChanged(numberOfPeers: Long) {
            peerCount = numberOfPeers
            notifyStateChanged()
        }
    }

    /** Pairs connus du moteur : connectes d'abord, puis par nom. Vide sans client ou en cas d'erreur. */
    fun peers(): List<NetbirdPeer> {
        val c = synchronized(lock) { client } ?: return emptyList()
        return try {
            val arr = c.peersList()
            val out = ArrayList<NetbirdPeer>()
            for (i in 0 until arr.size()) {
                val p = arr.get(i)
                val f = p.fqdn ?: ""
                out.add(NetbirdPeer(
                    name = f.substringBefore('.').ifEmpty { f },
                    fqdn = f, ip = p.ip ?: "", connStatus = p.connStatus.toInt(),
                    relayed = p.relayed, latency = p.latency ?: ""))
            }
            out.sortedWith(compareByDescending<NetbirdPeer> { it.connStatus == 2 }
                .thenBy { it.name.lowercase() })
        } catch (e: Throwable) {
            Log.w(TAG, "peersList", e)
            emptyList()
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { thread?.isAlive == true }

    /** Routes NetBird memorisees (separees par ";"), sans entree vide. */
    @JvmStatic
    fun storedRoutes(prefs: SharedPreferences): List<String> =
        effectiveRoutes(
            prefs.getString(PREF_ROUTES, "") ?: "",
            prefs.getString(PREF_ADDRESS, "") ?: "",
            prefs.getString(PREF_ADDRESS6, "") ?: ""
        )

    /**
     * Routes du tunnel : celles annoncées par NetBird plus le réseau de l'overlay, déduit de
     * l'adresse et de son préfixe (100.90.20.30/16 → 100.90.0.0/16). Sur Android, le
     * moteur ne met pas le réseau de l'overlay dans ses routes : sans lui, le trafic vers les
     * pairs ne passerait pas par le VPN.
     */
    @JvmStatic
    fun effectiveRoutes(routes: String, address: String, address6: String): List<String> {
        val out = LinkedHashSet(splitList(routes))
        for (a in listOf(address, address6)) networkOf(a)?.let { out.add(it) }
        return out.toList()
    }

    /** Réseau d'une adresse CIDR (« ip/préfixe »), ou null si absente, invalide ou hôte seul. */
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_LITERAL = Regex("""^[0-9a-fA-F:]+$""")

    @JvmStatic
    fun networkOf(cidr: String): String? {
        val parts = cidr.trim().split("/")
        if (parts.size != 2) return null
        val ip = parts[0]
        // Littéraux seulement : InetAddress.getByName résoudrait un nom par DNS.
        if (!IPV4_LITERAL.matches(ip) && !(ip.contains(':') && IPV6_LITERAL.matches(ip))) return null
        val bytes = try { java.net.InetAddress.getByName(ip).address } catch (e: Exception) { return null }
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix <= 0 || prefix >= bytes.size * 8) return null
        for (i in bytes.indices) {
            val keep = (prefix - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
        }
        val net = java.net.InetAddress.getByAddress(bytes).hostAddress ?: return null
        return "$net/$prefix"
    }

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
        setError(null)

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
                    setError("NetBird non configuré")
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
                setError("Could not create NetBird packet socket")
                return false
            }
            Android.setSocketpairTunWriteFd(dupTun(vpnFd).toLong())
            c.renewTun(rx.toLong()) // le moteur prend possession de rx
            return true
        } catch (e: Throwable) {
            setError("NetBird renewTun failed: ${e.message ?: e.javaClass.simpleName}")
            Log.e(TAG, "renewTun", e)
            return false
        }
    }

    /**
     * Crée le client une fois. NewClient installe la protection des sockets du moteur : sans
     * elle, Auth (connexion par clé ou SSO) ne peut pas joindre le serveur de management.
     */
    @JvmStatic
    fun ensureClient(ctx: Context) {
        synchronized(lock) { if (client == null) client = createClient(ctx.applicationContext) }
    }

    private fun dupTun(pfd: ParcelFileDescriptor): Int =
        ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()

    private fun createClient(ctx: Context): Client =
        newClientWithListener(ctx)

    private fun newClientWithListener(ctx: Context): Client {
        val c = Android.newClient(
            Build.VERSION.SDK_INT.toLong(),
            Build.MODEL ?: "android",
            "trackercontrol-netbird",
            tunAdapter,
            ifaceDiscover,
            networkListener
        )
        c.setConnectionListener(connectionListener)
        return c
    }

    private fun startThread(c: Client, ctx: Context) {
        val dir = File(ctx.filesDir, "netbird").apply { mkdirs() }
        val files = object : PlatformFiles {
            override fun configurationFilePath(): String = File(dir, "netbird.cfg").absolutePath
            override fun stateFilePath(): String = File(dir, "state.json").absolutePath
            override fun cacheDir(): String = ctx.cacheDir.absolutePath
        }
        val dnsList = underlyingDnsList(ctx)
        val appCtx = ctx.applicationContext
        stopRequested = false
        val t = Thread({
            var ownInspector: DnsPacketInspector? = null
            try {
                Log.i(TAG, "moteur NetBird demarre")
                ownInspector = installDnsInspector(ctx)
                try { c.setNetworkAvailable(Util.isConnected(ctx)) } catch (e: Throwable) {
                    Log.w(TAG, "setNetworkAvailable", e)
                }
                c.runWithoutLogin(files, dnsList, object : DnsReadyListener {
                    override fun onReady() {}
                }, Android.newEnvList())
            } catch (e: Throwable) {
                setError("NetBird: ${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "runWithoutLogin", e)
            } finally {
                Log.i(TAG, "moteur NetBird arrete")
                ownInspector?.let { removeDnsInspector(it) }
                val unexpected = synchronized(lock) {
                    val own = thread === Thread.currentThread()
                    if (own) tunConfigured = false
                    own && !stopRequested
                }
                setConnState(STATE_DISCONNECTED)
                if (unexpected) onEngineExitedUnexpectedly(appCtx)
            }
        }, "netbird-engine")
        synchronized(lock) {
            thread = t
            tunConfigured = false
        }
        setConnState(STATE_CONNECTING)
        t.start()
    }

    /** Le moteur est sorti sans que stop() l'ait demande : reconnexion manuelle ou relance avec backoff. */
    private fun onEngineExitedUnexpectedly(ctx: Context) {
        val err = lastError
        if (NetbirdReconnect.needsRelogin(err)) {
            Log.w(TAG, "session NetBird a refaire: $err")
            showReloginNotification(ctx)
            return
        }
        val delay = NetbirdReconnect.backoffMs(restartAttempt)
        restartAttempt++
        Log.w(TAG, "moteur NetBird arrete ($err), relance dans ${delay / 1000} s")
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delay)
    }

    private fun onRestartDue() {
        val ctx = synchronized(lock) { service } ?: return
        if (stopRequested || !prefs(ctx).getBoolean(PREF_ENABLED, false)) return
        scheduleReload()
    }

    private fun showReloginNotification(ctx: Context) {
        try {
            val i = Intent(ctx, ActivitySettings::class.java)
                .putExtra(ActivitySettings.EXTRA_OPEN_SCREEN, "screen_netbird")
            val pi = PendingIntentCompat.getActivity(ctx, NOTIFY_RELOGIN, i, PendingIntent.FLAG_UPDATE_CURRENT)
            val text = ctx.getString(R.string.msg_netbird_relogin_detail)
            val b = NotificationCompat.Builder(ctx, "notify")
                .setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(ctx.getString(R.string.msg_netbird_relogin_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setColor(ctx.resources.getColor(R.color.colorTrackerControl))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            Util.notify(ctx, NOTIFY_RELOGIN, NotificationCompat.BigTextStyle(b).bigText(text).build())
        } catch (e: Throwable) {
            Log.w(TAG, "notification de reconnexion", e)
        }
    }

    private var notifCtx: Context? = null

    private fun cancelReloginNotification() {
        val ctx = synchronized(lock) { service }?.applicationContext ?: notifCtx ?: return
        cancelReloginNotification(ctx)
    }

    /** Retire la notification « reconnexion necessaire » (connexion retablie, deconnexion). */
    @JvmStatic
    fun cancelReloginNotification(ctx: Context) {
        notifCtx = ctx.applicationContext
        try { NotificationManagerCompat.from(ctx).cancel(NOTIFY_RELOGIN) } catch (e: Throwable) {
            Log.w(TAG, "cancel notification", e)
        }
    }

    /**
     * Le reseau sous-jacent a change : previent le moteur (disponibilite, changement de reseau,
     * DNS de l'hote). Hors thread principal, les appels Go pouvant bloquer.
     */
    fun onUnderlyingNetworkChanged(ctx: Context) {
        val appCtx = ctx.applicationContext
        val c = synchronized(lock) { if (thread?.isAlive == true) client else null } ?: return
        networkExecutor.execute {
            try {
                val connected = Util.isConnected(appCtx)
                c.setNetworkAvailable(connected)
                if (connected) {
                    c.notifyNetworkChange()
                    c.onUpdatedHostDNS(underlyingDnsList(appCtx))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "onUnderlyingNetworkChanged", e)
            }
        }
    }

    /** Serveurs DNS du reseau sous-jacent (hors VPN), sans l'adresse NetBird. */
    private fun underlyingDnsList(ctx: Context): DNSList {
        val list = DNSList()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val own = prefs(ctx).getString(PREF_ADDRESS, "").orEmpty().substringBefore("/")
            val seen = LinkedHashSet<String>()
            val nets = cm.allNetworks.sortedByDescending { it == cm.activeNetwork }
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                for (a in cm.getLinkProperties(n)?.dnsServers.orEmpty()) {
                    val ip = a.hostAddress?.substringBefore("%") ?: continue
                    if (ip != own) seen.add(ip)
                }
            }
            for (ip in seen) list.add(ip)
            Log.i(TAG, "DNS sous-jacents: ${seen.size}")
        } catch (e: Throwable) {
            Log.w(TAG, "DNS sous-jacents", e)
        }
        return list
    }

    private fun installDnsInspector(ctx: Context): DnsPacketInspector {
        val vpn = synchronized(lock) { service }
        val recorder = object : DnsRecorder {
            override fun recordDns(qname: String, aname: String, resource: String, ttl: Int) {
                val s = vpn
                if (s is ServiceSinkhole) s.wireGuardDnsResolved(qname, aname, resource, ttl)
            }
        }
        val inspector = DnsPacketInspector(recorder)
        val old = synchronized(lock) { dnsInspector.also { dnsInspector = inspector } }
        Android.setInboundDNSInspector(object : DNSInspector {
            override fun inspectInboundDNS(packet: ByteArray?): ByteArray? =
                if (packet == null) null else inspector.inspect(packet)
        })
        old?.close()
        return inspector
    }

    /** Retire l'inspecteur seulement s'il est encore celui de ce moteur (un nouveau a pu le remplacer). */
    private fun removeDnsInspector(own: DnsPacketInspector) {
        val current = synchronized(lock) {
            (dnsInspector === own).also { if (it) dnsInspector = null }
        }
        if (current) Android.setInboundDNSInspector(null)
        own.close()
    }

    /** Arret idempotent. Ne tient jamais le verrou en attendant le thread du moteur. */
    fun stop(stopSocketpair: () -> Unit) {
        stopRequested = true
        mainHandler.removeCallbacks(restartRunnable)
        val c: Client?
        val t: Thread?
        synchronized(lock) {
            c = client
            t = thread
            thread = null
            tunPfd = null
            tunConfigured = false
        }
        if (t != null) setConnState(STATE_DISCONNECTED)
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

    private fun applyPolicy(routes: String, address: String, address6: String) {
        Wgbridge.setPolicyAllowedIps(effectiveRoutes(routes, address, address6).joinToString(";"))
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

            applyPolicy(r, a, a6)

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
                setError("NetBird configureInterface: ${e.message ?: e.javaClass.simpleName}")
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
        // Sur Android le moteur passe "" : c'est un signal, les routes se relisent par
        // GetTunSettings (comme le fait le client NetBird amont).
        override fun onNetworkChanged(ignored: String?) {
            val (ctx, c) = synchronized(lock) { service to client }
            if (ctx == null || c == null) return
            val settings = try { c.tunSettings } catch (e: Throwable) {
                Log.w(TAG, "getTunSettings: ${e.message}")
                return
            }
            val r = settings.routes ?: ""
            val sd = settings.searchDomains ?: ""
            val p = prefs(ctx)
            try {
                applyPolicy(r, p.getString(PREF_ADDRESS, "") ?: "", p.getString(PREF_ADDRESS6, "") ?: "")
            } catch (e: Throwable) { Log.w(TAG, "policy", e) }
            val changed = p.getString(PREF_ROUTES, "") != r || p.getString(PREF_SEARCH_DOMAINS, "") != sd
            p.edit().putString(PREF_ROUTES, r).putString(PREF_SEARCH_DOMAINS, sd).apply()
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
