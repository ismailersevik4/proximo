package fr.proximo.app.core

import android.content.Context
import fr.proximo.app.mesh.Modes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Réglages de l'appli (stockage privé). */
class Settings private constructor(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0L)
    private fun bump() { changes.update { it + 1 } }

    /** Mode de connexion : NORMAL, PORTEE, BATTERIE. */
    var mode: Int
        get() = p.getInt("mode", Modes.NORMAL)
        set(v) { p.edit().putInt("mode", v).apply(); bump() }

    /** Lecture automatique des vocaux (talkie-walkie). */
    var autoPlayVoice: Boolean
        get() = p.getBoolean("autoPlay", true)
        set(v) { p.edit().putBoolean("autoPlay", v).apply(); bump() }

    /** Accepter les messages privés de téléphones qui ne sont pas dans mes contacts. */
    var allowUnknownDm: Boolean
        get() = p.getBoolean("unknownDm", false)
        set(v) { p.edit().putBoolean("unknownDm", v).apply(); bump() }

    /** Envoyer un accusé de lecture (« Lu ») quand j'ouvre une discussion privée. */
    var readReceipts: Boolean
        get() = p.getBoolean("readReceipts", true)
        set(v) { p.edit().putBoolean("readReceipts", v).apply(); bump() }

    /** Mode relais fixe : ce téléphone ne fait que relayer (aucune sonnerie, aucune notification, portée maximale). */
    var relayMode: Boolean
        get() = p.getBoolean("relayMode", false)
        set(v) { p.edit().putBoolean("relayMode", v).apply(); bump() }

    /** Brouillon d'une discussion (texte tapé mais pas envoyé). */
    fun draft(chat: String): String = p.getString("draft_$chat", "") ?: ""
    fun setDraft(chat: String, text: String) { if (text.isBlank()) p.edit().remove("draft_$chat").apply() else p.edit().putString("draft_$chat", text.take(4000)).apply() }

    /** Sons de notification. */
    var sounds: Boolean
        get() = p.getBoolean("sounds", true)
        set(v) { p.edit().putBoolean("sounds", v).apply(); bump() }

    /** Démarrer le réseau au démarrage du téléphone. */
    var autoStart: Boolean
        get() = p.getBoolean("autoStart", true)
        set(v) { p.edit().putBoolean("autoStart", v).apply(); bump() }

    /** Partager automatiquement la mise à jour de l'appli aux voisins qui ont une version plus ancienne. */
    var shareUpdates: Boolean
        get() = p.getBoolean("shareUpdates", true)
        set(v) { p.edit().putBoolean("shareUpdates", v).apply(); bump() }

    /** L'écran « économie d'énergie » a déjà été proposé. */
    var batteryAsked: Boolean
        get() = p.getBoolean("batteryAsked", false)
        set(v) { p.edit().putBoolean("batteryAsked", v).apply(); bump() }

    /** Réseau arrêté à la main depuis les réglages (ne pas relancer tout seul). */
    var stoppedManually: Boolean
        get() = p.getBoolean("stopped", false)
        set(v) { p.edit().putBoolean("stopped", v).apply(); bump() }

    /** Heure du dernier « Plus tard » sur une offre de mise à jour. */
    var lastOfferedAt: Long
        get() = p.getLong("lastOfferedAt", 0L)
        set(v) { p.edit().putLong("lastOfferedAt", v).apply() }

    /** Dernière version proposée (pour ne pas redemander). */
    var lastOfferedVersion: Int
        get() = p.getInt("lastOffered", 0)
        set(v) { p.edit().putInt("lastOffered", v).apply() }

    /** Volume d'alarme à remettre après un wizz (-1 = rien à remettre) ; gardé sur disque au cas où l'appli s'arrête pendant la sonnerie. */
    var wizzRestoreVolume: Int
        get() = p.getInt("wizzRestoreVolume", -1)
        set(v) { p.edit().putInt("wizzRestoreVolume", v).apply() }

    companion object {
        @Volatile private var inst: Settings? = null
        fun get(ctx: Context): Settings = inst ?: synchronized(this) { inst ?: Settings(ctx.applicationContext).also { inst = it } }
    }
}
