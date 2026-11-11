package fr.proximo.app.msg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.proximo.app.MainActivity
import fr.proximo.app.R
import fr.proximo.app.core.B64
import fr.proximo.app.core.Card
import fr.proximo.app.core.Contact
import fr.proximo.app.core.Crypto
import fr.proximo.app.core.Db
import fr.proximo.app.core.Group
import fr.proximo.app.core.Hex
import fr.proximo.app.core.Identity
import fr.proximo.app.core.Message
import fr.proximo.app.core.Outbox
import fr.proximo.app.core.Settings
import fr.proximo.app.mesh.Frame
import fr.proximo.app.mesh.MeshCore
import fr.proximo.app.mesh.Peer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * La messagerie : fabrique les trames à partir de ce que fait l'utilisateur,
 * et range dans la base ce qui arrive du réseau.
 */
class Messenger(private val ctx: Context, private val core: MeshCore) {
    init {
        // L'appli s'est arrêtée pendant la sonnerie d'un wizz : le volume d'alarme est remis comme il était.
        try {
            val v = Settings.get(ctx).wizzRestoreVolume
            if (v >= 0) { (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0); Settings.get(ctx).wizzRestoreVolume = -1 }
        } catch (_: Exception) {}
    }
    companion object {
        /** Un wizz toutes les 30 s au plus vers un même contact. */
        const val WIZZ_EVERY_MS = 30_000L
        /** Au-delà de 10 min de retard, un wizz s'affiche sans sonner. */
        const val WIZZ_FRESH_MS = 10 * 60_000L
        /** Côté réception : pas deux sonneries du même contact à moins de 20 s. */
        const val WIZZ_RING_GAP_MS = 20_000L
        private const val GINV = "ginv"
        private const val CTX_ALL = "all"; private const val CTX_DM = "dm:"; private const val CTX_GROUP = "grp:"
        private val HEX16 = Regex("^[0-9a-f]{16}$")
    }
    private val tag = "ProximoMsg"
    private val db = Db.get(ctx)
    private val me = Identity.get(ctx)
    private val settings = Settings.get(ctx)
    private val mediaDir = File(ctx.filesDir, "media").also { it.mkdirs() }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(tag, "tâche de fond", e) })
    /** Démarrage du réseau : tâches de fond de la messagerie (appelé une fois le service prêt). */
    fun start() { scope.launch { try { migrateToFormat2() } catch (e: Exception) { Log.e(tag, "passage au format 2", e) } } }
    /** Arrêt du réseau : plus aucune tâche de fond de cette messagerie. */
    fun stop() { try { scope.cancel() } catch (_: Exception) {} }

    /** Cartes (clés publiques) des téléphones vus, même pas encore contacts. */
    val cards = ConcurrentHashMap<String, Card>()
    /** Voisins pour qui on a demandé l'ajout avant d'avoir leur carte. */
    private val wantContact = ConcurrentHashMap.newKeySet<String>()
    /** Discussion ouverte à l'écran (pas de notification pour elle). */
    @Volatile var openChat: String? = null
        set(v) { field = v; if (v != null) try { NotificationManagerCompat.from(ctx).cancel(v.hashCode()) } catch (_: Exception) {} } // discussion ouverte : sa notification s'en va
    /** La discussion est-elle vraiment sous les yeux de l'utilisateur ? (ouverte ET appli au premier plan, écran allumé) */
    private fun isShowing(chat: String) = openChat == chat && MainActivity.isForeground
    /** Dernier événement à afficher en bandeau (ex. demande de contact). Le numéro évite qu'un même texte répété soit ignoré. */
    data class Event(val n: Long, val text: String)
    val events = MutableStateFlow<Event?>(null)
    private var eventCounter = 0L
    fun event(text: String) { events.value = Event(++eventCounter, text) }

    // =============== côté utilisateur ===============

    fun sendText(chat: String, text: String) = sendMessage(chat, Message.TEXT, text.trim().take(4000), null)
    fun sendPhoto(chat: String, jpeg: ByteArray) = sendMessage(chat, Message.PHOTO, "", jpeg)
    fun sendVoice(chat: String, audio: ByteArray, seconds: Int) = sendMessage(chat, Message.VOICE, seconds.toString(), audio)
    /** Vidéo courte : uniquement par Wi-Fi direct, un seul relais au maximum. */
    fun sendVideo(chat: String, mp4: ByteArray, seconds: Int) = sendMessage(chat, Message.VIDEO, seconds.toString(), mp4, flags = Frame.FLAG_WIFI_ONLY, ttl = 1) // ttl 1 = un seul relais (2 en permettait deux)

    /** Peut-on envoyer une vidéo vers cette discussion maintenant ? (il faut une prise Wi-Fi direct) */
    fun canSendVideo(chat: String): Boolean = when {
        chat == Db.ALL || db.group(chat) != null -> core.wifiLinkedPeers().isNotEmpty()
        else -> core.hasWifiLink(chat) || core.wifiLinkedPeers().isNotEmpty()
    }
    fun sendLocation(chat: String, lat: Double, lon: Double) = sendMessage(chat, Message.LOC, "$lat,$lon", null)

    // ---- wizz ----
    /** Un wizz à afficher à l'écran (la discussion ouverte tremble). */
    data class Wizz(val n: Long, val chat: String, val at: Long)
    val wizz = MutableStateFlow<Wizz?>(null)
    private var wizzCounter = 0L
    private val wizzSentAt = ConcurrentHashMap<String, Long>()
    private val wizzRungAt = ConcurrentHashMap<String, Long>()
    private fun shake(chat: String) { wizz.value = Wizz(++wizzCounter, chat, System.currentTimeMillis()) }

    /** Wizz : uniquement en privé, un toutes les 30 s par contact. Le texte « Wizz ! » reste lisible sur une ancienne version. */
    fun sendWizz(chat: String) {
        if (chat == Db.ALL || db.group(chat) != null) { event("Le wizz ne marche qu'en discussion privée"); return }
        if (db.contact(chat)?.accepted != true) { event("Le wizz marchera quand le contact aura accepté"); return }
        // Horloge « depuis l'allumage » : la limite de 30 s ne se dérègle pas si l'heure du téléphone change.
        val now = android.os.SystemClock.elapsedRealtime()
        val last = wizzSentAt[chat]
        val wait = if (last == null) 0L else WIZZ_EVERY_MS - (now - last)
        if (wait > 0) { event("Attends encore ${(wait + 999) / 1000} s avant un nouveau wizz"); return }
        wizzSentAt[chat] = now
        sendMessage(chat, Message.WIZZ, "Wizz !", null)
        shake(chat)
        event(if (core.peer(chat)?.linked == true) "Wizz envoyé" else "Wizz en attente : il ne sonnera que s'il arrive dans les 10 minutes")
    }

    /** SOS : à tout le monde, prioritaire, avec ma position si on l'a. */
    fun sendSos(text: String, lat: Double?, lon: Double?) {
        val body = JSONObject().put("t", text.trim().take(500)).apply { if (lat != null && lon != null) put("lat", lat).put("lon", lon) }.toString()
        sendMessage(Db.ALL, Message.SOS, body, null)
    }

    /** Chiffrement et écriture des fichiers hors du fil de l'interface (une vidéo de 4 Mo gèlerait l'écran). */
    private fun sendMessage(chat: String, kind: String, body: String, blob: ByteArray?, flags: Int = 0, ttl: Int = Frame.DEFAULT_TTL) {
        scope.launch { try { sendMessageNow(chat, kind, body, blob, flags, ttl) } catch (e: Exception) { Log.e(tag, "envoi", e); event("Envoi impossible : ${e.message}") } }
    }

    private fun sendMessageNow(chat: String, kind: String, body: String, blob: ByteArray?, flags: Int, ttl: Int) {
        val fid = Frame.newId()
        val mid = Hex.enc(fid)
        val time = System.currentTimeMillis()
        // Un contact passe toujours avant un groupe : une discussion privée ne peut jamais partir vers un groupe du même identifiant.
        val contact = if (chat == Db.ALL) null else db.contact(chat) ?: cards[chat]?.let { Contact(it.id, it.name, it.dhPub, it.sigPub, 0, 0, 0) }
        val group = if (chat == Db.ALL || contact != null) null else db.group(chat)
        // Le destinataire fait partie de ce qui est signé : « all », l'identifiant du groupe, ou celui du contact.
        // Le type est signé aussi (« dm: », « grp: », « all ») : un identifiant de groupe ne peut pas passer pour celui d'un téléphone.
        val ctx = when { chat == Db.ALL -> CTX_ALL; group != null -> CTX_GROUP + group.id; else -> CTX_DM + (contact?.id ?: chat) }
        // Vers un contact accepté, ma clé n'est pas jointe (il l'a déjà) : le message reste assez court pour un petit message Wi-Fi direct.
        val inner = inner(mid, kind, body, time, blob, ctx, attachKey = !(chat != Db.ALL && group == null && contact?.accepted == true))
        val frame: Frame? = when {
            chat == Db.ALL -> {
                val sig = Crypto.sign(me.sigPriv, fid + inner)
                Frame(if (kind == Message.SOS) Frame.SOS else Frame.PUBLIC, ttl, 0, flags, fid, core.myIdBytes(), Frame.BROADCAST, inner + sig)
            }
            group != null -> {
                val g = group
                Frame(Frame.GROUP, ttl, 0, flags, fid, core.myIdBytes(), Frame.BROADCAST, Hex.dec(g.id) + Crypto.aesEncrypt(g.key, inner, fid))
            }
            else -> {
                val c = contact
                if (c == null) { event("Impossible d'écrire : contact inconnu"); return }
                val to = Hex.dec(c.id)
                Frame(Frame.DM, ttl, 0, flags, fid, core.myIdBytes(), to, Crypto.sealFor(c.dhPub, inner, fid + core.myIdBytes() + to))
            }
        }
        val file = blob?.let { saveBlob(mid, kind, it) }
        // L'invitation de groupe est une trame technique : elle ne s'affiche pas comme un message (elle montrait la clé du groupe en clair).
        if (kind != GINV) db.saveMessage(Message(mid, chat, me.id, true, kind, body, file, time, Message.PENDING, true, ""))
        frame?.let { core.send(it) }
    }

    /** Payload en clair : [longueur json (2)][json][fichier]. Le json est signé par l'émetteur. */
    private fun inner(mid: String, kind: String, body: String, time: Long, blob: ByteArray?, ctxId: String, attachKey: Boolean = true): ByteArray {
        val blobHash = Hex.enc(Crypto.sha256(blob ?: ByteArray(0)))
        val name = me.name
        val sig = Crypto.sign(me.sigPriv, signedV2(mid, kind, body, time, blobHash, ctxId, name))
        // « sg » = ma clé publique : n'importe qui peut vérifier la signature (l'identifiant d'un téléphone est l'empreinte de cette clé).
        val json = JSONObject().put("v", 2).put("k", kind).put("b", body).put("t", time).put("n", name).put("s", B64.enc(sig)).apply { if (attachKey) put("sg", B64.enc(me.sigPub)) }.toString().toByteArray()
        require(json.size <= 0xFFFF) { "message trop long" }
        val b = ByteBuffer.allocate(2 + json.size + (blob?.size ?: 0))
        b.putShort(json.size.toShort()).put(json)
        if (blob != null) b.put(blob)
        return b.array()
    }

    private class Inner(val json: JSONObject, val blob: ByteArray?)
    private fun parseInner(data: ByteArray): Inner? = try {
        val b = ByteBuffer.wrap(data)
        val len = b.short.toInt() and 0xffff
        val js = ByteArray(len); b.get(js)
        val rest = if (b.remaining() > 0) ByteArray(b.remaining()).also { b.get(it) } else null
        Inner(JSONObject(String(js)), rest)
    } catch (e: Exception) { null }

    /**
     * Ce qui est signé (format 2) : chaque champ précédé de sa longueur, dont le DESTINATAIRE (identifiant du contact,
     * du groupe, ou « all ») et le pseudo. Un message signé pour quelqu'un ne peut donc plus être présenté à un autre.
     */
    private fun signedV2(mid: String, kind: String, body: String, time: Long, blobHash: String, ctxId: String, name: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bos.write("proximo-msg-v2".toByteArray())
        for (s in listOf(mid, kind, body, time.toString(), blobHash, ctxId, name)) { val b = s.toByteArray(); bos.write(ByteBuffer.allocate(4).putInt(b.size).array()); bos.write(b) }
        return bos.toByteArray()
    }

    /** ctxId = à qui le message doit être destiné pour être accepté (moi, le groupe, ou « all »). Les messages à l'ancien format sont refusés. */
    private fun verifyInner(mid: String, inn: Inner, sigPub: ByteArray?, ctxId: String): Boolean {
        if (sigPub == null) return false
        val j = inn.json
        if (j.optInt("v") < 2) return false
        val blobHash = Hex.enc(Crypto.sha256(inn.blob ?: ByteArray(0)))
        val data = signedV2(mid, j.optString("k"), j.optString("b"), j.optLong("t"), blobHash, ctxId, j.optString("n"))
        return try { Crypto.verify(sigPub, data, B64.dec(j.optString("s"))) } catch (e: Exception) { false }
    }

    /**
     * La clé de signature de l'expéditeur : celle du contact, de sa carte, ou celle jointe au message — acceptée seulement
     * si son empreinte est bien l'identifiant de l'expéditeur. Plus aucun message n'est accepté sans vérification.
     */
    private fun senderKey(from: String, inn: Inner): ByteArray? {
        db.contact(from)?.sigPub?.takeIf { it.size == 32 }?.let { return it }
        cards[from]?.sigPub?.let { return it }
        val sg = inn.json.optString("sg")
        if (sg.isEmpty()) return null
        val pub = try { B64.dec(sg) } catch (e: Exception) { return null }
        return pub.takeIf { it.size == 32 && Identity.idOf(it) == from }
    }

    private fun saveBlob(mid: String, kind: String, blob: ByteArray): String {
        val ext = when (kind) { Message.PHOTO -> "jpg"; Message.VOICE -> "m4a"; Message.VIDEO -> "mp4"; else -> "bin" }
        val f = File(mediaDir, "$mid.$ext"); f.writeBytes(blob); return f.absolutePath
    }

    // ---- contacts ----

    /** Ajouter un voisin vu sur le radar. */
    fun requestContact(peerId: String) {
        val card = cards[peerId]
        if (card == null) { wantContact.add(peerId); askCard(peerId); event("Demande en cours…"); return }
        addFromCard(card)
    }

    /** Ajouter depuis une carte (QR code ou carte reçue). */
    fun addFromCard(card: Card) {
        if (card.id == me.id) { event("C'est ton propre code"); return }
        val existing = db.contact(card.id)
        if (existing?.blocked == true) { event("${existing.name} est bloqué : débloque-le dans Contacts pour l'ajouter"); return }
        if (existing?.accepted == true) { event("${card.name} est déjà dans tes contacts"); return }
        if (existing?.status == 2) { acceptContact(card.id); return }
        db.saveContact(card, 0)
        core.send(Frame(Frame.CONTACT_REQ, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(card.id), me.card().toJson().toString().toByteArray()))
        event("Demande envoyée à ${card.name}")
    }

    /** Ajouter avec un code PRX-XXXX-XXXX : se fera dès que le téléphone est à portée. */
    fun addByCode(code: String): Boolean {
        val n = Identity.normalizeCode(code)
        if (n.isEmpty()) return false
        if (n == me.shortCode) { event("C'est ton propre code"); return true }
        cards.values.firstOrNull { it.shortCode == n }?.let { addFromCard(it); return true }
        db.addPendingCode(n)
        core.peers.value.forEach { askCard(it.id) }
        event("Code enregistré : l'ajout se fera dès que ce téléphone sera à portée")
        return true
    }

    fun acceptContact(id: String) {
        val c = db.contact(id) ?: return
        db.setContactStatus(id, 1)
        core.replayHeld(id)
        core.send(Frame(Frame.CONTACT_ACCEPT, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(id), me.card().toJson().toString().toByteArray()))
        db.saveMessage(Message(Hex.enc(Frame.newId()), id, me.id, true, Message.SYS, "Vous êtes maintenant en contact", null, System.currentTimeMillis(), Message.DELIVERED, true, ""))
    }
    fun refuseContact(id: String) { db.deleteContact(id) }

    /** Bloquer : plus rien de ce téléphone n'arrive (messages, demandes de contact, appels, wizz), sa discussion est cachée. */
    fun blockContact(id: String) {
        val name = db.contact(id)?.name ?: return
        db.setBlocked(id, true)
        try { NotificationManagerCompat.from(ctx).cancel(id.hashCode()) } catch (_: Exception) {}
        event("$name est bloqué. Tu peux le débloquer dans Contacts.")
    }
    fun unblockContact(id: String) {
        val name = db.contact(id)?.name ?: return
        db.setBlocked(id, false)
        // Ma demande de contact était partie avant le blocage et sa réponse a été ignorée : je la renvoie.
        if (db.contact(id)?.status == 0) cards[id]?.let { core.send(Frame(Frame.CONTACT_REQ, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(id), me.card().toJson().toString().toByteArray())) }
        event("$name est débloqué")
    }
    private fun isBlocked(id: String) = db.contact(id)?.blocked == true

    /** Trace d'un appel dans la discussion (« Appel manqué », « Appel sans réponse »), avec notification si demandé. */
    fun callNote(peerId: String, text: String, notifyUser: Boolean) {
        scope.launch {
            val c = db.contact(peerId)
            if (c?.blocked == true) return@launch
            val name = c?.name ?: cards[peerId]?.name ?: core.peer(peerId)?.name ?: "Inconnu"
            val now = System.currentTimeMillis()
            val showing = isShowing(peerId)
            db.saveMessage(Message(Hex.enc(Frame.newId()), peerId, peerId, false, Message.SYS, text, null, now, Message.DELIVERED, !notifyUser || showing, "", name, now))
            if (notifyUser && !showing) notify(name, text, peerId, false)
        }
    }

    /**
     * Une seule fois, au premier démarrage de la version « preuve d'identité » : mes messages privés encore en attente
     * étaient signés à l'ancien format, que les téléphones à jour refusent. Ils sont refaits au nouveau format,
     * et mes groupes sont réannoncés à leurs membres.
     */
    private fun migrateToFormat2() {
        if (db.kv("fmt2") != null) return
        val broadcast = Hex.enc(Frame.BROADCAST)
        // Du plus ancien au plus récent, pour qu'ils repartent et s'affichent dans l'ordre.
        for (o in db.outbox().sortedBy { it.created }) {
            if (!o.mine || o.dest == broadcast) continue
            val m = db.message(o.fid)
            if (m == null || !m.mine || m.status >= Message.DELIVERED) { db.removeOutbox(o.fid); continue }
            try {
                val blob = m.file?.let { java.io.File(it).takeIf { f -> f.exists() }?.readBytes() }
                val lost = m.kind in setOf(Message.PHOTO, Message.VOICE, Message.VIDEO) && blob == null
                if (m.kind == Message.WIZZ || lost || db.contact(m.chat) == null) { db.removeOutbox(o.fid); db.setStatus(m.mid, Message.FAILED); continue }
                if (m.kind == Message.VIDEO) sendMessageNow(m.chat, m.kind, m.body, blob, Frame.FLAG_WIFI_ONLY, 1) else sendMessageNow(m.chat, m.kind, m.body, blob, 0, Frame.DEFAULT_TTL)
                // L'ancien n'est retiré qu'une fois le nouveau enregistré.
                db.removeOutbox(o.fid); db.deleteMessage(m.mid)
            } catch (e: Exception) { Log.e(tag, "refonte ${m.mid}", e); db.removeOutbox(o.fid); db.setStatus(m.mid, Message.FAILED) }
        }
        for (g in db.groups()) if (g.owner == me.id) shareGroup(g, g.members.filter { it != me.id && db.contact(it) != null })
        db.kv("fmt2", "1")
    }

    /** Bouton « Renvoyer » d'un message en échec : il repart comme un message neuf, l'ancien est retiré. */
    private val resending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun resend(mid: String) {
        if (!resending.add(mid)) return // deux appuis rapprochés : un seul renvoi
        scope.launch {
            try {
                val m = db.message(mid) ?: return@launch
                if (!m.mine || m.status != Message.FAILED) return@launch
                if (m.kind == Message.WIZZ || m.kind == Message.SOS) { event("Un ancien SOS ou wizz ne se renvoie pas : refais-le"); return@launch }
                val blob = m.file?.let { java.io.File(it).takeIf { f -> f.exists() }?.readBytes() }
                if (m.chat != Db.ALL && db.group(m.chat) == null && db.contact(m.chat) == null) { event("Renvoi impossible : ce contact n'existe plus"); return@launch }
                if (m.kind in setOf(Message.PHOTO, Message.VOICE, Message.VIDEO) && blob == null) { event("Renvoi impossible : le fichier n'est plus sur ce téléphone"); return@launch }
                if (m.kind == Message.VIDEO) sendMessageNow(m.chat, m.kind, m.body, blob, Frame.FLAG_WIFI_ONLY, 1)
                else sendMessageNow(m.chat, m.kind, m.body, blob, 0, Frame.DEFAULT_TTL)
                db.deleteMessage(mid)
            } catch (e: Exception) { Log.e(tag, "renvoi", e); event("Renvoi impossible : ${e.message}") }
            finally { resending.remove(mid) }
        }
    }

    private fun askCard(peerId: String) {
        core.send(Frame(Frame.CARD_REQ, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), ByteArray(0)))
    }

    /** Quand une liaison s'ouvre : on demande sa carte (clés publiques) si besoin, et on lui envoie ma photo de profil. */
    fun onPeerAppeared(p: Peer) {
        if (!cards.containsKey(p.id)) askCard(p.id)
        sendAvatar(p.id, force = true)
    }

    /** Ma photo de profil vers un voisin (petit JPEG), ou vers tous les voisins connectés quand je la change. */
    private val avatarSentAt = ConcurrentHashMap<String, Long>()
    fun sendAvatar(peerId: String?, force: Boolean = true) {
        val mine = fr.proximo.app.core.Avatars.mine(ctx)
        val payload = mine ?: ByteArray(0) // vide = « plus de photo »
        val targets = if (peerId != null) listOf(peerId) else core.connectedPeerIds().toList()
        val now = System.currentTimeMillis()
        for (t in targets) {
            if (!force && now - (avatarSentAt[t] ?: 0L) < 10 * 60_000) continue
            if (core.sendTo(t, Frame(Frame.AVATAR, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(t), payload).encode())) avatarSentAt[t] = now
        }
    }
    /** La photo a changé : prévenir l'interface et les voisins. */
    val avatarsChanged = MutableStateFlow(0L)

    // ---- groupes ----

    fun createGroup(name: String, members: List<String>): Group {
        val g = Group(Hex.enc(Crypto.random(8)), name.trim(), Crypto.random(32), me.id, (members + me.id).distinct(), System.currentTimeMillis())
        db.saveGroup(g)
        shareGroup(g, g.members.filter { it != me.id })
        db.saveMessage(Message(Hex.enc(Frame.newId()), g.id, me.id, true, Message.SYS, "Groupe créé", null, System.currentTimeMillis(), Message.DELIVERED, true, ""))
        return g
    }

    fun addToGroup(groupId: String, memberId: String) {
        val g = db.group(groupId) ?: return
        if (memberId in g.members) return
        val ng = g.copy(members = g.members + memberId)
        db.saveGroup(ng)
        shareGroup(ng, ng.members.filter { it != me.id })
    }

    fun leaveGroup(groupId: String) { db.deleteGroup(groupId) }

    private fun shareGroup(g: Group, to: List<String>) {
        val body = JSONObject().put("id", g.id).put("name", g.name).put("key", B64.enc(g.key)).put("owner", g.owner).put("members", JSONArray(g.members)).toString()
        for (m in to) sendMessage(m, GINV, body, null)
    }

    // =============== côté réseau ===============

    /** Rend false si la trame n'a pas pu être traitée faute de clé (elle pourra être rejouée). */
    fun handle(f: Frame, fromPeer: String): Boolean {
        when (f.type) {
            Frame.CARD_REQ -> { core.send(Frame(Frame.CARD, 0, 0, 0, Frame.newId(), core.myIdBytes(), f.from, me.card().toJson().toString().toByteArray())); sendAvatar(f.fromHex, force = false) }
            Frame.CARD -> onCard(f)
            Frame.CONTACT_REQ -> onContactReq(f)
            Frame.CONTACT_ACCEPT -> onContactAccept(f)
            Frame.DM -> return onDm(f)
            Frame.GROUP -> return onGroup(f)
            Frame.PUBLIC, Frame.SOS -> onPublic(f)
            Frame.ACK -> onAck(f)
            Frame.AVATAR -> {
                if (f.payload.isEmpty()) fr.proximo.app.core.Avatars.remove(ctx, f.fromHex)
                else if (f.payload.size <= fr.proximo.app.core.Avatars.MAX_BYTES + 2000) fr.proximo.app.core.Avatars.save(ctx, f.fromHex, f.payload)
                avatarsChanged.value = avatarsChanged.value + 1
                // Échange : je lui renvoie la mienne s'il ne l'a pas eue récemment (chacun voit la photo de l'autre).
                sendAvatar(f.fromHex, force = false)
            }
        }
        return true
    }

    private fun cardOf(f: Frame): Card? = Card.fromJson(JSONObject(String(f.payload))).takeIf { it?.id == f.fromHex }

    private fun onCard(f: Frame) {
        val card = cardOf(f) ?: return
        cards[card.id] = card
        if (db.contact(card.id)?.blocked != true) core.replayHeld(card.id) // bloqué : ses messages mis de côté avant le blocage ne sont pas détruits
        db.contact(card.id)?.let { if (it.name != card.name && it.accepted) db.renameContact(card.id, card.name) }
        // Quelqu'un attendait cette carte ?
        if (wantContact.remove(card.id)) addFromCard(card)
        db.pendingCodes().firstOrNull { it == card.shortCode }?.let { db.removePendingCode(it); addFromCard(card) }
    }

    private fun onContactReq(f: Frame) {
        val card = cardOf(f) ?: return
        cards[card.id] = card
        val existing = db.contact(card.id)
        if (existing?.blocked == true) return // bloqué : sa demande est ignorée, sans rien afficher
        core.replayHeld(card.id)
        when {
            existing?.accepted == true -> { db.saveContact(card, 1); core.send(Frame(Frame.CONTACT_ACCEPT, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), f.from, me.card().toJson().toString().toByteArray())) }
            existing?.status == 0 -> { db.saveContact(card, 1); acceptContact(card.id) }   // on s'était ajoutés mutuellement
            else -> { db.saveContact(card, 2); event("${card.name} veut t'ajouter en contact"); notify("Demande de contact", "${card.name} veut t'ajouter", "contacts", false) }
        }
    }

    private fun onContactAccept(f: Frame) {
        val card = cardOf(f) ?: return
        cards[card.id] = card
        // Une « acceptation » ne vaut que si JE lui ai demandé (demande envoyée) ou si on est déjà en contact.
        // Sans ça, n'importe quel téléphone pouvait se déclarer « accepté » tout seul.
        val existing = db.contact(card.id)
        if (existing == null || existing.status == 2 || existing.blocked) return
        db.saveContact(card, 1)
        core.replayHeld(card.id)
        if (existing.accepted) return // déjà en contact : pas de deuxième « a accepté ta demande »
        db.saveMessage(Message(Hex.enc(Frame.newId()), card.id, card.id, false, Message.SYS, "${card.name} a accepté ta demande", null, System.currentTimeMillis(), Message.DELIVERED, isShowing(card.id), ""))
        notify("Contact accepté", "${card.name} a accepté ta demande", card.id, false)
    }

    private fun onDm(f: Frame): Boolean {
        val from = f.fromHex
        if (isBlocked(from)) {
            // Bloqué : le message n'est ni affiché ni gardé. Un accusé part quand même, sinon son téléphone le renverrait
            // toutes les deux minutes pendant 30 jours (liaison et batterie occupées pour rien, des deux côtés).
            core.send(core.ackFrame(f.from, f.fid, f.hops))
            db.markAcked(f.fidHex)
            return true
        }
        val plain = Crypto.openWith(me.dhPriv, me.dhPub, f.payload, f.fid + f.from + f.to) ?: return true
        val inn = parseInner(plain) ?: return true
        val contact = db.contact(from)
        val sigPub = senderKey(from, inn)
        if (sigPub == null) { Log.i(tag, "clé de $from pas encore connue : on demande sa carte, le message sera rejoué"); askCard(from); return false }
        // La signature doit dire que le message est pour MOI (un message écrit pour un autre ne passe plus).
        if (!verifyInner(f.fidHex, inn, sigPub, CTX_DM + me.id)) { Log.w(tag, "signature invalide de $from"); return true }
        if (contact?.accepted != true && !settings.allowUnknownDm) {
            // Pas encore accepté : si sa demande est en attente, le message attend qu'on l'accepte.
            if (contact?.status == 2 || contact?.status == 0) return false
            Log.i(tag, "message privé d'un inconnu ignoré"); return true
        }
        val kind = inn.json.optString("k")
        val senderName = contact?.name ?: inn.json.optString("n", "Inconnu")
        // Accusé de réception : repart vers l'émetteur par le réseau.
        core.send(core.ackFrame(f.from, f.fid, f.hops))
        db.markAcked(f.fidHex) // si l'expéditeur ne reçoit pas cet accusé et renvoie le message, on saura le lui redonner
        when (kind) {
            GINV -> onGroupInvite(from, inn.json.optString("b"))
            else -> store(f, from, from, senderName, inn, kind)
        }
        return true
    }

    private fun onGroupInvite(from: String, body: String) {
        try {
            val o = JSONObject(body)
            val arr = o.getJSONArray("members"); if (arr.length() > 500) return
            val m = ArrayList<String>(); for (i in 0 until arr.length()) m.add(arr.getString(i))
            val id = o.getString("id"); val key = B64.dec(o.getString("key")); val name = o.getString("name").trim().take(60)
            // Invitation vérifiée : un vrai identifiant de groupe, une vraie clé, envoyée par le créateur du groupe,
            // et jamais l'identifiant d'un téléphone (sinon une discussion privée pouvait être détournée vers ce « groupe »).
            if (!HEX16.matches(id) || key.size != 32 || name.isEmpty()) return
            if (id == me.id || db.contact(id) != null || cards.containsKey(id)) return
            if (o.getString("owner") != from || me.id !in m) return
            val existing = db.group(id)
            if (existing != null && existing.owner != from) return
            val g = Group(id, name, key, from, m, System.currentTimeMillis())
            db.saveGroup(g)
            core.replayHeld(null)
            if (existing == null) {
                val who = db.contact(from)?.name ?: "Un contact"
                db.saveMessage(Message(Hex.enc(Frame.newId()), g.id, from, false, Message.SYS, "$who t'a ajouté au groupe", null, System.currentTimeMillis(), Message.DELIVERED, false, ""))
                notify("Nouveau groupe : ${g.name}", "$who t'a ajouté", g.id, false)
            }
        } catch (e: Exception) { Log.w(tag, "invitation groupe", e) }
    }

    private fun onGroup(f: Frame): Boolean {
        if (f.payload.size < 8 + 28) return true
        val gid = Hex.enc(f.payload.copyOfRange(0, 8))
        // Groupe pas encore connu (l'invitation n'est pas arrivée) : on garde la possibilité de rejouer.
        val g = db.group(gid) ?: return false
        val plain = Crypto.aesDecrypt(g.key, f.payload.copyOfRange(8, f.payload.size), f.fid) ?: return true
        val inn = parseInner(plain) ?: return true
        val from = f.fromHex
        val contact = db.contact(from)
        if (contact?.blocked == true) return true // bloqué : ses messages de groupe ne s'affichent pas non plus
        // Signature exigée, liée à CE groupe : un membre ne peut plus écrire au nom d'un autre, ni un relais changer l'expéditeur.
        val sigPub = senderKey(from, inn) ?: return true
        if (!verifyInner(f.fidHex, inn, sigPub, CTX_GROUP + gid)) return true
        store(f, gid, from, contact?.name ?: inn.json.optString("n", "Membre"), inn, inn.json.optString("k"))
        return true
    }

    private fun onPublic(f: Frame) {
        if (f.payload.size < 64 + 3) return
        val inner = f.payload.copyOfRange(0, f.payload.size - 64)
        val sig = f.payload.copyOfRange(f.payload.size - 64, f.payload.size)
        val inn = parseInner(inner) ?: return
        val from = f.fromHex
        if (isBlocked(from)) return // bloqué : ni ses messages publics ni ses SOS ne s'affichent (ils restent relayés aux autres)
        // Signature exigée pour tout le monde, même les inconnus (leur clé est jointe au message et vérifiée contre leur identifiant) :
        // plus de faux SOS ni de message public sous le nom d'un autre.
        val pub = senderKey(from, inn) ?: return
        if (!Crypto.verify(pub, f.fid + inner, sig)) return
        // La signature intérieure (format 2, destinataire « all ») est exigée elle aussi : la signature extérieure seule
        // n'a pas de préfixe et pouvait être fabriquée à partir d'une autre signature du même téléphone.
        if (!verifyInner(f.fidHex, inn, pub, CTX_ALL)) return
        val name = db.contact(from)?.name ?: inn.json.optString("n", "Inconnu")
        val kind = inn.json.optString("k")
        // « Tout le monde » : tout le monde voit tout, sans limite de temps (les retardataires reçoivent ce qui attend chez les voisins).
        if (kind == Message.SOS) {
            store(f, Db.ALL, from, name, inn, Message.SOS)
            val o = Db.jsonObj(inn.json.optString("b"))
            notify("🆘 SOS de $name", o.optString("t", "Besoin d'aide !"), Db.ALL, true)
        } else store(f, Db.ALL, from, name, inn, kind)
    }

    private fun onAck(f: Frame) {
        if (f.payload.size < 16) return
        val mid = Hex.enc(f.payload.copyOfRange(0, 16))
        val hops = if (f.payload.size > 16) f.payload[16].toInt() and 0xff else 0
        // L'accusé ne compte que s'il vient du destinataire de CE message (un autre téléphone ne peut pas le marquer « Reçu »).
        val m = db.message(mid)
        val o = db.outboxRow(mid)
        val fromDest = (m != null && m.mine && m.chat == f.fromHex) || (o != null && o.mine && o.dest == f.fromHex)
        if (!fromDest) return
        // … et s'il est signé par lui (sa clé est jointe, et son empreinte doit être son identifiant).
        if (!core.ackValid(f)) return
        if (m != null) db.setStatus(mid, Message.DELIVERED, if (hops <= 1) "Reçu en direct" else "Reçu après $hops sauts")
        db.removeOutbox(mid)
    }

    private fun store(f: Frame, chat: String, from: String, senderName: String, inn: Inner, kind: String) {
        val mid = f.fidHex
        if (db.message(mid) != null) return
        val body = inn.json.optString("b")
        val file = inn.blob?.let { saveBlob(mid, kind, it) }
        val isOpen = isShowing(chat)
        val route = if (f.hops == 0) "direct" else "${f.hops} saut(s)"
        val now = System.currentTimeMillis()
        // Une heure d'envoi « dans le futur » (horloge de l'expéditeur en avance) est ramenée à maintenant.
        val sentAt = inn.json.optLong("t", now).coerceAtMost(now)
        db.saveMessage(Message(mid, chat, from, false, kind, body, file, sentAt, Message.DELIVERED, isOpen, route, senderName, now))
        val late = now - sentAt > 3600_000L
        db.touchContact(from)
        if (kind == Message.WIZZ) { onWizz(chat, from, senderName, now - sentAt, isOpen); return }
        val preview = when (kind) {
            Message.PHOTO -> "📷 Photo"; Message.VOICE -> "🎤 Vocal (${body}s)"; Message.VIDEO -> "🎬 Vidéo (${body}s)"; Message.LOC -> "📍 Position"; Message.SOS -> "🆘 SOS"; else -> body
        }
        val title = if (chat == Db.ALL) "$senderName · Tout le monde" else if (db.group(chat) != null) "$senderName · ${db.group(chat)?.name}" else senderName
        if (kind != Message.SOS && !isOpen) notify(if (late) "$title · écrit le ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(sentAt))}" else title, preview, chat, false)
        if (kind == Message.VOICE && file != null && settings.autoPlayVoice) queueVoice(file)
    }

    /**
     * Wizz reçu. En privé, d'un contact accepté et arrivé à temps : le téléphone sonne et vibre, même en silencieux.
     * Arrivé plus de 10 min après (ou hors discussion privée) : simple notification muette, sans sonnerie.
     */
    private fun onWizz(chat: String, from: String, senderName: String, ageMs: Long, isOpen: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val direct = chat == from && db.contact(from)?.accepted == true
        val fresh = ageMs <= WIZZ_FRESH_MS
        val lastRing = wizzRungAt[from]
        val ring = direct && fresh && (lastRing == null || now - lastRing >= WIZZ_RING_GAP_MS)
        if (ring) { wizzRungAt[from] = now; ringWizz(); shake(chat) }
        if (!isOpen) notify(senderName, if (fresh) "👋 Wizz !" else "👋 Wizz (arrivé en retard)", chat, false, silent = true)
    }

    /** Sonnerie + vibration du wizz sur le canal « alarme » : passe même si le téléphone est en silencieux. */
    @Volatile private var wizzRinging = false
    @Suppress("DEPRECATION")
    private fun ringWizz() {
        try {
            val pattern = longArrayOf(0, 110, 60, 110, 60, 110, 60, 380, 160, 110, 60, 110, 60, 110, 60, 380, 160, 110, 60, 110, 60, 500)
            val effect = android.os.VibrationEffect.createWaveform(pattern, -1)
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 33) vib?.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            else vib?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        } catch (e: Exception) { Log.w(tag, "vibration wizz", e) }
        // Pendant un appel, on ne couvre pas la voix : vibration seulement.
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // (Un appel Proximo, téléphonique ou d'une autre appli est en cours : on ne couvre pas la voix.)
        val inCall = try {
            fr.proximo.app.mesh.MeshService.instance?.calls?.state?.value?.phase == fr.proximo.app.call.CallState.ACTIVE ||
                am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
        if (inCall) return
        if (wizzRinging) return
        wizzRinging = true
        var restore: Int? = null
        try {
            // Volume d'alarme trop bas ou à zéro : on le remonte le temps du wizz, puis on le remet comme avant.
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val want = (max * 7 + 9) / 10
                if (cur < want) { settings.wizzRestoreVolume = cur; am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0); restore = cur }
            }
        } catch (e: Exception) { Log.w(tag, "volume wizz", e) }
        var tg: ToneGenerator? = null
        try { tg = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) { Log.w(tag, "son wizz", e) }
        // Deux rafales de quatre notes rapides, calées sur la vibration.
        val notes = intArrayOf(ToneGenerator.TONE_DTMF_A, ToneGenerator.TONE_DTMF_D, ToneGenerator.TONE_DTMF_A, ToneGenerator.TONE_DTMF_D)
        val starts = longArrayOf(0, 170, 340, 510, 1050, 1220, 1390, 1560)
        val gen = tg
        if (gen != null) starts.forEachIndexed { i, t -> mainHandler.postDelayed({ try { gen.startTone(notes[i % notes.size], if (i % 4 == 3) 380 else 120) } catch (_: Exception) {} }, t) }
        mainHandler.postDelayed({
            try { gen?.release() } catch (_: Exception) {}
            try { restore?.let { am?.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } } catch (_: Exception) {}
            settings.wizzRestoreVolume = -1
            wizzRinging = false
        }, 2400)
    }

    /** Appelé par le cœur quand une de mes trames est passée à un voisin : statut « Relayé par … ». */
    fun onRelayed(o: Outbox, peerId: String) {
        if (!o.mine) return
        val m = db.message(o.fid) ?: return
        if (m.status >= Message.DELIVERED) return
        val who = db.contact(peerId)?.name ?: core.peer(peerId)?.name ?: "un voisin"
        db.setStatus(o.fid, Message.RELAYED, if (o.dest == peerId) "Remis en direct" else "Relayé par $who")
        if (o.dest == peerId && m.chat != Db.ALL && db.group(m.chat) == null) { /* l'accusé de réception confirmera */ }
    }

    // ---- notifications et sons ----
    private fun notify(title: String, text: String, chat: String, sos: Boolean, silent: Boolean = false) {
        try {
            val i = Intent(ctx, MainActivity::class.java).putExtra("chat", chat).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, chat.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, if (sos) fr.proximo.app.mesh.MeshService.CH_SOS else fr.proximo.app.mesh.MeshService.CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi)
                .setPriority(if (sos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (sos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
                .setSilent(silent || (!settings.sounds && !sos))
                .build()
            NotificationManagerCompat.from(ctx).notify(chat.hashCode(), n)
        } catch (e: SecurityException) { Log.w(tag, "notification refusée") }
        if (sos) beep(AudioManager.STREAM_ALARM, ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun beep(stream: Int, tone: Int, ms: Int) {
        try {
            val tg = ToneGenerator(stream, 90)
            tg.startTone(tone, ms)
            mainHandler.postDelayed({ try { tg.release() } catch (_: Exception) {} }, ms + 300L)
        } catch (_: Exception) {}
    }

    /** Vocaux à lire : l'un après l'autre (plusieurs vocaux arrivés ensemble ne se superposent plus). */
    private val voiceQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val voicePlaying = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun queueVoice(path: String) {
        if (voiceQueue.size >= 20) return
        voiceQueue.add(path)
        mainHandler.post { playNextVoice() }
    }
    private fun playNextVoice() {
        if (!voicePlaying.compareAndSet(false, true)) return
        val path = voiceQueue.poll()
        if (path == null) { voicePlaying.set(false); return }
        var done = false
        val next = Runnable { if (!done) { done = true; voicePlaying.set(false); playNextVoice() } }
        try {
            beep(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_BEEP, 150)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release(); beep(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_BEEP2, 200); mainHandler.postDelayed(next, 400) }
            mp.setOnErrorListener { p, _, _ -> try { p.release() } catch (_: Exception) {}; mainHandler.post(next); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            // Garde-fou : si le lecteur ne rend jamais la main, la file repart au bout de 70 s (un vocal dure 60 s au plus).
            mainHandler.postDelayed(next, 70_000)
        } catch (e: Exception) { Log.w(tag, "lecture vocal", e); mainHandler.post(next) }
    }
}
