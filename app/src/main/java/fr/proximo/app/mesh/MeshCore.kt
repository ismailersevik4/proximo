package fr.proximo.app.mesh

import android.content.Context
import android.util.Log
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Réception sérialisée : une trame à la fois, dans l'ordre d'arrivée (pas de doublons ni de désordre). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val rx = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private val recentSeen = ConcurrentHashMap<String, Long>()
    /** Heure du dernier envoi direct au destinataire d'un de mes messages (pour renvoyer si pas d'accusé). */
    private val lastDirectSend = ConcurrentHashMap<String, Long>()

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
            while (true) { delay(30_000); flush(); expirePeers(); db.purgeOld(); pendingCount.value = db.outboxCount() }
        }
        scope.launch { pendingCount.value = db.outboxCount() }
    }
    fun stop() { scope.cancel(); transports.forEach { it.stop() } }
    fun setMode(mode: Int) { transports.forEach { it.setMode(mode) } }
    /** Relance un transport (Bluetooth ou Wi-Fi rallumé). */
    fun restart(name: String) { transports.filter { it.name == name }.forEach { t -> try { t.stop() } catch (_: Exception) {}; try { t.start() } catch (e: Exception) { Log.w(tag, "relance $name", e) } } }

    fun peer(id: String): Peer? = peerMap[id]
    fun connectedPeerIds(): Set<String> = transports.flatMap { it.connectedPeers() }.toSet()
    /** Vrai si une prise Wi-Fi direct (gros débit) est ouverte avec ce voisin. */
    fun hasWifiLink(peerId: String): Boolean = transports.any { it.name == Modes.WIFI && it.hasFastLink(peerId) }
    /** Voisins avec une prise Wi-Fi direct ouverte. */
    fun wifiLinkedPeers(): List<String> = peerMap.keys.filter { hasWifiLink(it) }

    // ---------------- transports -> cœur ----------------
    override fun onPeerSeen(peerId: String, name: String?, transport: String, rssi: Int) {
        val old = peerMap[peerId]
        val linked = connectedPeerIds().contains(peerId)
        val p = Peer(
            id = peerId,
            name = name ?: old?.name ?: db.contact(peerId)?.name,
            lastSeen = System.currentTimeMillis(),
            ble = (transport == Modes.BLE) || (old?.ble == true),
            wifi = (transport == Modes.WIFI) || (old?.wifi == true),
            rssi = if (transport == Modes.BLE && rssi != 0) rssi else (old?.rssi ?: 0),
            linked = linked,
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

    override fun onFrame(data: ByteArray, peerId: String, transport: String) {
        val f = Frame.decode(data) ?: return
        scope.launch(rx) { try { receive(f, peerId) } catch (e: Exception) { Log.e(tag, "réception", e) } }
    }

    /** Rejoue les trames mises de côté (clé reçue, contact accepté, groupe rejoint). from = null : toutes. */
    fun replayHeld(from: String?) {
        scope.launch(rx) {
            for ((fid, data, peer) in db.held(from)) {
                db.removeHeld(fid)
                val f = Frame.decode(data) ?: continue
                try { receive(f, peer, replay = true) } catch (e: Exception) { Log.e(tag, "rejeu", e) }
            }
        }
    }

    /** Une liaison de données vient de s'ouvrir : on envoie tout de suite ce qui attend, et on prévient la messagerie. */
    fun onLinkReady(peerId: String) {
        val p = (peerMap[peerId] ?: Peer(peerId, db.contact(peerId)?.name, System.currentTimeMillis(), false, false, 0, true)).copy(linked = true, lastSeen = System.currentTimeMillis())
        peerMap[peerId] = p
        publishPeers()
        scope.launch { delay(300); onLinkUp?.invoke(p); flush() }
    }

    /** Une liaison est tombée alors que des trames étaient en cours d'envoi : elles seront renvoyées à ce voisin. */
    fun onDeliveryFailed(peerId: String) {
        scope.launch {
            lock.withLock {
                try { for (o in db.outbox()) if (o.sentTo.remove(peerId)) db.outboxSentTo(o.fid, o.sentTo) } catch (e: Exception) { Log.e(tag, "échec de remise", e) }
            }
        }
    }

    private fun publishPeers() { peers.value = peerMap.values.sortedBy { it.name ?: it.id } }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        val connected = connectedPeerIds()
        for (p in peerMap.values.toList()) {
            if (p.id in connected) { peerMap[p.id] = p.copy(lastSeen = now, linked = true); continue }
            if (now - p.lastSeen > 150_000) { peerMap.remove(p.id); onPeerChange?.invoke(p, false) }
        }
        publishPeers()
    }

    // ---------------- réception ----------------
    private suspend fun receive(f: Frame, fromPeer: String, replay: Boolean = false) {
        val fid = f.fidHex
        if (f.fromHex == me.id) return
        val now = System.currentTimeMillis()
        if (f.relayable && !replay) {
            if (recentSeen.containsKey(fid) || db.seen(fid)) {
                // Déjà reçu : si c'est un message privé pour moi, l'expéditeur n'a peut-être pas eu mon accusé -> je le renvoie.
                if (f.type == Frame.DM && f.toHex == me.id && db.message(fid) != null) {
                    send(Frame(Frame.ACK, Frame.DEFAULT_TTL, 0, 0, Frame.newId(), myIdBytes, f.from, f.fid + byteArrayOf(f.hops.toByte())))
                }
                return
            }
            if (recentSeen.size > 5000) recentSeen.entries.removeAll { now - it.value > 600_000 }
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
            recentSeen[fid] = now; db.markSeen(fid)
            if (forMe && !handled) db.saveHeld(fid, f.encode(), f.fromHex, fromPeer)
        }
        if (replay) return
        // Un accusé de réception vide les files d'attente de tous les relais.
        if (f.type == Frame.ACK && f.payload.size >= 16) db.removeOutbox(Hex.enc(f.payload.copyOfRange(0, 16)))
        // Relais : tout ce qui est relayable et pas uniquement pour moi, tant qu'il reste des sauts.
        if (f.relayable && f.ttl > 0 && (f.isBroadcast || f.toHex != me.id)) {
            val fwd = f.forwarded()
            val o = Outbox(fid, fwd.encode(), fwd.toHex, now, mutableSetOf(fromPeer, f.fromHex), mine = false)
            lock.withLock { db.saveOutbox(o) }
            scope.launch { flush() } // hors du fil de réception : il relit la file (parfois des Mo) et ne doit pas retarder les trames suivantes
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
                val technical = f.type == Frame.ACK || f.type == Frame.CONTACT_REQ || f.type == Frame.CONTACT_ACCEPT
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
            return t.hasFastLink(peerId) && t.send(peerId, data)
        }
        // Wi-Fi d'abord pour les grosses trames, Bluetooth d'abord pour les petites.
        val ordered = if (data.size > 600) transports.sortedByDescending { it.name == Modes.WIFI } else transports.sortedByDescending { it.name == Modes.BLE }
        for (t in ordered) if (t.connectedPeers().contains(peerId) && t.send(peerId, data)) return true
        return false
    }

    /** Vide la file d'attente vers les voisins connectés qui n'ont pas encore reçu chaque trame. */
    suspend fun flush() = lock.withLock {
        val connected = connectedPeerIds()
        if (connected.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val entries = try { db.outbox() } catch (e: Exception) { Log.e(tag, "file d'attente", e); emptyList() }
        for (o in entries) try {
            // Ce que je relaie pour d'autres : 14 jours (petits messages), 3 jours (photos), 24 h (vidéos) ; le mien 30 jours (purge).
            val keep = when { o.data.size > 1_000_000 -> 24 * 3600_000L; o.data.size > 50_000 -> 3 * 24 * 3600_000L; else -> 14 * 24 * 3600_000L }
            if (!o.mine && now - o.created > keep) { db.removeOutbox(o.fid); continue }
            val isDirect = o.dest != Hex.enc(Frame.BROADCAST)
            var changed = false
            // Mon message privé remis au destinataire mais toujours sans accusé après 2 min : on le lui redonne (il renverra l'accusé).
            if (o.mine && isDirect && o.dest in o.sentTo && o.dest in connected) {
                val last = lastDirectSend[o.fid] ?: 0L
                if (now - last > 120_000) { o.sentTo.remove(o.dest); changed = true }
            }
            for (p in connected) {
                if (p in o.sentTo) continue
                if (sendTo(p, o.data)) { o.sentTo.add(p); changed = true; if (p == o.dest) lastDirectSend[o.fid] = now; MeshService.instance?.messenger?.onRelayed(o, p) }
            }
            if (changed) db.outboxSentTo(o.fid, o.sentTo)
            // Une trame directe remise au destinataire (relais ou trame technique) n'a plus besoin d'attendre.
            if (isDirect && o.dest in o.sentTo && !o.mine) db.removeOutbox(o.fid)
        } catch (e: Exception) { Log.e(tag, "envoi ${o.fid}", e) }
        pendingCount.value = db.outboxCount()
        pendingDetail.value = db.outboxDetail()
    }

    fun myId() = me.id
    fun myIdBytes() = myIdBytes
}
