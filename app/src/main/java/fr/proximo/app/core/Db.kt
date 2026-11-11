package fr.proximo.app.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Un contact (téléphone ajouté). status : 1 = accepté, 2 = demande reçue en attente, 0 = demande envoyée. */
data class Contact(val id: String, val name: String, val dhPub: ByteArray, val sigPub: ByteArray, val status: Int, val added: Long, val lastSeen: Long,
    /** Contact bloqué : plus rien de lui n'arrive (messages, demandes, appels, wizz) et sa discussion est cachée. */
    val blocked: Boolean = false) {
    /** En contact ET pas bloqué : tout ce qui demande « un contact accepté » refuse donc un contact bloqué. */
    val accepted get() = status == 1 && !blocked
}

data class Group(val id: String, val name: String, val key: ByteArray, val owner: String, val members: List<String>, val created: Long)

/** Un message affiché dans une discussion. */
data class Message(
    val mid: String, val chat: String, val from: String, val mine: Boolean, val kind: String,
    val body: String, val file: String?, val time: Long, val status: Int, val read: Boolean, val route: String,
    /** Pseudo de l'expéditeur tel qu'il s'est présenté (utile pour « Tout le monde » et les non-contacts). */
    val senderName: String = "",
    /** Heure d'arrivée sur ce téléphone (0 = inconnue / message à moi). */
    val received: Long = 0L,
) {
    /** Arrivé plus d'une heure après avoir été écrit : à signaler. */
    val late: Boolean get() = !mine && received > 0 && received - time > 3600_000L
    /** Heure qui range le message dans la discussion : son arrivée sur ce téléphone (ou son envoi, pour les miens). */
    val shownAt: Long get() = if (received > 0) received else time
    companion object {
        const val PENDING = 0; const val RELAYED = 1; const val DELIVERED = 2; const val FAILED = 3
        const val TEXT = "text"; const val PHOTO = "photo"; const val VOICE = "voice"; const val VIDEO = "video"; const val LOC = "loc"; const val SOS = "sos"; const val SYS = "sys"; const val WIZZ = "wizz"
    }
}

data class ChatSummary(val chat: String, val name: String, val last: Message?, val unread: Int, val isGroup: Boolean)

/** Trame rangée en attente (pas encore remise ou à relayer plus tard). */
data class Outbox(val fid: String, val data: ByteArray, val dest: String, val created: Long, val sentTo: MutableSet<String>, val mine: Boolean,
    /** Taille du contenu sur le disque (la liste de la file ne charge pas le contenu : voir Db.outboxData). */
    val size: Long = data.size.toLong(),
    /** Type de la trame (Frame.SOS, Frame.DM…), 0 si inconnu : les SOS passent en tête de file. */
    val type: Int = if (data.size > 3) data[3].toInt() and 0xff else 0)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "proximo.db", null, 6) {
    /** Les gros contenus (file d'attente, trames mises de côté) sont en fichiers : SQLite refuse les lignes de plus de 2 Mo. */
    private val outboxDir = java.io.File(ctx.filesDir, "outbox").also { it.mkdirs() }
    private val heldDir = java.io.File(ctx.filesDir, "held").also { it.mkdirs() }
    /** Compteur qui change à chaque écriture : l'interface se rafraîchit en l'observant. */
    val changes = MutableStateFlow(0L)
    private fun bump() { changes.update { it + 1 } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT, dh BLOB, sg BLOB, status INTEGER, added INTEGER, last_seen INTEGER, blocked INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE pending_codes(code TEXT PRIMARY KEY, added INTEGER)")
        db.execSQL("CREATE TABLE groups(id TEXT PRIMARY KEY, name TEXT, key BLOB, owner TEXT, members TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE messages(mid TEXT PRIMARY KEY, chat TEXT, from_id TEXT, mine INTEGER, kind TEXT, body TEXT, file TEXT, time INTEGER, status INTEGER, read INTEGER, route TEXT, sender_name TEXT DEFAULT '', received INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_msg_chat ON messages(chat, time)")
        db.execSQL("CREATE TABLE seen(fid TEXT PRIMARY KEY, t INTEGER)")
        db.execSQL("CREATE TABLE outbox(fid TEXT PRIMARY KEY, data BLOB, dest TEXT, created INTEGER, sent_to TEXT, mine INTEGER, type INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE held(fid TEXT PRIMARY KEY, from_id TEXT, peer TEXT, t INTEGER)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE acked(fid TEXT PRIMARY KEY, t INTEGER)")
        db.execSQL("CREATE INDEX idx_msg_unread ON messages(chat) WHERE read=0 AND mine=0")
        db.execSQL("CREATE INDEX idx_msg_arrival ON messages(chat, $ARRIVAL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE messages ADD COLUMN sender_name TEXT DEFAULT ''")
        if (o < 3) { db.execSQL("CREATE TABLE IF NOT EXISTS held(fid TEXT PRIMARY KEY, from_id TEXT, peer TEXT, t INTEGER)"); db.execSQL("DELETE FROM outbox") }
        if (o < 4) db.execSQL("ALTER TABLE messages ADD COLUMN received INTEGER DEFAULT 0")
        if (o < 5) {
            // Accusés déjà envoyés (pour les renvoyer si l'expéditeur ne les a pas eus), index des non-lus,
            // et retrait des invitations de groupe qui s'affichaient comme des messages.
            db.execSQL("CREATE TABLE IF NOT EXISTS acked(fid TEXT PRIMARY KEY, t INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_unread ON messages(chat) WHERE read=0 AND mine=0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_arrival ON messages(chat, $ARRIVAL)")
            db.execSQL("DELETE FROM messages WHERE kind='ginv'")
        }
        if (o < 6) {
            // Contacts bloqués, et type des trames en attente (pour faire passer les SOS en premier).
            db.execSQL("ALTER TABLE contacts ADD COLUMN blocked INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE outbox ADD COLUMN type INTEGER DEFAULT 0")
            // Les trames déjà en attente reçoivent leur type (lu dans leur fichier) : un SOS en file avant la mise à jour passe en tête lui aussi.
            try {
                db.rawQuery("SELECT fid FROM outbox", null).use { c ->
                    while (c.moveToNext()) try {
                        val fid = c.getString(0)
                        java.io.File(outboxDir, "$fid.bin").inputStream().use { s ->
                            val h = ByteArray(4)
                            if (s.read(h) == 4) db.execSQL("UPDATE outbox SET type=? WHERE fid=?", arrayOf<Any>(h[3].toInt() and 0xff, fid))
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    // ---------- contacts ----------
    fun contacts(): List<Contact> = q("SELECT $CONTACT_COLS FROM contacts ORDER BY name COLLATE NOCASE") { readContact(it) }
    fun contact(id: String): Contact? = q("SELECT $CONTACT_COLS FROM contacts WHERE id=?", id) { readContact(it) }.firstOrNull()
    fun acceptedContacts(): List<Contact> = contacts().filter { it.accepted }
    private fun readContact(c: Cursor) = Contact(c.getString(0), c.getString(1), c.getBlob(2), c.getBlob(3), c.getInt(4), c.getLong(5), c.getLong(6), c.getInt(7) == 1)

    /** Bloquer : sa discussion est marquée lue et cachée, plus rien de lui n'arrive. Débloquer : tout redevient comme avant. */
    fun setBlocked(id: String, blocked: Boolean) {
        writableDatabase.execSQL("UPDATE contacts SET blocked=? WHERE id=?", arrayOf(if (blocked) 1 else 0, id))
        if (blocked) writableDatabase.execSQL("UPDATE messages SET read=1 WHERE chat=? AND read=0", arrayOf(id))
        bump()
    }

    fun saveContact(card: Card, status: Int) {
        val cv = ContentValues().apply {
            put("id", card.id); put("name", card.name); put("dh", card.dhPub); put("sg", card.sigPub); put("status", status)
            put("added", System.currentTimeMillis()); put("last_seen", 0L)
        }
        val existing = contact(card.id)
        if (existing != null) {
            // On ne rétrograde jamais un contact accepté ; on met à jour pseudo et clés si la carte est plus récente.
            cv.put("status", if (existing.status == 1) 1 else status); cv.put("added", existing.added); cv.put("last_seen", existing.lastSeen)
            writableDatabase.update("contacts", cv, "id=?", arrayOf(card.id))
        } else writableDatabase.insert("contacts", null, cv)
        bump()
    }
    fun setContactStatus(id: String, status: Int) { writableDatabase.execSQL("UPDATE contacts SET status=? WHERE id=?", arrayOf(status, id)); bump() }
    fun touchContact(id: String) { writableDatabase.execSQL("UPDATE contacts SET last_seen=? WHERE id=?", arrayOf(System.currentTimeMillis(), id)) }
    fun renameContact(id: String, name: String) { writableDatabase.execSQL("UPDATE contacts SET name=? WHERE id=?", arrayOf(name, id)); bump() }
    fun deleteContact(id: String) {
        deleteFilesOf(id)
        writableDatabase.delete("contacts", "id=?", arrayOf(id)); writableDatabase.delete("messages", "chat=?", arrayOf(id)); bump()
    }
    /** Photos, vocaux et vidéos d'une discussion qu'on supprime. */
    private fun deleteFilesOf(chat: String) {
        for (f in q("SELECT file FROM messages WHERE chat=? AND file IS NOT NULL", chat) { it.getString(0) }) try { java.io.File(f).delete() } catch (_: Exception) {}
    }

    fun addPendingCode(code: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO pending_codes VALUES(?,?)", arrayOf(code, System.currentTimeMillis())); bump() }
    fun pendingCodes(): List<String> = q("SELECT code FROM pending_codes") { it.getString(0) }
    fun removePendingCode(code: String) { writableDatabase.delete("pending_codes", "code=?", arrayOf(code)); bump() }

    // ---------- groupes ----------
    fun groups(): List<Group> = q("SELECT * FROM groups ORDER BY name COLLATE NOCASE") { readGroup(it) }
    fun group(id: String): Group? = q("SELECT * FROM groups WHERE id=?", id) { readGroup(it) }.firstOrNull()
    private fun readGroup(c: Cursor): Group {
        val arr = JSONArray(c.getString(4)); val m = ArrayList<String>(); for (i in 0 until arr.length()) m.add(arr.getString(i))
        return Group(c.getString(0), c.getString(1), c.getBlob(2), c.getString(3), m, c.getLong(5))
    }
    fun saveGroup(g: Group) {
        val cv = ContentValues().apply {
            put("id", g.id); put("name", g.name); put("key", g.key); put("owner", g.owner); put("members", JSONArray(g.members).toString()); put("created", g.created)
        }
        writableDatabase.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteGroup(id: String) { deleteFilesOf(id); writableDatabase.delete("groups", "id=?", arrayOf(id)); writableDatabase.delete("messages", "chat=?", arrayOf(id)); bump() }

    // ---------- messages ----------
    fun messages(chat: String, limit: Int = 500): List<Message> =
        q("SELECT * FROM messages WHERE chat=? ORDER BY $ARRIVAL DESC LIMIT $limit", chat) { readMsg(it) }.asReversed()
    fun message(mid: String): Message? = q("SELECT * FROM messages WHERE mid=?", mid) { readMsg(it) }.firstOrNull()
    private fun readMsg(c: Cursor) = Message(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5), c.getString(6), c.getLong(7), c.getInt(8), c.getInt(9) == 1, c.getString(10) ?: "", c.getString(11) ?: "", c.getLong(12))

    fun saveMessage(m: Message) {
        val cv = ContentValues().apply {
            put("mid", m.mid); put("chat", m.chat); put("from_id", m.from); put("mine", if (m.mine) 1 else 0); put("kind", m.kind)
            put("body", m.body); put("file", m.file); put("time", m.time); put("status", m.status); put("read", if (m.read) 1 else 0); put("route", m.route); put("sender_name", m.senderName); put("received", m.received)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun setStatus(mid: String, status: Int, route: String? = null) {
        // « Reçu » l'emporte toujours, même sur un ancien « Échec » (accusé arrivé très tard).
        val expr = if (status == Message.DELIVERED) "?" else "MAX(status,?)"
        if (route != null) writableDatabase.execSQL("UPDATE messages SET status=$expr, route=? WHERE mid=?", arrayOf(status, route, mid))
        else writableDatabase.execSQL("UPDATE messages SET status=$expr WHERE mid=?", arrayOf(status, mid))
        bump()
    }
    /** Un message jamais parti (personne à portée) : « Échec », sans toucher à ceux déjà relayés ou reçus. */
    fun failIfPending(mid: String) { writableDatabase.execSQL("UPDATE messages SET status=? WHERE mid=? AND status=?", arrayOf(Message.FAILED, mid, Message.PENDING)); bump() }
    fun markRead(chat: String) {
        val n = writableDatabase.update("messages", ContentValues().apply { put("read", 1) }, "chat=? AND read=0", arrayOf(chat))
        if (n > 0) bump() // rien à marquer : on ne fait pas relire tous les écrans pour rien
    }
    fun unreadTotal(): Int = q("SELECT COUNT(*) FROM messages WHERE read=0 AND mine=0") { it.getInt(0) }.firstOrNull() ?: 0
    fun deleteMessage(mid: String) {
        message(mid)?.file?.let { java.io.File(it).delete() }
        writableDatabase.delete("messages", "mid=?", arrayOf(mid)); bump()
    }
    fun clearChat(chat: String) {
        for (f in q("SELECT file FROM messages WHERE chat=? AND file IS NOT NULL", chat) { it.getString(0) }) java.io.File(f).delete()
        writableDatabase.delete("messages", "chat=?", arrayOf(chat)); bump()
    }

    fun chatSummaries(): List<ChatSummary> {
        val out = ArrayList<ChatSummary>()
        fun add(chat: String, name: String, isGroup: Boolean) {
            val last = q("SELECT * FROM messages WHERE chat=? ORDER BY $ARRIVAL DESC LIMIT 1", chat) { readMsg(it) }.firstOrNull()
            val unread = q("SELECT COUNT(*) FROM messages WHERE chat=? AND read=0 AND mine=0", chat) { it.getInt(0) }.firstOrNull() ?: 0
            out.add(ChatSummary(chat, name, last, unread, isGroup))
        }
        add(ALL, "Tout le monde", true)
        groups().forEach { add(it.id, it.name, true) }
        val all = contacts()
        all.filter { it.status != 0 && !it.blocked }.forEach { add(it.id, it.name, false) }
        // Discussions qui ont des messages reçus mais ne sont ni un groupe ni un contact (messages privés d'inconnus) :
        // sans cette ligne, elles restaient introuvables et le compteur de non-lus ne redescendait jamais.
        val known = out.map { it.chat }.toSet() + all.filter { it.blocked }.map { it.id } // un contact bloqué reste caché
        q("SELECT chat, MAX(sender_name) FROM messages WHERE mine=0 GROUP BY chat") { it.getString(0) to (it.getString(1) ?: "") }
            .filter { it.first !in known }.forEach { (c, n) -> add(c, n.ifBlank { "Inconnu" }, false) }
        return out.sortedWith(compareByDescending<ChatSummary> { it.chat == ALL }.thenByDescending { it.last?.shownAt ?: 0L })
    }

    // ---------- dédoublonnage ----------
    fun seen(fid: String): Boolean = q("SELECT 1 FROM seen WHERE fid=?", fid) { 1 }.isNotEmpty()
    fun markSeen(fid: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO seen VALUES(?,?)", arrayOf(fid, System.currentTimeMillis()))
    }
    fun purgeOld() {
        val d = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val old = (System.currentTimeMillis() - 40L * 24 * 3600 * 1000).toString()
        writableDatabase.delete("seen", "t<?", arrayOf(old))
        writableDatabase.delete("acked", "t<?", arrayOf(old))
        var changed = false
        // Mes messages jamais remis après 30 jours : marqués « Échec » (ils ne repartiront plus).
        for (fid in q("SELECT fid FROM outbox WHERE created<? AND mine=1", d.toString()) { it.getString(0) }) {
            val n = writableDatabase.update("messages", ContentValues().apply { put("status", Message.FAILED) }, "mid=? AND status=?", arrayOf(fid, Message.PENDING.toString()))
            if (n > 0) changed = true
        }
        for (fid in q("SELECT fid FROM outbox WHERE created<?", d.toString()) { it.getString(0) }) removeOutbox(fid)
        val h = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (fid in q("SELECT fid FROM held WHERE t<?", h.toString()) { it.getString(0) }) removeHeld(fid)
        capHeld()
        capOutbox()
        if (changed) bump() // l'interface ne se rafraîchit que si un statut a vraiment changé
    }

    /** Les trames mises de côté ne comptaient dans aucun plafond : au-delà de 30 Mo, les plus anciennes partent. */
    private fun capHeld() {
        var total = (heldDir.listFiles() ?: return).sumOf { it.length() }
        if (total <= HELD_CAP) return
        for (fid in q("SELECT fid FROM held ORDER BY t") { it.getString(0) }) {
            if (total <= HELD_CAP) break
            total -= java.io.File(heldDir, "$fid.bin").length(); removeHeld(fid)
        }
    }

    /** Plafond de stockage des relais (300 Mo) : on efface d'abord ce qui n'est pas à moi, le plus lourd et le plus vieux. */
    private fun capOutbox() {
        val files = (outboxDir.listFiles() ?: return).toList()
        var total = files.sumOf { it.length() }
        if (total <= OUTBOX_CAP) return
        val mine = q("SELECT fid FROM outbox WHERE mine=1") { it.getString(0) }.toSet()
        val candidates = files.filter { it.nameWithoutExtension !in mine }.sortedWith(compareByDescending<java.io.File> { it.length() > 500_000 }.thenBy { it.lastModified() })
        for (f in candidates) { if (total <= OUTBOX_CAP) break; total -= f.length(); removeOutbox(f.nameWithoutExtension) }
    }

    // ---------- file d'attente (contenu en fichiers) ----------
    /**
     * La file d'attente SANS le contenu des trames (seulement leur taille) : on la relit très souvent, et charger
     * des dizaines de Mo de photos et vidéos à chaque passage finissait par saturer la mémoire. Le contenu se lit
     * au moment d'envoyer, avec [outboxData].
     */
    fun outbox(): List<Outbox> {
        // Les SOS d'abord, puis du plus récent au plus ancien.
        val rows = q("SELECT fid, dest, created, sent_to, mine, type FROM outbox ORDER BY (type=10) DESC, created DESC") { readOutboxRow(it) }
        val out = ArrayList<Outbox>(rows.size)
        for (r in rows) {
            val f = java.io.File(outboxDir, r.fid + ".bin")
            if (!f.exists()) { writableDatabase.delete("outbox", "fid=?", arrayOf(r.fid)); continue }
            out.add(r.copy(size = f.length()))
        }
        return out
    }
    private fun readOutboxRow(c: Cursor): Outbox {
        val set = HashSet<String>(); val arr = JSONArray(c.getString(3)); for (i in 0 until arr.length()) set.add(arr.getString(i))
        return Outbox(c.getString(0), ByteArray(0), c.getString(1), c.getLong(2), set, c.getInt(4) == 1, 0L, c.getInt(5))
    }
    /** Une ligne de la file (sans contenu), ou null. */
    fun outboxRow(fid: String): Outbox? = q("SELECT fid, dest, created, sent_to, mine, type FROM outbox WHERE fid=?", fid) { readOutboxRow(it) }.firstOrNull()
    /** Le contenu d'une trame en attente, lu au moment de l'envoyer. */
    fun outboxData(fid: String): ByteArray? = try { java.io.File(outboxDir, "$fid.bin").takeIf { it.exists() }?.readBytes() } catch (e: Exception) { null }
    fun outboxCount(): Int = q("SELECT COUNT(*) FROM outbox") { it.getInt(0) }.firstOrNull() ?: 0
    /**
     * (mes messages privés en attente de « Reçu », trames en transit pour d'autres).
     * Les messages de « Tout le monde » et de groupe n'ont jamais d'accusé : ils ne comptent pas comme « en attente de Reçu ».
     */
    fun outboxDetail(): Pair<Int, Int> {
        val mine = q("SELECT COUNT(*) FROM messages WHERE mine=1 AND status<2 AND kind!='sys' AND chat!='$ALL' AND chat NOT IN (SELECT id FROM groups) AND mid IN (SELECT fid FROM outbox)") { it.getInt(0) }.firstOrNull() ?: 0
        val others = q("SELECT COUNT(*) FROM outbox WHERE mine=0") { it.getInt(0) }.firstOrNull() ?: 0
        return mine to others
    }

    // ---------- accusés déjà envoyés ----------
    fun markAcked(fid: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO acked VALUES(?,?)", arrayOf(fid, System.currentTimeMillis())) }
    fun acked(fid: String): Boolean = q("SELECT 1 FROM acked WHERE fid=?", fid) { 1 }.isNotEmpty()
    fun saveOutbox(o: Outbox) {
        java.io.File(outboxDir, o.fid + ".bin").writeBytes(o.data)
        val cv = ContentValues().apply {
            put("fid", o.fid); put("data", ByteArray(0)); put("dest", o.dest); put("created", o.created); put("sent_to", JSONArray(o.sentTo.toList()).toString()); put("mine", if (o.mine) 1 else 0); put("type", o.type)
        }
        writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun outboxSentTo(fid: String, sentTo: Set<String>) { writableDatabase.execSQL("UPDATE outbox SET sent_to=? WHERE fid=?", arrayOf(JSONArray(sentTo.toList()).toString(), fid)) }
    fun removeOutbox(fid: String) { writableDatabase.delete("outbox", "fid=?", arrayOf(fid)); java.io.File(outboxDir, "$fid.bin").delete() }

    // ---------- trames mises de côté (clé du contact pas encore reçue) ----------
    /** t = date de la première mise de côté : un rejeu ne remet pas le délai de 7 jours à zéro. */
    fun saveHeld(fid: String, data: ByteArray, from: String, peer: String, t: Long = System.currentTimeMillis()) {
        java.io.File(heldDir, "$fid.bin").writeBytes(data)
        writableDatabase.execSQL("INSERT OR REPLACE INTO held VALUES(?,?,?,?)", arrayOf(fid, from, peer, t))
    }
    /** (identifiant, voisin qui l'a remise, date) des trames de cet expéditeur (null = toutes), sans leur contenu. */
    fun heldIds(from: String?): List<Triple<String, String, Long>> =
        if (from == null) q("SELECT fid, peer, t FROM held ORDER BY t") { Triple(it.getString(0), it.getString(1), it.getLong(2)) }
        else q("SELECT fid, peer, t FROM held WHERE from_id=? ORDER BY t", from) { Triple(it.getString(0), it.getString(1), it.getLong(2)) }
    fun heldData(fid: String): ByteArray? = try { java.io.File(heldDir, "$fid.bin").takeIf { it.exists() }?.readBytes() } catch (e: Exception) { null }
    fun isHeld(fid: String): Boolean = q("SELECT 1 FROM held WHERE fid=?", fid) { 1 }.isNotEmpty()
    fun removeHeld(fid: String) { writableDatabase.delete("held", "fid=?", arrayOf(fid)); java.io.File(heldDir, "$fid.bin").delete() }

    // ---------- réglages divers ----------
    fun kv(k: String): String? = q("SELECT v FROM kv WHERE k=?", k) { it.getString(0) }.firstOrNull()
    fun kv(k: String, v: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES(?,?)", arrayOf(k, v)); bump() }

    private fun <T> q(sql: String, vararg args: String, read: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(read(c)) }
        return out
    }

    companion object {
        const val ALL = "all"
        const val OUTBOX_CAP = 300L * 1024 * 1024
        const val HELD_CAP = 30L * 1024 * 1024
        private const val CONTACT_COLS = "id, name, dh, sg, status, added, last_seen, blocked"
        /** Tri des discussions par heure d'arrivée sur ce téléphone : un message arrivé en retard se range en bas, là où on le voit. */
        private const val ARRIVAL = "CASE WHEN received>0 THEN received ELSE time END"
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
        fun jsonObj(s: String): JSONObject = try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }
}
