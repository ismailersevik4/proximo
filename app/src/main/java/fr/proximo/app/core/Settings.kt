package fr.proximo.app.core

import android.content.Context
import fr.proximo.app.mesh.Modes
import kotlinx.coroutines.flow.MutableStateFlow

/** Réglages de l'appli (stockage privé). */
class Settings private constructor(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0L)
    private fun bump() { changes.value = changes.value + 1 }

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

    /** Dernière version proposée (pour ne pas redemander). */
    var lastOfferedVersion: Int
        get() = p.getInt("lastOffered", 0)
        set(v) { p.edit().putInt("lastOffered", v).apply() }

    companion object {
        @Volatile private var inst: Settings? = null
        fun get(ctx: Context): Settings = inst ?: synchronized(this) { inst ?: Settings(ctx.applicationContext).also { inst = it } }
    }
}
