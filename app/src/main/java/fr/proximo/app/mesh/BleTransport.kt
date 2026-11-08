package fr.proximo.app.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import fr.proximo.app.core.Hex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Transport Bluetooth basse consommation.
 *  - Annonce : UUID du service + mon identifiant (8 octets) dans la réponse de scan.
 *  - Liaison : GATT. Le nœud dont l'identifiant est le plus petit se connecte à l'autre
 *    (évite deux connexions croisées). Les trames sont découpées en morceaux (MTU).
 */
@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context, private val myId: String, private val myName: () -> String, private val listener: TransportListener) : Transport {
    override val name = Modes.BLE
    private val tag = "ProximoBLE"
    private val handler = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var mode = Modes.NORMAL
    @Volatile private var running = false

    /** Une liaison avec un voisin : soit je suis client (gatt != null), soit serveur (device côté serveur). */
    private inner class Link(val address: String) {
        var peerId: String? = null
        var gatt: BluetoothGatt? = null          // côté client
        var serverDevice: BluetoothDevice? = null // côté serveur
        var mtu = 23
        var ready = false
        val assembler = Fragmenter.Assembler()
        val queue = ConcurrentLinkedDeque<ByteArray>()
        @Volatile var writing = false
        var retries = 0
        var lastSeen = System.currentTimeMillis()
        var lastRx = System.currentTimeMillis()   // dernière trame reçue (battement de cœur)
        var lastPing = 0L
        var rssi = 0
        val chunk get() = (mtu - 3).coerceIn(Fragmenter.HDR + 1, 509)
    }

    private val links = ConcurrentHashMap<String, Link>()          // par adresse
    private val seenAdv = ConcurrentHashMap<String, Long>()        // adresse -> dernière annonce
    private val idByAddress = ConcurrentHashMap<String, String>()  // adresse -> identifiant annoncé
    private val lastNotified = ConcurrentHashMap<String, Pair<Long, Int>>() // adresse -> (heure, rssi) de la dernière remontée
    private val connectFailures = ConcurrentHashMap<String, Pair<Int, Long>>() // adresse -> (échecs, heure du dernier)

    override fun start() {
        if (running) return
        running = true
        val a = adapter
        if (a == null || !a.isEnabled) { listener.onStatus(name, false, "Bluetooth éteint"); return }
        if (Build.VERSION.SDK_INT < 31) {
            // Avant Android 12, le scan Bluetooth exige que la localisation du téléphone soit activée.
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val on = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { true }
            if (!on) listener.onStatus(name, false, "Active la localisation du téléphone : Android l'exige pour que le Bluetooth trouve les voisins")
        }
        try {
            startServer()
            startAdvertising()
            startScan()
            listener.onStatus(name, true, "Bluetooth actif")
            handler.postDelayed(housekeeping, 15_000)
        } catch (e: Exception) {
            Log.e(tag, "start", e); listener.onStatus(name, false, "Bluetooth : ${e.message}")
        }
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(housekeeping)
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        try { advertiser?.stopAdvertising(advCb) } catch (_: Exception) {}
        links.values.forEach { l -> try { l.gatt?.disconnect(); l.gatt?.close() } catch (_: Exception) {} }
        val lost = (links.values.mapNotNull { it.peerId } + idByAddress.values).toSet()
        links.clear(); seenAdv.clear(); idByAddress.clear(); lastNotified.clear()
        lost.forEach { listener.onPeerLost(it, name) }
        try { server?.close() } catch (_: Exception) {}
        server = null
        listener.onStatus(name, false, "Bluetooth arrêté")
    }

    override fun setMode(mode: Int) {
        if (this.mode == mode) return
        this.mode = mode
        if (running) { try { scanner?.stopScan(scanCb) } catch (_: Exception) {}; startScan() }
    }

    override fun connectedPeers(): Set<String> = links.values.filter { it.ready && it.peerId != null }.mapNotNull { it.peerId }.toSet()

    override fun send(peerId: String, data: ByteArray): Boolean {
        val link = links.values.firstOrNull { it.peerId == peerId && it.ready } ?: return false
        for (c in Fragmenter.split(data, link.chunk)) link.queue.add(c)
        pump(link)
        return true
    }

    // ---------------- annonce et scan ----------------
    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) { listener.onStatus(name, true, "Ce téléphone ne peut pas s'annoncer en Bluetooth (il voit les autres mais n'est pas vu)"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (mode == Modes.BATTERIE) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false).addServiceUuid(ParcelUuid(SERVICE)).build()
        val resp = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(ParcelUuid(SERVICE), Hex.dec(myId)).build()
        advertiser?.startAdvertising(settings, data, resp, advCb)
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { listener.onStatus(name, true, "Annonce Bluetooth refusée (code $errorCode)") }
    }

    private fun startScan() {
        scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val scanMode = when (mode) { Modes.PORTEE -> ScanSettings.SCAN_MODE_LOW_LATENCY; Modes.BATTERIE -> ScanSettings.SCAN_MODE_LOW_POWER; else -> ScanSettings.SCAN_MODE_BALANCED }
        val settings = ScanSettings.Builder().setScanMode(scanMode).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        try { scanner?.startScan(filters, settings, scanCb) } catch (e: Exception) { listener.onStatus(name, false, "Scan Bluetooth impossible : ${e.message}") }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handleAdv(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handleAdv(it) } }
        override fun onScanFailed(errorCode: Int) { listener.onStatus(name, true, "Scan Bluetooth en erreur (code $errorCode)") }
    }

    private fun handleAdv(r: ScanResult) {
        val sd = r.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
        if (sd.size != 8) return
        val peerId = Hex.enc(sd)
        if (peerId == myId) return
        val addr = r.device.address
        val now = System.currentTimeMillis()
        seenAdv[addr] = now
        idByAddress[addr] = peerId
        links[addr]?.let { it.rssi = r.rssi; it.lastSeen = now; if (it.peerId == null) it.peerId = peerId }
        // On ne remonte pas chaque annonce (jusqu'à 10 par seconde) : toutes les 2 s ou si le signal a nettement bougé.
        val last = lastNotified[addr]
        if (last == null || now - last.first > 2000 || kotlin.math.abs(last.second - r.rssi) > 6) {
            lastNotified[addr] = now to r.rssi
            listener.onPeerSeen(peerId, null, name, r.rssi)
        }
        // Le plus petit identifiant se connecte à l'autre, avec un délai croissant après des échecs.
        if (myId < peerId && links[addr] == null) {
            val f = connectFailures[addr]
            val wait = when (f?.first ?: 0) { 0 -> 0L; 1 -> 10_000L; 2 -> 30_000L; else -> 60_000L }
            if (f == null || now - f.second > wait) connectTo(r.device, peerId)
        }
    }

    // ---------------- côté client ----------------
    private fun connectTo(device: BluetoothDevice, peerId: String) {
        if (links.size >= MAX_LINKS) return
        val link = Link(device.address).also { it.peerId = peerId }
        links[device.address] = link
        Log.i(tag, "connexion vers $peerId")
        val gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, clientCb, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(ctx, false, clientCb)
        link.gatt = gatt
        handler.postDelayed({ if (!link.ready && links[device.address] === link) dropLink(link) }, 20_000)
    }

    private val clientCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = links[gatt.device.address] ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) dropLink(link)
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            links[gatt.device.address]?.mtu = mtu
            gatt.discoverServices()
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = links[gatt.device.address] ?: return
            val svc = gatt.getService(SERVICE) ?: run { dropLink(link); return }
            val tx = svc.getCharacteristic(TX) ?: run { dropLink(link); return }
            gatt.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCCD)
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(d) }
            } else markReady(link)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            links[gatt.device.address]?.let { markReady(it) }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            links[gatt.device.address]?.let { synchronized(it) { it.writing = false }; pump(it) }
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) { val v = characteristic.value ?: return; incoming(gatt.device.address, v) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            incoming(gatt.device.address, value)
        }
    }

    private fun markReady(link: Link) {
        if (link.ready) return
        link.ready = true
        sendHello(link)
        link.peerId?.let { listener.onPeerSeen(it, null, name, link.rssi) }
        link.peerId?.let { MeshService.instance?.core?.onLinkReady(it) }
    }

    /** Battement de cœur : permet de voir en 30 s qu'un voisin s'est endormi (Samsung…) au lieu d'attendre 90 s. */
    private fun sendPing(link: Link, pong: Boolean) {
        val f = Frame(Frame.PING, 0, 0, 0, Frame.newId(), Hex.dec(myId), Frame.BROADCAST, byteArrayOf(if (pong) 1 else 0))
        link.queue.add(f.encode()); pump(link)
    }

    private fun sendHello(link: Link) {
        val f = Frame(Frame.HELLO, 0, 0, 0, Frame.newId(), Hex.dec(myId), Frame.BROADCAST, myName().toByteArray())
        for (c in Fragmenter.split(f.encode(), link.chunk)) link.queue.add(c)
        pump(link)
    }

    private fun dropLink(link: Link) {
        links.remove(link.address)
        if (!link.ready && link.gatt != null) { val f = connectFailures[link.address]; connectFailures[link.address] = ((f?.first ?: 0) + 1) to System.currentTimeMillis() }
        if (link.ready) connectFailures.remove(link.address)
        try { link.gatt?.disconnect(); link.gatt?.close() } catch (_: Exception) {}
        val pending = link.queue.isNotEmpty()
        link.queue.clear()
        link.peerId?.let { id ->
            if (pending) MeshService.instance?.core?.onDeliveryFailed(id)
            if (links.values.none { it.peerId == id && it.ready }) listener.onPeerLost(id, name)
        }
    }

    // ---------------- côté serveur ----------------
    private fun startServer() {
        server = manager.openGattServer(ctx, serverCb) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(rx); svc.addCharacteristic(tx)
        txChar = tx
        server?.addService(svc)
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val link = links.getOrPut(device.address) { Link(device.address) }
                link.serverDevice = device
                link.peerId = idByAddress[device.address]
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                links[device.address]?.let { if (it.serverDevice != null) dropLink(it) }
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { links[device.address]?.mtu = mtu }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (characteristic.uuid == RX && value != null) incoming(device.address, value)
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val link = links.getOrPut(device.address) { Link(device.address).also { it.serverDevice = device } }
            link.serverDevice = device
            if (!link.ready) { link.ready = true; sendHello(link); link.peerId?.let { listener.onPeerSeen(it, null, name, link.rssi); MeshService.instance?.core?.onLinkReady(it) } }
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links[device.address]?.let { synchronized(it) { it.writing = false }; pump(it) }
        }
    }

    // ---------------- envoi / réception ----------------
    private fun pump(link: Link) {
        if (links[link.address] !== link) { link.queue.clear(); return }
        val chunk: ByteArray
        synchronized(link) {
            if (link.writing) return
            chunk = link.queue.peek() ?: return
            link.writing = true
        }
        var ok = false
        try {
            val g = link.gatt
            if (g != null) {
                val rx = g.getService(SERVICE)?.getCharacteristic(RX)
                if (rx != null) {
                    ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                    else { rx.value = chunk; rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; g.writeCharacteristic(rx) }
                }
            } else {
                val dev = link.serverDevice; val tx = txChar; val s = server
                if (dev != null && tx != null && s != null) {
                    ok = if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(dev, tx, false, chunk) == BluetoothGatt.GATT_SUCCESS
                    else { tx.value = chunk; s.notifyCharacteristicChanged(dev, tx, false) }
                }
            }
        } catch (e: Exception) { Log.w(tag, "écriture", e) }
        if (ok) { synchronized(link) { link.queue.poll() }; link.retries = 0 } // le morceau est parti ; sinon il reste en tête et sera retenté
        else {
            synchronized(link) { link.writing = false }
            if (++link.retries > 200) { Log.w(tag, "écritures refusées en série : liaison coupée"); dropLink(link) }
            else handler.postDelayed({ pump(link) }, 50)
        }
    }

    private fun incoming(address: String, chunk: ByteArray) {
        val link = links[address] ?: return
        val now = System.currentTimeMillis()
        link.lastSeen = now; link.lastRx = now
        val full = link.assembler.feed(chunk) ?: return
        val f = Frame.decode(full) ?: return
        if (f.type == Frame.PING) {
            // 0 = ping -> on répond ; 1 = pong -> rien à faire (lastRx suffit)
            if (f.payload.isNotEmpty() && f.payload[0] == 0.toByte()) sendPing(link, pong = true)
            return
        }
        if (f.type == Frame.HELLO) {
            val wasIdentified = link.peerId != null && link.ready
            link.peerId = f.fromHex
            idByAddress[address] = f.fromHex
            link.ready = true
            if (!wasIdentified) MeshService.instance?.core?.onLinkReady(f.fromHex)
            listener.onPeerSeen(f.fromHex, String(f.payload), name, link.rssi)
            return
        }
        val pid = link.peerId ?: idByAddress[address] ?: return
        listener.onFrame(full, pid, name)
    }

    private var ticks = 0
    private val housekeeping = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            for (l in links.values.toList()) {
                // Liaison ouverte mais muette depuis 45 s malgré les pings : le voisin dort ou est parti.
                if (l.ready && now - l.lastRx > 45_000) { Log.i(tag, "liaison muette avec ${l.peerId} : coupée"); dropLink(l); continue }
                if (l.ready && now - l.lastPing > 15_000) { l.lastPing = now; sendPing(l, pong = false) }
                // Voisins plus annoncés depuis 90 s : on coupe la liaison.
                val adv = seenAdv[l.address] ?: l.lastSeen
                if (now - maxOf(adv, l.lastSeen) > 90_000) dropLink(l)
            }
            for ((addr, t) in seenAdv.entries.toList()) if (now - t > 90_000) { seenAdv.remove(addr); idByAddress[addr]?.let { id -> if (links.values.none { it.peerId == id }) listener.onPeerLost(id, name) } }
            // Sur certains téléphones le scan s'arrête tout seul : on le relance de temps en temps (toutes les 30 s).
            if (++ticks % 2 == 0) { try { scanner?.stopScan(scanCb) } catch (_: Exception) {}; startScan() }
            handler.postDelayed(this, 15_000)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("7a1b0001-9b3e-4c2a-8f1d-70726f780001")
        val RX: UUID = UUID.fromString("7a1b0002-9b3e-4c2a-8f1d-70726f780001")
        val TX: UUID = UUID.fromString("7a1b0003-9b3e-4c2a-8f1d-70726f780001")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_LINKS = 7
    }
}
