package fr.proximo.app.mesh

/** Ce qu'un moyen de transport (Bluetooth, Wi-Fi) rapporte au cœur du maillage. */
interface TransportListener {
    /** Un voisin est visible (annonce reçue ou liaison établie). rssi = 0 si inconnu. */
    fun onPeerSeen(peerId: String, name: String?, transport: String, rssi: Int)
    /** Un voisin n'est plus joignable par ce transport. */
    fun onPeerLost(peerId: String, transport: String)
    /** Une trame complète est arrivée d'un voisin. */
    fun onFrame(data: ByteArray, peerId: String, transport: String)
    /** Le transport a changé d'état (allumé/éteint/erreur) : texte court pour l'écran. */
    fun onStatus(transport: String, enabled: Boolean, detail: String)
}

interface Transport {
    val name: String
    fun start()
    fun stop()
    /** Envoie une trame entière à un voisin connecté. Rend false si pas de liaison. */
    fun send(peerId: String, data: ByteArray): Boolean
    /** Voisins avec qui une liaison de données est ouverte. */
    fun connectedPeers(): Set<String>
    /** Change le mode (NORMAL / PORTEE / BATTERIE). */
    fun setMode(mode: Int)
    /** Vrai si une liaison à gros débit (prise Wi-Fi) est ouverte avec ce voisin. */
    fun hasFastLink(peerId: String): Boolean = false
}

object Modes {
    const val NORMAL = 0
    const val PORTEE = 1
    const val BATTERIE = 2
    const val BLE = "ble"
    const val WIFI = "wifi"
}
