package net.kollnig.missioncontrol.netbird

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import eu.faircode.netguard.ActivitySettings
import eu.faircode.netguard.ServiceSinkhole
import net.kollnig.missioncontrol.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Section NetBird de l'onglet VPN (VpnFragment) : carte d'etat en tete puis une ligne par pair.
 * Quand NetBird est active, la carte WireGuard est masquee et ces lignes la remplacent.
 * Toute la logique vit ici pour que VpnFragment (fichier d'origine) ne garde que des points d'accroche.
 *
 * Appels depuis le thread principal uniquement.
 */
class NetbirdVpnSection(
    private val fragment: Fragment,
    private val prefs: SharedPreferences,
    private val executor: ExecutorService,
    /** Rafraichit toute la liste (VpnFragment.refreshUi). */
    private val refreshUi: Runnable,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pairs mis en cache (lus hors du thread principal).
    @Volatile
    private var peers: List<NetbirdEgress.NetbirdPeer> = emptyList()
    private var visible = false

    private val stateListener = Runnable { refresh() }
    private val ticker = object : Runnable {
        override fun run() {
            if (!visible) return
            if (isEnabled()) refresh()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    fun isEnabled(): Boolean = prefs.getBoolean(NetbirdEgress.PREF_ENABLED, false)

    /** Nombre de lignes NetBird en tete : carte + un pair par ligne, ou 0 si NetBird est desactive. */
    fun rows(): Int = if (isEnabled()) 1 + peers.size else 0

    /**
     * Decalage a appliquer aux positions de la liste d'origine : les lignes NetBird remplacent
     * la carte d'etat WireGuard (une ligne), donc rows() - 1 lignes de plus.
     */
    fun shift(): Int = maxOf(rows() - 1, 0)

    fun ownsPosition(position: Int): Boolean = position < rows()

    fun viewTypeAt(position: Int): Int = if (position == 0) TYPE_CARD else TYPE_PEER

    fun ownsViewType(viewType: Int): Boolean = viewType == TYPE_CARD || viewType == TYPE_PEER

    fun createHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CARD)
            CardHolder(inflater.inflate(R.layout.item_vpn_status, parent, false))
        else
            PeerHolder(inflater.inflate(R.layout.item_vpn_netbird_peer, parent, false))
    }

    fun bind(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is CardHolder) bindCard(holder)
        else if (holder is PeerHolder) bindPeer(holder, peers[position - 1])
    }

    /** A appeler dans onResume : abonnement aux etats et rafraichissement periodique. */
    fun start() {
        NetbirdEgress.addStateListener(stateListener)
        visible = true
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    /** A appeler dans onPause. */
    fun stop() {
        NetbirdEgress.removeStateListener(stateListener)
        visible = false
        mainHandler.removeCallbacks(ticker)
    }

    /** Retourne true si la cle de preference concerne NetBird (et a ete traitee). */
    fun onPreferenceChanged(key: String?): Boolean {
        if (NetbirdEgress.PREF_ENABLED != key) return false
        refresh()
        return true
    }

    /** Relit les pairs hors du thread principal puis rafraichit la liste. */
    fun refresh() {
        if (!fragment.isAdded) return
        if (!isEnabled()) {
            peers = emptyList()
            refreshUi.run()
            return
        }
        try {
            executor.execute {
                val fresh = NetbirdEgress.peers()
                mainHandler.post {
                    peers = fresh
                    refreshUi.run()
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    private fun bindCard(holder: CardHolder) {
        val context = fragment.requireContext()
        val nb = NetbirdEgress
        val configured = nb.configFile(context).exists()
        val error = nb.getLastError()
        val state = nb.getConnectionState()
        val summary = if (!configured) {
            context.getString(R.string.vpn_netbird_not_configured)
        } else if (state == NetbirdEgress.STATE_CONNECTED) {
            val connected = peers.count { it.connStatus == 2 }
            val addr = if (nb.getAddress().isEmpty()) prefs.getString(NetbirdEgress.PREF_ADDRESS, "") else nb.getAddress()
            context.getString(R.string.vpn_netbird_connected, addr, connected, peers.size)
        } else if (!error.isNullOrEmpty()) {
            context.getString(R.string.vpn_netbird_error, error)
        } else if (state == NetbirdEgress.STATE_CONNECTING) {
            context.getString(R.string.vpn_netbird_connecting)
        } else {
            context.getString(R.string.vpn_netbird_disconnected)
        }
        holder.flag.visibility = View.GONE
        holder.title.setText(R.string.vpn_netbird_title)
        holder.summary.visibility = View.VISIBLE
        holder.summary.text = summary
        holder.summary.maxLines = 2
        holder.setTextStartMargin(8)

        holder.switchView.setOnCheckedChangeListener(null)
        holder.switchView.isChecked = true
        holder.switchView.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(NetbirdEgress.PREF_ENABLED, checked).apply()
            // Le listener de preferences d'ActivitySettings n'est actif que quand cet ecran est ouvert.
            ServiceSinkhole.reload("netbird toggle", fragment.requireContext(), false)
            refresh()
        }

        holder.settings.visibility = View.VISIBLE
        holder.settings.contentDescription = context.getString(R.string.vpn_netbird_settings)
        holder.settings.setOnClickListener {
            val i = Intent(fragment.requireContext(), ActivitySettings::class.java)
            i.putExtra(ActivitySettings.EXTRA_OPEN_SCREEN, "screen_netbird")
            fragment.startActivity(i)
        }
    }

    private fun bindPeer(holder: PeerHolder, p: NetbirdEgress.NetbirdPeer) {
        val context = fragment.requireContext()
        holder.name.text = p.name
        holder.ip.text = p.ip
        val status = p.connStatus
        val color: Int
        val state: String
        if (status == 2) {
            color = 0xFF2E9E4F.toInt()
            val sb = StringBuilder()
            if (p.relayed) sb.append(context.getString(R.string.vpn_netbird_peer_relayed))
            if (p.latency.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(p.latency)
            }
            state = sb.toString()
        } else if (status == 1) {
            color = 0xFFF29900.toInt()
            state = context.getString(R.string.vpn_netbird_peer_connecting)
        } else {
            color = 0xFF9E9E9E.toInt()
            state = context.getString(R.string.vpn_netbird_peer_idle)
        }
        holder.dot.setTextColor(color)
        holder.state.text = state
    }

    private class CardHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val flag: TextView = itemView.findViewById(R.id.vpnStatusFlag)
        val text: LinearLayout = itemView.findViewById(R.id.vpnStatusText)
        val title: TextView = itemView.findViewById(R.id.vpnStatusTitle)
        val summary: TextView = itemView.findViewById(R.id.vpnStatusSummary)
        val settings: ImageButton = itemView.findViewById(R.id.vpnSettingsButton)
        val switchView: MaterialSwitch = itemView.findViewById(R.id.vpnEnabledSwitch)

        fun setTextStartMargin(dp: Int) {
            val params = text.layoutParams as ViewGroup.MarginLayoutParams
            val margin = Math.round(dp * itemView.resources.displayMetrics.density)
            if (params.marginStart == margin) return
            params.marginStart = margin
            text.layoutParams = params
        }
    }

    private class PeerHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dot: TextView = itemView.findViewById(R.id.nbPeerDot)
        val name: TextView = itemView.findViewById(R.id.nbPeerName)
        val ip: TextView = itemView.findViewById(R.id.nbPeerIp)
        val state: TextView = itemView.findViewById(R.id.nbPeerState)
    }

    companion object {
        private const val REFRESH_MS = 5000L
        const val TYPE_CARD = 10
        const val TYPE_PEER = 11
    }
}
