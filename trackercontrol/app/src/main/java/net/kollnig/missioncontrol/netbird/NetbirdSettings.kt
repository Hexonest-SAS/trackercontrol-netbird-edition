package net.kollnig.missioncontrol.netbird

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.kollnig.missioncontrol.R

/**
 * Ecran de reglages NetBird (etat, interrupteur, cle d'installation, SSO, deconnexion).
 * Tout le code NetBird d'ActivitySettings vit ici : le fichier d'origine ne garde que de
 * petits points d'accroche, pour que les mises a jour de TrackerControl fusionnent sans conflit.
 */
object NetbirdSettings {
    /** Extra optionnel : cle d'un sous-ecran de reglages a ouvrir directement (ex. "screen_netbird"). */
    const val EXTRA_OPEN_SCREEN = "open_screen"

    /** Fragment initial : ouvre directement le sous-ecran demande par EXTRA_OPEN_SCREEN, s'il y en a un. */
    @JvmStatic
    fun withOpenScreen(activity: AppCompatActivity, fragment: Fragment): Fragment {
        val openScreen = activity.intent.getStringExtra(EXTRA_OPEN_SCREEN)
        if (openScreen != null) {
            val args = Bundle()
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, openScreen)
            fragment.arguments = args
        }
        return fragment
    }

    /**
     * Reaction a un changement de preference. Retourne true quand l'appelant doit en plus
     * rafraichir le mode recherche et recharger le service (interrupteur NetBird).
     */
    @JvmStatic
    fun onPreferenceChanged(activity: AppCompatActivity, name: String?): Boolean {
        if (NetbirdEgress.PREF_ENABLED == name) {
            updateStatus(activity)
            return true
        }
        if (NetbirdLogin.PREF_MANAGEMENT_URL == name || NetbirdEgress.PREF_ADDRESS == name)
            updateStatus(activity)
        return false
    }

    @JvmStatic
    fun configure(activity: AppCompatActivity, screen: PreferenceScreen) {
        screen.findPreference<Preference>("netbird_login_key")
            ?.setOnPreferenceClickListener {
                val input = EditText(activity)
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.setHint(R.string.msg_netbird_setup_key_hint)
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.setting_netbird_login_key)
                    .setView(input)
                    .setPositiveButton(R.string.msg_netbird_ok) { _, _ ->
                        NetbirdLogin.loginWithSetupKey(activity, input.text.toString())
                    }
                    .setNegativeButton(R.string.msg_netbird_cancel, null)
                    .show()
                true
            }
        screen.findPreference<Preference>("netbird_login_sso")
            ?.setOnPreferenceClickListener {
                NetbirdLogin.loginWithSso(activity)
                true
            }
        screen.findPreference<Preference>("netbird_logout")
            ?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.msg_netbird_logout_confirm)
                    .setPositiveButton(R.string.msg_netbird_ok) { _, _ ->
                        NetbirdLogin.logout(activity)
                        currentScreen(activity)
                            ?.findPreference<eu.faircode.netguard.SwitchPreference>("netbird_enabled")
                            ?.isChecked = false
                        updateStatus(activity)
                    }
                    .setNegativeButton(R.string.msg_netbird_cancel, null)
                    .show()
                true
            }
        screen.findPreference<Preference>(NetbirdLogin.PREF_MANAGEMENT_URL)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val v = if (newValue is String) newValue.trim() else ""
                if (!NetbirdLogin.isValidManagementUrl(v)) {
                    Toast.makeText(activity, R.string.msg_netbird_invalid_url, Toast.LENGTH_LONG).show()
                    false
                } else true
            }
        updateStatus(activity)
    }

    private fun currentScreen(activity: AppCompatActivity): PreferenceScreen? =
        (activity.supportFragmentManager.findFragmentById(R.id.settings_container)
                as? PreferenceFragmentCompat)?.preferenceScreen

    private fun statusSummary(ctx: Context, prefs: SharedPreferences): CharSequence {
        if (!NetbirdEgress.configFile(ctx).exists())
            return ctx.getString(R.string.summary_netbird_status_unconfigured)
        if (!prefs.getBoolean(NetbirdEgress.PREF_ENABLED, false))
            return ctx.getString(R.string.summary_netbird_status_off)
        val error = NetbirdEgress.getLastError()
        if (!TextUtils.isEmpty(error))
            return ctx.getString(R.string.summary_netbird_status_error, error)
        if (NetbirdEgress.isRunning()) {
            val addr = prefs.getString(NetbirdEgress.PREF_ADDRESS, "")
            return if (TextUtils.isEmpty(addr)) ctx.getString(R.string.summary_netbird_status_starting)
            else ctx.getString(R.string.summary_netbird_status_connected, addr)
        }
        return ctx.getString(R.string.summary_netbird_status_starting)
    }

    @JvmStatic
    fun updateStatus(activity: AppCompatActivity) {
        val screen = currentScreen(activity) ?: return
        val summary = statusSummary(activity, PreferenceManager.getDefaultSharedPreferences(activity))
        screen.findPreference<Preference>("screen_netbird")?.summary = summary
        screen.findPreference<Preference>("netbird_status")?.summary = summary
        screen.findPreference<Preference>(NetbirdLogin.PREF_MANAGEMENT_URL)
            ?.summary = activity.getString(R.string.summary_netbird_management_url,
                NetbirdLogin.managementUrl(activity))
    }
}
