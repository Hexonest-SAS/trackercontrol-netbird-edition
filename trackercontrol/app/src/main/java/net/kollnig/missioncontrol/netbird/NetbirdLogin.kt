package net.kollnig.missioncontrol.netbird

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import eu.faircode.netguard.ServiceSinkhole
import io.netbird.gomobile.android.Auth
import io.netbird.gomobile.android.ErrListener
import io.netbird.gomobile.android.URLOpener
import net.kollnig.missioncontrol.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connexion a NetBird (cle d'installation ou SSO) et deconnexion. Les appels Auth sont bloquants
 * cote Go : ils tournent toujours dans un thread dedie, jamais sur le thread UI. La cle
 * d'installation n'est jamais journalisee.
 */
object NetbirdLogin {
    private const val TAG = "NetbirdLogin"
    const val PREF_MANAGEMENT_URL = "netbird_management_url"
    const val DEFAULT_MANAGEMENT_URL = "https://netbird.hexonest.fr"

    private val ui = Handler(Looper.getMainLooper())

    fun managementUrl(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_MANAGEMENT_URL, DEFAULT_MANAGEMENT_URL)
            .orEmpty().trim().ifEmpty { DEFAULT_MANAGEMENT_URL }

    private fun netbirdDir(ctx: Context): File = File(ctx.filesDir, "netbird").apply { mkdirs() }

    private fun toast(ctx: Context, msg: String) =
        ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    private fun onLoggedIn(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(NetbirdEgress.PREF_ENABLED, true).apply()
        ui.post {
            try {
                ServiceSinkhole.reload("netbird login", ctx, false)
            } catch (e: Throwable) {
                Log.w(TAG, "reload", e)
            }
            Toast.makeText(ctx, R.string.msg_netbird_login_ok, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Auth écrit sa configuration avant même de joindre le management. Elle travaille donc sur
     * netbird.cfg.pending (copie de la configuration existante, pour garder les clés en cas de
     * reconnexion), qui ne devient netbird.cfg qu'en cas de succès : un échec, ou l'app tuée
     * en plein flux SSO, ne laisse jamais une configuration non enregistrée passer pour valide.
     */
    private fun preparePending(ctx: Context): Pair<File, File> {
        val dir = netbirdDir(ctx)
        val cfg = File(dir, "netbird.cfg")
        val pending = File(dir, "netbird.cfg.pending")
        pending.delete()
        if (cfg.exists()) cfg.copyTo(pending, overwrite = true)
        return cfg to pending
    }

    private fun dropPending(pending: File) {
        if (pending.exists() && !pending.delete())
            Log.w(TAG, "could not delete netbird.cfg.pending")
    }

    private fun loginFailed(ctx: Context, pending: File, e: Throwable) {
        dropPending(pending)
        Log.w(TAG, "login failed: ${e.javaClass.simpleName}")
        toast(ctx, ctx.getString(R.string.msg_netbird_login_failed, e.message ?: e.javaClass.simpleName))
    }

    private fun listener(ctx: Context, cfg: File, pending: File): ErrListener {
        val done = AtomicBoolean(false)
        return object : ErrListener {
            override fun onError(e: Exception) {
                if (done.compareAndSet(false, true)) loginFailed(ctx, pending, e)
            }

            override fun onSuccess() {
                if (!done.compareAndSet(false, true)) return
                if (!pending.renameTo(cfg)) {
                    loginFailed(ctx, pending, IllegalStateException("could not save NetBird configuration"))
                    return
                }
                onLoggedIn(ctx)
            }
        }
    }

    fun loginWithSetupKey(context: Context, setupKey: String) {
        val ctx = context.applicationContext
        val key = setupKey.trim()
        if (key.isEmpty()) return
        val url = managementUrl(ctx)
        Thread({
            val (cfg, pending) = preparePending(ctx)
            try {
                NetbirdEgress.ensureClient(ctx)
                Auth(pending.absolutePath, url, null)
                    .loginWithSetupKeyAndSaveConfig(listener(ctx, cfg, pending), key, Build.MODEL ?: "android")
            } catch (e: Throwable) {
                loginFailed(ctx, pending, e)
            }
        }, "netbird-login").start()
    }

    fun loginWithSso(context: Context) {
        val ctx = context.applicationContext
        val url = managementUrl(ctx)
        val opener = object : URLOpener {
            override fun open(url: String, userCode: String) {
                ui.post {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Throwable) {
                        Toast.makeText(ctx, R.string.msg_netbird_no_browser, Toast.LENGTH_LONG).show()
                    }
                    if (userCode.isNotEmpty())
                        Toast.makeText(ctx, ctx.getString(R.string.msg_netbird_user_code, userCode),
                            Toast.LENGTH_LONG).show()
                }
            }

            override fun onLoginSuccess() {}
        }
        Thread({
            val (cfg, pending) = preparePending(ctx)
            try {
                NetbirdEgress.ensureClient(ctx)
                Auth(pending.absolutePath, url, null).login(listener(ctx, cfg, pending), opener, false)
            } catch (e: Throwable) {
                loginFailed(ctx, pending, e)
            }
        }, "netbird-sso").start()
    }

    fun logout(context: Context) {
        val ctx = context.applicationContext
        val dir = File(ctx.filesDir, "netbird")
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(NetbirdEgress.PREF_ENABLED, false).apply()
        try {
            ServiceSinkhole.reload("netbird logout", ctx, false)
        } catch (e: Throwable) {
            Log.w(TAG, "reload", e)
        }
        Thread({
            // Attend que le reload ait arrete le moteur (borne a 10 s) avant de supprimer sa config.
            val deadline = System.currentTimeMillis() + 10_000
            try {
                while (NetbirdEgress.isRunning() && System.currentTimeMillis() < deadline)
                    Thread.sleep(100)
            } catch (_: InterruptedException) {}
            if (NetbirdEgress.isRunning()) Log.w(TAG, "moteur NetBird encore actif, config supprimee quand meme")
            File(dir, "netbird.cfg").delete()
            File(dir, "state.json").delete()
            File(dir, "netbird.cfg.pending").delete()
        }, "netbird-logout").start()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(NetbirdEgress.PREF_ADDRESS)
            .remove(NetbirdEgress.PREF_ADDRESS6)
            .remove(NetbirdEgress.PREF_ROUTES)
            .remove(NetbirdEgress.PREF_DNS)
            .remove(NetbirdEgress.PREF_SEARCH_DOMAINS)
            .remove(NetbirdEgress.PREF_MTU)
            .apply()
    }
}
