package net.kollnig.missioncontrol.netbird

/** Regles pures de la reconnexion automatique du moteur NetBird (testables sans Android). */
object NetbirdReconnect {
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    private val RELOGIN_MARKERS = listOf("permissiondenied", "unauthenticated", "login", "token")

    /** Vrai si l'erreur indique une session a refaire (relancer le moteur serait inutile). */
    @JvmStatic
    fun needsRelogin(error: String?): Boolean {
        val e = error?.lowercase() ?: return false
        return RELOGIN_MARKERS.any { e.contains(it) }
    }

    /** Delai avant la relance numero `attempt` (0 = premiere) : 30 s, 60 s, 120 s, ... plafonne a 5 min. */
    @JvmStatic
    fun backoffMs(attempt: Int): Long {
        if (attempt < 0) return BASE_DELAY_MS
        if (attempt >= 4) return MAX_DELAY_MS // 30 s * 2^4 = 480 s > plafond
        return minOf(BASE_DELAY_MS shl attempt, MAX_DELAY_MS)
    }
}
