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
    private val lock = Mutex()
    private val recentSeen = ConcurrentHashMap<String, Long>()

    private val peerMap = ConcurrentHashMap<String, Peer>()
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val status = MutableStateFlow<Map<String, TransportStatus>>(emptyMap())
    val pendingCount = MutableStateFlow(0)

    /** Celui qui reçoit ce qui m'est destiné (la messagerie). */
    var handler: ((Frame, String) -> Unit)? = null
    /** Prévenu quand un voisin apparaît ou disparaît (mise à jour, appels). */
    var onPeerChange: ((Peer, Boolean) -> Unit)? = null

    fun start() {
        transports.forEach { it.start() }
        scope.launch {
            while (true) { delay(30_000); flush(); expirePeers(); db.purgeOld(); pendingCount.value = db.outboxCount() }
        }
        pendingCount.value = db.outboxCount()
    }
    fun stop() { transports.forEach { it.stop() } }
    fun setMode(mode: Int) { transports.forEach { it.setMode(mode) } }

    fun peer(id: String): Peer? = peerMap[id]
    fun connectedPeerIds(): Set<String> = transports.flatMap { it.connectedPeers() }.toSet()

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
        if (old == null) { db.touchContact(peerId); onPeerChange?.invoke(p, true) }
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
        scope.launch { receive(f, peerId) }
    }

    /** Une liaison de données vient de s'ouvrir : on envoie tout de suite ce qui attend. */
    fun onLinkReady(peerId: String) {
        peerMap[peerId]?.let { peerMap[peerId] = it.copy(linked = true, lastSeen = System.currentTimeMillis()); publishPeers() }
        scope.launch { flush() }
    }

    private fun publishPeers() { peers.value = peerMap.values.sortedBy { it.name ?: it.id } }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        for (p in peerMap.values.toList()) if (now - p.lastSeen > 150_000) { peerMap.remove(p.id); onPeerChange?.invoke(p, false) }
        publishPeers()
    }

    // ---------------- réception ----------------
    private suspend fun receive(f: Frame, fromPeer: String) {
        val fid = f.fidHex
        if (f.fromHex == me.id) return
        val now = System.currentTimeMillis()
        if (f.relayable) {
            if (recentSeen.containsKey(fid) || db.seen(fid)) return
            recentSeen[fid] = now; db.markSeen(fid)
            if (recentSeen.size > 5000) recentSeen.entries.removeAll { now - it.value > 600_000 }
        }
        val forMe = f.isBroadcast || f.toHex == me.id
        if (forMe) {
            try { handler?.invoke(f, fromPeer) } catch (e: Exception) { Log.e(tag, "traitement", e) }
        }
        // Un accusé de réception vide les files d'attente de tous les relais.
        if (f.type == Frame.ACK && f.payload.size >= 16) db.removeOutbox(Hex.enc(f.payload.copyOfRange(0, 16)))
        // Relais : tout ce qui est relayable et pas uniquement pour moi, tant qu'il reste des sauts.
        if (f.relayable && f.ttl > 0 && (f.isBroadcast || f.toHex != me.id)) {
            val fwd = f.forwarded()
            val o = Outbox(fid, fwd.encode(), fwd.toHex, now, mutableSetOf(fromPeer, f.fromHex), mine = false)
            lock.withLock { db.saveOutbox(o) }
            flush()
        }
    }

    // ---------------- émission ----------------
    /** Envoie une trame de ma part : elle part vers tous les voisins et reste en attente pour les suivants. */
    fun send(f: Frame) {
        val fid = f.fidHex
        recentSeen[fid] = System.currentTimeMillis(); db.markSeen(fid)
        scope.launch {
            if (f.relayable) {
                val o = Outbox(fid, f.encode(), f.toHex, System.currentTimeMillis(), HashSet(), mine = true)
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
        for (o in db.outbox()) {
            // Ce qui n'est pas à moi n'est gardé qu'une heure ; le mien trois jours (purge).
            if (!o.mine && now - o.created > 3600_000) { db.removeOutbox(o.fid); continue }
            val isDirect = o.dest != Hex.enc(Frame.BROADCAST)
            var changed = false
            for (p in connected) {
                if (p in o.sentTo) continue
                if (sendTo(p, o.data)) { o.sentTo.add(p); changed = true; MeshService.instance?.messenger?.onRelayed(o, p) }
            }
            if (changed) db.outboxSentTo(o.fid, o.sentTo)
            // Un message direct remis en main propre au destinataire n'a plus besoin d'attendre.
            if (isDirect && o.dest in o.sentTo && o.dest in connected && !o.mine) db.removeOutbox(o.fid)
        }
        pendingCount.value = db.outboxCount()
    }

    fun myId() = me.id
    fun myIdBytes() = myIdBytes
}
