package fr.proximo.app.core

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** Carte d'identité d'un téléphone Proximo : ce qu'on échange pour s'ajouter en contact. */
data class Card(
    val id: String,          // identifiant du nœud : 16 caractères hexa (8 octets)
    val name: String,        // pseudo
    val dhPub: ByteArray,    // clé publique X25519
    val sigPub: ByteArray,   // clé publique Ed25519
    val time: Long,
    val sig: ByteArray,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("n", name)
        .put("dh", B64.enc(dhPub)).put("sg", B64.enc(sigPub))
        .put("t", time).put("s", B64.enc(sig))

    fun toQr(): String = "PRX1:" + B64.enc(toJson().toString().toByteArray())

    /** Code court lisible : PRX-XXXX-XXXX, dérivé de la clé de signature. */
    val shortCode: String get() = Identity.shortCodeOf(sigPub)

    fun verify(): Boolean = Identity.idOf(sigPub) == id && Crypto.verify(sigPub, Identity.signedBytes(id, name, dhPub, sigPub, time), sig)

    companion object {
        fun fromJson(o: JSONObject): Card? = try {
            Card(o.getString("id"), o.getString("n"), B64.dec(o.getString("dh")), B64.dec(o.getString("sg")), o.getLong("t"), B64.dec(o.getString("s")))
                .takeIf { it.verify() && it.dhPub.size == 32 && it.sigPub.size == 32 && it.name.isNotBlank() }
        } catch (e: Exception) { null }

        fun fromQr(text: String): Card? {
            val t = text.trim()
            if (!t.startsWith("PRX1:")) return null
            return try { fromJson(JSONObject(String(B64.dec(t.substring(5))))) } catch (e: Exception) { null }
        }
    }
}

object B64 {
    fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}

object Hex {
    private const val D = "0123456789abcdef"
    fun enc(b: ByteArray): String = buildString(b.size * 2) { for (x in b) { append(D[(x.toInt() shr 4) and 15]); append(D[x.toInt() and 15]) } }
    fun dec(s: String): ByteArray = ByteArray(s.length / 2) { i -> ((D.indexOf(s[2 * i].lowercaseChar()) shl 4) or D.indexOf(s[2 * i + 1].lowercaseChar())).toByte() }
}

/** Mon identité : clés et pseudo, rangées dans le stockage privé de l'appli. */
class Identity private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val sigPriv: ByteArray
    val sigPub: ByteArray
    val dhPriv: ByteArray
    val dhPub: ByteArray
    val id: String

    init {
        var s = prefs.getString("sig", null)
        var d = prefs.getString("dh", null)
        if (s == null || d == null) {
            s = B64.enc(Crypto.newSigningKey()); d = B64.enc(Crypto.newDhKey())
            prefs.edit().putString("sig", s).putString("dh", d).putLong("created", System.currentTimeMillis()).apply()
        }
        sigPriv = B64.dec(s); dhPriv = B64.dec(d)
        sigPub = Crypto.signingPublic(sigPriv); dhPub = Crypto.dhPublic(dhPriv)
        id = idOf(sigPub)
    }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", v.trim().take(24)).apply() }

    val isReady: Boolean get() = name.isNotBlank()
    val shortCode: String get() = shortCodeOf(sigPub)

    fun card(): Card {
        val t = System.currentTimeMillis()
        val sig = Crypto.sign(sigPriv, signedBytes(id, name, dhPub, sigPub, t))
        return Card(id, name, dhPub, sigPub, t, sig)
    }

    companion object {
        @Volatile private var inst: Identity? = null
        fun get(ctx: Context): Identity = inst ?: synchronized(this) { inst ?: Identity(ctx.applicationContext).also { inst = it } }

        fun idOf(sigPub: ByteArray): String = Hex.enc(Crypto.sha256(sigPub).copyOfRange(0, 8))
        fun idBytes(id: String): ByteArray = Hex.dec(id)

        private const val ALPHA = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        fun shortCodeOf(sigPub: ByteArray): String {
            val h = Crypto.sha256("code".toByteArray(), sigPub)
            val sb = StringBuilder()
            for (i in 0 until 8) sb.append(ALPHA[(h[i].toInt() and 0xff) % ALPHA.length])
            return "PRX-" + sb.substring(0, 4) + "-" + sb.substring(4, 8)
        }
        fun normalizeCode(s: String): String {
            val up = s.uppercase().replace("O", "0").replace("I", "1").replace("L", "1").filter { it.isLetterOrDigit() }
            val body = if (up.startsWith("PRX")) up.substring(3) else up
            return if (body.length == 8) "PRX-" + body.substring(0, 4) + "-" + body.substring(4) else ""
        }
        fun signedBytes(id: String, name: String, dhPub: ByteArray, sigPub: ByteArray, t: Long): ByteArray =
            (id + "|" + name + "|" + t + "|").toByteArray() + dhPub + sigPub
    }
}
