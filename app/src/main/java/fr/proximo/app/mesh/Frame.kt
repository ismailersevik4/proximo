package fr.proximo.app.mesh

import fr.proximo.app.core.Crypto
import fr.proximo.app.core.Hex
import java.nio.ByteBuffer

/**
 * Trame qui circule de téléphone en téléphone.
 *
 *  'P' 'X' | ver | type | ttl | hops | flags | fid(16) | from(8) | to(8) | len(4) | payload
 *
 *  - fid   : identifiant unique de la trame (sert au dédoublonnage et aux accusés de réception)
 *  - from  : nœud émetteur d'origine ; to : destinataire (8 octets à zéro = tout le monde)
 *  - ttl   : nombre de sauts restants ; hops : sauts déjà faits
 */
class Frame(
    val type: Int,
    var ttl: Int,
    var hops: Int,
    val flags: Int,
    val fid: ByteArray,
    val from: ByteArray,
    val to: ByteArray,
    val payload: ByteArray,
) {
    val fidHex: String get() = Hex.enc(fid)
    val fromHex: String get() = Hex.enc(from)
    val toHex: String get() = Hex.enc(to)
    val isBroadcast: Boolean get() = to.all { it.toInt() == 0 }
    /** Les trames directes (appels, mises à jour) ne sont jamais relayées. */
    val relayable: Boolean get() = type !in DIRECT_ONLY

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size)
        b.put('P'.code.toByte()).put('X'.code.toByte()).put(VERSION.toByte()).put(type.toByte())
        b.put(ttl.toByte()).put(hops.toByte()).put(flags.toByte())
        b.put(fid).put(from).put(to)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    /** Copie pour relais : un saut de plus, un ttl de moins. */
    fun forwarded(): Frame = Frame(type, ttl - 1, hops + 1, flags, fid, from, to, payload)

    companion object {
        const val VERSION = 1
        const val HEADER = 2 + 1 + 1 + 1 + 1 + 1 + 16 + 8 + 8 + 4
        const val MAX_PAYLOAD = 4_000_000
        const val DEFAULT_TTL = 7

        // Types de trames
        const val HELLO = 1          // mon identifiant + pseudo, dès qu'on se connecte
        const val CARD_REQ = 2       // demande de carte (clés)
        const val CARD = 3           // ma carte signée
        const val CONTACT_REQ = 4    // demande d'ajout en contact (carte incluse)
        const val CONTACT_ACCEPT = 5 // acceptation (carte incluse)
        const val DM = 6             // message privé chiffré
        const val GROUP = 7          // message de groupe chiffré
        const val PUBLIC = 8         // « Tout le monde », signé
        const val ACK = 9            // accusé de réception
        const val SOS = 10           // alerte prioritaire, signée
        const val CALL = 11          // signalisation d'appel (direct uniquement)
        const val APK = 12           // partage de mise à jour (direct uniquement)
        const val PING = 13          // mesure de présence (direct uniquement)

        val DIRECT_ONLY = setOf(HELLO, CARD_REQ, CARD, CALL, APK, PING)
        val BROADCAST = ByteArray(8)

        fun newId(): ByteArray = Crypto.random(16)

        fun decode(data: ByteArray): Frame? {
            if (data.size < HEADER) return null
            if (data[0] != 'P'.code.toByte() || data[1] != 'X'.code.toByte() || data[2].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(data)
            b.position(3)
            val type = b.get().toInt() and 0xff
            val ttl = b.get().toInt() and 0xff
            val hops = b.get().toInt() and 0xff
            val flags = b.get().toInt() and 0xff
            val fid = ByteArray(16); b.get(fid)
            val from = ByteArray(8); b.get(from)
            val to = ByteArray(8); b.get(to)
            val len = b.int
            if (len < 0 || len > MAX_PAYLOAD || data.size < HEADER + len) return null
            val payload = ByteArray(len); b.get(payload)
            return Frame(type, ttl, hops, flags, fid, from, to, payload)
        }

        /** Longueur totale annoncée par un en-tête (pour lire un flux). */
        fun totalLength(header: ByteArray): Int {
            if (header.size < HEADER) return -1
            val len = ByteBuffer.wrap(header, HEADER - 4, 4).int
            return HEADER + len
        }
    }
}

/**
 * Découpage d'une trame en morceaux pour le Bluetooth (petits paquets) et recollage à l'arrivée.
 *   'F' | fid(16) | index(2) | total(2) | morceau
 */
object Fragmenter {
    const val HDR = 1 + 16 + 2 + 2

    fun split(frame: ByteArray, maxChunk: Int): List<ByteArray> {
        if (frame.size <= maxChunk) return listOf(frame)
        require(maxChunk > HDR) { "morceau trop petit" }
        val fid = frame.copyOfRange(7, 23)
        val body = maxChunk - HDR
        val total = (frame.size + body - 1) / body
        val out = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val start = i * body
            val end = minOf(frame.size, start + body)
            val b = ByteBuffer.allocate(HDR + (end - start))
            b.put('F'.code.toByte()).put(fid).putShort(i.toShort()).putShort(total.toShort()).put(frame, start, end - start)
            out.add(b.array())
        }
        return out
    }

    private class Partial(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var got = 0
        var last = System.currentTimeMillis()
    }

    /** Un assembleur par liaison (un par voisin et par transport). */
    class Assembler {
        private val partials = HashMap<String, Partial>()

        /** Rend la trame complète quand tous les morceaux sont là, sinon null. */
        @Synchronized
        fun feed(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null
            if (chunk[0] == 'P'.code.toByte()) return chunk
            if (chunk[0] != 'F'.code.toByte() || chunk.size < HDR) return null
            val b = ByteBuffer.wrap(chunk)
            b.get()
            val fid = ByteArray(16); b.get(fid)
            val idx = b.short.toInt() and 0xffff
            val total = b.short.toInt() and 0xffff
            if (total == 0 || idx >= total) return null
            val key = Hex.enc(fid)
            val p = partials.getOrPut(key) { Partial(total) }
            if (p.total != total) { partials.remove(key); return null }
            if (p.parts[idx] == null) { p.parts[idx] = chunk.copyOfRange(HDR, chunk.size); p.got++ }
            p.last = System.currentTimeMillis()
            if (p.got < total) { cleanup(); return null }
            partials.remove(key)
            var size = 0; for (x in p.parts) size += x!!.size
            val out = ByteArray(size); var pos = 0
            for (x in p.parts) { System.arraycopy(x!!, 0, out, pos, x.size); pos += x.size }
            return out
        }

        private fun cleanup() {
            val now = System.currentTimeMillis()
            partials.entries.removeAll { now - it.value.last > 60_000 }
        }
    }
}
