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
    companion object {
        /** Un wizz toutes les 30 s au plus vers un même contact. */
        const val WIZZ_EVERY_MS = 30_000L
        /** Au-delà de 10 min de retard, un wizz s'affiche sans sonner. */
        const val WIZZ_FRESH_MS = 10 * 60_000L
        /** Côté réception : pas deux sonneries du même contact à moins de 20 s. */
        const val WIZZ_RING_GAP_MS = 20_000L
    }
    private val tag = "ProximoMsg"
    private val db = Db.get(ctx)
    private val me = Identity.get(ctx)
    private val settings = Settings.get(ctx)
    private val mediaDir = File(ctx.filesDir, "media").also { it.mkdirs() }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Cartes (clés publiques) des téléphones vus, même pas encore contacts. */
    val cards = ConcurrentHashMap<String, Card>()
    /** Voisins pour qui on a demandé l'ajout avant d'avoir leur carte. */
    private val wantContact = ConcurrentHashMap.newKeySet<String>()
    /** Discussion ouverte à l'écran (pas de notification pour elle). */
    @Volatile var openChat: String? = null
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
    fun sendVideo(chat: String, mp4: ByteArray, seconds: Int) = sendMessage(chat, Message.VIDEO, seconds.toString(), mp4, flags = Frame.FLAG_WIFI_ONLY, ttl = 2)

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
        val now = System.currentTimeMillis()
        val wait = WIZZ_EVERY_MS - (now - (wizzSentAt[chat] ?: 0L))
        if (wait > 0) { event("Attends encore ${(wait + 999) / 1000} s avant un nouveau wizz"); return }
        wizzSentAt[chat] = now
        sendMessage(chat, Message.WIZZ, "Wizz !", null)
        shake(chat)
        event("Wizz envoyé")
    }

    /** SOS : à tout le monde, prioritaire, avec ma position si on l'a. */
    fun sendSos(text: String, lat: Double?, lon: Double?) {
        val body = JSONObject().put("t", text).apply { if (lat != null && lon != null) put("lat", lat).put("lon", lon) }.toString()
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
        val inner = inner(mid, kind, body, time, blob)
        val frame: Frame? = when {
            chat == Db.ALL -> {
                val sig = Crypto.sign(me.sigPriv, fid + inner)
                Frame(if (kind == Message.SOS) Frame.SOS else Frame.PUBLIC, ttl, 0, flags, fid, core.myIdBytes(), Frame.BROADCAST, inner + sig)
            }
            db.group(chat) != null -> {
                val g = db.group(chat)!!
                Frame(Frame.GROUP, ttl, 0, flags, fid, core.myIdBytes(), Frame.BROADCAST, Hex.dec(g.id) + Crypto.aesEncrypt(g.key, inner, fid))
            }
            else -> {
                val c = db.contact(chat) ?: cards[chat]?.let { Contact(it.id, it.name, it.dhPub, it.sigPub, 0, 0, 0) }
                if (c == null) { event("Impossible d'écrire : contact inconnu"); return }
                val to = Hex.dec(c.id)
                Frame(Frame.DM, ttl, 0, flags, fid, core.myIdBytes(), to, Crypto.sealFor(c.dhPub, inner, fid + core.myIdBytes() + to))
            }
        }
        val file = blob?.let { saveBlob(mid, kind, it) }
        db.saveMessage(Message(mid, chat, me.id, true, kind, body, file, time, Message.PENDING, true, ""))
        frame?.let { core.send(it) }
    }

    /** Payload en clair : [longueur json (2)][json][fichier]. Le json est signé par l'émetteur. */
    private fun inner(mid: String, kind: String, body: String, time: Long, blob: ByteArray?): ByteArray {
        val blobHash = Hex.enc(Crypto.sha256(blob ?: ByteArray(0)))
        val sig = Crypto.sign(me.sigPriv, "$mid|$kind|$body|$time|$blobHash".toByteArray())
        val json = JSONObject().put("k", kind).put("b", body).put("t", time).put("n", me.name).put("s", B64.enc(sig)).toString().toByteArray()
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

    private fun verifyInner(mid: String, inn: Inner, sigPub: ByteArray?): Boolean {
        if (sigPub == null) return false
        val j = inn.json
        val blobHash = Hex.enc(Crypto.sha256(inn.blob ?: ByteArray(0)))
        val data = "$mid|${j.optString("k")}|${j.optString("b")}|${j.optLong("t")}|$blobHash".toByteArray()
        return Crypto.verify(sigPub, data, B64.dec(j.optString("s")))
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
        for (m in to) sendMessage(m, "ginv", body, null)
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
        core.replayHeld(card.id)
        db.contact(card.id)?.let { if (it.name != card.name && it.accepted) db.renameContact(card.id, card.name) }
        // Quelqu'un attendait cette carte ?
        if (wantContact.remove(card.id)) addFromCard(card)
        db.pendingCodes().firstOrNull { it == card.shortCode }?.let { db.removePendingCode(it); addFromCard(card) }
    }

    private fun onContactReq(f: Frame) {
        val card = cardOf(f) ?: return
        cards[card.id] = card
        core.replayHeld(card.id)
        val existing = db.contact(card.id)
        when {
            existing?.accepted == true -> { db.saveContact(card, 1); core.send(Frame(Frame.CONTACT_ACCEPT, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), f.from, me.card().toJson().toString().toByteArray())) }
            existing?.status == 0 -> { db.saveContact(card, 1); acceptContact(card.id) }   // on s'était ajoutés mutuellement
            else -> { db.saveContact(card, 2); event("${card.name} veut t'ajouter en contact"); notify("Demande de contact", "${card.name} veut t'ajouter", "contacts", false) }
        }
    }

    private fun onContactAccept(f: Frame) {
        val card = cardOf(f) ?: return
        cards[card.id] = card
        db.saveContact(card, 1)
        core.replayHeld(card.id)
        db.saveMessage(Message(Hex.enc(Frame.newId()), card.id, card.id, false, Message.SYS, "${card.name} a accepté ta demande", null, System.currentTimeMillis(), Message.DELIVERED, openChat == card.id, ""))
        notify("Contact accepté", "${card.name} a accepté ta demande", card.id, false)
    }

    private fun onDm(f: Frame): Boolean {
        val from = f.fromHex
        val plain = Crypto.openWith(me.dhPriv, me.dhPub, f.payload, f.fid + f.from + f.to) ?: return true
        val inn = parseInner(plain) ?: return true
        val contact = db.contact(from)
        val sigPub = contact?.sigPub ?: cards[from]?.sigPub
        if (sigPub == null) { Log.i(tag, "clé de $from pas encore connue : on demande sa carte, le message sera rejoué"); askCard(from); return false }
        if (!verifyInner(f.fidHex, inn, sigPub)) { Log.w(tag, "signature invalide de $from"); return true }
        if (contact?.accepted != true && !settings.allowUnknownDm) {
            // Pas encore accepté : si sa demande est en attente, le message attend qu'on l'accepte.
            if (contact?.status == 2 || contact?.status == 0) return false
            Log.i(tag, "message privé d'un inconnu ignoré"); return true
        }
        val kind = inn.json.optString("k")
        val senderName = contact?.name ?: inn.json.optString("n", "Inconnu")
        // Accusé de réception : repart vers l'émetteur par le réseau.
        core.send(Frame(Frame.ACK, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), core.myIdBytes(), f.from, f.fid + byteArrayOf(f.hops.toByte())))
        when (kind) {
            "ginv" -> onGroupInvite(from, inn.json.optString("b"))
            else -> store(f, from, from, senderName, inn, kind)
        }
        return true
    }

    private fun onGroupInvite(from: String, body: String) {
        try {
            val o = JSONObject(body)
            val arr = o.getJSONArray("members"); val m = ArrayList<String>(); for (i in 0 until arr.length()) m.add(arr.getString(i))
            val existing = db.group(o.getString("id"))
            if (existing != null && existing.owner != from) return
            val g = Group(o.getString("id"), o.getString("name"), B64.dec(o.getString("key")), o.getString("owner"), m, System.currentTimeMillis())
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
        val sigPub = contact?.sigPub ?: cards[from]?.sigPub
        if (sigPub != null && !verifyInner(f.fidHex, inn, sigPub)) return true
        store(f, gid, from, contact?.name ?: inn.json.optString("n", "Membre"), inn, inn.json.optString("k"))
        return true
    }

    private fun onPublic(f: Frame) {
        if (f.payload.size < 64 + 3) return
        val inner = f.payload.copyOfRange(0, f.payload.size - 64)
        val sig = f.payload.copyOfRange(f.payload.size - 64, f.payload.size)
        val inn = parseInner(inner) ?: return
        val from = f.fromHex
        val known = db.contact(from)?.sigPub ?: cards[from]?.sigPub
        if (known != null && !Crypto.verify(known, f.fid + inner, sig)) return
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
        db.setStatus(mid, Message.DELIVERED, if (hops <= 1) "Reçu en direct" else "Reçu après $hops sauts")
        db.removeOutbox(mid)
    }

    private fun store(f: Frame, chat: String, from: String, senderName: String, inn: Inner, kind: String) {
        val mid = f.fidHex
        if (db.message(mid) != null) return
        val body = inn.json.optString("b")
        val file = inn.blob?.let { saveBlob(mid, kind, it) }
        val isOpen = openChat == chat
        val route = if (f.hops == 0) "direct" else "${f.hops} saut(s)"
        val sentAt = inn.json.optLong("t", System.currentTimeMillis())
        val now = System.currentTimeMillis()
        db.saveMessage(Message(mid, chat, from, false, kind, body, file, sentAt, Message.DELIVERED, isOpen, route, senderName, now))
        val late = now - sentAt > 3600_000L
        db.touchContact(from)
        if (kind == Message.WIZZ) { onWizz(chat, from, senderName, now - sentAt, isOpen); return }
        val preview = when (kind) {
            Message.PHOTO -> "📷 Photo"; Message.VOICE -> "🎤 Vocal (${body}s)"; Message.VIDEO -> "🎬 Vidéo (${body}s)"; Message.LOC -> "📍 Position"; Message.SOS -> "🆘 SOS"; else -> body
        }
        val title = if (chat == Db.ALL) "$senderName · Tout le monde" else if (db.group(chat) != null) "$senderName · ${db.group(chat)?.name}" else senderName
        if (kind != Message.SOS && !isOpen) notify(if (late) "$title · écrit le ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(sentAt))}" else title, preview, chat, false)
        if (kind == Message.VOICE && file != null && settings.autoPlayVoice) playVoice(file)
    }

    /**
     * Wizz reçu. En privé, d'un contact accepté et arrivé à temps : le téléphone sonne et vibre, même en silencieux.
     * Arrivé plus de 10 min après (ou hors discussion privée) : simple notification muette, sans sonnerie.
     */
    private fun onWizz(chat: String, from: String, senderName: String, ageMs: Long, isOpen: Boolean) {
        val now = System.currentTimeMillis()
        val direct = chat == from && db.contact(from)?.accepted == true
        val fresh = ageMs <= WIZZ_FRESH_MS
        val ring = direct && fresh && now - (wizzRungAt[from] ?: 0L) >= WIZZ_RING_GAP_MS
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
        val inCall = try { fr.proximo.app.mesh.MeshService.instance?.calls?.state?.value?.phase == fr.proximo.app.call.CallState.ACTIVE } catch (_: Exception) { false }
        if (inCall) return
        if (wizzRinging) return
        wizzRinging = true
        var restore: Int? = null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            // Volume d'alarme trop bas ou à zéro : on le remonte le temps du wizz, puis on le remet comme avant.
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val want = (max * 7 + 9) / 10
                if (cur < want) { am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0); restore = cur }
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

    private fun playVoice(path: String) {
        try {
            beep(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_BEEP, 150)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release(); beep(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_BEEP2, 200) }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) { Log.w(tag, "lecture vocal", e) }
    }
}
