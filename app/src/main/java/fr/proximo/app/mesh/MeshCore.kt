package fr.proximo.app.mesh

import android.content.Context
import android.util.Log
import fr.proximo.app.core.Crypto
import fr.proximo.app.core.Db
import fr.proximo.app.core.Hex
import fr.proximo.app.core.Identity
import fr.proximo.app.core.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Un voisin vu par au moins un transport. */
data class Peer(
    val id: String,
    val name: String?,
    val lastSeen: Long,
    val ble: Boolean,
    val wifi: Boolean,
    val rssi: Int,
    val linked: Boolean,
    /** Relié mais sans preuve d'identité : ancienne version de Proximo, seules les mises à jour passent. */
    val limited: Boolean = false,
) {
    /** Distance approximative en mètres d'après la force du signal Bluetooth (très approximatif). */
    val meters: Int? get() = if (ble && rssi != 0) Math.pow(10.0, (-59 - rssi) / 25.0).toInt().coerceIn(1, 150) else null
}

/** État d'un transport pour l'écran. */
data class TransportStatus(val enabled: Boolean, val detail: String)

/**
 * Cœur du maillage : reçoit les trames des transports, dédoublonne, remet à la messagerie
 * ce qui m'est destiné, relaie le reste, et garde en attente ce qui n'a pas pu partir.
 */
class MeshCore(private val ctx: Context, private val transports: List<Transport>) : TransportListener {
    private val tag = "ProximoMesh"
    private val db = Db.get(ctx)
    private val me = Identity.get(ctx)
    private val myIdBytes = Hex.dec(me.id)
    /** Filet de sécurité : une erreur dans une tâche de fond (stockage plein…) est notée, elle ne fait plus planter l'appli. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(tag, "tâche de fond", e) })
    /** Réception sérialisée : une trame à la fois, dans l'ordre d'arrivée (pas de doublons ni de désordre). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val rx = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private val recentSeen = ConcurrentHashMap<String, Long>()
    /** Heure du dernier envoi direct au destinataire d'un de mes messages (pour renvoyer si pas d'accusé). */
    private val lastDirectSend = ConcurrentHashMap<String, Long>()
    /** Trames confiées récemment à un transport pour un voisin (peut-être pas encore arrivées) : voisin -> (trame -> heure de remise). */
    private val inFlight = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    /** Dernier renvoi d'un accusé pour un message déjà reçu (pas plus d'un par minute et par message). */
    private val lastReAck = ConcurrentHashMap<String, Long>()
    private val flushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    // ---- inventaire : à l'ouverture d'une liaison, on demande au voisin ce qu'il a déjà, pour ne pas le lui renvoyer ----
    /** Voisins à qui on vient de demander leur inventaire : on attend leur réponse jusqu'à cette heure avant de leur envoyer les gros messages. */
    private val invWait = ConcurrentHashMap<String, Long>()
    /** Voisins qui n'ont pas répondu (ancienne version de Proximo) : on ne leur redemande pas. */
    private val invSilent: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Heure de la dernière question posée à chaque voisin (une par minute au plus), et ce qu'on lui a demandé. */
    private val invAskedAt = ConcurrentHashMap<String, Long>()
    private val invAsked = ConcurrentHashMap<String, Set<String>>()
    /** Nombre de renvois d'un message privé resté sans accusé : l'attente double à chaque fois (2, 4, 8… min, 1 h au plus). */
    private val directTries = ConcurrentHashMap<String, Int>()
    /** Trames « Wi-Fi direct seulement » (vidéos) déjà repérées : on ne relit pas leur fichier pour un voisin en Bluetooth. */
    private val wifiOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val peerMap = ConcurrentHashMap<String, Peer>()
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val status = MutableStateFlow<Map<String, TransportStatus>>(emptyMap())
    val pendingCount = MutableStateFlow(0)
    /** (mes messages en attente, messages en transit pour d'autres). */
    val pendingDetail = MutableStateFlow(0 to 0)

    /** Celui qui reçoit ce qui m'est destiné (la messagerie). Rend true si la trame a été traitée. */
    var handler: ((Frame, String) -> Boolean)? = null
    /** Prévenu quand un voisin apparaît ou disparaît (radar). */
    var onPeerChange: ((Peer, Boolean) -> Unit)? = null
    /** Prévenu quand une liaison de données est ouverte avec un voisin (on peut lui parler directement). */
    var onLinkUp: ((Peer) -> Unit)? = null

    fun start() {
        transports.forEach { it.start() }
        scope.launch {
            var n = 0
            while (true) {
                delay(30_000)
                try {
                    flush(); expirePeers()
                    if (n++ % 10 == 0) db.purgeOld() // ménage toutes les 5 minutes (il relit des dossiers entiers)
                    pendingCount.value = db.outboxCount(); pendingDetail.value = db.outboxDetail()
                } catch (e: Exception) { Log.e(tag, "entretien", e) }
            }
        }
        scope.launch { pendingCount.value = db.outboxCount(); pendingDetail.value = db.outboxDetail() }
    }
    fun stop() { scope.cancel(); transports.forEach { it.stop() } }
    fun setMode(mode: Int) { transports.forEach { it.setMode(mode) } }
    /** Relance un transport (Bluetooth ou Wi-Fi rallumé). */
    fun restart(name: String) { transports.filter { it.name == name }.forEach { t -> try { t.stop() } catch (_: Exception) {}; try { t.start() } catch (e: Exception) { Log.w(tag, "relance $name", e) } } }

    fun peer(id: String): Peer? = peerMap[id]
    fun connectedPeerIds(): Set<String> = transports.flatMap { it.connectedPeers() }.toSet()
    /** Voisins reliés sans preuve d'identité (ancienne version) et qui ne sont prouvés par aucun transport. */
    fun limitedPeerIds(): Set<String> { val ok = connectedPeerIds(); return transports.flatMap { it.limitedPeers() }.filter { it !in ok }.toSet() }
    /** Vrai si une prise Wi-Fi est ouverte avec ce voisin, même sans preuve d'identité (pour lui envoyer une mise à jour). */
    fun hasUpdateLink(peerId: String): Boolean = transports.any { it.name == Modes.WIFI && it.hasUpdateLink(peerId) }

    /** Accusé de réception signé par moi (le destinataire) : l'expéditeur vérifie qu'il vient bien de moi. */
    fun ackFrame(to: ByteArray, fid: ByteArray, hops: Int): Frame =
        Frame(Frame.ACK, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), myIdBytes, to, fid + byteArrayOf(hops.toByte()) + Crypto.sign(me.sigPriv, ACK_PREFIX + fid) + me.sigPub)

    /**
     * Un accusé est-il bien signé par son expéditeur ? [fid (16)][sauts (1)][signature (64)][clé publique (32)].
     * La clé jointe n'est acceptée que si son empreinte est l'identifiant de l'expéditeur : tout le monde peut vérifier, relais compris.
     */
    fun ackValid(f: Frame): Boolean {
        val p = f.payload
        if (f.type != Frame.ACK || p.size < 113) return false
        val pub = p.copyOfRange(81, 113)
        return try { Identity.idOf(pub) == f.fromHex && Crypto.verify(pub, ACK_PREFIX + p.copyOfRange(0, 16), p.copyOfRange(17, 81)) } catch (e: Exception) { false }
    }
    /** Vrai si une prise Wi-Fi direct (gros débit) est ouverte avec ce voisin. */
    fun hasWifiLink(peerId: String): Boolean = transports.any { it.name == Modes.WIFI && it.hasFastLink(peerId) }
    /** Voisins avec une prise Wi-Fi direct ouverte. */
    fun wifiLinkedPeers(): List<String> = peerMap.keys.filter { hasWifiLink(it) }

    // ---------------- transports -> cœur ----------------
    override fun onPeerSeen(peerId: String, name: String?, transport: String, rssi: Int) {
        val old = peerMap[peerId]
        val linked = connectedPeerIds().contains(peerId)
        val limited = !linked && (old?.limited == true)
        val p = Peer(
            id = peerId,
            // Le nom du contact n'est cherché dans la base qu'à la première apparition (pas à chaque annonce Bluetooth).
            name = name ?: old?.name ?: if (old == null) db.contact(peerId)?.name else null,
            lastSeen = System.currentTimeMillis(),
            ble = (transport == Modes.BLE) || (old?.ble == true),
            wifi = (transport == Modes.WIFI) || (old?.wifi == true),
            rssi = if (transport == Modes.BLE && rssi != 0) rssi else (old?.rssi ?: 0),
            linked = linked,
            limited = limited,
        )
        peerMap[peerId] = p
        publishPeers()
        if (old == null) { scope.launch { db.touchContact(peerId) }; onPeerChange?.invoke(p, true) }
    }

    override fun onPeerLost(peerId: String, transport: String) {
        val old = peerMap[peerId] ?: return
        val p = old.copy(ble = if (transport == Modes.BLE) false else old.ble, wifi = if (transport == Modes.WIFI) false else old.wifi, linked = connectedPeerIds().contains(peerId))
        if (!p.ble && !p.wifi) { peerMap.remove(peerId); onPeerChange?.invoke(p, false) } else peerMap[peerId] = p
        publishPeers()
    }

    override fun onStatus(transport: String, enabled: Boolean, detail: String) {
        status.value = status.value + (transport to TransportStatus(enabled, detail))
    }

    override fun onFrame(data: ByteArray, peerId: String, transport: String, proven: Boolean) {
        val f = Frame.decode(data) ?: return
        // Liaison sans preuve d'identité : seules les mises à jour sont écoutées, et jamais au nom d'un voisin déjà prouvé ailleurs.
        if (!proven && (f.type != Frame.APK || peerId in connectedPeerIds())) return
        scope.launch(rx) { try { receive(f, peerId) } catch (e: Exception) { Log.e(tag, "réception", e) } }
    }

    /** Rejoue les trames mises de côté (clé reçue, contact accepté, groupe rejoint). from = null : toutes. */
    fun replayHeld(from: String?) {
        scope.launch(rx) {
            // Une trame à la fois (pas tout le dossier en mémoire), en gardant sa date d'origine si elle doit encore attendre.
            for ((fid, peer, t) in db.heldIds(from)) {
                val data = db.heldData(fid)
                db.removeHeld(fid)
                val f = data?.let { Frame.decode(it) } ?: continue
                try { receive(f, peer, replay = true, heldSince = t) } catch (e: Exception) { Log.e(tag, "rejeu", e) }
            }
        }
    }

    /** Une liaison de données vient de s'ouvrir : on envoie tout de suite ce qui attend, et on prévient la messagerie. */
    fun onLinkReady(peerId: String) {
        val p = (peerMap[peerId] ?: Peer(peerId, db.contact(peerId)?.name, System.currentTimeMillis(), false, false, 0, true)).copy(linked = true, limited = false, lastSeen = System.currentTimeMillis())
        peerMap[peerId] = p
        publishPeers()
        // La porte se ferme tout de suite pour les gros messages : la question d'inventaire part avant eux.
        if (peerId !in invSilent) invWait[peerId] = System.currentTimeMillis() + 300 + INV_WAIT_MS
        scope.launch {
            // Liaison (r)ouverte : ce qui avait été confié à l'ancienne dans la dernière minute est redonné, par sécurité.
            lock.withLock { try { rearm(peerId) } catch (e: Exception) { Log.e(tag, "réarmement", e) } }
            delay(300)
            try { askInventory(peerId) } catch (e: Exception) { Log.e(tag, "inventaire", e) }
            try { onLinkUp?.invoke(p) } catch (e: Exception) { Log.e(tag, "liaison ouverte", e) }; flush()
        }
    }

    /** Prévenu quand un voisin est relié sans preuve d'identité (ancienne version) : on lui propose la mise à jour. */
    var onLimited: ((String) -> Unit)? = null

    /** Liaison ouverte avec un voisin qui n'a pas prouvé son identité : rien ne lui est envoyé, sauf l'offre de mise à jour. */
    fun onLimitedLink(peerId: String) {
        if (peerId in connectedPeerIds()) return
        peerMap[peerId]?.let { if (!it.linked && !it.limited) { peerMap[peerId] = it.copy(limited = true); publishPeers() } }
        scope.launch { delay(300); try { onLimited?.invoke(peerId) } catch (e: Exception) { Log.e(tag, "liaison limitée", e) } }
    }

    /**
     * Une liaison est tombée alors que des trames étaient en cours d'envoi : celles-là (et seulement celles-là)
     * seront renvoyées à ce voisin. Avant, toute la file d'attente lui était renvoyée à chaque coupure.
     */
    fun onDeliveryFailed(peerId: String) {
        scope.launch { lock.withLock { try { rearm(peerId) } catch (e: Exception) { Log.e(tag, "échec de remise", e) } } }
    }

    /** À appeler sous le verrou : remet en attente pour ce voisin ce qui lui a été confié dans la dernière minute. */
    private fun rearm(peerId: String) {
        val fids = inFlight.remove(peerId)?.keys?.toList() ?: return
        for (fid in fids) db.outboxRow(fid)?.let { o -> if (o.sentTo.remove(peerId)) db.outboxSentTo(fid, o.sentTo) }
    }

    /** Une seule trame n'est pas passée vers ce voisin (petit message Wi-Fi direct refusé) : elle sera retentée. */
    fun onFrameFailed(peerId: String, fid: String) {
        inFlight[peerId]?.remove(fid)
        scope.launch {
            lock.withLock {
                try { db.outboxRow(fid)?.let { o -> if (o.sentTo.remove(peerId)) db.outboxSentTo(fid, o.sentTo) } } catch (e: Exception) { Log.e(tag, "échec de remise", e) }
            }
        }
    }

    /** Publie la liste des voisins seulement si quelque chose de visible a changé (pas à chaque annonce Bluetooth). */
    private fun publishPeers() {
        val l = peerMap.values.sortedBy { it.name ?: it.id }
        val old = peers.value
        if (l.size != old.size || l.indices.any { l[it].copy(lastSeen = 0) != old[it].copy(lastSeen = 0) }) peers.value = l
    }

    /**
     * Inventaire : avant d'envoyer à ce voisin ce qui attend pour lui, on lui donne la liste des identifiants (16 octets
     * chacun) ; il répond avec ceux qu'il a déjà, et on ne les lui renvoie pas. Rien n'est demandé s'il y a peu à envoyer.
     * Une ancienne version ne répond pas : on attend 4 s une seule fois, puis on envoie tout comme avant.
     */
    private suspend fun askInventory(peerId: String) {
        if (peerId in invSilent) { invWait.remove(peerId); return }
        // Une question par minute et par voisin au plus (une rencontre ouvre souvent 2 ou 3 liaisons d'affilée).
        val t = System.currentTimeMillis()
        if (t - (invAskedAt[peerId] ?: 0L) < INV_EVERY_MS) { invWait.remove(peerId); return }
        invAskedAt[peerId] = t
        val fids = lock.withLock {
            db.outbox().filter { peerId !in it.sentTo && it.size > 300 && it.type != Frame.SOS && it.type != Frame.ACK }.take(INV_MAX).map { it.fid }
        }
        if (fids.size < INV_MIN) { invWait.remove(peerId); return }
        invAsked[peerId] = fids.toHashSet()
        val payload = java.io.ByteArrayOutputStream(1 + fids.size * 16).apply { write(INV_ASK); for (f in fids) write(Hex.dec(f)) }.toByteArray()
        val deadline = System.currentTimeMillis() + INV_WAIT_MS
        invWait[peerId] = deadline
        if (!sendTo(peerId, Frame(Frame.INV, 0, 0, 0, Frame.newId(), myIdBytes, Hex.dec(peerId), payload).encode())) { invWait.remove(peerId, deadline); return }
        scope.launch {
            delay(INV_WAIT_MS + 100)
            // Toujours en attente de CETTE question : pas de réponse -> ancienne version, on ne lui redemandera pas.
            if (invWait.remove(peerId, deadline)) { invSilent.add(peerId); flush() }
        }
    }

    /** Trame d'inventaire reçue : une question (je réponds avec ce que j'ai déjà) ou une réponse (je marque « déjà chez lui »). */
    private suspend fun onInventory(f: Frame, fromPeer: String) {
        val p = f.payload
        if (p.isEmpty() || (p.size - 1) % 16 != 0) return
        val n = minOf((p.size - 1) / 16, INV_MAX)
        val fids = (0 until n).map { Hex.enc(p.copyOfRange(1 + it * 16, 17 + it * 16)) }
        when (p[0].toInt()) {
            INV_ASK -> {
                invSilent.remove(fromPeer) // il parle l'inventaire : on pourra lui poser la question aussi
                val have = fids.filter { recentSeen.containsKey(it) || db.seen(it) }
                val out = java.io.ByteArrayOutputStream(1 + have.size * 16).apply { write(INV_HAVE); for (h in have) write(Hex.dec(h)) }.toByteArray()
                sendTo(fromPeer, Frame(Frame.INV, 0, 0, 0, Frame.newId(), myIdBytes, Hex.dec(fromPeer), out).encode())
            }
            INV_HAVE -> {
                invSilent.remove(fromPeer)
                // Seulement ce que j'ai demandé : une réponse non sollicitée ne peut rien faire marquer.
                val asked = invAsked.remove(fromPeer) ?: return
                lock.withLock {
                    for (fid in fids) if (fid in asked) db.outboxRow(fid)?.let { o -> if (o.sentTo.add(fromPeer)) db.outboxSentTo(fid, o.sentTo) }
                }
                invWait.remove(fromPeer)
                requestFlush()
            }
        }
    }

    /** Demande un passage de la file d'attente ; les demandes en rafale n'en font qu'un. */
    private fun requestFlush() {
        if (flushQueued.compareAndSet(false, true)) scope.launch { flushQueued.set(false); flush() }
    }

    private fun backlog(peerId: String): Int = transports.sumOf { it.backlog(peerId) }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        val connected = connectedPeerIds()
        for (p in peerMap.values.toList()) {
            if (p.id in connected) { peerMap[p.id] = p.copy(lastSeen = now, linked = true, limited = false); continue }
            if (p.linked) peerMap[p.id] = p.copy(linked = false) // la liaison prouvée est tombée
            if (now - p.lastSeen > 150_000) { peerMap.remove(p.id); onPeerChange?.invoke(p, false) }
        }
        publishPeers()
    }

    // ---------------- réception ----------------
    private suspend fun receive(f: Frame, fromPeer: String, replay: Boolean = false, heldSince: Long = 0L) {
        val fid = f.fidHex
        if (f.fromHex == me.id) return
        // Une trame directe (carte, photo de profil, mise à jour…) ne peut venir que du voisin qui la remet :
        // personne ne peut l'envoyer au nom d'un autre. Seul l'appel passe par un relais.
        if (!f.relayable && f.type != Frame.CALL && f.fromHex != fromPeer) return
        if (f.type == Frame.AUTH) return // preuve d'identité : traitée par les transports
        if (f.type == Frame.INV) { onInventory(f, fromPeer); return }
        val now = System.currentTimeMillis()
        if (f.relayable && !replay) {
            if (recentSeen.containsKey(fid) || db.seen(fid)) {
                // Déjà reçu : si c'est un message privé pour moi dont j'ai déjà accusé réception, l'expéditeur n'a peut-être
                // pas eu mon accusé -> je le renvoie (une fois par minute au plus, même s'il m'arrive par dix voisins).
                if (f.type == Frame.DM && f.toHex == me.id && now - (lastReAck[fid] ?: 0L) > 60_000 && (db.acked(fid) || db.message(fid) != null)) {
                    lastReAck[fid] = now
                    send(ackFrame(f.from, f.fid, f.hops))
                }
                return
            }
            if (recentSeen.size > 5000) { recentSeen.entries.removeAll { now - it.value > 600_000 }; lastReAck.entries.removeAll { now - it.value > 600_000 } }
        }
        val forMe = f.isBroadcast || f.toHex == me.id
        // Appel via moi : je passe la trame au destinataire si j'ai une prise Wi-Fi avec lui (un seul relais).
        if (f.type == Frame.CALL && !forMe) {
            if (f.hops == 0 && hasWifiLink(f.toHex)) { val fwd = f.forwarded(); sendTo(f.toHex, fwd.encode()) }
            return
        }
        var handled = true
        if (forMe) {
            handled = try { handler?.invoke(f, fromPeer) ?: true } catch (e: Exception) { Log.e(tag, "traitement", e); true }
        }
        // Dédoublonnage : une trame pour moi que je n'ai pas pu traiter (clé pas encore reçue) est mise de côté et rejouée plus tard.
        if (f.relayable) {
            // Demande de contact non signée (version 1.5.0) arrivée par un relais : refusée, mais pas notée « déjà vue »,
            // pour que la même trame soit acceptée quand elle arrivera en direct de son auteur.
            val retryDirect = forMe && !handled && (f.type == Frame.CONTACT_REQ || f.type == Frame.CONTACT_ACCEPT)
            if (!retryDirect) { recentSeen[fid] = now; db.markSeen(fid) }
            if (forMe && !handled && !retryDirect) db.saveHeld(fid, f.encode(), f.fromHex, fromPeer, if (heldSince > 0) heldSince else now)
        }
        if (replay) return
        // Un accusé de réception vide les files d'attente des relais — seulement s'il est signé par le destinataire du message
        // (sinon n'importe qui pouvait faire effacer un message en transit). Le mien est traité par la messagerie.
        if (ackValid(f)) {
            val target = Hex.enc(f.payload.copyOfRange(0, 16))
            val o = db.outboxRow(target)
            if (o != null && !o.mine && o.dest == f.fromHex) db.removeOutbox(target)
        }
        // Relais : tout ce qui est relayable et pas uniquement pour moi, tant qu'il reste des sauts.
        if (f.relayable && f.ttl > 0 && (f.isBroadcast || f.toHex != me.id)) {
            val fwd = f.forwarded()
            val o = Outbox(fid, fwd.encode(), fwd.toHex, now, mutableSetOf(fromPeer, f.fromHex), mine = false)
            lock.withLock { db.saveOutbox(o) }
            requestFlush() // hors du fil de réception, et une seule fois même si dix trames arrivent d'un coup
        }
    }

    // ---------------- émission ----------------
    /** Envoie une trame de ma part : elle part vers tous les voisins et reste en attente pour les suivants. */
    fun send(f: Frame) {
        val fid = f.fidHex
        recentSeen[fid] = System.currentTimeMillis()
        scope.launch {
            db.markSeen(fid)
            if (f.relayable) {
                // Les trames « techniques » (accusés, demandes de contact) sont retirées dès qu'elles sont remises au destinataire.
                val technical = f.type == Frame.ACK || f.type == Frame.CONTACT_REQ || f.type == Frame.CONTACT_ACCEPT || f.type == Frame.ECHO || f.type == Frame.READ
                val o = Outbox(fid, f.encode(), f.toHex, System.currentTimeMillis(), HashSet(), mine = !technical)
                lock.withLock { db.saveOutbox(o) }
                flush()
            } else {
                // Direct : uniquement au destinataire s'il est connecté.
                val data = f.encode()
                val dest = f.toHex
                val targets = if (f.isBroadcast) connectedPeerIds() else setOf(dest)
                for (t in targets) sendTo(t, data)
            }
        }
    }

    /** Envoi direct (sans relais ni attente) : vrai si un transport a pris la trame. */
    fun sendTo(peerId: String, data: ByteArray): Boolean {
        val type = if (data.size > 3) data[3].toInt() and 0xff else 0
        val flags = if (data.size > 6) data[6].toInt() and 0xff else 0
        // Appels, morceaux d'APK et trames marquées « Wi-Fi seulement » (vidéos) : uniquement par la prise Wi-Fi.
        // Seuls les GROS morceaux d'APK sont réservés au Wi-Fi (sauf accord Bluetooth) ; les petits messages de contrôle
        // de la mise à jour (offre, demande, accusés) passent par n'importe quelle liaison.
        val apkBig = type == Frame.APK && data.size > 2000 && (flags and Frame.FLAG_ALLOW_BLE) == 0
        if (type == Frame.CALL || apkBig || (flags and Frame.FLAG_WIFI_ONLY) != 0) {
            val t = transports.firstOrNull { it.name == Modes.WIFI } ?: return false
            // (Une mise à jour peut partir par une prise sans preuve d'identité : c'est ainsi qu'une ancienne version se met à jour.)
            return (if (type == Frame.APK) t.hasUpdateLink(peerId) else t.hasFastLink(peerId)) && t.send(peerId, data)
        }
        // Wi-Fi d'abord pour les grosses trames, Bluetooth d'abord pour les petites.
        val ordered = if (data.size > 600) transports.sortedByDescending { it.name == Modes.WIFI } else transports.sortedByDescending { it.name == Modes.BLE }
        for (t in ordered) if ((type == Frame.APK || t.connectedPeers().contains(peerId)) && t.send(peerId, data)) return true
        return false
    }

    /** Vide la file d'attente vers les voisins connectés qui n'ont pas encore reçu chaque trame. */
    suspend fun flush(): Unit = lock.withLock {
        val connected = connectedPeerIds()
        val now = System.currentTimeMillis()
        // Voisin parti : ce qui venait de lui être confié est remis en attente. Voisin toujours là et file du transport vide :
        // on considère arrivé ce qui a été confié il y a plus d'une minute. (Avant de lire la file, pour qu'elle soit à jour.)
        for ((p, m) in inFlight.entries.toList()) {
            if (p !in connected) try { rearm(p) } catch (e: Exception) { Log.e(tag, "réarmement", e) }
            else if (backlog(p) == 0) { m.entries.removeAll { now - it.value > 60_000 }; if (m.isEmpty()) inFlight.remove(p, m) }
        }
        // La liste ne contient que les tailles : le contenu d'une trame n'est lu que s'il y a quelqu'un à qui l'envoyer.
        val entries = try { db.outbox() } catch (e: Exception) { Log.e(tag, "file d'attente", e); emptyList() }
        val broadcast = Hex.enc(Frame.BROADCAST)
        // Voisins qui viennent de refuser une grosse trame (pas de prise Wi-Fi ouverte) : inutile de relire les suivantes pour eux.
        val refusedBig = HashSet<String>()
        if (wifiOnly.size > 2000) wifiOnly.clear()
        for (o in entries) try {
            // Ce que je relaie pour d'autres : 14 jours (petits messages), 3 jours (photos), 24 h (vidéos) ; le mien 30 jours (purge).
            // (Ces délais s'appliquent aussi quand personne n'est à portée.)
            val keep = when { o.size > 1_000_000 -> 24 * 3600_000L; o.size > 50_000 -> 3 * 24 * 3600_000L; else -> 14 * 24 * 3600_000L }
            if (!o.mine && now - o.created > keep) { db.removeOutbox(o.fid); continue }
            // Un test de liaison n'a de sens que tout de suite : il ne traîne pas dans les files d'attente.
            if (o.type == Frame.ECHO && now - o.created > 60_000) { db.removeOutbox(o.fid); continue }
            if (connected.isEmpty()) continue
            val isDirect = o.dest != broadcast
            var changed = false
            // Mon message privé remis au destinataire mais toujours sans accusé après 2 min : on le lui redonne (il renverra l'accusé)
            // — sauf s'il attend encore dans la file du transport (liaison lente) : inutile de l'empiler deux fois.
            if (o.mine && isDirect && o.dest in o.sentTo && o.dest in connected && backlog(o.dest) == 0) {
                val last = lastDirectSend[o.fid] ?: 0L
                val tries = directTries[o.fid] ?: 0
                val wait = minOf(120_000L shl minOf(tries, 5), 3_600_000L) // 2, 4, 8, 16, 32 min puis 1 h : plus de renvoi toutes les 2 min pendant 30 jours
                if (now - last > wait) { o.sentTo.remove(o.dest); changed = true; directTries[o.fid] = tries + 1 }
            }
            var data: ByteArray? = null
            for (p in connected) {
                if (p in o.sentTo) continue
                // Frein : on ne confie pas plus de ~500 Ko d'avance à un voisin (sinon toute la file part en mémoire
                // face à une liaison Bluetooth lente). Les petits messages passent toujours.
                if (o.size > 4096 && backlog(p) > MAX_BACKLOG) continue
                // Inventaire demandé à ce voisin : les gros messages attendent sa réponse (4 s au plus) ; les petits et les SOS partent.
                if (o.size > 300 && o.type != Frame.SOS && (invWait[p] ?: 0L) > now) continue
                if (o.size > 600 && p in refusedBig) continue
                if (o.fid in wifiOnly && !hasWifiLink(p)) continue // vidéo : Wi-Fi direct seulement
                val d = data ?: db.outboxData(o.fid)?.also { data = it } ?: break
                if (d.size > 6 && (d[6].toInt() and Frame.FLAG_WIFI_ONLY) != 0) { wifiOnly.add(o.fid); if (!hasWifiLink(p)) continue }
                if (!sendTo(p, d)) { if (d.size > 600) refusedBig.add(p) }
                else {
                    o.sentTo.add(p); changed = true
                    inFlight.getOrPut(p) { ConcurrentHashMap() }[o.fid] = now
                    if (p == o.dest) lastDirectSend[o.fid] = now
                    MeshService.instance?.messenger?.onRelayed(o, p)
                }
            }
            if (changed) db.outboxSentTo(o.fid, o.sentTo)
            // Une trame directe remise au destinataire (relais ou trame technique) n'a plus besoin d'attendre — une fois
            // qu'elle est vraiment partie (pas seulement mise en file : si la liaison tombait avant, elle était perdue).
            if (isDirect && o.dest in o.sentTo && !o.mine && inFlight[o.dest]?.containsKey(o.fid) != true) db.removeOutbox(o.fid)
        } catch (e: Exception) { Log.e(tag, "envoi ${o.fid}", e) }
        if (lastDirectSend.size > 2000) lastDirectSend.entries.removeAll { now - it.value > 3600_000 }
        if (directTries.size > 2000) directTries.clear()
        pendingCount.value = db.outboxCount()
        pendingDetail.value = db.outboxDetail()
    }

    fun myId() = me.id
    companion object {
        const val MAX_BACKLOG = 512_000
        val ACK_PREFIX = "proximo-ack-v1|".toByteArray()
        const val INV_ASK = 1; const val INV_HAVE = 2
        /** Pas d'inventaire pour moins de 4 messages à envoyer ; 200 identifiants au plus par question (3,2 Ko, passe vite en Bluetooth). */
        const val INV_MIN = 4; const val INV_MAX = 200
        const val INV_WAIT_MS = 4000L
        const val INV_EVERY_MS = 60_000L
    }
    fun myIdBytes() = myIdBytes
}
