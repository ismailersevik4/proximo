package fr.proximo.app.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import fr.proximo.app.core.Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Transport Wi-Fi direct (Wi-Fi Aware / NAN).
 *  - Découverte : je publie « proximo » (mon identifiant + pseudo) et je m'abonne au même service.
 *  - Liaison : le nœud au plus petit identifiant (abonné) demande une « voie de données » vers l'autre
 *    (éditeur), qui ouvre un serveur TCP. Les trames passent ensuite dans la prise (longueur + trame).
 *  - Android 8/9 : pas de voie de données fiable -> seulement les petits messages (texte) par sendMessage.
 */
@SuppressLint("MissingPermission")
class AwareTransport(private val ctx: Context, private val myId: String, private val myName: () -> String, private val listener: TransportListener) : Transport {
    override val name = Modes.WIFI
    private val tag = "ProximoWiFi"
    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mgr: WifiAwareManager? = if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) ctx.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager else null

    private var session: WifiAwareSession? = null
    private var pub: PublishDiscoverySession? = null
    private var sub: SubscribeDiscoverySession? = null
    private var mode = Modes.NORMAL
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var serverPort = 0

    private inner class Peer(val id: String) {
        var pubHandle: PeerHandle? = null   // vu par mon abonnement (il publie)
        var subHandle: PeerHandle? = null   // il m'a écrit via ma publication (il est abonné)
        var lastSeen = System.currentTimeMillis()
        var name: String? = null
        var socket: Socket? = null
        var out: DataOutputStream? = null
        val queue = LinkedBlockingDeque<ByteArray>()
        var failures = 0
        var nextTry = 0L
        var network: Network? = null
        var cb: ConnectivityManager.NetworkCallback? = null
        var connecting = false
        val assembler = Fragmenter.Assembler()
        var msgId = 1
    }
    private val peers = ConcurrentHashMap<String, Peer>()

    val available: Boolean get() = mgr != null

    override fun start() {
        if (running) return
        running = true
        val m = mgr
        if (m == null) { listener.onStatus(name, false, "Wi-Fi direct non disponible sur ce téléphone"); return }
        if (!m.isAvailable) { listener.onStatus(name, false, "Wi-Fi direct indisponible (Wi-Fi éteint ?)"); return }
        try {
            startServer()
            m.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) { session = s; publish(); subscribe(); listener.onStatus(name, true, "Wi-Fi direct actif") }
                override fun onAttachFailed() { listener.onStatus(name, false, "Wi-Fi direct : connexion refusée") }
            }, handler)
            handler.postDelayed(housekeeping, 20_000)
        } catch (e: Exception) { Log.e(tag, "start", e); listener.onStatus(name, false, "Wi-Fi direct : ${e.message}") }
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(housekeeping)
        peers.values.forEach { closePeer(it) }
        peers.clear()
        try { pub?.close(); sub?.close(); session?.close() } catch (_: Exception) {}
        pub = null; sub = null; session = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        listener.onStatus(name, false, "Wi-Fi direct arrêté")
    }

    override fun setMode(mode: Int) { this.mode = mode }

    override fun hasFastLink(peerId: String): Boolean = peers[peerId]?.out != null

    override fun connectedPeers(): Set<String> = peers.values.filter { it.out != null || it.pubHandle != null || it.subHandle != null }.map { it.id }.toSet()

    /** Envoi : par la prise si elle est ouverte, sinon par petits messages (si la trame est courte). */
    override fun send(peerId: String, data: ByteArray): Boolean {
        val p = peers[peerId] ?: return false
        if (p.out != null) { p.queue.offer(data); return true }
        if (data.size <= 250) {
            val h = p.pubHandle; val s = sub
            if (h != null && s != null) { s.sendMessage(h, p.msgId++, data); return true }
            val h2 = p.subHandle; val pb = pub
            if (h2 != null && pb != null) { pb.sendMessage(h2, p.msgId++, data); return true }
            return false
        }
        // Trame longue sans prise : on demande la voie de données (le plus petit identifiant initie).
        if (myId < peerId) requestDataPath(p)
        return false
    }

    // ---------------- découverte ----------------
    private fun publish() {
        val cfg = PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info()).build()
        session?.publish(cfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) { pub = s }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { onMsg(peerHandle, message, fromPublish = true) }
            override fun onMessageSendFailed(messageId: Int) { onSendFailed() }
            override fun onSessionTerminated() { pub = null; if (running) handler.postDelayed({ publish() }, 3000) }
        }, handler)
    }

    private fun subscribe() {
        val cfg = SubscribeConfig.Builder().setServiceName(SERVICE).build()
        session?.subscribe(cfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) { sub = s }
            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                val i = serviceSpecificInfo ?: return
                if (i.size < 8) return
                val id = Hex.enc(i.copyOfRange(0, 8)); if (id == myId) return
                val nm = if (i.size > 8) String(i.copyOfRange(8, i.size)) else null
                val p = peers.getOrPut(id) { Peer(id) }
                p.pubHandle = peerHandle; p.lastSeen = System.currentTimeMillis(); if (nm != null) p.name = nm
                listener.onPeerSeen(id, nm, name, 0)
                // Je me signale à lui (il ne me voit peut-être pas encore) et je demande la voie de données si je suis le plus petit.
                sub?.sendMessage(peerHandle, p.msgId++, hello())
                if (myId < id) requestDataPath(p)
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { onMsg(peerHandle, message, fromPublish = false) }
            override fun onMessageSendFailed(messageId: Int) { onSendFailed() }
            override fun onSessionTerminated() { sub = null; if (running) handler.postDelayed({ subscribe() }, 3000) }
        }, handler)
    }

    private fun info(): ByteArray {
        var n = myName()
        while (n.toByteArray().size > 24 && n.isNotEmpty()) n = n.dropLast(1)
        return Hex.dec(myId) + n.toByteArray()
    }
    /** On ne sait pas quel pair a échoué : on redonne leur chance à tous les pairs sans prise. */
    private fun onSendFailed() { for (p in peers.values) if (p.out == null) MeshService.instance?.core?.onDeliveryFailed(p.id) }

    private fun hello(): ByteArray = Frame(Frame.HELLO, 0, 0, 0, Frame.newId(), Hex.dec(myId), Frame.BROADCAST, myName().toByteArray()).encode()

    private fun onMsg(peerHandle: PeerHandle, message: ByteArray, fromPublish: Boolean) {
        val f = Frame.decode(message) ?: return
        val id = f.fromHex
        if (id == myId) return
        val p = peers.getOrPut(id) { Peer(id) }
        p.lastSeen = System.currentTimeMillis()
        if (fromPublish) p.subHandle = peerHandle else p.pubHandle = peerHandle
        if (f.type == Frame.HELLO) {
            p.name = String(f.payload)
            listener.onPeerSeen(id, p.name, name, 0)
            // L'éditeur (plus grand identifiant) prépare la voie de données dès qu'un abonné se signale.
            if (fromPublish && myId > id) acceptDataPath(p)
            MeshService.instance?.core?.onLinkReady(id)
            return
        }
        listener.onFrame(message, id, name)
    }

    // ---------------- voie de données (prise TCP) ----------------
    private fun startServer() {
        try {
            server = ServerSocket(0).also { serverPort = it.localPort }
            exec.execute {
                while (running) {
                    val s = try { server?.accept() } catch (e: Exception) { null } ?: break
                    exec.execute { serve(s, null) }
                }
            }
        } catch (e: Exception) { Log.w(tag, "serveur", e) }
    }

    /** Côté éditeur : demande du réseau avec mon port (Android 10+). */
    private fun acceptDataPath(p: Peer) {
        if (Build.VERSION.SDK_INT < 29) return
        val s = pub ?: return; val h = p.subHandle ?: return
        if (p.out != null || p.connecting || System.currentTimeMillis() < p.nextTry) return
        p.connecting = true
        val spec = WifiAwareNetworkSpecifier.Builder(s, h).setPskPassphrase(PSK).setPort(serverPort).build()
        request(p, spec, initiator = false)
    }

    /** Côté abonné : demande du réseau, puis connexion à l'adresse et au port rapportés. */
    private fun requestDataPath(p: Peer) {
        if (Build.VERSION.SDK_INT < 29) return
        val s = sub ?: return; val h = p.pubHandle ?: return
        if (p.out != null || p.connecting || System.currentTimeMillis() < p.nextTry) return
        p.connecting = true
        val spec = WifiAwareNetworkSpecifier.Builder(s, h).setPskPassphrase(PSK).build()
        request(p, spec, initiator = true)
    }

    private fun releaseRequest(p: Peer) {
        try { p.cb?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        p.cb = null; p.network = null
    }

    /** Échec d'ouverture : on attend de plus en plus longtemps (5 s, 15 s, 60 s) avant de réessayer tant que le pair est visible. */
    private fun scheduleRetry(p: Peer) {
        p.connecting = false
        p.failures++
        val wait = when (p.failures) { 1 -> 5_000L; 2 -> 15_000L; else -> 60_000L }
        p.nextTry = System.currentTimeMillis() + wait
        handler.postDelayed({ if (running && peers[p.id] === p && p.out == null) { if (myId < p.id) requestDataPath(p) else acceptDataPath(p) } }, wait + 100)
    }

    private fun request(p: Peer, spec: WifiAwareNetworkSpecifier, initiator: Boolean) {
        releaseRequest(p)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { p.network = network }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!initiator) return
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val addr = info.peerIpv6Addr ?: return
                val port = info.port
                if (p.socket != null) return
                exec.execute {
                    try {
                        val sock = network.socketFactory.createSocket()
                        sock.connect(InetSocketAddress(addr, port), 8000)
                        serve(sock, p)
                    } catch (e: Exception) { Log.w(tag, "connexion prise", e); scheduleRetry(p) }
                }
            }
            override fun onLost(network: Network) { closeSocket(p); if (p.cb === this) releaseRequest(p); scheduleRetry(p) }
            override fun onUnavailable() { if (p.cb === this) releaseRequest(p); scheduleRetry(p) }
        }
        p.cb = cb
        try { cm.requestNetwork(req, cb, handler) } catch (e: Exception) { releaseRequest(p); scheduleRetry(p); Log.w(tag, "requête réseau", e) }
        handler.postDelayed({ if (p.out == null && p.cb === cb) { releaseRequest(p); scheduleRetry(p) } }, 30_000)
    }

    /** Boucle d'une prise : on s'identifie, puis on lit les trames (longueur sur 4 octets + trame). */
    private fun serve(sock: Socket, known: Peer?) {
        var peer: Peer? = known
        try {
            sock.tcpNoDelay = true
            val out = DataOutputStream(sock.getOutputStream())
            val inp = DataInputStream(sock.getInputStream())
            writeFrame(out, hello())
            val first = readFrame(inp) ?: return
            val f = Frame.decode(first) ?: return
            if (f.type != Frame.HELLO) return
            val id = f.fromHex
            val p = peers.getOrPut(id) { Peer(id) }
            p.name = String(f.payload)
            closeSocket(p)
            p.socket = sock; p.out = out; p.connecting = false; p.failures = 0; p.nextTry = 0L
            peer = p
            listener.onPeerSeen(id, p.name, name, 0)
            MeshService.instance?.core?.onLinkReady(id)
            exec.execute {
                try {
                    while (p.socket === sock) {
                        val d = p.queue.poll(1, TimeUnit.SECONDS) ?: continue
                        if (p.socket !== sock) { p.queue.offerFirst(d); break }
                        synchronized(out) { writeFrame(out, d) }
                    }
                } catch (_: Exception) {}
            }
            while (true) {
                val data = readFrame(inp) ?: break
                p.lastSeen = System.currentTimeMillis()
                listener.onFrame(data, id, name)
            }
        } catch (e: Exception) { Log.i(tag, "prise fermée : ${e.message}") }
        finally {
            try { sock.close() } catch (_: Exception) {}
            peer?.let { if (it.socket === sock) { it.socket = null; it.out = null } }
        }
    }

    private fun writeFrame(out: DataOutputStream, data: ByteArray) { out.writeInt(data.size); out.write(data); out.flush() }
    private fun readFrame(inp: InputStream): ByteArray? {
        val din = if (inp is DataInputStream) inp else DataInputStream(inp)
        val len = try { din.readInt() } catch (e: Exception) { return null }
        if (len <= 0 || len > 4_000_000) return null
        val buf = ByteArray(len)
        try { din.readFully(buf) } catch (e: Exception) { return null }
        return buf
    }

    private fun closeSocket(p: Peer) {
        val had = p.socket != null
        try { p.socket?.close() } catch (_: Exception) {}
        p.socket = null; p.out = null
        if (had) { MeshService.instance?.core?.onDeliveryFailed(p.id); listener.onPeerSeen(p.id, p.name, name, 0) }
    }
    private fun closePeer(p: Peer) {
        closeSocket(p)
        releaseRequest(p)
    }

    private val housekeeping = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            for (p in peers.values.toList()) {
                if (p.out != null) { p.lastSeen = now; listener.onPeerSeen(p.id, p.name, name, 0); continue }
                if (now - p.lastSeen > 120_000) { closePeer(p); peers.remove(p.id); listener.onPeerLost(p.id, name) }
            }
            // Le pseudo peut avoir changé : on republie.
            if (pub != null) try { pub?.updatePublish(PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info()).build()) } catch (_: Exception) {}
            handler.postDelayed(this, if (mode == Modes.BATTERIE) 60_000 else 20_000)
        }
    }

    companion object {
        const val SERVICE = "proximo"
        const val PSK = "proximo-maillage-2026"
    }
}
